package com.dash.android.transport

import android.content.Context
import com.dash.android.core.ModuleData
import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The controller — DASH's message brain, the dispatcher at the desk.
 *
 * It sits above the [TransportManager] (the dumb pipe) and understands what lines *mean*. Every
 * inbound line lands here; the controller reads its TYPE word (the first pipe-separated field) and
 * routes it to the desk that handles that kind of message. Anything DASH sends goes back out through
 * the same pipe.
 *
 * This is the *frame*, built ahead of the features it will hold — the roadmap fills the desks one per
 * version. Desks staffed today: [discovery] (`HELLO` collection), [install] (the handshake —
 * `SYSTEM_SIGNAL` / `SUBSCRIBE` / `MANIFEST` / asset blocks / `INSTALL_END`, roadmap 1.4.4),
 * [reconciliation] (the sweep, `ACTIVATE`/`DEACTIVATE`, `ROGER` — roadmap 1.4.6), [broadcasts]
 * (`BROADCAST` → the sourceless core, roadmap 1.4.7), [streams] (the core → `LISTEN` out to subscribers,
 * roadmap 1.4.8), [moduleReports] (`REPORT` → the per-module store, roadmap 1.4.9) and [actions]
 * (`ACTION` out to a module's panel control, roadmap 1.4.9). Every other TYPE word is deliberately
 * ignored for now rather than mishandled — it still shows on the wire tap, so nothing is lost, and
 * adding its desk later is a single new branch in [route], never a rewrite.
 *
 * The 2×2 of arduino.md §4 is now fully staffed: [broadcasts]/[streams] are the general (sourceless)
 * column in and out, [moduleReports]/[actions] the specific (per-module, sourceful) column in and out.
 *
 * Two desks are not in [route]: [streams] is driven by *watching* the sourceless core, and [actions] by
 * a *user gesture* in a panel — neither by an inbound TYPE word. They are the two that produce without
 * consuming the wire, the outbound mirrors of [broadcasts] and [moduleReports] respectively.
 *
 * `HELLO` is the one line two desks receive — deliberately. The install/reconnect distinction lives
 * in DASH, not on the wire (arduino.md §6: a module doesn't know its own install state), so the same
 * reply is an install candidate to the discovery desk and a liveness report to the reconciliation
 * desk; each ignores the ids that aren't its business.
 *
 * Future direction (see the transport-brain design notes): routing becomes *configurable* — standard
 * signals act by sensible defaults, custom signals fall through to a user-defined desk, and a
 * patch-bay override redirects signals to Android, DASH, or back out to another module. The shape
 * here — one inbox, sort by TYPE, dispatch, one outbox — is what makes that possible without rework.
 */
class DashController(private val transport: TransportManager, context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The module database (1.4.5). The on-disk installed list — the single source of truth for
     *  install state (arduino.md §6), loaded every boot, fed by the install desk on commit. */
    val database = ModuleDatabase(context)

    /** The discovery desk. Collects the HELLO replies the reconciliation sweep raises (since 1.4.6;
     *  the broadcast itself was this desk's until then). */
    val discovery = Discovery()

    /** The reconciliation desk (1.4.6). Owns the persistent DISCOVER sweep, keeps installed modules
     *  ACTIVE, and hushes them with DEACTIVATE on uninstall. */
    val reconciliation: Reconciliation = Reconciliation(
        scope = scope,
        send = transport::send,
        database = database,
        // Busy means a handshake genuinely in flight — never a lingering Failed badge (1.4.14), so a
        // failed install can't freeze the sweep (the 1.4.6 wedged-install item).
        installBusy = { install.isBusy() },
        pruneDiscovered = discovery::prune,
        wiredTags = transport.wiredTags
    )

    /** The sourceless core's system state (1.4.7) — the §5a state store and event bus. The interface
     *  era reads from this; today the State Inspector is its window. */
    val systemState = SystemState()

    /** The broadcast desk (1.4.7). The §5 gatekeeper: checks the sender is installed and ACTIVE,
     *  consumes the id, then routes the signal into [systemState] by its §5a behaviour. */
    val broadcasts: Broadcasts = Broadcasts(
        state = systemState,
        isInstalled = { id -> database.modules.value.containsKey(id) },
        isActive = { id -> reconciliation.activity.value[id] == ModuleActivity.ACTIVE },
        heard = { id -> reconciliation.heard(id) }
    )

    /** The streams desk (1.4.8) — LISTENER delivery, the outbound mirror of [broadcasts]. Watches
     *  [systemState] and the installed/active modules, and delivers subscribed signals back out as
     *  `LISTEN`, evaluating rate/threshold/gate here (arduino.md §9). */
    val streams: Streams = Streams(
        scope = scope,
        send = transport::send,
        state = systemState,
        installed = database.modules,
        activity = reconciliation.activity
    )

    /** The per-module data store (1.4.9) — the sourceful twin of [systemState]. A module's private
     *  panel variables (`REPORT`) land here keyed by id. The module panel (1.6.x) reads it; today the
     *  State Inspector is its window. */
    val moduleData = ModuleData()

    /** The module reports desk (1.4.9). The §5 gatekeeper for the specific column: checks the sender is
     *  installed and ACTIVE, then routes the variable into [moduleData] under its id (kept, not
     *  consumed — a report is private to its module). */
    val moduleReports: ModuleReports = ModuleReports(
        data = moduleData,
        isInstalled = { id -> database.modules.value.containsKey(id) },
        isActive = { id -> reconciliation.activity.value[id] == ModuleActivity.ACTIVE },
        heard = { id -> reconciliation.heard(id) }
    )

    /** The actions desk (1.4.9) — the outbound mirror of [moduleReports]. The 1.6.x module panel calls
     *  [Actions.sendAction] when the user operates a control; it emits `ACTION|id|control|value` to that
     *  module, gatekept to installed + ACTIVE. Holds no state. */
    val actions: Actions = Actions(
        send = transport::send,
        isInstalled = { id -> database.modules.value.containsKey(id) },
        isActive = { id -> reconciliation.activity.value[id] == ModuleActivity.ACTIVE }
    )

    /** The install desk. Runs the install handshake for a module the user chose from the discovery
     *  list; a completed handshake commits into the [database], then goes straight to activation —
     *  the §7 flow ends `INSTALL_END` → save → `ACTIVATE`. */
    val install: Install = Install(
        scope = scope,
        send = transport::send,
        identityOf = { id -> discovery.modules.value.firstOrNull { it.id == id } },
        isInstalled = { id -> database.modules.value.containsKey(id) },
        commit = { module, payloads ->
            database.commit(module, payloads)
            reconciliation.activate(module.id)
        }
    )

    /**
     * One-tap firmware update (roadmap 1.4.13). A module persists nothing (§6), so a firmware update
     * *is* a reinstall: forget the stale record and re-run the install handshake, which re-seeds from
     * the module's live `HELLO` identity (the new firmware's) and re-captures its declarations fresh.
     *
     * The controller is the one place holding both the [database] and the [install] desk, so this
     * orchestration lives here rather than in a single desk or the UI. Unlike a user uninstall it sends
     * **no** `DEACTIVATE` first: this module is about to be reactivated seconds later, and a late
     * DEACTIVATE retry could otherwise land after the fresh `ACTIVATE` and wrongly silence the
     * just-updated module. The brief record-absent window is harmless — the module's broadcasts simply
     * drop-and-log at the gatekeeper until the reinstall commits.
     */
    fun updateModule(id: String) {
        database.uninstall(id)              // drop the stale contract (no wire hush — refreshing, not removing)
        reconciliation.clearMismatch(id)    // optimistic; a genuine remaining difference re-flags next HELLO
        install.install(id)                 // re-run §7 → commit → ACTIVATE, seeded from the live identity
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        database.load()
        reconciliation.start()
        streams.start()
        scope.launch {
            transport.inbound.collect { env ->
                // The origin (which device on which transport) rides with every frame (1.4.14): HELLO
                // uses the tag for wired/wireless wording, install uses the device to fail the right
                // handshake on a disconnect.
                val origin = env.deviceKey?.let { DeviceRef(env.transportTag, it) }
                when (val frame = env.frame) {
                    is Inbound.Line -> route(frame.text, env.transportTag, origin)
                    is Inbound.Block -> install.onBlock(frame, origin)   // asset payload → straight to the desk
                    is Inbound.OversizeBlock -> install.onOversizeBlock(frame, origin)
                    is Inbound.BlockProgress -> install.onBlockProgress(frame, origin)
                }
            }
        }
        // A transport coming up (boot, replug, permission granted) is the moment its modules become
        // reachable — sweep straight away rather than waiting out the timer.
        scope.launch {
            transport.status
                .map { it.state == TransportState.CONNECTED }
                .distinctUntilChanged()
                .filter { it }
                .collect { reconciliation.sync() }
        }
        // Device presence drives two things, both transport-agnostic (USB, WiFi, BT alike):
        //   1. the install disconnect-trip (1.4.14): tell the install desk who is still on the bus so
        //      it can fail any handshake whose module just left;
        //   2. greet-on-arrival (1.4.16): a *new* device joining an already-CONNECTED transport does
        //      not change the aggregate status, so the status→CONNECTED sweep above never fires for it
        //      — it would otherwise wait out the timer (up to a slow-phase 30 s). Any newly-present
        //      device wakes an immediate DISCOVER sweep, so a second board plugged in later (or a
        //      module that dials in over WiFi/BT while another is already up) is greeted at once.
        scope.launch {
            var known = emptySet<DeviceRef>()
            transport.devices.collect { devices ->
                val present = devices.map { DeviceRef(it.transportTag, it.key) }.toSet()
                install.devicesPresent(present)
                if ((present - known).isNotEmpty()) reconciliation.sync()
                known = present
            }
        }
    }

    private val _lastDashAt = MutableStateFlow<Map<DeviceRef, Long>>(emptyMap())
    /**
     * When each **board** last sent a line DASH could actually *understand*, device → epoch ms
     * (roadmap 1.5.10) — the top rung of a board's row in Transport Manager.
     *
     * The router is the only place in DASH that knows the difference between a DASH message and noise,
     * so the fact is recorded here rather than sniffed out of the wire tap by the UI. Note it is *any*
     * routable TYPE word, not only `HELLO`: a `HELLO` is proof, but so is a `REPORT` from an installed
     * module, and waiting for the next sweep's `HELLO` would leave the card claiming a chattering board
     * doesn't speak DASH for up to a slow-phase 30 s. Anything falling through to `else` is by
     * definition not DASH grammar and deliberately does not count — that is exactly the "connected but
     * talking gibberish" case the board row has to be able to show.
     *
     * Keyed per device, matching [TransportManager.lastInboundAt] — see the reasoning there.
     */
    val lastDashAt: StateFlow<Map<DeviceRef, Long>> = _lastDashAt.asStateFlow()

    /** Sort an inbound line by its TYPE word and hand it to the desk that owns that message type.
     *  [transportTag] is the pipe it arrived on and [origin] the specific device (both 1.4.14). */
    private fun route(line: String, transportTag: String, origin: DeviceRef?) {
        when (line.substringBefore('|').trim()) {
            "HELLO" -> {
                discovery.onHello(line)
                reconciliation.onHello(line, transportTag)
            }
            "ROGER" -> reconciliation.onRoger(line)
            "BROADCAST" -> broadcasts.onBroadcast(line)
            "REPORT" -> moduleReports.onReport(line)
            "SYSTEM_SIGNAL" -> install.onSignal(line, origin)
            "SUBSCRIBE" -> install.onSubscribe(line, origin)
            "MANIFEST" -> install.onManifest(line, origin)
            "INSTALL_END" -> install.onInstallEnd(line)
            // BLOCK headers arrive as Inbound.Block (header + bytes together), so no branch is needed
            // here; they still show on the wire tap. No desk for other TYPE words yet — added one per
            // version.
            else -> return   // not DASH grammar — routed nowhere, and it does not mark the board below
        }
        // Only a frame that named its device can credit one. A transport that doesn't distinguish
        // devices leaves the row unproven rather than crediting the wrong board.
        origin?.let { ref -> _lastDashAt.update { it + (ref to System.currentTimeMillis()) } }
    }

    fun stop() {
        if (!started) return
        started = false
        scope.cancel()
    }
}
