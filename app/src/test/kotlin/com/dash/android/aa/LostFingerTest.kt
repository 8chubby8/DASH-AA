package com.dash.android.aa

import com.dash.android.aa.input.EvdevTouch
import com.dash.android.aa.input.EvdevTouch.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 1.2.5: a finger the Surface's touchscreen loses for a moment in a two-finger gesture is not lifted. */
class LostFingerTest {
    private val heard = mutableListOf<String>()
    private val fingers = EvdevTouch.Fingers { kind, key, _, _, millis -> heard += "$kind $key @$millis" }

    @Test fun `the rotate the Surface broke, 2026-10-24 — the lost finger carries on`() {
        // Two fingers down for a rotate; the screen reports the first lifted, then a "new" one in its place 110 ms on.
        fingers.down(0, 0.70f, 0.34f, 15_973)
        fingers.down(1, 0.72f, 0.69f, 15_984)
        fingers.move(0, 0.717f, 0.520f, 17_500)
        fingers.up(0, 17_622)
        assertEquals(138L, fingers.due(17_622 + 12), "waiting for it")
        fingers.down(0, 0.716f, 0.518f, 17_733)
        assertEquals(listOf("DOWN 0 @15973", "DOWN 1 @15984", "MOVE 0 @17500", "MOVE 0 @17733"), heard,
            "no lift and no new finger reached the phone: the same finger moved on")
        assertNull(fingers.due(17_800))
    }

    @Test fun `a finger of a pinch really lifted is passed on, with its own time`() {
        fingers.down(0, 0.3f, 0.3f, 0)
        fingers.down(1, 0.6f, 0.6f, 5)
        fingers.up(0, 300)
        fingers.up(1, 310)
        assertEquals(listOf("DOWN 0 @0", "DOWN 1 @5"), heard)
        fingers.due(449)
        assertEquals(2, heard.size, "not yet")
        fingers.due(460)
        assertEquals(listOf("DOWN 0 @0", "DOWN 1 @5", "UP 0 @300", "UP 1 @310"), heard)
    }

    @Test fun `a finger alone lifts at once — taps, double taps and flicks unchanged`() {
        fingers.down(0, 0.5f, 0.5f, 0)
        fingers.up(0, 90)
        fingers.down(0, 0.5f, 0.5f, 200)   // a double tap: the second comes down where the first lifted
        fingers.up(0, 290)
        fingers.down(0, 0.5f, 0.8f, 1000)
        fingers.move(0, 0.5f, 0.2f, 1100)  // a flick
        fingers.up(0, 1200)
        assertEquals(
            listOf("DOWN 0 @0", "UP 0 @90", "DOWN 1 @200", "UP 1 @290", "DOWN 2 @1000", "MOVE 2 @1100", "UP 2 @1200"),
            heard,
        )
        assertNull(fingers.due(1200))
    }

    @Test fun `a touch far from the lost finger is a new finger`() {
        fingers.down(0, 0.2f, 0.2f, 0)
        fingers.down(1, 0.8f, 0.8f, 5)
        fingers.up(0, 100)
        fingers.down(0, 0.5f, 0.9f, 150)
        fingers.due(260)
        assertEquals(listOf("DOWN 0 @0", "DOWN 1 @5", "DOWN 2 @150", "UP 0 @100"), heard)
    }
}
