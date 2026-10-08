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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.dash.android.audio.CarSound
import com.dash.android.audio.SoundControl
import com.dash.android.audio.SoundProcessor
import com.dash.android.audio.SpeakerAssignment
import com.dash.android.audio.SpeakerPosition
import com.dash.android.audio.balanceLabel
import com.dash.android.audio.defaultOutputs
import com.dash.android.audio.fadeLabel
import com.dash.android.audio.hasFade
import com.dash.android.audio.SurroundMode
import com.dash.android.ui.settings.content.FitPresetSegment
import com.dash.android.audio.outputChoices
import com.dash.android.audio.outputsLabel
import com.dash.android.audio.usesLow
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SUBHEADING
import com.dash.android.ui.common.TINY
import kotlin.math.abs

/**
 * Audio › Equaliser, Speakers, Microphone and Volumes (DASH-AA 1.1.2; renamed, and the Equaliser added, 1.1.3) — the machine's sound settings and the car's sound
 * menu in one (Roger, 2026-10-07). With no desktop there is no other sound panel, so these tabs choose
 * the speakers and the microphone for the whole machine, set its volume, and show everything playing.
 * Android Auto's own choices sit beneath, in their own section.
 *
 * Built only on [SoundSystem] and [SoundProcessor], never on PipeWire, so the tabs are shared code:
 * native takes them with Android ones behind them. Calls is next door in `ui/androidauto/CallsContent.kt`.
 *
 * Levels are steppers, as everywhere in DASH — the design language has no slider — in steps of 5%.
 */

/**
 * Audio › Speakers: where the sound plays, how loud, how loud at most when DASH starts, and what the volume
 * buttons turn. With **Car sound** on (1.1.3) it is the speaker layout — each position on a device of the
 * user's choosing, or none — and everything plays through DASH's [SoundProcessor]. Off, it is the
 * machine's choice of one device, as at 1.1.2.
 */
@Composable
fun AudioOutputContent() {
    val (sound, state) = rememberSound()
    val aa = rememberAaSettings()
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { SoundPreferences(app) }
    val scope = rememberCoroutineScope()
    val soundSettings by prefs.settings.collectAsState(initial = SoundSettings())
    val processor by app.soundProcessor.state.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val out = state.defaultOutput
    val car = soundSettings.car
    val speakers = state.outputs.filterNot { it.dash }
    fun updateCar(t: (CarSound) -> CarSound) = scope.launch { prefs.update { it.copy(car = t(it.car)) } }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Speakers")
        if (!state.available) InfoRows(listOf("Sound" to state.note))
        if (processor.available) {
            SettingBlock(
                name = "Car sound",
                help = "On: all sound goes through DASH. Audio › Equaliser shapes it, and each speaker below plays " +
                    "through the device you choose. Off: everything plays straight to one device, untouched.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (car.enabled) 1 else 0, controlWidth) { i ->
                        updateCar { c -> if (i == 1) c.turnedOn(out?.takeIf { !it.dash }) else c.copy(enabled = false) }
                    }
                },
            )
        } else {
            InfoRows(listOf("Car sound" to processor.note))
        }
        if (car.enabled && processor.available) {
            if (processor.note.isNotEmpty()) InfoRows(listOf("Car sound" to processor.note))
            if (SoundControl.LAYOUT in processor.offers) SpeakerPosition.entries.forEach { position ->
                SpeakerLayout(position, car.speakers[position], speakers, controlWidth) { a ->
                    updateCar { c -> c.copy(speakers = if (a == null) c.speakers - position else c.speakers + (position to a)) }
                }
                if (position == SpeakerPosition.SURROUND && car.speakers[position] != null && SoundControl.SURROUND_EFFECT in processor.offers) {
                    SurroundEffect(car, controlWidth) { t -> updateCar(t) }
                }
            }
            SettingsSectionHeader("Volume")
        } else {
            SettingBlock(
                name = "Speakers",
                help = "Where all sound plays. Plug in a sound card or headphones and they appear here.",
                fullWidthControl = true,
                control = { DeviceChoice(speakers, "No speakers found") { sound.setDefault(it) } },
            )
        }
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

