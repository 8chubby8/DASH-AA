package com.dash.android.ui.common

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A **mouse** dragging a list scrolls it, with a flick that coasts to a stop — as a finger does
 * (roadmap 1.2.5; Roger: a mouse drag should scroll too).
 *
 * Compose scrolls a list when a finger drags it, on Android and on the desktop alike, but deliberately
 * ignores a mouse drag. This handles the mouse drag Compose leaves alone; a real touch is Compose's own,
 * so nothing scrolls twice. (1.2.5a–b used this for the touchscreen too, which on Linux reached DASH as a
 * mouse; from 1.2.5c the touchscreen is handed to Compose as a touchscreen — `ScreenTouch` — and only a
 * real mouse, or a finger when that cannot be done, comes through here.)
 *
 * It behaves as Android's does:
 * - **A tap stays a tap.** Nothing happens until the pointer has moved past the touch slop along the
 *   list's direction; from then on the drag is the list's, and the row under the finger is not pressed.
 * - **What drags for itself keeps its drag.** A slider, the colour picker, the crop editor, a text
 *   field — anything inside that takes the drag first — is left alone, and so is a drag mostly across
 *   the list's direction, which belongs to a list running the other way.
 * - **A touch stops a flick**, and that touch presses nothing.
 *
 * **For native:** nothing of Linux in it — take it as it is, and a mouse on an Android head unit
 * scrolls the same way. A finger is never touched by it.
 */
fun Modifier.dragScroll(state: ScrollableState, orientation: Orientation): Modifier =
    pointerInput(state, orientation) {
        val decay = splineBasedDecay<Float>(this)
        val maxVelocity = viewConfiguration.maximumFlingVelocity
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                if (state.isScrollInProgress) {
                    // Caught mid-flick: stop it, and let the touch that stopped it press nothing.
                    down.consume()
                    launch { state.stopScroll() }
                }

                val slop = viewConfiguration.touchSlop
                val vertical = orientation == Orientation.Vertical
                // Where the pointer was along the list, and when: the speed of a flick is read from these.
                val times = ArrayList<Long>()
                val places = ArrayList<Float>()
                fun along(o: Offset) = if (vertical) o.y else o.x
                times += down.uptimeMillis
                places += along(down.position)
                // What the pointer did, for the detail log: the speed comes from what the window system
                // hands DASH, and a touchscreen made into a mouse does not always hand it what a mouse would.
                val heard = StringBuilder()
                var dragging = false
                var outcome = "tap"
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null) { outcome = "lost"; break }
                    // The press itself comes round again on this pass; a row taking it is only a row being pressed.
                    if (change.changedToDownIgnoreConsumed()) continue
                    if (heard.length < 900) heard.append(' ')
                        .append(change.uptimeMillis - down.uptimeMillis).append(':')
                        .append(along(change.position - down.position).toInt())
                        .append(if (change.pressed) "" else "^").append(if (change.isConsumed) "c" else "")
                    if (!change.pressed) {
                        if (dragging) {
                            change.consume()
                            val v = flickSpeed(times, places, change.uptimeMillis).coerceIn(-maxVelocity, maxVelocity)
                            outcome = "flick ${v.toInt()} px/s"
                            launch { state.scroll { fling(-v, decay) } }
                        }
                        break
                    }
                    times += change.uptimeMillis
                    places += along(change.position)
                    if (dragging) {
                        state.dispatchRawDelta(-along(change.positionChange()))
                        change.consume()
                        continue
                    }
                    // Something inside took this drag for itself.
                    if (change.isConsumed) { outcome = "taken by the control under it"; break }
                    val moved = change.position - down.position
                    val ahead = along(moved)
                    val across = if (vertical) moved.x else moved.y
                    if (abs(ahead) > slop && abs(ahead) >= abs(across)) {
                        dragging = true
                        // Only what lies past the slop moves the list, so it doesn't jump on taking hold.
                        state.dispatchRawDelta(-(ahead - sign(ahead) * slop))
                        change.consume()
                    } else if (abs(across) > slop) {
                        outcome = "across, not this list's"
                        break
                    }
                }
                Log.d(TAG, "${if (vertical) "vertical" else "horizontal"} ${outcome}; slop ${slop.toInt()}; ms:px$heard")
            }
        }
    }

private const val TAG = "DashDragScroll"

/** Still this long before lifting is a deliberate stop, not a flick: see [flickSpeed]. */
private const val STILL_MS = 200

/**
 * How fast the pointer was moving when it let go, in px/s along the list: its travel over the last tenth of a
 * second of movement. Not Compose's own tracker — that one counts a finger as stopped when 40 ms pass between
 * the last move and the lift, and a touchscreen often lifts later than that. Measured on the Surface (1.2.5b,
 * its raw digitiser beside DASH's log): every touch, the fastest flick included, is held still at its last
 * point for 90–130 ms after the finger has gone before the lift is reported. So a pointer counts as stopped
 * only when it was still for [STILL_MS] before lifting — longer than that lag, shorter than a deliberate hold.
 */
internal fun flickSpeed(times: List<Long>, places: List<Float>, liftedAt: Long): Float {
    if (times.size < 2) return 0f
    val last = times.lastIndex
    if (liftedAt - times[last] > STILL_MS) return 0f
    var first = last
    while (first > 0 && times[last] - times[first - 1] <= 100) first--
    // Moves that arrived together in one burst carry one time; reach back for one that didn't.
    if (first == last || times[last] == times[first]) first = (first - 1).coerceAtLeast(0)
    val ms = times[last] - times[first]
    if (ms <= 0) return 0f
    return (places[last] - places[first]) * 1000f / ms
}

/** `verticalScroll(state)` that a pointer can also drag — see [dragScroll]. */
fun Modifier.verticalDragScroll(state: ScrollState): Modifier =
    dragScroll(state, Orientation.Vertical).verticalScroll(state)

/** `horizontalScroll(state)` that a pointer can also drag — see [dragScroll]. */
fun Modifier.horizontalDragScroll(state: ScrollState): Modifier =
    dragScroll(state, Orientation.Horizontal).horizontalScroll(state)

/** Coasts from [velocity] to a stop, as Compose's own fling does; ends early at the list's end. */
private suspend fun ScrollScope.fling(velocity: Float, decay: DecayAnimationSpec<Float>) {
    if (abs(velocity) < 1f) return
    var last = 0f
    AnimationState(0f, velocity).animateDecay(decay) {
        val delta = value - last
        last = value
        if (abs(delta - scrollBy(delta)) > 0.5f) cancelAnimation()
    }
}
