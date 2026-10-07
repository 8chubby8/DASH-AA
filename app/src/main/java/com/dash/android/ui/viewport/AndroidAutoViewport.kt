package com.dash.android.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.dash.android.aa.AaStatus
import com.dash.android.aa.AndroidAutoHost
import com.dash.android.aa.input.EvdevTouch
import com.dash.android.ui.common.BODY
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.clock.AnalogueClock
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * **The viewport** (DASH-AA) — interface.md's "dedicated display area for all Android applications",
 * filled here by Android Auto.
 *
 * It occupies exactly the rectangle upstream's 1.7.1 test build measured: the space the system bar and
 * the *resting* module-panel assembly leave, which is also the rectangle the settings blind rolls into.
 * Everything interface.md asked of the viewport and upstream could not deliver on Android, it does:
 *
 * - **"Apps always fill the viewport completely — DASH never letterboxes."** The phone is told margins
 *   that make its interface exactly the viewport's shape ([com.dash.android.aa.protocol.VideoGeometry]);
 *   the margins are cropped off here and the rest scaled to fill.
 * - **"The viewport is the app's entire world."** Nothing of DASH is drawn inside it while projecting.
 * - **The module panel and the settings blind draw over it**, as upstream's do over an app — and touches
 *   on them never reach the phone, because they are on top.
 *
 * **When no phone is projecting it shows a big analogue clock** (Roger, 2026-10-07; it was the weather
 * scene from 2026-10-05, which stays as the settings panel's landing) — interface.md's "the system never
 * goes black" — with one quiet line saying what Android Auto is doing. Tapping that line
 * after the phone has closed Android Auto reconnects.
 */
@Composable
fun AndroidAutoViewport(
    host: AndroidAutoHost,
    window: java.awt.Window?,
    modifier: Modifier = Modifier,
) {
    val status by host.status.collectAsState()

    Box(
        modifier
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                val size = coords.size
                host.updateViewport(size.width, size.height)
                // Screen coordinates, for the touchscreen reader: window origin plus the viewport's place
                // in the window, in the toolkit's units — pixels divided by the *toolkit's* scale, which
                // is not Compose's density once DASH-AA has applied the desktop scale itself.
                if (window != null && window.isShowing) {
                    val toolkit = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                    val density = if (toolkit > 0f) toolkit else 1f
                    val b = coords.boundsInWindow()
                    val origin = runCatching { window.locationOnScreen }.getOrNull()
                    val insets = window.insets
                    if (origin != null) {
                        host.updateScreenGeometry(
                            java.awt.Rectangle(
                                origin.x + insets.left + (b.left / density).toInt(),
                                origin.y + insets.top + (b.top / density).toInt(),
                                (b.width / density).toInt(),
                                (b.height / density).toInt(),
                            ),
                            window.graphicsConfiguration?.bounds,
                        )
                    }
                }
            }
    ) {
        if (status is AaStatus.Projecting) {
            ProjectionSurface(host)
        } else {
            IdleViewport(status, onReconnect = { host.reconnect() })
        }
    }
}

@Composable
private fun ProjectionSurface(host: AndroidAutoHost) {
    val tick by host.frames.tick.collectAsState()
    val geometry = (host.status.collectAsState().value as? AaStatus.Projecting)?.geometry ?: return
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(host) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        // Wheel or two-finger trackpad swipe: a pinch about the pointer (see windowZoom).
                        if (event.type == PointerEventType.Scroll) {
                            event.changes.firstOrNull()?.let { c ->
                                host.windowZoom(c.position.x / w, c.position.y / h, -c.scrollDelta.y)
                                c.consume()
                            }
                            continue
                        }
                        // Only the main button is a finger; a right-click or two-finger tap is not a touch.
                        if (event.type == PointerEventType.Press && event.changes.any { it.type == PointerType.Mouse } &&
                            !event.buttons.isPrimaryPressed) continue
                        for (c in event.changes) {
                            val kind = when {
                                event.type == PointerEventType.Press && c.pressed && !c.previousPressed -> EvdevTouch.Kind.DOWN
                                !c.pressed && c.previousPressed -> EvdevTouch.Kind.UP
                                c.pressed && event.type == PointerEventType.Move -> EvdevTouch.Kind.MOVE
                                else -> null
                            } ?: continue
                            host.windowTouch(kind, c.id.value, c.position.x / w, c.position.y / h)
                            c.consume()
                        }
                    }
                }
            }
    ) {
        tick // read here, in the draw pass, so a new frame redraws without recomposing anything
        drawIntoCanvas { canvas ->
            host.frames.withFrame { image ->
                if (image == null) return@withFrame
                val src = Rect.makeXYWH(
                    geometry.contentLeft.toFloat(), geometry.contentTop.toFloat(),
                    geometry.contentWidth.toFloat(), geometry.contentHeight.toFloat(),
                )
                val dst = Rect.makeWH(size.width, size.height)
                canvas.nativeCanvas.drawImageRect(image, src, dst, SamplingMode.LINEAR, null, true)
            }
        }
    }
}

@Composable
private fun IdleViewport(status: AaStatus, onReconnect: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(Modifier.fillMaxSize()) {
        AnalogueClock(Modifier.fillMaxSize())
        val line = statusLine(status)
        if (line != null) {
            val tappable = status is AaStatus.Ended || status is AaStatus.Retrying
            Text(
                line,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = BODY,
                fontFamily = theme.font,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .then(if (tappable) Modifier.clickable { onReconnect() } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** One plain sentence about where Android Auto is — or nothing, when there is nothing worth saying. */
internal fun statusLine(status: AaStatus): String? = when (status) {
    AaStatus.WaitingForPhone -> "Connect your phone by USB for Android Auto"
    AaStatus.Disabled -> null
    is AaStatus.Unavailable -> status.reason
    is AaStatus.NoPermission -> "Phone found, but DASH-AA may not open it — run packaging/install.sh"
    is AaStatus.Switching -> "Starting Android Auto on the phone…"
    is AaStatus.Connecting -> status.step
    is AaStatus.Projecting -> null
    is AaStatus.Ended -> "Android Auto closed on the phone · tap to reconnect"
    is AaStatus.Retrying -> "Android Auto stopped (${status.reason}) · retrying — tap to retry now"
}
