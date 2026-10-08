package com.dash.android.connections.linux

import android.util.Log
import com.dash.android.connections.BluetoothDevice
import com.dash.android.connections.BluetoothState
import com.dash.android.connections.BluetoothSystem
import com.dash.android.connections.DeviceKind
import com.dash.android.connections.PairingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DASH-AA's Bluetooth (1.1.6): BlueZ, over the system message bus, as the seat user — BlueZ's own
 * policy lets the person at the machine do all of this, with no password and no root.
 *
 * **DASH is BlueZ's pairing agent while it runs** (`KeyboardDisplay`, made the default). Every question
 * BlueZ would put to a desktop's Bluetooth panel — do these codes match, what is the PIN — becomes a
 * [PairingRequest] for DASH to show, answered by the person or refused after [ANSWER_SECONDS]. On the
 * laptop this takes over from GNOME's agent while DASH is open; when DASH closes, BlueZ goes back to it.
 *
 * Devices paired through DASH are **trusted**, so a phone or module reconnects on its own next time.
 *
 * Every BlueZ call is made on one worker thread, never the caller's; the [state] is rebuilt from BlueZ's
 * own list whenever BlueZ reports a change.
 */
class BlueZBluetooth : BluetoothSystem {
    private val _state = MutableStateFlow(BluetoothState(available = false, note = "Looking for Bluetooth…"))
    override val state: StateFlow<BluetoothState> = _state
    private val _request = MutableStateFlow<PairingRequest?>(null)
    override val request: StateFlow<PairingRequest?> = _request

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dash-bluetooth").apply { isDaemon = true } }
    private var bus: DBusConnection? = null
    private var adapterPath: String? = null
    private var refreshQueued: ScheduledFuture<*>? = null
    private var searchStop: ScheduledFuture<*>? = null
    private var visibleStop: ScheduledFuture<*>? = null

    /** The answer the agent is waiting on, completed by [answer]; null is "no". */
    @Volatile private var waiting: CompletableFuture<String?>? = null

    override fun start() {
        worker.execute {
            val c = runCatching { DBusConnectionBuilder.forSystemBus().withShared(false).build() }.getOrElse {
                Log.w(TAG, "no system bus: ${it.message}")
                _state.value = BluetoothState(false, "DASH cannot reach the machine's services (the system message bus), so Bluetooth is not available.")
                return@execute
            }
            bus = c
            runCatching {
                c.addSigHandler(ObjectManager.InterfacesAdded::class.java) { s -> if (s.objectPath.startsWith("/org/bluez")) queueRefresh() }
                c.addSigHandler(ObjectManager.InterfacesRemoved::class.java) { s -> if (s.objectPath.startsWith("/org/bluez")) queueRefresh() }
                c.addSigHandler(Properties.PropertiesChanged::class.java) { s ->
                    if (!s.path.startsWith("/org/bluez")) return@addSigHandler
                    if (s.interfaceName == DEVICE && s.propertiesChanged["Paired"]?.value == true) worker.execute { trust(s.path) }
                    queueRefresh()
                }
                // BlueZ restarted (or started late): its agent list starts empty, so register again.
                c.addSigHandler(DBus.NameOwnerChanged::class.java) { s ->
                    if (s.name == BLUEZ && s.newOwner.isNotEmpty()) worker.schedule({ registerAgent(); refresh() }, 500, TimeUnit.MILLISECONDS)
                }
                c.exportObject(AGENT_PATH, agent)
            }.onFailure { Log.w(TAG, "could not watch BlueZ: ${it.message}") }
            registerAgent()
            refresh()
        }
    }

    private fun registerAgent() {
        val c = bus ?: return
        runCatching {
            val manager = c.getRemoteObject(BLUEZ, "/org/bluez", AgentManager1::class.java)
            runCatching { manager.UnregisterAgent(DBusPath(AGENT_PATH)) }
            manager.RegisterAgent(DBusPath(AGENT_PATH), "KeyboardDisplay")
            manager.RequestDefaultAgent(DBusPath(AGENT_PATH))
            Log.i(TAG, "DASH is the Bluetooth pairing agent")
        }.onFailure { Log.w(TAG, "could not become the pairing agent: ${it.message}") }
    }

    private fun queueRefresh() {
        refreshQueued?.cancel(false)
        refreshQueued = worker.schedule({ refresh() }, 150, TimeUnit.MILLISECONDS)
    }

    /** Rebuild [state] from BlueZ's own list. On the worker thread; a device BlueZ describes oddly never stops it. */
    private fun refresh() {
        runCatching { rebuild() }.onFailure { Log.w(TAG, "could not read BlueZ: ${it.message}") }
    }

