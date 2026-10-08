package com.dash.android.ui.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.display.BrightnessLevels
import com.dash.android.display.DisplayPreferences
import com.dash.android.display.DisplaySettings
import com.dash.android.display.NightLightMode
import com.dash.android.display.RgbRange
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.FitPresetSegment
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Display › Brightness, Colour, Screen Blanking and Touchscreen (DASH-AA 1.1.5). Like Screens, each
 * shows only what this machine's display program can do on these screens — capability detection, as
 * everywhere — and says so in plain words when it can do none of it.
 *
 * **For native:** Brightness, Blanking and the rules behind them are shared and follow as they are
 * (the window's brightness standing in for a screen); Colour and Touchscreen are Linux's and native
 * leaves them out, or shows night light alone if Android offers it.
 */

@Composable
private fun rememberDisplaySettings(): Pair<DisplaySettings, ((DisplaySettings) -> DisplaySettings) -> Unit> {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { DisplayPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = DisplaySettings())
    val scope = rememberCoroutineScope()
    return settings to { change -> scope.launch { prefs.update(change) } }
}

/** Whether a module reports the headlights on now — DASH's night, for brightness and night light. */
@Composable
private fun rememberNight(): Boolean {
    val app = LocalContext.current.applicationContext as DashApplication
    val night by remember {
        app.controller.systemState.values.map { v -> v["headlights_on"]?.value?.let { it.equals("true", true) || it == "1" || it.equals("on", true) } == true }
    }.collectAsState(initial = false)
    return night
}

/**
 * Display › Brightness: each screen DASH can set, by day and by night. Night is while a module reports
 * the headlights on — like a car's dimmer on the dash-lights circuit. With no such module it is always day.
 */
@Composable
fun BrightnessContent() {
    val (display, state) = rememberDisplay()
    val (settings, update) = rememberDisplaySettings()
    val night = rememberNight()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Brightness")
        if (!state.available) { Note(state.note); return@Column }
        val screens = state.screens.filter { it.enabled && it.brightness != null }
        if (screens.isEmpty()) {
            Note("DASH cannot set the brightness of ${if (state.screens.size > 1) "these screens" else "this screen"}. A built-in " +
                "panel can be; a separate monitor only where the display program sets it.")
            return@Column
        }
        Note("Night is while a module reports the headlights on${if (night) " — as now" else ""}. Without one, it is always day.")
        screens.forEach { s ->
            if (screens.size > 1) SettingsSectionHeader(s.name)
            val levels = settings.brightness[s.identity] ?: BrightnessLevels(s.brightness ?: 1f)
            fun set(next: BrightnessLevels) {
                update { it.copy(brightness = it.brightness + (s.identity to next)) }
                // The level in use now applies at once; the rules do the same, but a tap should not wait.
                display.setBrightness(s.id, if (night) next.night ?: next.day else next.day)
            }
            SettingBlock(
                name = "By day",
                control = {
                    Stepper(
                        value = "${(levels.day * 100).roundToInt()}%",
                        modifier = controlWidth,
                        onMinus = { set(levels.copy(day = step(levels.day, -1))) },
                        onPlus = { set(levels.copy(day = step(levels.day, +1))) },
                    )
                },
            )
            SettingBlock(
                name = "By night",
                help = if (levels.night == null) "The same as by day." else null,
                control = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val n = levels.night
                        Stepper(
                            value = n?.let { "${(it * 100).roundToInt()}%" } ?: "Same",
                            modifier = controlWidth,
                            onMinus = { set(levels.copy(night = step(n ?: levels.day, -1))) },
                            onPlus = { set(levels.copy(night = step(n ?: levels.day, +1))) },
                        )
                        if (n != null) DashButton("Same as by day", onClick = { set(levels.copy(night = null)) }, modifier = controlWidth)
                    }
                },
            )
        }
    }
}

