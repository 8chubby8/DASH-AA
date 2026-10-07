package com.dash.android.ui.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A big, plain, modern analogue clock (DASH-AA 1.1.1, Roger: "if no phone is connected then the
 * background is a simple modern style analogue clock"). What the viewport shows when Android Auto is not
 * projecting; the weather scene stays as the settings panel's landing.
 *
 * Deliberately plain: no numerals, sixty fine marks with the twelve hours heavier, three hands and a
 * centre. Every colour is a theme token, as all DASH chrome is, so a future theme changes it with
 * everything else — and the settings for how it looks come later (Roger: "we can add settings for what
 * it looks like later").
 *
 * The second hand steps once a second, so the clock redraws once a second and no more; the minute and
 * hour hands move on with it rather than jumping on the minute.
 *
 * **For native:** plain Compose with nothing of Linux in it — take as it is if native wants a clock.
 */
@Composable
fun AnalogueClock(modifier: Modifier = Modifier) {
    val theme = LocalDashTheme.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            // Wake just after the next whole second, so the hand steps in time with the real clock.
            delay(1000L - now.nano / 1_000_000 + 5)
        }
    }

    val face = theme.backgroundColourSecondary
    val ink = theme.textColourSecondary
    val second = theme.accentColourSecondary

    Box(modifier.background(face)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) * 0.42f
            if (r <= 0f) return@Canvas
            val c = center

            // The marks: sixty minutes, the twelve hours longer and heavier.
            for (i in 0 until 60) {
                val hour = i % 5 == 0
                val angle = i / 60.0 * 2 * PI
                val outer = r
                val inner = r * if (hour) 0.86f else 0.94f
                drawLine(
                    color = if (hour) ink else ink.copy(alpha = 0.45f),
                    start = c + polar(angle, inner),
                    end = c + polar(angle, outer),
                    strokeWidth = r * if (hour) 0.022f else 0.008f,
                    cap = StrokeCap.Butt,
                )
            }

            val s = now.second
            val m = now.minute + s / 60.0
            val h = now.hour % 12 + m / 60.0

            hand(c, h / 12.0, r * 0.52f, r * 0.045f, ink)
            hand(c, m / 60.0, r * 0.80f, r * 0.030f, ink)
            // The second hand runs a little past the centre, as on most modern faces.
            hand(c, s / 60.0, r * 0.88f, r * 0.010f, second, tail = r * 0.16f)

            drawCircle(ink, radius = r * 0.040f, center = c)
            drawCircle(second, radius = r * 0.018f, center = c)
        }
    }
}

/** A point [length] from the centre at [angle] radians, measured clockwise from twelve. */
private fun polar(angle: Double, length: Float) =
    Offset((sin(angle) * length).toFloat(), (-cos(angle) * length).toFloat())

/** One hand, pointing at [turn] of a full circle (0 = twelve), with an optional [tail] behind the centre. */
private fun DrawScope.hand(c: Offset, turn: Double, length: Float, width: Float, colour: Color, tail: Float = 0f) {
    val angle = turn * 2 * PI
    drawLine(
        color = colour,
        start = c - polar(angle, tail),
        end = c + polar(angle, length),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}
