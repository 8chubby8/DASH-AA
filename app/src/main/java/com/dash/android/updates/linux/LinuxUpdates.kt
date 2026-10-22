package com.dash.android.updates.linux

import android.util.Log
import com.dash.android.logs.linux.UsbSticks
import com.dash.android.system.linux.Command
import com.dash.android.updates.Release
import com.dash.android.updates.SettingsCopies
import com.dash.android.updates.UpdateCheck
import com.dash.android.updates.UpdateFile.shown
import com.dash.android.updates.UpdateFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/**
 * **System › Updates on DASH-AA Linux** (1.2.2): the two system slots, update files found on USB
 * sticks, and installing one or going back to the other slot.
 *
 * DASH never runs as root, so it does not write a slot itself. It starts one of two system services the
 * person at the seat is allowed to start (`dash-update@<file>.service`, `dash-rollback.service`, by the polkit
 * rule) and shows what they say they are doing (`/run/dash-update/status`). Those check the seal themselves.
 *
 * Capability detection: on a machine that is not DASH-AA Linux (no `/usr/lib/dash/dash-update`, or not started
 * from a slot) the tab says how DASH is updated there instead, and offers nothing.
 *
 * DASH never looks for updates by itself — only when the user presses (Roger, 2026-10-15).
 */
object LinuxUpdates {
    private const val TAG = "DashUpdates"
    private val HELPER = File("/usr/lib/dash/dash-update")
    private val KEY = File("/usr/lib/dash/release.pub")
    private val STATUS = File("/run/dash-update/status")

    data class Found(val stick: UsbSticks.Stick, val file: File, val check: UpdateCheck) {
        val release: Release? get() = when (check) {
            is UpdateCheck.Sealed -> check.release
            is UpdateCheck.NotSealed -> check.release
            is UpdateCheck.Unreadable -> null
        }
    }

    sealed interface Job {
        data class Working(val what: String, val percent: Int, val going: String) : Job
        /** Done: [version] starts at the next start — DASH offers to restart now. */
        data class Ready(val version: String, val message: String) : Job
        data class Failed(val message: String) : Job
    }

