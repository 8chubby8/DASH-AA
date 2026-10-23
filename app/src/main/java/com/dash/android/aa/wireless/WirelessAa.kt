package com.dash.android.aa.wireless

import android.util.Log
import com.dash.android.connections.AdapterJob
import com.dash.android.connections.AdapterKind
import com.dash.android.connections.Band
import com.dash.android.connections.BluetoothState
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.connections.NO_COUNTRY
import com.dash.android.connections.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** A phone's Bluetooth link to DASH's wireless Android Auto service. */
class PhoneLink(val address: String, val input: InputStream, val output: OutputStream, private val closer: Closeable) : Closeable {
    override fun close() { runCatching { closer.close() } }
}

/** How the phone is offered wireless Android Auto on Bluetooth — BlueZ on DASH-AA, Android's own on native. */
interface PhoneService {
    /** Offer it. Null when it is offered; otherwise why not, in plain words. */
    fun offer(): String?
    fun withdraw()
}

/**
 * Wireless Android Auto (DASH-AA 1.2.3), from the phone's Bluetooth to a connection Android Auto can run on.
 *
 * **When it is offered:** switched on (Android Auto › Connection › Wireless), Bluetooth on, and a Wi-Fi
 * adapter hosting DASH's own Wi-Fi on 5 GHz (Connections › Wi-Fi). Whatever is missing, [note] says so in
 * plain words — capability detection; nothing fails. Until it is all there, phones are not offered it at
 * all, so they never try a car that cannot answer.
 *
 * **Then (Roger, 2026-10-22):** any phone paired with DASH may connect; the first one there keeps the screen,
 * by cable or not — a phone asking while Android Auto already runs is turned away, and a cable plugged in
 * during a wireless session only charges. The phone is told DASH's Wi-Fi over Bluetooth ([WirelessSetup]),
 * joins it, and connects to DASH on [WirelessSetup.PORT], only on DASH's own Wi-Fi's address. Only a phone
 * that has just been told is let in; anything else connecting there is closed.
 *
 * The connection loop takes each arrival with [take] and runs it as it runs a cable.
 */
