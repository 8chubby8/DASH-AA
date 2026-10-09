package com.dash.android.power

import android.util.Log
import com.dash.android.core.SystemState
import com.dash.android.display.DisplaySystem
import com.dash.android.display.UserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/*
 * The car's power (DASH-AA 1.1.7, Roger 2026-10-09: "these are not just computer power profiles but
 * profiles of power for the car itself as well"). A factory head unit's stages, run from what modules
 * report, with every choice the user's:
 *
 *   Waking  — awake with the ignition off (a module woke the machine as the doors unlocked): screen dark,
 *             phone and modules connecting quietly.
 *   Ready   — the ignition is on: screen on, the splash, outputs come on after their delays.
 *   Parked  — the ignition has gone off: screen dark, DASH still running, outputs go off.
 *   Stopping— about to sleep or shut down: every output off, in reverse order, before anything else.
 *   Off     — the last word, as DASH shuts down.
 *
 * DASH announces the stage as `power_state`, and each switched output as `power_output_1` … `_8`, so a
 * relay module only has to listen. With no module reporting the ignition or `head_unit_awake`, DASH is
 * simply Ready, as it has always been — a laptop on a desk changes nothing.
 *
 * Shared code: native follows as it is; only what "sleep" and "shut down" do is the platform's.
 */

@Serializable
data class CarPowerSettings(
    /** The ignition position that turns the screen on: "accessory" or "on". */
    val readyAt: String = "accessory",
    /** What happens when the car is left. */
    val leave: LeaveAction = LeaveAction.SLEEP,
    /** Leave as soon as the doors are locked. */
    val leaveWhenLocked: Boolean = true,
    /** Minutes awake with the ignition off before leaving anyway; 0 never. */
    val leaveAfterMinutes: Int = 10,
    /** How long the ignition may drop out before it counts as off — the starter motor cuts it on many cars. */
    val ignitionGraceSeconds: Int = 3,
    /** Shut down when the car's battery stays below this, in tenths of a volt; 0 never. */
    val lowVoltageTenths: Int = 118,
    /** Workshop: stay awake whatever the car says — the battery protection still stands. */
    val keepAwake: Boolean = false,
    val outputs: List<PowerOutput> = List(OUTPUTS) { PowerOutput() },
) {
    companion object {
        const val OUTPUTS = 8
        val LEAVE_AFTER_CHOICES = listOf(0, 2, 5, 10, 15, 30, 60)
        val LOW_VOLTAGE_CHOICES = listOf(0, 115, 118, 120, 122)
        /** The battery has to stay low this long — a starter motor pulls it down for a moment every start. */
        const val LOW_VOLTAGE_SECONDS = 30
    }
}

enum class LeaveAction(val label: String) { SLEEP("Sleep"), SHUT_DOWN("Shut down"), STAY_ON("Stay on") }

/** One switched output — an amplifier's remote wire, a dashcam, lights — named and timed by the user. */
@Serializable
data class PowerOutput(
    val name: String = "",
    val on: OutputWhen = OutputWhen.OFF,
    /** Seconds after its moment comes before it switches on — amplifiers last, after the head unit settles. */
    val delayOn: Int = 0,
    /** Seconds after its moment goes before it switches off. */
    val delayOff: Int = 0,
)

enum class OutputWhen(val label: String) {
    OFF("Off"),
    AWAKE("Awake"),
    IGNITION("Ignition"),
    ENGINE("Engine"),
    /** With the ignition and DASH's sound ready — an amplifier's remote wire that never thumps. */
    SOUND("Sound"),
}

enum class CarStage(val wire: String, val label: String) {
    WAKING("waking", "Waking — awake, the ignition off"),
    READY("ready", "Ready — the ignition on"),
    PARKED("parked", "Parked — the ignition off"),
    STOPPING("stopping", "Stopping"),
    OFF("off", "Off"),
}

