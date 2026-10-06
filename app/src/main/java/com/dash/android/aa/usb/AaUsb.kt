package com.dash.android.aa.usb

import android.util.Log
import com.dash.android.aa.protocol.AaLink
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.IOException

/**
 * Finding the phone on USB and turning it into an Android Auto accessory.
 *
 * **Android Open Accessory (AOA).** A phone plugged into a computer is, to USB, an ordinary device —
 * charging, MTP, maybe adb. A head unit asks it to become an *accessory* with three vendor control
 * requests: what AOA version do you speak (51), here is who I am (52, six strings), now switch (53).
 * The phone drops off the bus and comes back as Google's accessory device (`18d1:2d00`, or `2d01` with
 * adb), and Android hands the accessory to Android Auto because the strings say "Android Auto". From
 * then on the accessory's two bulk endpoints are the pipe the whole session runs over.
 *
 * **Which devices are asked.** Only ones that look like phones: a known phone maker's vendor id, or a
 * device showing an MTP/PTP or adb interface. A keyboard or a webcam is never sent a vendor request.
 *
 * **Permission.** Opening a USB device needs write access to its node in `/dev/bus/usb`. Desktop Linux
 * grants that to the logged-in user only for device kinds udev has been told about, so DASH-AA ships a
 * rule (`packaging/51-dash-aa.rules`) and, without it, reports exactly that rather than failing
 * silently — the capability-detection rule.
 */
object AaUsb {
    private const val TAG = "DashAaUsb"

    const val GOOGLE_VID = 0x18D1
    val ACCESSORY_PIDS = 0x2D00..0x2D05

    /** Phone makers' USB vendor ids — a phone from one of these is offered the accessory switch. */
    val PHONE_VENDORS = setOf(
        0x18D1, // Google
        0x04E8, // Samsung
        0x22B8, // Motorola
        0x1004, // LG
        0x0BB4, // HTC
        0x0FCE, // Sony
        0x12D1, // Huawei
        0x2717, // Xiaomi
        0x2A70, // OnePlus
        0x22D9, // OPPO / Realme
        0x2D95, // vivo
        0x2E04, // HMD / Nokia
        0x05C6, // Qualcomm reference devices
        0x1EBF, // Fairphone (older)
        0x2A45, // Meizu
        0x19D2, // ZTE
        0x17EF, // Lenovo
        // Deliberately absent: Foxconn (0x0489) — it builds phones, but also the Bluetooth/WiFi radios
        // inside laptops (this G14's is 0489:e11e), which must never be asked to become an accessory.
        // A Foxconn-built phone is still found by its MTP or adb interface.
    )

    sealed interface Found {
        /** A phone already in accessory mode — open it. */
        data class Accessory(val key: String, val id: String) : Found
        /** A phone in normal mode — switch it. */
        data class Phone(val key: String, val id: String) : Found
        /** Something phone-like we may not open — the udev rule is missing. */
        data class NoPermission(val key: String, val id: String) : Found
    }

    fun available(): Boolean = LibUsb.native != null

    /** What is on the bus right now, best first: an accessory beats a phone beats a refusal. */
    fun scan(skip: Set<String> = emptySet()): Found? = LibUsb.withDevices { devices ->
        devices.firstNotNullOfOrNull { d ->
            if (d.key in skip) null
            else if (d.vendorId == GOOGLE_VID && d.productId in ACCESSORY_PIDS) Found.Accessory(d.key, d.idString)
            else null
        } ?: devices.firstNotNullOfOrNull { d ->
            if (d.key in skip || d.deviceClass == 9 /* hub */) return@firstNotNullOfOrNull null
            if (d.vendorId == GOOGLE_VID && d.productId in ACCESSORY_PIDS) return@firstNotNullOfOrNull null
            if (!looksLikePhone(d)) return@firstNotNullOfOrNull null
            val handle = PointerByReference()
            when (val rc = LibUsb.native!!.libusb_open(d.pointer, handle)) {
                0 -> { LibUsb.native!!.libusb_close(handle.value); Found.Phone(d.key, d.idString) }
                LibUsb.ERROR_ACCESS -> Found.NoPermission(d.key, d.idString)
                else -> { Log.d(TAG, "${d.idString} would not open: ${LibUsb.errorName(rc)}"); null }
            }
        }
    }

