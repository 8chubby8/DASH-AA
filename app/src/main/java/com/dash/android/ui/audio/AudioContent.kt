package com.dash.android.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.ui.androidauto.rememberAaSettings
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.Stepper
import kotlin.math.roundToInt

/**
 * Audio › Output, Microphone and Mixing (DASH-AA 1.1.1) — the sound controls that were the Sound section
 * of Layout › Android Auto, moved to where they will live (Roger, 2026-10-07). Today each holds the
 * Android Auto choice it always did; 1.1.2 adds choosing which speakers and which microphone, through
 * PipeWire, to the same tabs. Calls is next door in `ui/androidauto/CallsContent.kt`.
 *
 * Same values, same saved settings: moving a control never touches what it is set to.
 */

/** Audio › Output: where Android Auto's sound plays. */
@Composable
fun AudioOutputContent() {
    val aa = rememberAaSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Output")
        SettingBlock(
            name = "Android Auto sound",
            control = {
                PresetSegment(listOf("Phone", "DASH-AA"), if (aa.settings.audio) 1 else 0, controlWidth) { i ->
                    aa.set { it.copy(audio = i == 1) }
                }
            },
        )
    }
}

/** Audio › Microphone: whose microphone Android Auto listens through. */
@Composable
fun AudioMicrophoneContent() {
    val aa = rememberAaSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Microphone")
        SettingBlock(
            name = "Android Auto microphone",
            control = {
                PresetSegment(listOf("Phone", "DASH-AA"), if (aa.settings.microphone) 1 else 0, controlWidth) { i ->
                    aa.set { it.copy(microphone = i == 1) }
                }
            },
        )
    }
}

/** Audio › Mixing: how loud, and whether music steps back for directions. Both apply at once. */
@Composable
fun AudioMixingContent() {
    val aa = rememberAaSettings()
    val s = aa.settings
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Mixing")
        SettingBlock(
            name = "Volume",
            control = {
                Stepper(
                    value = "${(s.volume * 100).roundToInt()}%",
                    modifier = controlWidth,
                    onMinus = { aa.set { it.copy(volume = (it.volume - 0.1f).coerceAtLeast(0f)) } },
                    onPlus = { aa.set { it.copy(volume = (it.volume + 0.1f).coerceAtMost(1f)) } },
                )
            },
        )
        SettingBlock(
            name = "Lower music under directions",
            control = {
                PresetSegment(listOf("Off", "On"), if (s.duckMedia) 1 else 0, controlWidth) { i ->
                    aa.set { it.copy(duckMedia = i == 1) }
                }
            },
        )
    }
}
