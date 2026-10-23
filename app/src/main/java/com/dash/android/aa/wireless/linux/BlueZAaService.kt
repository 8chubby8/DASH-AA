package com.dash.android.aa.wireless.linux

import android.util.Log
import com.dash.android.aa.wireless.PhoneLink
import com.dash.android.aa.wireless.PhoneService
import com.dash.android.aa.wireless.WirelessSetup
import com.dash.android.transport.bluetooth.linux.RfcommSocket
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant

/**
 * DASH-AA's wireless Android Auto service on Bluetooth (1.2.3): offered through BlueZ's profile manager,
 * so a paired phone that connects finds it and opens it, and BlueZ hands DASH the connection.
 *
 * Offered only while wireless Android Auto can actually happen — switched on, and DASH's own Wi-Fi up on
 * 5 GHz — so a phone never tries a car that cannot answer. [offer] and [withdraw] are idempotent.
 *
 * The connection arrives as a file descriptor over the system bus, which needs a bus transport that
 * passes them (junixsocket's; the JDK's own unix sockets cannot). BlueZ only hands it over for a
 * paired device (`RequireAuthentication`), so pairing in Connections › Bluetooth is the trust.
 */
class BlueZAaService(private val onPhone: (PhoneLink) -> Unit) : PhoneService {
    private var bus: DBusConnection? = null
    @Volatile private var offered = false

    @Synchronized
    override fun offer(): String? {
        if (offered) return null
        return try {
            val c = bus ?: DBusConnectionBuilder.forSystemBus().withShared(false).build().also {
                it.exportObject(PROFILE_PATH, profile)
                bus = it
            }
            val manager = c.getRemoteObject(BLUEZ, "/org/bluez", ProfileManager1::class.java)
            runCatching { manager.UnregisterProfile(DBusPath(PROFILE_PATH)) }
            manager.RegisterProfile(
                DBusPath(PROFILE_PATH),
                WirelessSetup.SERVICE_UUID,
                mapOf(
                    "Name" to Variant("Android Auto Wireless"),
                    "Role" to Variant("server"),
                    "Channel" to Variant(UInt16(CHANNEL)),
                    "RequireAuthentication" to Variant(true),
                    "RequireAuthorization" to Variant(false),
                ),
            )
            offered = true
            Log.i(TAG, "wireless Android Auto offered on Bluetooth (RFCOMM channel $CHANNEL)")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "could not offer wireless Android Auto on Bluetooth: ${e.message}")
            "Bluetooth would not take Android Auto's service (${e.message ?: e.javaClass.simpleName})"
        }
    }

    @Synchronized
    override fun withdraw() {
        if (!offered) return
        offered = false
        runCatching { bus?.getRemoteObject(BLUEZ, "/org/bluez", ProfileManager1::class.java)?.UnregisterProfile(DBusPath(PROFILE_PATH)) }
        Log.i(TAG, "wireless Android Auto withdrawn from Bluetooth")
    }

    private val profile = object : Profile1 {
        override fun getObjectPath() = PROFILE_PATH
        override fun isRemote() = false
        override fun Release() { offered = false }
        override fun RequestDisconnection(device: DBusPath) = Unit

        override fun NewConnection(device: DBusPath, fd: FileDescriptor, properties: Map<String, Variant<*>>) {
            val address = device.path.substringAfterLast("dev_").replace('_', ':')
            val socket = RfcommSocket.adopt(fd.intFileDescriptor, address)
            // The bus's own thread: the conversation with the phone goes elsewhere.
            Thread({ onPhone(PhoneLink(address, socket.inputStream, socket.outputStream, socket)) }, "dash-aa-wireless").apply { isDaemon = true }.start()
        }
    }

    private companion object {
        const val TAG = "DashAaWireless"
        const val BLUEZ = "org.bluez"
        const val PROFILE_PATH = "/com/dash/android/aa_wireless"
        /** The channel the open wireless adapters use; any free one would do. */
        const val CHANNEL = 8
    }
}

@DBusInterfaceName("org.bluez.ProfileManager1")
@Suppress("FunctionName")
interface ProfileManager1 : DBusInterface {
    fun RegisterProfile(profile: DBusPath, uuid: String, options: Map<String, @JvmSuppressWildcards Variant<*>>)
    fun UnregisterProfile(profile: DBusPath)
}

@DBusInterfaceName("org.bluez.Profile1")
@Suppress("FunctionName")
interface Profile1 : DBusInterface {
    fun Release()
    fun NewConnection(device: DBusPath, fd: FileDescriptor, properties: Map<String, @JvmSuppressWildcards Variant<*>>)
    fun RequestDisconnection(device: DBusPath)
}