/** Display › Colour: HDR and colour range for each screen that has them, and night light. */
@Composable
fun ColourContent() {
    val (display, state) = rememberDisplay()
    val (settings, update) = rememberDisplaySettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Colour")
        if (!state.available) { Note(state.note); return@Column }
        KeepBanner()
        state.failure?.let { Note(it) }
        val screens = state.screens.filter { it.enabled && it.mirrorOf == null }
        val anyColour = screens.any { it.hdr != null || it.rgbRange != null }
        screens.filter { it.hdr != null || it.rgbRange != null }.forEach { s ->
            if (screens.size > 1) SettingsSectionHeader(s.name)
            s.hdr?.let { on ->
                SettingBlock(
                    name = "HDR",
                    help = "Brighter highlights and deeper colour, for a screen and a picture made for it.",
                    control = {
                        PresetSegment(listOf("Off", "On"), if (on) 1 else 0, controlWidth) { i ->
                            if ((i == 1) != on) tryChange(display, state, "HDR ${if (i == 1) "on" else "off"}", s.copy(hdr = i == 1))
                        }
                    },
                )
            }
            s.rgbRange?.let { range ->
                SettingBlock(
                    name = "Colour range",
                    help = "Washed-out blacks on an HDMI screen? Try Full; crushed ones, Limited.",
                    fullWidthControl = true,
                    control = {
                        PresetSegment(RgbRange.entries.map { it.label }, range.ordinal, Modifier.fillMaxWidth()) { i ->
                            val next = RgbRange.entries[i]
                            if (next != range) tryChange(display, state, "${next.label.lowercase()} colour range", s.copy(rgbRange = next))
                        }
                    },
                )
            }
        }
        if (!anyColour) Note("${if (screens.size > 1) "These screens have" else "This screen has"} no HDR or colour range for DASH to set.")

        SettingsSectionHeader("Night light")
        if (state.features.nightLight == null) {
            Note("The display program here has no night light DASH can use.")
            return@Column
        }
        SettingBlock(
            name = "Night light",
            help = "Warmer colours, easier on the eyes in the dark. With the headlights: while a module reports them on.",
            fullWidthControl = true,
            control = {
                PresetSegment(NightLightMode.entries.map { it.label }, settings.nightLight.ordinal, Modifier.fillMaxWidth()) { i ->
                    update { it.copy(nightLight = NightLightMode.entries[i]) }
                }
            },
        )
        if (settings.nightLight != NightLightMode.OFF) {
            SettingBlock(
                name = "Warmth",
                help = "Lower is warmer.",
                control = {
                    Stepper(
                        value = "${settings.nightKelvin} K",
                        modifier = controlWidth,
                        onMinus = { update { it.copy(nightKelvin = (it.nightKelvin - 250).coerceAtLeast(DisplaySettings.KELVIN_MIN)) } },
                        onPlus = { update { it.copy(nightKelvin = (it.nightKelvin + 250).coerceAtMost(DisplaySettings.KELVIN_MAX)) } },
                    )
                },
            )
        }
    }
}

/** Display › Screen Blanking: dark after the chosen minutes with nobody touching DASH; any touch brings it back. */
@Composable
fun BlankingContent() {
    val (_, state) = rememberDisplay()
    val (settings, update) = rememberDisplaySettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Screen Blanking")
        val canBlank = state.features.blank || state.screens.any { it.builtin && it.brightness != null }
        if (!canBlank) {
            Note("Nothing here can turn the screen off — the display program cannot, and there is no backlight DASH can set.")
            return@Column
        }
        val choices = DisplaySettings.BLANK_CHOICES
        SettingBlock(
            name = "Turn the screen off",
            help = "After this long with nobody touching DASH. A touch, a key or the mouse brings it back. While " +
                "DASH runs it looks after this itself, and the desktop's own blanking waits.",
            fullWidthControl = true,
            control = {
                FitPresetSegment(choices.map { if (it == 0) "Never" else "$it min" }, choices.indexOf(settings.blankAfterMinutes).coerceAtLeast(0)) { i ->
                    update { it.copy(blankAfterMinutes = choices[i]) }
                }
            },
        )
        if (settings.blankAfterMinutes > 0) {
            SettingBlock(
                name = "Dim first",
                help = "Dims for the last half-minute, so a glance can stop it.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (settings.dimFirst) 1 else 0, controlWidth) { i ->
                        update { it.copy(dimFirst = i == 1) }
                    }
                },
            )
        }
    }
}

/** Display › Touchscreen: each touchscreen, the screen its touches land on, and a place to try it. */
@Composable
fun TouchscreenContent() {
    val (display, state) = rememberDisplay()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Touchscreen")
        if (state.touchscreens.isEmpty()) Note("No touchscreen found. The space below still shows where a mouse or touchpad lands.")
        val screens = state.screens.filter { it.enabled && it.mirrorOf == null }
        state.touchscreens.forEach { t ->
            if (state.touchscreens.size > 1) SettingsSectionHeader(t.name)
            if (state.features.touchMapping && screens.size > 1) {
                SettingBlock(
                    name = "Touches land on",
                    fullWidthControl = true,
                    control = {
                        ChoiceList(screens.map { s -> ChoiceRow(s.name, if (s.primary) "The main screen" else null, t.screenId == s.id) { display.mapTouchscreen(t.id, s.id) } })
                    },
                )
            } else {
                InfoRows(listOf(t.name to (state.screens.firstOrNull { it.id == t.screenId }?.name ?: "The screen it is part of")))
            }
        }
        // DASH's on-screen keyboard (1.1.6): its one setting lives with the touchscreen it is for.
        SettingsSectionHeader("On-screen keyboard")
        com.dash.android.ui.keyboard.KeyboardSettings()
        SettingsSectionHeader("Try it")
        TouchTest()
    }
}

/** Every touch drawn where it lands, the trail fading as it grows. Leaving the tab clears it. */
@Composable
private fun TouchTest() {
    val theme = LocalDashTheme.current
    val points = remember { mutableStateListOf<Offset>() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(theme.textColourSecondary.copy(alpha = 0.06f))
                .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.filter { it.pressed }.forEach { c ->
                                points += c.position
                                if (points.size > 400) points.removeRange(0, points.size - 400)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (points.isEmpty()) Note("Touch or drag here.")
            Canvas(Modifier.fillMaxSize()) {
                points.forEachIndexed { i, p ->
                    drawCircle(theme.textColourSecondary.copy(alpha = 0.2f + 0.8f * i / points.size), radius = 6.dp.toPx(), center = p)
                }
            }
        }
        DashButton("Clear", onClick = { points.clear() })
    }
}

private fun step(v: Float, direction: Int): Float = (((v * 20).roundToInt() + direction).coerceIn(0, 20)) / 20f
