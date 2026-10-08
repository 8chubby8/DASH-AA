package com.dash.android.ui.rotation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * DASH turned inside its own window (DASH-AA 1.1.5) — Rotation's fallback for a machine where no display
 * program can turn the screen. [content] is laid out in the turned shape (tall for portrait in a wide
 * window) and drawn turned [quarterTurns] anticlockwise, the same sense as a display program's own turn.
 *
 * Everything DASH draws turns with it, the Android Auto picture included, and touches through the window
 * follow the turn by themselves; the touchscreen reader is told separately (`AndroidAutoHost.contentTurn`).
 * Other programs and the desktop do not turn — that needs a display program.
 *
 * **For native:** not needed — Android always turns the screen itself.
 */
@Composable
fun TurnedWindow(quarterTurns: Int, content: @Composable () -> Unit) {
    val turns = quarterTurns and 3
    Layout(content, Modifier.fillMaxSize().clipToBounds()) { measurables, c ->
        val w = c.maxWidth
        val h = c.maxHeight
        val inner = if (turns % 2 == 1) Constraints.fixed(h, w) else Constraints.fixed(w, h)
        val placeable = measurables.first().measure(inner)
        layout(w, h) {
            placeable.placeWithLayer((w - placeable.width) / 2, (h - placeable.height) / 2) {
                rotationZ = -90f * turns
            }
        }
    }
}
