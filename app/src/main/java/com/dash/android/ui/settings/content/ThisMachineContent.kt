package com.dash.android.ui.settings.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dash.android.prefs.DashPreferences
import com.dash.android.system.buildDeviceReport
import com.dash.android.system.formatDeviceReport
import com.dash.android.ui.common.BODY
import com.dash.android.ui.theme.LocalDashTheme

/**
 * System › This Machine (DASH-AA 1.1.1) — what DASH found on this machine: the report that was the foot
 * of About DASH (roadmap 1.5.14), moved to a tab of its own. Its purpose and rules are unchanged —
 * *facts and capabilities, not identity*, the first thing to ask for before asking anything else — and
 * it grows as each new tab checks for what it needs.
 *
 * **For native:** take this file and the matching cut from `AboutContent.kt` as they are; the report
 * itself (`system/DeviceReport.kt`) is each edition's own.
 */
@Composable
fun ThisMachineContent() {
    val theme = LocalDashTheme.current
    val appContext = LocalContext.current.applicationContext
    val prefs = remember { DashPreferences(appContext) }
    val clipboard = LocalClipboardManager.current
    val dashTextScale by prefs.dashTextScale.collectAsState(initial = 1.0f)

    val report = remember(dashTextScale) { buildDeviceReport(appContext, dashTextScale) }
    var copied by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsContentHeader("This Machine")
        InfoRows(report.map { it.label to it.value })
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinkButton(if (copied) "COPIED" else "COPY REPORT") {
                clipboard.setText(AnnotatedString(formatDeviceReport(report)))
                copied = true
            }
            if (copied) {
                Text(
                    "Paste it into your bug report.",
                    color = theme.textColourSecondary.copy(alpha = 0.62f),
                    fontSize = BODY,
                    fontFamily = theme.font,
                )
            }
        }
    }
}
