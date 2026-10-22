package com.dash.android.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.power.PowerAction
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.display.Note
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.updates.SettingsCopies
import com.dash.android.updates.UpdateCheck
import com.dash.android.updates.UpdateFile.shown
import com.dash.android.updates.linux.LinuxUpdates
import com.dash.android.updates.linux.LinuxUpdates.Found
import com.dash.android.updates.linux.LinuxUpdates.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * System › Updates (DASH-AA 1.2.2). DASH-AA Linux keeps two releases, one in each system slot: the
 * one running now and the one before it. A new release goes into the other slot, from an update file on a USB
 * stick; the one running stays, and one press goes back to it. Nothing is looked for, downloaded or installed
 * until the user presses (Roger, 2026-10-15).
 *
 * Settings: DASH's own are copied before each install; going back asks whether to keep them as they are or
 * put that copy back (Roger, 2026-10-22). Wi-Fi and Bluetooth pairings are never copied or put back.
 *
 * **For native:** does not apply — Android updates the app itself. The settings copies (updates/SettingsCopies.kt)
 * would follow if native ever installs releases of its own.
 */
@Composable
fun UpdatesContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val state by LinuxUpdates.state.collectAsState()
    val copies = remember { SettingsCopies(app.filesDir) }
    LaunchedEffect(Unit) { LinuxUpdates.refresh() }
    var confirmInstall by remember { mutableStateOf<Found?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    fun restart() = app.power.act(PowerAction.RESTART)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Updates")
        if (!state.available) {
            Note("Updates are for DASH-AA Linux, the system built around DASH, which keeps two releases and can go " +
                "back to the one before. On this machine DASH is updated by building it again from its repository.")
            return@Column
        }
        InfoRows(listOf(
            "Running now" to shown(state.running.orEmpty()),
            "Other slot" to (state.other?.let { shown(it) } ?: "Empty"),
        ))

        when (val job = state.job) {
            is Job.Working -> {
                SettingsSectionHeader(shown(job.going))
                Progress(job.what, job.percent)
                Note("Leave the machine on until this is done.")
                return@Column
            }
            is Job.Ready -> {
                // The stick it came from carries the same system: restarting with it in could start from it (1.2.2).
                val stick by produceState<String?>(null) {
                    while (true) { value = withContext(Dispatchers.IO) { LinuxUpdates.clashingStick() }; delay(2000) }
                }
                SettingsSectionHeader("Ready")
                Note(job.message)
                if (stick != null) Note("Take the USB stick ($stick) out before restarting: it carries the same system, and the machine could start from it instead.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (stick == null) DashButton("Restart now", onClick = { restart() }, modifier = Modifier.weight(1f))
                    DashButton("Later", onClick = { LinuxUpdates.dismiss() }, modifier = Modifier.weight(1f))
                }
                return@Column
            }
            is Job.Failed -> {
                SettingsSectionHeader("Not done")
                Note(job.message)
                DashButton("OK", onClick = { LinuxUpdates.dismiss() }, modifier = controlWidth)
                return@Column
            }
            null -> {}
        }

        SettingsSectionHeader("Install a release")
        val pending = confirmInstall
        if (pending != null) {
            val r = pending.release!!
            Note("${shown(r.version)} goes into the other slot" +
                (state.other?.let { ", in place of ${shown(it)}" } ?: "") +
                ". ${shown(state.running.orEmpty())} stays where it is, to come back to. Your settings are copied first. " +
                "It takes a few minutes, then the machine asks to restart.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashButton("Install", onClick = {
                    confirmInstall = null
                    LinuxUpdates.install(pending, copies)
                }, modifier = Modifier.weight(1f))
                DashButton("Cancel", onClick = { confirmInstall = null }, modifier = Modifier.weight(1f))
            }
        } else {
            Note("Put a USB stick with a DASH update on it into the machine, then look. DASH never looks by itself.")
            if (!state.sticksOpenable) Note("This machine cannot open USB sticks itself (no udisks), so only sticks already open are looked on.")
            DashButton(if (state.looking) "Looking…" else "Look on USB sticks", onClick = { if (!state.looking) LinuxUpdates.look() }, modifier = controlWidth)
            state.found?.let { found ->
                if (found.isEmpty()) Note("No DASH updates on the USB sticks plugged in.")
                found.forEach { f -> FoundRow(f, state.running) { confirmInstall = f } }
            }
        }

        SettingsSectionHeader("Go back")
        val other = state.other
        when {
            other == null -> Note("The other slot is empty, so there is nothing to go back to yet.")
            confirmBack -> {
                val copy = copies.takenFrom(other)
                if (copy != null) {
                    Note("The machine restarts into ${shown(other)}. Your settings: keep them as they are now, or put back the copy " +
                        "taken from ${shown(other)} on ${copy.taken.format(WHEN)}?")
                    DashButton("Keep my settings as they are", onClick = {
                        confirmBack = false; LinuxUpdates.rollBack(null, copies, ::restart)
                    }, modifier = Modifier.fillMaxWidth())
                    DashButton("Put back my settings from ${copy.taken.format(WHEN)}", onClick = {
                        confirmBack = false; LinuxUpdates.rollBack(copy, copies, ::restart)
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    Note("The machine restarts into ${shown(other)}. There is no copy of your settings from ${shown(other)}, so they stay as they are now.")
                    DashButton("Go back to ${shown(other)}", onClick = {
                        confirmBack = false; LinuxUpdates.rollBack(null, copies, ::restart)
                    }, modifier = Modifier.fillMaxWidth())
                }
                DashButton("Cancel", onClick = { confirmBack = false }, modifier = Modifier.fillMaxWidth())
            }
            else -> SettingBlock(
                name = "Go back to ${shown(other)}",
                help = "The machine restarts into it. ${shown(state.running.orEmpty())} stays in its slot, so you can come back to it the same way. " +
                    "If ${shown(other)} does not start in three tries, ${shown(state.running.orEmpty())} comes back by itself.",
                control = { DashButton("Go back", onClick = { confirmBack = true }, modifier = controlWidth) },
            )
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/** One update file found: what it is, and Install when it may be. */
@Composable
private fun FoundRow(f: Found, running: String?, install: () -> Unit) {
    val r = f.release
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val help = buildList {
        r?.summary?.let { addAll(it) }
        add(listOfNotNull(r?.date, r?.systemBytes?.let { "%.1f GB".format(it / 1e9) }, "on ${f.stick.name}").joinToString(" · "))
        if (r?.settingsChanged == true) add("This release keeps its settings differently: going back from it later means going back to your settings from before.")
        when (val c = f.check) {
            is UpdateCheck.NotSealed -> add("Not installed: ${c.why}")
            is UpdateCheck.Unreadable -> add("${f.file.name}: ${c.why}")
            is UpdateCheck.Sealed -> {}
        }
    }.joinToString("\n")
    SettingBlock(
        name = r?.let { shown(it.version) } ?: f.file.name,
        help = help,
        control = {
            when {
                f.check !is UpdateCheck.Sealed -> {}
                r?.version == running -> Note("Running now")
                else -> DashButton("Install", onClick = install, modifier = controlWidth)
            }
        },
    )
}

@Composable
private fun Progress(what: String, percent: Int) {
    val theme = LocalDashTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(what, color = theme.textColourPrimary, fontSize = BODY, fontFamily = theme.font)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.weight(1f),
                color = theme.accentColourPrimary,
                trackColor = theme.textColourSecondary.copy(alpha = 0.2f),
            )
            Text("$percent%", color = theme.textColourSecondary.copy(alpha = 0.72f), fontSize = BODY, fontFamily = theme.font)
        }
    }
}