/** Turning Car sound on for the first time puts the front speakers on the device playing now. */
private fun CarSound.turnedOn(playing: SoundDevice?): CarSound {
    if (speakers.isNotEmpty() || playing == null) return copy(enabled = true)
    val front = SpeakerAssignment(playing.key, playing.label, defaultOutputs(SpeakerPosition.FRONT, playing.channels))
    return copy(enabled = true, speakers = mapOf(SpeakerPosition.FRONT to front))
}

private val POSITION_HELP = mapOf(
    SpeakerPosition.FRONT to "The front doors.",
    SpeakerPosition.REAR to "The rear doors.",
    SpeakerPosition.SURROUND to "The parcel shelf: full stereo, or a surround effect — see Mode below.",
    SpeakerPosition.CENTRE to "In the dashboard: left and right together, so voices sit in the middle.",
    SpeakerPosition.SUBWOOFER to "Only the low notes, below the crossover set in Audio › Equaliser.",
)

/**
 * One speaker position: its device (or none), which of the device's outputs when it has more than one
 * way to be used, and its level. A device chosen but not plugged in stays chosen, and says so.
 */
@Composable
private fun SpeakerLayout(
    position: SpeakerPosition,
    assignment: SpeakerAssignment?,
    devices: List<SoundDevice>,
    controlWidth: Modifier,
    onChange: (SpeakerAssignment?) -> Unit,
) {
    val device = assignment?.let { a -> devices.firstOrNull { it.key == a.device } }
    SettingsSectionHeader(position.label)
    SettingBlock(
        name = "Device",
        help = POSITION_HELP[position],
        fullWidthControl = true,
        control = {
            val rows = listOf(ChoiceRow("None", null, assignment == null) { onChange(null) }) +
                devices.map { d ->
                    ChoiceRow(d.label, d.detail, d.key == assignment?.device) {
                        onChange(SpeakerAssignment(d.key, d.label, defaultOutputs(position, d.channels), assignment?.level ?: 1f))
                    }
                } +
                listOfNotNull(assignment?.takeIf { device == null }?.let { ChoiceRow(it.label, "Not connected", true) {} })
            ChoiceList(rows)
        },
    )
    if (assignment == null) return
    val choices = device?.let { outputChoices(position, it.channels) }.orEmpty()
    if (choices.size > 1) {
        SettingBlock(
            name = "Outputs",
            help = if (position.stereo) "Which of the device's outputs play left and right." else "Which of the device's outputs it plays on.",
            fullWidthControl = true,
            control = {
                ChoiceList(choices.map { c ->
                    ChoiceRow(outputsLabel(c, device!!.channels), null, c == assignment.outputs) { onChange(assignment.copy(outputs = c)) }
                })
            },
        )
    }
    SettingBlock(
        name = "Level",
        help = "Like an amplifier's gain: set once, so the speakers match each other. The volume turns them all together.",
        control = { VolumeStepper(assignment.level, false, controlWidth) { v -> onChange(assignment.copy(level = v)) } },
    )
}

/**
 * The shelf's Mode and, for the effects, its Delay (Roger, 2026-10-08). Changes live, like a level.
 */
@Composable
private fun SurroundEffect(car: CarSound, controlWidth: Modifier, update: ((CarSound) -> CarSound) -> Unit) {
    val modes = SurroundMode.entries
    SettingBlock(
        name = "Mode",
        help = "Full stereo plays left and right. Surround plays the difference between them, the same on both " +
            "speakers: voices cancel, and the room, the echo and anything panned wide remain. Wide surround is " +
            "the speakers wired across the two positive terminals — left minus right on the left, right minus " +
            "left on the right — and the widest.",
        fullWidthControl = true,
        control = { FitPresetSegment(modes.map { it.label }, modes.indexOf(car.surroundMode)) { i -> update { it.copy(surroundMode = modes[i]) } } },
    )
    if (car.surroundMode != SurroundMode.STEREO) {
        SettingBlock(
            name = "Delay",
            help = "Plays the effect a little late, so it sounds like the room around you rather than another " +
                "speaker. Pro Logic used about 15–20 ms.",
            control = {
                val label = if (car.surroundDelay == 0) "Off" else "${car.surroundDelay} ms"
                ListStepper(label, CarSound.SURROUND_DELAYS, car.surroundDelay, controlWidth) { v -> update { it.copy(surroundDelay = v) } }
            },
        )
    }
}

