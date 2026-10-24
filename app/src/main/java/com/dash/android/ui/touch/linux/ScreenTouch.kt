package com.dash.android.ui.touch.linux

import android.util.Log
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import com.dash.android.aa.input.EvdevTouch
import com.dash.android.display.UserActivity
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

/**
 * The touchscreen, handed to DASH's screen as a touchscreen (roadmap 1.2.5).
 *
 * **Why.** DASH's window runs under XWayland: Compose for Desktop draws only through X11 (its graphics
 * layer needs it — tried 2026-10-24 on JetBrains' Wayland toolkit, which Compose cannot draw on), and X11
 * as Java uses it has no touch at all — the display program turns each finger into a mouse. So on Linux
 * a finger was a mouse to Compose: no scrolling by dragging, no flick, a mouse's idea of a tap. Android
 * hands Compose real touches, and Compose's own scrolling, flinging and tapping are built for them.
 *
 * **What this does.** It reads the touchscreen straight from the kernel ([EvdevTouch], the reader Android
 * Auto already uses), and hands every finger to Compose's scene as a real touch — more than one finger
 * included — so Compose behaves exactly as it does on Android. The mouse the display program makes from
 * the same finger is dropped before it reaches DASH. A real mouse or touchpad is untouched.
 *
 * **One thing it corrects.** The Surface's touchscreen reports each lift 90–130 ms after the finger has
 * gone, holding the touch still at its last point meanwhile (measured 2026-10-24, its raw reports beside
 * DASH's log). Compose reads a finger that stops for 40 ms before lifting as one that stopped — so every
 * quick flick would die. A lift that follows the last movement within [LIFT_LAG_MS] is dated to that
 * movement; one later than that is a finger that really did stop, and keeps its own time.
 *
 * **What it relies on — and what can break it.** Compose accepts touches through `ComposeScene`, but
 * the window keeps its scene private, so it is reached by reflection ([sceneOf]): four private names
 * inside Compose 1.9.3. **A Compose update can break this.** Nothing updates on its own (DASH-AA Linux
 * installs only what Roger publishes), and `ScreenTouchTest` fails at once if the names have gone — so
 * a Compose update is tested before it is released. If the scene cannot be reached even so, this says so
 * in the log and does nothing: the finger reaches DASH as a mouse, as before 1.2.5 (capability
 * detection — never a crash). Likewise with no touchscreen, or one DASH may not read.
 *
 * **Where a touch lands.** As for Android Auto: the touchscreen covers the screen DASH is on; its corners
 * are turned as the display program turns the picture ([turn], 1.1.5); DASH turning its own picture
 * inside its window is Compose's to undo, as it is for the mouse.
 *
 * **For native:** nothing — Android hands Compose real touches itself. Linux only.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ScreenTouch(
    private val window: ComposeWindow,
    /** How the display program has turned the main screen: anticlockwise quarter turns, and mirroring. */
    private val turn: () -> Pair<Int, Boolean>,
) {
    @Volatile private var lastTouchNanos = 0L
    private val fingers = TouchFrames()

    /** True when touches now reach Compose as touches; false when they still arrive as a mouse. */
    fun start(): Boolean {
        val scene = sceneOf(window) ?: return false
        val reader = EvdevTouch { kind, slot, fx, fy, millis -> heard(scene, kind, slot, fx, fy, millis) }
        if (!runCatching { reader.start() }.getOrDefault(false)) {
            Log.i(TAG, "no touchscreen DASH can read — a finger reaches DASH as a mouse")
            return false
        }
        // The display program's mouse made from the same finger: dropped while a finger is down, and for a
        // moment after (it arrives later than the kernel's own report). A wheel is never a finger.
        Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                if (event is MouseEvent && event !is MouseWheelEvent &&
                    (fingers.anyDown || System.nanoTime() - lastTouchNanos < ECHO_NS)) return
                super.dispatchEvent(event)
            }
        })
        Log.i(TAG, "touchscreen ${reader.device?.name} reaches DASH as a touchscreen")
        return true
    }

    private fun heard(scene: ComposeScene, kind: EvdevTouch.Kind, slot: Long, fx: Float, fy: Float, millis: Long) {
        lastTouchNanos = System.nanoTime()
        UserActivity.touch()  // the window's own listener no longer sees these
        val (quarterTurns, mirrored) = turn()
        val (sx, sy) = EvdevTouch.panelToScreen(fx, fy, quarterTurns, mirrored)
        SwingUtilities.invokeLater {
            val event = fingers.next(kind, slot, scenePosition(sx, sy), millis) ?: return@invokeLater
            runCatching {
                scene.sendPointerEvent(
                    event.type,
                    event.pointers.map { ComposeScenePointer(PointerId(it.id), it.position, it.pressed, PointerType.Touch) },
                    timeMillis = event.timeMillis,
                )
            }.onFailure { Log.w(TAG, "a touch did not reach DASH: ${it.message}") }
        }
    }

    /** A point given as fractions of the screen, in the scene's own pixels. Called on the window's thread. */
    private fun scenePosition(sx: Float, sy: Float): Offset {
        val config = window.graphicsConfiguration
        val screen = config.bounds
        val scale = config.defaultTransform.scaleX.toFloat()
        val origin = window.contentPane.locationOnScreen
        return Offset(
            (screen.x + sx * screen.width - origin.x) * scale,
            (screen.y + sy * screen.height - origin.y) * scale,
        )
    }

    companion object {
        private const val TAG = "DashTouch"
        /** Longer than the Surface's 90–130 ms lift lag; shorter than a deliberate stop before lifting. */
        const val LIFT_LAG_MS = 150L
        /** How long after the last touch a mouse event is still the display program's copy of it. */
        private const val ECHO_NS = 400_000_000L

        /**
         * The window's Compose scene — private to Compose, reached by four names inside it (1.9.3):
         * `ComposeWindow.composePanel` → `_composeContainer` → `mediator` → `access$getScene`.
         * Null, and said once in the log, when any of them has gone (a Compose update).
         */
        fun sceneOf(window: ComposeWindow): ComposeScene? = runCatching {
            val panel = field(window, "composePanel")
            val container = field(panel, "_composeContainer")
            val mediator = field(container, "mediator")
            mediator.javaClass.getMethod("access\$getScene", mediator.javaClass).invoke(null, mediator) as ComposeScene
        }.onFailure {
            Log.w(TAG, "Compose's window is not as DASH knows it (a Compose update?) — a finger reaches DASH as a mouse: $it")
        }.getOrNull()

        private fun field(owner: Any, name: String): Any =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!
    }
}

