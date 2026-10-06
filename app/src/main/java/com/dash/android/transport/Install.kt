package com.dash.android.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.zip.CRC32

/**
 * The install desk (roadmap 1.4.4) — the [DashController]'s first *stateful* desk, and its first
 * *bidirectional* one. Where the discovery desk is fire-and-collect (each `HELLO` is self-contained),
 * an install is a **conversation**: DASH sends `INSTALL|id`, a run of declaration lines arrives that
 * all belong together, and `INSTALL_END|id` closes it. This desk accumulates that run into one
 * in-progress [InstallSession] per module and commits it on the close.
 *
 * **Opened by us, fed by the module, closed by the module.** [install] opens a session (seeded from the
 * module's discovery identity) and sends `INSTALL`; the controller then feeds each declaration in via
 * [onSignal] / [onSubscribe] / [onManifest] / [onBlock]; [onInstallEnd] validates and commits.
 *
 * **The desk holds *installing*; the [ModuleDatabase] holds *installed*** (since 1.4.5). [states] here
 * covers only handshakes under way this session (plus a lingering [InstallState.Failed] badge until the
 * user retries or dismisses it); a completed session is handed to [commit] — wired to the database,
 * which persists it and owns it from then on. Asset payload bytes are held in the session for exactly
 * that hop: validated here, written to disk by the database, never kept in memory after.
 *
 * **Designed failure (roadmap 1.4.14).** An install no longer stays pending forever. Three things can
 * end one unhappily, each surfaced as a [InstallState.Failed] the card renders with a reason and a
 * retry — never a silent snap-back:
 *  - **[FailReason.STALLED]** — an *idle* watchdog: if a session hears nothing for [IDLE_TIMEOUT_MS]
 *    it is aborted. Idle, not a total-duration cap, so a large asset transfer that is genuinely making
 *    progress is never killed — every declaration and block resets the clock.
 *  - **[FailReason.DISCONNECTED]** — the fast, precise path: [devicesPresent] fails a session the
 *    instant the device carrying it leaves the bus (board unplugged, socket dropped), rather than
 *    waiting out the idle timeout.
 *  - **[FailReason.CORRUPT]** — an asset block whose CRC or length does not match (was the one
 *    unavoidable abort since 1.4.4; now it wears a designed fail state like the others). **Since
 *    1.6.12 only after a repair round**: a damaged piece is noted, the install runs to its end, and
 *    DASH asks for each damaged piece again with `RESEND`, up to five times, before failing.
 *  - **[FailReason.OVERSIZE]** — an asset block bigger than DASH will hold in memory at once
 *    (roadmap 1.6.5). The transport reads and discards the payload so the stream stays framed; this
 *    desk then says why, rather than leaving the session to die of the idle timeout wearing
 *    "stalled", which would have been a lie about what happened.
 * A user [cancel] is deliberate, so it is *not* a failure — it reverts the card cleanly with no badge.
 *
 * **Busy means in-flight, not badged.** [isBusy] counts only live [sessions], never a lingering
 * `Failed` badge, so the reconciliation sweep (which pauses while an install is in flight) is freed the
 * moment a handshake ends — closing the 1.4.6 "a wedged install pauses the sweep forever" item.
 *
 * **Keyed by id, holds many.** Sessions live in a map keyed by module id, because every declaration
 * carries its id (arduino.md §2). The UI drives one install at a time, but the wire is id-addressed, so
 * the frame naturally supports more than one in flight — build the frame for many, drive it as one.
 *
 * **Well-mannered.** A declaration for an id with no open session (a stray, a message type a later
 * build will handle, or the tail of a cancelled/failed install still monologuing) is logged and
 * ignored, never fatal. ACTIVATE and live data are 1.4.6+, so nothing here starts a module sending —
 * an installed module is dormant until reconciliation wakes it.
 */