    data class State(
        /** This machine is DASH-AA Linux, with slots DASH can install into. */
        val available: Boolean = false,
        val running: String? = null,
        /** The release in the other slot, or null when it is empty. */
        val other: String? = null,
        val looking: Boolean = false,
        /** Null until the user first looks. */
        val found: List<Found>? = null,
        val sticksOpenable: Boolean = true,
        val job: Job? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dash-updates").apply { isDaemon = true } }
    /** Sticks DASH opened to look on, to close again when done with them. */
    private val opened = mutableSetOf<String>()

    fun refresh() = worker.execute {
        val slots = slots()
        _state.update {
            it.copy(available = HELPER.canExecute() && slots.first != null, running = slots.first, other = slots.second,
                sticksOpenable = UsbSticks.canMount())
        }
    }

    /** The release running now and the one in the other slot, from the system slots' labels (`dash_<version>`). */
    private fun slots(): Pair<String?, String?> = runCatching {
        val root = Command.run("findmnt", "-no", "SOURCE", "/", seconds = 5)?.out?.trim().orEmpty()
        val disk = Command.run("lsblk", "-no", "PKNAME", root, seconds = 5)?.out?.trim().orEmpty()
        if (root.isEmpty() || disk.isEmpty()) return null to null
        val parts = Command.run("lsblk", "-lnpo", "PATH,PARTTYPE,PARTLABEL", "/dev/$disk", seconds = 5)?.out.orEmpty()
            .lines().map { it.trim().split(Regex("\\s+"), limit = 3) }.filter { it.size == 3 && it[1] == ROOT_TYPE }
        fun version(label: String) = label.takeIf { it.startsWith("dash_") }?.removePrefix("dash_")
        val running = parts.firstOrNull { it[0] == root }?.get(2)?.let(::version)
        val other = parts.firstOrNull { it[0] != root }?.get(2)?.let(::version)
        running to other
    }.onFailure { Log.w(TAG, "slots not read: ${it.message}") }.getOrDefault(null to null)

    /** Look on every USB stick for update files (`dash_*.update`, at the top or in the `usb` folder). */
    fun look() = worker.execute {
        _state.update { it.copy(looking = true) }
        val key = runCatching { UpdateFile.key(KEY.readText()) }.getOrNull()
        val found = mutableListOf<Found>()
        for (stick in UsbSticks.list()) {
            val wasOpen = stick.mountpoint != null
            val at = UsbSticks.mount(stick).first ?: continue
            if (!wasOpen) opened += stick.device
            val files = (listOf(at, File(at, "usb"))).flatMap { d ->
                d.listFiles { f -> f.isFile && f.name.startsWith("dash_") && f.name.endsWith(".update") }.orEmpty().toList()
            }
            files.forEach { found += Found(stick, it, UpdateFile.check(it, key)) }
            if (files.isEmpty() && stick.device in opened) { UsbSticks.unmount(stick); opened -= stick.device }
        }
        Log.i(TAG, "looked on USB sticks: ${found.size} update file(s)")
        _state.update { it.copy(looking = false, found = found.sortedByDescending { f -> f.release?.version }) }
    }

    /**
     * Install [found] into the spare slot: DASH's settings copied first, then the system service started and
     * followed until it is done. The machine is not restarted — the user is asked.
     */
    fun install(found: Found, copies: SettingsCopies) = worker.execute {
        val release = (found.check as? UpdateCheck.Sealed)?.release ?: return@execute
        val running = _state.value.running ?: return@execute
        _state.update { it.copy(job = Job.Working("Copying your settings…", 0, release.version)) }
        runCatching { copies.take(from = running, to = release.version) }.onFailure {
            Log.w(TAG, "settings not copied: ${it.message}")
            _state.update { s -> s.copy(job = Job.Failed("Your settings could not be copied, so nothing was installed: ${it.message}")) }
            return@execute
        }
        val unit = Command.run("systemd-escape", "--path", "--template=dash-update@.service", found.file.path, seconds = 5)?.out?.trim()
        if (unit.isNullOrEmpty()) { _state.update { it.copy(job = Job.Failed("The update could not be started.")) }; return@execute }
        follow(unit, release.version)
        closeSticks()
        refresh()
    }

    /**
     * Go back to the other slot's release: it is made the one to start, the settings put back if the user chose,
     * and the machine restarted straight away ([restart]) — nothing is left to change them again in between.
     */
    fun rollBack(restore: SettingsCopies.Copy?, copies: SettingsCopies, restart: () -> Unit) = worker.execute {
        val other = _state.value.other ?: return@execute
        clashingStick()?.let { stick ->
            _state.update { it.copy(job = Job.Failed("Take the USB stick ($stick) out first: it carries the same system as one of the slots, and the machine could start from it.")) }
            return@execute
        }
        _state.update { it.copy(job = Job.Working("Getting ready to go back…", 0, other)) }
        follow("dash-rollback.service", other)
        if (restore != null && _state.value.job is Job.Ready) {
            runCatching { copies.restore(restore) }.onFailure {
                Log.w(TAG, "settings not put back: ${it.message}")
                _state.update { s -> s.copy(job = Job.Failed("${shown(other)} starts next time, but your settings could not be put back: ${it.message}")) }
                return@execute
            }
        }
        if (_state.value.job is Job.Ready) {
            _state.update { it.copy(job = Job.Working("Restarting…", 100, other)) }
            restart()
        }
    }

    fun dismiss() = _state.update { it.copy(job = null) }

    /**
     * A USB stick that carries the very system in one of this machine's slots — the stick a release was installed
     * from. Its partition looks the same as the slot's (the same partition UUID, which the kernel finds the system
     * by), so with it plugged in the machine could start from the stick instead (1.2.2). Null when there is none;
     * otherwise the stick's name, and DASH asks for it to be taken out before restarting.
     */
    fun clashingStick(): String? = runCatching {
        val root = Command.run("findmnt", "-no", "SOURCE", "/", seconds = 5)?.out?.trim().orEmpty()
        val disk = Command.run("lsblk", "-no", "PKNAME", root, seconds = 5)?.out?.trim().orEmpty()
        val rows = Command.run("lsblk", "-lnpo", "PATH,PKNAME,PARTTYPE,PARTUUID", seconds = 5)?.out.orEmpty()
            .lines().map { it.trim().split(Regex("\\s+")) }.filter { it.size == 4 && it[2] == ROOT_TYPE }
        val mine = rows.filter { it[1] == "/dev/$disk" }.map { it[3].lowercase() }.toSet()
        val other = rows.firstOrNull { it[1] != "/dev/$disk" && it[3].lowercase() in mine } ?: return null
        UsbSticks.list().firstOrNull { it.device.startsWith(other[1]) }?.name ?: other[1].substringAfterLast('/')
    }.getOrNull()

    /** Start [unit] and show what it says until it is done. */
    private fun follow(unit: String, version: String) {
        val since = System.currentTimeMillis() / 1000 - 2
        val done = java.util.concurrent.atomic.AtomicReference<Command.Result?>()
        val start = Thread({
            done.set(runCatching { Command.run(listOf("systemctl", "start", "--no-ask-password", unit), 3600) }.getOrNull()
                ?: Command.Result(-1, "It took too long."))
        }, "dash-update-run").apply { isDaemon = true; start() }
        while (start.isAlive) {
            status(since)?.let { (state, percent, message) ->
                if (state == "working") _state.update { it.copy(job = Job.Working(message, percent, version)) }
            }
            start.join(1000)
        }
        val result = done.get()
        val said = status(since)
        _state.update {
            it.copy(job = when {
                said?.first == "done" -> Job.Ready(version, said.third)
                said?.first == "failed" -> Job.Failed(said.third)
                result?.code == 0 -> Job.Ready(version, "${shown(version)} starts next time.")
                else -> Job.Failed(refusal(result?.out.orEmpty()))
            })
        }
        Log.i(TAG, "$unit: ${_state.value.job}")
    }

    /** The service's own word on what it is doing — only from this run, never one left from before. */
    private fun status(since: Long): Triple<String, Int, String>? = runCatching {
        val lines = STATUS.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        if ((lines["started"]?.toLongOrNull() ?: 0) < since) return null
        Triple(lines["state"] ?: return null, lines["percent"]?.toIntOrNull() ?: 0, lines["message"].orEmpty())
    }.getOrNull()

    private fun refusal(said: String) = when {
        "Access denied" in said || "Interactive authentication required" in said ->
            "This machine does not let DASH start its updater. The rule that allows it is part of DASH-AA Linux " +
                "(50-dash.rules) — this system may be older than it."
        "not found" in said -> "This system has no updater (dash-update). It may be older than System › Updates."
        else -> "The updater stopped: ${said.trim().lineSequence().lastOrNull().orEmpty()}"
    }

    private fun closeSticks() {
        UsbSticks.list().filter { it.device in opened }.forEach { UsbSticks.unmount(it) }
        opened.clear()
    }

    private const val ROOT_TYPE = "4f68bce3-e8cd-4db1-96e7-fbcaf984b709"
}
