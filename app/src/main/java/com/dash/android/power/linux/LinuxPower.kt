package com.dash.android.power.linux

import android.util.Log
import com.dash.android.power.Battery
import com.dash.android.power.ChargeLimit
import com.dash.android.power.Lid
import com.dash.android.power.PowerAction
import com.dash.android.power.PowerProfile
import com.dash.android.power.PowerState
import com.dash.android.power.PowerSystem
import com.dash.android.power.SleepKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DASH-AA's power (1.1.7): the machine's own services, over the system message bus, as the seat user —
 * the same ones GNOME's and KDE's power settings use, whose policy lets the person at the machine do all
 * of this with no password and no root:
 * - **logind** sleeps, hibernates, shuts down and restarts; tells DASH the machine is about to sleep and has
 *   woken (`PrepareForSleep`); reports the lid, and lets DASH take it.
 * - **UPower** reports the battery and the charger, and turns the battery's charge limit on and off. The
 *   limit's figures are the machine's (UPower's own, from the firmware or its defaults).
 * - **The power-profiles service** (power-profiles-daemon, or Fedora's tuned-ppd, which answers the same
 *   way) switches the performance profile. On each machine it sets what that machine has — the processor's
 *   energy preference, and on some laptops the maker's fan and power limits too — which is why DASH offers
 *   profiles rather than clock speeds: the speeds themselves are root's to change.
 *
 * Each is detected on its own: a machine without one simply lacks what it gives.
 *
 * **Holding** — the lid, and a few seconds before sleep — is done through `systemd-inhibit`, kept running
 * for as long as the hold. Ending it lets go at once, and if DASH dies its standard input closes and the
 * hold goes with it, so a crash never leaves the lid or sleep held.
 *
 * Every bus call is made on one worker thread, never the caller's.
 */
class LinuxPower : PowerSystem {
    private val _state = MutableStateFlow(PowerState(available = false, note = "Looking at the machine's power…"))
    override val state: StateFlow<PowerState> = _state

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dash-power").apply { isDaemon = true } }
    private var bus: DBusConnection? = null
    private var refreshQueued: ScheduledFuture<*>? = null
    private var profilesAt: Pair<String, String>? = null
    private var batteryPath: String? = null
    private var failure: String? = null

    private var lidHold: Process? = null
    private var sleepHold: Process? = null
    private var prepare: () -> Unit = {}
    private var woke: () -> Unit = {}

    override fun start() = worker.execute {
        val c = runCatching { DBusConnectionBuilder.forSystemBus().withShared(false).build() }.getOrElse {
            Log.w(TAG, "no system bus: ${it.message}")
            _state.value = PowerState(false, "DASH cannot reach the machine's services (the system message bus), so it cannot sleep, shut down or read the battery.")
            return@execute
        }
        bus = c
        profilesAt = PROFILE_SERVICES.firstOrNull { (name, path) ->
            runCatching { c.getRemoteObject(name, path, Properties::class.java).Get<Any>(name, "ActiveProfile") }.isSuccess
        }
        runCatching {
            c.addSigHandler(Properties.PropertiesChanged::class.java) { s ->
                if (s.path.startsWith("/org/freedesktop/UPower") || s.path.startsWith("/net/hadess") || s.path == LOGIN_PATH) queueRefresh()
            }
            c.addSigHandler(Login1Manager.PrepareForSleep::class.java) { s -> worker.execute { sleeping(s.start) } }
        }.onFailure { Log.w(TAG, "could not watch the machine's power: ${it.message}") }
        holdSleep(true)
        refresh()
        // The charger and the battery change by themselves; a slow look as well, for any service that does not say.
        worker.scheduleWithFixedDelay({ refresh() }, 30, 30, TimeUnit.SECONDS)
    }

    override fun act(action: PowerAction) = worker.execute {
        val m = manager() ?: return@execute
        failure = runCatching {
            when (action) {
                PowerAction.SLEEP -> m.Suspend(false)
                PowerAction.HIBERNATE -> m.Hibernate(false)
                PowerAction.RESTART -> m.Reboot(false)
                PowerAction.SHUT_DOWN -> m.PowerOff(false)
            }
        }.exceptionOrNull()?.let { plain(it, action.label.lowercase()) }
        failure?.let { Log.w(TAG, it) }
        refresh()
    }

    override fun setProfile(profileId: String) = worker.execute {
        val (name, path) = profilesAt ?: return@execute
        failure = runCatching { bus!!.getRemoteObject(name, path, Properties::class.java).Set(name, "ActiveProfile", profileId) }
            .exceptionOrNull()?.let { plain(it, "change the profile") }
        refresh()
    }

    override fun setChargeLimit(on: Boolean) = worker.execute {
        val p = batteryPath ?: return@execute
        failure = runCatching { bus!!.getRemoteObject(UPOWER, p, UPowerDevice::class.java).EnableChargeThreshold(on) }
            .exceptionOrNull()?.let { plain(it, "change the charge limit") }
        refresh()
    }

    override fun holdLid(hold: Boolean) = worker.execute {
        if (hold && lidHold?.isAlive != true) lidHold = inhibit("handle-lid-switch", "block", "DASH decides what closing the lid does")
        if (!hold) { lidHold?.destroy(); lidHold = null }
    }

    override fun onSleep(prepare: () -> Unit, woke: () -> Unit) {
        this.prepare = prepare
        this.woke = woke
    }

    /** logind's word that the machine is about to sleep ([start]) or has woken. */
    private fun sleeping(start: Boolean) {
        if (start) {
            Log.i(TAG, "the machine is going to sleep")
            // Done on its own thread, so a stuck step cannot hold the machine awake past the limit.
            val t = Thread({ runCatching { prepare() }.onFailure { Log.w(TAG, "before sleep: ${it.message}") } }, "dash-before-sleep")
            t.isDaemon = true
            t.start()
            t.join(PREPARE_MS)
            holdSleep(false)
        } else {
            Log.i(TAG, "the machine has woken")
            holdSleep(true)
            runCatching { woke() }.onFailure { Log.w(TAG, "after waking: ${it.message}") }
            refresh()
        }
    }

    private fun holdSleep(hold: Boolean) {
        if (hold && sleepHold?.isAlive != true) sleepHold = inhibit("sleep", "delay", "DASH closes Android Auto and the sound first")
        if (!hold) { sleepHold?.destroy(); sleepHold = null }
    }

    private fun inhibit(what: String, mode: String, why: String): Process? = runCatching {
        // `cat` waits on DASH's end of a pipe: when DASH goes, the pipe closes, cat ends, and the hold with it.
        ProcessBuilder("systemd-inhibit", "--what=$what", "--mode=$mode", "--who=DASH", "--why=$why", "cat")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }.onFailure { Log.w(TAG, "could not hold $what: ${it.message}") }.getOrNull()

    private fun queueRefresh() {
        refreshQueued?.cancel(false)
        refreshQueued = worker.schedule({ refresh() }, 150, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        runCatching { rebuild() }.onFailure { Log.w(TAG, "could not read the machine's power: ${it.message}") }
    }

    private fun rebuild() {
        val c = bus ?: return
        val m = manager()
        val actions = buildSet {
            fun can(a: PowerAction, ask: () -> String) { if (m != null && runCatching(ask).getOrNull() in CAN) add(a) }
            can(PowerAction.SLEEP) { m!!.CanSuspend() }
            can(PowerAction.HIBERNATE) { m!!.CanHibernate() }
            can(PowerAction.RESTART) { m!!.CanReboot() }
            can(PowerAction.SHUT_DOWN) { m!!.CanPowerOff() }
        }
        val login = m?.let { runCatching { c.getRemoteObject(LOGIN, LOGIN_PATH, Properties::class.java).GetAll(LOGIN_MANAGER) }.getOrNull() }
        val upower = runCatching { c.getRemoteObject(UPOWER, UPOWER_PATH, Properties::class.java).GetAll(UPOWER) }.getOrNull()
        val battery = upower?.let { readBattery(c) }
        val lidPresent = upower?.get("LidIsPresent")?.value == true || File("/proc/acpi/button/lid").isDirectory
        val profiles = profilesAt?.let { (name, path) -> runCatching { c.getRemoteObject(name, path, Properties::class.java).GetAll(name) }.getOrNull() }

        _state.value = PowerState(
            available = m != null || upower != null || profiles != null,
            note = when {
                m == null && upower == null && profiles == null ->
                    "None of the machine's power services answered (logind, UPower, power profiles), so DASH cannot sleep, shut down or read the battery."
                m == null -> "The machine's session service (logind) did not answer, so DASH cannot sleep, shut down or restart it."
                else -> ""
            },
            actions = actions,
            sleepKind = sleepKind(),
            profiles = (profiles?.get("Profiles")?.value as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.get("Profile")?.let { v -> (v as? Variant<*>)?.value as? String } }
                .sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } }
                .map { PowerProfile.of(it) },
            activeProfile = profiles?.get("ActiveProfile")?.value as? String,
            battery = battery,
            onBattery = upower?.get("OnBattery")?.value == true,
            lid = if (lidPresent) Lid(closed = login?.get("LidClosed")?.value == true || upower?.get("LidIsClosed")?.value == true) else null,
            desktop = desktop(),
            failure = failure,
        )
    }

    private fun readBattery(c: DBusConnection): Battery? {
        val devices = runCatching { c.getRemoteObject(UPOWER, UPOWER_PATH, UPowerManager::class.java).EnumerateDevices() }.getOrNull() ?: return null
        // The machine's own battery — not a mouse's or a phone's, which UPower lists too.
        val (path, d) = devices.asSequence()
            .mapNotNull { p -> runCatching { p.path to c.getRemoteObject(UPOWER, p.path, Properties::class.java).GetAll(UPOWER_DEVICE) }.getOrNull() }
            .firstOrNull { (_, d) -> (d["Type"]?.value as? UInt32)?.toInt() == 2 && d["PowerSupply"]?.value == true && d["IsPresent"]?.value == true }
            ?: run { batteryPath = null; return null }
        batteryPath = path
        val state = (d["State"]?.value as? UInt32)?.toInt() ?: 0
        val secondsToEmpty = (d["TimeToEmpty"]?.value as? Number)?.toLong() ?: 0
        val secondsToFull = (d["TimeToFull"]?.value as? Number)?.toLong() ?: 0
        return Battery(
            percent = ((d["Percentage"]?.value as? Number)?.toDouble() ?: 0.0).toInt().coerceIn(0, 100),
            charging = state == 1,
            full = state == 4,
            minutesToEmpty = (secondsToEmpty / 60).toInt().takeIf { it > 0 },
            minutesToFull = (secondsToFull / 60).toInt().takeIf { it > 0 },
            chargeLimit = if (d["ChargeThresholdSupported"]?.value == true) ChargeLimit(
                on = d["ChargeThresholdEnabled"]?.value == true,
                stopAt = (d["ChargeEndThreshold"]?.value as? UInt32)?.toInt() ?: 80,
                startBelow = (d["ChargeStartThreshold"]?.value as? UInt32)?.toInt() ?: 75,
            ) else null,
        )
    }

    private fun manager(): Login1Manager? = bus?.let { c -> runCatching { c.getRemoteObject(LOGIN, LOGIN_PATH, Login1Manager::class.java) }.getOrNull() }

    companion object {
        private const val TAG = "DashPower"
        private const val LOGIN = "org.freedesktop.login1"
        private const val LOGIN_PATH = "/org/freedesktop/login1"
        private const val LOGIN_MANAGER = "org.freedesktop.login1.Manager"
        private const val UPOWER = "org.freedesktop.UPower"
        private const val UPOWER_PATH = "/org/freedesktop/UPower"
        private const val UPOWER_DEVICE = "org.freedesktop.UPower.Device"
        private const val PREPARE_MS = 3_000L

        /** The profile service under its new name and its old one (each name is also its interface); tuned-ppd answers to both. */
        private val PROFILE_SERVICES = listOf(
            "org.freedesktop.UPower.PowerProfiles" to "/org/freedesktop/UPower/PowerProfiles",
            "net.hadess.PowerProfiles" to "/net/hadess/PowerProfiles",
        )
        private val ORDER = listOf("power-saver", "balanced", "performance")

        /** logind's answers that mean "yes": "challenge" asks for a password a seat with no desktop cannot give. */
        private val CAN = setOf("yes", "inhibited")

        /** How this machine sleeps — the kernel's choice, marked [like this]; changing it is root's. */
        internal fun sleepKind(memSleep: String? = runCatching { File("/sys/power/mem_sleep").readText() }.getOrNull()): SleepKind? {
            val chosen = memSleep?.let { Regex("""\[(\w+)]""").find(it)?.groupValues?.get(1) } ?: return null
            return when (chosen) {
                "deep" -> SleepKind.DEEP
                "s2idle", "shallow" -> SleepKind.STANDBY
                else -> null
            }
        }

        /** The desktop DASH runs inside, from the session's own word; null for none or a bare display program. */
        internal fun desktop(current: String? = System.getenv("XDG_CURRENT_DESKTOP")): String? =
            current?.split(':')?.map { it.trim() }?.firstOrNull { it.uppercase() in DESKTOPS }?.let { DESKTOPS.getValue(it.uppercase()) }

        private val DESKTOPS = mapOf(
            "GNOME" to "GNOME", "KDE" to "KDE Plasma", "XFCE" to "Xfce", "X-CINNAMON" to "Cinnamon", "CINNAMON" to "Cinnamon",
            "MATE" to "MATE", "LXQT" to "LXQt", "BUDGIE" to "Budgie", "PANTHEON" to "Pantheon", "UNITY" to "Unity",
            "DEEPIN" to "Deepin", "COSMIC" to "COSMIC", "LXDE" to "LXDE",
        )

        /** The machine's refusal, in plain words. */
        internal fun plain(e: Throwable, what: String): String {
            val type = (e as? DBusExecutionException)?.type ?: ""
            val text = "$type ${e.message ?: ""}"
            return when {
                "InteractiveAuthorizationRequired" in text || "AccessDenied" in text || "NotAuthorized" in text ->
                    "Could not $what: the machine wants a password for it, and DASH does not run as administrator."
                "BlockedByInhibitorLock" in text -> "Could not $what: another program is holding the machine up."
                "SleepVerbNotSupported" in text || "NotSupported" in text -> "Could not $what: this machine cannot."
                "ServiceUnknown" in text -> "Could not $what: the service for it is not running."
                else -> "Could not $what${e.message?.let { ": $it" } ?: "."}"
            }
        }
    }
}

@DBusInterfaceName("org.freedesktop.login1.Manager")
@Suppress("FunctionName")
interface Login1Manager : DBusInterface {
    fun Suspend(interactive: Boolean)
    fun Hibernate(interactive: Boolean)
    fun PowerOff(interactive: Boolean)
    fun Reboot(interactive: Boolean)
    fun CanSuspend(): String
    fun CanHibernate(): String
    fun CanPowerOff(): String
    fun CanReboot(): String

    class PrepareForSleep(path: String, val start: Boolean) : DBusSignal(path, start)
}

@DBusInterfaceName("org.freedesktop.UPower")
@Suppress("FunctionName")
interface UPowerManager : DBusInterface {
    fun EnumerateDevices(): List<DBusPath>
}

@DBusInterfaceName("org.freedesktop.UPower.Device")
@Suppress("FunctionName")
interface UPowerDevice : DBusInterface {
    fun EnableChargeThreshold(enabled: Boolean)
}
