package com.dash.android.audio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The car's sound as settings: what each output plays, the choices of outputs, and how it is stored. */
class CarSoundTest {
    private val stereo = listOf("FL", "FR")
    private val surround71 = listOf("FL", "FR", "RL", "RR", "FC", "LFE", "SL", "SR")

    /** Roger's XF: doors front and rear on one card, the shelf on another, centre and sub on a third. */
    private val xf = CarSound(
        enabled = true,
        speakers = mapOf(
            SpeakerPosition.FRONT to SpeakerAssignment("card71", "7.1 card", listOf("FL", "FR")),
            SpeakerPosition.REAR to SpeakerAssignment("card71", "7.1 card", listOf("RL", "RR")),
            SpeakerPosition.SURROUND to SpeakerAssignment("usb", "USB card", listOf("FL", "FR"), level = 0.8f),
            SpeakerPosition.CENTRE to SpeakerAssignment("card71", "7.1 card", listOf("FC")),
            SpeakerPosition.SUBWOOFER to SpeakerAssignment("card71", "7.1 card", listOf("LFE")),
        ),
    )

    @Test fun `each position feeds its own outputs`() {
        val f = xf.feeds()
        assertEquals(listOf(SoundFeed(SoundSource.LEFT, 1f)), f["card71" to "FL"])
        assertEquals(listOf(SoundFeed(SoundSource.RIGHT, 1f)), f["card71" to "RR"])
        assertEquals(listOf(SoundFeed(SoundSource.LEFT, 0.8f), SoundFeed(SoundSource.DIFF, 0f)), f["usb" to "FL"])
        assertEquals(listOf(SoundFeed(SoundSource.LEFT, 0.5f), SoundFeed(SoundSource.RIGHT, 0.5f)), f["card71" to "FC"])
        assertEquals(listOf(SoundFeed(SoundSource.LOW, 1f)), f["card71" to "LFE"])
        assertEquals(8, f.size)                                      // four door outputs, the shelf's two, centre, sub
        assertTrue(xf.usesLow)
        assertTrue(xf.hasBehind)
        assertTrue(xf.hasFade)
    }

    @Test fun `balance turns one side down, a tenth a step`() {
        val f = xf.copy(balance = -3).feeds()                         // towards the left
        assertEquals(1f, f.getValue("card71" to "FL").single().gain)
        assertEquals(0.7f, f.getValue("card71" to "FR").single().gain, 1e-6f)
        // The centre moves only as far as the middle does; the subwoofer not at all.
        assertEquals(0.5f * 0.85f, f.getValue("card71" to "FC").first().gain, 1e-6f)
        assertEquals(1f, f.getValue("card71" to "LFE").single().gain)
        assertEquals("Left 3", balanceLabel(-3))
        assertEquals("Centre", balanceLabel(0))
    }

    @Test fun `fade is the front doors against the rear doors, and leaves the shelf alone`() {
        val f = xf.copy(fade = 4).feeds()                             // towards the rear
        assertEquals(0.6f, f.getValue("card71" to "FL").single().gain, 1e-6f)
        assertEquals(0.6f * 0.5f, f.getValue("card71" to "FC").first().gain, 1e-6f)
        assertEquals(1f, f.getValue("card71" to "RL").single().gain)
        assertEquals(0.8f, f.getValue("usb" to "FL").first().gain, 1e-6f)   // the shelf: not faded, its own level
        assertEquals(1f, f.getValue("card71" to "LFE").single().gain)
        val all = xf.copy(fade = -10).feeds()                         // front only: behind is silent
        assertEquals(0f, all.getValue("card71" to "RL").single().gain)
        assertEquals("Rear 4", fadeLabel(4))
    }

    @Test fun `an output given to two positions plays both, and a stereo pair on one output is mixed`() {
        val shared = CarSound(enabled = true, speakers = mapOf(
            SpeakerPosition.FRONT to SpeakerAssignment("a", "A", listOf("FL", "FR")),
            SpeakerPosition.CENTRE to SpeakerAssignment("a", "A", listOf("FL", "FR")),
            SpeakerPosition.REAR to SpeakerAssignment("b", "B", listOf("MONO")),
        ))
        val f = shared.feeds()
        assertEquals(listOf(SoundSource.LEFT, SoundSource.LEFT, SoundSource.RIGHT), f.getValue("a" to "FL").map { it.source })
        assertEquals(listOf(SoundFeed(SoundSource.LEFT, 0.5f), SoundFeed(SoundSource.RIGHT, 0.5f)), f["b" to "MONO"])
        assertFalse(shared.usesLow)
    }

