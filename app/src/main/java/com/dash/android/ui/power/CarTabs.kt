package com.dash.android.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.DashApplication
import com.dash.android.power.CarPowerSettings
import com.dash.android.power.LeaveAction
import com.dash.android.power.OutputWhen
import com.dash.android.power.PowerOutput
import com.dash.android.power.PowerPreferences
import com.dash.android.power.PowerSettings
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.display.Note
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.settings.content.FitPresetSegment
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import kotlinx.coroutines.launch

/*
 * Power › Car and Power › Outputs (DASH-AA 1.1.7): the car's own power — what the ignition, the locks and
 * the battery do to DASH, and the switched outputs DASH raises for a relay module (amplifiers, dashcam,
 * lights). Shared code: native follows as it is.
 */

@Composable
private fun rememberCar(): Pair<CarPowerSettings, ((CarPowerSettings) -> CarPowerSettings) -> Unit> {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { PowerPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = PowerSettings())
    val scope = rememberCoroutineScope()
    return settings.car to { change -> scope.launch { prefs.update { it.copy(car = change(it.car)) } } }
}

/** Power › Car: the stages — when the screen comes on, when DASH leaves and how, and the battery's protection. */
@Composable
fun CarPowerContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val (car, update) = rememberCar()
    val stage by app.carPower.stage.collectAsState()
    val inputs by app.carPower.inputs.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Car")
        if (!inputs.carPresent) {
            Note("No module reports the ignition (ignition_state) or a power module's word (head_unit_awake), so DASH " +
                "is always Ready: the screen stays on and nothing switches by itself. These settings take effect as soon as one does.")
        }
        InfoRows(buildList {
            add("Now" to if (inputs.carPresent) stage.label else "Ready")
            inputs.ignition?.let { add("Ignition" to it.replaceFirstChar { c -> c.uppercase() }) }
            inputs.locked?.let { add("Doors" to if (it) "Locked" else "Unlocked") }
            inputs.volts?.let { add("Car battery" to "%.1f V".format(it)) }
            inputs.awake?.let { add("Power module" to if (it) "Keep awake" else "Leave") }
        })

        SettingsSectionHeader("Screen")
        SettingBlock(
            name = "Screen on at",
            help = "The ignition position that turns the screen on and plays the splash. With the ignition off it is dark, " +
                "and a touch lights it for a minute.",
            control = {
                PresetSegment(listOf("Accessory", "On"), if (car.readyAt == "on") 1 else 0, controlWidth) { i ->
                    update { it.copy(readyAt = if (i == 1) "on" else "accessory") }
                }
            },
        )
        SettingBlock(
            name = "Ignition drop-out",
            help = "How long the ignition may vanish before it counts as off. Many cars cut it while the starter turns.",
            control = {
                Stepper(
                    value = "${car.ignitionGraceSeconds} s",
                    modifier = controlWidth,
                    onMinus = { update { it.copy(ignitionGraceSeconds = (it.ignitionGraceSeconds - 1).coerceAtLeast(0)) } },
                    onPlus = { update { it.copy(ignitionGraceSeconds = (it.ignitionGraceSeconds + 1).coerceAtMost(15)) } },
                )
            },
        )

        SettingsSectionHeader("Leaving the car")
        SettingBlock(
            name = "When the car is left",
            help = when (car.leave) {
                LeaveAction.SLEEP -> "DASH sleeps — quick to wake when a module wakes it. On a machine that cannot sleep, it shuts down."
                LeaveAction.SHUT_DOWN -> "The machine shuts down — no drain on the car's battery at all."
                LeaveAction.STAY_ON -> "DASH stays awake with the screen dark. Only the battery protection below stops it."
            },
            fullWidthControl = true,
            control = {
                PresetSegment(LeaveAction.entries.map { it.label }, car.leave.ordinal, Modifier.fillMaxWidth()) { i ->
                    update { it.copy(leave = LeaveAction.entries[i]) }
                }
            },
        )
        if (car.leave != LeaveAction.STAY_ON) {
            SettingBlock(
                name = "When the doors lock",
                help = "Leave as soon as a module reports the car locked.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (car.leaveWhenLocked) 1 else 0, controlWidth) { i -> update { it.copy(leaveWhenLocked = i == 1) } }
                },
            )
            val choices = CarPowerSettings.LEAVE_AFTER_CHOICES
            SettingBlock(
                name = "After the ignition is off for",
                help = "Leave anyway after this long — the doors never locked, or the car was unlocked and never started. " +
                    "Unlocking or opening a door starts it again.",
                fullWidthControl = true,
                control = {
                    FitPresetSegment(choices.map { if (it == 0) "Never" else "$it min" }, choices.indexOf(car.leaveAfterMinutes).coerceAtLeast(0)) { i ->
                        update { it.copy(leaveAfterMinutes = choices[i]) }
                    }
                },
            )
        }

        SettingsSectionHeader("The car's battery")
        val volts = CarPowerSettings.LOW_VOLTAGE_CHOICES
        SettingBlock(
            name = "Shut down below",
            help = "If the car's battery stays below this for ${CarPowerSettings.LOW_VOLTAGE_SECONDS} seconds with the ignition off, " +
                "DASH shuts down so the car still starts. Needs a module reporting battery_voltage.",
            fullWidthControl = true,
            control = {
                FitPresetSegment(volts.map { if (it == 0) "Never" else "${it / 10}.${it % 10} V" }, volts.indexOf(car.lowVoltageTenths).coerceAtLeast(0)) { i ->
                    update { it.copy(lowVoltageTenths = volts[i]) }
                }
            },
        )

        SettingsSectionHeader("Workshop")
        SettingBlock(
            name = "Keep awake",
            help = "Stay awake whatever the ignition and locks say — for working on the car. The battery protection still stands.",
            control = {
                PresetSegment(listOf("Off", "On"), if (car.keepAwake) 1 else 0, controlWidth) { i -> update { it.copy(keepAwake = i == 1) } }
            },
        )
    }
}

