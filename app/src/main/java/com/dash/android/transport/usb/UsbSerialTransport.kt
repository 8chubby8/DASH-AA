package com.dash.android.transport.usb

import android.content.Context
import android.util.Log
import com.dash.android.transport.DashTransport
import com.dash.android.transport.FrameAssembler
import com.dash.android.transport.Inbound
import com.dash.android.transport.InboundFrame
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.TransportState
import com.dash.android.transport.TransportStatus
import com.dash.android.location.Nmea
import com.dash.android.location.linux.GnssReceivers
import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * USB serial transport — **DASH-AA's Linux implementation**, under upstream's class name so
 * [com.dash.android.transport.TransportManager] stays byte-identical.
 *
 * Upstream (1.4.1 → 1.4.10) drives Android's USB host API through usb-serial-for-android. On Linux
 * the kernel has already done that work: every CDC-ACM board (Uno R4, native-USB ESP32-S2/S3/C3/C6,
 * Pico, Leonardo) appears as `/dev/ttyACM*`, and every bridge chip (CP210x on the ESP32 DevKitC,
 * CH34x, FTDI, PL2303) as `/dev/ttyUSB*`. So this transport opens tty devices rather than USB
 * interfaces, and everything above it is unchanged. What it keeps from upstream, deliberately:
 *
 * - **The module profile is fixed — 115200 8N1** — nothing for the user to configure.
 * - **DTR and RTS are raised after open.** Upstream's reason holds on Linux: some CDC bridges (the R4
 *   WiFi's ESP32-S3) gate data until the host raises them, and both-high is the ESP32 DevKitC's
 *   normal run state.
 * - **Multi-device, one [FrameAssembler] per device** — the 1.4.10 hard requirement. A block from
 *   board A can never flip board B's live bytes into byte-count mode.
 * - **An idempotent re-sweep every [RESWEEP_MS]** instead of trusting hot-plug notifications —
 *   upstream's own lesson (the attach broadcast was unreliable); here it also means no udev listener.
 * - **Graceful degradation.** No device is NO_DEVICE, not an error. A port the user may not open is
 *   PERMISSION_REQUIRED with the fix in the detail (on Arch the group is `uucp`, elsewhere `dialout`)
 *   — the desktop equivalent of Android's USB permission dialog, which DASH-AA cannot raise itself.
 *
 * **Only USB serial devices are considered** (`ttyACM*`, `ttyUSB*`). A laptop's built-in `ttyS*`
 * ports are not hot-plug USB modules and are left alone, the same way upstream left a flash drive
 * on the hub alone.
 *
 * It is a dumb pipe: it frames inbound bytes into lines and blocks and emits them; it never parses
 * a message.
 *
 * **A GPS receiver is claimed, not connected** (DASH-AA 1.1.11). A port whose first words are good NMEA
 * sentences is a GPS, not a module: from then on its lines go to [GnssReceivers] and nowhere else, DASH sends
 * it nothing, and it is not listed as a module device. A port sending only noise at 115200 is tried at the
 * speeds GPS receivers use (9600, 4800, 38400) before going back — no module ever sends noise, but a receiver
 * behind a bridge chip does at the wrong speed. A CDC receiver (u-blox and most others) is heard at once.
 */
