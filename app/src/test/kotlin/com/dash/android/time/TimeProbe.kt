package com.dash.android.time

import com.dash.android.time.linux.TimeDated
import kotlin.test.Test

/** Opt-in (-Dtime=1): what this machine's time service tells DASH, and the zone for this connection. Read only — nothing is set. */
class TimeProbe {
    @Test fun `read this machine's clock`() {
        if (System.getProperty("time") == null) return
        val t = TimeDated()
        t.start()
        val until = System.currentTimeMillis() + 5000
        while (!t.state.value.available && System.currentTimeMillis() < until) Thread.sleep(100)
        println("TIME ${t.state.value.copy(zones = emptyList())} zones=${t.state.value.zones.size}")
        println("ZONE for this connection: ${ZoneFinder.find(null)}")
        println("ZONE for Leeds: ${ZoneFinder.find(com.dash.android.weather.ManualLocation("Leeds", 53.8, -1.55))}")
    }
}