/** What modules say about the car at one moment, read from the sourceless store. */
data class CarInputs(
    /** off / accessory / on, or null when no module reports it. */
    val ignition: String? = null,
    val engineRunning: Boolean = false,
    val locked: Boolean? = null,
    val doorOpen: Boolean = false,
    val volts: Double? = null,
    /** A power module's own decision, outranking everything else; null when none sends it. */
    val awake: Boolean? = null,
    val soundReady: Boolean = false,
) {
    /** A module is reporting the car's power at all — without one, DASH is simply Ready. */
    val carPresent: Boolean get() = ignition != null || awake != null

    companion object {
        fun from(values: Map<String, String>) = CarInputs(
            ignition = values["ignition_state"]?.lowercase(),
            engineRunning = values["engine_running"].isTrue(),
            locked = values["doors_locked"]?.let { it.isTrue() },
            doorOpen = values.any { (k, v) -> k.startsWith("door_") && k.endsWith("_open") && v.isTrue() },
            volts = values["battery_voltage"]?.toDoubleOrNull(),
            awake = values["head_unit_awake"]?.let { it.isTrue() },
            soundReady = values["sound_ready"].isTrue(),
        )

        internal fun String?.isTrue() = this != null && (equals("true", true) || this == "1" || equals("on", true))
    }
}

/**
 * The stages on their own — no machine, no screens, no clock but the one given — so the rules can be
 * tested by the second. [step] moves the stage and says when DASH should leave, and how.
 */
class CarStages {
    var stage = CarStage.READY
        private set
    /** When the ignition-off spell began (Waking or Parked), or someone came back to the car. */
    private var offSince = 0L
    private var lastIgnition = Long.MIN_VALUE / 2
    private var lowSince: Long? = null
    private var wasLocked: Boolean? = null
    private var wasDoorOpen = false
    /** A car module has been heard since DASH started — the first word starts DASH in Waking. */
    private var seen = false
    private var wokeAt = Long.MIN_VALUE / 2

    /** Advance to [now]. Returns the leave to carry out, or null to carry on. */
    fun step(now: Long, i: CarInputs, s: CarPowerSettings, canSleep: Boolean = true): LeaveAction? {
        if (stage == CarStage.STOPPING || stage == CarStage.OFF) return null
        if (!i.carPresent) { stage = CarStage.READY; seen = false; return null }
        if (!seen) { seen = true; stage = CarStage.WAKING; offSince = now }

        val ignitionOn = when (s.readyAt) {
            "on" -> i.ignition == "on"
            else -> i.ignition == "on" || i.ignition == "accessory"
        }
        if (ignitionOn) lastIgnition = now
        // A drop shorter than the grace — the starter motor — is not the ignition going off.
        val on = ignitionOn || (stage == CarStage.READY && now - lastIgnition < s.ignitionGraceSeconds * 1000L)

        // Someone coming back — unlocking, opening a door — starts the ignition-off clock again.
        val unlocked = wasLocked == true && i.locked == false
        val opened = !wasDoorOpen && i.doorOpen
        val lockedNow = wasLocked == false && i.locked == true
        wasLocked = i.locked ?: wasLocked
        wasDoorOpen = i.doorOpen

        val next = when {
            on -> CarStage.READY
            stage == CarStage.READY -> CarStage.PARKED
            else -> stage
        }
        if (next != stage && next != CarStage.READY) offSince = now
        if (unlocked || opened) offSince = now
        stage = next

        // The car's battery, whatever else: a dead battery strands the driver. Not while driving — a low
        // battery with the engine running is a charging fault, and the screen is needed.
        val low = s.lowVoltageTenths > 0 && i.volts != null && i.volts * 10 < s.lowVoltageTenths
        lowSince = if (low && stage != CarStage.READY) lowSince ?: now else null
        if (lowSince != null && now - lowSince!! >= CarPowerSettings.LOW_VOLTAGE_SECONDS * 1000L) {
            Log.i(TAG, "the car's battery is low (${i.volts} V) — shutting down")
            return leave(LeaveAction.SHUT_DOWN, canSleep)
        }

        // A power module's word outranks everything — but just after waking, what it said before the sleep
        // is still in the store until it reconnects and speaks again, so its "no" waits a moment.
        if (i.awake == false && now - wokeAt >= WAKE_GRACE_MS) return leave(s.leave, canSleep)
        if (stage == CarStage.READY) return null
        if (i.awake == true) return null
        if (s.keepAwake || s.leave == LeaveAction.STAY_ON) return null
        if (s.leaveWhenLocked && lockedNow) return leave(s.leave, canSleep)
        if (s.leaveAfterMinutes > 0 && now - offSince >= s.leaveAfterMinutes * 60_000L) return leave(s.leave, canSleep)
        return null
    }

