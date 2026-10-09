package com.dash.android.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarPowerTest {
    private val s = CarPowerSettings()
    private fun car(ignition: String? = "off", locked: Boolean? = false, volts: Double? = 12.6, awake: Boolean? = null, door: Boolean = false, engine: Boolean = false, sound: Boolean = true) =
        CarInputs(ignition, engine, locked, door, volts, awake, sound)

    @Test fun `no car module, always Ready`() {
        val c = CarStages()
        assertNull(c.step(0, CarInputs(), s))
        assertEquals(CarStage.READY, c.stage)
        assertNull(c.step(3_600_000, CarInputs(), s))
        assertEquals(CarStage.READY, c.stage)
    }

    @Test fun `unlock, ignition, crank, off, lock — the day in the car`() {
        val c = CarStages()
        assertNull(c.step(0, car(), s))
        assertEquals(CarStage.WAKING, c.stage)
        c.step(5_000, car("accessory"), s)
        assertEquals(CarStage.READY, c.stage)
        // The starter cuts the ignition for two seconds: still Ready.
        c.step(6_000, car("off"), s)
        assertEquals(CarStage.READY, c.stage)
        c.step(8_000, car("on", engine = true), s)
        assertEquals(CarStage.READY, c.stage)
        // Ignition off for good.
        c.step(100_000, car("off"), s)
        c.step(104_000, car("off"), s)
        assertEquals(CarStage.PARKED, c.stage)
        // Locked: leave, as the user chose (sleep).
        assertEquals(LeaveAction.SLEEP, c.step(110_000, car("off", locked = true), s))
        assertEquals(CarStage.STOPPING, c.stage)
        // Nothing more until it wakes.
        assertNull(c.step(120_000, car("on"), s))
        c.woke(200_000)
        assertEquals(CarStage.WAKING, c.stage)
    }

    @Test fun `unlocked and never started leaves after the minutes, a door opening starts the clock again`() {
        val c = CarStages()
        c.step(0, car(), s)
        c.step(9 * 60_000L, car(door = true), s)
        c.step(9 * 60_000L + 1000, car(), s)
        assertNull(c.step(15 * 60_000L, car(), s))
        assertEquals(LeaveAction.SLEEP, c.step(19 * 60_000L + 1000, car(), s))
    }

    @Test fun `a machine that cannot sleep shuts down instead, Stay on never leaves`() {
        val c = CarStages()
        c.step(0, car(), s)
        assertEquals(LeaveAction.SHUT_DOWN, c.step(11 * 60_000L, car(), s, canSleep = false))
        val stay = CarStages()
        stay.step(0, car(), s.copy(leave = LeaveAction.STAY_ON))
        assertNull(stay.step(600 * 60_000L, car(locked = true), s.copy(leave = LeaveAction.STAY_ON)))
    }

    @Test fun `a low battery shuts down after half a minute, but never while the ignition is on`() {
        val c = CarStages()
        c.step(0, car("on", volts = 11.0), s)
        assertNull(c.step(60_000, car("on", volts = 11.0), s))
        c.step(70_000, car("off", volts = 11.0), s)
        c.step(75_000, car("off", volts = 11.0), s)
        assertNull(c.step(90_000, car("off", volts = 11.0), s))
        assertEquals(LeaveAction.SHUT_DOWN, c.step(106_000, car("off", volts = 11.0), s.copy(leave = LeaveAction.STAY_ON, keepAwake = true)))
    }

    @Test fun `a power module's word outranks the rest, but not straight after waking`() {
        val c = CarStages()
        c.step(0, car("on", awake = true), s)
        assertEquals(LeaveAction.SLEEP, c.step(1000, car("on", awake = false), s))
        c.woke(100_000)
        assertNull(c.step(105_000, car("off", awake = false), s))
        assertNull(c.step(110_000, car("off", awake = true, locked = true), s))   // held up despite the lock
        assertEquals(LeaveAction.SLEEP, c.step(125_000, car("off", awake = false), s))
    }

    @Test fun `outputs wait their delays, and go off last first`() {
        val o = CarOutputs()
        val outs = List(CarPowerSettings.OUTPUTS) { PowerOutput() }.toMutableList()
        outs[0] = PowerOutput("Head unit", OutputWhen.AWAKE)
        outs[1] = PowerOutput("Front amp", OutputWhen.SOUND, delayOn = 10, delayOff = 2)
        outs[2] = PowerOutput("Dashcam", OutputWhen.ENGINE)
        val set = s.copy(outputs = outs)
        assertEquals(mapOf(1 to true), o.step(0, CarStage.WAKING, car(), set))
        assertTrue(o.step(1000, CarStage.READY, car("on"), set).isEmpty())
        assertEquals(mapOf(2 to true), o.step(11_000, CarStage.READY, car("on"), set))
        assertEquals(mapOf(3 to true), o.step(12_000, CarStage.READY, car("on", engine = true), set))
        // Sound restarting: the amplifier waits its 2 s off-delay.
        assertTrue(o.step(13_000, CarStage.READY, car("on", engine = true, sound = false), set).isEmpty())
        assertEquals(mapOf(2 to false), o.step(15_000, CarStage.READY, car("on", engine = true, sound = false), set))
        assertEquals(listOf(3, 1), o.allOff())
    }
}