class Install(
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
    private val identityOf: (String) -> DiscoveredModule?,
    private val isInstalled: (String) -> Boolean,
    private val commit: (InstalledModule, List<ByteArray>) -> Unit
) {
    private val sessions = mutableMapOf<String, InstallSession>()

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** Per-module install state: [InstallState.Installing] while a handshake runs, [InstallState.Failed]
     *  until the user retries or dismisses. Absent ⇒ nothing in progress (installed lives in the database). */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    /** True while any handshake is genuinely in flight — the sweep gate (1.4.6/1.4.14). A `Failed`
     *  badge sitting on a card is *not* busy, so it can never freeze reconciliation. */
    @Synchronized
    fun isBusy(): Boolean = sessions.isNotEmpty()

    /** User pressed Install (or Retry): clear any stale badge, open a session, arm its watchdog, and
     *  begin the handshake. */
    @Synchronized
    fun install(id: String) {
        if (sessions.containsKey(id)) return               // already installing
        if (isInstalled(id)) return                        // already installed — uninstall first
        val seed = identityOf(id) ?: run {
            _states.value = _states.value - id             // identity gone (module unplugged) — clear any badge
            return
        }
        val session = InstallSession(seed)
        sessions[id] = session
        setState(id, InstallState.Installing(progress = null))
        arm(id, session)
        send("$INSTALL|$id")
    }

    /** User pressed Cancel on an in-progress install. A deliberate stop is not a failure — drop the
     *  session and return the card to its plain discovered state, no badge. (The module keeps sending
     *  its declaration run to the end; those strays drop-and-log against the now-absent session — a
     *  wire-level abort message is an SDK-lock decision, roadmap 1.4.15.) */
    @Synchronized
    fun cancel(id: String) {
        val session = sessions.remove(id) ?: return
        session.watchdog?.cancel()
        _states.value = _states.value - id
    }

    /** User pressed Dismiss on a failed install: clear the badge without retrying. */
    @Synchronized
    fun dismiss(id: String) {
        _states.value = _states.value - id
    }

    /**
     * Device presence changed (roadmap 1.4.14) — the aggregated transport device list, fed by the
     * controller. Fail any in-flight install whose source device has left the bus: a module unplugged
     * mid-handshake, caught at once instead of waiting out the idle timeout. A session whose source is
     * not yet known (no declaration has arrived, so no origin captured) is left to the watchdog.
     */
    @Synchronized
    fun devicesPresent(present: Set<DeviceRef>) {
        sessions.values
            .filter { it.origin != null && it.origin !in present }
            .toList()                                       // snapshot before mutating the map in fail()
            .forEach { fail(it.seed.id, it, FailReason.DISCONNECTED) }
    }

    /** `SYSTEM_SIGNAL|id|function` — a standard signal this SYSTEM module will broadcast. */
    @Synchronized
    fun onSignal(line: String, origin: DeviceRef?) {
        val session = sessionFor(line) ?: return
        touch(session, origin)
        val function = line.split('|').getOrNull(2)?.trim().orEmpty()
        if (function.isNotEmpty()) session.signals += function
    }

    /** `SUBSCRIBE|id|function|…` — a signal this LISTENER module wants delivered. */
    @Synchronized
    fun onSubscribe(line: String, origin: DeviceRef?) {
        val session = sessionFor(line) ?: return
        touch(session, origin)
        Subscription.parse(line)?.let { session.subscriptions += it }
    }

    /** `MANIFEST|id|blocks|bytes` — an ACCESSORY's asset table-of-contents; sets the progress total. */
    @Synchronized
    fun onManifest(line: String, origin: DeviceRef?) {
        val session = sessionFor(line) ?: return
        touch(session, origin)
        val parts = line.split('|')
        session.declaredBlocks = parts.getOrNull(2)?.trim()?.toIntOrNull()
        session.totalBytes = parts.getOrNull(3)?.trim()?.toIntOrNull()
        emitProgress(session)
    }

    /** A completed asset block (header + raw bytes). Validate the CRC and length, then keep it —
     *  metadata for the record, payload bytes for the database to write at commit. */
    @Synchronized
    fun onBlock(block: Inbound.Block, origin: DeviceRef?) {
        val parts = block.header.split('|')            // BLOCK|id|name|length|crc
        val id = parts.getOrNull(1)?.trim().orEmpty()
        val session = sessions[id]
        if (session == null) {
            Log.w(TAG, "block for id $id with no open install session — ignored")
            return
        }
        touch(session, origin)
        val name = parts.getOrNull(2)?.trim().orEmpty()
        val declaredLen = parts.getOrNull(3)?.trim()?.toIntOrNull()
        val declaredCrc = parts.getOrNull(4)?.trim()?.let { runCatching { it.toLong(16) }.getOrNull() }

        val actualCrc = CRC32().apply { update(block.bytes) }.value
        val lengthOk = declaredLen == null || declaredLen == block.bytes.size
        val crcOk = declaredCrc != null && declaredCrc == actualCrc

        if (!lengthOk || !crcOk) {
            Log.w(TAG, "block '$name' failed validation (lengthOk=$lengthOk crcOk=$crcOk) for $id")
            if (crcOk.not()) Log.w(TAG, "  ${characteriseCorruption(block.bytes)}")
            session.inFlightBytes = 0                  // thrown away — the bar gives the ground back
            /*
             * **A damaged piece is noted, not fatal** (roadmap 1.6.12, module-sdk.md §8). Before the
             * end of the install it joins the repair list and the install carries on — the module is
             * mid-monologue and cannot be interrupted. During the repair round it is the piece just
             * asked for, arriving damaged again: ask once more, up to [MAX_RESENDS] times, then fail
             * exactly as an install failed before there was a repair round at all.
             */
            if (!session.repairing) {
                if (name.isNotEmpty() && name !in session.damaged) session.damaged[name] = 0
                emitProgress(session)
            } else if (name == session.repairingName) {
                val tries = (session.damaged[name] ?: 0)
                if (tries >= MAX_RESENDS) {
                    Log.w(TAG, "block '$name' still damaged after $tries resends — install aborted for $id")
                    fail(id, session, FailReason.CORRUPT)
                } else {
                    requestResend(id, session, name)
                }
            }
            return
        }

        session.assets.removeAll { it.name == name }   // a repaired piece replaces nothing, but be sure
        session.assets += InstalledAsset(name = name, bytes = block.bytes.size, crcOk = true)
        session.payloads += block.bytes
        session.receivedBytes += block.bytes.size
        session.inFlightBytes = 0                      // committed now; the estimate has done its job
        emitProgress(session)

        if (session.repairing && session.damaged.remove(name) != null) {
            Log.i(TAG, "block '$name' repaired for $id")
            repairNextOrCommit(id, session)
        }
    }

    /**
     * The repair round (roadmap 1.6.12). Ask for the next damaged piece, or — with none left —
     * commit the install exactly as a clean one would have been.
     */
    private fun repairNextOrCommit(id: String, session: InstallSession) {
        val next = session.damaged.keys.firstOrNull()
        if (next == null) {
            sessions.remove(id)
            session.watchdog?.cancel()
            _states.value = _states.value - id
            commit(session.record(), session.payloads)
            return
        }
        requestResend(id, session, next)
    }

    /** `RESEND|id|name` — ask the module for one piece again, counting the attempt. */
    private fun requestResend(id: String, session: InstallSession, name: String) {
        session.repairingName = name
        session.damaged[name] = (session.damaged[name] ?: 0) + 1
        session.lastActivity = System.currentTimeMillis()   // the wait for an answer starts now
        Log.i(TAG, "asking $id to resend '$name' (attempt ${session.damaged[name]} of $MAX_RESENDS)")
        send("$RESEND|$id|$name")
    }

    /**
     * Part of a block has arrived (roadmap 1.6.6) — move the bar without committing anything.
     *
     * **This exists because the bar used to lie by standing still.** Progress advanced only as whole
     * blocks committed, so a panel whose artwork is ~90% of its payload gave three sample points:
     * 1%, then 90%, then done. Over USB that is eight seconds of a motionless bar in the middle,
     * which reads as a failed install rather than a working one.
     *
     * **Nothing here is trusted.** These bytes have not been CRC-checked and the block may still
     * fail, so they go to [InstallSession.inFlightBytes] — counted for display, abandoned on
     * failure, replaced by the real figure when the block commits. They must never reach
     * [InstallSession.receivedBytes], or an install that failed halfway would leave the bar
     * remembering progress towards something that was thrown away.
     *
     * A report for a module with no open session is ordinary rather than suspicious — an install can
     * be cancelled or time out while its bytes are still arriving — so it is dropped without a word.
     */
    @Synchronized
    fun onBlockProgress(progress: Inbound.BlockProgress, origin: DeviceRef?) {
        val id = progress.header.split('|').getOrNull(1)?.trim().orEmpty()
        val session = sessions[id] ?: return
        touch(session, origin)                         // arriving bytes are activity: hold the watchdog off
        session.inFlightBytes = progress.received
        emitProgress(session)
    }

    /**
     * Say *how* a block is wrong, not merely that it is (2026-08-13, diagnosing USB corruption).
     *
     * **DASH has no reference copy to diff against** — only the CRC the module declared — so the
     * usual "first differing byte" is not available. But one signature is, and it is the one that
     * matters, because it separates the two causes:
     *
     *  - **Bytes were dropped upstream.** The assembler always takes exactly the declared count, so
     *    a short delivery is made up from whatever arrives *next* — meaning the tail of the payload
     *    contains the beginning of the following message. Finding `BLOCK|` or `INSTALL_END` in the
     *    last stretch of a PNG is proof the stream lost bytes and slid.
     *  - **Bytes were altered in place.** The count was right and nothing slid; some bytes are
     *    simply wrong. That is corruption on the wire, not loss — a different fault with a
     *    different remedy.
     *
     * A retry protocol fixes both, but only the first is fixed by slowing the link down, so it is
     * worth knowing which one we have before designing anything.
     */
    private fun characteriseCorruption(bytes: ByteArray): String {
        val tail = bytes.takeLast(TAIL_SCAN).toByteArray().toString(Charsets.ISO_8859_1)
        val slid = listOf("BLOCK|", "INSTALL_END", "MANIFEST").firstOrNull { it in tail }
        return if (slid != null) {
            "the payload's tail contains '$slid' — bytes were LOST upstream and the stream slid " +
                "to fill the count. A slower link or flow control would address this."
        } else {
            "the tail carries no following message, so the count was met in step — bytes were " +
                "ALTERED in transit rather than lost. Slowing the link will not fix this; only a " +
                "retry or a cleaner connection will."
        }
    }

    /**
     * A block DASH refused to hold in memory (roadmap 1.6.5). The transport already read and
     * discarded the payload to keep the stream framed, so nothing is corrupt — this module simply
     * sent an asset larger than DASH will buffer, and the install cannot complete without it.
     *
     * It fails the install for the same reason a bad CRC does: the record would be missing an asset
     * the layout refers to, and a panel with a hole in it is worse than an honest failure.
     */
    @Synchronized
    fun onOversizeBlock(block: Inbound.OversizeBlock, origin: DeviceRef?) {
        val parts = block.header.split('|')            // BLOCK|id|name|length|crc
        val id = parts.getOrNull(1)?.trim().orEmpty()
        val session = sessions[id] ?: return
        touch(session, origin)
        val name = parts.getOrNull(2)?.trim().orEmpty()
        Log.w(TAG, "block '$name' declared ${block.declaredBytes} bytes, beyond what DASH will " +
            "buffer — payload discarded, install aborted for $id")
        fail(id, session, FailReason.OVERSIZE)
    }

    /**
     * `INSTALL_END|id` — hand the accumulated session to the database and close it here.
     *
     * **Unless something arrived damaged** (roadmap 1.6.12). Then the session stays open and the
     * repair round begins: the module has finished talking and is back in its normal loop, listening,
     * so DASH can now ask for each damaged piece again. A module that does not know `RESEND` simply
     * never answers, the watchdog runs out, and the install fails as CORRUPT — exactly what happened
     * before this round existed.
     */
    @Synchronized
    fun onInstallEnd(line: String) {
        val id = idOf(line) ?: return
        val session = sessions[id] ?: return
        if (session.repairing) return                  // a second INSTALL_END is noise
        if (session.damaged.isNotEmpty()) {
            Log.i(TAG, "install for $id ended with ${session.damaged.size} damaged piece(s) — repairing")
            session.repairing = true
            repairNextOrCommit(id, session)
            return
        }
        repairNextOrCommit(id, session)                // nothing damaged: commits at once
    }

    /**
     * Arm the idle watchdog for a session. Runs until the session has been silent for
     * [IDLE_TIMEOUT_MS], then fails it STALLED. Re-checks rather than re-arms on activity: [touch]
     * just moves [InstallSession.lastActivity] forward, and the loop recomputes the remaining wait
     * after each delay — so a steady block transfer is never killed while it keeps arriving.
     */
    private fun arm(id: String, session: InstallSession) {
        session.watchdog = scope.launch {
            while (isActive) {
                val remaining = IDLE_TIMEOUT_MS - (System.currentTimeMillis() - session.lastActivity)
                if (remaining <= 0) {
                    // Silence during the repair round means the module did not answer RESEND — an
                    // older module, or a piece that will not come. The honest reason is still the
                    // damage, not a stall: it is the failure the install would have had anyway.
                    fail(id, session, if (session.repairing) FailReason.CORRUPT else FailReason.STALLED)
                    return@launch
                }
                delay(remaining)
            }
        }
    }

    /** Mark a session alive (resets the idle watchdog) and capture where its declarations come from
     *  the first time we can, so a later disconnect can fail exactly this install. */
    private fun touch(session: InstallSession, origin: DeviceRef?) {
        session.lastActivity = System.currentTimeMillis()
        if (origin != null && session.origin == null) session.origin = origin
    }

    /** End a session unhappily: cancel its watchdog, drop it, and leave a [InstallState.Failed] badge.
     *  The identity guard makes it safe to call from the watchdog coroutine — a session already closed
     *  or replaced by a fresh install is left alone. */
    @Synchronized
    private fun fail(id: String, session: InstallSession, reason: FailReason) {
        if (sessions[id] !== session) return
        session.watchdog?.cancel()
        sessions.remove(id)
        _states.value = _states.value + (id to InstallState.Failed(reason))
        Log.w(TAG, "install failed for $id: $reason")
    }

    private fun sessionFor(line: String): InstallSession? {
        val id = idOf(line) ?: return null
        return sessions[id] ?: run {
            Log.w(TAG, "declaration for id $id with no open install session — ignored")
            null
        }
    }

    private fun idOf(line: String): String? =
        line.split('|').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun emitProgress(session: InstallSession) =
        setState(session.seed.id, InstallState.Installing(session.progress()))

    private fun setState(id: String, state: InstallState) {
        _states.value = _states.value + (id to state)
    }

    private companion object {
        const val INSTALL = "INSTALL"
        const val RESEND = "RESEND"

        /** How many times one damaged piece is asked for again before the install fails (1.6.12).
         *  Five, not three (Roger, after a 20-install ESP32 test): a picture that arrives damaged
         *  about half the time fails three asks in a row about 1 install in 25, five about 1 in 150. */
        const val MAX_RESENDS = 5
        const val TAG = "DashInstall"

        /** How much of a failed payload's tail to scan for a following message. A block header is
         *  well under this, and it stays clear of the payload's own content. */
        const val TAIL_SCAN = 128

        /** Silence for this long during a handshake ⇒ stalled. Generous enough to cover the gaps
         *  between blocks of a real asset transfer, short enough to catch a wedged module promptly. */
        const val IDLE_TIMEOUT_MS = 10_000L
    }
}

