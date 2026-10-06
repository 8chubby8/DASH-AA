package com.dash.android.transport.bluetooth

import android.content.Context
import android.util.Log
import com.dash.android.transport.DashTransport
import com.dash.android.transport.FrameAssembler
import com.dash.android.transport.InboundFrame
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.TransportState
import com.dash.android.transport.TransportStatus
import com.dash.android.transport.bluetooth.linux.BlueZ
import com.dash.android.transport.bluetooth.linux.RfcommSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Bluetooth Classic (SPP) transport — **DASH-AA's Linux implementation**, under upstream's class
 * name so [com.dash.android.transport.TransportManager] stays byte-identical. Upstream's design is
 * kept whole; only the three Android Bluetooth calls are replaced by their BlueZ equivalents
 * ([BlueZ]):
 *
 * - **DASH connects out, to bonded devices only.** Pairing is the operating system's job — in GNOME
 *   Settings › Bluetooth or `bluetoothctl`, never here (transport.md).
 * - **A module is identified by its name carrying `D.A.S.H`** ([NAME_MARKER]) — module-sdk.md §12,
 *   unchanged, so every existing SPP module is found without reflashing.
 * - **One [FrameAssembler] per device** — the 1.4.10 rule carried to RFCOMM.
 * - **An idempotent re-sweep**, so a module that comes back into range, or a radio switched on after
 *   launch, is picked up on the next tick.
 * - **Graceful degradation.** No adapter, radio off, BlueZ tools missing: a quiet NO_DEVICE and a
 *   transport that keeps sweeping. Linux needs no runtime permission for an outbound RFCOMM socket, so
 *   upstream's PERMISSION_REQUIRED path has no equivalent here and is simply never reached.
 *
 * **The channel.** Android asks SDP for the Serial Port service's RFCOMM channel on our behalf; here
 * [BlueZ.sppChannel] asks it directly, and remembers the answer per module. If SDP cannot answer, the
 * ESP32's `BluetoothSerial` default — channel 1 — is tried, which is where every reference module
 * listens.
 *
 * It is a dumb pipe: it frames inbound bytes into lines and blocks and emits them; it never parses a
 * message.
 */
