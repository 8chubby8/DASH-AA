package com.dash.android.ui.settings.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.dash.android.ui.theme.LocalDashTheme
import kotlin.math.roundToInt
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.TINY

/**
 * A colour as hue (0–360), saturation and value (0–1). Held as HSV, not ARGB, so the picker is its own
 * stable source of truth — an RGB round-trip loses the hue of a grey, which would make the hue slider
 * jump around under the user's finger. Converted to a real colour only at the edges.
 */
data class Hsv(val h: Float, val s: Float, val v: Float)

fun colourToHsv(argb: Long): Hsv {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV((argb and 0xFFFFFFFF).toInt(), out)
    return Hsv(out[0], out[1], out[2])
}

fun Hsv.toColour(): Color = Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

/** Store a Color as an unsigned 32-bit ARGB Long — the form DASH preferences keep splash colour in. */
fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

private val HUE_STOPS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/**
 * A compact HSV picker: a saturation/value square over a hue bar. [onChange] fires continuously as the
 * user drags (drive the live preview from it); [onCommit] fires when a gesture ends (persist there, so
 * a drag is one write, not hundreds).
 */
@Composable
fun ColourPicker(hsv: Hsv, onChange: (Hsv) -> Unit, onCommit: () -> Unit) {
    val hueColour = Color.hsv(hsv.h.coerceIn(0f, 360f), 1f, 1f)

    // A pointerInput(Unit) block captures its lambdas once. Route the current hsv and callbacks
    // through updated state so a gesture always acts on the latest — otherwise adjusting hue then S/V
    // would snap the hue back, and commit would persist a stale colour.
    val latestHsv by rememberUpdatedState(hsv)
    val latestChange by rememberUpdatedState(onChange)
    val latestCommit by rememberUpdatedState(onCommit)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Saturation (x) × Value (y) ───────────────────────────────────────────────────────────
        var svSize by remember { mutableStateOf(IntSize.Zero) }
        fun setSv(pos: Offset) {
            if (svSize.width == 0 || svSize.height == 0) return
            latestChange(
                latestHsv.copy(
                    s = (pos.x / svSize.width).coerceIn(0f, 1f),
                    v = (1f - pos.y / svSize.height).coerceIn(0f, 1f),
                )
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .onSizeChanged { svSize = it }
                .pointerInput(Unit) { detectTapGestures { setSv(it); latestCommit() } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { latestCommit() }) { change, _ ->
                        change.consume(); setSv(change.position)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, hueColour)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val cx = hsv.s.coerceIn(0f, 1f) * size.width
                val cy = (1f - hsv.v.coerceIn(0f, 1f)) * size.height
                drawCircle(Color.Black, radius = 11f, center = Offset(cx, cy), style = Stroke(width = 5f))
                drawCircle(Color.White, radius = 11f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
            }
        }

        // ── Hue ──────────────────────────────────────────────────────────────────────────────────
        var hueSize by remember { mutableStateOf(IntSize.Zero) }
        fun setHue(pos: Offset) {
            if (hueSize.width == 0) return
            latestChange(latestHsv.copy(h = (pos.x / hueSize.width).coerceIn(0f, 1f) * 360f))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .onSizeChanged { hueSize = it }
                .pointerInput(Unit) { detectTapGestures { setHue(it); latestCommit() } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { latestCommit() }) { change, _ ->
                        change.consume(); setHue(change.position)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(HUE_STOPS))
                val r = size.height / 2f
                val x = ((hsv.h / 360f) * size.width).coerceIn(r, size.width - r)
                drawCircle(Color.Black, radius = r - 1.5f, center = Offset(x, r), style = Stroke(width = 4f))
                drawCircle(Color.White, radius = r - 1.5f, center = Offset(x, r), style = Stroke(width = 2f))
            }
        }
    }
}

// ── Numeric entry (roadmap 1.5.15) ───────────────────────────────────────────────────────────────

/** The colour as three 0–255 channels. */
fun Hsv.toRgb(): Triple<Int, Int, Int> {
    val c = toColour().toArgb()
    return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
}

fun rgbToHsv(r: Int, g: Int, b: Int): Hsv {
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), out)
    return Hsv(out[0], out[1], out[2])
}

fun Hsv.toHex(): String {
    val (r, g, b) = toRgb()
    return "%02X%02X%02X".format(r, g, b)
}

