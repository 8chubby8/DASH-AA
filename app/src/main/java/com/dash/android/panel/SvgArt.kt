package com.dash.android.panel

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/**
 * DASH's intermediate representation of a piece of vector artwork (roadmap 1.6.6).
 *
 * **Why this exists rather than an `ImageVector`.** `module-layout.md` §9 names Compose's
 * `ImageVector` as the *model* the subset must land on — paths, groups, transforms, fills,
 * strokes, gradients, opacity — and as a description of the model that is exactly right. As the
 * runtime *vehicle* it does not work, which the parser spike established before this shipped.
 *
 * An `ImageVector` is built once and drawn whole. There is no handle on the element inside it, so
 * a `rotate` binding on `dial#needle` (§4.5) can only be honoured by rebuilding the entire vector
 * tree with a new group rotation on every frame the needle moves — which throws away the painter's
 * cache each time, on a panel that is persistent, always on screen and driven by live vehicle data.
 *
 * So the artwork parses into a flat list of [ArtNode]s instead. Each node keeps a [Path] built once
 * and its own accumulated [transform], and the renderer applies a *dynamic* transform on top per
 * frame. Paths are built at parse time; only matrices move at draw time. Named elements are
 * addressable **by construction**, which is what §9's `layer#element` target actually requires.
 *
 * Measured on the Tab S9 Ultra with a needle sweeping continuously: the full 120 Hz panel rate,
 * zero dropped frames, zero missed vsyncs.
 */
data class VectorArt(
    val viewBox: Rect,
    val nodes: List<ArtNode>,
    val warnings: List<Warning>,
) {
    /** Ids are unique within a document, so a binding target resolves to one node or none. */
    fun node(id: String): ArtNode? = nodes.firstOrNull { it.id == id }

    /** Every id a binding could point at — what the previewer checks a layout's targets against. */
    val boundableIds: List<String> get() = nodes.mapNotNull { it.id }
}

/**
 * One drawable shape, flattened out of the SVG tree.
 *
 * Flattening is safe because everything the subset inherits — fill, stroke, opacity — is resolved
 * during the parse, and everything a group contributes to geometry is folded into [transform].
 */
data class ArtNode(
    val id: String?,
    val path: Path,
    val bounds: Rect,
    val fill: ArtPaint?,
    val stroke: ArtPaint?,
    val strokeWidth: Float,
    val strokeCap: StrokeCap,
    val strokeJoin: StrokeJoin,
    val opacity: Float,
    val transform: Matrix,
)

/** Paint is either flat or a gradient. `none` is the absence of paint, so it is simply null. */
sealed interface ArtPaint {
    data class Solid(val colour: Color, val alpha: Float = 1f) : ArtPaint
    data class Gradient(val spec: GradientSpec, val alpha: Float = 1f) : ArtPaint
}

data class GradientStop(val offset: Float, val colour: Color)

sealed interface GradientSpec {
    val stops: List<GradientStop>

    /** True when coordinates are fractions of the shape's own bounding box — the SVG default. */
    val objectBoundingBox: Boolean

    /**
     * The gradient's own `gradientTransform`, applied in gradient space before the coordinates are
     * resolved against the shape (`module-layout.md` §9 — *"DASH must implement it"*).
     *
     * **This is not an optional refinement.** Inkscape almost always writes `userSpaceOnUse` with
     * the rotation and scale of the gradient carried here rather than in the coordinates, so
     * ignoring it makes an ordinary rotated gradient draw along the wrong axis — the quiet trap
     * the spec calls out by name. Null when the artwork carries none, which costs nothing.
     */
    val transform: Matrix?

    data class Linear(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        override val stops: List<GradientStop>,
        override val objectBoundingBox: Boolean,
        override val transform: Matrix? = null,
    ) : GradientSpec

    data class Radial(
        val cx: Float, val cy: Float, val r: Float,
        override val stops: List<GradientStop>,
        override val objectBoundingBox: Boolean,
        override val transform: Matrix? = null,
    ) : GradientSpec
}

/**
 * Something the author should know, collected during the parse.
 *
 * **The warnings are the product.** Rendering tells an author what their panel looks like; these
 * tell them what DASH did with the parts it could not draw — which is the half they cannot see.
 */
data class Warning(val severity: Severity, val what: String, val message: String) {
    override fun toString() = "${severity.label}  $what — $message"
}

enum class Severity(val label: String) {
    /** Not drawn at all. */
    SKIPPED("SKIPPED "),

    /** Drawn, but without something the author asked for. */
    DEGRADED("DEGRADED"),

    /** Drawn as intended; worth knowing anyway. */
    ADVICE("ADVICE  "),
}
