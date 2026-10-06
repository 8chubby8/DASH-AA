package com.dash.android.ui.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dash.android.ui.splash.CroppedImage
import com.dash.android.ui.splash.SPLASH_DETENT_EPS
import com.dash.android.ui.splash.SPLASH_ZOOM_MAX
import com.dash.android.ui.splash.SplashCrop
import com.dash.android.ui.splash.containZoom
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * The interactive crop editor (roadmap 1.5.6, Phase 2). A fixed frame the shape of the screen; the
 * image pans and pinches behind it. Zoom runs from the *contain floor* (whole image visible, matte in
 * the gaps) up through the **standard-crop detent** at 1.0 — where it clicks and holds — and on into
 * tight zoom. To cross the detent you release and pinch again: within one gesture you can only reach
 * it, not pass it, in either direction.
 */
@Composable
fun SplashCropEditor(
    bitmap: ImageBitmap,
    aspect: Float,
    colour: Color,
    crop: SplashCrop,
    maxHeight: Dp,
    onChange: (SplashCrop) -> Unit,
    onCommit: (SplashCrop) -> Unit,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestChange by rememberUpdatedState(onChange)
    val latestCommit by rememberUpdatedState(onCommit)

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val byHeight = maxHeight * aspect
        val w = if (byHeight <= maxWidth) byHeight else maxWidth
        val h = w / aspect

        var frameSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            Modifier
                .width(w)
                .height(h)
                .clip(RoundedCornerShape(8.dp))
                .background(colour)
                .onSizeChanged { frameSize = it }
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val startZoom = latestCrop.zoom
                        val onDetent = abs(startZoom - 1f) < SPLASH_DETENT_EPS
                        val fw = frameSize.width.toFloat()
                        val fh = frameSize.height.toFloat()
                        val iw = bitmap.width.toFloat()
                        val ih = bitmap.height.toFloat()
                        val floor = containZoom(fw, fh, iw, ih)
                        val cover = max(fw / iw, fh / ih)

                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoomChange != 1f || pan != Offset.Zero) {
                                val c = latestCrop

                                // Zoom, held within this gesture's detent-limited band.
                                var newZoom = c.zoom * zoomChange
                                newZoom = when {
                                    onDetent -> newZoom.coerceIn(floor, SPLASH_ZOOM_MAX)
                                    startZoom > 1f -> newZoom.coerceIn(1f, SPLASH_ZOOM_MAX)
                                    else -> newZoom.coerceIn(floor, 1f)
                                }
                                if (abs(newZoom - 1f) < SPLASH_DETENT_EPS) newZoom = 1f

                                // Pan within the overflow at the new zoom; a letterboxed axis pins to centre.
                                val scale = newZoom * cover
                                val dw = iw * scale
                                val dh = ih * scale
                                val maxPanX = max(0f, (dw - fw) / 2f)
                                val maxPanY = max(0f, (dh - fh) / 2f)
                                val newPanX = if (maxPanX > 0f) ((c.panX * maxPanX + pan.x) / maxPanX).coerceIn(-1f, 1f) else 0f
                                val newPanY = if (maxPanY > 0f) ((c.panY * maxPanY + pan.y) / maxPanY).coerceIn(-1f, 1f) else 0f

                                latestChange(SplashCrop(newZoom, newPanX, newPanY))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        latestCommit(latestCrop)
                    }
                }
        ) {
            CroppedImage(bitmap, Modifier.fillMaxSize()) { crop }

            // Dashed frame edge — this is the screen. Decorative only (no pointerInput), so the
            // pan/pinch gesture on the parent frame is never intercepted.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
                )
            }
        }
    }
}