class WirelessAa(
    private val service: (onPhone: (PhoneLink) -> Unit) -> PhoneService,
    private val networks: Flow<NetworkState>,
    private val connections: Flow<ConnectionsSettings>,
    private val bluetooth: StateFlow<BluetoothState>,
    /** Android Auto is running (or starting) already, by cable or wireless. */
    private val busy: () -> Boolean,
    /** End the running session now — the phone it was with has started again. */
    private val endSession: (why: String) -> Unit,
) {
    /** A phone on Wi-Fi, ready for its session. [close] when the session ends. */
    class Arrival(val link: TcpLink, val phone: String, private val bluetooth: PhoneLink) {
        val address get() = bluetooth.address
        fun close() { link.close(); bluetooth.close() }
    }

    private class Told(val name: String, val socket: PhoneLink, val at: Long)

    private val phoneService = service { onPhone(it) }
    private val enabled = MutableStateFlow(false)
    private val _note = MutableStateFlow("Off")
    /** What wireless Android Auto is doing, for Android Auto › Connection. */
    val note: StateFlow<String> = _note.asStateFlow()
    private val _ready = MutableStateFlow(false)
    /** Offered on Bluetooth and waiting for a phone — the viewport says so. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile private var target: WirelessNetwork? = null
    private var server: ServerSocket? = null
    private val told = AtomicReference<Told?>(null)
    private val arrival = AtomicReference<Arrival?>(null)
    @Volatile private var running: Arrival? = null

    fun setEnabled(on: Boolean) { enabled.value = on }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(enabled, networks, connections, bluetooth) { on, net, c, bt -> readiness(on, net, c, bt) }
                .collect { apply(it) }
        }
    }

    /** The phone waiting on Wi-Fi, if one is — taken once. */
    fun take(): Arrival? = arrival.getAndSet(null)?.also { running = it; _note.value = "Running wirelessly, with ${it.phone}" }

    /** The session [a] ran in has ended. */
    fun ended(a: Arrival) {
        a.close()
        if (running === a) running = null
        told.set(null)
        _note.value = if (_ready.value) WAITING else _note.value
    }

    // ---- Is everything there ----

    private sealed interface Readiness {
        data class No(val why: String) : Readiness
        data class Yes(val network: WirelessNetwork) : Readiness
    }

    private fun readiness(on: Boolean, net: NetworkState, c: ConnectionsSettings, bt: BluetoothState): Readiness {
        if (!on) return Readiness.No("Off")
        if (!bt.available || !bt.powered) return Readiness.No("Needs Bluetooth switched on (Connections › Bluetooth): the phone is told how to join over Bluetooth")
        if (!net.available) return Readiness.No(net.note.ifBlank { "The network service is not running" })
        val wifi = net.adapters.filter { it.kind == AdapterKind.WIFI }
        if (wifi.none { it.canHost }) return Readiness.No("Needs a Wi-Fi adapter that can host DASH's own Wi-Fi")
        val host = wifi.firstOrNull { it.job == AdapterJob.HOST }
            ?: return Readiness.No("Needs DASH's own Wi-Fi: in Connections › Wi-Fi, give the Wi-Fi the job DASH network")
        if (Band.GHZ_5 !in host.bands) return Readiness.No("This Wi-Fi adapter cannot host on 5 GHz, which phones need")
        if (c.network.band != Band.GHZ_5) return Readiness.No("DASH's own Wi-Fi is on 2.4 GHz; phones need 5 GHz (Connections › Wi-Fi › Band)")
        if (net.country == NO_COUNTRY) return Readiness.No("This machine does not know its country, so it may not host on 5 GHz (Connections › Wi-Fi)")
        val address = host.address?.substringBefore('/')
        if (!host.connected || address == null) return Readiness.No("Starting DASH's own Wi-Fi…")
        return Readiness.Yes(WirelessNetwork(c.network.name, c.network.password, host.mac, address))
    }

    @Synchronized
    private fun apply(r: Readiness) {
        when (r) {
            is Readiness.No -> {
                if (target != null || _ready.value) Log.i(TAG, "not offered: ${r.why}")
                target = null
                _ready.value = false
                phoneService.withdraw()
                closeServer()
                if (running == null) _note.value = r.why
            }
            is Readiness.Yes -> {
                if (target == r.network && _ready.value) return
                target = r.network
                closeServer()
                val opened = openServer(r.network.address)
                val refused = if (opened) phoneService.offer() else "Could not wait for the phone on ${r.network.address} : ${r.network.port}"
                _ready.value = refused == null
                if (running == null) _note.value = refused ?: WAITING
            }
        }
    }

    // ---- The phone on Bluetooth ----

    private fun onPhone(socket: PhoneLink) {
        val address = socket.address
        val name = bluetooth.value.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name ?: address
        val n = target
        // The phone in the wireless session asking again has left it, whatever DASH thought (seen 2026-10-23:
        // turned away 46 times as "already running"). That session ends; the phone's next try comes in.
        val stale = running?.takeIf { it.address.equals(address, ignoreCase = true) }
        if (stale != null) {
            Log.i(TAG, "$name started wireless Android Auto again: its old session is gone")
            endSession("the phone started again")
            socket.close()
            return
        }
        if (n == null || busy() || running != null || arrival.get() != null) {
            Log.i(TAG, "$name asked for wireless Android Auto, but ${if (n == null) "it is not ready" else "Android Auto is already running"}")
            if (n != null) _note.value = "$name asked, but Android Auto is already running"
            socket.close()
            return
        }
        told.getAndSet(Told(name, socket, System.currentTimeMillis()))?.let { if (it.socket !== socket) it.socket.close() }
        Log.i(TAG, "$name connected on Bluetooth; telling it DASH's Wi-Fi (${n.ssid}, ${n.address}:${n.port})")
        _note.value = "Telling $name how to join DASH's Wi-Fi"
        try {
            WirelessSetup.talk(socket.input, socket.output, n) { step ->
                Log.i(TAG, "$name: $step")
                if (step == "joining" && running == null) _note.value = "$name joining DASH's Wi-Fi"
            }
            Log.i(TAG, "$name closed the Bluetooth link")
        } catch (e: IOException) {
            Log.i(TAG, "$name's Bluetooth link ended: ${e.message}")
        }
    }

    // ---- The phone on Wi-Fi ----

    private fun openServer(address: String): Boolean = try {
        val s = ServerSocket(WirelessSetup.PORT, 2, InetAddress.getByName(address)).apply { reuseAddress = true }
        server = s
        Thread({ acceptLoop(s) }, "dash-aa-wireless-accept").apply { isDaemon = true }.start()
        true
    } catch (e: IOException) {
        Log.w(TAG, "could not listen on $address:${WirelessSetup.PORT}: ${e.message}")
        false
    }

    private fun closeServer() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket: Socket = try { s.accept() } catch (e: IOException) { return }
            val t = told.get()
            val fresh = t != null && System.currentTimeMillis() - t.at < TOLD_FOR_MS
            if (!fresh || busy() || running != null || arrival.get() != null) {
                Log.i(TAG, "closed a connection from ${socket.inetAddress.hostAddress}: no phone was expected")
                runCatching { socket.close() }
                continue
            }
            Log.i(TAG, "${t!!.name} connected over Wi-Fi from ${socket.inetAddress.hostAddress}")
            told.set(null)
            arrival.set(Arrival(TcpLink(socket, "${t.name} (wireless)"), t.name, t.socket))
            _note.value = "${t.name} connected over Wi-Fi"
        }
    }

    private companion object {
        const val TAG = "DashAaWireless"
        const val WAITING = "Waiting for your phone on Bluetooth"
        /** How long after being told a phone has to arrive on Wi-Fi. */
        const val TOLD_FOR_MS = 60_000L
            }
}
