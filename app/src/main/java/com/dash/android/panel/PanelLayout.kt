package com.dash.android.panel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * A panel layout document — **layers and bindings, and nothing else** (roadmap 1.6.6).
 *
 * This is `module-layout.md` §1's whole model in one type. [layers] are what is drawn, an ordered
 * stack composited bottom to top. [bindings] are what happens, each attaching a behaviour to a
 * target. There is no third concept, and nothing should ever be added that becomes one.
 *
 * **The layout is the declaration** (§9). A module declares no variables and no controls
 * separately, because everything it reports and everything it can be told is named in here.
 * [boundVariables] and [controls] read them back out, which is how DASH learns what a module has
 * without the module ever saying so twice.
 *
 * [variables] is the one thing a module states outright rather than being read out of its bindings:
 * its own private message board (§8a), which is what lets a press be drawn before the module answers.
 *
 * **Positions and sizes are fractions of the panel**, never pixels — `0,0` is top-left and `1,1`
 * bottom-right, so a layout drawn once lands in the same relative place on a phone and on a head
 * unit. DASH converts to real pixels at render time and nowhere else.
 */
data class PanelLayout(
    val layers: List<PanelLayer> = emptyList(),
    val bindings: List<PanelBinding> = emptyList(),
    /** The module's own private message board (§8a). Empty when the module declared none. */
    val variables: Map<String, PanelVariable> = emptyMap(),
    val warnings: List<Warning> = emptyList(),
) {
    /** Every module variable this layout watches — the read half of §9's declaration. */
    val boundVariables: Set<String>
        get() = (layers.filterIsInstance<TextLayer>().map { it.value } +
            bindings.mapNotNull { it.variable }).toSet()

    /** Every control this layout can send — the write half. Populated by `touch` bindings (1.6.7). */
    val controls: Set<String>
        get() = bindings.filterIsInstance<TouchBinding>().map { it.control }.toSet()

    /** The layer a binding target names, or null if the layout points at something it never drew. */
    fun layer(id: String): PanelLayer? = layers.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------------- variables

/**
 * One entry on the module's own private message board (roadmap 1.6.7, `module-layout.md` §8a).
 *
 * **This is the module's version of `system_commands.md`.** DASH's shared board has a declared
 * vocabulary with a type and a set of values per signal, written by DASH because the board is
 * shared. A module's board is private, so the module writes it — and that single difference is the
 * whole idea. Nobody reaches into anybody's box.
 *
 * **It is a list, and nothing more.** There is no type system here: no boolean, no enum, no
 * numeric range. A fan is `["off","1","2","3","4","5","6","max"]` and a toggle is `["off","on"]`,
 * and the second needs no rules of its own because a toggle *is* a two-element list that wraps.
 *
 * **The list is what makes a press instant.** DASH finds the current value in [values], moves one
 * place, and sends what it lands on — so a `+` stops being a direction nobody can predict and
 * becomes an ordinary set-this-to-this control that draws under the finger like any other.
 *
 * **DASH does no arithmetic on a module's data.** It never adds one to `3`; it moves along a list
 * the module wrote, and it has no idea what the values mean or that `4` is larger than `3`. That
 * distinction is what keeps this inside the Module Mantra: DASH is obeying a declaration, not
 * forming an opinion about a module's data.
 *
 * *Written out longhand rather than generated from a range. A range was designed and rejected
 * (2026-08-15, Roger's call) — the deciding case was his own temperature scale, whose ends are
 * `min` and `max` rather than numbers, so the shorthand could not express the very thing it was
 * invented for. Twice now a real control has had words at the ends and numbers in the middle.*
 */
data class PanelVariable(
    /** Every value this variable takes, in order. The order is the only thing DASH knows about them. */
    val values: List<String> = emptyList(),
    /** Whether the last value steps round to the first. `false` stops at each end. */
    val wrap: Boolean = false,
) {
    /**
     * The value one place from [current], or null when there is nowhere to go.
     *
     * Null covers both stop conditions, and they are deliberately the same answer: an end stop on a
     * non-wrapping list, and a [current] this list has never heard of. **The second is the browser
     * rule applied to data** — firmware moves on, an unlisted value arrives, and it still displays
     * and still drives every binding because it is a string like any other. There is simply no index
     * to move from, so prediction switches off until a known value returns. Never an error.
     */
    fun stepFrom(current: String?, by: Int): String? {
        if (values.isEmpty() || by == 0) return null
        val at = values.indexOf(current?.trim())
        if (at < 0) return null
        val next = at + by
        return when {
            next in values.indices -> values[next]
            !wrap -> null
            // Kotlin's % keeps the sign of the dividend, so a step down from the first entry needs
            // the extra turn to land on the last rather than off the front of the list.
            else -> values[((next % values.size) + values.size) % values.size]
        }
    }
}

// ------------------------------------------------------------------------------------- layers

/**
 * One layer of the stack (§2). Three types and no more: a raster, a vector, or text DASH draws.
 *
 * **Raster and vector are equal citizens** — neither is the base case with the other bolted on, and
 * nothing here is privileged as "the background". The bottom layer is at the bottom; that is its
 * only distinction.
 */
sealed interface PanelLayer {
    /** The name bindings use to point at this layer. Unique within the document. */
    val id: String

    /** `0`–`1`. Default `1`. */
    val opacity: Float
}

/**
 * A layer that occupies a box — a raster or a vector.
 *
 * **[at] and [size] are both optional, and omitting them means fill the panel** (§9). That is how a
 * background is expressed without DASH ever needing the concept of one.
 */
sealed interface BoxedLayer : PanelLayer {
    /** Top-left, as a fraction of the panel. Null fills. */
    val at: Offset?

    /** Width and height, as fractions of the panel. Null fills. */
    val size: Size?

    /** The block name of the artwork this layer draws. */
    val asset: String

    /** Rotation centre within this layer's own box. A `rotate` binding's own pivot overrides it. */
    val pivot: Offset

    /** Where this layer sits on the panel, resolved. Fills when the author gave no box. */
    fun box(): Rect {
        val origin = at ?: Offset.Zero
        val extent = size ?: Size(1f - origin.x, 1f - origin.y)
        return Rect(origin, extent)
    }
}

/** A PNG, shipped as a `BLOCK`. Photographic or painted artwork — anything a vector cannot express. */
data class RasterLayer(
    override val id: String,
    override val asset: String,
    override val at: Offset? = null,
    override val size: Size? = null,
    override val pivot: Offset = Offset(0.5f, 0.5f),
    override val opacity: Float = 1f,
) : BoxedLayer

/** An SVG, drawn from the subset in `svg-subset.json`. Its named elements can be bound to directly. */
data class VectorLayer(
    override val id: String,
    override val asset: String,
    override val at: Offset? = null,
    override val size: Size? = null,
    override val pivot: Offset = Offset(0.5f, 0.5f),
    override val opacity: Float = 1f,
) : BoxedLayer

/**
 * Text drawn by DASH from a live value (§2). **Not an asset** — the one layer type that ships no
 * bytes.
 *
 * It carries everything about how the value is drawn, because a number on a gauge is not a string:
 * a sensor reading `30.0` beside one reading `29.5` must not print as `30`, and a ride height goes
 * negative. Hence [decimals] and a sign that is never suppressed.
 *
 * **A module never ships a typeface** — [font] names one. See [PanelFont].
 */
data class TextLayer(
    override val id: String,
    /** The module variable displayed. */
    val value: String,
    /** The anchor point, as a fraction of the panel. */
    val at: Offset,
    /** Type size as a fraction of the panel's height. */
    val fontSize: Float,
    val align: TextAlign = TextAlign.LEFT,
    val colour: ColourRef = ColourRef.Token("textColourPrimary"),
    val font: PanelFont = PanelFont.DashToken,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Decimal places. Null prints the value exactly as the module reported it. */
    val decimals: Int? = null,
    val prefix: String = "",
    val suffix: String = "",
    override val opacity: Float = 1f,
) : PanelLayer

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * The two typeface options, and only two (§2, ruled 2026-08-06).
 *
 * [DashToken] follows whatever the user has chosen system-wide and changes with it. [Family] names
 * an Android font family — `sans-serif`, `serif` and `monospace` are guaranteed by the framework;
 * anything else is attempted and falls back to the default if the device does not have it.
 *
 * `monospace` earns its place for a specific reason: in a proportional face `29.5` becoming `30.0`
 * shifts the whole readout sideways, and on a gauge updating several times a second that jitter is
 * genuinely distracting. An author with changing numbers usually wants it and may not know yet.
 */
sealed interface PanelFont {
    data object DashToken : PanelFont
    data class Family(val name: String) : PanelFont
}

/**
 * A colour the author either stated or delegated (§7).
 *
 * **A [Token] is stored as a token and resolved in the frame being drawn** — never flattened to a
 * literal when the layout is stored. That is what makes a themed panel follow the user's colours
 * live, and baking it at install would freeze the panel at whatever theme was active that day.
 */
sealed interface ColourRef {
    data class Literal(val colour: Color) : ColourRef
    data class Token(val name: String) : ColourRef
}

// ----------------------------------------------------------------------------------- bindings

/**
 * A binding attaches a behaviour to a target (§4).
 *
 * **Two or more bindings may point at the same target, and that is the normal case.** An airflow
 * arrow is typically both a `touch` (press it) and a `style` (colour it to show it is selected).
 */
sealed interface PanelBinding {
    val target: BindingTarget

    /** Milliseconds to ease to a new state. `0` snaps. Always the module's own (§5). */
    val ease: Int

    /** The module variable this binding watches, or null when it watches nothing. */
    val variable: String?
}

/** The three ways to point at something (§3). */
sealed interface BindingTarget {
    /**
     * A layer, or a **named element inside a vector layer** written `layer#element`.
     *
     * The layer always qualifies the element, so two vector layers may each contain a `needle`
     * without ambiguity.
     */
    data class Named(val layer: String, val element: String? = null) : BindingTarget {
        override fun toString() = if (element == null) layer else "$layer#$element"
    }

    /** A bare area at normalised coordinates, attached to no layer. For touch over painted artwork. */
    data class Area(val rect: Rect) : BindingTarget
}

/**
 * Changes a target's appearance from a value (§4.3). Fill, stroke, tint, or opacity.
 *
 * **On vector this restyles the named element; on raster it is a tint** — ship the artwork once in
 * greyscale and colour it per state, rather than shipping an "on" image and an "off" image.
 *
 * **A binding with no [variable] is a static restyle** — *"this element is always this colour"*. It
 * watches nothing and never changes, and it is how an author delegates a colour to a theme token
 * without the SVG itself carrying any DASH-specific markup.
 *
 * **Opacity is how things appear and disappear.** There is no separate visibility binding: hiding
 * something is setting its opacity to zero, which also lets it fade rather than only blink.
 */
data class StyleBinding(
    override val target: BindingTarget,
    override val variable: String? = null,
    /** Ordered — **first match wins**. Empty when this is a static restyle. */
    val cases: List<StyleCase> = emptyList(),
    /** Applied when no case matches, or always when there is no variable. */
    val default: StyleEffect = StyleEffect(),
    override val ease: Int = 0,
) : PanelBinding

/** One case of a [StyleBinding]: what to match, and what it looks like when it does. */
data class StyleCase(val match: ValueMatch, val effect: StyleEffect)

/** What a case tests. Discrete for toggles and modes; ranged for warnings. */
sealed interface ValueMatch {
    data class Is(val value: String) : ValueMatch
    data class Below(val value: Float) : ValueMatch
    data class Above(val value: Float) : ValueMatch
    data class Between(val low: Float, val high: Float) : ValueMatch

    fun matches(raw: String): Boolean {
        val number = raw.trim().toFloatOrNull()
        return when (this) {
            is Is -> raw.trim().equals(value, ignoreCase = true)
            is Below -> number != null && number < value
            is Above -> number != null && number > value
            is Between -> number != null && number >= low && number <= high
        }
    }
}

/** What a matched case does. All three optional — an effect may change only one thing. */
data class StyleEffect(
    val colour: ColourRef? = null,
    val opacity: Float? = null,
    /** A pulse period in milliseconds — §5's effect, DASH-driven and needing no asset. */
    val pulse: Int? = null,
)

/**
 * Draws a target up to a proportion of itself (§4.4) — a bar filling, a meter, an arc gauge.
 *
 * **This is a clip, not a scale.** Scaling an element to show a proportion distorts it: a bar with
 * rounded ends or a gradient deforms visibly as it shrinks. Revealing draws the element at its true
 * size and clips it, which preserves the artwork at every value.
 */
data class RevealBinding(
    override val target: BindingTarget,
    override val variable: String,
    /** `[empty, full]` — the input range. */
    val from: ClosedFloatingPointRange<Float>,
    val direction: RevealDirection = RevealDirection.UP,
    override val ease: Int = 0,
) : PanelBinding

enum class RevealDirection(val wire: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * Turns a target about a pivot, mapped from a value (§4.5).
 *
 * **[pivot] is a fraction of the target's own box** (amended 2026-08-12) — `[0.5, 1.0]` is the
 * middle of its bottom edge, which is where a needle turns. Reading it against the *target* rather
 * than the layer is what makes one rule serve both routes: a needle is `[0.5, 1.0]` whether it was
 * shipped as its own raster layer or drawn as a named path inside a vector one. It is also what
 * lets two elements in one layer rotate about different centres, so a twin-needle gauge can be
 * expressed at all.
 */
data class RotateBinding(
    override val target: BindingTarget,
    override val variable: String,
    /** `[min, max]` input range. */
    val from: ClosedFloatingPointRange<Float>,
    /** `[angle, angle]` output range, degrees clockwise with zero pointing up. */
    val to: ClosedFloatingPointRange<Float>,
    /** Null defers to the layer's own pivot, and failing that the target's box centre. */
    val pivot: Offset? = null,
    override val ease: Int = 0,
) : PanelBinding

/**
 * Moves a target along an axis, mapped from a value (§4.6).
 *
 * *Included in the specification on reasoning rather than evidence — no drawn panel had required it
 * when the format was designed, and §4.6 marks it a candidate for removal if still unused when the
 * spec locks at 1.6.10. The Tank Gauge's arrow head, tracking the end of its bar, is the first real
 * use; whether one use justifies a binding is Roger's call at the lock.*
 */
data class TranslateBinding(
    override val target: BindingTarget,
    override val variable: String,
    val from: ClosedFloatingPointRange<Float>,
    /** Start and end offsets, as fractions of the panel. */
    val start: Offset,
    val end: Offset,
    override val ease: Int = 0,
) : PanelBinding

/**
 * Makes a target pressable (§4.2). On press, DASH sends `ACTION|id|control|value` to the module.
 *
 * **Parsed here, honoured at 1.6.7.** The layout is the declaration, so a control has to be *known*
 * from the moment the layout is read — [PanelLayout.controls] is what makes 1.4.9's decision not to
 * validate outgoing control ids sound, since the panel can only ever render controls the layout
 * declares. What 1.6.6 does not yet build is the other half: the press, the optimistic update and
 * the timeout that keeps optimism honest (§8). Nothing here misleads an author meanwhile — their
 * layout is read correctly and their control is recorded; it simply does not fire yet.
 */
data class TouchBinding(
    override val target: BindingTarget,
    val control: String,
    val value: String = "",
    /**
     * How far along [control]'s declared list this press moves — `+1` for a next, `-1` for a
     * previous, `0` for a control that names its value outright (roadmap 1.6.7).
     *
     * **One field serves the stepper and the toggle**, because the list decides what "next" means:
     * on a fan it is `3` → `4`, on an `["off","on"]` it is the other one. The toggle needed no
     * mechanism of its own, which is the sign the shape was right.
     */
    val step: Int = 0,
    /** Appearance while pressed. Local and immediate; nothing to do with the module. */
    val feedback: StyleEffect? = null,
    override val ease: Int = 0,
) : PanelBinding {
    override val variable: String? get() = null
}