    private fun looksLikePhone(d: LibUsb.Device): Boolean {
        if (d.vendorId in PHONE_VENDORS) return true
        return SysfsUsb.interfaces(d.bus, d.address).any { (cls, sub, proto) ->
            cls == 0x06 ||                                         // still image / MTP / PTP
                (cls == 0xFF && sub == 0x42 && proto == 0x01) ||   // adb
                (cls == 0xFF && sub == 0xFF && proto == 0x00)      // vendor MTP on some phones
        }
    }

    /**
     * Ask the phone at [key] to become an Android Auto accessory. True if it agreed; it then leaves the
     * bus and returns as an accessory a moment later.
     */
    fun switchToAccessory(key: String): Boolean = LibUsb.withDevices { devices ->
        val lib = LibUsb.native!!
        val d = devices.firstOrNull { it.key == key } ?: return@withDevices false
        val ref = PointerByReference()
        if (lib.libusb_open(d.pointer, ref) != 0) return@withDevices false
        val h = ref.value
        try {
            val version = ByteArray(2)
            val rc = lib.libusb_control_transfer(h, 0xC0.toByte(), 51, 0, 0, version, 2, 1000)
            val aoa = (version[0].toInt() and 0xFF) or ((version[1].toInt() and 0xFF) shl 8)
            if (rc < 0 || aoa < 1) {
                Log.i(TAG, "${d.idString} does not speak accessory mode (${if (rc < 0) LibUsb.errorName(rc) else "v$aoa"})")
                return@withDevices false
            }
            val strings = listOf(
                "Android",            // manufacturer — with the model below, what Android Auto listens for
                "Android Auto",       // model
                "Android Auto",       // description
                "2.0.1",              // version
                "https://github.com/8chubby8/DASH",
                "DASH-AA-0001",       // serial
            )
            strings.forEachIndexed { i, s ->
                val bytes = (s + "\u0000").toByteArray(Charsets.UTF_8)
                lib.libusb_control_transfer(h, 0x40, 52, 0, i.toShort(), bytes, bytes.size.toShort(), 1000)
            }
            val start = lib.libusb_control_transfer(h, 0x40, 53, 0, 0, null, 0, 1000)
            Log.i(TAG, "${d.idString} asked to switch (AOA v$aoa): ${if (start < 0) LibUsb.errorName(start) else "ok"}")
            start >= 0
        } finally {
            lib.libusb_close(h)
        }
    } ?: false

    /** Open the accessory at [key] and claim its bulk pipe. */
    fun openAccessory(key: String): AccessoryLink? = LibUsb.withDevices { devices ->
        val lib = LibUsb.native!!
        val d = devices.firstOrNull { it.key == key } ?: return@withDevices null
        val ref = PointerByReference()
        val rc = lib.libusb_open(d.pointer, ref)
        if (rc != 0) { Log.w(TAG, "accessory ${d.idString} would not open: ${LibUsb.errorName(rc)}"); return@withDevices null }
        val h = ref.value
        val pipe = findBulkPipe(h)
        if (pipe == null) { lib.libusb_close(h); Log.w(TAG, "accessory has no bulk pipe"); return@withDevices null }
        lib.libusb_set_auto_detach_kernel_driver(h, 1)
        val claim = lib.libusb_claim_interface(h, pipe.iface)
        if (claim != 0) {
            lib.libusb_close(h)
            Log.w(TAG, "could not claim the accessory interface: ${LibUsb.errorName(claim)}")
            return@withDevices null
        }
        val product = ByteArray(128).let { buf ->
            val n = if (d.iProduct != 0) lib.libusb_get_string_descriptor_ascii(h, d.iProduct.toByte(), buf, buf.size) else 0
            if (n > 0) String(buf, 0, n) else d.idString
        }
        Log.i(TAG, "accessory open: $product, iface ${pipe.iface}, in 0x%02x out 0x%02x".format(pipe.inEp, pipe.outEp))
        AccessoryLink(h, pipe, "USB $key ($product)")
    }

    data class BulkPipe(val iface: Int, val inEp: Int, val outEp: Int)

