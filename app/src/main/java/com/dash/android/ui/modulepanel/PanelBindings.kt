package com.dash.android.ui.modulepanel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.dash.android.panel.BindingTarget
import com.dash.android.panel.ColourRef
import com.dash.android.panel.PanelBinding
import com.dash.android.panel.PanelFont
import com.dash.android.panel.PanelLayout
import com.dash.android.panel.RevealBinding
import com.dash.android.panel.RotateBinding
import com.dash.android.panel.StyleBinding
import com.dash.android.panel.StyleEffect
import com.dash.android.panel.TextLayer
import com.dash.android.panel.TouchBinding
import com.dash.android.panel.TranslateBinding
import com.dash.android.ui.theme.DashTheme
import java.util.Locale

/**
 * Every binding in a layout, reduced to what each target looks like right now (roadmap 1.6.6).
 *
 * Keyed by the target's wire form — `"needle"` for a layer, `"dial#needle"` for an element inside a
 * vector layer.
 *
 * **Two or more bindings may point at the same target, and that is the normal case** (§1), so
 * results are *merged* rather than replacing one another. An airflow arrow that is both pressable
 * and colour-coded is one target wearing two bindings, and an author who assumed one binding per
 * target would have designed around a limit that does not exist.
 */
@Composable
fun rememberDynamics(
    layout: PanelLayout,
    values: Map<String, String>,
    theme: DashTheme,
    pressed: Int? = null,
): Map<String, Dynamic> {
    val merged = mutableMapOf<String, Dynamic>()
    layout.bindings.forEachIndexed { index, binding ->
        val named = binding.target as? BindingTarget.Named ?: return@forEachIndexed
        // Keyed by position so each binding keeps its own animation state across recompositions.
        // The list is fixed for the life of a document, so the structure never shifts underneath.
        val dynamic = key(index) { dynamicFor(binding, values, theme, index == pressed) }
            ?: return@forEachIndexed
        val at = named.toString()
        merged[at] = merged[at]?.mergedWith(dynamic) ?: dynamic
    }
    return merged
}

/**
 * One binding's contribution, with its ease applied.
 *
 * **When a bound variable never arrives, the binding does nothing** (§8) — a typo in the layout, or
 * a variable dropped by a later firmware revision, leaves its target with the default appearance it
 * was drawn with. Never an error, never a blank panel, and never a value invented to stand in for
 * the missing one: treating an absent reading as zero would park a needle at empty and look exactly
 * like a real measurement.
 */
@Composable
private fun dynamicFor(
    binding: PanelBinding,
    values: Map<String, String>,
    theme: DashTheme,
    pressed: Boolean,
): Dynamic? {
    val raw = binding.variable?.let { values[it] }
    if (binding.variable != null && raw == null) return null

    return when (binding) {
        is RotateBinding -> {
            val fraction = fractionOf(raw, binding.from) ?: return null
            val angle by animateFloatAsState(
                targetValue = lerp(binding.to.start, binding.to.endInclusive, fraction),
                animationSpec = tween(binding.ease),
                label = "rotate",
            )
            Dynamic(rotate = angle, pivot = binding.pivot)
        }

        is RevealBinding -> {
            val fraction = fractionOf(raw, binding.from) ?: return null
            val reveal by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(binding.ease),
                label = "reveal",
            )
            Dynamic(reveal = reveal, revealDirection = binding.direction.wire)
        }

        is TranslateBinding -> {
            val fraction = fractionOf(raw, binding.from) ?: return null
            val x by animateFloatAsState(
                targetValue = lerp(binding.start.x, binding.end.x, fraction),
                animationSpec = tween(binding.ease), label = "translateX",
            )
            val y by animateFloatAsState(
                targetValue = lerp(binding.start.y, binding.end.y, fraction),
                animationSpec = tween(binding.ease), label = "translateY",
            )
            Dynamic(translate = Offset(x, y))
        }

        is StyleBinding -> {
            // First match wins (§9). With no variable there are no cases and the binding's own
            // properties are the effect — that is the static restyle a theme token is delegated by.
            val effect = binding.cases.firstOrNull { raw != null && it.match.matches(raw) }?.effect
                ?: binding.default
            styleDynamic(effect, binding.ease, theme, binding.pulsePeriod())
        }

        // Press feedback (§4.2, roadmap 1.6.7) — **momentary, and about the interaction**, which is
        // what distinguishes it from `style`: a `style` binding says what the module's state *is*,
        // this says only that a finger is on the control right now. It is local and immediate, and
        // nothing about it involves the module or waits for it.
        //
        // The effect is built whether or not the control is pressed, with the press choosing only
        // its target — the same discipline the style animations follow above. Building it on press
        // would make the release snap back while the press eased in, so a control with a 200 ms
        // fade would light gently and go out like a switch.
        is TouchBinding -> binding.feedback?.let { feedback ->
            styleDynamic(if (pressed) feedback else StyleEffect(), binding.ease, theme, null)
        }
    }
}

/**
 * A style effect as a [Dynamic], including §5's pulse.
 *
 * **Every animation here is created unconditionally**, driven by whether the *binding* could ever
 * want it rather than by whether the value happens to want it this frame. A style binding is
 * precisely the thing whose appearance changes as a reading crosses a threshold, so making the
 * animations come and go with the cases would rebuild them at exactly the moment they matter — a
 * warning lamp restarting its own clock the instant it starts warning. [pulsePeriod] is a fixed
 * property of the layout for the same reason; a case with no pulse simply does not apply it.
 */