class BluetoothSppTransport(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope
) : DashTransport {

    override val tag: String = "bt"

    // Bluetooth is wireless: a module falling silent is ordinary (out of range / off), not a fault (1.4.14).
    override val wired: Boolean = false

    private val _incoming = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<InboundFrame> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(TransportStatus.NO_DEVICE)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<TransportDevice>>(emptyList())
    override val devices: StateFlow<List<TransportDevice>> = _devices.asStateFlow()

    // One entry per open RFCOMM link, keyed by the device's MAC address. Guarded by `this`.
    private val connections = mutableMapOf<String, DeviceConnection>()

    // Devices mid-connect (a connect blocks for seconds when out of range). Guarded by `this`.
    private val connecting = mutableSetOf<String>()

    /** SDP's answer per module, so the lookup happens once rather than every reconnect. */
    private val channels = mutableMapOf<String, Int>()

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

    override fun send(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val targets = synchronized(this) { connections.values.toList() }
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            targets.forEach { conn ->
                runCatching { conn.write(bytes) }
                    .onFailure { closeDevice(conn.address, "write failed: ${it.message}") }
            }
        }
    }

    override fun send(line: String, deviceKey: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val conn = synchronized(this) { connections[deviceKey] } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { conn.write(bytes) }
                .onFailure { closeDevice(conn.address, "write failed: ${it.message}") }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        val open = synchronized(this) {
            val all = connections.values.toList()
            connections.clear()
            connecting.clear()
            all
        }
        open.forEach { it.close() }
        _devices.value = emptyList()
        _status.value = TransportStatus(TransportState.NO_DEVICE, "Stopped")
    }

    private fun connectAvailable() {
        when (BlueZ.adapter()) {
            BlueZ.Adapter.ABSENT -> {
                _status.value = TransportStatus(TransportState.NO_DEVICE, "Bluetooth unavailable")
                return
            }
            BlueZ.Adapter.OFF -> {
                _status.value = TransportStatus(TransportState.NO_DEVICE, "Bluetooth off")
                return
            }
            BlueZ.Adapter.POWERED -> Unit
        }

        for (device in BlueZ.bondedDevices()) {
            if (!device.name.contains(NAME_MARKER)) continue      // not one of ours (name marker)
            val skip = synchronized(this) {
                connections.containsKey(device.address) || !connecting.add(device.address)
            }
            if (skip) { refreshLabel(device); continue }
            openDevice(device)
        }
        publishState()
    }

    private fun refreshLabel(device: BlueZ.BondedDevice) {
        val current = labelFor(device)
        val changed = synchronized(this) {
            val conn = connections[device.address] ?: return
            if (conn.label == current) false else { conn.label = current; true }
        }
        if (changed) {
            Log.i(TAG, "${device.address} is now called $current")
            publishState()
        }
    }

    private fun openDevice(device: BlueZ.BondedDevice) {
        val address = device.address
        val label = labelFor(device)
        scope.launch(Dispatchers.IO) {
            val channel = synchronized(this@BluetoothSppTransport) { channels[address] }
                ?: (runCatching { BlueZ.sppChannel(address) }.getOrNull() ?: DEFAULT_CHANNEL)
                    .also { ch -> synchronized(this@BluetoothSppTransport) { channels[address] = ch } }
            val socket = try {
                RfcommSocket.connect(address, channel)            // blocking; throws if unreachable
            } catch (e: Exception) {
                synchronized(this@BluetoothSppTransport) {
                    connecting.remove(address)
                    channels.remove(address)                     // re-ask SDP next time; it may have moved
                }
                Log.i(TAG, "connect to $label ($address) failed: ${e.message}")
                return@launch
            }
            val conn = DeviceConnection(address, label, socket)
            synchronized(this@BluetoothSppTransport) {
                connecting.remove(address)
                connections[address] = conn
            }
            conn.start()
            publishState()
            Log.i(TAG, "connected to $label ($address) on channel $channel")
        }
    }

    private fun labelFor(device: BlueZ.BondedDevice): String = device.name.ifBlank { device.address }

    private fun closeDevice(address: String, reason: String) {
        val conn = synchronized(this) { connections.remove(address) } ?: return
        conn.close()
        publishState()
        Log.i(TAG, "device $address closed — $reason")
    }

    private fun publishState() {
        synchronized(this) {
            _devices.value = connections.values.map { TransportDevice(it.address, it.label, tag) }
            val open = connections.values.toList()
            if (open.isNotEmpty()) {
                val detail = when (open.size) {
                    1 -> "1 module on BT — ${open.first().label}"
                    else -> "${open.size} modules on BT"
                }
                _status.value = TransportStatus(TransportState.CONNECTED, detail)
            } else {
                _status.value = TransportStatus(TransportState.NO_DEVICE, "No paired module")
            }
        }
    }

    private inner class DeviceConnection(
        val address: String,
        @Volatile var label: String,
        private val socket: RfcommSocket
    ) : java.io.Closeable {

        private val assembler = FrameAssembler { frame -> _incoming.tryEmit(InboundFrame(frame, tag, address)) }
        private val output: OutputStream = socket.outputStream
        private var readerJob: Job? = null

        fun start() {
            readerJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(READ_BUF)
                try {
                    val input = socket.inputStream
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break                 // link closed cleanly
                        if (n > 0) assembler.feed(buf, n)
                    }
                } catch (e: Exception) {
                    // A dropped link — out of range, module powered off. Ordinary for wireless.
                } finally {
                    closeDevice(address, "link closed")
                }
            }
        }

        @Synchronized
        fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            readerJob?.cancel()
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val NAME_MARKER = "D.A.S.H"
        /** ESP32 `BluetoothSerial`'s SPP server channel — the fallback when SDP cannot answer. */
        const val DEFAULT_CHANNEL = 1
        const val READ_BUF = 1024
        const val RESWEEP_MS = 3000L
        const val TAG = "DashBtSpp"
    }
}