    /** Walks the active configuration descriptor for the first vendor interface with two bulk endpoints. */
    private fun findBulkPipe(h: Pointer): BulkPipe? {
        val lib = LibUsb.native!!
        val buf = ByteArray(1024)
        val n = lib.libusb_control_transfer(h, 0x80.toByte(), 6, (2 shl 8).toShort(), 0, buf, buf.size.toShort(), 1000)
        if (n <= 0) return null
        var i = 0
        var iface = -1
        var ifaceClass = -1
        var inEp = -1
        var outEp = -1
        while (i + 1 < n) {
            val len = buf[i].toInt() and 0xFF
            if (len < 2) break
            when (buf[i + 1].toInt() and 0xFF) {
                4 -> {                                                     // interface
                    if (iface >= 0 && inEp >= 0 && outEp >= 0 && ifaceClass == 0xFF) return BulkPipe(iface, inEp, outEp)
                    iface = buf[i + 2].toInt() and 0xFF
                    ifaceClass = buf[i + 5].toInt() and 0xFF
                    inEp = -1; outEp = -1
                }
                5 -> {                                                     // endpoint
                    val addr = buf[i + 2].toInt() and 0xFF
                    val attrs = buf[i + 3].toInt() and 0x03
                    if (attrs == 2) { if (addr and 0x80 != 0) { if (inEp < 0) inEp = addr } else if (outEp < 0) outEp = addr }
                }
            }
            i += len
        }
        return if (iface >= 0 && inEp >= 0 && outEp >= 0) BulkPipe(iface, inEp, outEp) else null
    }

    /**
     * The accessory's bulk pipe as an [AaLink]. Reads time out every [READ_TIMEOUT_MS] so a closed link
     * is noticed promptly; the handle itself is only released by [release], after the reader has left,
     * because closing a libusb handle under an in-flight transfer is undefined behaviour.
     */
    class AccessoryLink internal constructor(
        private val handle: Pointer,
        private val pipe: BulkPipe,
        override val description: String,
    ) : AaLink {
        @Volatile private var closed = false
        @Volatile private var released = false
        private val lib = LibUsb.native!!
        private val writeLock = Any()

        override fun read(buffer: ByteArray): Int {
            if (closed) return -1
            val got = IntByReference()
            val rc = lib.libusb_bulk_transfer(handle, pipe.inEp.toByte(), buffer, buffer.size, got, READ_TIMEOUT_MS)
            return when {
                closed -> -1
                rc == 0 || rc == LibUsb.ERROR_TIMEOUT -> got.value
                else -> { Log.i(TAG, "accessory read ended: ${LibUsb.errorName(rc)}"); -1 }
            }
        }

        override fun write(data: ByteArray) {
            synchronized(writeLock) {
                if (closed) throw IOException("accessory closed")
                var sent = 0
                while (sent < data.size) {
                    val chunk = if (sent == 0) data else data.copyOfRange(sent, data.size)
                    val done = IntByReference()
                    val rc = lib.libusb_bulk_transfer(handle, pipe.outEp.toByte(), chunk, chunk.size, done, WRITE_TIMEOUT_MS)
                    if (rc != 0 && done.value == 0) throw IOException("accessory write failed: ${LibUsb.errorName(rc)}")
                    sent += done.value
                }
            }
        }

        override fun close() { closed = true }

        /** Release the interface and the handle. With [reset], the phone is also re-enumerated — it leaves
         *  accessory mode and returns as a phone, ready to be switched afresh with a new video shape. */
        fun release(reset: Boolean) {
            closed = true
            if (released) return
            released = true
            synchronized(writeLock) {
                lib.libusb_release_interface(handle, pipe.iface)
                if (reset) lib.libusb_reset_device(handle)
                lib.libusb_close(handle)
            }
        }

        private companion object {
            const val READ_TIMEOUT_MS = 250
            const val WRITE_TIMEOUT_MS = 2000
        }
    }
}

/** Interface classes from sysfs — readable without opening the device, so no permission is needed. */
internal object SysfsUsb {
    fun interfaces(bus: Int, address: Int): List<Triple<Int, Int, Int>> {
        val root = java.io.File("/sys/bus/usb/devices")
        val dev = root.listFiles().orEmpty().firstOrNull { d ->
            runCatching {
                java.io.File(d, "busnum").readText().trim().toInt() == bus &&
                    java.io.File(d, "devnum").readText().trim().toInt() == address
            }.getOrDefault(false)
        } ?: return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.name.startsWith(dev.name + ":") }
            .mapNotNull { i ->
                runCatching {
                    Triple(
                        java.io.File(i, "bInterfaceClass").readText().trim().toInt(16),
                        java.io.File(i, "bInterfaceSubClass").readText().trim().toInt(16),
                        java.io.File(i, "bInterfaceProtocol").readText().trim().toInt(16),
                    )
                }.getOrNull()
            }
    }
}
