package com.dash.android.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.time.DashClock
import com.dash.android.time.DashTime
import com.dash.android.time.DateStyle
import com.dash.android.time.TimePreferences
import com.dash.android.time.TimeSettings
import com.dash.android.time.TimeState
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.display.ChoiceList
import com.dash.android.ui.display.ChoiceRow
import com.dash.android.ui.display.Note
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * System › Date & Time (DASH-AA 1.1.8). With no desktop this is the machine's only clock setting, so it
 * covers what a desktop's does — the zone, automatic time, setting the clock by hand — and DASH's own: how
 * the time and date are written, for DASH's clocks and for every element and overlay that reads DashTime.
 *
 * No password is ever asked for. Where the machine would want one, the clock is shown as it is, with how to
 * let DASH change it.
 *
 * **For native:** the tab is shared and follows as it is; native's TimeSystem reports canChange false, so
 * only the "how DASH shows it" half works there and the rest points to Android's own Date & Time.
 */

/** The regions the machine's zones are filed under, as "Region/City"; anything else (old names, "Etc/GMT+3") is left out. */
private val REGIONS = listOf("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

@Composable
fun DateTimeContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val state by app.time.state.collectAsState()
    val clock by DashTime.clock.collectAsState()
    val found by app.timeRules.found.collectAsState()
    val prefs = remember { TimePreferences(app) }
    val settings by prefs.settings.collectAsState(initial = TimeSettings())
    val scope = rememberCoroutineScope()
    fun update(change: (TimeSettings) -> TimeSettings) { scope.launch { prefs.update(change) } }
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val canChange = state.available && state.canChange != false

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Date & Time")
        InfoRows(buildList {
            add("Time" to local(clock).format(DateTimeFormatter.ofPattern(if (clock.hours24) "HH:mm:ss" else "h:mm:ss a", Locale.UK)))
            add("Date" to DashTime.formatDate(local(clock), DateStyle.NAMES))
            add("Time zone" to "${state.zone ?: clock.zone} — ${clock.zoneShort}, ${DashTime.offsetLabel(clock.utcOffsetMinutes)}")
            if (state.available) add("Clock" to when {
                state.automatic && state.synced -> "Set from the internet, and in step"
                state.automatic -> "Set from the internet — waiting for a connection"
                else -> "Set by hand"
            })
        })
        if (!state.available) Note(state.note)
        if (state.available && state.canChange == false) {
            Note("This machine wants an administrator's password to change the clock, and DASH never asks for one. " +
                "Run packaging/install.sh once — it asks for the password that one time — and DASH changes the time, " +
                "the time zone and automatic time from here, without asking again.")
        }
        state.failure?.let { Note(it) }

        if (canChange) {
            SettingsSectionHeader("Setting the clock")
            if (state.canAutomatic) {
                SettingBlock(
                    name = "Automatic time",
                    help = "From the internet, through whichever connection DASH has. Without one the clock keeps its own time " +
                        "and catches up when it is back.",
                    control = {
                        PresetSegment(listOf("Off", "On"), if (state.automatic) 1 else 0, controlWidth) { i -> app.time.setAutomatic(i == 1) }
                    },
                )
            } else {
                Note("This machine has no time-sync service (systemd-timesyncd or chrony), so its clock is set by hand.")
            }
            if (!state.automatic) SetByHand(clock, settings) { app.time.setTime(it) }

            SettingsSectionHeader("Time zone")
            SettingBlock(
                name = "Follow Location",
                help = (if (settings.zoneFromLocation) when {
                    found == null -> "Looking up the zone…"
                    found!!.zone == null -> "Could not look up the zone for ${found!!.from} — no internet? It stays as it is and is tried again in half an hour."
                    else -> "${found!!.zone}, for ${found!!.from}. Looked up again every half hour, so the clocks change when the car crosses into another zone."
                } else "Set the zone from System › Location — the place pinned there, or with none, where the internet connection is."),
                control = {
                    PresetSegment(listOf("Off", "On"), if (settings.zoneFromLocation) 1 else 0, controlWidth) { i ->
                        update { it.copy(zoneFromLocation = i == 1) }
                    }
                },
            )
            if (!settings.zoneFromLocation) ZonePicker(state) { app.time.setZone(it) }
        }

        SettingsSectionHeader("How DASH shows it")
        Note("For DASH's clocks, and for every element and overlay that shows the time.")
        val sample = local(clock)
        SettingBlock(
            name = "Hours",
            fullWidthControl = true,
            control = {
                PresetSegment(
                    listOf("24-hour  ${DashTime.formatTime(sample, true)}", "12-hour  ${DashTime.formatTime(sample, false)}"),
                    if (settings.hours24) 0 else 1,
                    Modifier.fillMaxWidth(),
                ) { i -> update { it.copy(hours24 = i == 0) } }
            },
        )
        SettingBlock(
            name = "Date",
            fullWidthControl = true,
            control = {
                ChoiceList(DateStyle.entries.map { style ->
                    ChoiceRow(DashTime.formatDate(sample, style), null, style == settings.dateStyle) { update { it.copy(dateStyle = style) } }
                })
            },
        )
    }
}

