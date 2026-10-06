package com.dash.android.ui.settings.content

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
import com.dash.android.ui.androidauto.AndroidAutoContent
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
        "layout.androidauto" -> AndroidAutoContent()
        "audio.calls" -> CallsContent()
        "modules.management" -> ModulesContent()
        "modules.transport" -> TransportManagerContent()
        "modules.serial" -> SerialMonitorContent()
        "modules.signal" -> SignalMonitorContent()
        "system.location" -> LocationContent()
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
            sub.wipVersion?.let { "Arrives with $it." } ?: "Empty — wired in a later 1.5.x version.",
            color = theme.textColourSecondary.copy(alpha = 0.7f),
            fontSize = MAINBODY,
            fontFamily = theme.font,
        )
    }
}
