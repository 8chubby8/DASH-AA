package com.dash.android.audio

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow

/**
 * **The car's sound** (DASH-AA 1.1.3): where each speaker plays, and how the sound is shaped on its way
 * there — DASH's own sound settings, the ones a car's head unit keeps, as opposed to the machine's
 * (which device is the default and how loud, in [SoundSystem]).
 *
 * The layout is Roger's XF (2026-10-08): speakers in the front and rear doors, a pair on the parcel
 * shelf, a centre in the dashboard and a subwoofer. Each position plays through **a device of the
 * user's choosing, or none**, so the layout fits whatever car it is put in.
 *
 * **Built ready for a sound module** (Roger, 2026-10-07): these settings are handed to a
 * [SoundProcessor], which does the work. DASH-AA's is a PipeWire chain (`audio/linux/PipeWireChain.kt`);
 * an outboard DSP module can be another. What each output carries is worked out here, in [feeds], so
 * every processor shapes the sound the same way.
 */
@Serializable
data class CarSound(
    /** Audio › Speakers › Car sound. Off: everything plays straight to one device, as before 1.1.3. */
    val enabled: Boolean = false,
    val speakers: Map<SpeakerPosition, SpeakerAssignment> = emptyMap(),
    /** Gain of each [EQ_BANDS] band, in whole dB, [EQ_MIN]–[EQ_MAX]. */
    val eq: List<Int> = List(EQ_BANDS.size) { 0 },
    /** −[SIDE_STEPS] (left only) … 0 (centre) … +[SIDE_STEPS] (right only). */
    val balance: Int = 0,
    /**
     * −[SIDE_STEPS] (front only) … 0 … +[SIDE_STEPS] (rear only): the front doors (and centre) against the
     * rear doors. The parcel shelf is not part of it (Roger, 2026-10-08), and it is offered only when both
     * front and rear have a device ([hasFade]).
     */
    val fade: Int = 0,
    /** Where the subwoofer stops, in Hz. */
    val subCutoff: Int = SUB_CUTOFF_DEFAULT,
    /** The other speakers' low cut, in Hz; [LOW_CUT_OFF] lets them play everything. */
    val lowCut: Int = LOW_CUT_OFF,
    /**
     * Everything turned down by the biggest equaliser boost first, so a boost can never push the sound
     * past what the sound card can play and make it crackle (Roger, 2026-10-08: on by default).
     */
    val antiDistortion: Boolean = true,
    /** Audio › Calls: which speakers a call plays through (Roger, 2026-10-08). */
    val calls: CallRouting = CallRouting.ALL,
    /** Whether a call plays through the subwoofer too. */
    val callsSubwoofer: Boolean = true,
    /** What the parcel shelf plays (Roger, 2026-10-08): full stereo, or one of the two surround effects. */
    val surroundMode: SurroundMode = SurroundMode.STEREO,
    /** How late the surround effect plays, in ms; 0 is no delay. Pro Logic used 15–20. */
    val surroundDelay: Int = 0,
) {
    companion object {
        /** A ten-band graphic equaliser, an octave apart. */
        val EQ_BANDS = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val EQ_MIN = -12
        const val EQ_MAX = 12
        const val SIDE_STEPS = 10
        const val SUB_CUTOFF_DEFAULT = 80
        val SUB_CUTOFFS = (40..200 step 10).toList()
        const val LOW_CUT_OFF = 0
        val LOW_CUTS = listOf(LOW_CUT_OFF) + (40..200 step 10).toList()
        val SURROUND_DELAYS = (0..30 step 5).toList()
    }
}

/**
 * The speaker positions, front to back, and how many signals each carries: [stereo] positions a left
 * and a right, the others one.
 */
@Serializable
enum class SpeakerPosition(val label: String, val stereo: Boolean) {
    FRONT("Front", stereo = true),
    REAR("Rear", stereo = true),
    /** The parcel shelf: full stereo or a surround effect, the user's choice ([SurroundMode]). */
    SURROUND("Surround", stereo = true),
    CENTRE("Centre", stereo = false),
    SUBWOOFER("Subwoofer", stereo = false),
}

/**
 * What the parcel shelf plays (Roger, 2026-10-08). The effects are the passive matrix a speaker wired
 * across the two positive terminals gives (David Hafler's, which Dolby Surround grew from): the difference
 * between left and right, so whatever is the same in both — the voice, mostly — cancels, and the room,
 * the reverb and whatever is panned wide remain. Not called Dolby or Pro Logic, which are Dolby's marks.
 */