    @Test fun `the gains change with balance and fade but the shape does not`() {
        val shape = { c: CarSound -> c.feeds().mapValues { (_, v) -> v.map { it.source } } }
        assertEquals(shape(xf), shape(xf.copy(balance = 7, fade = -2)))
    }

    @Test fun `output choices fit the position and the device`() {
        assertEquals(listOf(stereo), outputChoices(SpeakerPosition.FRONT, stereo))
        assertEquals(listOf(stereo, listOf("FL"), listOf("FR")), outputChoices(SpeakerPosition.SUBWOOFER, stereo))
        assertEquals(listOf(listOf("FL", "FR"), listOf("RL", "RR"), listOf("FC", "LFE"), listOf("SL", "SR")),
            outputChoices(SpeakerPosition.REAR, surround71))
        assertEquals(8, outputChoices(SpeakerPosition.CENTRE, surround71).size)
        assertEquals(listOf(stereo), outputChoices(SpeakerPosition.FRONT, emptyList()))    // unknown counts as stereo
        assertEquals(listOf(listOf("MONO")), outputChoices(SpeakerPosition.FRONT, listOf("MONO")))
    }

    @Test fun `a position starts on the outputs named for it`() {
        assertEquals(listOf("RL", "RR"), defaultOutputs(SpeakerPosition.REAR, surround71))
        assertEquals(listOf("SL", "SR"), defaultOutputs(SpeakerPosition.SURROUND, surround71))
        assertEquals(listOf("FC"), defaultOutputs(SpeakerPosition.CENTRE, surround71))
        assertEquals(listOf("LFE"), defaultOutputs(SpeakerPosition.SUBWOOFER, surround71))
        assertEquals(stereo, defaultOutputs(SpeakerPosition.SUBWOOFER, stereo))             // both, on a stereo card
        assertEquals(stereo, defaultOutputs(SpeakerPosition.REAR, stereo))
    }

    @Test fun `outputs read as a fitter says them`() {
        assertEquals("Both", outputsLabel(stereo, stereo))
        assertEquals("Left", outputsLabel(listOf("FL"), stereo))
        assertEquals("Rear", outputsLabel(listOf("RL", "RR"), surround71))
        assertEquals("Centre + Subwoofer", outputsLabel(listOf("FC", "LFE"), surround71))
        assertEquals("Output 3 + Output 4", outputsLabel(listOf("AUX2", "AUX3"), listOf("AUX0", "AUX1", "AUX2", "AUX3")))
    }

