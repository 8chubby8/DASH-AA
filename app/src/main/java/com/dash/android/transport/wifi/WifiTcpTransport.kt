package com.dash.android.transport.wifi

import android.util.Log
import com.dash.android.transport.DASH_ABSENT_MS
import com.dash.android.transport.DashTransport
import com.dash.android.transport.FrameAssembler
import com.dash.android.transport.Inbound
import com.dash.android.transport.InboundFrame
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.TransportState
import com.dash.android.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * WiFi TCP transport (roadmap 1.4.11) — the second [DashTransport] implementation, and the proof
 * that the 1.4.1 abstraction is genuinely transport-agnostic: it slots in beside the USB serial
 * transport with nothing above this layer changed. The controller, the sourceless core, install,
 * database and reconciliation neither know nor care that a module arrived over a socket instead of
 * a cable — a module answers with its id and the layers above route by id (transport.md).
 *
 * **The inversion vs USB.** On USB, DASH is the host: it enumerates and opens devices (DASH
 * initiates). On TCP, DASH runs a **server** on a fixed port ([PORT]) and modules are **clients that
 * connect in** (the module initiates — transport.md's WiFi model). DASH knows a module is present
 * because it connected; discovery still works exactly as everywhere else — the reconciliation sweep
 * broadcasts `DISCOVER` to all connected clients and each replies `HELLO`.
 *
 * **One accepted socket is one "device."** Each connecting client becomes a [ClientConnection] with
 * its own reader coroutine and — the same hard requirement 1.4.10 established for USB — **its own
 * [FrameAssembler]**, so a length-prefixed asset BLOCK from client A can never flip client B's live
 * bytes into byte-count mode. [send] fans a line out to every client; [incoming] merges what all of
 * them receive; [status]/[devices] aggregate across them. The Serial Monitor's per-device selector
 * and the Signal Monitor read these merged flows with no WiFi-specific code.
 *
 * **Graceful degradation (CLAUDE.md).** The server binds on [start] regardless of network state —
 * with WiFi off there is simply no reachable client, so it reports NO_DEVICE ("Listening on …") and
 * keeps running, exactly as the USB transport reports NO_DEVICE with nothing plugged in. A client
 * dropping (clean close or reset) closes only that connection; a module that vanishes goes SILENT
 * and DASH's reconciliation re-greets and re-activates it when it reconnects — the same self-heal a
 * brown-out reboot gets, arriving for free from the module lifecycle (arduino.md §6).
 *
 * It is a dumb pipe: it frames inbound bytes into lines and blocks and emits them; it never parses
 * a message.
 */
class WifiTcpTransport(
    private val scope: CoroutineScope
) : DashTransport {

    override val tag: String = "wifi"

    // WiFi is wireless: a client falling silent is ordinary (out of range / powered down), not a fault (1.4.14).
    override val wired: Boolean = false

    private val _incoming = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val incoming: SharedFlow<InboundFrame> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(TransportStatus.NO_DEVICE)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<TransportDevice>>(emptyList())
    override val devices: StateFlow<List<TransportDevice>> = _devices.asStateFlow()

    // One entry per accepted client socket, keyed by a monotonic connection id (the socket's remote
    // address is not unique — a module that reconnects reuses its port). Guarded by `this`.
    private val connections = mutableMapOf<String, ClientConnection>()
    private val nextId = AtomicLong(1)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var started = false

    override fun start() {
        if (started) return
        started = true
        // Bind and accept on the IO dispatcher — bind() and accept() both block. A bind failure
        // (port already in use) degrades to ERROR rather than crashing; nothing above depends on the
        // WiFi transport succeeding.
        acceptJob = scope.launch(Dispatchers.IO) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not bind port $PORT: ${e.message}")
                _status.value = TransportStatus(TransportState.ERROR, "Port $PORT unavailable")
                return@launch
            }
            serverSocket = server
            publishState()   // listening — NO_DEVICE with "Listening on ip:port" until a client joins
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break   // server closed by stop() (or a fatal accept error) — leave the loop
                }
                acceptClient(socket)
            }
        }
    }

    /** Wrap one freshly-accepted client socket into a live [ClientConnection] and start reading it. */
    private fun acceptClient(socket: Socket) {
        val id = "c${nextId.getAndIncrement()}"
        val conn = try {
            ClientConnection(id, socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            return
        }
        synchronized(this) { connections[id] = conn }
        conn.start()
        publishState()
        Log.i(TAG, "client $id connected from ${socket.inetAddress?.hostAddress}")
    }

    /** Fan a line out to every connected client (DASH → modules). Each module ignores what isn't
     *  addressed to its id, exactly as it would on any shared medium. */
    override fun send(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val targets = synchronized(this) { connections.values.toList() }
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            targets.forEach { conn ->
                runCatching { conn.write(bytes) }
                    .onFailure { closeClient(conn.id, "write failed: ${it.message}") }
            }
        }
    }

    /** Send to exactly one client (roadmap 1.4.10 — the Serial Monitor's per-device target). An
     *  unknown key (client gone) is a silent no-op. */
    override fun send(line: String, deviceKey: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val conn = synchronized(this) { connections[deviceKey] } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { conn.write(bytes) }
                .onFailure { closeClient(conn.id, "write failed: ${it.message}") }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        // Closing the server socket unblocks the accept() call; closing each client unblocks its
        // reader. Both loops then fall out on their own.
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        val open = synchronized(this) {
            val all = connections.values.toList()
            connections.clear()
            all
        }
        open.forEach { it.close() }
        _devices.value = emptyList()
        _status.value = TransportStatus(TransportState.NO_DEVICE, "Stopped")
    }

    /** Close and forget one client (on disconnect, read error, or write failure). */
    private fun closeClient(id: String, reason: String) {
        val conn = synchronized(this) { connections.remove(id) } ?: return
        conn.close()
        publishState()
        Log.i(TAG, "client $id closed — $reason")
    }

    /** Publish the per-client picture up to the layers above: the [devices] list (one entry per open
     *  client, for the Serial Monitor's selector) and the collapsed [status] — CONNECTED if any
     *  client is connected, else NO_DEVICE while the server is up and listening. */
    private fun publishState() {
        synchronized(this) {
            _devices.value = connections.values.map { TransportDevice(it.id, it.label, tag) }
            val n = connections.size
            val ip = localIpv4()
            val here = "${ip ?: "?"}:$PORT"
            // The address is published as its own field as well as inside the sentence: the sentence is
            // for a human reading a status line, the field is for Transport Manager's copyable "point
            // your board at" panel. Null while the IP is unknown — better no panel than a "?:3274" a
            // builder might actually type in.
            val address = ip?.let { "$it:$PORT" }
            _status.value = when {
                n > 0 -> TransportStatus(
                    TransportState.CONNECTED,
                    if (n == 1) "1 module on WiFi @ $here" else "$n modules on WiFi @ $here",
                    address
                )
                else -> TransportStatus(TransportState.NO_DEVICE, "Listening on $here", address)
            }
        }
    }

    /** This device's LAN IPv4 address, for the status line so a module author knows where to point
     *  firmware. Enumerating interfaces needs no permission (unlike ConnectivityManager). Returns the
     *  first site-local, non-loopback IPv4 — the address a module on the same network reaches us on. */
    private fun localIpv4(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

    /**
     * One connected client, the TCP analogue of the USB transport's DeviceConnection. Owns its own
     * reader coroutine and — the 1.4.10 requirement — its own [FrameAssembler], so its block framing
     * can never bleed into another client's stream. A read returning -1 (clean close) or throwing
     * (reset) closes exactly this client and no other.
     */
    private inner class ClientConnection(
        val id: String,
        private val socket: Socket
    ) : java.io.Closeable {

        val label: String = socket.inetAddress?.hostAddress ?: id

        init {
            // The idle horizon — the whole reason a dead WiFi board is ever noticed (roadmap 1.5.10).
            // A board that loses power sends no FIN and no RST: to TCP it is indistinguishable from a
            // board that simply has nothing to say, so without this the reader blocks in read() for
            // ever, the client is never closed, and the card claims a link to a board lying on the
            // bench unplugged. Writes don't catch it either — DISCOVER lands in the local send buffer
            // and returns success. So silence itself has to be the signal.
            //
            // DASH_ABSENT_MS is the right length because it is the same clock everything else ages on:
            // the sweep sends DISCOVER every 30 s and every live module answers HELLO, so 75 s of total
            // silence is two and a half missed sweeps — gone, not quiet. Bluetooth needs no equivalent;
            // its radio link supervision throws inside seconds on its own.
            runCatching { socket.soTimeout = DASH_ABSENT_MS.toInt() }
        }

        // This client's private framing state — the heart of the per-client guarantee. Each frame
        // carries this socket's key as it leaves the assembler (1.4.14), so an install can be failed
        // the instant its client disconnects.
        private val assembler = FrameAssembler { frame -> _incoming.tryEmit(InboundFrame(frame, tag, id)) }
        private val output: OutputStream = socket.getOutputStream()
        private var readerJob: Job? = null

        fun start() {
            readerJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(READ_BUF)
                var reason = "peer closed"
                try {
                    val input = socket.getInputStream()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break                 // peer closed cleanly
                        if (n > 0) assembler.feed(buf, n)
                    }
                } catch (e: SocketTimeoutException) {
                    // Nothing at all for the whole idle horizon — a powered-down or out-of-range board.
                    reason = "silent for ${DASH_ABSENT_MS / 1000}s"
                } catch (e: Exception) {
                    reason = "link dropped (${e.javaClass.simpleName})"
                } finally {
                    closeClient(id, reason)
                }
            }
        }

        /** Writes are serialised on the stream so two fan-out sends can't interleave bytes. */
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
        /** The DASH TCP port — "DASH" on a phone keypad (D-A-S-H → 3-2-7-4). Unprivileged (>1024), so
         *  it honours the no-root constraint. This is the number every WiFi module's firmware targets
         *  (transport.md); changing it is a protocol decision. */
        const val PORT = 3274
        const val READ_BUF = 1024
        const val TAG = "DashWifiTcp"
    }
}