/**
 * Audio › Equaliser (1.1.3): how the car's sound is shaped on its way to the speakers — the equaliser,
 * balance, fade and crossover. Each control appears only when the speaker layout needs it.
 */
@Composable
fun AudioSoundContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { SoundPreferences(app) }
    val scope = rememberCoroutineScope()
    val soundSettings by prefs.settings.collectAsState(initial = SoundSettings())
    val processor by app.soundProcessor.state.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val car = soundSettings.car
    fun updateCar(t: (CarSound) -> CarSound) = scope.launch { prefs.update { it.copy(car = t(it.car)) } }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Equaliser")
        when {
            !processor.available -> InfoRows(listOf("Car sound" to processor.note))
            !car.enabled -> Note("Car sound is off, so these wait until it is on. Turn it on in Audio › Speakers.")
        }
        // Only what the processor can do (DASH's own chain does it all); before it has said, everything.
        val offers = processor.offers.takeIf { processor.available } ?: SoundControl.entries.toSet()
        if (SoundControl.EQUALISER in offers) {
            SettingBlock(
                name = "Equaliser",
                help = "Ten bands, an octave apart, each up or down 12 dB.",
                fullWidthControl = true,
                control = {
                    Equaliser(car.eq) { band, gain ->
                        updateCar { c -> c.copy(eq = c.eq.toMutableList().also { it[band] = gain.coerceIn(CarSound.EQ_MIN, CarSound.EQ_MAX) }) }
                    }
                },
            )
            SettingBlock(
                name = "Flat",
                help = "Every band back to 0 dB.",
                control = { DashButton("Flat", onClick = { updateCar { it.copy(eq = List(CarSound.EQ_BANDS.size) { 0 }) } }, modifier = controlWidth) },
            )
        }
        if (SoundControl.ANTI_DISTORTION in offers) {
            SettingBlock(
                name = "Anti-distortion",
                help = "Turns everything down by your biggest boost, so the equaliser can never make the sound " +
                    "crackle. Off is louder, but boosts can distort at high volume.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (car.antiDistortion) 1 else 0, controlWidth) { i ->
                        updateCar { it.copy(antiDistortion = i == 1) }
                    }
                },
            )
        }
        if (SoundControl.BALANCE in offers) {
            SettingBlock(
                name = "Balance",
                control = {
                    Stepper(
                        value = balanceLabel(car.balance),
                        modifier = controlWidth,
                        onMinus = { updateCar { it.copy(balance = (it.balance - 1).coerceAtLeast(-CarSound.SIDE_STEPS)) } },
                        onPlus = { updateCar { it.copy(balance = (it.balance + 1).coerceAtMost(CarSound.SIDE_STEPS)) } },
                    )
                },
            )
        }
        if (SoundControl.FADE in offers && car.hasFade) {
            SettingBlock(
                name = "Fade",
                help = "Between the front doors and the rear doors. The parcel shelf is not part of it.",
                control = {
                    Stepper(
                        value = fadeLabel(car.fade),
                        modifier = controlWidth,
                        onMinus = { updateCar { it.copy(fade = (it.fade - 1).coerceAtLeast(-CarSound.SIDE_STEPS)) } },
                        onPlus = { updateCar { it.copy(fade = (it.fade + 1).coerceAtMost(CarSound.SIDE_STEPS)) } },
                    )
                },
            )
        }
        val subCrossover = SoundControl.CROSSOVER in offers && car.usesLow
        if (subCrossover || SoundControl.LOW_CUT in offers) SettingsSectionHeader("Crossover")
        if (subCrossover) {
            SettingBlock(
                name = "Subwoofer",
                help = "The subwoofer plays everything below this.",
                control = { ListStepper("${car.subCutoff} Hz", CarSound.SUB_CUTOFFS, car.subCutoff, controlWidth) { v -> updateCar { it.copy(subCutoff = v) } } },
            )
        }
        if (SoundControl.LOW_CUT in offers) {
            SettingBlock(
                name = "Low cut",
                help = "The other speakers play only above this, sparing small speakers bass they cannot play. Off plays everything.",
                control = {
                    val label = if (car.lowCut == CarSound.LOW_CUT_OFF) "Off" else "${car.lowCut} Hz"
                    ListStepper(label, CarSound.LOW_CUTS, car.lowCut, controlWidth) { v -> updateCar { it.copy(lowCut = v) } }
                },
            )
        }
    }
}