@Composable
private fun styleDynamic(effect: StyleEffect, ease: Int, theme: DashTheme, pulsePeriod: Int?): Dynamic {
    val colour = effect.colour?.resolve(theme)
    // Transparent stands in while no case is asking for a colour, so the animation keeps its slot.
    // Its value is never read in that state — a null colour means "keep whatever the artwork has",
    // and there is nothing to fade towards.
    val animatedColour by animateColorAsState(colour ?: Color.Transparent, tween(ease), label = "styleColour")

    val animatedOpacity by animateFloatAsState(effect.opacity ?: 1f, tween(ease), label = "styleOpacity")

    val pulse = if (pulsePeriod != null) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val phase by transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_FLOOR,
            animationSpec = infiniteRepeatable(
                // Half the period each way, so one full cycle is the period the author asked for.
                animation = tween(pulsePeriod / 2),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulsePhase",
        )
        if (effect.pulse != null) phase else 1f
    } else {
        1f
    }

    return Dynamic(
        colour = if (colour == null) null else animatedColour,
        opacity = animatedOpacity * pulse,
    )
}

/**
 * The pulse period this binding declares anywhere, or null if it never pulses.
 *
 * One period per binding: an element that pulsed at two different speeds depending on its value
 * would be describing two states with the same mechanism, and the second one to be written would
 * silently restart the first. If that turns out to be wanted, it is a case for the lock at 1.6.10
 * rather than something to guess at now.
 */
private fun StyleBinding.pulsePeriod(): Int? =
    cases.firstNotNullOfOrNull { it.effect.pulse } ?: default.pulse

/**
 * Where [raw] sits in [range], as `0`–`1`.
 *
 * Clamped, so a module reporting beyond its own declared range pins the needle at the end of its
 * sweep rather than winding it past the stop — which is what a real gauge does, and what an author
 * who wrote `"from": [0, 11]` has already said they expect.
 */
private fun fractionOf(raw: String?, range: ClosedFloatingPointRange<Float>): Float? {
    val value = raw?.trim()?.toFloatOrNull() ?: return null
    val span = range.endInclusive - range.start
    if (span == 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

/** Later bindings fill in what earlier ones left alone; neither wins outright. */
private fun Dynamic.mergedWith(other: Dynamic) = Dynamic(
    rotate = if (other.rotate != 0f) other.rotate else rotate,
    pivot = other.pivot ?: pivot,
    translate = if (other.translate != Offset.Zero) other.translate else translate,
    colour = other.colour ?: colour,
    opacity = opacity * other.opacity,
    reveal = minOf(reveal, other.reveal),
    revealDirection = if (other.reveal < 1f) other.revealDirection else revealDirection,
)

/**
 * A token resolved against the theme **in the frame being drawn**, never flattened at install (§7).
 *
 * This is what makes a themed panel follow the user's colours with no module involvement. The
 * module issued its instruction once, at install — *"this one follows the system"* — and never
 * needs to be told the theme changed, because it already said what should happen.
 */
fun ColourRef.resolve(theme: DashTheme): Color? = when (this) {
    is ColourRef.Literal -> colour
    is ColourRef.Token -> when (name) {
        "backgroundColourPrimary" -> theme.backgroundColourPrimary
        "backgroundColourSecondary" -> theme.backgroundColourSecondary
        "textColourPrimary" -> theme.textColourPrimary
        "textColourSecondary" -> theme.textColourSecondary
        "iconColourPrimary" -> theme.iconColourPrimary
        "iconColourSecondary" -> theme.iconColourSecondary
        "accentColourPrimary" -> theme.accentColourPrimary
        "accentColourSecondary" -> theme.accentColourSecondary
        else -> null // `@font` is a typeface, not a colour; the reader has already said so.
    }
}

/**
 * The typeface a text layer asked for (§2).
 *
 * `sans-serif`, `serif` and `monospace` are guaranteed by the Android framework and always resolve.
 * Anything else falls back to the default — the browser rule, and necessary because what a vendor
 * ships varies between a consumer tablet and a bare head-unit image.
 */
fun PanelFont.resolve(theme: DashTheme): FontFamily = when (this) {
    is PanelFont.DashToken -> theme.font
    is PanelFont.Family -> when (name.trim().lowercase()) {
        "monospace" -> FontFamily.Monospace
        "serif" -> FontFamily.Serif
        "sans-serif", "sans" -> FontFamily.SansSerif
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.Default
    }
}

/**
 * A module's value as the author asked for it to be printed (§2).
 *
 * **[TextLayer.decimals] is not decoration.** A sensor reading `30.0` beside one reading `29.5`
 * must not print as `30`, and a ride height goes negative — so the sign is never suppressed and the
 * places are never trimmed. With no `decimals` the value is printed exactly as the module reported
 * it, which is the right default for a string that was never a number.
 *
 * **Formatted in [Locale.ROOT], not the device's locale.** The module sent `10.5` and the author
 * drew a panel around what that looks like; re-punctuating it to `10,5` because of an Android
 * setting would be DASH forming an opinion inside the panel boundary. Whether a module should be
 * able to *ask* for a localised number is a real question and belongs to the 1.6.10 lock, not to a
 * default chosen by accident here.
 */
fun TextLayer.format(raw: String?): String {
    val value = raw ?: return ""
    val body = decimals?.let { places ->
        value.trim().toDoubleOrNull()?.let { String.format(Locale.ROOT, "%.${places}f", it) } ?: value
    } ?: value
    return prefix + body + suffix
}

/** How far a pulse dips. Deep enough to read across a cabin, shallow enough not to strobe. */
private const val PULSE_FLOOR = 0.25f