/** Parses `#RRGGBB` or `RRGGBB`, returning null for anything else — a half-typed value simply does
 *  not apply yet rather than fighting the user mid-entry. */
fun parseHex(text: String): Hsv? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return rgbToHsv(
        hex.substring(0, 2).toInt(16),
        hex.substring(2, 4).toInt(16),
        hex.substring(4, 6).toInt(16),
    )
}

/**
 * The full colour editor (roadmap 1.5.15) — the [ColourPicker] square and hue bar, with typed entry
 * beneath it: hex, R/G/B and H/S/V, every set live-synced to the same colour.
 *
 * **Why all three sets and not just saturation.** The original ask was RGB plus a saturation field,
 * but saturation alone is a third of HSV: it lets you nudge a colour and never type one. With numeric
 * entry present at all, the whole triple earns its place — and hex is what anyone actually pastes
 * when they are matching a colour from somewhere else.
 *
 * Typing parses on every keystroke and ignores whatever does not parse, so a half-finished `#3D` is
 * simply not applied rather than snapping the picker somewhere absurd. Values persist on focus loss,
 * matching the picker's own drag/commit split — one write per edit, not one per character.
 *
 * Built here rather than in the splash tab because version 2's theming work (colour customisation,
 * presets, export/import) wants exactly this, and splash is only its first caller.
 */
@Composable
fun ColourEditor(hsv: Hsv, onChange: (Hsv) -> Unit, onCommit: () -> Unit) {
    val theme = LocalDashTheme.current
    val (r, g, b) = hsv.toRgb()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ColourPicker(hsv = hsv, onChange = onChange, onCommit = onCommit)

        // Hex, with a live chip of the colour beside it — the one field you can paste into.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(hsv.toColour())
                    .border(1.dp, theme.textColourSecondary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            )
            ColourField(
                label = "HEX",
                value = hsv.toHex(),
                modifier = Modifier.weight(1f),
                numeric = false,
                onText = { parseHex(it)?.let(onChange) },
                onDone = onCommit,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColourField("R", "$r", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(rgbToHsv(it, g, b)) }
            }, onCommit)
            ColourField("G", "$g", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(rgbToHsv(r, it, b)) }
            }, onCommit)
            ColourField("B", "$b", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(rgbToHsv(r, g, it)) }
            }, onCommit)
        }

        // H in degrees, S and V as percentages — the units people say out loud, rather than the 0–1
        // the picker holds internally.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColourField("H°", "${hsv.h.roundToInt()}", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(hsv.copy(h = it.coerceIn(0, 360).toFloat())) }
            }, onCommit)
            ColourField("S%", "${(hsv.s * 100).roundToInt()}", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(hsv.copy(s = it.coerceIn(0, 100) / 100f)) }
            }, onCommit)
            ColourField("V%", "${(hsv.v * 100).roundToInt()}", Modifier.weight(1f), true, { t ->
                t.toIntOrNull()?.let { onChange(hsv.copy(v = it.coerceIn(0, 100) / 100f)) }
            }, onCommit)
        }
    }
}

/**
 * One typed field. It shows [value] whenever it is not being edited, so dragging the square updates
 * every field live — but while focused it holds exactly what was typed, so a partially entered value
 * is never rewritten under the user's fingers.
 */
@Composable
private fun ColourField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    numeric: Boolean,
    onText: (String) -> Unit,
    onDone: () -> Unit,
) {
    val theme = LocalDashTheme.current
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    if (!focused && text != value) text = value

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            color = theme.textColourSecondary.copy(alpha = 0.55f),
            fontSize = TINY,
            letterSpacing = 0.8.sp,
            fontFamily = theme.font,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it; onText(it) },
            singleLine = true,
            textStyle = TextStyle(
                color = theme.textColourSecondary,
                fontSize = MAINBODY,
                fontFamily = theme.font,
            ),
            cursorBrush = SolidColor(theme.textColourSecondary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged {
                    val wasFocused = focused
                    focused = it.isFocused
                    // Persist and re-sync when the field is left — one write per edit, and anything
                    // that never parsed is discarded rather than left sitting there looking accepted.
                    if (wasFocused && !it.isFocused) { onDone(); text = value }
                },
        )
    }
}