@Serializable
enum class SurroundMode(val label: String) {
    /** Left and right, as the doors play them. */
    STEREO("Full stereo"),
    /** The difference, the same to both shelf speakers — Pro Logic's single surround channel. */
    SURROUND("Surround"),
    /** True Hafler: left minus right on the left, right minus left on the right. The widest. */
    WIDE("Wide surround"),
}

/** Where one speaker position plays. */
@Serializable
data class SpeakerAssignment(
    /** The device's [SoundDevice.key], which outlasts reboots and replugging. */
    val device: String,
    /** The device's name when it was chosen — shown while it is not plugged in. */
    val label: String,
    /**
     * Which of the device's outputs, by channel name (`FL`, `RL`, `AUX3`…) — one of [outputChoices]. A
     * stereo position on two outputs plays left on the first and right on the second; on one output it
     * plays both, mixed. A one-signal position plays the same on every output named.
     */
    val outputs: List<String>,
    /** The position's level, 0–1: its amplifier gain, set once to match the speakers to each other. */
    val level: Float = 1f,
)

/** One signal the car's sound is made from, after the equaliser. */
enum class SoundSource {
    /** The left side, with the low cut. */
    LEFT,
    /** The right side, with the low cut. */
    RIGHT,
    /** Left and right together, through the subwoofer's crossover. */
    LOW,
    /** Half of left minus right, with the low cut, delayed by [CarSound.surroundDelay] — the surround effect. */
    DIFF,
}

/** A part of what one output plays: [source], turned by [gain]. */
data class SoundFeed(val source: SoundSource, val gain: Float)

/**
 * What every output plays, keyed by the device's key and its channel: the sum of its [SoundFeed]s. An
 * output given to two positions plays both. The shape of the map (which outputs, fed from which sources)
 * changes only with the layout; balance, fade, the levels and the surround mode change only the gains.
 */
fun CarSound.feeds(): Map<Pair<String, String>, List<SoundFeed>> = layoutFeeds { position, source, level ->
    val fadeGain = when (position) {
        SpeakerPosition.FRONT, SpeakerPosition.CENTRE -> frontGain()
        SpeakerPosition.REAR -> rearGain()
        else -> 1f
    }
    when {
        position == SpeakerPosition.SUBWOOFER -> level
        // The centre sits in the middle: balance moves it only as far as it moves the middle.
        !position.stereo -> level * (leftGain() + rightGain()) / 2 * fadeGain
        position == SpeakerPosition.SURROUND -> surroundGain(source) * level
        source == SoundSource.LEFT -> level * leftGain() * fadeGain
        else -> level * rightGain() * fadeGain
    }
}

/**
 * The shelf's gain for each part of each speaker's feed — [source] is LEFT for the left speaker's own
 * side, RIGHT for the right's, and [SoundSource.DIFF] with each speaker's balance and sign folded in by
 * [layoutFeeds] (negative on the right in [SurroundMode.WIDE]).
 */
private fun CarSound.surroundGain(source: SoundSource): Float = when (surroundMode) {
    SurroundMode.STEREO -> when (source) { SoundSource.LEFT -> leftGain(); SoundSource.RIGHT -> rightGain(); else -> 0f }
    else -> if (source == SoundSource.DIFF) 1f else 0f
}

/**
 * What every output plays of a call: the same outputs fed from the same sources as [feeds], so a call
 * can be moved about without the layout changing shape — only the gains differ. Balance, fade and the
 * surround effect are the music's and leave a call alone. [driverOnRight] is which front speaker is the
 * driver's.
 */
fun CarSound.callFeeds(driverOnRight: Boolean): Map<Pair<String, String>, List<SoundFeed>> {
    val routing = effectiveCalls()
    return layoutFeeds { position, source, level ->
        val plays = source != SoundSource.DIFF && when (position) {
            SpeakerPosition.SUBWOOFER -> callsSubwoofer
            SpeakerPosition.FRONT -> routing != CallRouting.DRIVER ||
                (speakers[position]?.outputs?.size ?: 0) < 2 ||
                source == (if (driverOnRight) SoundSource.RIGHT else SoundSource.LEFT)
            SpeakerPosition.CENTRE -> routing != CallRouting.DRIVER
            else -> routing == CallRouting.ALL
        }
        if (plays) level else 0f
    }
}

/**
 * Every output the layout uses and the sources feeding it, each given its gain by [gain]. The shelf is
 * always fed its own side and the difference, so changing its mode is only a change of gains.
 */
