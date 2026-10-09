package com.dash.android.power

import com.dash.android.display.UserActivity
import com.dash.android.power.linux.LinuxPower
import com.dash.android.ui.power.batteryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerTest {
    @Test fun `the kernel's sleep, marked in brackets, in plain words`() {
        assertEquals(SleepKind.STANDBY, LinuxPower.sleepKind("[s2idle]\n"))
        assertEquals(SleepKind.DEEP, LinuxPower.sleepKind("s2idle [deep]\n"))
        assertEquals(SleepKind.STANDBY, LinuxPower.sleepKind("[s2idle] deep\n"))
        assertNull(LinuxPower.sleepKind(null))
        assertNull(LinuxPower.sleepKind(""))
    }

    @Test fun `a desktop is named, a bare display program is not`() {
        assertEquals("GNOME", LinuxPower.desktop("GNOME"))
        assertEquals("GNOME", LinuxPower.desktop("ubuntu:GNOME"))
        assertEquals("KDE Plasma", LinuxPower.desktop("KDE"))
        assertNull(LinuxPower.desktop("labwc:wlroots"))
        assertNull(LinuxPower.desktop("cage"))
        assertNull(LinuxPower.desktop(null))
    }

    @Test fun `left alone sleeps once, never while projecting, never at Never`() {
        assertTrue(PowerRules.shouldSleep(60_000, 61_000, alreadyFor = false, busy = false, canSleep = true))
        assertFalse(PowerRules.shouldSleep(60_000, 59_000, alreadyFor = false, busy = false, canSleep = true))
        assertFalse(PowerRules.shouldSleep(60_000, 61_000, alreadyFor = true, busy = false, canSleep = true))
        assertFalse(PowerRules.shouldSleep(60_000, 61_000, alreadyFor = false, busy = true, canSleep = true))
        assertFalse(PowerRules.shouldSleep(60_000, 61_000, alreadyFor = false, busy = false, canSleep = false))
        assertFalse(PowerRules.shouldSleep(0, 10_000_000, alreadyFor = false, busy = false, canSleep = true))
    }

    @Test fun `the battery in a few words`() {
        assertEquals("Charging — full in 23 min", batteryStatus(Battery(91, true, false, minutesToFull = 23), false))
        assertEquals("On the battery — 2 h 5 min left", batteryStatus(Battery(50, false, false, minutesToEmpty = 125), true))
        assertEquals("Full", batteryStatus(Battery(100, false, true), false))
        assertEquals("Plugged in, not charging", batteryStatus(Battery(80, false, false), false))
    }

    @Test fun `the charger's profile and the battery's follow the plug, the lid is taken only when DASH decides`() {
        val power = PretendPower()
        val display = ScreensOnly()
        val settings = MutableStateFlow(PowerSettings(profileOnCharger = "performance", profileOnBattery = "power-saver"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var prepared = 0
        PowerRules(power, display, settings, scope, busy = { false }, prepare = { prepared++ }).start()
        waitFor { power.s.value.activeProfile == "performance" }
        power.s.value = power.s.value.copy(onBattery = true)
        waitFor { power.s.value.activeProfile == "power-saver" }
        // Changed by hand while on the battery: left alone until the plug changes.
        power.setProfile("balanced")
        Thread.sleep(300)
        assertEquals("balanced", power.s.value.activeProfile)

        assertFalse(power.lidHeld)
        settings.value = settings.value.copy(lid = LidAction.SCREEN_OFF)
        waitFor { power.lidHeld }
        power.s.value = power.s.value.copy(lid = Lid(closed = true))
        waitFor { display.screensOn == false }
        power.s.value = power.s.value.copy(lid = Lid(closed = false))
        waitFor { display.screensOn == true }
        settings.value = settings.value.copy(lid = LidAction.SLEEP)
        waitFor { !power.lidHeld }

        val before = UserActivity.lastAt
        Thread.sleep(5)
        power.prepare(); power.woke()
        assertEquals(1, prepared)
        assertTrue(UserActivity.lastAt > before)
        scope.cancel()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }
}

/** Only the screens' on and off, which is all the lid rule touches. */
private class ScreensOnly : com.dash.android.display.DisplaySystem {
    @Volatile var screensOn: Boolean? = null
    override val state = MutableStateFlow(com.dash.android.display.DisplayState(true, ""))
    override fun start() {}
    override fun rotate(orientation: com.dash.android.ui.rotation.DashOrientation?) {}
    override fun arrange(screens: List<com.dash.android.display.Screen>, keep: Boolean) {}
    override fun setBrightness(screenId: String, level: Float) {}
    override fun setNightLight(on: Boolean, kelvin: Int) {}
    override fun setScreensOn(on: Boolean) { screensOn = on }
    override fun mapTouchscreen(deviceId: String, screenId: String) {}
    override fun restore() {}
}
