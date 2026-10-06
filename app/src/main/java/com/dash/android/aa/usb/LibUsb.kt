package com.dash.android.aa.usb

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The few libusb-1.0 calls DASH-AA needs, bound directly with JNA against the system library
 * (`libusb-1.0.so`, present on every desktop Linux). A direct binding rather than a Java USB library
 * because those bundle their own, often years-old, native builds; the system's libusb is the one the
 * kernel and udev are set up for.
 */
@Suppress("FunctionName")
internal interface LibUsbNative : Library {
    fun libusb_init(ctx: PointerByReference?): Int
    fun libusb_get_device_list(ctx: Pointer?, list: PointerByReference): Long
    fun libusb_free_device_list(list: Pointer, unrefDevices: Int)
    fun libusb_get_device_descriptor(dev: Pointer, desc: Pointer): Int
    fun libusb_get_bus_number(dev: Pointer): Byte
    fun libusb_get_device_address(dev: Pointer): Byte
    fun libusb_open(dev: Pointer, handle: PointerByReference): Int
    fun libusb_close(handle: Pointer)
    fun libusb_set_auto_detach_kernel_driver(handle: Pointer, enable: Int): Int
    fun libusb_claim_interface(handle: Pointer, iface: Int): Int
    fun libusb_release_interface(handle: Pointer, iface: Int): Int
    fun libusb_reset_device(handle: Pointer): Int
    fun libusb_control_transfer(handle: Pointer, requestType: Byte, request: Byte, value: Short, index: Short,
                                data: ByteArray?, length: Short, timeout: Int): Int
    fun libusb_bulk_transfer(handle: Pointer, endpoint: Byte, data: ByteArray, length: Int,
                             transferred: IntByReference, timeout: Int): Int
    fun libusb_get_string_descriptor_ascii(handle: Pointer, index: Byte, data: ByteArray, length: Int): Int
    fun libusb_error_name(code: Int): String
}

internal object LibUsb {
    const val ERROR_ACCESS = -3
    const val ERROR_NO_DEVICE = -4
    const val ERROR_TIMEOUT = -7

    /** Null when libusb cannot be loaded — Android Auto is then simply unavailable, never a crash. */
    val native: LibUsbNative? by lazy {
        runCatching {
            Native.load("usb-1.0", LibUsbNative::class.java).also { lib ->
                check(lib.libusb_init(null) == 0) { "libusb_init failed" }
            }
        }.getOrNull()
    }

    fun errorName(code: Int): String = native?.let { runCatching { it.libusb_error_name(code) }.getOrNull() } ?: "error $code"

    /** A USB device as listed — descriptor fields read once, the libusb pointer valid only in [withDevices]. */
    data class Device(
        val pointer: Pointer,
        val bus: Int,
        val address: Int,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val iProduct: Int,
    ) {
        val key: String get() = "%03d:%03d".format(bus, address)
        val idString: String get() = "%04x:%04x".format(vendorId, productId)
    }

    /** Lists the bus and runs [block] while the device pointers are valid; frees the list after. */
    fun <T> withDevices(block: (List<Device>) -> T): T? {
        val lib = native ?: return null
        val listRef = PointerByReference()
        val count = lib.libusb_get_device_list(null, listRef)
        if (count < 0) return null
        val list = listRef.value
        try {
            val devices = (0 until count.toInt()).mapNotNull { i ->
                val dev = list.getPointer(i.toLong() * Native.POINTER_SIZE) ?: return@mapNotNull null
                val desc = Memory(18)
                if (lib.libusb_get_device_descriptor(dev, desc) != 0) return@mapNotNull null
                Device(
                    pointer = dev,
                    bus = lib.libusb_get_bus_number(dev).toInt() and 0xFF,
                    address = lib.libusb_get_device_address(dev).toInt() and 0xFF,
                    vendorId = desc.getShort(8).toInt() and 0xFFFF,
                    productId = desc.getShort(10).toInt() and 0xFFFF,
                    deviceClass = desc.getByte(4).toInt() and 0xFF,
                    iProduct = desc.getByte(15).toInt() and 0xFF,
                )
            }
            return block(devices)
        } finally {
            lib.libusb_free_device_list(list, 1)
        }
    }
}
