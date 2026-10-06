package com.dash.android.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection state of a transport, surfaced to the UI (the Serial Monitor). The absence of a
 * device is a normal state, not an error — a transport reports NO_DEVICE and keeps running,
 * honouring the capability-detection / graceful-degradation principle (CLAUDE.md).
 */
enum class TransportState {
    NO_DEVICE,            // nothing connected — normal, not a fault
    PERMISSION_REQUIRED,  // device present, awaiting the user's USB permission
    CONNECTING,
    CONNECTED,
    ERROR
}

data class TransportStatus(
    val state: TransportState,
    val detail: String = "",
    /**
     * Where a module should be pointed to reach this pipe, when that is a meaningful idea — the WiFi
     * server's `ip:port` today (roadmap 1.5.10). Null for a pipe a module cannot be *aimed* at: on USB
     * and Bluetooth the module is found, not addressed.
     *
     * A structured field rather than something the UI digs back out of [detail]: Transport Manager
     * offers this address as copyable text for a builder's firmware, and parsing it out of a
     * human-readable sentence would mean a reworded status silently breaking the copy button. The
     * transport that owns the address states it; a fourth transport with an address gets the panel for
     * free by filling this in.
     */
    val address: String? = null
) {
    companion object {
        val NO_DEVICE = TransportStatus(TransportState.NO_DEVICE, "No device")
    }
}

/**
 * The pluggable transport abstraction (roadmap 1.4.1). A transport is a *dumb pipe*: it moves
 * whole lines of UTF-8 text in and out and knows nothing about their meaning. USB serial is the
 * first implementation; WiFi TCP and the rest follow behind this same contract — which is exactly
 * transport.md's core philosophy: "the transport is simply the pipe. The message is what matters."
 *
 * Deliberately minimal. Parsing the pipe grammar (arduino/arduino.md) and running the module
 * lifecycle are the job of the layers above, built in 1.4.2+.
 */
interface DashTransport {
    /** Short human-readable tag identifying this transport, e.g. "usb". Rides on every WireEvent. */
    val tag: String

    /**
     * Whether this is a *wired* transport (roadmap 1.4.14). A wired pipe (USB) that goes silent is a
     * fault — a cable or a board has failed. A wireless pipe (WiFi, Bluetooth) going silent is
     * ordinary — the module drove out of range or powered down. The reconciliation desk uses this to
     * word a "not responding" module honestly rather than treat every absence the same.
     */
    val wired: Boolean

    /**
     * Complete inbound units (module → DASH), framed by the transport and stamped with their origin
     * (roadmap 1.4.14): ordinary [Inbound.Line]s with the trailing newline removed, and
     * length-prefixed [Inbound.Block]s for asset payloads, each wrapped in an [InboundFrame] carrying
     * the transport tag and the source device key. Both arrive on this one stream, in the order the
     * module sent them.
     */
    val incoming: Flow<InboundFrame>

    /** Live connection status. */
    val status: StateFlow<TransportStatus>

    /**
     * Physical devices currently connected on this transport (roadmap 1.4.10) — one entry per device,
     * empty when nothing is connected. The Serial Monitor's device selector reads this. A transport
     * that never distinguishes devices may leave this empty and rely on the broadcast [send].
     */
    val devices: StateFlow<List<TransportDevice>>

    /** Begin operating: register listeners and attempt to connect. Idempotent. */
    fun start()

    /**
     * Send one line (DASH → module). Newline framing is added by the transport. Fire-and-forget:
     * live data is stateless and self-healing (arduino.md §6), so a failed send is not fatal.
     */
    fun send(line: String)

    /**
     * Send to one specific device by its [TransportDevice.key] (roadmap 1.4.10 — the Serial Monitor's
     * "talk to one device" path). Default: broadcast, for transports that don't distinguish devices.
     */
    fun send(line: String, deviceKey: String) = send(line)

    /** Stop operating and release all resources. */
    fun stop()
}