    private fun rebuild() {
        val c = bus ?: return
        val objects = runCatching { c.getRemoteObject(BLUEZ, "/", ObjectManager::class.java).GetManagedObjects() }.getOrElse {
            adapterPath = null
            _state.value = BluetoothState(false, "Bluetooth's service (BlueZ) is not running on this machine, so there is no Bluetooth.")
            return
        }
        val adapter = objects.entries.firstOrNull { ADAPTER in it.value }
        if (adapter == null) {
            adapterPath = null
            _state.value = BluetoothState(false, "This machine has no Bluetooth adapter, or it is switched off by a hardware switch or airplane mode.")
            return
        }
        adapterPath = adapter.key.path
        val a = adapter.value.getValue(ADAPTER)
        val devices = objects.entries
            .filter { it.key.path.startsWith(adapter.key.path + "/") && DEVICE in it.value }
            .mapNotNull { (_, ifaces) ->
                val d = ifaces.getValue(DEVICE)
                val address = d.str("Address") ?: return@mapNotNull null
                val name = d.str("Name")
                val paired = d.bool("Paired") || d.bool("Bonded")
                // A nameless device heard while searching is noise (beacons, watches, other cars); it is
                // shown only once it is paired.
                if (name == null && !paired) return@mapNotNull null
                val label = d.str("Alias") ?: name ?: address
                BluetoothDevice(
                    address = address,
                    name = label,
                    kind = DeviceKind.of(label, (d["Class"]?.value as? UInt32)?.toInt(), d.str("Icon")),
                    paired = paired,
                    connected = d.bool("Connected"),
                    rssi = (d["RSSI"]?.value as? Number)?.toInt(),
                )
            }
            .sortedWith(compareByDescending<BluetoothDevice> { it.connected }.thenBy { it.name.lowercase() })
        _state.value = _state.value.copy(
            available = true,
            note = "",
            powered = a.bool("Powered"),
            name = a.str("Alias") ?: a.str("Name") ?: "",
            address = a.str("Address") ?: "",
            visible = a.bool("Discoverable"),
            searching = a.bool("Discovering"),
            devices = devices,
        )
    }

    // ---- What the tab asks for ----

    override fun setPowered(on: Boolean): Unit = adapterSet("Powered", on, if (on) "switch Bluetooth on" else "switch Bluetooth off")

