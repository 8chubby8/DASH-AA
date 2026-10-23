package com.dash.android.aa.wireless

import com.dash.android.aa.protocol.AaLink
import com.dash.android.aa.protocol.ProtoMessage
import com.dash.android.aa.protocol.proto
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** What the phone is told over Bluetooth: the network to join, and where DASH waits on it. */
data class WirelessNetwork(
    val ssid: String,
    val password: String,
    /** The hosting adapter's hardware address, `AA:BB:CC:DD:EE:FF`. */
    val bssid: String,
    /** DASH's address on that network. */
    val address: String,
    val port: Int = WirelessSetup.PORT,
)

/**
 * Wireless Android Auto's start (DASH-AA 1.2.3): the few messages over Bluetooth that tell the phone how
 * to reach DASH on Wi-Fi. After them the phone joins the network, opens a connection to [PORT], and from
 * there it is the same Android Auto as by cable — the same framing, TLS and channels ([TcpLink]).
 *
 * A message is `length(2) id(2)` then a protobuf of `length` bytes, both numbers big-endian. Field numbers
 * from aasdk's and the open wireless adapters' published `.proto` files, as for the rest of the protocol.
 *
 * The order, as phones keep it: DASH sends [START_REQUEST] (its address and port); the phone asks for the
 * network with [INFO_REQUEST]; DASH answers [INFO_RESPONSE] (name, password, BSSID, security); the phone
 * reports [START_RESPONSE] and [CONNECTION_STATUS] as it joins.
 *
 * Plain Kotlin on streams, so native takes it as it is; only how the Bluetooth service is offered and the
 * network hosted is each platform's own.
 */
object WirelessSetup {
    /** The Bluetooth service phones look for on a car that offers wireless Android Auto. */
    const val SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
    /** Where head units wait for the phone on Wi-Fi. */
    const val PORT = 5288

    const val START_REQUEST = 1
    const val INFO_REQUEST = 2
    const val INFO_RESPONSE = 3
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    const val CONNECTION_STATUS = 6
    const val START_RESPONSE = 7

    private const val SECURITY_WPA2_PERSONAL = 8
    private const val ACCESS_POINT_STATIC = 0

    fun startRequest(n: WirelessNetwork): ByteArray = proto { string(1, n.address); uint(2, n.port) }

    fun infoResponse(n: WirelessNetwork): ByteArray = proto {
        string(1, n.ssid)
        string(2, n.password)
        string(3, n.bssid)
        uint(4, SECURITY_WPA2_PERSONAL)
        uint(5, ACCESS_POINT_STATIC)
    }

    fun frame(id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size shr 8).toByte(); out[1] = payload.size.toByte()
        out[2] = (id shr 8).toByte(); out[3] = id.toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** One message, or null at the end of the stream. */
    fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val d = DataInputStream(input)
        val size = try { d.readUnsignedShort() } catch (e: EOFException) { return null }
        val id = d.readUnsignedShort()
        val payload = ByteArray(size)
        d.readFully(payload)
        return id to payload
    }

    /**
     * Tell the phone how to reach DASH, then answer it until the Bluetooth link closes. [onStep] hears each
     * step in plain words. Returns when the phone closes the link (it may keep it open for the whole session).
     */
    fun talk(input: InputStream, output: OutputStream, network: WirelessNetwork, onStep: (String) -> Unit) {
        output.write(frame(START_REQUEST, startRequest(network))); output.flush()
        while (true) {
            val (id, payload) = readFrame(input) ?: return
            when (id) {
                INFO_REQUEST -> {
                    output.write(frame(INFO_RESPONSE, infoResponse(network))); output.flush()
                    onStep("joining")
                }
                START_RESPONSE -> onStep("started, status ${ProtoMessage.parse(payload).int(3) ?: 0}")
                CONNECTION_STATUS -> onStep("connection status ${ProtoMessage.parse(payload).int(1) ?: 0}")
                else -> onStep("message $id, ${payload.size} bytes")
            }
        }
    }
}

/**
 * Android Auto over Wi-Fi: the phone's connection to [WirelessSetup.PORT] as an [AaLink]. Reads time out
 * every [READ_TIMEOUT_MS], as the USB link's do, so a closed link is noticed promptly.
 *
 * **Writes time out too**, as the USB link's do after two seconds. A socket has no write limit of its own:
 * a phone that stops reading leaves a write waiting for ever, and with it every thread that wants to send
 * — the session could then never end, and the phone was turned away as "already running" (seen
 * 2026-10-23). A write held past [WRITE_TIMEOUT_MS] closes the link, which ends the write and the session.
 */
class TcpLink(private val socket: Socket, override val description: String) : AaLink {
    @Volatile private var closed = false
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val writeLock = Any()
    @Volatile private var writingSince = 0L

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        Thread({
            while (!closed) {
                Thread.sleep(READ_TIMEOUT_MS.toLong())
                val since = writingSince
                if (since != 0L && System.currentTimeMillis() - since > WRITE_TIMEOUT_MS) {
                    android.util.Log.w("DashAaWireless", "the phone stopped taking data over Wi-Fi — closing the link")
                    close()
                }
            }
        }, "dash-aa-wireless-write-watch").apply { isDaemon = true }.start()
    }

    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        return try {
            input.read(buffer)
        } catch (e: SocketTimeoutException) {
            0
        } catch (e: java.io.IOException) {
            -1
        }
    }

    override fun write(data: ByteArray) {
        synchronized(writeLock) {
            if (closed) throw java.io.IOException("the Wi-Fi link is closed")
            writingSince = System.currentTimeMillis()
            try { output.write(data); output.flush() } finally { writingSince = 0L }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 250
        const val WRITE_TIMEOUT_MS = 2000L
    }
}