/** How an install ended unhappily (roadmap 1.4.14). Each renders a distinct, honest reason on the card. */
enum class FailReason { STALLED, DISCONNECTED, CORRUPT, OVERSIZE }

/**
 * What the Module Management card renders for a module the install desk is currently tracking.
 *  - [Installing] — a handshake in flight. [progress] is a 0..1 fraction for ACCESSORY (known once
 *    MANIFEST lands), or null for SYSTEM/LISTENER, whose few-line handshake shows an indeterminate bar.
 *  - [Failed] — the handshake ended unhappily and is waiting on the user to retry or dismiss.
 */
sealed interface InstallState {
    data class Installing(val progress: Float?) : InstallState
    data class Failed(val reason: FailReason) : InstallState
}

/**
 * One install in progress. Accumulates a module's declarations between `INSTALL` and `INSTALL_END` —
 * asset payload bytes included, held only until commit — then flattens into an [InstalledModule]
 * record. Only the desk touches it, under the desk's lock (except [watchdog]/[lastActivity], which the
 * watchdog coroutine reads, guarded by the same lock in [Install.fail]).
 */
private class InstallSession(val seed: DiscoveredModule) {
    val signals = mutableListOf<String>()
    val subscriptions = mutableListOf<Subscription>()
    val assets = mutableListOf<InstalledAsset>()
    val payloads = mutableListOf<ByteArray>()          // aligned index-for-index with [assets]

