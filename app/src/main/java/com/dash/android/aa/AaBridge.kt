package com.dash.android.aa

import android.util.Log
import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.AaMessages
import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The sourceless core, feeding Android Auto (DASH-AA; Roger, 2026-10-05).
 *
 * It sits where upstream's desks sit — reading [SystemState], never a module — so it inherits the core's
 * guarantees for free: a value is "the gear", not "what module X said"; two modules may feed one signal;
 * and **no module ever learns Android Auto exists**. A steering-wheel SYSTEM module built for upstream
 * DASH drives the phone here with no change to its firmware, which is the point.
 *
 * **Sensors: offered only when DASH can honestly feed them.** Android Auto trusts a sensor the head unit
 * advertises — advertise speed and it stops using the phone's own GPS speed. So at each connection a
 * sensor is advertised only if the core already holds its signal (a module is reporting it), and
 * otherwise the phone is left to its own judgement. Driving status is always sent, as unrestricted — the
 * restriction decisions belong to the phone's own logic, not to DASH.
 *
 * | Core signal (system_commands.md) | Android Auto |
 * |---|---|
 * | `headlights_on` | night mode (Night source: Headlights) |
 * | `vehicle_speed` (km/h) | car speed |
 * | `gear_position` | gear |
 * | `handbrake_on` | parking brake |
 * | `engine_rpm` | RPM |
 * | `media_play_pause` / `media_next` / `media_prev` | media keys |
 * | `voice_activate` | the assistant |
 * | `button_home_pressed` | home |
 * | `media_volume_up` / `media_volume_down` / `media_muted` | DASH-AA's own output volume and mute |
 */
class AaBridge(
    private val state: SystemState,
    private val scope: CoroutineScope,
    private val onVolumeStep: (Int) -> Unit,
    private val onMuted: (Boolean) -> Unit,
) {
    /** The sensors to advertise for a connection starting now. */
    fun sensorsFor(settings: AaSettings): List<Int> = buildList {
        add(Aa.SENSOR_DRIVING_STATUS)
        if (nightFor(settings) != null) add(Aa.SENSOR_NIGHT)
        if (state.current("vehicle_speed")?.toDoubleOrNull() != null) add(Aa.SENSOR_CAR_SPEED)
        if (state.current("gear_position") != null) add(Aa.SENSOR_GEAR)
        if (state.current("handbrake_on") != null) add(Aa.SENSOR_PARKING_BRAKE)
        if (state.current("engine_rpm")?.toDoubleOrNull() != null) add(Aa.SENSOR_RPM)
    }

    /** The current reading for a sensor the phone just started, or null to send nothing yet. */
    fun eventFor(type: Int, settings: AaSettings): ByteArray? = when (type) {
        Aa.SENSOR_DRIVING_STATUS -> AaMessages.drivingStatus(0)
        Aa.SENSOR_NIGHT -> nightFor(settings)?.let { AaMessages.nightMode(it) }
        Aa.SENSOR_CAR_SPEED -> state.current("vehicle_speed")?.toDoubleOrNull()?.let { AaMessages.speed(kmhToMmPerSecond(it)) }
        Aa.SENSOR_GEAR -> state.current("gear_position")?.let { gearCode(it) }?.let { AaMessages.gear(it) }
        Aa.SENSOR_PARKING_BRAKE -> state.current("handbrake_on")?.let { AaMessages.parkingBrake(it.isTrue()) }
        Aa.SENSOR_RPM -> state.current("engine_rpm")?.toDoubleOrNull()?.let { AaMessages.rpm((it * 1000).toInt()) }
        else -> null
    }

    private fun nightFor(settings: AaSettings): Boolean? = when (settings.nightSource) {
        NightSource.DAY -> false
        NightSource.NIGHT -> true
        NightSource.AUTO -> state.current("headlights_on")?.isTrue()
    }

    /**
     * Follow the core for the life of one connection: push sensor changes as they happen, and turn
     * the event-only controls into key presses. Returns the job to cancel when the session ends.
     */
    fun attach(settings: () -> AaSettings, sendSensor: (Int, ByteArray) -> Unit, sendKey: (Int) -> Unit): Job =
        scope.launch {
            val since = System.currentTimeMillis()
            launch {
                state.values
                    .map { v -> listOf("headlights_on", "vehicle_speed", "gear_position", "handbrake_on", "engine_rpm").associateWith { v[it]?.value } }
                    .distinctUntilChanged()
                    .collect {
                        val s = settings()
                        listOf(Aa.SENSOR_NIGHT, Aa.SENSOR_CAR_SPEED, Aa.SENSOR_GEAR, Aa.SENSOR_PARKING_BRAKE, Aa.SENSOR_RPM)
                            .forEach { type -> eventFor(type, s)?.let { sendSensor(type, it) } }
                    }
            }
            launch {
                state.values.map { it["media_muted"]?.value }.distinctUntilChanged().collect { v ->
                    if (v != null) onMuted(v.isTrue())
                }
            }
            // The event bus replays its recent history to a new subscriber; a press from before this
            // connection must never reach the phone, so anything older than the attach is ignored.
            state.events.collect { e ->
                if (e.at < since) return@collect
                when (e.function) {
                    "media_play_pause" -> sendKey(Aa.KEY_MEDIA_PLAY_PAUSE)
                    "media_next" -> sendKey(Aa.KEY_MEDIA_NEXT)
                    "media_prev" -> sendKey(Aa.KEY_MEDIA_PREVIOUS)
                    "voice_activate" -> sendKey(Aa.KEY_SEARCH)
                    "button_home_pressed" -> sendKey(Aa.KEY_HOME)
                    "media_volume_up" -> onVolumeStep(+1)
                    "media_volume_down" -> onVolumeStep(-1)
                    else -> return@collect
                }
                Log.i(TAG, "control ${e.function} → Android Auto")
            }
        }

    companion object {
        private const val TAG = "DashAaBridge"

        private fun String.isTrue() = equals("true", ignoreCase = true) || this == "1" || equals("on", ignoreCase = true)

        /** km/h → Android Auto's unit, metres per second × 1000. */
        fun kmhToMmPerSecond(kmh: Double): Int = (kmh / 3.6 * 1000).toInt()

        /** system_commands.md's `gear_position` values → Android Auto's gear codes. */
        fun gearCode(value: String): Int? = when (value.lowercase()) {
            "park" -> Aa.GEAR_PARK
            "reverse" -> Aa.GEAR_REVERSE
            "neutral" -> Aa.GEAR_NEUTRAL
            "drive" -> Aa.GEAR_DRIVE
            else -> value.toIntOrNull()?.takeIf { it in 1..10 }
        }
    }
}
