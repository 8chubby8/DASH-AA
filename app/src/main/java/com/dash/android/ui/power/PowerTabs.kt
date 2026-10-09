package com.dash.android.ui.power

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.power.Battery
import com.dash.android.power.LidAction
import com.dash.android.power.PowerAction
import com.dash.android.power.PowerPreferences
import com.dash.android.power.PowerSettings
import com.dash.android.power.PowerState
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.MAINBODY_LINE
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.display.ChoiceList
import com.dash.android.ui.display.ChoiceRow
import com.dash.android.ui.display.Note
import com.dash.android.ui.settings.content.FitPresetSegment
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch

/*
 * Power (DASH-AA 1.1.7): Shut Down & Restart, Sleep & Wake, Performance and Battery. With no desktop these
 * are the machine's only power settings, so they cover what a desktop's do — and, as everywhere, show only
 * what this machine has: a mini PC on the car's supply has no Battery to show and no lid; a machine with no
 * power-profiles service has no profiles.
 *
 * **For native:** the tabs are shared and follow as they are; native's PowerSystem reports the battery and
 * little else, so Android's own power menu stays the way to shut down, and the tabs say so.
 */

@Composable
private fun rememberPower(): Pair<DashApplication, PowerState> {
    val app = LocalContext.current.applicationContext as DashApplication
    val state by app.power.state.collectAsState()
    return app to state
}

@Composable
private fun rememberPowerSettings(): Pair<PowerSettings, ((PowerSettings) -> PowerSettings) -> Unit> {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { PowerPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = PowerSettings())
    val scope = rememberCoroutineScope()
    return settings to { change -> scope.launch { prefs.update(change) } }
}

/** Something that cannot be undone from the screen, asked once more. */
private data class Asking(val question: String, val yes: String, val run: () -> Unit)

/**
 * Power › Shut Down & Restart: the machine's sleep, hibernate, restart and shut down — whichever it allows —
 * and DASH's own: Leave DASH (with a desktop to go back to) and Restart DASH.
 */
@Composable
fun PowerActionsContent() {
    val (app, state) = rememberPower()
    var asking by remember { mutableStateOf<Asking?>(null) }
    var dashNote by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Shut Down & Restart")
        asking?.let { a -> Confirm(a, onNo = { asking = null }) }
        if (!state.available) Note(state.note)
        state.failure?.let { Note(it) }

        SettingsSectionHeader("The machine")
        if (state.actions.isEmpty() && state.available) Note("This machine will not let DASH sleep, restart or shut it down.")
        PowerAction.entries.filter { it in state.actions }.forEach { action ->
            val (question, help) = when (action) {
                PowerAction.SLEEP -> null to "Wakes with the power button${if (state.lid != null) " or by opening the lid" else ""}. " +
                    "The phone is let go first, and found again on waking."
                PowerAction.HIBERNATE -> null to "Everything is saved to disk and the machine switches off; it starts again where it left off. Slower than sleep, and uses no power at all."
                PowerAction.RESTART -> "Restart the machine?" to null
                PowerAction.SHUT_DOWN -> "Shut the machine down?" to null
            }
            ActionRow(action.label, help) {
                if (question == null) app.power.act(action) else asking = Asking(question, action.label) { app.power.act(action) }
            }
        }

        SettingsSectionHeader("DASH")
        state.desktop?.let { desktop ->
            ActionRow("Leave DASH", "Back to $desktop. DASH starts again from the app menu.") {
                asking = Asking("Leave DASH for $desktop?", "Leave DASH") { app.leave() }
            }
        }
        ActionRow("Restart DASH", "DASH closes and starts again. The phone and modules reconnect.") {
            asking = Asking("Restart DASH?", "Restart DASH") {
                if (!app.restart()) dashNote = "DASH could not start itself again here, so it has stayed open."
            }
        }
        dashNote?.let { Note(it) }
    }
}

/** Power › Sleep & Wake: the machine sleeping when left alone, the lid, and how this machine sleeps. */
@Composable
fun PowerSleepContent() {
    val (_, state) = rememberPower()
    val (settings, update) = rememberPowerSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Sleep & Wake")
        if (!state.available) { Note(state.note); return@Column }
        if (PowerAction.SLEEP in state.actions) {
            val choices = PowerSettings.SLEEP_CHOICES
            SettingBlock(
                name = "Sleep when left alone",
                help = "After this long with nobody touching DASH — never while a phone is projecting Android Auto. " +
                    "The screen goes dark first, as Display › Screen Blanking says.",
                fullWidthControl = true,
                control = {
                    FitPresetSegment(choices.map { sleepLabel(it) }, choices.indexOf(settings.sleepAfterMinutes).coerceAtLeast(0)) { i ->
                        update { it.copy(sleepAfterMinutes = choices[i]) }
                    }
                },
            )
        } else {
            Note("This machine will not let DASH put it to sleep, so it stays awake; the screen still goes dark as Display › Screen Blanking says.")
        }
        state.lid?.let {
            SettingBlock(
                name = "Closing the lid",
                help = when (settings.lid) {
                    LidAction.SLEEP -> "The machine sleeps — its own way, left as it is."
                    LidAction.SCREEN_OFF -> "The screens go dark and everything keeps running: the music plays on, the phone stays connected."
                    LidAction.NOTHING -> "Everything carries on as if the lid were open."
                },
                fullWidthControl = true,
                control = {
                    PresetSegment(LidAction.entries.map { it.label }, settings.lid.ordinal, Modifier.fillMaxWidth()) { i ->
                        update { it.copy(lid = LidAction.entries[i]) }
                    }
                },
            )
        }
        state.sleepKind?.let { kind ->
            SettingsSectionHeader("How this machine sleeps")
            InfoRows(listOf("Sleep" to kind.label))
            Note(kind.detail)
            if (PowerAction.HIBERNATE !in state.actions) {
                Note("It cannot hibernate (save everything to disk and switch off) as it is set up. That needs space on disk " +
                    "set aside for it, which only an administrator can arrange; when it has, Hibernate appears in Shut Down & Restart.")
            }
        }
    }
}