    var declaredBlocks: Int? = null
    var totalBytes: Int? = null
    var receivedBytes: Int = 0

    /**
     * Bytes of the block currently arriving — **counted for the bar, never banked** (roadmap 1.6.6).
     *
     * Reset to zero when the block commits (its real size joins [receivedBytes]) and simply
     * abandoned if it fails, so a corrupt asset cannot leave the bar remembering ground that was
     * given back.
     */
    var inFlightBytes: Int = 0

    /**
     * Pieces that arrived damaged, by name, with how many times each has been asked for again
     * (roadmap 1.6.12). Filled during the install; worked through after `INSTALL_END`.
     */
    val damaged = linkedMapOf<String, Int>()

    /** True once `INSTALL_END` has arrived with damage outstanding and DASH is asking for it again. */
    var repairing: Boolean = false

    /** The piece most recently asked for. */
    var repairingName: String? = null

    /** Where this install's declarations arrive from (roadmap 1.4.14) — captured on the first one,
     *  used to fail the session if that device leaves the bus. Null until the first declaration lands. */
    var origin: DeviceRef? = null

    /** When this session last heard anything, for the idle watchdog. */
    var lastActivity: Long = System.currentTimeMillis()
    var watchdog: Job? = null

    /** 0..1 once an ACCESSORY MANIFEST has given a byte total; null (indeterminate) until then and for
     *  SYSTEM/LISTENER, whose handshakes carry no total. */
    fun progress(): Float? {
        val total = totalBytes ?: return null
        if (total <= 0) return null
        return ((receivedBytes + inFlightBytes).toFloat() / total).coerceIn(0f, 1f)
    }

    fun record() = InstalledModule(
        id = seed.id,
        type = seed.type,
        name = seed.name,
        description = seed.description,
        version = seed.version,
        signals = signals.toList(),
        subscriptions = subscriptions.toList(),
        assets = assets.toList()
    )
}
