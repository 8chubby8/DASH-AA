package com.dash.android.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.weather.BackgroundState
import com.dash.android.weather.Precip
import com.dash.android.weather.TimeOfDay
import com.dash.android.weather.WeatherArt
import com.dash.android.weather.WeatherSnapshot
import com.dash.android.weather.cloudLevel
import com.dash.android.weather.label
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The layered weather scene that fills the settings-panel landing (roadmap 1.5.4). Back-to-front:
 * skybox (time only), the sun/moon, drifting clouds, the graded background scene, foreground
 * particles, a soft fade, then the text readout. Each visual layer draws from user/bundled art via
 * [WeatherArt] if a file is present, and falls back to a procedural render if not — so the scene is
 * complete with no art shipped, and every layer upgrades independently as art arrives.
 *
 * The vehicle silhouette and live-car interaction are intentionally absent — they are version 2.
 *
 * "Frozen clock, living air": the snapshot is fixed for the session (chosen when the landing opens),
 * but the clouds still drift and the particles still fall while it is on screen. The frame loop runs
 * only while composed, so it costs nothing once the settings panel closes.
 */
@Composable
fun WeatherScene(
    snapshot: WeatherSnapshot,
    art: WeatherArt,
    font: FontFamily,
    modifier: Modifier = Modifier,
) {
    // One elapsed-seconds clock feeding every animated layer. withFrameNanos only ticks while
    // composed, so this is self-pausing.
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeSec = (now - start) / 1_000_000_000f }
        }
    }

    // The wall-clock readout ticks on its own slow timer, independent of the frame loop.
    var clock by remember { mutableStateOf(clockString()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockString()
            delay(10_000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SkyLayer(snapshot.timeOfDay, art)
        SunLayer(snapshot.timeOfDay, snapshot.cloudCoverPercent)
        CloudsLayer(snapshot, art, timeSec)
        BackgroundLayer(snapshot, art)
        ParticlesLayer(snapshot, timeSec)
        // A soft fade rising from the bottom keeps the readout legible over any sky — a gradient, not
        // a box, so the artist's picture is never framed (module is king).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
        )
        WeatherReadout(
            snapshot, clock, font,
            Modifier.align(Alignment.BottomStart).padding(24.dp),
        )
    }
}

// ── Layer 1: skybox (time only) ──────────────────────────────────────────────────────────────────
@Composable
private fun SkyLayer(time: TimeOfDay, art: WeatherArt) {
    val image = remember(time) { art.sky(time) }
    if (image != null) {
        FillImage(image)
    } else {
        val (top, bottom) = proceduralSky(time)
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
        }
    }
}

// ── Sun / moon: rides the right third, height set by time of day ─────────────────────────────────
@Composable
private fun SunLayer(time: TimeOfDay, cloudCoverPercent: Int) {
    // Heavy cloud hides the sun behind the cloud layer, so don't bother drawing it then.
    if (cloudLevel(cloudCoverPercent) >= 5) return
    val body = sunBody(time) ?: return
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width * 0.75f          // the right third — fixed, so clouds light consistently
        val cy = size.height * body.height
        val r = size.minDimension * if (body.moon) 0.05f else 0.075f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(body.colour.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r * 4.5f,
            ),
            radius = r * 4.5f,
            center = Offset(cx, cy),
        )
        drawCircle(body.colour, r, Offset(cx, cy))
    }
}

// ── Layer 2: clouds, scrolling at wind speed ────────────────────────────────────────────────────
@Composable
private fun CloudsLayer(snapshot: WeatherSnapshot, art: WeatherArt, timeSec: Float) {
    val level = cloudLevel(snapshot.cloudCoverPercent)
    if (level == 0) return

    val image = remember(level) { art.clouds(level) }
    // Scroll speed from wind, scaled for visibility.
    val scrollPxPerSec = (snapshot.windKph.toFloat() * 3f).coerceIn(4f, 90f)
    val tint = cloudTint(snapshot.timeOfDay)

    Canvas(Modifier.fillMaxSize()) {
        val shift = (timeSec * scrollPxPerSec) % size.width
        if (image != null) {
            // Two copies of the art side by side so the scroll wraps seamlessly.
            val w = size.width
            val h = size.height * 0.6f
            drawImageTiled(image, -shift, 0f, w, h)
            drawImageTiled(image, w - shift, 0f, w, h)
        } else {
            // Procedural: a coverage wash greys the upper sky for the heavy levels, plus soft blobs
            // drifting across. Both scale with the level, so level 7 reads as full overcast.
            val washAlpha = (level / 7f) * 0.55f
            drawRect(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = washAlpha),
                    0.7f to tint.copy(alpha = washAlpha * 0.35f),
                    1f to Color.Transparent,
                )
            )
            val blobs = 2 + level
            val span = size.width + 300f
            for (i in 0 until blobs) {
                val baseX = size.width * (i / blobs.toFloat())
                val x = ((baseX - shift) % span + span) % span - 150f
                val y = size.height * (0.12f + 0.07f * sin(i.toFloat()))
                drawCloudBlob(x, y, size.minDimension * (0.18f + 0.05f * (i % 2)), tint.copy(alpha = 0.5f))
            }
        }
    }
}

