package com.dash.android.core

/**
 * The standard system signal vocabulary — DASH's in-code copy of `system_commands.md`, which is the
 * authoritative reference (arduino.md §5a). Every standard signal maps to exactly one of the three
 * controller behaviours; the behaviour belongs to the **signal**, never to the module — a module just
 * sends its value and the controller looks the handling up here.
 *
 * `system_commands.md` is explicitly a working document ("Tentative Vocabulary. Incomplete."), so this
 * table will grow with it — when a signal is added there, it is added here. If the two ever disagree,
 * the markdown wins and this file is wrong.
 *
 * A `BROADCAST` whose function is not in this table is **dropped and logged** for now: the custom-signal
 * fallthrough (user-defined signals with user-defined handling) and the patch-bay override are the
 * crossroads' future configurable stages, deliberately not built in 1.4.7 — but dropped traffic still
 * shows on the Serial Monitor wire tap, so nothing disappears silently.
 */
enum class SignalBehaviour {
    /** Boolean / multi-state / string state: update the store AND fire an event, in one step. */
    STORE_AND_EVENT,

    /** Continuous values: silent store update, no event — readers poll the store at their own rate.
     *  Firing events on high-frequency continuous signals would flood listeners for no benefit. */
    STORE_ONLY,

    /** Momentary controls: fire and forget. No value travels, nothing is stored — there is no state. */
    EVENT_ONLY
}

object SystemCommands {

    /** Behaviour of [function], or null if it is not a standard signal. */
    fun behaviourOf(function: String): SignalBehaviour? = BEHAVIOURS[function]

    /** Every standard signal name, sorted — the full known vocabulary. The Signal Monitor (1.4.10)
     *  lists these against the sourceless store's current values. */
    fun allFunctions(): List<String> = BEHAVIOURS.keys.sorted()

    private val BEHAVIOURS: Map<String, SignalBehaviour> = buildMap {
        // Doors & Access
        storeAndEvent(
            "door_driver_open", "door_passenger_open", "door_rear_left_open", "door_rear_right_open",
            "door_boot_open", "door_boot_glass_open", "door_bonnet_open", "door_fuel_flap_open",
            "charge_port_open"
        )
        // Windows
        storeAndEvent("window_driver_up", "window_passenger_up")
        // Lights
        storeAndEvent(
            "headlights_on", "fog_lights_front_on", "fog_lights_rear_on", "hazard_lights_on",
            "interior_lights_on", "indicator_left_on", "indicator_right_on"
        )
        // Wipers
        storeAndEvent("wipers_front_state", "wipers_rear_on")
        // Vehicle State — stateful
        storeAndEvent("ignition_state", "handbrake_on", "gear_position")
        // Vehicle State — continuous
        storeOnly(
            "vehicle_speed", "steering_angle", "engine_rpm", "fuel_level", "coolant_temp",
            "ambient_temp", "ambient_light"
        )
        // Safety
        storeAndEvent("seatbelt_driver_fastened", "seatbelt_passenger_fastened")
        // EV / Charging
        storeAndEvent("charge_connected")
        storeOnly("charge_level")
        // Media — stateful
        storeAndEvent("media_muted", "media_source")
        // Controls — momentary
        eventOnly(
            "media_play_pause", "media_next", "media_prev", "media_volume_up", "media_volume_down",
            "voice_activate", "button_home_pressed"
        )
    }

    private fun MutableMap<String, SignalBehaviour>.storeAndEvent(vararg functions: String) =
        functions.forEach { put(it, SignalBehaviour.STORE_AND_EVENT) }

    private fun MutableMap<String, SignalBehaviour>.storeOnly(vararg functions: String) =
        functions.forEach { put(it, SignalBehaviour.STORE_ONLY) }

    private fun MutableMap<String, SignalBehaviour>.eventOnly(vararg functions: String) =
        functions.forEach { put(it, SignalBehaviour.EVENT_ONLY) }
}
