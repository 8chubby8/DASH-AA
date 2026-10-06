package com.dash.android.transport.bluetooth.linux

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * DASH-AA — the three things Android's Bluetooth API did for upstream's SPP transport, done against
 * Linux's BlueZ directly:
 *
 * 1. **Which modules are paired** — [bondedDevices], from BlueZ's own `bluetoothctl`. Pairing stays the
 *    operating system's job, exactly as upstream leaves it to Android's settings (transport.md): DASH
 *    never pairs, it only dials what the user has bonded.
 * 2. **Which RFCOMM channel the module's SPP server is on** — [sppChannel], an SDP query for the Serial
 *    Port service, the lookup Android's `createRfcommSocketToServiceRecord` makes on our behalf.
 * 3. **A byte stream to it** — [RfcommSocket], a kernel RFCOMM socket.
 *
 * Every failure degrades: no controller, radio off, BlueZ tools missing or a library that will not
 * load all read as "no Bluetooth", never as a crash — the capability-detection rule.
 */
object BlueZ {
    private const val TAG = "DashBlueZ"

    data class BondedDevice(val address: String, val name: String)

    enum class Adapter { POWERED, OFF, ABSENT }

    fun adapter(): Adapter {
        val out = bluetoothctl("show") ?: return Adapter.ABSENT
        if (out.contains("No default controller", ignoreCase = true)) return Adapter.ABSENT
        val powered = Regex("""Powered:\s*(\w+)""").find(out)?.groupValues?.get(1)
        return when (powered) {
            "yes" -> Adapter.POWERED
            null -> Adapter.ABSENT
            else -> Adapter.OFF
        }
    }

    /** This machine's own Bluetooth address, as BlueZ reports its default controller. */
    fun adapterAddress(): String? =
        bluetoothctl("show")?.let { Regex("""Controller\s+([0-9A-Fa-f:]{17})""").find(it)?.groupValues?.get(1)?.uppercase() }

    fun bondedDevices(): List<BondedDevice> {
        // "Bonded" is the honest filter (a key exists); older BlueZ only knows "Paired".
        val out = bluetoothctl("devices", "Bonded")?.takeIf { it.contains("Device ") }
            ?: bluetoothctl("devices", "Paired")
            ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val m = Regex("""Device\s+([0-9A-Fa-f:]{17})\s*(.*)""").find(line) ?: return@mapNotNull null
            BondedDevice(m.groupValues[1].uppercase(), m.groupValues[2].trim())
        }.toList()
    }

    private fun bluetoothctl(vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("bluetoothctl") + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else text.replace(Regex("""\u001B\[[0-9;?]*[A-Za-z]"""), "")
    } catch (e: IOException) {
        null                                   // bluez-utils not installed — no Bluetooth, quietly
    }

    // ---- SDP: which channel is the SPP server on ----

    private const val SDP_RETRY_IF_BUSY = 0x01
    private const val SERIAL_PORT_SVCLASS_ID: Short = 0x1101
    private const val SDP_ATTR_REQ_RANGE = 2
    private const val RFCOMM_UUID = 0x0003

    @Suppress("FunctionName")
    private interface LibBluetooth : Library {
        fun sdp_connect(src: ByteArray, dst: ByteArray, flags: Int): Pointer?
        fun sdp_close(session: Pointer): Int
        fun sdp_uuid16_create(uuid: Pointer, data: Short): Pointer
        fun sdp_list_append(list: Pointer?, d: Pointer): Pointer
        fun sdp_service_search_attr_req(session: Pointer, search: Pointer, reqtype: Int, attrids: Pointer, rsp: PointerByReference): Int
        fun sdp_get_access_protos(rec: Pointer, protos: PointerByReference): Int
        fun sdp_get_proto_port(list: Pointer, proto: Int): Int
    }

    private val libBluetooth: LibBluetooth? by lazy {
        runCatching { Native.load("bluetooth", LibBluetooth::class.java) }
            .onFailure { Log.w(TAG, "libbluetooth unavailable — SPP channel will be assumed: ${it.message}") }
            .getOrNull()
    }

    /** The RFCOMM channel the device's Serial Port service listens on, or null if SDP cannot say. */
    fun sppChannel(address: String): Int? {
        val lib = libBluetooth ?: return null
        val session = lib.sdp_connect(ByteArray(6), bdaddr(address), SDP_RETRY_IF_BUSY) ?: return null
        try {
            val uuid = Memory(32).apply { clear() }
            lib.sdp_uuid16_create(uuid, SERIAL_PORT_SVCLASS_ID)
            val search = lib.sdp_list_append(null, uuid)
            val range = Memory(4).apply { setInt(0, 0x0000ffff) }
            val attrs = lib.sdp_list_append(null, range)
            val rsp = PointerByReference()
            if (lib.sdp_service_search_attr_req(session, search, SDP_ATTR_REQ_RANGE, attrs, rsp) != 0) return null
            var node: Pointer? = rsp.value
            while (node != null) {
                val record = node.getPointer(Native.POINTER_SIZE.toLong())
                if (record != null) {
                    val protos = PointerByReference()
                    if (lib.sdp_get_access_protos(record, protos) == 0 && protos.value != null) {
                        val ch = lib.sdp_get_proto_port(protos.value, RFCOMM_UUID)
                        if (ch in 1..30) return ch
                    }
                }
                node = node.getPointer(0)
            }
            return null
        } finally {
            lib.sdp_close(session)
        }
    }

    /** "AA:BB:CC:DD:EE:FF" → BlueZ's bdaddr_t, which stores the address least-significant byte first. */
    internal fun bdaddr(address: String): ByteArray {
        val parts = address.split(':').map { it.toInt(16).toByte() }
        require(parts.size == 6) { "not a Bluetooth address: $address" }
        return parts.reversed().toByteArray()
    }
}

