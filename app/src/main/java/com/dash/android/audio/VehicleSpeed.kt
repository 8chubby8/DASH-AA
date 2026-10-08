package com.dash.android.audio

import com.dash.android.core.StoredSignal
import com.dash.android.transport.InstalledModule
import com.dash.android.transport.ModuleActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * **The car's speed, for the sound** (DASH-AA 1.1.4) — `vehicle_speed` (km/h, system_commands.md), as
 * long as a module that declared it is active. When none is, the speed is unknown (null) and speed volume
 * adds nothing: a module unplugged at 70 mph must not leave the sound turned up for the drive home.
 * Capability-detected, never faked: with no module reporting speed, Speed volume is not offered at all.
 */
object VehicleSpeed {
    const val SIGNAL = "vehicle_speed"

    fun of(
        values: Flow<Map<String, StoredSignal>>,
        modules: Flow<Map<String, InstalledModule>>,
        activity: Flow<Map<String, ModuleActivity>>,
    ): Flow<Float?> = combine(values, modules, activity) { v, m, a ->
        v[SIGNAL]?.value?.toFloatOrNull()?.takeIf { m.values.any { mod -> SIGNAL in mod.signals && a[mod.id] == ModuleActivity.ACTIVE } }
    }.distinctUntilChanged()

    /** Whether any installed module reports the speed — Speed volume is offered only then. */
    fun reported(modules: Map<String, InstalledModule>): Boolean = modules.values.any { SIGNAL in it.signals }
}