private fun CarSound.layoutFeeds(gain: (SpeakerPosition, SoundSource, Float) -> Float): Map<Pair<String, String>, List<SoundFeed>> {
    val out = LinkedHashMap<Pair<String, String>, MutableList<SoundFeed>>()
    fun add(device: String, channel: String, position: SpeakerPosition, source: SoundSource, share: Float, level: Float) {
        out.getOrPut(device to channel) { mutableListOf() }.add(SoundFeed(source, share * gain(position, source, level)))
    }
    for (position in SpeakerPosition.entries) {
        val a = speakers[position] ?: continue
        if (a.outputs.isEmpty()) continue
        val shelf = position == SpeakerPosition.SURROUND
        when {
            position == SpeakerPosition.SUBWOOFER ->
                a.outputs.forEach { add(a.device, it, position, SoundSource.LOW, 1f, a.level) }
            position.stereo && a.outputs.size >= 2 -> {
                add(a.device, a.outputs[0], position, SoundSource.LEFT, 1f, a.level)
                if (shelf) add(a.device, a.outputs[0], position, SoundSource.DIFF, leftGain(), a.level)
                add(a.device, a.outputs[1], position, SoundSource.RIGHT, 1f, a.level)
                if (shelf) add(a.device, a.outputs[1], position, SoundSource.DIFF,
                    (if (surroundMode == SurroundMode.WIDE) -1f else 1f) * rightGain(), a.level)
            }
            else -> a.outputs.take(if (position.stereo) 1 else a.outputs.size).forEach {
                add(a.device, it, position, SoundSource.LEFT, 0.5f, a.level)
                add(a.device, it, position, SoundSource.RIGHT, 0.5f, a.level)
                if (shelf) add(a.device, it, position, SoundSource.DIFF, 1f, a.level)
            }
        }
    }
    return out
}

/** Which speakers a call can play through. */
@Serializable
enum class CallRouting(val label: String) { ALL("All speakers"), FRONT("Front only"), DRIVER("Driver's side") }

/**
 * The call routings this layout makes sense of (Roger, 2026-10-08): *Front only* only when there is
 * something behind the front, *Driver's side* only when there are front speakers.
 */
fun CarSound.callChoices(): List<CallRouting> = listOfNotNull(
    CallRouting.ALL,
    CallRouting.FRONT.takeIf { hasBehind },
    CallRouting.DRIVER.takeIf { speakers[SpeakerPosition.FRONT] != null },
)

/** The routing in force: one the layout no longer allows falls back to all speakers. */
fun CarSound.effectiveCalls(): CallRouting = calls.takeIf { it in callChoices() } ?: CallRouting.ALL

/**
 * Anti-distortion's turn-down, as a gain: the biggest boost taken off everything first, so the boosted
 * band ends at 0 dB and the rest below. 1 when it is off or nothing is boosted.
 */
fun CarSound.preGain(): Float {
    val boost = eq.maxOrNull()?.coerceAtLeast(0) ?: 0
    return if (antiDistortion && boost > 0) 10f.pow(-boost / 20f) else 1f
}

/** Whether any output takes the subwoofer's signal — no crossover is built when none does. */
val CarSound.usesLow: Boolean get() = speakers[SpeakerPosition.SUBWOOFER]?.outputs?.isNotEmpty() == true

/** Something plays behind the front seats — so a call can be kept to the front. */
val CarSound.hasBehind: Boolean get() = speakers[SpeakerPosition.REAR] != null || speakers[SpeakerPosition.SURROUND] != null

/** Fade is offered only when both the front and the rear doors have a device (Roger, 2026-10-08). */
val CarSound.hasFade: Boolean get() = speakers[SpeakerPosition.FRONT] != null && speakers[SpeakerPosition.REAR] != null

/** Each side's share of the balance: full until the balance moves away from it, then down a tenth a step. */
fun CarSound.leftGain(): Float = if (balance > 0) 1f - balance.toFloat() / CarSound.SIDE_STEPS else 1f
fun CarSound.rightGain(): Float = if (balance < 0) 1f + balance.toFloat() / CarSound.SIDE_STEPS else 1f
fun CarSound.frontGain(): Float = if (fade > 0) 1f - fade.toFloat() / CarSound.SIDE_STEPS else 1f
fun CarSound.rearGain(): Float = if (fade < 0) 1f + fade.toFloat() / CarSound.SIDE_STEPS else 1f