    private fun leave(how: LeaveAction, canSleep: Boolean): LeaveAction {
        stage = CarStage.STOPPING
        return if (how == LeaveAction.SLEEP && !canSleep) LeaveAction.SHUT_DOWN else how
    }

    /** The machine is going to sleep, whoever asked. */
    fun stopping() { stage = CarStage.STOPPING }

    /** DASH is closing. */
    fun off() { stage = CarStage.OFF }

    /** The machine has woken: awake with the ignition off until a module says otherwise. */
    fun woke(now: Long) {
        stage = CarStage.WAKING
        offSince = now
        wokeAt = now
        lowSince = null
    }

    internal companion object {
        const val TAG = "DashCarPower"
        const val WAKE_GRACE_MS = 20_000L
    }
}

/** The switched outputs on their own: each one's moment, its delays, on and off. */
class CarOutputs {
    private val on = BooleanArray(CarPowerSettings.OUTPUTS)
    private val since = LongArray(CarPowerSettings.OUTPUTS)
    private val wanted = BooleanArray(CarPowerSettings.OUTPUTS)

    /** Advance to [now]; returns the outputs that changed, by number (1–8), and their new state. */
    fun step(now: Long, stage: CarStage, i: CarInputs, s: CarPowerSettings): Map<Int, Boolean> {
        val changed = mutableMapOf<Int, Boolean>()
        s.outputs.take(CarPowerSettings.OUTPUTS).forEachIndexed { n, o ->
            val want = wants(o.on, stage, i)
            if (want != wanted[n]) { wanted[n] = want; since[n] = now }
            val wait = (if (want) o.delayOn else o.delayOff) * 1000L
            if (on[n] != want && now - since[n] >= wait) { on[n] = want; changed[n + 1] = want }
        }
        return changed
    }

    /** Everything off at once, last first — before sleep or shut down. Returns those that were on, in that order. */
    fun allOff(): List<Int> = (CarPowerSettings.OUTPUTS - 1 downTo 0).filter { on[it] }.onEach { on[it] = false; wanted[it] = false }.map { it + 1 }

    fun isOn(number: Int) = on[number - 1]

    internal companion object {
        fun wants(w: OutputWhen, stage: CarStage, i: CarInputs): Boolean {
            val awake = stage == CarStage.WAKING || stage == CarStage.READY || stage == CarStage.PARKED
            val ready = stage == CarStage.READY
            return when (w) {
                OutputWhen.OFF -> false
                OutputWhen.AWAKE -> awake
                OutputWhen.IGNITION -> ready
                OutputWhen.ENGINE -> ready && i.engineRunning
                OutputWhen.SOUND -> ready && i.soundReady
            }
        }
    }
}

/**
 * The car's power carried out: the stages and outputs above, applied to the screens and the machine, and
 * announced to modules. Ticks twice a second, from the sourceless store — the same values every module
 * and the Signal Monitor see.
 */
