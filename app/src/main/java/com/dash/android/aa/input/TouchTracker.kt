package com.dash.android.aa.input

import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.AaMessages
import com.dash.android.aa.protocol.VideoGeometry

/**
 * Turns finger movements into the touch events Android Auto expects — Android's own MotionEvent
 * sequence: the first finger down is DOWN, each further finger POINTER_DOWN, every movement MOVE with
 * all fingers, a finger lifting while others remain POINTER_UP, and the last one UP.
 *
 * Positions arrive as fractions of the viewport (0..1) from whichever source saw the finger — the
 * window's pointer events or the touchscreen read directly ([EvdevTouch]) — and leave in the phone's
 * coordinates: the phone's interface area — the content area of the video frame, in its own
 * coordinates, which is also the touchscreen size DASH-AA declares (see [AaMessages]).
 */
class TouchTracker(private val send: (pointers: List<AaMessages.Pointer>, actionIndex: Int, action: Int) -> Unit) {

    private data class Finger(val source: Long, val aaId: Int, var x: Float, var y: Float)

    private val fingers = mutableListOf<Finger>()
    @Volatile var geometry: VideoGeometry? = null

    @Synchronized
    fun down(source: Long, x: Float, y: Float) {
        if (fingers.any { it.source == source }) return move(source, x, y)
        val id = (0..9).first { n -> fingers.none { it.aaId == n } }
        fingers += Finger(source, id, x, y)
        val index = fingers.size - 1
        emit(if (fingers.size == 1) Aa.TOUCH_DOWN else Aa.TOUCH_POINTER_DOWN, index)
    }

    @Synchronized
    fun move(source: Long, x: Float, y: Float) {
        val f = fingers.firstOrNull { it.source == source } ?: return
        if (f.x == x && f.y == y) return
        f.x = x; f.y = y
        emit(Aa.TOUCH_MOVE, 0)
    }

    @Synchronized
    fun up(source: Long) {
        val index = fingers.indexOfFirst { it.source == source }
        if (index < 0) return
        emit(if (fingers.size == 1) Aa.TOUCH_UP else Aa.TOUCH_POINTER_UP, index)
        fingers.removeAt(index)
    }

    /** Lift everything — the session ended or the viewport went away mid-gesture. */
    @Synchronized
    fun cancelAll() {
        while (fingers.isNotEmpty()) up(fingers.last().source)
    }

    private fun emit(action: Int, actionIndex: Int) {
        val g = geometry ?: return
        val pointers = fingers.map { f ->
            AaMessages.Pointer(
                id = f.aaId,
                x = (f.x.coerceIn(0f, 1f) * (g.contentWidth - 1)).toInt(),
                y = (f.y.coerceIn(0f, 1f) * (g.contentHeight - 1)).toInt(),
            )
        }
        if (action != Aa.TOUCH_MOVE) {
            val p = pointers.getOrNull(actionIndex)
            android.util.Log.i("DashAaTouch", "${actionName(action)} at ${p?.x},${p?.y} (${pointers.size} finger${if (pointers.size == 1) "" else "s"})")
        }
        send(pointers, actionIndex, action)
    }

    private fun actionName(a: Int) = when (a) {
        Aa.TOUCH_DOWN -> "down"; Aa.TOUCH_UP -> "up"; Aa.TOUCH_POINTER_DOWN -> "finger down"
        Aa.TOUCH_POINTER_UP -> "finger up"; else -> "action $a"
    }
}