/** How the balance reads: "Centre", "Left 3", "Right 10". */
fun balanceLabel(balance: Int): String = when {
    balance < 0 -> "Left ${abs(balance)}"
    balance > 0 -> "Right $balance"
    else -> "Centre"
}

fun fadeLabel(fade: Int): String = when {
    fade < 0 -> "Front ${abs(fade)}"
    fade > 0 -> "Rear $fade"
    else -> "Centre"
}

/**
 * The ways [position] can use a device whose outputs are [channels] (PipeWire's names, in order): for a
 * stereo position, each pair; for the others, each output alone — and on a two-output device, both at
 * once as well, first. A device that does not say counts as stereo.
 */
fun outputChoices(position: SpeakerPosition, channels: List<String>): List<List<String>> {
    val ch = channels.ifEmpty { listOf("FL", "FR") }
    if (ch.size == 1) return listOf(ch)
    return if (position.stereo) ch.chunked(2)
    else (if (ch.size == 2) listOf(ch) else emptyList()) + ch.map { listOf(it) }
}

/** The choice a position starts on: the outputs named for it if the device has them, else the first. */
fun defaultOutputs(position: SpeakerPosition, channels: List<String>): List<String> {
    val choices = outputChoices(position, channels)
    val wanted = when (position) {
        SpeakerPosition.FRONT -> "FL"
        SpeakerPosition.REAR -> "RL"
        SpeakerPosition.SURROUND -> "SL"
        SpeakerPosition.CENTRE -> "FC"
        SpeakerPosition.SUBWOOFER -> "LFE"
    }
    return choices.firstOrNull { it.first() == wanted && (it.size == 1 || position.stereo) } ?: choices.first()
}

/** How a choice of outputs reads: "Both", "Left", "Rear", "Output 3 + Output 4". */
fun outputsLabel(outputs: List<String>, channels: List<String>): String {
    val ch = channels.ifEmpty { listOf("FL", "FR") }
    if (ch.size == 2 && ch == listOf("FL", "FR")) return when (outputs) {
        ch -> "Both"
        listOf("FL") -> "Left"
        listOf("FR") -> "Right"
        else -> outputs.joinToString(" + ") { channelLabel(it) }
    }
    if (outputs.size == 2) {
        val (a, b) = outputs.map { channelLabel(it) }
        val side = Regex("""^(.*) left$""").find(a)?.groupValues?.get(1)
        if (side != null && b == "$side right") return side
    }
    return outputs.joinToString(" + ") { channelLabel(it) }
}

/** PipeWire's channel names, as a car fitter would say them. */
fun channelLabel(channel: String): String = when (channel) {
    "MONO" -> "Mono"
    "FL" -> "Front left"
    "FR" -> "Front right"
    "FC" -> "Centre"
    "LFE" -> "Subwoofer"
    "RL" -> "Rear left"
    "RR" -> "Rear right"
    "RC" -> "Rear centre"
    "SL" -> "Side left"
    "SR" -> "Side right"
    else -> Regex("""^AUX(\d+)$""").find(channel)?.let { "Output ${it.groupValues[1].toInt() + 1}" } ?: channel
}

/**
 * **What does the work** — DASH-AA's PipeWire chain, or one day a sound module. DASH hands it the car's
 * sound whenever the settings change, and it reports whether it can and does.
 */
interface SoundProcessor {
    val state: StateFlow<ProcessorState>

    fun start()

    /**
     * Make the sound so. [driverOnRight] says which side the driver sits, for calls on the driver's side.
     * Returns at once; the work is done off the caller's thread.
     */
    fun apply(sound: CarSound, driverOnRight: Boolean)
}

data class ProcessorState(
    /** False when this machine cannot run it; [note] then says why, in plain words. */
    val available: Boolean,
    /** Whether the sound is going through it now. */
    val running: Boolean = false,
    val note: String = "",
    /**
     * What it can do. The tabs show only these, so no control is ever there that does nothing — a sound
     * module or Android offers fewer than DASH-AA's PipeWire chain, which offers them all.
     */
    val offers: Set<SoundControl> = emptySet(),
    /**
     * The sound is up and as the user left it — the `sound_ready` system signal (Roger, 2026-10-08). False
     * while starting, restarting, changing the layout or being restored, so amplifiers can stay quiet.
     */
    val ready: Boolean = false,
)

/** The controls a [SoundProcessor] may offer. */
enum class SoundControl { LAYOUT, EQUALISER, ANTI_DISTORTION, BALANCE, FADE, CROSSOVER, LOW_CUT, CALL_ROUTING, SURROUND_EFFECT }