// ── Layer 3: background scene, three graded states ──────────────────────────────────────────────
@Composable
private fun BackgroundLayer(snapshot: WeatherSnapshot, art: WeatherArt) {
    val state = snapshot.backgroundState()
    val image = remember(state) { art.background(state) }
    if (image != null) {
        FillImage(image)
        return
    }
    // Procedural placeholder: two layered hill silhouettes, coloured for the state. NIGHT carries a
    // few warm "window" dots to hint at the lit content real night art would hold.
    val (_, skyBottom) = proceduralSky(snapshot.timeOfDay)
    val hillBack: Color
    val hillFront: Color
    val lit: Boolean
    when (state) {
        BackgroundState.DAY -> {
            hillBack = lerp(skyBottom, Color.Black, 0.55f)
            hillFront = lerp(skyBottom, Color.Black, 0.72f)
            lit = false
        }
        BackgroundState.NIGHT -> {
            hillBack = Color(0xFF0E1420)
            hillFront = Color(0xFF070B12)
            lit = true
        }
        BackgroundState.SNOW -> {
            hillBack = Color(0xFFD7E0E8)
            hillFront = Color(0xFFBCC7D1)
            lit = false
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height
        val w = size.width
        val back = Path().apply {
            moveTo(0f, h * 0.72f)
            cubicTo(w * 0.25f, h * 0.66f, w * 0.55f, h * 0.80f, w, h * 0.70f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(back, hillBack)
        val front = Path().apply {
            moveTo(0f, h * 0.84f)
            cubicTo(w * 0.30f, h * 0.78f, w * 0.70f, h * 0.90f, w, h * 0.82f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(front, hillFront)
        if (lit) {
            val warm = Color(0xFFFFD98A)
            for (i in 0 until 7) {
                val x = w * (0.08f + 0.13f * i)
                val y = h * (0.80f + 0.015f * sin(i * 2f))
                drawCircle(warm.copy(alpha = 0.85f), size.minDimension * 0.006f, Offset(x, y))
            }
        }
    }
}

// ── Layer 4: foreground particles (always code) ─────────────────────────────────────────────────
@Composable
private fun ParticlesLayer(snapshot: WeatherSnapshot, timeSec: Float) {
    if (snapshot.precipitation == Precip.NONE) return
    val snow = snapshot.precipitation == Precip.SNOW
    val count = if (snow) 90 else 140
    val particles = remember(count) {
        val rng = Random(count)
        List(count) {
            Particle(
                xSeed = rng.nextFloat(),
                ySeed = rng.nextFloat(),
                speed = if (snow) rng.nextFloat() * 0.15f + 0.08f else rng.nextFloat() * 0.5f + 0.7f,
                size = if (snow) rng.nextFloat() * 4f + 2f else rng.nextFloat() * 1.4f + 0.8f,
            )
        }
    }
    // Rain rakes with the wind; snow drifts gently. windFromDegrees 270 (westerly) rakes rightward.
    val rake = ((snapshot.windFromDegrees - 180) / 180.0).toFloat().coerceIn(-1f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        for (p in particles) {
            val progress = (timeSec * p.speed + p.ySeed) % 1f
            val y = progress * size.height
            if (snow) {
                val sway = sin((timeSec + p.xSeed * 10f) * 1.5f) * 12f
                val x = p.xSeed * size.width + sway
                drawCircle(Color.White.copy(alpha = 0.85f), p.size, Offset(x, y))
            } else {
                val len = 14f + p.speed * 10f
                val dx = rake * len * 0.5f
                val x = p.xSeed * size.width + dx * progress
                drawLine(
                    Color(0xFFB9D3E8).copy(alpha = 0.55f),
                    Offset(x, y), Offset(x + dx, y + len),
                    strokeWidth = p.size, cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ── The text readout: left-aligned cluster in the bottom-left, over the soft fade ────────────────
@Composable
private fun WeatherReadout(
    snapshot: WeatherSnapshot,
    clock: String,
    font: FontFamily,
    modifier: Modifier,
) {
    // The readout is DASH chrome, not art, so it wears DASH's typography: the font token is passed in,
    // and every size is sp so it rides the DASH text-size stepper (dashTextScale) automatically.
    val uriHandler = LocalUriHandler.current
    val shadow = androidx.compose.ui.graphics.Shadow(
        color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 8f,
    )
    val tempStr = snapshot.temperatureC?.let { "${Math.round(it)}°" } ?: "—"
    val locationText = snapshot.locationName ?: if (!snapshot.live) "Offline" else null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = clock,
            style = TextStyle(shadow = shadow),
            color = Color.White, fontFamily = font, fontSize = 40.sp, fontWeight = FontWeight.Light,
        )
        Text(
            text = "$tempStr   ${snapshot.condition.label()}",
            style = TextStyle(shadow = shadow),
            color = Color.White, fontFamily = font, fontSize = 20.sp,
        )
        if (locationText != null) {
            Text(
                text = locationText,
                style = TextStyle(shadow = shadow),
                color = Color.White.copy(alpha = 0.9f), fontFamily = font, fontSize = 14.sp,
            )
        }
        // Open-Meteo's free tier is CC-BY 4.0 — attribution is required, and doubles as a link out.
        Text(
            text = "Weather data by Open-Meteo →",
            style = TextStyle(shadow = shadow),
            color = Color.White.copy(alpha = 0.65f), fontFamily = font, fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable { uriHandler.openUri("https://open-meteo.com") },
        )
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────────────────────────
private data class Particle(val xSeed: Float, val ySeed: Float, val speed: Float, val size: Float)

/** The sun (or moon) for a time of day: its height in the frame, colour, and whether it's the moon. */
private data class SkyBody(val height: Float, val colour: Color, val moon: Boolean)

private fun sunBody(time: TimeOfDay): SkyBody? = when (time) {
    TimeOfDay.DAWN -> SkyBody(0.62f, Color(0xFFFFE1AC), false)
    TimeOfDay.SUNRISE -> SkyBody(0.50f, Color(0xFFFFE0A0), false)
    TimeOfDay.MORNING -> SkyBody(0.34f, Color(0xFFFFF4D6), false)
    TimeOfDay.MIDDAY -> SkyBody(0.18f, Color(0xFFFFF8E4), false)
    TimeOfDay.AFTERNOON -> SkyBody(0.32f, Color(0xFFFFF4D6), false)
    TimeOfDay.EVENING -> SkyBody(0.46f, Color(0xFFFFD9A0), false)
    TimeOfDay.SUNSET -> SkyBody(0.58f, Color(0xFFFFC187), false)
    TimeOfDay.DUSK -> null // twilight, sun gone
    TimeOfDay.NIGHT -> SkyBody(0.22f, Color(0xFFEFF3FA), true)
}

@Composable
private fun FillImage(image: ImageBitmap, alpha: Float = 1f) {
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alpha = alpha,
    )
}

/** The procedural sky palette: a time-of-day base gradient. Condition is *not* mixed in here — that
 *  is the cloud and background layers' job now. */
private fun proceduralSky(time: TimeOfDay): Pair<Color, Color> = when (time) {
    TimeOfDay.DAWN -> Color(0xFF2E3866) to Color(0xFFF4B07A)
    TimeOfDay.SUNRISE -> Color(0xFF4A5A8C) to Color(0xFFFFC98A)
    TimeOfDay.MORNING -> Color(0xFF3E86C9) to Color(0xFFCFE8F7)
    TimeOfDay.MIDDAY -> Color(0xFF2F7DC9) to Color(0xFFBFE3F7)
    TimeOfDay.AFTERNOON -> Color(0xFF3E86C9) to Color(0xFFDCEAF2)
    TimeOfDay.EVENING -> Color(0xFF3A5590) to Color(0xFFF0C892)
    TimeOfDay.SUNSET -> Color(0xFF1E2650) to Color(0xFFE86A38)
    TimeOfDay.DUSK -> Color(0xFF141A38) to Color(0xFFB5654E)
    TimeOfDay.NIGHT -> Color(0xFF05070F) to Color(0xFF16203E)
}

/** How the clouds are tinted for the hour — warm at the ends of the day, cool at noon, dark at night. */
private fun cloudTint(time: TimeOfDay): Color = when (time) {
    TimeOfDay.DAWN, TimeOfDay.SUNRISE -> Color(0xFFF3D9C0)
    TimeOfDay.SUNSET -> Color(0xFFE8C4A0)
    TimeOfDay.DUSK -> Color(0xFF9AA0AD)
    TimeOfDay.NIGHT -> Color(0xFF6A7180)
    else -> Color(0xFFFFFFFF)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudBlob(
    cx: Float, cy: Float, r: Float, tint: Color,
) {
    drawCircle(tint, r, Offset(cx, cy))
    drawCircle(tint, r * 0.75f, Offset(cx + r * 0.7f, cy + r * 0.15f))
    drawCircle(tint, r * 0.7f, Offset(cx - r * 0.7f, cy + r * 0.2f))
    drawCircle(tint, r * 0.6f, Offset(cx + r * 0.2f, cy - r * 0.35f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageTiled(
    image: ImageBitmap, x: Float, y: Float, w: Float, h: Float,
) {
    drawImage(
        image = image,
        dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
    )
}

private fun clockString(): String {
    val c = Calendar.getInstance()
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
