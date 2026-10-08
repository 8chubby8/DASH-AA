package com.dash.android.audio

import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * **`sound_ready`** (DASH-AA 1.1.3, Roger 2026-10-08) — DASH's version of a car amplifier's remote wire,
 * raised by DASH itself into the sourceless core, so any module subscribed to it hears it like any other
 * system signal (transport.md: DASH relays what it "receives or internally generates").
 *
 * True when DASH's sound is up and as the user left it; false before it stops, while it starts, restarts,
 * changes its speaker layout or is being restored. With Car sound on, the [SoundProcessor] says; with it
 * off, the sound system simply being there is enough. [quiet] lowers it as DASH closes, before the sound
 * goes, and waits a moment for it to reach the modules.
 */
class SoundReady(private val state: SystemState) {

    fun start(scope: CoroutineScope, sound: Flow<SoundState>, processor: Flow<ProcessorState>, car: Flow<CarSound>) {
        raise(false)
        scope.launch {
            combine(sound, processor, car) { s, p, c -> if (c.enabled && p.available) p.ready else s.available }
                .distinctUntilChanged()
                .collect { raise(it) }
        }
    }

    fun quiet() {
        raise(false)
        Thread.sleep(QUIET_MS)
    }

    private fun raise(ready: Boolean) {
        val value = ready.toString()
        if (state.current(SIGNAL) == value) return
        state.store(SIGNAL, value)
        state.fire(SIGNAL, value)
    }

    private companion object {
        const val SIGNAL = "sound_ready"
        /** Long enough for the streams desk to send it down every transport. */
        const val QUIET_MS = 300L
    }
}
