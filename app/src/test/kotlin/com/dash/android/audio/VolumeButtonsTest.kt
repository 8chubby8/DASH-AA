package com.dash.android.audio

import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The volume buttons as a SYSTEM module sends them (system_commands.md: `media_volume_up` /
 * `media_volume_down` event-only, `media_muted` store + event), sent wherever Audio › Output points them.
 */
class VolumeButtonsTest {

    private class FakeSound : SoundSystem {
        val calls = mutableListOf<String>()
        override val state: StateFlow<SoundState> = MutableStateFlow(SoundState(true, "fake",
            outputs = listOf(SoundDevice("58", "Speakers", null, isDefault = true, volume = 0.5f, muted = false))))
        override fun start() {}
        override fun setDefault(device: SoundDevice) {}
        override fun setVolume(id: String, volume: Float) { synchronized(calls) { calls += "volume $id ${"%.2f".format(java.util.Locale.ROOT, volume)}" } }
        override fun setMuted(id: String, muted: Boolean) { synchronized(calls) { calls += "mute $id $muted" } }
        override fun listen(onLevel: (Float) -> Unit): AutoCloseable? = null
    }

    private class FakeViewport : ViewportVolume {
        val calls = mutableListOf<String>()
        override fun stepVolume(direction: Int) { synchronized(calls) { calls += "step $direction" } }
        override fun setMuted(muted: Boolean) { synchronized(calls) { calls += "mute $muted" } }
    }

    private fun run(test: (SystemState, MutableStateFlow<SoundSettings>, FakeSound, FakeViewport) -> Unit) {
        val state = SystemState()
        val settings = MutableStateFlow(SoundSettings())
        val sound = FakeSound()
        val viewport = FakeViewport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        state.fire("media_volume_up", null)                 // pressed before DASH was listening
        Thread.sleep(20)
        VolumeButtons(state, sound, settings, viewport, scope).start()
        Thread.sleep(150)
        try { test(state, settings, sound, viewport) } finally { scope.cancel() }
    }

    @Test fun `by default the buttons turn Android Auto, as before`() = run { state, _, sound, viewport ->
        state.fire("media_volume_up", null)
        state.fire("media_volume_down", null)
        Thread.sleep(150)
        assertEquals(listOf("step 1", "step -1"), synchronized(viewport.calls) { viewport.calls.toList() })
        assertEquals(emptyList(), sound.calls, "an old press is not replayed, and the machine is untouched")
    }

    @Test fun `pointed at the machine they turn the default output in 5 percent steps`() = run { state, settings, sound, viewport ->
        settings.value = SoundSettings(volumeButtons = VolumeTarget.MACHINE)
        Thread.sleep(100)
        state.fire("media_volume_up", null)
        Thread.sleep(150)
        assertEquals(listOf("volume 58 0.55"), synchronized(sound.calls) { sound.calls.toList() })
        assertEquals(emptyList(), viewport.calls)
    }

    @Test fun `mute moves with the choice so nothing is left silent`() = run { state, settings, sound, viewport ->
        state.store("media_muted", "true")
        Thread.sleep(150)
        settings.value = SoundSettings(volumeButtons = VolumeTarget.MACHINE)
        Thread.sleep(150)
        assertEquals(listOf("mute true", "mute false"), synchronized(viewport.calls) { viewport.calls.toList() })
        assertEquals(listOf("mute 58 true"), synchronized(sound.calls) { sound.calls.toList() })
    }
}
