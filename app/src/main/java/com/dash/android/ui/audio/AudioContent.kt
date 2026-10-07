package com.dash.android.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.audio.SoundDevice
import com.dash.android.audio.SoundPreferences
import com.dash.android.audio.SoundSettings
import com.dash.android.audio.SoundState
import com.dash.android.audio.SoundSystem
import com.dash.android.audio.VolumeTarget
import com.dash.android.audio.defaultInput
import com.dash.android.audio.defaultOutput
import com.dash.android.ui.androidauto.rememberAaSettings
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.LivePreviewCard
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Audio › Output, Input and Mixer (DASH-AA 1.1.2) — the machine's sound settings and the car's sound
 * menu in one (Roger, 2026-10-07). With no desktop there is no other sound panel, so these tabs choose
 * the speakers and the microphone for the whole machine, set its volume, and show everything playing.
 * Android Auto's own choices sit beneath, in their own section.
 *
 * Built only on [SoundSystem], never on PipeWire, so the tabs are shared code: native takes them with
 * an Android [SoundSystem] behind them. Calls is next door in `ui/androidauto/CallsContent.kt`; Sound
 * (equaliser, balance, fade) arrives with 1.1.3.
 *
 * Levels are steppers, as everywhere in DASH — the design language has no slider — in steps of 5%.
 */