/**
 * A kernel RFCOMM socket (`AF_BLUETOOTH` / `BTPROTO_RFCOMM`) driven through libc — what Android's
 * `BluetoothSocket` wraps. Outbound only: DASH dials the module's SPP server, as upstream does.
 */
class RfcommSocket private constructor(private val fd: Int, val address: String) : Closeable {

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun connect(fd: Int, addr: ByteArray, len: Int): Int
        fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun write(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun shutdown(fd: Int, how: Int): Int
        fun close(fd: Int): Int
    }

    @Volatile private var closed = false

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            val tmp = if (off == 0) b else ByteArray(len)
            val n = libc.read(fd, tmp, NativeLong(len.toLong())).toInt()
            if (n < 0) { if (closed) return -1; throw IOException("rfcomm read failed (errno ${Native.getLastError()})") }
            if (n == 0) return -1
            if (off != 0) System.arraycopy(tmp, 0, b, off, n)
            return n
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var sent = 0
            while (sent < len) {
                val chunk = b.copyOfRange(off + sent, off + len)
                val n = libc.write(fd, chunk, NativeLong(chunk.size.toLong())).toInt()
                if (n <= 0) throw IOException("rfcomm write failed (errno ${Native.getLastError()})")
                sent += n
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        libc.shutdown(fd, SHUT_RDWR)          // wakes a reader blocked in read()
        libc.close(fd)
    }

    companion object {
        private const val AF_BLUETOOTH = 31
        private const val SOCK_STREAM = 1
        private const val BTPROTO_RFCOMM = 3
        private const val SHUT_RDWR = 2

        private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

        /** Blocking connect — seconds when the module is out of range, so callers run it off the sweep. */
        fun connect(address: String, channel: Int): RfcommSocket {
            val fd = libc.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
            if (fd < 0) throw IOException("no RFCOMM socket (errno ${Native.getLastError()})")
            // struct sockaddr_rc { sa_family_t family; bdaddr_t bdaddr; uint8_t channel; } — 10 bytes padded.
            val addr = ByteArray(10)
            addr[0] = AF_BLUETOOTH.toByte()
            BlueZ.bdaddr(address).copyInto(addr, 2)
            addr[8] = channel.toByte()
            if (libc.connect(fd, addr, addr.size) != 0) {
                val errno = Native.getLastError()
                libc.close(fd)
                throw IOException("rfcomm connect to $address ch$channel failed (errno $errno)")
            }
            return RfcommSocket(fd, address)
        }
    }
}