    override fun setName(name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) adapterSet("Alias", n, "change the name")
    }

    override fun setVisible(on: Boolean): Unit = worker.execute {
        val path = adapterPath ?: return@execute
        visibleStop?.cancel(false)
        runBlueZ("make this machine ${if (on) "visible" else "hidden"}") {
            val p = props(path)
            if (on) {
                p.Set(ADAPTER, "PairableTimeout", Variant(UInt32(BluetoothSystem.VISIBLE_SECONDS.toLong())))
                p.Set(ADAPTER, "DiscoverableTimeout", Variant(UInt32(BluetoothSystem.VISIBLE_SECONDS.toLong())))
                p.Set(ADAPTER, "Pairable", Variant(true))
            }
            p.Set(ADAPTER, "Discoverable", Variant(on))
            if (!on) p.Set(ADAPTER, "Pairable", Variant(false))
        }
        refresh()
    }

    override fun search(on: Boolean): Unit = worker.execute {
        val path = adapterPath ?: return@execute
        searchStop?.cancel(false)
        runBlueZ(if (on) "search for devices" else "stop searching") {
            val adapter = bus!!.getRemoteObject(BLUEZ, path, Adapter1::class.java)
            if (on) {
                adapter.StartDiscovery()
                searchStop = worker.schedule({ search(false) }, BluetoothSystem.SEARCH_SECONDS.toLong(), TimeUnit.SECONDS)
            } else if (_state.value.searching) {
                adapter.StopDiscovery()
            }
        }
        refresh()
    }

    override fun pair(address: String): Unit = worker.execute {
        val path = devicePath(address) ?: return@execute
        // Searching slows pairing on most radios.
        if (_state.value.searching) runCatching { bus!!.getRemoteObject(BLUEZ, adapterPath!!, Adapter1::class.java).StopDiscovery() }
        _state.value = _state.value.copy(pairing = address, failure = null)
        val device = bus!!.getRemoteObject(BLUEZ, path, Device1::class.java)
        // Pairing waits on the person (the codes, a PIN) for longer than an ordinary call is allowed to
        // take, so it is asked for and then waited on here, with BlueZ's own limit on top.
        val reply = bus!!.callMethodAsync(device, "Pair")
        val until = System.currentTimeMillis() + PAIR_SECONDS * 1000L
        while (!reply.hasReply() && System.currentTimeMillis() < until) Thread.sleep(100)
        val failure = runCatching { reply.reply }.exceptionOrNull()
        if (failure == null && reply.hasReply()) {
            trust(path)
            val kind = _state.value.devices.firstOrNull { it.address == address }?.kind
            // A phone or a speaker is connected straight away; a module is reached by its own transport.
            if (kind == DeviceKind.PHONE || kind == DeviceKind.AUDIO) runCatching { device.Connect() }
            _state.value = _state.value.copy(pairing = null)
        } else {
            if (!reply.hasReply()) runCatching { device.CancelPairing() }
            _state.value = _state.value.copy(pairing = null, failure = plain(failure, "pair with it", timedOut = !reply.hasReply()))
        }
        _request.value = null
        refresh()
    }

    override fun connect(address: String): Unit = deviceCall(address, "connect to it") { d ->
        val reply = bus!!.callMethodAsync(d, "Connect")
        val until = System.currentTimeMillis() + 30_000
        while (!reply.hasReply() && System.currentTimeMillis() < until) Thread.sleep(100)
        reply.reply
    }

    override fun disconnect(address: String): Unit = deviceCall(address, "disconnect it") { it.Disconnect() }

    override fun forget(address: String): Unit = worker.execute {
        val path = devicePath(address) ?: return@execute
        runBlueZ("forget it") { bus!!.getRemoteObject(BLUEZ, adapterPath!!, Adapter1::class.java).RemoveDevice(DBusPath(path)) }
        refresh()
    }

    override fun answer(request: PairingRequest, accept: Boolean, typed: String?) {
        if (_request.value != request) return
        _request.value = null
        waiting?.complete(if (accept) (typed ?: "") else null)
    }

    // ---- The agent: BlueZ asking DASH ----

    private val agent = object : Agent1 {
        override fun getObjectPath() = AGENT_PATH
        override fun isRemote() = false

        override fun Release() = Unit

        override fun RequestPinCode(device: DBusPath): String =
            ask(PairingRequest.EnterPin(addressOf(device), nameOf(device)))?.takeIf { it.isNotBlank() } ?: throw rejected()

        override fun DisplayPinCode(device: DBusPath, pincode: String) {
            _request.value = PairingRequest.Show(addressOf(device), nameOf(device), pincode)
        }

        override fun RequestPasskey(device: DBusPath): UInt32 =
            ask(PairingRequest.EnterPasskey(addressOf(device), nameOf(device)))?.toLongOrNull()?.let { UInt32(it) } ?: throw rejected()

        override fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16) {
            _request.value = PairingRequest.Show(addressOf(device), nameOf(device), passkey.toLong().toString().padStart(6, '0'))
        }

        override fun RequestConfirmation(device: DBusPath, passkey: UInt32) {
            ask(PairingRequest.Confirm(addressOf(device), nameOf(device), passkey.toLong().toString().padStart(6, '0'))) ?: throw rejected()
        }

        override fun RequestAuthorization(device: DBusPath) {
            ask(PairingRequest.Allow(addressOf(device), nameOf(device))) ?: throw rejected()
        }

        /** A paired device reaching for one of its services: it is paired, so it may. */
        override fun AuthorizeService(device: DBusPath, uuid: String) = Unit

        override fun Cancel() {
            _request.value = null
            waiting?.complete(null)
        }
    }

    /** Put [r] to the person and wait for the answer (null for no), on BlueZ's call thread. */
    private fun ask(r: PairingRequest): String? {
        waiting?.complete(null)
        val f = CompletableFuture<String?>()
        waiting = f
        _request.value = r
        return runCatching { f.get(ANSWER_SECONDS.toLong(), TimeUnit.SECONDS) }.getOrNull().also {
            if (_request.value == r) _request.value = null
        }
    }

    private fun rejected() = DBusExecutionException("Rejected by the person at DASH").apply { setType("org.bluez.Error.Rejected") }

    // ---- Helpers ----

    private fun trust(path: String) {
        runCatching { props(path).Set(DEVICE, "Trusted", Variant(true)) }
    }

    private fun adapterSet(name: String, value: Any, what: String): Unit = worker.execute {
        val path = adapterPath ?: return@execute
        runBlueZ(what) { props(path).Set(ADAPTER, name, Variant(value)) }
        refresh()
    }

    private fun deviceCall(address: String, what: String, call: (Device1) -> Unit): Unit = worker.execute {
        val path = devicePath(address) ?: return@execute
        runBlueZ(what) { call(bus!!.getRemoteObject(BLUEZ, path, Device1::class.java)) }
        refresh()
    }

    private fun runBlueZ(what: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { _state.value = _state.value.copy(failure = null) }
            .onFailure { e ->
                Log.w(TAG, "$what: ${e.message}")
                _state.value = _state.value.copy(failure = plain(e, what))
            }
    }

    private fun props(path: String) = bus!!.getRemoteObject(BLUEZ, path, Properties::class.java)

    private fun devicePath(address: String): String? =
        adapterPath?.let { "$it/dev_" + address.uppercase().replace(':', '_') }

    private fun addressOf(device: DBusPath) = device.path.substringAfterLast("dev_").replace('_', ':')

    private fun nameOf(device: DBusPath): String =
        _state.value.devices.firstOrNull { it.address == addressOf(device) }?.name
            ?: runCatching { props(device.path).Get<String>(DEVICE, "Alias") }.getOrNull()
            ?: addressOf(device)

    private fun Map<String, Variant<*>>.str(key: String) = (this[key]?.value as? String)?.takeIf { it.isNotBlank() }
    private fun Map<String, Variant<*>>.bool(key: String) = this[key]?.value == true

    companion object {
        private const val TAG = "DashBluetooth"
        private const val BLUEZ = "org.bluez"
        private const val ADAPTER = "org.bluez.Adapter1"
        private const val DEVICE = "org.bluez.Device1"
        private const val AGENT_PATH = "/com/dash/android/agent"

        /** How long a pairing question waits for the person before it is refused. */
        const val ANSWER_SECONDS = 30
        private const val PAIR_SECONDS = 60

        /** BlueZ's error, in plain words. */
        internal fun plain(e: Throwable?, what: String, timedOut: Boolean = false): String {
            if (timedOut) return "Could not $what: no answer in time."
            val type = (e as? DBusExecutionException)?.type ?: ""
            val text = "$type ${e?.message ?: ""}"
            return when {
                "AuthenticationFailed" in text -> "Could not $what: the codes did not match, or the PIN was wrong."
                "AuthenticationCanceled" in text || "Rejected" in text || "AuthenticationRejected" in text -> "Could not $what: pairing was turned down."
                "AuthenticationTimeout" in text -> "Could not $what: the other device did not answer in time."
                "ConnectionAttemptFailed" in text || "Page Timeout" in text || "HostDown" in text || "host-down" in text ->
                    "Could not $what: it did not answer. Is it switched on, nearby, and ready to pair?"
                "AlreadyExists" in text -> "It is already paired."
                "InProgress" in text -> "Already doing that — wait a moment."
                "NotReady" in text -> "Could not $what: Bluetooth is switched off."
                "Blocked" in text || "rfkill" in text -> "Could not $what: Bluetooth is blocked (airplane mode or a hardware switch)."
                "NotAvailable" in text || "profile-unavailable" in text -> "Could not $what: it offers nothing this machine can connect to."
                else -> "Could not $what${e?.message?.let { ": $it" } ?: "."}"
            }
        }
    }
}

@DBusInterfaceName("org.bluez.AgentManager1")
@Suppress("FunctionName")
interface AgentManager1 : DBusInterface {
    fun RegisterAgent(agent: DBusPath, capability: String)
    fun UnregisterAgent(agent: DBusPath)
    fun RequestDefaultAgent(agent: DBusPath)
}

@DBusInterfaceName("org.bluez.Agent1")
@Suppress("FunctionName")
interface Agent1 : DBusInterface {
    fun Release()
    fun RequestPinCode(device: DBusPath): String
    fun DisplayPinCode(device: DBusPath, pincode: String)
    fun RequestPasskey(device: DBusPath): UInt32
    fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16)
    fun RequestConfirmation(device: DBusPath, passkey: UInt32)
    fun RequestAuthorization(device: DBusPath)
    fun AuthorizeService(device: DBusPath, uuid: String)
    fun Cancel()
}

@DBusInterfaceName("org.bluez.Adapter1")
@Suppress("FunctionName")
interface Adapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun RemoveDevice(device: DBusPath)
}

@DBusInterfaceName("org.bluez.Device1")
@Suppress("FunctionName")
interface Device1 : DBusInterface {
    fun Pair()
    fun CancelPairing()
    fun Connect()
    fun Disconnect()
}
