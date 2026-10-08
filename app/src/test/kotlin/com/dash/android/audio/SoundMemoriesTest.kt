package com.dash.android.audio

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Audio › Saved: slots kept on disk, loading that keeps what it replaces, and undo. */
class SoundMemoriesTest {
    private val tuned = CarSound(
        enabled = true, eq = listOf(3, 2, 0, 0, -1, 0, 0, 1, 2, 4), loudness = 2, loudnessReference = 0.45f, timeAlignment = true,
        speakers = mapOf(SpeakerPosition.FRONT to SpeakerAssignment("card", "Card", listOf("FL", "FR"), level = 0.8f, distances = listOf(85, 120))),
    )
    private val muddled = CarSound(enabled = false, balance = 7)

    @Test fun `a slot is kept on disk, and a new DASH reads it back`() {
        val dir = Files.createTempDirectory("dash-mem").toFile()
        SoundMemories(dir).save(2, tuned)
        val again = SoundMemories(dir)
        assertEquals(tuned, again.slots.value[2]?.car)
        assertNull(again.slots.value[0])
        assertTrue(java.io.File(dir, "slot3.json").exists())
    }

    @Test fun `loading keeps Car sound on or off as it is, and keeps what it replaced`() {
        val m = SoundMemories(Files.createTempDirectory("dash-mem").toFile())
        m.save(0, tuned)
        val loaded = m.load(0, muddled)!!
        assertEquals(tuned.copy(enabled = false), loaded)
        assertTrue(loaded.sameSound(tuned))
        assertEquals(muddled, m.undo.value?.car)
        assertNull(m.load(4, muddled), "an empty slot loads nothing")
    }

    @Test fun `undo puts it back, and undoing the undo goes forward again`() {
        val m = SoundMemories(Files.createTempDirectory("dash-mem").toFile())
        m.save(0, tuned)
        val loaded = m.load(0, muddled)!!
        val back = m.undo(loaded)!!
        assertEquals(muddled, back)
        assertEquals(loaded, m.undo(back))
    }
}