/** Audio › Output: which speakers, how loud, how loud at most when DASH starts, and what the volume buttons turn. */
@Composable
fun AudioOutputContent() {
    val (sound, state) = rememberSound()
    val aa = rememberAaSettings()
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { SoundPreferences(app) }
    val scope = rememberCoroutineScope()
    val soundSettings by prefs.settings.collectAsState(initial = SoundSettings())
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val out = state.defaultOutput

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Output")
        if (!state.available) InfoRows(listOf("Sound" to state.note))
        SettingBlock(
            name = "Speakers",
            help = "Where all sound plays. Plug in a sound card or headphones and they appear here.",
            fullWidthControl = true,
            control = { DeviceChoice(state.outputs, "No speakers found") { sound.setDefault(it) } },
        )
        SettingBlock(
            name = "Volume",
            control = { VolumeStepper(out?.volume, out?.muted == true, controlWidth) { v -> out?.let { sound.setVolume(it.id, v) } } },
        )
        SettingBlock(
            name = "Mute",
            control = {
                PresetSegment(listOf("Off", "On"), if (out?.muted == true) 1 else 0, controlWidth) { i ->
                    out?.let { sound.setMuted(it.id, i == 1) }
                }
            },
        )
        SettingBlock(
            name = "Start-up volume limit",
            help = "When DASH starts, the volume comes down to this if it was left higher, so the car is " +
                "never blasted as it starts. It never turns the volume up.",
            control = {
                val limit = soundSettings.startupLimit
                Stepper(
                    value = if (limit <= SoundSettings.LIMIT_OFF) "Off" else "${(limit * 100).roundToInt()}%",
                    modifier = controlWidth,
                    onMinus = { scope.launch { prefs.update { it.copy(startupLimit = stepped(it.startupLimit, -1)) } } },
                    onPlus = { scope.launch { prefs.update { it.copy(startupLimit = stepped(it.startupLimit, +1)) } } },
                )
            },
        )
        SettingBlock(
            name = "Volume buttons",
            help = "What the steering wheel's volume buttons turn, or any module's that sends them. " +
                "Machine turns everything; Android Auto turns only its own sound.",
            control = {
                val labels = listOf("Machine", "Android Auto")
                val targets = listOf(VolumeTarget.MACHINE, VolumeTarget.VIEWPORT)
                PresetSegment(labels, targets.indexOf(soundSettings.volumeButtons), controlWidth) { i ->
                    scope.launch { prefs.update { it.copy(volumeButtons = targets[i]) } }
                }
            },
        )
        SettingsSectionHeader("Android Auto")
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

/** Audio › Input: which microphone, how sensitive, and a meter to see it hears you. */
@Composable
fun AudioInputContent() {
    val (sound, state) = rememberSound()
    val aa = rememberAaSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val mic = state.defaultInput
    var testing by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var cannotListen by remember { mutableStateOf(false) }

    // The meter listens only while it is switched on and this tab is open — leaving the tab stops it.
    // DASH-AA otherwise listens only when the phone opens the microphone, and that rule stands.
    DisposableEffect(testing) {
        level = 0f
        val handle = if (testing) sound.listen { l -> level = maxOf(l, level * 0.85f) } else null
        cannotListen = testing && handle == null
        onDispose { handle?.close() }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Input")
        if (!state.available) InfoRows(listOf("Sound" to state.note))
        SettingBlock(
            name = "Microphone",
            help = "What the phone hears for the assistant and for calls.",
            fullWidthControl = true,
            control = { DeviceChoice(state.inputs, "No microphone found") { sound.setDefault(it) } },
        )
        SettingBlock(
            name = "Input volume",
            control = { VolumeStepper(mic?.volume, mic?.muted == true, controlWidth) { v -> mic?.let { sound.setVolume(it.id, v) } } },
        )
        SettingBlock(
            name = "Mute",
            control = {
                PresetSegment(listOf("Off", "On"), if (mic?.muted == true) 1 else 0, controlWidth) { i ->
                    mic?.let { sound.setMuted(it.id, i == 1) }
                }
            },
        )
        SettingBlock(
            name = "Test microphone",
            help = "Shows how loud the microphone hears you. Nothing is recorded, and it stops when you leave this page.",
            control = { PresetSegment(listOf("Off", "On"), if (testing) 1 else 0, controlWidth) { i -> testing = i == 1 } },
            preview = if (!testing) null else {
                { LivePreviewCard("Level") { if (cannotListen) Note("Nothing can listen — pw-cat is missing") else LevelBar(level) } }
            },
        )
        SettingsSectionHeader("Android Auto")
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

/**
 * Audio › Mixer: a level for every source. Android Auto's are DASH-AA's own and always here; anything
 * else appears while it plays. The friend's voice on a call has its own level in Calls.
 */
@Composable
fun AudioMixerContent() {
    val (sound, state) = rememberSound()
    val aa = rememberAaSettings()
    val s = aa.settings
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Mixer")
        SettingBlock(
            name = "Android Auto",
            help = "All of Android Auto's sound. The volume buttons move this when Output › Volume buttons is set to Android Auto.",
            control = { VolumeStepper(s.volume, false, controlWidth) { v -> aa.set { it.copy(volume = v) } } },
        )
        SettingBlock(
            name = "Music",
            control = { VolumeStepper(s.musicLevel, false, controlWidth) { v -> aa.set { it.copy(musicLevel = v) } } },
        )
        SettingBlock(
            name = "Directions and assistant",
            control = { VolumeStepper(s.directionsLevel, false, controlWidth) { v -> aa.set { it.copy(directionsLevel = v) } } },
        )
        SettingBlock(
            name = "System sounds",
            control = { VolumeStepper(s.systemLevel, false, controlWidth) { v -> aa.set { it.copy(systemLevel = v) } } },
        )
        SettingBlock(
            name = "Lower music under directions",
            control = {
                PresetSegment(listOf("Off", "On"), if (s.duckMedia) 1 else 0, controlWidth) { i ->
                    aa.set { it.copy(duckMedia = i == 1) }
                }
            },
        )
        SettingsSectionHeader("Everything else")
        when {
            !state.available -> InfoRows(listOf("Sound" to state.note))
            state.streams.isEmpty() -> Note("Nothing else is playing.")
            else -> state.streams.forEach { t ->
                SettingBlock(
                    name = t.label,
                    help = t.detail,
                    control = { VolumeStepper(t.volume, t.muted, controlWidth) { v -> sound.setVolume(t.id, v) } },
                )
            }
        }
    }
}

@Composable
private fun rememberSound(): Pair<SoundSystem, SoundState> {
    val sound = (LocalContext.current.applicationContext as DashApplication).sound
    val state by sound.state.collectAsState()
    return sound to state
}

/** One step of 5% up or down, landing on the steps whatever the value was. */
private fun stepped(v: Float, direction: Int): Float = (((v * 20).roundToInt() + direction).coerceIn(0, 20)) / 20f

/** A level as a percentage, ±5%. Greyed when there is nothing to set ([volume] null). */
@Composable
private fun VolumeStepper(volume: Float?, muted: Boolean, modifier: Modifier, onSet: (Float) -> Unit) {
    Stepper(
        value = volume?.let { "${(it * 100).roundToInt()}%" } ?: "—",
        sub = if (muted) "muted" else null,
        enabled = volume != null,
        modifier = modifier,
        onMinus = { volume?.let { onSet(stepped(it, -1)) } },
        onPlus = { volume?.let { onSet(stepped(it, +1)) } },
    )
}

/**
 * The devices to choose between, one row each, the one in use filled — the segmented selector stood on
 * end, because device names are far too long to sit side by side.
 */
@Composable
private fun DeviceChoice(devices: List<SoundDevice>, empty: String, onChoose: (SoundDevice) -> Unit) {
    val theme = LocalDashTheme.current
    if (devices.isEmpty()) { Note(empty); return }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        devices.forEach { d ->
            val ink = if (d.isDefault) theme.backgroundColourSecondary else theme.textColourSecondary
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (d.isDefault) theme.textColourSecondary else Color.Transparent)
                    .clickable { if (!d.isDefault) onChoose(d) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(d.label, color = ink, fontSize = BODY, fontFamily = theme.font)
                if (d.detail != null) Text(d.detail, color = ink.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
            }
        }
    }
}

/** The microphone's level, from silence (empty) to full scale (full). */
@Composable
private fun LevelBar(level: Float) {
    val theme = LocalDashTheme.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(theme.textColourSecondary),
        )
    }
}

@Composable
private fun Note(text: String) {
    val theme = LocalDashTheme.current
    Text(text, color = theme.textColourSecondary.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
}
