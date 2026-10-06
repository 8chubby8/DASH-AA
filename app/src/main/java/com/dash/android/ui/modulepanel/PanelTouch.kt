package com.dash.android.ui.modulepanel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.dash.android.panel.ArtNode
import com.dash.android.panel.BindingTarget
import com.dash.android.panel.BoxedLayer
import com.dash.android.panel.PanelDocument
import com.dash.android.panel.TouchBinding
import com.dash.android.panel.VectorArt
import com.dash.android.panel.VectorLayer

/**
 * Where a press landed — the binding it hit, and its position in the document (roadmap 1.6.7).
 *
 * The index is carried because a binding is not unique: two buttons may send the same control with
 * the same value and differ only in where they sit. Press feedback has to light the one that was
 * actually touched.
 */
data class PanelHit(val index: Int, val binding: TouchBinding)

/**
 * The topmost `touch` binding under [at], or null if the press landed on nothing (roadmap 1.6.7).
 *
 * **All three target routes are pressable** (`module-layout.md` §3), and the third is the one that
 * earns this file: a bare `rect` and a whole layer are boxes DASH already knows, but `layer#element`
 * points at a named path *inside* parsed artwork, and finding it means walking the same coordinate
 * chain the renderer walks — the node's own accumulated group transform, then the artwork's uniform
 * fit into its layer, then the layer's box within the panel. Getting that chain wrong here would be
 * invisible: the button draws in the right place and answers somewhere else.
 *
 * **The area is the element's bounding box, not its outline.** A press is a fingertip, not a
 * pointer, and an author who drew a `+` out of two thin bars did not mean *"only the bars are
 * pressable"* — they drew a button. Hit-testing the path itself would make the gaps in their own
 * glyph into dead spots.
 *
 * **The *drawn* position is deliberately not consulted.** A `rotate` or `translate` binding moves
 * what is on screen every frame, and a control that moved away from your finger as you reached for
 * it would be indefensible. Nothing that is bound to live data is a sane thing to press, so the
 * press area is where the artwork was authored.
 *
 * **DASH imposes no minimum touch size.** A control too small to hit is a mistake the author can
 * see and fix in their own artwork; DASH quietly inflating it would be reaching inside the panel
 * boundary to correct a design decision, which is precisely what the module mantra forbids. The
 * previewer is the right place for that to be said out loud.
 */
fun PanelDocument.hitTest(panel: Size, at: Offset): PanelHit? {
    if (panel.width <= 0f || panel.height <= 0f) return null

    // Topmost wins, so the search keeps the best rather than stopping at the first. Bindings are not
    // in z-order — layers are — so a binding's depth is the depth of the layer it points at, and a
    // later binding beats an earlier one at the same depth for the same reason a later layer beats
    // an earlier one: the author put it second.
    var best: PanelHit? = null
    var bestDepth = Int.MIN_VALUE
    layout.bindings.forEachIndexed { index, binding ->
        if (binding !is TouchBinding) return@forEachIndexed
        val depth = depthOf(binding.target) ?: return@forEachIndexed
        if (depth < bestDepth) return@forEachIndexed
        val area = pressArea(binding.target, panel) ?: return@forEachIndexed
        if (!area.contains(at)) return@forEachIndexed
        best = PanelHit(index, binding)
        bestDepth = depth
    }
    return best
}

/**
 * How deep in the stack a target sits, or null when it names something the layout never drew.
 *
 * **A bare `rect` sits above every layer.** §3 gives it as the route for *"touch areas over painted
 * artwork"* — over being the whole point, since an author reaching for it has already decided the
 * artwork underneath has nothing pressable to say.
 */
private fun PanelDocument.depthOf(target: BindingTarget): Int? = when (target) {
    is BindingTarget.Area -> Int.MAX_VALUE
    is BindingTarget.Named -> layout.layers.indexOfFirst { it.id == target.layer }.takeIf { it >= 0 }
}

/** The pressable box in panel pixels, or null when the target names something that is not drawn. */
private fun PanelDocument.pressArea(target: BindingTarget, panel: Size): Rect? = when (target) {
    is BindingTarget.Area -> target.rect.scaledTo(panel)

    is BindingTarget.Named -> {
        val layer = layout.layer(target.layer) as? BoxedLayer
        val box = layer?.box()?.scaledTo(panel)
        when {
            layer == null || box == null -> null
            target.element == null -> box
            else -> {
                // Only a vector layer has elements inside it. A `layer#element` target aimed at a
                // raster names something that cannot exist, and answers nothing rather than falling
                // back to the whole layer — silently widening a press area to the size of the
                // artwork is how a mistyped element name becomes a panel that responds everywhere.
                val art = (layer as? VectorLayer)?.let { vector[it.asset] }
                art?.node(target.element)?.areaIn(art, box)
            }
        }
    }
}

/**
 * One element's press area, mapped out of artwork space and into the panel.
 *
 * The three steps mirror [VectorArtLayer] exactly, in the same order and for the same reasons: the
 * node's own [ArtNode.transform] carries whatever nesting the artwork applied, the artwork is fitted
 * uniformly into its layer and centred, and the layer sits at its own box within the panel.
 *
 * The four corners are mapped rather than the two opposite ones, because a group `rotate` in the
 * artwork turns a box into a diamond — and the bounding box of two corners of a diamond is not the
 * bounding box of the diamond.
 */
private fun ArtNode.areaIn(art: VectorArt, layerBox: Rect): Rect {
    val viewBox = art.viewBox
    if (viewBox.width <= 0f || viewBox.height <= 0f) return Rect.Zero
    val scale = minOf(layerBox.width / viewBox.width, layerBox.height / viewBox.height)
    val originX = layerBox.left + (layerBox.width - viewBox.width * scale) / 2f
    val originY = layerBox.top + (layerBox.height - viewBox.height * scale) / 2f

    val corners = listOf(
        Offset(bounds.left, bounds.top),
        Offset(bounds.right, bounds.top),
        Offset(bounds.right, bounds.bottom),
        Offset(bounds.left, bounds.bottom),
    ).map { transform.map(it) }

    val xs = corners.map { originX + (it.x - viewBox.left) * scale }
    val ys = corners.map { originY + (it.y - viewBox.top) * scale }
    return Rect(xs.min(), ys.min(), xs.max(), ys.max())
}

/** A rect given as fractions of the panel, in panel pixels. */
private fun Rect.scaledTo(panel: Size) =
    Rect(left * panel.width, top * panel.height, right * panel.width, bottom * panel.height)
