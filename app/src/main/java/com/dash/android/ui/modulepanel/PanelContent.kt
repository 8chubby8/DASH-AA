package com.dash.android.ui.modulepanel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dash.android.panel.BoxedLayer
import com.dash.android.panel.PanelDocument
import com.dash.android.panel.RasterLayer
import com.dash.android.panel.TextAlign
import com.dash.android.panel.TextLayer
import com.dash.android.panel.TouchBinding
import com.dash.android.panel.VectorLayer
import com.dash.android.ui.theme.LocalDashTheme

/**
 * A module's panel, drawn (roadmap 1.6.6).
 *
 * **This is the inside of the castle, and DASH is only the mason.** Everything on screen here was
 * described by the module: its layers, its artwork, its colours, its arrangement. Nothing in this
 * file adds a border, a background, a padding, a radius or a style of DASH's own — the container is
 * [ModulePanel]'s business and it stops at the boundary. The one thing DASH contributes inside the
 * panel is a colour the author *asked* it to choose, through a theme token, which is obeying rather
 * than overriding (§7).
 *
 * Layers are drawn in list order, first at the bottom. Nothing is privileged as "the background":
 * the bottom layer is at the bottom, and that is its only distinction.
 */
@Composable
fun PanelContent(
    document: PanelDocument,
    values: Map<String, String>,
    modifier: Modifier = Modifier,
    onPress: (TouchBinding) -> Unit = {},
    onTouch: () -> Unit = {},
) {
    val theme = LocalDashTheme.current
    var pressed by remember(document) { mutableStateOf<Int?>(null) }
    val dynamics = rememberDynamics(document.layout, values, theme, pressed)
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.panelPresses(document, onPress, onTouch) { pressed = it }) {
        val panelWidth = maxWidth
        val panelHeight = maxHeight

        for (layer in document.layout.layers) {
            val dynamic = dynamics[layer.id]

            when (layer) {
                is RasterLayer -> {
                    val bitmap = document.raster[layer.asset] ?: continue
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        // Fit, not FillBounds. A layout is drawn for a known shape (§6), so this is
                        // an exact fit in practice — and where it is not, letterboxing shows the
                        // author their mismatch instead of quietly distorting their artwork to hide
                        // it. The same choice the vector layer makes, because raster and vector are
                        // equal citizens.
                        contentScale = ContentScale.Fit,
                        // `colour` on a raster is a tint (§4.3) — ship the artwork once in greyscale
                        // and colour it per state, rather than shipping an "on" and an "off" image.
                        colorFilter = dynamic?.colour?.let { ColorFilter.tint(it) },
                        modifier = Modifier.layerBox(layer, panelWidth, panelHeight, dynamic),
                    )
                }

                is VectorLayer -> {
                    val art = document.vector[layer.asset] ?: continue
                    VectorArtLayer(
                        art = art,
                        dynamics = dynamics.elementsOf(layer.id),
                        modifier = Modifier.layerBox(layer, panelWidth, panelHeight, dynamic),
                    )
                }

                is TextLayer -> {
                    val text = layer.format(values[layer.value])
                    val colour = layer.colour.resolve(theme) ?: theme.textColourPrimary
                    val family = layer.font.resolve(theme)
                    Canvas(Modifier.fillMaxSize()) {
                        val style = TextStyle(
                            // A fraction of the panel's height, taken straight to pixels. The module
                            // owns this number, so it deliberately does not track DASH's text-size
                            // control or Android's font setting — `toSp` here cancels the scale the
                            // renderer will reapply, leaving exactly the size the author asked for.
                            fontSize = (layer.fontSize * size.height).toSp(),
                            fontFamily = family,
                            fontWeight = if (layer.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (layer.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                        val measured = measurer.measure(AnnotatedString(text), style)
                        val anchorX = layer.at.x * size.width
                        val anchorY = layer.at.y * size.height
                        val x = when (layer.align) {
                            TextAlign.LEFT -> anchorX
                            TextAlign.CENTER -> anchorX - measured.size.width / 2f
                            TextAlign.RIGHT -> anchorX - measured.size.width
                        }
                        drawText(
                            textLayoutResult = measured,
                            color = dynamic?.colour ?: colour,
                            topLeft = Offset(x, anchorY),
                            alpha = layer.opacity * (dynamic?.opacity ?: 1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Presses on the panel, routed to the binding under the finger (roadmap 1.6.7).
 *
 * **The action fires on the way down**, which is what §4.2 says — *"on press, DASH sends
 * `ACTION`"* — and is also the only honest reading of press feedback: a control that lights under
 * the finger and does nothing until it is let go has already told the user it acted.
 *
 * **The whole panel is one gesture handler, not a pressable box per binding.** Pressability is a
 * property the layout attaches to targets that were drawn for other reasons — a named path inside
 * a vector layer is not a Compose element and never can be — so the press is resolved against the
 * layout rather than against the composition. It also keeps the drawing code free of it entirely:
 * nothing in the layer stack knows a press exists.
 *
 * A press that lands on nothing is left unconsumed, so it stays available to whatever is underneath
 * the panel rather than being swallowed by a surface that had no use for it.
 */
@Composable
private fun Modifier.panelPresses(
    document: PanelDocument,
    onPress: (TouchBinding) -> Unit,
    onTouch: () -> Unit,
    setPressed: (Int?) -> Unit,
): Modifier {
    /*
     * **The handler is read through [rememberUpdatedState], and that is load-bearing.**
     *
     * `pointerInput` is keyed on the document, so its coroutine is created once and lives for as
     * long as that panel is on screen — which is exactly what you want for a gesture loop, and
     * exactly what makes capturing anything else a trap. Called directly, `onPress` would be the
     * lambda from the composition in which the *document* last changed, and that lambda closes over
     * the panel's values as they were at that moment.
     *
     * Found on hardware (1.6.7): a fan that started at `2` stepped down to `1` and up to `3` and
     * would never go higher, because every press was stepping from a value frozen at install time
     * rather than from the current one. The panel looked alive — the readout moved, the module
     * answered — and it was reading a snapshot.
     *
     * This is the same shape as the `LaunchedEffect` defect fixed the day before: **a long-lived
     * coroutine keyed on one thing, silently holding a stale copy of another.** Keying the input on
     * the handler instead would fix it by tearing the gesture loop down on every reported value,
     * which is a far worse cure than the disease.
     */
    val current by rememberUpdatedState(onPress)
    // Same stale-capture trap as `onPress`, same cure — see above.
    val touched by rememberUpdatedState(onTouch)
    return pointerInput(document) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // **A finger landed. That is all this reports, and it consumes nothing** (roadmap
            // 1.6.9). The dwell timer needs to know the panel is in use so it does not fold shut
            // mid-adjustment — but 1.6.8 gave every gesture inside the boundary to the module, so
            // DASH observes and does not claim. A press that hits no binding still returns below,
            // still unconsumed, and still available to whatever is underneath.
            touched()
            val hit = document.hitTest(size.toSize(), down.position) ?: return@awaitEachGesture
            down.consume()
            setPressed(hit.index)
            current(hit.binding)
            // Feedback lasts exactly as long as the finger does. Cancellation — a scroll elsewhere
            // stealing the gesture — ends it the same way a lift does, because either way the
            // finger is no longer on the control.
            waitForUpOrCancellation()
            setPressed(null)
        }
    }
}

/**
 * Place and transform a layer within the panel.
 *
 * A layer-level `rotate` turns about the layer's own `pivot`, which is right for a needle shipped as
 * its own small raster: `[0.5, 1.0]` is the middle of its bottom edge, and that is where a needle
 * turns. A binding may carry its own pivot, which wins.
 */
private fun Modifier.layerBox(
    layer: BoxedLayer,
    panelWidth: Dp,
    panelHeight: Dp,
    dynamic: Dynamic?,
): Modifier {
    val box = layer.box()
    val pivot = dynamic?.pivot ?: layer.pivot
    return this
        .offset(
            x = panelWidth * box.left + panelWidth * (dynamic?.translate?.x ?: 0f),
            y = panelHeight * box.top + panelHeight * (dynamic?.translate?.y ?: 0f),
        )
        .size(panelWidth * box.width, panelHeight * box.height)
        .graphicsLayer {
            rotationZ = dynamic?.rotate ?: 0f
            transformOrigin = TransformOrigin(pivot.x, pivot.y)
            alpha = layer.opacity * (dynamic?.opacity ?: 1f)
        }
        .revealClip(dynamic)
}

/**
 * A whole-layer `reveal` — a clip, never a scale (§4.4).
 *
 * Scaling a layer to show a proportion distorts it: a bar with rounded ends or a gradient deforms
 * visibly as it shrinks. Revealing draws it at true size and uncovers it.
 */
private fun Modifier.revealClip(dynamic: Dynamic?): Modifier {
    if (dynamic == null || dynamic.reveal >= 1f) return this
    return drawWithContent {
        val visible = visibleRect(dynamic.reveal, dynamic.revealDirection)
        clipRect(visible.left, visible.top, visible.right, visible.bottom) { this@drawWithContent.drawContent() }
    }
}

private fun DrawScope.visibleRect(reveal: Float, direction: String): Rect = when (direction) {
    "down" -> Rect(0f, 0f, size.width, size.height * reveal)
    "left" -> Rect(size.width - size.width * reveal, 0f, size.width, size.height)
    "right" -> Rect(0f, 0f, size.width * reveal, size.height)
    else -> Rect(0f, size.height - size.height * reveal, size.width, size.height)
}

/**
 * The dynamics belonging to elements *inside* one vector layer, re-keyed to the bare element name.
 *
 * `layer#element` is what closes the name-scoping problem — two vector layers may each contain an
 * element called `needle` without ambiguity, because the layer always qualifies the element. The
 * renderer inside a layer only ever sees its own.
 */
private fun Map<String, Dynamic>.elementsOf(layerId: String): Map<String, Dynamic> {
    val prefix = "$layerId#"
    return entries
        .filter { it.key.startsWith(prefix) }
        .associate { it.key.removePrefix(prefix) to it.value }
}