class CarPower(
    private val state: SystemState,
    private val power: PowerSystem,
    private val display: DisplaySystem,
    private val settings: Flow<PowerSettings>,
    private val scope: CoroutineScope,
) {
    private val stages = CarStages()
    private val outputs = CarOutputs()
    private val _stage = MutableStateFlow(CarStage.READY)
    val stage: StateFlow<CarStage> = _stage
    private val _inputs = MutableStateFlow(CarInputs())
    val inputs: StateFlow<CarInputs> = _inputs

    /** The ignition has turned the screen on — the splash plays (interface.md, *Power and Wake Behaviour*). */
    val splash = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var screenOff = false
    private var lastTouchSeen = 0L

    fun start() {
        announce(CarStage.READY)
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.w(TAG, "car power: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val s = settings.first().car
        val i = CarInputs.from(state.values.value.mapValues { it.value.value })
        _inputs.value = i
        val before = stages.stage
        val leave = stages.step(now, i, s, canSleep = PowerAction.SLEEP in power.state.value.actions)
        if (stages.stage != before) onStage(before, stages.stage, i)
        if (leave != null) { carryOut(leave); return }
        outputs.step(now, stages.stage, i, s).forEach { (n, v) -> raiseOutput(n, v) }
        parkedTouch(now, i)
    }

    private fun onStage(from: CarStage, to: CarStage, i: CarInputs) {
        Log.i(TAG, "${from.wire} → ${to.wire}")
        announce(to)
        if (!i.carPresent) return
        when (to) {
            CarStage.READY -> if (screenOff || from != CarStage.READY) { screen(true); splash.tryEmit(Unit) }
            CarStage.WAKING, CarStage.PARKED -> screen(false)
            else -> Unit
        }
    }

    /** With the ignition off, a touch lights the screen for a minute — someone sitting in the parked car. */
    private fun parkedTouch(now: Long, i: CarInputs) {
        if (!i.carPresent || (stages.stage != CarStage.PARKED && stages.stage != CarStage.WAKING)) return
        val touched = UserActivity.lastAt
        if (screenOff && touched > lastTouchSeen) screen(true)
        else if (!screenOff && now - touched > PARKED_SCREEN_MS) screen(false)
    }

    private fun screen(on: Boolean) {
        screenOff = !on
        lastTouchSeen = UserActivity.lastAt
        display.setScreensOn(on)
        state.raise("screen_on", on.toString())
    }

    private suspend fun carryOut(leave: LeaveAction) {
        Log.i(TAG, "leaving the car: ${leave.label}")
        stopping()
        delay(SETTLE_MS)
        when (leave) {
            LeaveAction.SLEEP -> power.act(PowerAction.SLEEP)
            LeaveAction.SHUT_DOWN -> power.act(PowerAction.SHUT_DOWN)
            LeaveAction.STAY_ON -> Unit
        }
    }

    /** About to sleep or shut down, whoever asked: say so, then every output off, last first. Blocking and short. */
    fun stopping() {
        if (stages.stage != CarStage.STOPPING) { stages.stopping(); announce(CarStage.STOPPING) }
        outputs.allOff().forEach { raiseOutput(it, false); Thread.sleep(OUTPUT_GAP_MS) }
    }

    /** Awake again: the stages start from Waking, and the store is what modules say next. */
    fun woke() {
        stages.woke(System.currentTimeMillis())
        announce(stages.stage)
    }

    /** DASH is closing: the last word modules hear. */
    fun off() {
        stopping()
        stages.off()
        announce(CarStage.OFF)
        Thread.sleep(SETTLE_MS)
    }

    private fun announce(s: CarStage) {
        _stage.value = s
        state.raise("power_state", s.wire)
    }

    private fun raiseOutput(n: Int, on: Boolean) = state.raise("power_output_$n", on.toString())

    private companion object {
        const val TAG = "DashCarPower"
        const val TICK_MS = 500L
        const val SETTLE_MS = 400L
        const val OUTPUT_GAP_MS = 150L
        const val PARKED_SCREEN_MS = 60_000L
    }
}

/** DASH raising a signal itself (transport.md: what DASH "internally generates"): stored, and fired on a change. */
internal fun SystemState.raise(function: String, value: String) {
    if (current(function) == value) return
    store(function, value)
    fire(function, value)
}
