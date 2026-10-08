package com.dash.android.connections

import kotlinx.coroutines.flow.StateFlow

/**
 * The machine's Bluetooth, as DASH's Connections › Bluetooth tab sees it (DASH-AA 1.1.6): the radio, the
 * name phones see, the devices paired and nearby, and **pairing itself**.
 *
 * **The seam between DASH and the platform**, as [com.dash.android.display.DisplaySystem] is for the
 * screens. The tab and the pairing prompt only ever talk to this interface. DASH-AA's implementation talks
 * to BlueZ over the system message bus (`connections/linux/`), and **is BlueZ's pairing agent** whenever
 * DASH runs: with no desktop there is nothing else to answer "does this code match?", and a phone or a
 * module asking to pair would get no answer at all.
 *
 * **For native:** Android owns pairing; native's implementation lists the bonded devices through
 * `BluetoothAdapter`, opens Android's own pairing screen, and never raises a [PairingRequest].
 *
 * **Capability detection, as everywhere.** With no Bluetooth (no adapter, no BlueZ), [BluetoothState.available]
 * is false, [BluetoothState.note] says why in plain words, and nothing else is affected.
 *
 * Every call returns at once; the work happens off the caller's thread, and [state] shows the result.
 */
interface BluetoothSystem {
    val state: StateFlow<BluetoothState>

    /** The question being asked of the person now — a code to check or a PIN to type — or null. */
    val request: StateFlow<PairingRequest?>

    fun start()

    fun setPowered(on: Boolean)

    /** The name phones and modules see this machine by. */
    fun setName(name: String)

    /** Let devices that are looking find this machine — for [VISIBLE_SECONDS], then hidden again. */
    fun setVisible(on: Boolean)

    /** Look for devices nearby, or stop looking. Looking stops by itself after [SEARCH_SECONDS]. */
    fun search(on: Boolean)

    fun pair(address: String)
    fun connect(address: String)
    fun disconnect(address: String)

    /** Unpair: the device must pair again to come back. */
    fun forget(address: String)

    /** Answer [request]: yes or no, with the PIN or passkey typed when it asked for one. */
    fun answer(request: PairingRequest, accept: Boolean, typed: String? = null)

    companion object {
        const val VISIBLE_SECONDS = 180
        const val SEARCH_SECONDS = 60
    }
}

data class BluetoothState(
    val available: Boolean,
    val note: String,
    val powered: Boolean = false,
    /** What phones see this machine as. */
    val name: String = "",
    val address: String = "",
    val visible: Boolean = false,
    val searching: Boolean = false,
    val devices: List<BluetoothDevice> = emptyList(),
    /** The device being paired now, by address. */
    val pairing: String? = null,
    /** The last thing that did not happen, in plain words; null when the last one did. */
    val failure: String? = null,
)

data class BluetoothDevice(
    val address: String,
    val name: String,
    val kind: DeviceKind,
    val paired: Boolean,
    val connected: Boolean,
    /** Signal strength while searching, in dBm; null when not heard lately. */
    val rssi: Int? = null,
)

/**
 * What a device is to DASH — the Bluetooth tab's grouping. A **module** is one whose name carries the
 * DASH marker (module-sdk.md §12, the rule the Bluetooth transport finds modules by); a **phone** is what
 * the device says it is.
 */
enum class DeviceKind(val label: String) {
    PHONE("Phones"), MODULE("Modules"), AUDIO("Sound"), INPUT("Keyboards and controllers"), OTHER("Other");

    companion object {
        const val MODULE_MARKER = "D.A.S.H"

        /**
         * From the device's name and its Bluetooth *class of device* — the 24 bits every device announces,
         * whose major class (bits 8–12) says phone, computer, audio and so on (Bluetooth Assigned Numbers).
         */
        fun of(name: String, deviceClass: Int?, icon: String?): DeviceKind = when {
            name.contains(MODULE_MARKER) -> MODULE
            deviceClass != null && (deviceClass shr 8 and 0x1F) == 0x02 -> PHONE
            icon == "phone" -> PHONE
            deviceClass != null && (deviceClass shr 8 and 0x1F) == 0x04 -> AUDIO
            icon?.startsWith("audio") == true -> AUDIO
            deviceClass != null && (deviceClass shr 8 and 0x1F) == 0x05 -> INPUT
            icon?.startsWith("input") == true -> INPUT
            else -> OTHER
        }
    }
}

/**
 * Something a device has asked that only the person can answer. Pairing is never silent: every new
 * device is seen and agreed to (a module's fixed PIN is typed by the person, not guessed by DASH).
 */
sealed class PairingRequest {
    abstract val address: String
    abstract val name: String

    /** Both screens show [code]; pair only if they match. Phones pair this way. */
    data class Confirm(override val address: String, override val name: String, val code: String) : PairingRequest()

    /** Type this device's PIN — most modules' radios (HC-05: 1234 or 0000). */
    data class EnterPin(override val address: String, override val name: String) : PairingRequest()

    /** Type the six-digit passkey the device shows. */
    data class EnterPasskey(override val address: String, override val name: String) : PairingRequest()

    /** Type [code] on the device (a keyboard). */
    data class Show(override val address: String, override val name: String, val code: String) : PairingRequest()

    /** A device asks to pair without a code — allow it or not. */
    data class Allow(override val address: String, override val name: String) : PairingRequest()
}
