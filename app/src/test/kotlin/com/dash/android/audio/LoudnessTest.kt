package com.dash.android.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loudness: the ISO 226 contours, the correction drawn from them, and how closely the filters follow it. */
class LoudnessTest {
    private fun at(hz: Double) = Loudness.FREQUENCIES.indexOfFirst { it == hz }

    @Test fun `the contours match the standard's published values`() {
        // At 1 kHz a phon is a decibel, by definition.
        for (phon in listOf(20.0, 40.0, 60.0, 80.0)) assertEquals(phon, Loudness.spl(at(1000.0), phon), 0.3)
        // ISO 226:2003's 40-phon contour.
        assertEquals(99.85, Loudness.spl(at(20.0), 40.0), 0.05)
        assertEquals(64.37, Loudness.spl(at(100.0), 40.0), 0.05)
    }

    @Test fun `the correction is nothing at the comfortable volume and grows as it comes down`() {
        assertTrue(Loudness.target(0.0).all { abs(it) < 1e-9 })
        val bass = at(31.5)
        var last = 0.0
        for (below in 5..60 step 5) {
            val c = Loudness.target(below.toDouble())[bass]
            assertTrue(c > last, "at $below dB down, 31.5 Hz should need more than at ${below - 5}")
            last = c
        }
        assertEquals(0.0, Loudness.target(30.0)[at(1000.0)], 0.3)
    }

    @Test fun `no frequency ever plays louder than at the comfortable volume`() {
        for (below in 1..60) {
            val t = Loudness.target(below.toDouble())
            assertTrue(t.all { it < below }, "at $below dB down the correction must stay below the turn-down")
        }
    }

    @Test fun `the filters follow the curve to within about half a decibel, at every volume and every level`() {
        var worst = 0.0
        for (level in 1..Loudness.LEVELS) for (tenth in 0..600 step 5) {
            val below = tenth / 10.0
            val g = Loudness.gains(below, level)
            val want = Loudness.target(below)
            Loudness.FREQUENCIES.forEachIndexed { i, f ->
                val err = abs(Loudness.response(g, f) - want[i] * level / Loudness.LEVELS)
                worst = maxOf(worst, err)
            }
        }
        println("loudness fit: worst error ${"%.2f".format(worst)} dB")
        assertTrue(worst < 0.6, "worst error $worst dB")
    }

    @Test fun `off, or at the comfortable volume, is flat`() {
        assertTrue(Loudness.gains(30.0, 0).all { it == 0f })
        assertTrue(Loudness.gains(0.0, 4).all { it == 0f })
        assertTrue(Loudness.gains(-6.0, 4).all { it == 0f })
    }

    @Test fun `print the curve`() {
        for (below in listOf(10.0, 20.0, 30.0, 40.0)) {
            val t = Loudness.target(below)
            val g = Loudness.gains(below, 4)
            println("$below dB down: " + Loudness.FREQUENCIES.indices.joinToString(" ") { i ->
                "${Loudness.FREQUENCIES[i].toInt()}:${"%.1f".format(t[i])}/${"%.1f".format(Loudness.response(g, Loudness.FREQUENCIES[i]))}"
            })
            println("   gains " + g.joinToString(" ") { "%.1f".format(it) })
        }
    }
}