/** The clock set by hand: the date and time on steppers, from now, and set when the user says. */
@Composable
private fun SetByHand(clock: DashClock, settings: TimeSettings, set: (Long) -> Unit) {
    var draft by remember { mutableStateOf<LocalDateTime?>(null) }
    val d = draft ?: local(clock).withSecond(0)
    fun change(to: LocalDateTime) { draft = to }

    SettingBlock(
        name = "Date",
        fullWidthControl = true,
        control = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper("${d.dayOfMonth}", sub = "day", modifier = Modifier.weight(1f),
                    onMinus = { change(d.withDayOfMonth(wrap(d.dayOfMonth - 1, 1, YearMonth.from(d).lengthOfMonth()))) },
                    onPlus = { change(d.withDayOfMonth(wrap(d.dayOfMonth + 1, 1, YearMonth.from(d).lengthOfMonth()))) })
                Stepper(d.month.getDisplayName(TextStyle.SHORT, Locale.UK), sub = "month", modifier = Modifier.weight(1f),
                    onMinus = { change(d.withMonth(wrap(d.monthValue - 1, 1, 12))) },
                    onPlus = { change(d.withMonth(wrap(d.monthValue + 1, 1, 12))) })
                Stepper("${d.year}", sub = "year", modifier = Modifier.weight(1f),
                    onMinus = { change(d.minusYears(1)) },
                    onPlus = { change(d.plusYears(1)) })
            }
        },
    )
    SettingBlock(
        name = "Time",
        fullWidthControl = true,
        control = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(
                    if (settings.hours24) "%02d".format(d.hour) else "${(d.hour + 11) % 12 + 1} ${if (d.hour < 12) "am" else "pm"}",
                    sub = "hour", modifier = Modifier.weight(1f),
                    onMinus = { change(d.withHour(wrap(d.hour - 1, 0, 23))) },
                    onPlus = { change(d.withHour(wrap(d.hour + 1, 0, 23))) })
                Stepper("%02d".format(d.minute), sub = "minute", modifier = Modifier.weight(1f),
                    onMinus = { change(d.withMinute(wrap(d.minute - 1, 0, 59))) },
                    onPlus = { change(d.withMinute(wrap(d.minute + 1, 0, 59))) })
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DashButton("Set the clock", onClick = {
            set(d.atZone(ZoneId.of(clock.zone)).toInstant().toEpochMilli())
            draft = null
        }, modifier = Modifier.weight(1f))
        if (draft != null) DashButton("Back to now", onClick = { draft = null }, modifier = Modifier.weight(1f))
    }
}

/** The zone, and choosing another: its region first, then the place. */
@Composable
private fun ZonePicker(state: TimeState, choose: (String) -> Unit) {
    var region by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    SettingBlock(
        name = "Time zone",
        help = state.zone?.replace('_', ' ')?.replace("/", " › ") ?: "Not set",
        control = { DashButton(if (open) "Close" else "Change", onClick = { open = !open; region = null }, modifier = controlWidth) },
    )
    if (!open) return
    val now = Instant.now()
    if (region == null) {
        val offered = REGIONS.filter { r -> state.zones.any { it.startsWith("$r/") } }
        ChoiceList(offered.map { r -> ChoiceRow(r, null, state.zone?.startsWith("$r/") == true) { region = r } } +
            listOfNotNull(if ("UTC" in state.zones) ChoiceRow("UTC", "No summer time, no zone", state.zone == "UTC") { choose("UTC"); open = false } else null))
    } else {
        DashButton("‹ ${region!!}", onClick = { region = null }, alignStart = true, modifier = Modifier.fillMaxWidth())
        ChoiceList(state.zones.filter { it.startsWith("$region/") }.sortedBy { placeName(it) }.map { z ->
            val offset = ZoneId.of(z).rules.getOffset(now).totalSeconds / 60
            ChoiceRow(placeName(z), DashTime.offsetLabel(offset), z == state.zone) { choose(z); open = false }
        })
    }
}

/** "America/Argentina/Buenos_Aires" → "Argentina › Buenos Aires". */
private fun placeName(zone: String) = zone.substringAfter('/').replace('_', ' ').replace("/", " › ")

private fun local(c: DashClock) = LocalDateTime.of(c.year, c.month, c.day, c.hour, c.minute, c.second)

private fun wrap(v: Int, low: Int, high: Int) = if (v < low) high else if (v > high) low else v