/** Power › Outputs: eight switched outputs a relay module carries out — each named, given its moment, and timed. */
@Composable
fun PowerOutputsContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val (car, update) = rememberCar()
    val values by app.controller.systemState.values.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Outputs")
        Note("Each output is a signal, power_output_1 to _8, that a relay module listens for — an amplifier's remote " +
            "wire, a dashcam, lights. Awake: whenever DASH is. Ignition: with the ignition on. Engine: with the engine " +
            "running. Sound: with the ignition on and DASH's sound ready, so an amplifier never thumps. Before DASH " +
            "sleeps or shuts down, every output goes off, the last first.")
        car.outputs.forEachIndexed { n, o ->
            fun set(change: (PowerOutput) -> PowerOutput) = update { c -> c.copy(outputs = c.outputs.mapIndexed { k, x -> if (k == n) change(x) else x }) }
            val live = values["power_output_${n + 1}"]?.value == "true"
            SettingsSectionHeader("Output ${n + 1}${o.name.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}${if (live) " · on" else ""}")
            SettingBlock(
                name = "On",
                fullWidthControl = true,
                control = {
                    FitPresetSegment(OutputWhen.entries.map { it.label }, o.on.ordinal) { i -> set { it.copy(on = OutputWhen.entries[i]) } }
                },
            )
            if (o.on != OutputWhen.OFF) {
                val name = remember(n) { mutableStateOf(o.name) }
                LaunchedEffect(o.name) { if (name.value != o.name) name.value = o.name }
                SettingBlock(
                    name = "Name",
                    control = { KeyboardField(name, "Output ${n + 1}'s name", controlWidth, placeholder = "Front amplifier") { set { it.copy(name = name.value.trim().take(24)) } } },
                )
                SettingBlock(
                    name = "Delay on",
                    control = {
                        Stepper("${o.delayOn} s", modifier = controlWidth,
                            onMinus = { set { it.copy(delayOn = (it.delayOn - 1).coerceAtLeast(0)) } },
                            onPlus = { set { it.copy(delayOn = (it.delayOn + 1).coerceAtMost(120)) } })
                    },
                )
                SettingBlock(
                    name = "Delay off",
                    control = {
                        Stepper("${o.delayOff} s", modifier = controlWidth,
                            onMinus = { set { it.copy(delayOff = (it.delayOff - 1).coerceAtLeast(0)) } },
                            onPlus = { set { it.copy(delayOff = (it.delayOff + 1).coerceAtMost(600)) } })
                    },
                )
            }
        }
    }
}
