package com.dash.android.audio

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The machine's sound, as DASH's Audio tabs see it (DASH-AA 1.1.2) — which speakers and which
 * microphone, how loud each is, and what else is playing.
 *
 * **The seam between DASH and the platform.** The tabs (`ui/audio/AudioContent.kt`) only ever talk to
 * this interface, never to the sound system underneath, so they carry across editions unchanged.
 * DASH-AA's implementation is PipeWire (`audio/linux/PipeWireSound.kt`). Native supplies an Android one
 * when it catches up; what Android cannot do (choose the speakers, set another app's level) it reports
 * as absent, and the tabs leave it out — capability detection, as everywhere.
 *
 * Volumes are as people hear them: 0 is silent, 1 is full, and a step of 0.05 sounds like a step
 * wherever it is taken. Every call returns at once; the work happens off the caller's thread, and
 * [state] shows the result as the sound system reports it.
 */
interface SoundSystem {
    val state: StateFlow<SoundState>

    fun start()

    /** Make [device] the machine's default, so every sound (or every recording) follows it. */
    fun setDefault(device: SoundDevice)

    /** [id] is a [SoundDevice.id] or a [SoundStream.id]. */
    fun setVolume(id: String, volume: Float)

    fun setMuted(id: String, muted: Boolean)

    /**
     * Listen to the default microphone and report its level, 0–1, about forty times a second — only
     * until the returned handle is closed. Null when nothing can listen. Nothing is recorded or kept.
     */
    fun listen(onLevel: (Float) -> Unit): AutoCloseable?
}

data class SoundState(
    /** False when the sound system is missing or not running; [note] then says why, in plain words. */
    val available: Boolean,
    val note: String,
    val outputs: List<SoundDevice> = emptyList(),
    val inputs: List<SoundDevice> = emptyList(),
    /** What is playing now, DASH-AA's own sound and calls aside (those have their own levels). */
    val streams: List<SoundStream> = emptyList(),
)

data class SoundDevice(
    val id: String,
    /** What the user calls it — "Speakers", "Headphones", a USB card's name. */
    val label: String,
    /** The hardware it belongs to, when [label] alone does not say. */
    val detail: String?,
    val isDefault: Boolean,
    val volume: Float,
    val muted: Boolean,
)

data class SoundStream(
    val id: String,
    /** The app playing it. */
    val label: String,
    /** What it says it is playing, if anything. */
    val detail: String?,
    val volume: Float,
    val muted: Boolean,
)

/** The output everything plays through now, if the sound system has one. */
val SoundState.defaultOutput: SoundDevice? get() = outputs.firstOrNull { it.isDefault }
val SoundState.defaultInput: SoundDevice? get() = inputs.firstOrNull { it.isDefault }

/**
 * **The start-up volume limit** (Audio › Output). A head unit that starts at the volume it was left at
 * can blast the car when the engine turns over; this brings the default output down to [limit] if it
 * was left above it, and never turns anything up.
 */
suspend fun SoundSystem.limitStartupVolume(limit: Float) {
    val out = withTimeoutOrNull(15_000) { state.first { it.defaultOutput != null } }?.defaultOutput ?: return
    if (out.volume > limit) setVolume(out.id, limit)
}
