package com.dash.android.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Loudness** (DASH-AA 1.1.4, Roger 2026-10-08: "it's got to be good") — the ear's own correction for
 * listening quietly, worked out from the equal-loudness contours of ISO 226:2003.
 *
 * The ear loses the low notes (and, a little, the very top) faster than the middle as sound gets quieter.
 * Music is mixed loud, so turned down it sounds thin. The user sets a **comfortable volume**, where the
 * music sounds full and right; that is taken as the level it was mixed at ([REFERENCE_PHON]). Below it,
 * the correction at each frequency is exactly what the contours say the ear is now missing:
 *
 *     correction(f) = [ SPL(f, reference − below) − SPL(f, reference) ] + below
 *
 * — the level that frequency needs to sound as loud, relative to the middle, as it did at the comfortable
 * volume. It is nothing at 1 kHz, nothing at or above the comfortable volume, and grows smoothly as the
 * volume comes down. Because the contours bunch together in the bass, the correction is always smaller
 * than the turn-down, so **no frequency ever plays louder than it did at the comfortable volume**: loudness
 * needs no headroom, and asks nothing of a speaker that it was not already doing.
 *
 * [level] 1–4 gives a quarter, a half, three quarters or all of the correction, for music mastered hotter
 * or quieter and for taste. The correction is made by [BANK], a fixed set of shelving and peaking filters
 * whose gains are fitted to the curve by least squares — so a processor changes only gains as the volume
 * moves, never the shape of what it runs. A sound module may do it its own way (its own curves, its own
 * microphones); this is DASH's.
 *
 * Shared code: nothing here knows about PipeWire. A processor says how many dB below the comfortable
 * volume it is playing, and gets back the gains.
 */
object Loudness {
    const val LEVELS = 4
    /** The loudness music is taken to be mixed at, in phon — about where a mixing room sits. */
    const val REFERENCE_PHON = 80.0
    /** The quietest the contours are used for: 60 dB below the reference, 20 phon, the standard's floor. */
    const val MAX_BELOW_DB = 60.0
    /** The filters are designed at the usual graph rate; below 10 kHz the rate makes no difference to speak of. */
    const val SAMPLE_RATE = 48_000.0

    enum class Kind { LOW_SHELF, PEAKING, HIGH_SHELF }

    /** One filter of the bank: its kind, its frequency and its Q. Only its gain moves. */
    data class Band(val kind: Kind, val freq: Double, val q: Double)

    /**
     * Shelves an octave apart through the bass, where the correction lives; two bells for the shallow dip
     * where the ear is most sensitive, 1–6 kHz; a shelf for the top. Q 0.7071 is the shelf's natural slope,
     * read the same by every biquad implementation. Fitted, it follows the full correction to within about
     * half a decibel from 20 Hz to 12.5 kHz at every volume and level (LoudnessTest).
     */
    val BANK = listOf(
        Band(Kind.LOW_SHELF, 25.0, SHELF_Q),
        Band(Kind.LOW_SHELF, 50.0, SHELF_Q),
        Band(Kind.LOW_SHELF, 100.0, SHELF_Q),
        Band(Kind.LOW_SHELF, 200.0, SHELF_Q),
        Band(Kind.LOW_SHELF, 400.0, SHELF_Q),
        Band(Kind.LOW_SHELF, 800.0, SHELF_Q),
        Band(Kind.PEAKING, 2000.0, 1.0),
        Band(Kind.PEAKING, 5000.0, 1.0),
        Band(Kind.HIGH_SHELF, 12_000.0, SHELF_Q),
    )

    private const val SHELF_Q = 0.7071

    // ---- ISO 226:2003, table 1 -------------------------------------------------------------------------