class UsbSerialTransport(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope
) : DashTransport {

    override val tag: String = "usb"

    // USB is a wired pipe: a device going silent is a fault, not an out-of-range wireless module (1.4.14).
    override val wired: Boolean = true

    private val _incoming = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<InboundFrame> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(TransportStatus.NO_DEVICE)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<TransportDevice>>(emptyList())
    override val devices: StateFlow<List<TransportDevice>> = _devices.asStateFlow()

    /** One entry per open port, keyed by its device path (`/dev/ttyACM0`). Guarded by `this`. */
    private val connections = mutableMapOf<String, DeviceConnection>()

    /** Ports present but not openable by this user — reported, and retried each sweep (a user who
     *  fixes their group membership and logs back in is picked up without a restart). */
    private val refused = mutableSetOf<String>()

    private var started = false

    override fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { connectAvailable() }
                    .onFailure { Log.w(TAG, "sweep failed: ${it.message}") }
                delay(RESWEEP_MS)
            }
        }
    }

    /** Fan a line out to every open device (DASH → modules). Each module ignores what isn't
     *  addressed to its id, exactly as it would on a shared bus. */
    override fun send(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val targets = synchronized(this) { connections.values.filter { !it.gps } }
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            targets.forEach { conn ->
                runCatching { conn.write(bytes) }
                    .onFailure { closeDevice(conn.path, "Write failed: ${it.message}") }
            }
        }
    }

    override fun send(line: String, deviceKey: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val conn = synchronized(this) { connections[deviceKey]?.takeIf { !it.gps } } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { conn.write(bytes) }
                .onFailure { closeDevice(conn.path, "Write failed: ${it.message}") }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        val open = synchronized(this) {
            val all = connections.values.toList()
            connections.clear()
            refused.clear()
            all
        }
        open.forEach { it.close() }
        _devices.value = emptyList()
        _status.value = TransportStatus(TransportState.NO_DEVICE, "Stopped")
    }

    /** The USB serial ports present right now, by device path. */
    private fun presentPorts(): Map<String, SerialPort> =
        SerialPort.getCommPorts()
            .filter { isUsbSerial(it.systemPortPath) }
            .associateBy { it.systemPortPath }

    private fun isUsbSerial(path: String?): Boolean {
        val name = path?.substringAfterLast('/') ?: return false
        return name.startsWith("ttyACM") || name.startsWith("ttyUSB")
    }

    /**
     * Scan every present port and open the ones not open yet. Idempotent: an open port is skipped, a
     * vanished one is closed and forgotten so a replug is a fresh open.
     */
    @Synchronized
    private fun connectAvailable() {
        val ports = presentPorts()
        (connections.keys - ports.keys).toList().forEach { path ->
            connections.remove(path)?.close()
            GnssReceivers.release(path)
            Log.i(TAG, "$path gone")
        }
        refused.retainAll(ports.keys)

        for ((path, port) in ports) {
            if (connections.containsKey(path)) continue
            if (!File(path).canWrite() || !File(path).canRead()) {
                if (refused.add(path)) Log.w(TAG, "$path present but not openable by this user")
                continue
            }
            openPort(path, port)
        }
        publishState()
    }

    /** Open one port into a live [DeviceConnection]. A failure leaves it out and the next sweep
     *  retries it — one board that will not open never blocks the others. */
    private fun openPort(path: String, port: SerialPort) {
        port.setComPortParameters(BAUD_RATE, DATA_BITS, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, WRITE_TIMEOUT_MS)
        if (!port.openPort()) {
            if (refused.add(path)) Log.w(TAG, "$path would not open")
            return
        }
        refused.remove(path)
        // Raise DTR/RTS — see the class note; wrapped so a driver without control lines still connects.
        runCatching {
            port.setDTR()
            port.setRTS()
        }
        val conn = DeviceConnection(path, describe(port), port)
        connections[path] = conn
        conn.start()
        Log.i(TAG, "opened $path (${conn.name}) @ $BAUD_RATE 8N1")
    }

    private fun closeDevice(path: String, reason: String) {
        val conn = synchronized(this) { connections.remove(path) } ?: return
        conn.close()
        GnssReceivers.release(path)
        Log.i(TAG, "$path closed — $reason")
        publishState()
    }

    private fun publishState() {
        synchronized(this) {
            val modules = connections.values.filter { !it.gps }
            _devices.value = modules.map { TransportDevice(it.path, it.name, tag) }
            val n = modules.size
            _status.value = when {
                n > 0 -> TransportStatus(
                    TransportState.CONNECTED,
                    if (n == 1) "1 device @ $BAUD_RATE 8N1" else "$n devices @ $BAUD_RATE 8N1"
                )
                refused.isNotEmpty() -> TransportStatus(
                    TransportState.PERMISSION_REQUIRED,
                    "Cannot open ${refused.first()} — add your user to the 'uucp' (Arch) or 'dialout' group"
                )
                else -> TransportStatus(TransportState.NO_DEVICE, "No device")
            }
        }
    }

    /** A short human label — the USB product string if the kernel knows it, else the port name. */
    private fun describe(port: SerialPort): String =
        port.portDescription?.takeIf { it.isNotBlank() && !it.startsWith("tty") }
            ?: port.descriptivePortName?.takeIf { it.isNotBlank() }
            ?: port.systemPortName

    /**
     * One open port. Owns its own reader thread and — the 1.4.10 requirement — its own
     * [FrameAssembler], so its block framing can never bleed into another device's stream.
     */
    private inner class DeviceConnection(
        val path: String,
        val name: String,
        private val port: SerialPort,
    ) {
        private val assembler = FrameAssembler { frame ->
            val line = (frame as? Inbound.Line)?.text
            if (line != null && line.all { it == '\t' || it.code in 0x20..0x7E }) sane++
            if (!gps && !module && line != null && Nmea.isSentence(line)) claimGps()
            when {
                gps -> line?.let { GnssReceivers.feed(path, it) }
                else -> {
                    if (line != null && line.isNotBlank() && !line.startsWith('$')) module = true
                    _incoming.tryEmit(InboundFrame(frame, tag, path))
                }
            }
        }

        /** Claimed as a GPS receiver (see the class note). */
        @Volatile var gps = false
            private set
        /** It has said something a GPS would not — a module, never claimed. */
        private var module = false
        private var sane = 0
        private var noise = 0
        private var speed = 0
        private var tries = 0
        private var since = System.currentTimeMillis()

        private fun claimGps() {
            gps = true
            GnssReceivers.claim(path, name)
            Log.i(TAG, "$path is a GPS receiver — claimed for Location")
            publishState()
        }

        /** Only noise for a while: try the next speed a GPS receiver might use. */
        private fun hunt(n: Int) {
            if (gps || module || sane > 0 || tries >= HUNT_SPEEDS.size) return
            noise += n
            if (noise < HUNT_BYTES || System.currentTimeMillis() - since < HUNT_MS) return
            speed = (speed + 1) % HUNT_SPEEDS.size
            tries++
            runCatching { port.setBaudRate(HUNT_SPEEDS[speed]) }
            assembler.reset()
            noise = 0
            since = System.currentTimeMillis()
            Log.i(TAG, "$path: noise only — trying ${HUNT_SPEEDS[speed]}")
        }

        @Volatile private var running = true
        private val reader = Thread({ readLoop() }, "dash-usb-${path.substringAfterLast('/')}").apply { isDaemon = true }

        fun start() = reader.start()

        private fun readLoop() {
            val buf = ByteArray(READ_BUF)
            try {
                while (running) {
                    val n = port.readBytes(buf, buf.size)
                    if (n < 0) break                     // port gone (unplugged)
                    if (n > 0) {
                        assembler.feed(buf, n)
                        hunt(n)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "$path read ended: ${e.message}")
            }
            if (running) closeDevice(path, "Read error")
        }

        @Synchronized
        fun write(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) {
                val n = port.writeBytes(bytes, bytes.size - off, off)
                if (n < 0) error("write failed")
                off += n
            }
        }

        fun close() {
            running = false
            runCatching { port.closePort() }
        }
    }

    private companion object {
        const val TAG = "DashUsbSerial"

        /** The project's serial rate, matched by every module's firmware — see upstream for why it
         *  stays at 115200 (a slower rate did not help large installs; RESEND does). */
        const val BAUD_RATE = 115200
        const val DATA_BITS = 8
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 2000
        const val READ_BUF = 4096
        const val RESWEEP_MS = 1500L
        /** GPS speeds tried on a port sending only noise, back to the module speed last (1.1.11). */
        val HUNT_SPEEDS = intArrayOf(BAUD_RATE, 9600, 4800, 38400)
        const val HUNT_BYTES = 256
        const val HUNT_MS = 3000L
    }
}
