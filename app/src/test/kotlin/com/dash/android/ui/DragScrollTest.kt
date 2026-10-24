package com.dash.android.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dash.android.ui.common.dragScroll
import com.dash.android.ui.common.flickSpeed
import com.dash.android.ui.common.verticalDragScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 1.2.5: a pointer drag scrolls a settings page, as a finger does on Android, and a tap stays a tap. */
@OptIn(ExperimentalComposeUiApi::class)
class DragScrollTest {
    private var clock = 0L

    private fun ImageComposeScene.frames(n: Int = 1) = repeat(n) { clock += 16; render(clock * 1_000_000) }

    private fun ImageComposeScene.pointer(
        type: PointerEventType, at: Offset, pressed: Boolean, pointerType: PointerType = PointerType.Mouse,
    ) {
        clock += 16
        sendPointerEvent(type, at, timeMillis = clock, type = pointerType, buttons = PointerButtons(isPrimaryPressed = pressed))
        render(clock * 1_000_000)
    }

    /** Presses at [from], moves to [to] in [steps] even steps, lets go there. */
    private fun ImageComposeScene.drag(from: Offset, to: Offset, steps: Int = 10, pointerType: PointerType = PointerType.Mouse) {
        pointer(PointerEventType.Press, from, true, pointerType)
        for (i in 1..steps) pointer(PointerEventType.Move, from + (to - from) * (i / steps.toFloat()), true, pointerType)
        pointer(PointerEventType.Release, to, false, pointerType)
    }

    private class Page(val scene: ImageComposeScene, val scroll: ScrollState, val clicks: IntArray)

    /** A 400 px page of twenty 100 px rows that each count their taps; [row] lets a test put something in row 0. */
    private fun page(row: @androidx.compose.runtime.Composable () -> Unit = {}): Page {
        val scroll = ScrollState(0)
        val clicks = IntArray(20)
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            Column(Modifier.fillMaxSize().verticalDragScroll(scroll)) {
                repeat(20) { i ->
                    Box(Modifier.fillMaxWidth().height(100.dp).clickable { clicks[i]++ }) { if (i == 0) row() }
                }
            }
        }
        scene.frames()
        return Page(scene, scroll, clicks)
    }

    @Test fun `a drag scrolls the page and presses nothing`() {
        val p = page()
        p.scene.drag(Offset(150f, 350f), Offset(150f, 50f), steps = 30)
        p.scene.frames(5)
        assertTrue(p.scroll.value in 250..400, "scrolled ${p.scroll.value}")
        assertEquals(0, p.clicks.sum(), "no row was tapped")
        p.scene.close()
    }

    @Test fun `a tap stays a tap`() {
        val p = page()
        p.scene.pointer(PointerEventType.Press, Offset(150f, 150f), true)
        p.scene.pointer(PointerEventType.Move, Offset(152f, 153f), true)
        p.scene.pointer(PointerEventType.Release, Offset(152f, 153f), false)
        p.scene.frames()
        assertEquals(0, p.scroll.value)
        assertEquals(1, p.clicks[1])
        p.scene.close()
    }

    @Test fun `a flick coasts on, and a touch stops it without pressing`() {
        val p = page()
        p.scene.drag(Offset(150f, 300f), Offset(150f, 150f), steps = 5)
        val letGo = p.scroll.value
        p.scene.frames(6)
        val coasting = p.scroll.value
        assertTrue(coasting > letGo, "kept moving after letting go ($letGo → $coasting)")
        p.scene.pointer(PointerEventType.Press, Offset(150f, 200f), true)
        p.scene.pointer(PointerEventType.Release, Offset(150f, 200f), false)
        val stopped = p.scroll.value
        p.scene.frames(30)
        assertEquals(stopped, p.scroll.value, "stopped by the touch")
        assertEquals(0, p.clicks.sum(), "the stopping touch pressed nothing")
        p.scene.close()
    }

    @Test fun `a flick still coasts when the lift comes late`() {
        // The Surface's screen: quick moves, then the lift ~125 ms after the last one.
        val p = page()
        p.scene.pointer(PointerEventType.Press, Offset(150f, 300f), true)
        for (i in 1..5) p.scene.pointer(PointerEventType.Move, Offset(150f, 300f - i * 30f), true)
        clock += 110
        p.scene.pointer(PointerEventType.Release, Offset(150f, 150f), false)
        val letGo = p.scroll.value
        p.scene.frames(10)
        assertTrue(p.scroll.value > letGo + 50, "coasted ($letGo → ${p.scroll.value})")
        p.scene.close()
    }

    @Test fun `flick speed`() {
        // Even moves of 30 px every 16 ms, lifted at once: ~1875 px/s.
        val t = listOf(0L, 16, 32, 48, 64); val y = listOf(0f, 30f, 60f, 90f, 120f)
        assertEquals(1875f, flickSpeed(t, y, 64), 1f)
        // Lifted 128 ms late, as the Surface's screen does: still a flick. Held still a fifth of a second: none.
        assertEquals(1875f, flickSpeed(t, y, 64 + 128), 1f)
        assertEquals(0f, flickSpeed(t, y, 64 + 250))
        // A quick flick the Surface sent (1.2.5b's log): four moves, then the lift 128 ms after the last.
        assertTrue(flickSpeed(listOf(0L, 65, 106, 148, 171), listOf(0f, -22f, -103f, -176f, -216f), 299) < -1500f)
        // Moves that arrived in one burst with one time: reach back past them.
        assertEquals(1500f, flickSpeed(listOf(0L, 40, 40, 40), listOf(0f, 20f, 40f, 60f), 40), 1f)
        assertEquals(0f, flickSpeed(listOf(0L), listOf(0f), 0))
    }

    @Test fun `a drag across the page is not the page's`() {
        val p = page()
        p.scene.drag(Offset(20f, 150f), Offset(280f, 160f))
        p.scene.frames()
        assertEquals(0, p.scroll.value)
        p.scene.close()
    }

    @Test fun `something that drags for itself keeps its drag`() {
        var dragged = 0f
        val p = page {
            Box(Modifier.fillMaxSize().draggable(rememberDraggableState { dragged += it }, Orientation.Vertical))
        }
        p.scene.drag(Offset(150f, 90f), Offset(150f, 10f))
        p.scene.frames()
        assertEquals(0, p.scroll.value, "the page stayed put")
        assertTrue(dragged < -40f, "the control got the drag ($dragged)")
        p.scene.close()
    }

    @Test fun `a real touch is left to Compose, so it never scrolls twice`() {
        val p = page()
        p.scene.drag(Offset(150f, 350f), Offset(150f, 50f), steps = 30, pointerType = PointerType.Touch)
        p.scene.frames(5)
        // Compose's own scrolling takes a touch: one finger's worth, less its slop — not twice that.
        assertTrue(p.scroll.value in 200..400, "scrolled ${p.scroll.value}")
        p.scene.close()
    }

    @Test fun `a lazy list drags too`() {
        val state = LazyListState()
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().dragScroll(state, Orientation.Vertical)) {
                items(100) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }
        scene.frames()
        scene.drag(Offset(150f, 350f), Offset(150f, 50f), steps = 30)
        scene.frames(5)
        assertTrue(state.firstVisibleItemIndex >= 5, "at item ${state.firstVisibleItemIndex}")
        scene.close()
    }
}
