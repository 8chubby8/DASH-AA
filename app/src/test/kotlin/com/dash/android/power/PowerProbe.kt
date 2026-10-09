package com.dash.android.power

import com.dash.android.power.linux.LinuxPower
import kotlin.test.Test

/** Opt-in (-Dpower=1): what this machine's own power services tell DASH. Read only — nothing is slept, switched or limited. */
class PowerProbe {
    @Test fun `read this machine's power`() {
        if (System.getProperty("power") == null) return
        val p = LinuxPower()
        p.start()
        val until = System.currentTimeMillis() + 5000
        while (p.state.value.actions.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
        Thread.sleep(500)
        println("POWER ${p.state.value}")
    }
}