    @Test fun `it is stored and read back whole, and survives a stranger's keys`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val c = xf.copy(eq = listOf(3, 2, 0, 0, -1, 0, 0, 1, 2, 4), balance = -2, fade = 5, subCutoff = 60, lowCut = 80)
        val text = json.encodeToString(CarSound.serializer(), c)
        assertEquals(c, json.decodeFromString(CarSound.serializer(), text))
        val later = text.replaceFirst("{", "{\"speakerPhase\":[1,2,3],")
        assertEquals(c, json.decodeFromString(CarSound.serializer(), later))
    }

    @Test fun `anti-distortion turns down by the biggest boost, and only a boost`() {
        assertEquals(1f, xf.preGain())                                              // flat
        assertEquals(0.5012f, xf.copy(eq = listOf(6, 0, 0, 0, -3, 0, 0, 0, 0, 2)).preGain(), 0.001f)
        assertEquals(1f, xf.copy(eq = listOf(-6, 0, 0, 0, 0, 0, 0, 0, 0, 0)).preGain())
        assertEquals(1f, xf.copy(eq = listOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0), antiDistortion = false).preGain())
    }

    @Test fun `calls go where they are sent, with the music's shape`() {
        val shape = { f: Map<Pair<String, String>, List<SoundFeed>> -> f.mapValues { (_, v) -> v.map { it.source } } }
        val all = xf.callFeeds(driverOnRight = true)
        assertEquals(shape(xf.feeds()), shape(all))
        assertEquals(1f, all.getValue("card71" to "RL").single().gain)
        assertEquals(1f, all.getValue("card71" to "FL").single().gain, "balance and fade are the music's")

        val front = xf.copy(calls = CallRouting.FRONT, balance = -5).callFeeds(true)
        assertEquals(0f, front.getValue("card71" to "RL").single().gain)
        assertEquals(0f, front.getValue("usb" to "FL").first().gain)
        assertEquals(0.5f, front.getValue("card71" to "FC").first().gain)

        val driver = xf.copy(calls = CallRouting.DRIVER).callFeeds(driverOnRight = true)
        assertEquals(1f, driver.getValue("card71" to "FR").single().gain)
        assertEquals(0f, driver.getValue("card71" to "FL").single().gain)
        assertEquals(0f, driver.getValue("card71" to "FC").first().gain)

        val noSub = xf.copy(callsSubwoofer = false).callFeeds(true)
        assertEquals(0f, noSub.getValue("card71" to "LFE").single().gain)
    }

    @Test fun `call choices follow the speakers there are`() {
        val frontOnly = CarSound(enabled = true, speakers = mapOf(SpeakerPosition.FRONT to SpeakerAssignment("a", "A", listOf("FL", "FR"))))
        assertEquals(listOf(CallRouting.ALL, CallRouting.DRIVER), frontOnly.callChoices())
        assertEquals(listOf(CallRouting.ALL, CallRouting.FRONT, CallRouting.DRIVER), xf.callChoices())
        // A choice the layout no longer allows plays through all.
        assertEquals(CallRouting.ALL, frontOnly.copy(calls = CallRouting.FRONT).effectiveCalls())
    }

    @Test fun `fade is offered only with front and rear doors`() {
        val shelfOnly = CarSound(enabled = true, speakers = mapOf(SpeakerPosition.SURROUND to SpeakerAssignment("a", "A", listOf("FL", "FR"))))
        assertFalse(shelfOnly.hasFade)
        assertFalse(xf.copy(speakers = xf.speakers - SpeakerPosition.REAR).hasFade)
        assertTrue(xf.hasFade)
    }

    @Test fun `the shelf plays stereo, the difference, or the difference wide`() {
        fun shelf(mode: SurroundMode, balance: Int = 0) = xf.copy(surroundMode = mode, balance = balance).feeds()
            .let { f -> f.getValue("usb" to "FL").map { it.gain } to f.getValue("usb" to "FR").map { it.gain } }
        // Each shelf speaker: [its own side, the difference] — at the shelf's level, 0.8.
        assertEquals(listOf(0.8f, 0f) to listOf(0.8f, 0f), shelf(SurroundMode.STEREO))
        assertEquals(listOf(0f, 0.8f) to listOf(0f, 0.8f), shelf(SurroundMode.SURROUND))
        assertEquals(listOf(0f, 0.8f) to listOf(0f, -0.8f), shelf(SurroundMode.WIDE))    // right minus left on the right
        val (l, r) = shelf(SurroundMode.WIDE, balance = 5)                                   // balance still moves it
        assertEquals(0.4f, l[1], 1e-6f); assertEquals(-0.8f, r[1], 1e-6f)
        // The mode changes the gains, never the shape: no restart.
        val shape = { m: SurroundMode -> xf.copy(surroundMode = m).feeds().mapValues { (_, v) -> v.map { it.source } } }
        assertEquals(shape(SurroundMode.STEREO), shape(SurroundMode.WIDE))
        // A call never plays the effect.
        assertTrue(xf.copy(surroundMode = SurroundMode.WIDE).callFeeds(false).values.flatten().filter { it.source == SoundSource.DIFF }.all { it.gain == 0f })
    }

    @Test fun `speed volume rises with each doubling of speed, and only when the speed is known`() {
        assertEquals(0f, speedBoostDb(null, 10))
        assertEquals(0f, speedBoostDb(120f, 0))
        assertEquals(0f, speedBoostDb(25f, 10))
        assertEquals(4.77f, speedBoostDb(112.65f, 5), 0.02f)                  // 70 mph at 5
        assertEquals(2f * speedBoostDb(60f, 5), speedBoostDb(120f, 5), 0.001f)
        assertEquals(SPEED_MAX_DB, speedBoostDb(300f, 10))
    }

    @Test fun `time alignment is nothing until it is on`() {
        assertTrue(xf.outputDelays().values.all { it == 0f })
        assertEquals(xf.feeds().keys, xf.outputDelays().keys)
    }
}
