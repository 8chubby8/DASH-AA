package com.dash.android.display

import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The last time anyone touched DASH (DASH-AA 1.1.5) — a tap, a key, the mouse, a finger on Android Auto.
 * Screen blanking counts from it. Told by the platform: DASH-AA's window and touchscreen reader;
 * native's activity's `onUserInteraction`.
 */
object UserActivity {
    @Volatile var lastAt: Long = System.currentTimeMillis()
        private set

    fun touch() { lastAt = System.currentTimeMillis() }
}

/**
 * DASH's display rules (DASH-AA 1.1.5), carried out on the screens through [DisplaySystem]:
 * - **Brightness by day and by night.** Each screen's level is DASH's, by the screen's identity; at
 *   night — while a module reports `headlights_on`, the car's own idea of night, as Android Auto's night
 *   mode uses — the night level, if one is set. Like a car's dimmer on the dash-lights circuit.
 * - **Night light**: off (left as the machine has it), on, or with the headlights.
 * - **Blanking**: after the chosen minutes with nobody touching DASH, dim for half a minute, then dark;
 *   the first touch brings it back.
 *
 * Shared code: native follows with the same rules, its window brightness standing in for the screens.
 */
class DisplayRules(
    private val state: SystemState,
    private val display: DisplaySystem,
    private val settings: Flow<DisplaySettings>,
    private val scope: CoroutineScope,
) {
    @Volatile private var blanked = false
    private var dimmedFrom: Map<String, Float> = emptyMap()
    private var nightTouched = false

    val headlights: Flow<Boolean> = state.values
        .map { it["headlights_on"]?.value?.isTrue() == true }
        .distinctUntilChanged()

    fun start() {
        // Which screens there are, by id and identity — not their brightness, or a change would loop.
        val screens = display.state.map { s -> s.screens.filter { it.brightness != null }.map { it.id to it.identity } }.distinctUntilChanged()
        scope.launch {
            combine(settings.map { it.brightness }.distinctUntilChanged(), headlights, screens) { levels, night, list -> Triple(levels, night, list) }
                .collect { (levels, night, list) ->
                    if (dimmedFrom.isNotEmpty() || blanked) return@collect
                    list.forEach { (id, identity) -> levels[identity]?.let { display.setBrightness(id, it.at(night)) } }
                }
        }
        scope.launch {
            combine(settings.map { it.nightLight to it.nightKelvin }.distinctUntilChanged(), headlights) { (mode, k), lights -> Triple(mode, k, lights) }
                .collect { (mode, k, lights) ->
                    when (mode) {
                        NightLightMode.OFF -> if (nightTouched) display.setNightLight(false, k)
                        NightLightMode.ON -> { nightTouched = true; display.setNightLight(true, k) }
                        NightLightMode.HEADLIGHTS -> { nightTouched = true; display.setNightLight(lights, k) }
                    }
                }
        }
        scope.launch { blankLoop() }
    }

    private suspend fun blankLoop() {
        while (true) {
            delay(1000)
            val s = settings.first()
            val idle = System.currentTimeMillis() - UserActivity.lastAt
            val after = s.blankAfterMinutes * 60_000L
            when {
                idle < 1500 && (blanked || dimmedFrom.isNotEmpty()) -> wake(s)
                after <= 0 -> Unit
                idle >= after && !blanked -> { blanked = true; display.setScreensOn(false) }
                s.dimFirst && idle >= after - DIM_MS && !blanked && dimmedFrom.isEmpty() -> dim()
            }
        }
    }

    private fun dim() {
        val screens = display.state.value.screens.filter { it.enabled && it.brightness != null }
        dimmedFrom = screens.associate { it.id to it.brightness!! }
        screens.forEach { display.setBrightness(it.id, it.brightness!! * DIM_TO) }
    }

    private suspend fun wake(s: DisplaySettings) {
        if (blanked) display.setScreensOn(true)
        blanked = false
        val night = headlights.first()
        dimmedFrom.forEach { (id, was) ->
            val identity = display.state.value.screens.firstOrNull { it.id == id }?.identity
            display.setBrightness(id, s.brightness[identity]?.at(night) ?: was)
        }
        dimmedFrom = emptyMap()
    }

    private companion object {
        const val DIM_MS = 30_000L
        const val DIM_TO = 0.3f
        fun String.isTrue() = equals("true", ignoreCase = true) || this == "1" || equals("on", ignoreCase = true)
        fun BrightnessLevels.at(night: Boolean) = if (night) this.night ?: day else day
    }
}
