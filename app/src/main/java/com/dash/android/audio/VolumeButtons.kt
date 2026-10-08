package com.dash.android.audio

import android.util.Log
import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the volume buttons turn up and down — Audio › Speakers › Volume buttons. */
enum class VolumeTarget {
    /** The machine's volume: everything that plays. */
    MACHINE,
    /** The viewport's tenant only — Android Auto on DASH-AA. */
    VIEWPORT,
    // A sound module joins here once one is designed (roadmap, Later — Roger's crossover).
}

/** The viewport tenant's own volume, for [VolumeTarget.VIEWPORT]. */
interface ViewportVolume {
    fun stepVolume(direction: Int)
    fun setMuted(muted: Boolean)
}

/**
 * **The volume buttons** (DASH-AA 1.1.2) — `media_volume_up`, `media_volume_down` and `media_muted`
 * (system_commands.md), from the steering-wheel module or any module that sends them, sent wherever the
 * user chose (Roger, 2026-10-07: "let it be decided by the user which it controls").
 *
 * They were Android Auto's until now, heard only while a phone projected. Here they are DASH's, heard for
 * the life of the process, so with the machine chosen they work with no phone at all.
 *
 * **Mute follows the choice.** `media_muted` is a state, not a press: when the choice changes while muted,
 * the old target is unmuted and the new one muted, so nothing is left silent that the buttons no longer
 * reach.
 */
class VolumeButtons(
    private val state: SystemState,
    private val sound: SoundSystem,
    private val settings: Flow<SoundSettings>,
    private val viewport: ViewportVolume,
    private val scope: CoroutineScope,
) {
    @Volatile private var target = SoundSettings().volumeButtons
    @Volatile private var muted = false

    fun start() {
        val since = System.currentTimeMillis()
        scope.launch {
            settings.map { it.volumeButtons }.distinctUntilChanged().collect { next ->
                val previous = target
                target = next
                if (muted && previous != next) { mute(previous, false); mute(next, true) }
            }
        }
        scope.launch {
            state.values.map { it["media_muted"]?.value }.distinctUntilChanged().collect { v ->
                if (v == null) return@collect
                muted = v.equals("true", ignoreCase = true) || v == "1" || v.equals("on", ignoreCase = true)
                mute(target, muted)
            }
        }
        scope.launch {
            // The event bus replays recent history to a new subscriber; a press from before DASH was
            // listening is not a press now.
            state.events.collect { e ->
                if (e.at < since) return@collect
                val direction = when (e.function) {
                    "media_volume_up" -> +1
                    "media_volume_down" -> -1
                    else -> return@collect
                }
                step(target, direction)
            }
        }
    }

    private fun step(to: VolumeTarget, direction: Int) {
        when (to) {
            VolumeTarget.VIEWPORT -> viewport.stepVolume(direction)
            VolumeTarget.MACHINE -> {
                val out = sound.state.value.defaultOutput
                if (out == null) { Log.w(TAG, "volume button: no output to turn"); return }
                sound.setVolume(out.id, (((out.volume * 20).roundToInt() + direction).coerceIn(0, 20)) / 20f)
            }
        }
        Log.i(TAG, "volume ${if (direction > 0) "up" else "down"} → $to")
    }

    private fun mute(to: VolumeTarget, on: Boolean) {
        when (to) {
            VolumeTarget.VIEWPORT -> viewport.setMuted(on)
            VolumeTarget.MACHINE -> sound.state.value.defaultOutput?.let { sound.setMuted(it.id, on) }
        }
    }

    private companion object {
        const val TAG = "DashVolume"
    }
}
