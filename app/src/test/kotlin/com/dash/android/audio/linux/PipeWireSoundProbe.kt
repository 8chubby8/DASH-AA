package com.dash.android.audio.linux

import com.dash.android.audio.SoundState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in (-Dsound=1): the real PipeWire. Plays a silent stream from a test app and checks it appears in
 * the Mixer, its level can be set and read back, and it goes when it stops; then listens to the
 * microphone for a second. Never changes the machine's default devices or their volume.
 */
class PipeWireSoundProbe {
    private fun waitFor(sound: PipeWireSound, what: String, test: (SoundState) -> Boolean): SoundState {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) { sound.state.value.let { if (test(it)) return it }; Thread.sleep(50) }
        error("timed out waiting for $what — last: ${sound.state.value}")
    }

    @Test fun `the live graph follows a stream and sets its level`() {
        if (System.getProperty("sound") == null) return
        val sound = PipeWireSound().apply { start() }
        val s = waitFor(sound, "PipeWire") { it.available }
        println("outputs: ${s.outputs}\ninputs: ${s.inputs}\nstreams: ${s.streams}")

        val player = ProcessBuilder(
            "pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
            "-P", "node.name=dash-probe application.name=DASH-Probe application.id=dash-probe", "/dev/zero",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val stream = waitFor(sound, "the probe's stream") { st -> st.streams.any { it.label == "DASH-Probe" } }
                .streams.first { it.label == "DASH-Probe" }
            sound.setVolume(stream.id, 0.4f)
            sound.setVolume(stream.id, 0.45f)                 // a second press while the first is sent
            Thread.sleep(600)
            val after = sound.state.value.streams.first { it.id == stream.id }
            println("probe stream at ${after.volume}")
            assertEquals(0.45f, after.volume, 0.01f)
        } finally {
            player.destroy()
        }
        waitFor(sound, "the stream to go") { st -> st.streams.none { it.label == "DASH-Probe" } }

        val levels = mutableListOf<Float>()
        val meter = sound.listen { synchronized(levels) { levels += it } }
        Thread.sleep(1000)
        meter?.close()
        println("microphone: ${levels.size} readings, loudest ${levels.maxOrNull()}")
        assertTrue(levels.size > 20, "the meter reports about forty times a second")
    }
}
