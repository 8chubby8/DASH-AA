package com.dash.android.time.linux

import android.util.Log
import com.dash.android.time.TimeState
import com.dash.android.time.TimeSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DASH-AA's clock (1.1.8): the machine's time service, **timedated**, over the system message bus, as the
 * seat user — what GNOME's and KDE's date settings use. It sets the zone, turns automatic time (the machine's
 * time-sync service, systemd-timesyncd or chrony) on and off, and sets the clock by hand.
 *
 * **Permission.** timedated asks the machine's permission service (polkit) before each change, and polkit's
 * own default is an administrator's password. DASH never asks for one (Roger, 2026-10-21), so it asks polkit
 * first, with no one to answer, whether DASH may (`pkcheck`, without interaction): yes, and the controls
 * work; no, and the tab says how to allow it. `packaging/install.sh` installs the rule that allows it —
 * for the person at the seat, for these three changes and nothing else. With no `pkcheck`, DASH tries and
 * reports a refusal plainly.
 *
 * timedated starts when asked and leaves when idle, so it is asked afresh each time, and looked at every
 * half minute as well: whether the clock is in step is never announced.
 *
 * Every bus call is made on one worker thread, never the caller's.
 */
class TimeDated : TimeSystem {
    private val _state = MutableStateFlow(TimeState(available = false, note = "Looking at the machine's clock…"))
    override val state: StateFlow<TimeState> = _state

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dash-time").apply { isDaemon = true } }
    private var bus: DBusConnection? = null
    private var zones: List<String> = emptyList()
    private var refreshQueued: ScheduledFuture<*>? = null
    private var failure: String? = null

    override fun start() = worker.execute {
        val c = runCatching { DBusConnectionBuilder.forSystemBus().withShared(false).build() }.getOrElse {
            Log.w(TAG, "no system bus: ${it.message}")
            _state.value = TimeState(false, "DASH cannot reach the machine's services (the system message bus), so it cannot read or set the clock's settings.")
            return@execute
        }
        bus = c
        runCatching {
            c.addSigHandler(Properties.PropertiesChanged::class.java) { s -> if (s.path == PATH) queueRefresh() }
        }.onFailure { Log.w(TAG, "could not watch the clock: ${it.message}") }
        refresh()
        worker.scheduleWithFixedDelay({ refresh() }, 30, 30, TimeUnit.SECONDS)
    }

    override fun setAutomatic(on: Boolean) = change("change automatic time") { it.SetNTP(on, false) }

    override fun setZone(zone: String) = change("change the time zone") { it.SetTimezone(zone, false) }

    override fun setTime(epochMillis: Long) = change("set the clock") { it.SetTime(epochMillis * 1000, false, false) }

    private fun change(what: String, call: (TimeDate1) -> Unit) = worker.execute {
        val c = bus ?: return@execute
        failure = runCatching { call(c.getRemoteObject(NAME, PATH, TimeDate1::class.java)) }
            .exceptionOrNull()?.let { plain(it, what) }
        failure?.let { Log.w(TAG, it) }
        refresh()
    }

    private fun queueRefresh() {
        refreshQueued?.cancel(false)
        refreshQueued = worker.schedule({ refresh() }, 150, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        runCatching { rebuild() }.onFailure {
            Log.w(TAG, "could not read the clock: ${it.message}")
            _state.value = TimeState(false, "The machine's time service (timedated) did not answer, so DASH cannot read or set the clock's settings.")
        }
    }

    private fun rebuild() {
        val c = bus ?: return
        val p = c.getRemoteObject(NAME, PATH, Properties::class.java).GetAll(NAME)
        if (zones.isEmpty()) zones = runCatching { c.getRemoteObject(NAME, PATH, TimeDate1::class.java).ListTimezones() }.getOrDefault(emptyList())
        _state.value = TimeState(
            available = true,
            note = "",
            zone = (p["Timezone"]?.value as? String)?.takeIf { it.isNotBlank() },
            automatic = p["NTP"]?.value == true,
            canAutomatic = p["CanNTP"]?.value == true,
            synced = p["NTPSynchronized"]?.value == true,
            canChange = allowed(),
            zones = zones,
            failure = failure,
        )
    }

    companion object {
        private const val TAG = "DashTime"
        private const val NAME = "org.freedesktop.timedate1"
        private const val PATH = "/org/freedesktop/timedate1"

        /** The three changes DASH makes, and the rule in packaging/ allows. */
        val ACTIONS = listOf("set-time", "set-timezone", "set-ntp").map { "org.freedesktop.timedate1.$it" }

        /** Whether polkit allows DASH every change without a password: true, false, or null when it cannot tell. */
        private fun allowed(): Boolean? {
            val pid = ProcessHandle.current().pid().toString()
            val answers = ACTIONS.map { action ->
                runCatching {
                    val p = ProcessBuilder("pkcheck", "--action-id", action, "--process", pid)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    if (p.waitFor(3, TimeUnit.SECONDS)) p.exitValue() else { p.destroy(); -1 }
                }.getOrDefault(-1)
            }
            return allowedFrom(answers)
        }

        /** pkcheck's answers: 0 yes; 1 no; 2 only with a password; anything else (not installed, an error) unknown. */
        internal fun allowedFrom(exits: List<Int>): Boolean? = when {
            exits.all { it == 0 } -> true
            exits.any { it == 1 || it == 2 } -> false
            else -> null
        }

        /** The machine's refusal, in plain words. */
        internal fun plain(e: Throwable, what: String): String {
            val type = (e as? DBusExecutionException)?.type ?: ""
            val text = "$type ${e.message ?: ""}"
            return when {
                "InteractiveAuthorizationRequired" in text || "AccessDenied" in text || "NotAuthorized" in text ->
                    "Could not $what: the machine wants a password for it, and DASH never asks for one. Run packaging/install.sh once to let DASH change the clock."
                "AutomaticTimeSyncEnabled" in text -> "Could not $what: automatic time is on. Turn it off to set the clock by hand."
                "NoNTPSupport" in text -> "Could not $what: this machine has no time-sync service (systemd-timesyncd or chrony)."
                "InvalidArgs" in text -> "Could not $what: the machine does not know that time zone."
                "ServiceUnknown" in text -> "Could not $what: the machine's time service is not running."
                else -> "Could not $what${e.message?.let { ": $it" } ?: "."}"
            }
        }
    }
}

@DBusInterfaceName("org.freedesktop.timedate1")
@Suppress("FunctionName")
interface TimeDate1 : DBusInterface {
    fun SetTime(usecUtc: Long, relative: Boolean, interactive: Boolean)
    fun SetTimezone(timezone: String, interactive: Boolean)
    fun SetNTP(useNtp: Boolean, interactive: Boolean)
    fun ListTimezones(): List<String>
}
