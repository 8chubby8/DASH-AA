package com.dash.android.aa

import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.AaVideoMode
import com.dash.android.aa.protocol.ProtoMessage
import com.dash.android.aa.protocol.VideoGeometry
import com.dash.android.aa.protocol.proto
import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AaUnitTest {

    @Test fun `geometry keeps the viewport's exact shape`() {
        for ((w, h) in listOf(1680 to 996, 1920 to 1080, 1280 to 1024, 2560 to 800, 900 to 1600)) {
            val mode = if (h > w) AaVideoMode.P1080.forPortrait() else AaVideoMode.P1080
            val g = VideoGeometry.fit(mode, w, h)
            val want = w.toDouble() / h
            val got = g.contentWidth.toDouble() / g.contentHeight
            assertTrue(abs(got - want) / want < 0.005, "$w×$h → ${g.contentWidth}×${g.contentHeight}")
            assertTrue(g.marginWidth == 0 || g.marginHeight == 0, "margins on one axis only")
            assertEquals(0, g.marginWidth % 2); assertEquals(0, g.marginHeight % 2)
        }
    }

    @Test fun `a 16 by 9 viewport needs no margins`() {
        val g = VideoGeometry.fit(AaVideoMode.P720, 1280, 720)
        assertEquals(0 to 0, g.marginWidth to g.marginHeight)
    }

    @Test fun `system_commands gear values map to Android Auto gears`() {
        assertEquals(Aa.GEAR_PARK, AaBridge.gearCode("park"))
        assertEquals(Aa.GEAR_REVERSE, AaBridge.gearCode("reverse"))
        assertEquals(Aa.GEAR_NEUTRAL, AaBridge.gearCode("neutral"))
        assertEquals(Aa.GEAR_DRIVE, AaBridge.gearCode("drive"))
        assertEquals(3, AaBridge.gearCode("3"))
        assertEquals(null, AaBridge.gearCode("banana"))
    }

    @Test fun `speed is sent in metres per second times 1000`() {
        assertEquals(27777, AaBridge.kmhToMmPerSecond(100.0))
    }

    @Test fun `sensors are advertised only when a module feeds them`() {
        val state = SystemState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = AaBridge(state, scope)
        assertEquals(listOf(Aa.SENSOR_DRIVING_STATUS), bridge.sensorsFor(AaSettings()))
        state.store("headlights_on", "true")
        state.store("vehicle_speed", "48")
        val sensors = bridge.sensorsFor(AaSettings())
        assertTrue(Aa.SENSOR_NIGHT in sensors && Aa.SENSOR_CAR_SPEED in sensors)
        val night = ProtoMessage.parse(bridge.eventFor(Aa.SENSOR_NIGHT, AaSettings())!!)
        assertEquals(true, night.message(10)!!.bool(1))
        // A forced day overrides the headlights.
        val day = ProtoMessage.parse(bridge.eventFor(Aa.SENSOR_NIGHT, AaSettings(nightSource = NightSource.DAY))!!)
        assertEquals(false, day.message(10)!!.bool(1))
        scope.cancel()
    }

    @Test fun `a steering-wheel press from before the connection is never replayed to the phone`() {
        val state = SystemState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = AaBridge(state, scope)
        state.fire("media_next", null)                 // pressed before the phone connected
        Thread.sleep(20)
        val keys = mutableListOf<Int>()
        val job = bridge.attach({ AaSettings() }, { _, _ -> }, { synchronized(keys) { keys += it } })
        Thread.sleep(200)
        state.fire("media_play_pause", null)           // pressed while connected
        Thread.sleep(200)
        job.cancel(); scope.cancel()
        assertEquals(listOf(Aa.KEY_MEDIA_PLAY_PAUSE), synchronized(keys) { keys.toList() })
    }

    @Test fun `protobuf round trip, including negatives and nesting`() {
        val bytes = proto { uint(1, 300); int(2, -5); string(3, "DASH"); message(4) { bool(1, true) } }
        val m = ProtoMessage.parse(bytes)
        assertEquals(300, m.int(1)); assertEquals(-5, m.int(2)); assertEquals("DASH", m.string(3))
        assertEquals(true, m.message(4)!!.bool(1))
    }
}
