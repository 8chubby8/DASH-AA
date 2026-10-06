package com.dash.android.ui.androidauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.DashApplication
import com.dash.android.aa.AaCapabilities
import com.dash.android.aa.AaPreferences
import com.dash.android.aa.AaSettings
import com.dash.android.aa.AaStatus
import com.dash.android.aa.DriverSide
import com.dash.android.aa.NightSource
import com.dash.android.aa.protocol.AaVideoMode
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import com.dash.android.ui.viewport.statusLine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Layout › Android Auto (DASH-AA) — the viewport's tenant, configured.
 *
 * Built from upstream's settings vocabulary and held to its 1.5.15 rules: titled once, sections a rank
 * below, no help text under settings, every control on the right at one shared width.
 *
 * **What is here and why.** Each control is something Android Auto genuinely asks a head unit, plus
 * the head unit's own mixer. *Video* and *Density* are the fork's equivalent of upstream's Android App
 * Density: they decide how big the phone draws its interface, which is the viewport app's sizing, not
 * DASH's. Anything that changes what the phone was told when it connected restarts projection, which
 * takes a few seconds; volume and ducking apply at once.
 */
@Composable
fun AndroidAutoContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val host = app.androidAuto
    val prefs = remember { AaPreferences(app) }
    val scope = rememberCoroutineScope()
    val s by prefs.settings.collectAsState(initial = host.settings)
    val status by host.status.collectAsState()
    val callsNote by host.callsNote.collectAsState()
    val soundStuck by host.soundStuck.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    fun set(t: (AaSettings) -> AaSettings) { scope.launch { prefs.update(t) } }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsContentHeader("Android Auto")

        Column(verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("Connection")
            InfoRows(
                listOfNotNull(
                    "Status" to (statusLine(status) ?: when (status) {
                        is AaStatus.Projecting -> "Projecting"
                        AaStatus.Disabled -> "Off"
                        else -> "—"
                    }),
                    (status as? AaStatus.Projecting)?.let { p ->
                        "Video" to "${p.geometry.mode.label}, interface ${p.geometry.contentWidth} × ${p.geometry.contentHeight}"
                    },
                    "Phone access" to AaCapabilities.usbSummary(),
                    "Calls" to callsNote,
                    "Touch" to if (host.multiTouch) AaCapabilities.touchSummary() else "Through the window (one finger)",
                )
            )
            SettingBlock(
                name = "Android Auto",
                control = {
                    PresetSegment(listOf("Off", "On"), if (s.enabled) 1 else 0, controlWidth) { i -> set { it.copy(enabled = i == 1) } }
                },
            )
            // Offered only when the desktop's sound system has actually stopped taking audio.
            if (soundStuck) SettingBlock(
                name = "The laptop's sound system has stopped",
                control = { DashButton("Restart sound", { host.restartSoundSystem() }, modifier = controlWidth) },
            )
            SettingBlock(
                name = "Phone",
                control = { DashButton("Reconnect", { host.reconnect() }, modifier = controlWidth) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("Picture")
            val modes = listOf(AaVideoMode.P480, AaVideoMode.P720, AaVideoMode.P1080, AaVideoMode.P1440)
            // Four choices do not fit the shared control width without breaking "1080" in two, so this
            // one stacks — upstream's documented escape hatch (1.5.15 rule 4), not a preference.
            SettingBlock(
                name = "Video",
                fullWidthControl = true,
                control = {
                    PresetSegment(listOf("480", "720", "1080", "1440"), modes.indexOf(s.videoMode).coerceAtLeast(0), Modifier.fillMaxWidth()) { i ->
                        set { it.copy(videoMode = modes[i]) }
                    }
                },
            )
            SettingBlock(
                name = "Frame rate",
                control = {
                    PresetSegment(listOf("30", "60"), if (s.fps60) 1 else 0, controlWidth) { i -> set { it.copy(fps60 = i == 1) } }
                },
            )
            SettingBlock(
                name = "Density",
                control = {
                    Stepper(
                        value = "${s.dpi} dpi",
                        modifier = controlWidth,
                        onMinus = { set { it.copy(dpi = (it.dpi - AaSettings.DPI_STEP).coerceAtLeast(AaSettings.DPI_MIN)) } },
                        onPlus = { set { it.copy(dpi = (it.dpi + AaSettings.DPI_STEP).coerceAtMost(AaSettings.DPI_MAX)) } },
                    )
                },
            )
            SettingBlock(
                name = "Night mode",
                fullWidthControl = true,
                control = {
                    val options = NightSource.entries
                    PresetSegment(options.map { it.label }, options.indexOf(s.nightSource), Modifier.fillMaxWidth()) { i ->
                        set { it.copy(nightSource = options[i]) }
                    }
                },
            )
            SettingBlock(
                name = "Driver sits",
                control = {
                    val options = DriverSide.entries
                    PresetSegment(options.map { it.label }, options.indexOf(s.driverSide), controlWidth) { i ->
                        set { it.copy(driverSide = options[i]) }
                    }
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("Sound")
            SettingBlock(
                name = "Sound",
                control = {
                    PresetSegment(listOf("Phone", "DASH-AA"), if (s.audio) 1 else 0, controlWidth) { i -> set { it.copy(audio = i == 1) } }
                },
            )
            SettingBlock(
                name = "Volume",
                control = {
                    Stepper(
                        value = "${(s.volume * 100).roundToInt()}%",
                        modifier = controlWidth,
                        onMinus = { set { it.copy(volume = (it.volume - 0.1f).coerceAtLeast(0f)) } },
                        onPlus = { set { it.copy(volume = (it.volume + 0.1f).coerceAtMost(1f)) } },
                    )
                },
            )
            SettingBlock(
                name = "Lower music under directions",
                control = {
                    PresetSegment(listOf("Off", "On"), if (s.duckMedia) 1 else 0, controlWidth) { i -> set { it.copy(duckMedia = i == 1) } }
                },
            )
            SettingBlock(
                name = "Microphone",
                control = {
                    PresetSegment(listOf("Phone", "DASH-AA"), if (s.microphone) 1 else 0, controlWidth) { i -> set { it.copy(microphone = i == 1) } }
                },
            )
        }
    }
}