/** A stepper that moves through [values], staying on the ends. */
@Composable
private fun ListStepper(label: String, values: List<Int>, value: Int, modifier: Modifier, onSet: (Int) -> Unit) {
    val i = values.indexOf(value).takeIf { it >= 0 } ?: values.indexOfFirst { it >= value }.coerceAtLeast(0)
    Stepper(
        value = label,
        modifier = modifier,
        onMinus = { onSet(values[(i - 1).coerceAtLeast(0)]) },
        onPlus = { onSet(values[(i + 1).coerceAtMost(values.size - 1)]) },
    )
}

/**
 * The equaliser, drawn as one: a column for each band with its level, a bar from the 0 dB line, and a
 * button above and below — the stepper stood on end, ten abreast, since the design language has no slider.
 */
@Composable
private fun Equaliser(gains: List<Int>, onSet: (Int, Int) -> Unit) {
    val theme = LocalDashTheme.current
    val ink = theme.textColourSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(ink.copy(alpha = 0.08f))
            .border(1.dp, ink.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CarSound.EQ_BANDS.forEachIndexed { band, hz ->
            val gain = gains.getOrElse(band) { 0 }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EqButton("+") { onSet(band, gain + 1) }
                Text(if (gain > 0) "+$gain" else "$gain", color = ink, fontSize = BODY, fontFamily = theme.font, maxLines = 1)
                Canvas(Modifier.width(8.dp).height(96.dp)) {
                    val mid = size.height / 2
                    val h = mid * gain / CarSound.EQ_MAX
                    drawRoundRect(ink.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.width / 2))
                    drawRect(ink, topLeft = Offset(0f, if (h > 0) mid - h else mid), size = Size(size.width, abs(h)))
                    drawLine(ink.copy(alpha = 0.6f), Offset(-3f, mid), Offset(size.width + 3f, mid), strokeWidth = 1.5f)
                }
                EqButton("−") { onSet(band, gain - 1) }
                Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", color = ink.copy(alpha = 0.68f), fontSize = TINY, fontFamily = theme.font, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EqButton(sign: String, onClick: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, color = theme.textColourSecondary, fontSize = SUBHEADING, fontFamily = theme.font)
    }
}

/** Audio › Microphone: which microphone, how sensitive, and a meter to see it hears you. */
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
        SettingsContentHeader("Microphone")
        if (!state.available) InfoRows(listOf("Sound" to state.note))
        SettingBlock(
            name = "Which microphone",
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
 * Audio › Volumes: a level for every source. Android Auto's are DASH-AA's own and always here; anything
 * else appears while it plays. The friend's voice on a call has its own level in Calls.
 */
@Composable
fun AudioMixerContent() {
    val (sound, state) = rememberSound()
    val aa = rememberAaSettings()
    val s = aa.settings
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Volumes")
        SettingBlock(
            name = "Android Auto",
            help = "All of Android Auto's sound. The volume buttons move this when Speakers › Volume buttons is set to Android Auto.",
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

/** The devices to choose between, the one in use filled. */
@Composable
private fun DeviceChoice(devices: List<SoundDevice>, empty: String, onChoose: (SoundDevice) -> Unit) {
    if (devices.isEmpty()) { Note(empty); return }
    ChoiceList(devices.map { d -> ChoiceRow(d.label, d.detail, d.isDefault) { if (!d.isDefault) onChoose(d) } })
}

private data class ChoiceRow(val label: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit)

/**
 * Choices one row each, the chosen one filled — the segmented selector stood on end, because device
 * names are far too long to sit side by side.
 */
@Composable
private fun ChoiceList(rows: List<ChoiceRow>) {
    val theme = LocalDashTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { r ->
            val ink = if (r.selected) theme.backgroundColourSecondary else theme.textColourSecondary
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (r.selected) theme.textColourSecondary else Color.Transparent)
                    .clickable { if (!r.selected) r.onClick() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(r.label, color = ink, fontSize = BODY, fontFamily = theme.font)
                if (r.detail != null) Text(r.detail, color = ink.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
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
