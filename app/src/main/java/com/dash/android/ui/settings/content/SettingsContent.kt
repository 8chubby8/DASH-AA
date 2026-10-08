package com.dash.android.ui.settings.content

import com.dash.android.ui.display.BlankingContent
import com.dash.android.ui.display.BrightnessContent
import com.dash.android.ui.display.ColourContent
import com.dash.android.ui.display.ScreensContent
import com.dash.android.ui.display.TouchscreenContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.ui.modules.ModulesContent
import com.dash.android.ui.monitor.SerialMonitorContent
import com.dash.android.ui.signal.SignalMonitorContent
import com.dash.android.ui.transports.TransportManagerContent
import com.dash.android.ui.androidauto.AndroidAutoConnectionContent
import com.dash.android.ui.androidauto.AndroidAutoNightContent
import com.dash.android.ui.androidauto.AndroidAutoPictureContent
import com.dash.android.ui.audio.AudioInputContent
import com.dash.android.ui.audio.AudioMixerContent
import com.dash.android.ui.audio.AudioOutputContent
import com.dash.android.ui.audio.AudioSoundContent
import com.dash.android.ui.audio.AudioSavedContent
import com.dash.android.ui.androidauto.CallsContent
import com.dash.android.ui.settings.SettingsSub
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.SUBHEADING

/**
 * The content router for the settings box. Each subcategory that has gone live claims its id here;
 * everything else falls through to the honest WIP placeholder from 1.5.2. Adding a tab is one line —
 * the navigation shell never changes.
 */
@Composable
fun SettingsContent(sub: SettingsSub) {
    when (sub.id) {
        "appearance.density" -> SizeScaleContent()
        "appearance.transitions" -> MotionContent()
        "appearance.splash" -> SplashContent()
        "layout.systembar" -> SystemBarContent()
        "layout.modulepanel" -> ModulePanelContent()
        "androidauto.connection" -> AndroidAutoConnectionContent()
        "androidauto.picture" -> AndroidAutoPictureContent()
        "androidauto.night" -> AndroidAutoNightContent()
        "audio.output" -> AudioOutputContent()
        "audio.input" -> AudioInputContent()
        "audio.calls" -> CallsContent()
        "audio.mixer" -> AudioMixerContent()
        "audio.sound" -> AudioSoundContent()
        "audio.saved" -> AudioSavedContent()
        "display.screens" -> ScreensContent()
        "display.rotation" -> RotationContent()
        "display.brightness" -> BrightnessContent()
        "display.colour" -> ColourContent()
        "display.blanking" -> BlankingContent()
        "display.touchscreen" -> TouchscreenContent()
        "modules.management" -> ModulesContent()
        "modules.transport" -> TransportManagerContent()
        "modules.serial" -> SerialMonitorContent()
        "modules.signal" -> SignalMonitorContent()
        "system.location" -> LocationContent()
        "system.machine" -> ThisMachineContent()
        "system.about" -> AboutContent()
        "system.licence" -> LicenceContent()
        else -> WipPlaceholder(sub)
    }
}

@Composable
private fun WipPlaceholder(sub: SettingsSub) {
    val theme = LocalDashTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            sub.label.uppercase(),
            color = theme.textColourSecondary,
            fontSize = SUBHEADING,
            fontFamily = theme.font,
            letterSpacing = 2.sp,
        )
        Text(
            sub.wipVersion?.let { "Arrives with $it." } ?: "Not built yet.",
            color = theme.textColourSecondary.copy(alpha = 0.7f),
            fontSize = MAINBODY,
            fontFamily = theme.font,
        )
    }
}
