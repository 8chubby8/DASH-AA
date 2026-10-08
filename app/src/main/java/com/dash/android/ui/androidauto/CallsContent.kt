package com.dash.android.ui.androidauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import com.dash.android.audio.CarSound
import com.dash.android.audio.SoundControl
import com.dash.android.audio.SoundPreferences
import com.dash.android.audio.SoundSettings
import com.dash.android.audio.callChoices
import com.dash.android.audio.effectiveCalls
import com.dash.android.audio.usesLow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.DashApplication
import com.dash.android.aa.AaPreferences
import com.dash.android.aa.AaSettings
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.FitPresetSegment
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.Stepper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Audio › Calls (DASH-AA 1.0.8) — phone calls through the head unit.
 *
 * **Call volume** is the friend's voice only, independent of music (Roger). Past 100% it is boosted —
 * the phone's level is fixed, so the laptop adds the gain — and the stepper says so rather than leaving
 * a number above 100 to explain itself. Applied at once, mid-call included. **Echo cancelling** moved here
 * from Layout › Android Auto, so everything about calls is in one place.
 *
 * Built from upstream's settings vocabulary — a stepper, like every other level in DASH, rather than a
 * slider, which the design language has none of.
 */
@Composable
fun CallsContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val host = app.androidAuto
    val prefs = remember { AaPreferences(app) }
    val scope = rememberCoroutineScope()
    val s by prefs.settings.collectAsState(initial = host.settings)
    val callsNote by host.callsNote.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    fun set(t: (AaSettings) -> AaSettings) { scope.launch { prefs.update(t) } }
    fun snap(v: Float) = (v * 10).roundToInt() / 10f

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Calls")
        InfoRows(listOf("Status" to callsNote))
        SettingBlock(
            name = "Call volume",
            control = {
                val pct = (s.callVolume * 100).roundToInt()
                Stepper(
                    value = "$pct%",
                    sub = if (pct > 100) "boosted" else null,
                    modifier = controlWidth,
                    onMinus = { set { it.copy(callVolume = snap(it.callVolume - AaSettings.CALL_VOLUME_STEP).coerceAtLeast(AaSettings.CALL_VOLUME_MIN)) } },
                    onPlus = { set { it.copy(callVolume = snap(it.callVolume + AaSettings.CALL_VOLUME_STEP).coerceAtMost(AaSettings.CALL_VOLUME_MAX)) } },
                )
            },
        )
        SettingBlock(
            name = "Echo cancelling",
            control = {
                PresetSegment(listOf("Off", "On"), if (s.echoCancel) 1 else 0, controlWidth) { i -> set { it.copy(echoCancel = i == 1) } }
            },
        )
        CallSpeakers(controlWidth)
    }
}

/**
 * Where a call plays (1.1.3, Roger 2026-10-08) — with Car sound on, and only the choices the speaker layout
 * makes sense of: *Front only* when something plays behind the front, *Driver's side* when there are front
 * speakers (the side is Android Auto's driver side), the subwoofer only when there is one.
 */
@Composable
private fun CallSpeakers(controlWidth: Modifier) {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { SoundPreferences(app) }
    val scope = rememberCoroutineScope()
    val car = prefs.settings.collectAsState(initial = SoundSettings()).value.car
    val processor by app.soundProcessor.state.collectAsState()
    if (!car.enabled || !processor.available || SoundControl.CALL_ROUTING !in processor.offers) return
    fun update(t: (CarSound) -> CarSound) { scope.launch { prefs.update { it.copy(car = t(it.car)) } } }

    val choices = car.callChoices()
    if (choices.size > 1) {
        SettingBlock(
            name = "Calls play through",
            fullWidthControl = true,
            control = {
                FitPresetSegment(choices.map { it.label }, choices.indexOf(car.effectiveCalls())) { i ->
                    update { it.copy(calls = choices[i]) }
                }
            },
        )
    }
    if (car.usesLow) {
        SettingBlock(
            name = "Subwoofer in calls",
            control = {
                PresetSegment(listOf("Off", "On"), if (car.callsSubwoofer) 1 else 0, controlWidth) { i ->
                    update { it.copy(callsSubwoofer = i == 1) }
                }
            },
        )
    }
}