/**
 * The fingers on the screen, frame by frame, as Compose wants them: every event carries every finger
 * down, the one that changed among them. Its own class so it can be tested without a screen.
 */
internal class TouchFrames {
    data class Finger(val id: Long, val position: Offset, val pressed: Boolean)
    data class Event(val type: PointerEventType, val pointers: List<Finger>, val timeMillis: Long)

    private class Down(val id: Long, var position: Offset, var lastMoveMillis: Long, var moved: Boolean = false)

    private val down = LinkedHashMap<Long, Down>()
    private var nextId = FIRST_ID

    val anyDown: Boolean get() = synchronized(down) { down.isNotEmpty() }

    fun next(kind: EvdevTouch.Kind, slot: Long, at: Offset, millis: Long): Event? = synchronized(down) {
        when (kind) {
            EvdevTouch.Kind.DOWN -> {
                down[slot] = Down(nextId++, at, millis)
                Event(PointerEventType.Press, fingers(), millis)
            }
            EvdevTouch.Kind.MOVE -> {
                val d = down[slot] ?: return null
                if (d.position == at) return null
                d.position = at; d.lastMoveMillis = millis; d.moved = true
                Event(PointerEventType.Move, fingers(), millis)
            }
            EvdevTouch.Kind.UP -> {
                val d = down[slot] ?: return null
                // The touchscreen's late lift (see ScreenTouch): dated to the last movement when it is only that.
                val lifted = if (d.moved && millis - d.lastMoveMillis <= ScreenTouch.LIFT_LAG_MS) d.lastMoveMillis + 1 else millis
                val all = fingers().map { if (it.id == d.id) it.copy(pressed = false) else it }
                down.remove(slot)
                Event(PointerEventType.Release, all, lifted)
            }
        }
    }

    private fun fingers() = down.values.map { Finger(it.id, it.position, true) }

    private companion object {
        /** Clear of the ids Compose gives the mouse. */
        const val FIRST_ID = 1L shl 32
    }
}