/** Power › Performance: the machine's profile now, and the one to use on the charger and on the battery. */
@Composable
fun PowerPerformanceContent() {
    val (app, state) = rememberPower()
    val (settings, update) = rememberPowerSettings()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Performance")
        if (state.profiles.isEmpty()) {
            Note("This machine has no power-profiles service (power-profiles-daemon, or tuned-ppd), so it runs as its " +
                "maker set it. Installing one brings the profiles here.")
            return@Column
        }
        state.failure?.let { Note(it) }
        SettingBlock(
            name = "Now",
            help = "What each one does is the machine's: the processor's speed and, on some laptops, the fans and power limits.",
            fullWidthControl = true,
            control = {
                ChoiceList(state.profiles.map { p -> ChoiceRow(p.label, p.detail.ifBlank { null }, p.id == state.activeProfile) { app.power.setProfile(p.id) } })
            },
        )
        val labels = listOf("As it is") + state.profiles.map { it.label }
        fun index(id: String?) = state.profiles.indexOfFirst { it.id == id } + 1
        fun idAt(i: Int) = if (i == 0) null else state.profiles[i - 1].id
        SettingBlock(
            name = if (state.battery != null) "On the charger" else "When DASH starts",
            help = if (state.battery != null) "Switched to when the charger is plugged in — in a car, while the engine runs." else null,
            fullWidthControl = true,
            control = { FitPresetSegment(labels, index(settings.profileOnCharger)) { i -> update { it.copy(profileOnCharger = idAt(i)) } } },
        )
        if (state.battery != null) {
            SettingBlock(
                name = "On the battery",
                help = "Switched to when the charger is unplugged.",
                fullWidthControl = true,
                control = { FitPresetSegment(labels, index(settings.profileOnBattery)) { i -> update { it.copy(profileOnBattery = idAt(i)) } } },
            )
        }
    }
}

/** Power › Battery: the charge, and the charge limit where the battery has one. */
@Composable
fun PowerBatteryContent() {
    val (app, state) = rememberPower()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Battery")
        val b = state.battery
        if (b == null) {
            Note(if (state.available) "This machine has no battery — it runs from its supply." else state.note)
            return@Column
        }
        state.failure?.let { Note(it) }
        InfoRows(listOf("Charge" to "${b.percent}%", "Now" to batteryStatus(b, state.onBattery)))
        val limit = b.chargeLimit
        if (limit == null) {
            Note("This battery has no charge limit DASH can set.")
            return@Column
        }
        SettingBlock(
            name = "Charge limit",
            help = "Stops charging at ${limit.stopAt}% and starts again below ${limit.startBelow}%. A battery kept full and " +
                "warm — as one living in a car is — wears out fast; held lower it lasts years longer. The figures are the machine's.",
            control = {
                PresetSegment(listOf("Off", "On"), if (limit.on) 1 else 0, controlWidth) { i -> app.power.setChargeLimit(i == 1) }
            },
        )
    }
}

@Composable
private fun ActionRow(label: String, help: String?, onClick: () -> Unit) {
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    SettingBlock(name = label, help = help, control = { DashButton(label, onClick = onClick, modifier = controlWidth) })
}

/** The question, big — as the Display tabs' "Keep this?" — with the two answers side by side. */
@Composable
private fun Confirm(a: Asking, onNo: () -> Unit) {
    val theme = LocalDashTheme.current
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(a.question, color = theme.textColourSecondary, fontSize = MAINBODY, lineHeight = MAINBODY_LINE, fontFamily = theme.font)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashButton(a.yes, onClick = { onNo(); a.run() }, modifier = Modifier.weight(1f))
            DashButton("Cancel", onClick = onNo, modifier = Modifier.weight(1f))
        }
    }
}

private fun sleepLabel(minutes: Int) = when {
    minutes == 0 -> "Never"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "$minutes min"
}

internal fun batteryStatus(b: Battery, onBattery: Boolean): String = when {
    b.full -> "Full"
    b.charging -> "Charging" + (b.minutesToFull?.let { " — full in ${duration(it)}" } ?: "")
    onBattery -> "On the battery" + (b.minutesToEmpty?.let { " — ${duration(it)} left" } ?: "")
    else -> "Plugged in, not charging"
}

private fun duration(minutes: Int) = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
