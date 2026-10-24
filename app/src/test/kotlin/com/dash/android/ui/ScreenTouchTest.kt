package com.dash.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dash.android.aa.input.EvdevTouch.Kind
import com.dash.android.system.DesktopScale
import com.dash.android.ui.touch.linux.TouchFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.2.5: the touchscreen handed to Compose as a touchscreen, and the scale from the screen's own size. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ScreenTouchTest {

    /**
     * ScreenTouch reaches the window's scene through four private names inside Compose. **If this fails
     * after a Compose update, a finger is a mouse again** — find the new names before releasing it.
     */
    @Test fun `the window's scene is where ScreenTouch looks for it`() {
        fun declared(owner: Class<*>, name: String): Class<*> = owner.getDeclaredField(name).type
        val panel = declared(androidx.compose.ui.awt.ComposeWindow::class.java, "composePanel")
        val container = declared(panel, "_composeContainer")
        val mediator = declared(container, "mediator")
        val getScene = mediator.getMethod("access\$getScene", mediator)
        assertEquals(androidx.compose.ui.scene.ComposeScene::class.java, getScene.returnType)
    }

    @Test fun `fingers become press, move and release, every finger in every event`() {
        val f = TouchFrames()
        val a = f.next(Kind.DOWN, 0, Offset(10f, 10f), 1000)!!
        assertEquals(PointerEventType.Press, a.type); assertEquals(1, a.pointers.size)
        val b = f.next(Kind.DOWN, 1, Offset(50f, 50f), 1010)!!
        assertEquals(2, b.pointers.size, "the second finger's press carries the first")
        assertNull(f.next(Kind.MOVE, 0, Offset(10f, 10f), 1020), "a frame where nothing moved is no event")
        val m = f.next(Kind.MOVE, 0, Offset(10f, 40f), 1030)!!
        assertEquals(PointerEventType.Move, m.type); assertEquals(Offset(10f, 40f), m.pointers.first().position)
        val up = f.next(Kind.UP, 1, Offset(50f, 50f), 1040)!!
        assertEquals(PointerEventType.Release, up.type)
        assertEquals(listOf(true, false), up.pointers.map { it.pressed }, "the lifted finger is the one not pressed")
        assertTrue(f.anyDown)
        f.next(Kind.UP, 0, Offset(10f, 40f), 1050)
        assertTrue(!f.anyDown)
        assertNull(f.next(Kind.MOVE, 3, Offset(0f, 0f), 1060), "a finger never put down moves nothing")
    }

    @Test fun `the Surface's late lift is dated to the last movement, a real stop is not`() {
        val f = TouchFrames()
        f.next(Kind.DOWN, 0, Offset(0f, 300f), 0)
        f.next(Kind.MOVE, 0, Offset(0f, 200f), 23)
        assertEquals(24, f.next(Kind.UP, 0, Offset(0f, 200f), 23 + 128)!!.timeMillis, "128 ms late: the screen's lag")
        f.next(Kind.DOWN, 0, Offset(0f, 300f), 1000)
        f.next(Kind.MOVE, 0, Offset(0f, 200f), 1023)
        assertEquals(1523, f.next(Kind.UP, 0, Offset(0f, 200f), 1523)!!.timeMillis, "half a second still: a real stop")
        f.next(Kind.DOWN, 0, Offset(0f, 300f), 2000)
        assertEquals(2095, f.next(Kind.UP, 0, Offset(0f, 300f), 2095)!!.timeMillis, "a tap keeps its own length")
    }

    /** A quick flick as the Surface sends it — moves every 23 ms, the lift 110 ms late — through Compose's own list. */
    @Test fun `a quick flick from the Surface coasts in Compose's own list, and presses nothing`() {
        val state = LazyListState()
        var taps = 0
        val scene = ImageComposeScene(400, 800, Density(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(400) { Box(Modifier.fillMaxWidth().height(50.dp).clickable { taps++ }) }
            }
        }
        var clock = 0L
        fun frame() { clock += 16; scene.render(clock * 1_000_000) }
        frame()
        val f = TouchFrames()
        fun send(kind: Kind, y: Float, ms: Long) {
            val e = f.next(kind, 0, Offset(200f, y), ms) ?: return
            scene.sendPointerEvent(
                e.type, e.pointers.map { ComposeScenePointer(PointerId(it.id), it.position, it.pressed, PointerType.Touch) },
                timeMillis = e.timeMillis,
            )
            frame()
        }
        send(Kind.DOWN, 700f, 10_000)
        send(Kind.MOVE, 640f, 10_023)
        send(Kind.MOVE, 520f, 10_046)
        send(Kind.MOVE, 380f, 10_069)
        send(Kind.UP, 380f, 10_069 + 110)
        val letGo = state.firstVisibleItemIndex
        repeat(40) { frame() }
        assertTrue(state.firstVisibleItemIndex > letGo + 5, "coasted from $letGo to ${state.firstVisibleItemIndex}")
        assertEquals(0, taps)
        scene.close()
    }

    private fun edid(wMm: Int, hMm: Int, wCm: Int = 0, hCm: Int = 0) = ByteArray(128).also {
        it[21] = wCm.toByte(); it[22] = hCm.toByte()
        it[66] = (wMm and 0xFF).toByte(); it[67] = (hMm and 0xFF).toByte()
        it[68] = (((wMm shr 8) shl 4) or (hMm shr 8)).toByte()
    }

    @Test fun `the scale from the screen's own size`() {
        // The Surface Pro 3, as it reports itself: 254 × 169 mm, 2160 × 1440 — 216 to the inch.
        assertEquals(1.35f, DesktopScale.edidScale(edid(254, 169), 2160, 1440))
        assertEquals(1.35f, DesktopScale.edidScale(edid(254, 169), 1440, 2160), "a mode reported turned")
        // A plain 24-inch 1080p monitor: ~92 to the inch.
        assertEquals(0.57f, DesktopScale.edidScale(edid(531, 299), 1920, 1080))
        // No detailed size: the basic block's centimetres.
        assertNotNull(DesktopScale.edidScale(edid(0, 0, 25, 17), 2160, 1440))
        // A television giving its shape for a size (one byte the ratio, the other 0), or nothing at all: no answer.
        assertNull(DesktopScale.edidScale(edid(0, 0, 79, 0), 1920, 1080))
        assertNull(DesktopScale.edidScale(edid(0, 0), 1920, 1080))
        assertNull(DesktopScale.edidScale(ByteArray(10), 1920, 1080))
    }
}
