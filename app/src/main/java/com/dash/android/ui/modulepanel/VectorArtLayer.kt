package com.dash.android.ui.modulepanel

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.dash.android.panel.ArtNode
import com.dash.android.panel.ArtPaint
import com.dash.android.panel.GradientSpec
import com.dash.android.panel.VectorArt

/**
 * What a binding is currently doing to one target (roadmap 1.6.6).
 *
 * This is the whole payload of a frame. An element parsed out of the artwork keeps its name, so a
 * binding can reach it and move it every frame **without the artwork being rebuilt** — paths are
 * built once at parse time, and a frame costs a matrix and a paint.
 *
 * Every field here is already resolved: the layout engine has read the module's value, mapped it
 * through the binding's ranges, run it through any ease, and reduced it to *what this thing looks
 * like now*. Nothing in the renderer knows what a variable is.
 */
data class Dynamic(
    /** Degrees clockwise, zero pointing up — `module-layout.md` §9's angle convention. */
    val rotate: Float = 0f,
    /**
     * Rotation centre as a **fraction of the target's own box** (§9, amended 2026-08-12). `[0.5, 1.0]`
     * is the middle of its bottom edge, which is where a needle turns. Null means the box centre.
     */
    val pivot: Offset? = null,
    /** A `translate` binding's offset, as a fraction of the layer's own box. */
    val translate: Offset = Offset.Zero,
    /** A `style` binding's colour, replacing whatever the artwork painted. */
    val colour: Color? = null,
    /** A `style` binding's opacity, multiplied into whatever the artwork carried. */
    val opacity: Float = 1f,
    /** A `reveal` binding: 0 hides the element, 1 shows all of it. */
    val reveal: Float = 1f,
    /** Which way a reveal fills — `up`, `down`, `left`, `right`. */
    val revealDirection: String = "up",
)

/**
 * One vector layer of a panel.
 *
 * The artwork is scaled uniformly into the box. A layout's slot has the same aspect as the artwork
 * drawn for it (§6 — DASH never scales a layout into a slot it was not drawn for), so in practice
 * this is an exact fit; the letterboxing below only ever shows up when an author has drawn to the
 * wrong shape, and centring it is the honest way to show them.
 */
@Composable
fun VectorArtLayer(
    art: VectorArt,
    modifier: Modifier = Modifier,
    dynamics: Map<String, Dynamic> = emptyMap(),
) {
    Canvas(modifier) {
        val scale = minOf(size.width / art.viewBox.width, size.height / art.viewBox.height)
        val offsetX = (size.width - art.viewBox.width * scale) / 2f
        val offsetY = (size.height - art.viewBox.height * scale) / 2f

        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-art.viewBox.left, -art.viewBox.top)
        }) {
            for (node in art.nodes) draw(node, art.viewBox, node.id?.let { dynamics[it] })
        }
    }
}

/**
 * **The order of these three transforms is load-bearing**, because each one belongs in a different
 * coordinate space.
 *
 * A `translate` is a fraction of the layer, so it must act in the artwork's space — an element
 * asked to move a tenth of the panel moves that far on screen whatever group it happens to sit in.
 * A `rotate` is about the element's *own* box, so it must act inside the element's own space,
 * after any group transform the artwork applied. `node.transform` sits between them and carries
 * the artwork's own nesting.
 *
 * Getting this wrong is quiet rather than loud: a needle inside a translated group turns about a
 * point somewhere off in the artwork, which looks like a wrong pivot rather than a wrong space.
 */
