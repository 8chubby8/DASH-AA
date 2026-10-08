package com.dash.android.ui.keyboard

import com.dash.android.core.StoredSignal
import com.dash.android.transport.InstalledModule
import com.dash.android.transport.ModuleActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * **Is the car still?** (DASH-AA 1.1.6) — for the on-screen keyboard's lock: typing on a screen is for a
 * car that is not moving. Read from `handbrake_on` and `vehicle_speed` (system_commands.md), each counted
 * only while a module that declared it is active, as [com.dash.android.audio.VehicleSpeed] does.
 *
 * **True** — the speed is zero, or with no speed reported, the handbrake is on. **False** — a module
 * reports the car moving: a speed above zero (a speed outranks the handbrake, which can be on while
 * the car rolls), or the handbrake off with no speed to say otherwise. **Null** — no module reports
 * either, so DASH cannot know, and nothing is locked (Roger, 2026-10-08: "if it doesn't have any of those
 * signals then it will default to being allowed all the time").
 */
object CarStill {
    const val HANDBRAKE = "handbrake_on"
    const val SPEED = "vehicle_speed"

    /** Below this (km/h) the car counts as stopped — a speed sensor's last flicker as it settles. */
    const val STOPPED_KMH = 1f

    fun of(
        values: Flow<Map<String, StoredSignal>>,
        modules: Flow<Map<String, InstalledModule>>,
        activity: Flow<Map<String, ModuleActivity>>,
    ): Flow<Boolean?> = combine(values, modules, activity) { v, m, a ->
        fun live(signal: String) = m.values.any { signal in it.signals && a[it.id] == ModuleActivity.ACTIVE }
        val handbrake = if (live(HANDBRAKE)) v[HANDBRAKE]?.value?.let { it.equals("true", true) || it == "1" || it.equals("on", true) } else null
        val speed = if (live(SPEED)) v[SPEED]?.value?.toFloatOrNull() else null
        decide(handbrake, speed)
    }.distinctUntilChanged()

    internal fun decide(handbrake: Boolean?, speed: Float?): Boolean? = when {
        speed != null -> speed < STOPPED_KMH
        else -> handbrake
    }
}
