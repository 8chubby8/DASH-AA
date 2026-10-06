package com.dash.android.transport

import android.content.Context
import com.dash.android.transport.bluetooth.BluetoothSppTransport
import com.dash.android.transport.usb.UsbSerialTransport
import com.dash.android.transport.wifi.WifiTcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the set of active transports and exposes the read-only wire tap the Serial Monitor renders.
 *
 * Held for the lifetime of the running app (created in MainScreen), so the connection persists
 * independently of whether the monitor is open — the monitor is only a *view* onto the wire, never
 * its owner. The discovery/handshake brain (1.4.2+) sits above this and drives it through [send] and
 * [wire] while the monitor passively observes.
 *
 * Deliberately transport-agnostic: DASH sends to *all* active transports and merges what *all* of
 * them receive, so no message above this layer ever cares which pipe carried it. The list holds a USB
 * serial transport, a WiFi TCP transport (1.4.11) and a Bluetooth Classic SPP transport (1.4.12), all
 * three behind the same [DashTransport] contract — adding each new one was a single line here and
 * nothing else above this layer, which is exactly the proof 1.4.11 and 1.4.12 set out to give.
 *
 * The `wire` flow replays recent history to new collectors, so opening the monitor immediately
 * shows the last of the traffic rather than a blank screen.
 */
class TransportManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every transport DASH can talk over. Nothing above this line is transport-specific — the list
     *  is where a new pipe joins, and the whole of 1.4.11/1.4.12 above this layer is these lines. */
    private val transports: List<DashTransport> = listOf(
        UsbSerialTransport(context, scope),
        WifiTcpTransport(scope),
        BluetoothSppTransport(context, scope)
    )

    /**
     * Aggregate status across every transport. Reports the "liveliest" state present — CONNECTED if
     * any pipe is up, down through to NO_DEVICE if none is — carrying that transport's detail text.
     * With a single transport this is simply that transport's status.
     */
    val status: StateFlow<TransportStatus> =
        combine(transports.map { it.status }) { statuses -> aggregate(statuses) }
            .stateIn(scope, SharingStarted.Eagerly, TransportStatus.NO_DEVICE)

    /**
     * Each transport's *own* status, tagged with its pipe (roadmap 1.4.11). The merged [status] above
     * collapses to the single liveliest line, which is right for a one-glance summary but hides the
     * others — a WiFi transport's "Listening on <ip>:3274" would be masked whenever USB is the livelier
     * pipe. The Serial Monitor renders this list so every transport is visible, IP and all.
     */
    val transportStatuses: StateFlow<List<Pair<String, TransportStatus>>> =
        combine(transports.map { t -> t.status.map { s -> t.tag to s } }) { it.toList() }
            .stateIn(scope, SharingStarted.Eagerly, transports.map { it.tag to TransportStatus.NO_DEVICE })

    /** Every physical device across every transport (roadmap 1.4.10) — the merged list the Serial
     *  Monitor's device selector renders. Each [TransportDevice] carries its own transport tag, so a
     *  targeted send routes back to the pipe that owns it. Since 1.4.14 the install desk also watches
     *  this to fail a handshake whose device leaves the bus. */
    val devices: StateFlow<List<TransportDevice>> =
        combine(transports.map { it.devices }) { lists -> lists.toList().flatten() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Tags of the wired transports (roadmap 1.4.14). The reconciliation desk uses this to tell a
     *  wired absence (a fault) from a wireless one (ordinary out-of-range), and to word the card. */
    val wiredTags: Set<String> = transports.filter { it.wired }.map { it.tag }.toSet()

    private val _lastInboundAt = MutableStateFlow<Map<DeviceRef, Long>>(emptyMap())
    /**
     * When each **board** last sent any inbound byte, device → epoch ms (roadmap 1.5.10).
     *
     * Keyed per device rather than per pipe, because a pipe is not a thing that talks — the boards on
     * it are, and there can be several. "Data arrived on the WiFi pipe" is meaningless with two boards
     * connected: one can be streaming while the other lies unplugged on the bench, and a per-pipe
     * answer averages them into a lie. Transport Manager states facts about boards, so the fact has to
     * be recorded about boards.
     *
     * Deliberately *not* derived from [wire] by the UI: that flow replays only [WIRE_REPLAY] events
     * shared across every pipe, so on a busy bus a quiet board's last traffic has long fallen out of
     * the buffer by the time a tab opens, and the tab would report a healthy board as silent. This map
     * lives as long as the manager does, so the answer is the same whether the tab has been open all
     * along or was opened a second ago. Kept dumb on purpose — "bytes arrived", nothing about what
     * they meant; the grammar half is [DashController.lastDashAt].
     */
    val lastInboundAt: StateFlow<Map<DeviceRef, Long>> = _lastInboundAt.asStateFlow()

    private val _wire = MutableSharedFlow<WireEvent>(
        replay = WIRE_REPLAY,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wire: SharedFlow<WireEvent> = _wire.asSharedFlow()

    /**
     * The primary inbound stream (module → DASH) the controller/brain consumes and routes — framed
     * [Inbound] units (lines and asset blocks), in order. Distinct from [wire]: [wire] is a read-only
     * *observation* tap for the monitor (and a future SDK logger) carrying both directions as text with
     * replay; this is the live one-direction feed for the brain, with no replay so a restarted
     * collector never re-processes stale frames as new modules.
     */
    private val _inbound = MutableSharedFlow<InboundFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val inbound: SharedFlow<InboundFrame> = _inbound.asSharedFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        // Merge inbound frames from every transport onto the wire tap (module → DASH), each tagged
        // with the pipe it arrived on. A block renders as its header line plus a readable byte-count
        // note — never the raw payload as text, which would spew binary into the monitor.
        transports.forEach { t ->
            scope.launch {
                t.incoming.collect { env ->
                    val now = System.currentTimeMillis()
                    env.deviceKey?.let { key -> _lastInboundAt.update { it + (DeviceRef(t.tag, key) to now) } }
                    when (val frame = env.frame) {
                        is Inbound.Line -> _wire.tryEmit(WireEvent(now, WireDirection.IN, t.tag, frame.text, env.deviceKey))
                        is Inbound.Block -> {
                            _wire.tryEmit(WireEvent(now, WireDirection.IN, t.tag, frame.header, env.deviceKey))
                            _wire.tryEmit(WireEvent(now, WireDirection.IN, t.tag, frame.note, env.deviceKey))
                        }
                        // Deliberately silent. The monitor is a view of what came down the wire,
                        // and a progress report is DASH counting its own bytes, not a module saying
                        // anything — twenty per asset would bury the messages that are real.
                        is Inbound.BlockProgress -> Unit
                        is Inbound.OversizeBlock -> {
                            _wire.tryEmit(WireEvent(now, WireDirection.IN, t.tag, frame.header, env.deviceKey))
                            _wire.tryEmit(WireEvent(now, WireDirection.IN, t.tag, frame.note, env.deviceKey))
                        }
                    }
                    _inbound.tryEmit(env)   // origin (tag + device key) rides up to the controller (1.4.14)
                }
            }
        }
        transports.forEach { it.start() }
    }

    /**
     * Send a line out on every *active* transport (DASH → module) and record each on the wire tap.
     * "Active" means CONNECTED — a pipe with no device attached is skipped rather than shown a
     * phantom outbound line. This is the "broadcast to all active transports" the discovery brain
     * (1.4.2+) relies on.
     */
    fun send(line: String) {
        val now = System.currentTimeMillis()
        transports.forEach { t ->
            if (t.status.value.state == TransportState.CONNECTED) {
                _wire.tryEmit(WireEvent(now, WireDirection.OUT, t.tag, line))
                t.send(line)
            }
        }
    }

    // sendTo(device, line) — a per-device send — was removed at 1.5.15. It was built for the Serial
    // Monitor's SEND TO selector, which 1.5.13 dropped on the grounds that a DASH message already
    // carries its own addressing in field 1 of the grammar: a line finds the right module down
    // whichever pipe it travels, so targeting at the transport layer duplicated that a layer down.
    // Nothing has called it since. The broadcast send() below remains the controller's path.

    fun stop() {
        if (!started) return
        started = false
        transports.forEach { it.stop() }
        scope.cancel()
    }

    /** Collapse many transport statuses into one, favouring the liveliest state present. */
    private fun aggregate(statuses: Array<TransportStatus>): TransportStatus {
        for (state in STATE_PRIORITY) {
            statuses.firstOrNull { it.state == state }?.let { return it }
        }
        return TransportStatus.NO_DEVICE
    }

    private companion object {
        const val WIRE_REPLAY = 200
        val STATE_PRIORITY = listOf(
            TransportState.CONNECTED,
            TransportState.CONNECTING,
            TransportState.PERMISSION_REQUIRED,
            TransportState.ERROR,
            TransportState.NO_DEVICE
        )
    }
}