private fun DrawScope.draw(node: ArtNode, viewBox: Rect, dynamic: Dynamic?) {
    withTransform({
        if (dynamic != null && dynamic.translate != Offset.Zero) {
            translate(dynamic.translate.x * viewBox.width, dynamic.translate.y * viewBox.height)
        }
        transform(node.transform)
        if (dynamic != null && dynamic.rotate != 0f) {
            val b = node.bounds
            val pivot = dynamic.pivot
                ?.let { Offset(b.left + it.x * b.width, b.top + it.y * b.height) }
                ?: b.center
            rotate(dynamic.rotate, pivot)
        }
    }) {
        val alpha = node.opacity * (dynamic?.opacity ?: 1f)
        val body: DrawScope.() -> Unit = {
            node.fill?.let { paint ->
                drawPath(node.path, brush(paint, node.bounds, dynamic?.colour), alpha = alpha * alphaOf(paint))
            }
            node.stroke?.let { paint ->
                drawPath(
                    node.path,
                    brush(paint, node.bounds, dynamic?.colour),
                    alpha = alpha * alphaOf(paint),
                    style = Stroke(node.strokeWidth, cap = node.strokeCap, join = node.strokeJoin),
                )
            }
        }

        // A reveal is a clip, not a scale (module-layout.md §4.4) — the artwork does not stretch,
        // it is progressively uncovered. Found by drawing a tyre-pressure bar, where scaling made
        // the rounded end of the bar go oval.
        if (dynamic != null && dynamic.reveal < 1f) {
            val b = node.bounds
            val visible = when (dynamic.revealDirection) {
                "down" -> Rect(b.left, b.top, b.right, b.top + b.height * dynamic.reveal)
                "left" -> Rect(b.right - b.width * dynamic.reveal, b.top, b.right, b.bottom)
                "right" -> Rect(b.left, b.top, b.left + b.width * dynamic.reveal, b.bottom)
                else -> Rect(b.left, b.bottom - b.height * dynamic.reveal, b.right, b.bottom)
            }
            clipRect(visible.left, visible.top, visible.right, visible.bottom) { body() }
        } else {
            body()
        }
    }
}

private fun alphaOf(paint: ArtPaint): Float = when (paint) {
    is ArtPaint.Solid -> paint.alpha
    is ArtPaint.Gradient -> paint.alpha
}

/**
 * A `style` binding's colour replaces the artwork's own paint entirely — including a gradient.
 *
 * That is the right reading of §4.3: *"`colour` means make this thing this colour"*. An author who
 * delegates an element to `@accentColourPrimary` has said what they want it to be, and blending it
 * with whatever the artwork happened to carry would give them neither.
 */
private fun brush(paint: ArtPaint, bounds: Rect, override: Color?): Brush {
    override?.let { return androidx.compose.ui.graphics.SolidColor(it) }
    return when (paint) {
        is ArtPaint.Solid -> androidx.compose.ui.graphics.SolidColor(paint.colour)
        is ArtPaint.Gradient -> when (val spec = paint.spec) {
            is GradientSpec.Linear -> Brush.linearGradient(
                colorStops = spec.stops.map { it.offset to it.colour }.toTypedArray(),
                start = resolve(spec.x1, spec.y1, bounds, spec),
                end = resolve(spec.x2, spec.y2, bounds, spec),
            )
            is GradientSpec.Radial -> Brush.radialGradient(
                colorStops = spec.stops.map { it.offset to it.colour }.toTypedArray(),
                center = resolve(spec.cx, spec.cy, bounds, spec),
                radius = spec.r * scaleOf(spec.transform) *
                    if (spec.objectBoundingBox) maxOf(bounds.width, bounds.height) else 1f,
            )
        }
    }
}

/**
 * A gradient control point, taken through the gradient's own transform and then resolved against
 * the shape.
 *
 * The order is SVG's: `gradientTransform` acts in gradient space, and only then are the
 * coordinates read as fractions of the shape's box (the default) or as plain user-space
 * coordinates (`gradientUnits="userSpaceOnUse"`, which is what Inkscape usually writes).
 */
private fun resolve(x: Float, y: Float, bounds: Rect, spec: GradientSpec): Offset {
    val p = spec.transform?.map(Offset(x, y)) ?: Offset(x, y)
    return if (spec.objectBoundingBox) {
        Offset(bounds.left + p.x * bounds.width, bounds.top + p.y * bounds.height)
    } else {
        p
    }
}

/**
 * How much a transform scales a length — the geometric mean of its two axes, `√|det|`.
 *
 * A radius is one number, so a transform that scales the axes differently has to be answered with
 * one number too. The parser has already warned the author when that is the case; this picks the
 * honest middle rather than favouring either axis.
 */
private fun scaleOf(m: Matrix?): Float {
    if (m == null) return 1f
    val determinant = m.values[0] * m.values[5] - m.values[4] * m.values[1]
    return kotlin.math.sqrt(kotlin.math.abs(determinant)).takeIf { it > 0f } ?: 1f
}