    /** The standard's frequencies, a third of an octave apart, 20 Hz to 12.5 kHz. */
    val FREQUENCIES = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
        630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
    )
    /** αf, the exponent for loudness perception. */
    private val AF = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267,
        0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301,
    )
    /** LU, the magnitude of the linear transfer function normalised at 1 kHz, dB. */
    private val LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0,
        0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1,
    )
    /** Tf, the threshold of hearing, dB. */
    private val TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4,
        3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )

    /** The sound pressure level, dB, at which [FREQUENCIES]`[i]` sounds as loud as [phon] (ISO 226:2003 §4.1). */
    fun spl(i: Int, phon: Double): Double {
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) + (0.4 * 10.0.pow((TF[i] + LU[i]) / 10 - 9)).pow(AF[i])
        return 10 / AF[i] * log10(af) - LU[i] + 94
    }

    /** The full correction at each of [FREQUENCIES], dB, when playing [below] dB under the comfortable volume. */
    fun target(below: Double): DoubleArray {
        val b = below.coerceIn(0.0, MAX_BELOW_DB)
        return DoubleArray(FREQUENCIES.size) { i -> spl(i, REFERENCE_PHON - b) - spl(i, REFERENCE_PHON) + b }
    }

    // ---- The fit ---------------------------------------------------------------------------------------

    /** Where the fit is judged: the standard's own frequencies. */
    private val FIT_FREQUENCIES = FREQUENCIES

    private val cache = object : LinkedHashMap<Long, List<Float>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Float>>) = size > 256
    }

    /**
     * The gain of each of [BANK]'s filters, dB, for [level] (0 off, 1–[LEVELS]) when playing [below] dB
     * under the comfortable volume. All zero when off or at the comfortable volume and above. Worked out to
     * a twentieth of a dB of turn-down, and remembered, so a volume held moving costs nothing.
     */
    fun gains(below: Double, level: Int): List<Float> {
        val b = below.coerceIn(0.0, MAX_BELOW_DB)
        if (level <= 0 || b < 0.05) return List(BANK.size) { 0f }
        val steps = (b * 20).toLong()
        val key = steps * 8 + level.coerceAtMost(LEVELS)
        synchronized(cache) { cache[key] }?.let { return it }
        val strength = level.coerceAtMost(LEVELS).toDouble() / LEVELS
        val t = target(steps / 20.0)
        val wanted = DoubleArray(FIT_FREQUENCIES.size) { i -> strength * t[i] }
        val fitted = fit(wanted).map { it.toFloat() }
        synchronized(cache) { cache[key] = fitted }
        return fitted
    }

    /**
     * Least squares, Gauss–Newton: start from flat and move every gain at once toward the curve, a little
     * damped so neighbouring shelves never fight each other with large opposite gains.
     */
    private fun fit(wanted: DoubleArray): DoubleArray {
        val n = BANK.size
        val g = DoubleArray(n)
        val damping = 1e-3
        repeat(12) {
            val r = DoubleArray(wanted.size) { i -> response(g, FIT_FREQUENCIES[i]) - wanted[i] }
            // The Jacobian, numerically: how the response at each frequency moves with each gain.
            val j = Array(wanted.size) { DoubleArray(n) }
            for (k in 0 until n) {
                val saved = g[k]
                g[k] = saved + 0.01
                for (i in wanted.indices) j[i][k] = (response(g, FIT_FREQUENCIES[i]) - wanted[i] - r[i]) / 0.01
                g[k] = saved
            }
            val a = Array(n) { p -> DoubleArray(n) { q -> wanted.indices.sumOf { j[it][p] * j[it][q] } + if (p == q) damping else 0.0 } }
            val rhs = DoubleArray(n) { p -> -wanted.indices.sumOf { j[it][p] * r[it] } }
            val step = solve(a, rhs)
            for (k in 0 until n) g[k] += step[k]
        }
        return g
    }

    /** Gaussian elimination with partial pivoting — [a] is small and well conditioned. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        for (c in 0 until n) {
            val p = (c until n).maxBy { kotlin.math.abs(m[it][c]) }
            m[c] = m[p].also { m[p] = m[c] }
            x[c] = x[p].also { x[p] = x[c] }
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
                x[r] -= f * x[c]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = x[r]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }

    // ---- The filters, as a biquad plays them -----------------------------------------------------------

    /** What [BANK] with [gains] does at [freq], dB. */
    fun response(gains: DoubleArray, freq: Double): Double =
        BANK.indices.sumOf { k -> bandResponse(BANK[k], gains[k], freq) }

    fun response(gains: List<Float>, freq: Double): Double = response(DoubleArray(gains.size) { gains[it].toDouble() }, freq)

    /** One filter's response at [freq], dB — the RBJ cookbook filters, as PipeWire's `bq_*` builtins make them. */
    private fun bandResponse(band: Band, gainDb: Double, freq: Double): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * band.freq / SAMPLE_RATE
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * band.q)
        val sa = 2 * sqrt(a) * alpha
        val (b, d) = when (band.kind) {
            Kind.PEAKING -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            Kind.LOW_SHELF -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
            ) to doubleArrayOf((a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa)
            Kind.HIGH_SHELF -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
            ) to doubleArrayOf((a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa)
        }
        val w = 2 * PI * freq / SAMPLE_RATE
        fun mag(c: DoubleArray): Double {
            val re = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val im = -c[1] * sin(w) - c[2] * sin(2 * w)
            return hypot(re, im)
        }
        return 20 * log10(mag(b) / mag(d))
    }
}
