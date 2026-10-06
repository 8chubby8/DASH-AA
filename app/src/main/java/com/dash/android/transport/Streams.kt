package com.dash.android.transport

import android.util.Log
import com.dash.android.core.SignalBehaviour
import com.dash.android.core.StoredSignal
import com.dash.android.core.SystemCommands
import com.dash.android.core.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The streams desk (roadmap 1.4.8) — LISTENER delivery. Where the broadcast desk (1.4.7) is the
 * *inbound* system path — `BROADCAST` off the wire, gatekept, into the sourceless core — this desk is
 * its **outbound mirror**: a value already in the core goes back out to the modules that subscribed to
 * it, as `LISTEN|id|function|value`. Same core in the middle, opposite direction (arduino.md §4c
 * "same rhythm, both directions").
 *
 * **It watches the core; the core stays dumb.** This desk observes [SystemState] rather than being
 * pushed to — the store is a passive blackboard (the crossroads' destination), and all the delivery
 * cleverness lives here at the desk. It never touches the wire's inbound side; it only reads state and
 * emits `LISTEN`.
 *
 * **Four delivery triggers (§4c), each derived from the signal's behaviour ([SystemCommands]):**
 *  - **On change** — a subscribed stateful signal's stored value moves → deliver, subject to the
 *    controls below. Caught by watching the store, which covers both continuous (store-only, no event)
 *    and boolean/multi-state (store-and-event) uniformly.
 *  - **Heartbeat** — every [HEARTBEAT_MS], resend every subscribed stateful signal's current value to
 *    each active subscriber, changed or not. The downstream twin of the SYSTEM module's report; it
 *    self-heals a subscriber that missed an event.
 *  - **On activation** — the moment a module turns ACTIVE, dump the current value of each subscribed
 *    stateful signal before its first heartbeat, so it is never stale on wake.
 *  - **Event-only fire** — momentary controls (`media_next`…) come off the event bus and deliver
 *    valueless `LISTEN` on fire. No heartbeat, no dump, no dedup — there is no state.
 *
 * **The four controls, all evaluated here (§9), never on the module:**
 *  - **rate** (`20hz`) — a leading-**and-trailing** throttle: the first value of a burst goes at once
 *    (responsive), and the last value of a burst goes when its window closes (converges within one
 *    window, never waits on the 5 s heartbeat). Pure leading-edge would drop the resting value of a
 *    stream for up to a heartbeat — bad for exactly the steering-angle case §9 uses.
 *  - **threshold** — numeric deadband against the last delivered value; sub-threshold moves are held.
 *  - **gate + gate_value** — the stream runs only while another stored signal equals a value. A
 *    false→true flip fires the current value immediately, ignoring the threshold (§9 "gate-open fires
 *    immediately"); while closed, nothing is sent.
 *
 * **Malformed subscriptions are not accommodated.** A blank optional field is legal and total (no cap /
 * any change / always) and is honoured literally. A field that is present but unparseable (a rate that
 * is not `Nhz`, a non-numeric threshold) is logged and treated as absent — DASH does not guess the
 * author's intent, and its own integrity is never at risk from a bad module line.
 *
 * Delivery only ever reaches an **installed, ACTIVE** subscriber. Subscriptions are read type-agnostic
 * from every installed module — a LISTENER's and an ACCESSORY's (§11) are handled identically.
 */
class Streams(
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
    private val state: SystemState,
    private val installed: StateFlow<Map<String, InstalledModule>>,
    private val activity: StateFlow<Map<String, ModuleActivity>>
) {

    /** Per-subscription runtime state for throttling, keyed by "id|function". Guarded by [lock]. */
    private class Stream {
        var lastSentValue: String? = null
        var lastSentAt: Long = 0
        var pendingValue: String? = null
        var flushJob: Job? = null
    }

    private val lock = Any()
    private val streams = mutableMapOf<String, Stream>()

    private var started = false

    fun start() {
        if (started) return
        started = true
        watchStore()
        watchEvents()
        watchActivations()
        runHeartbeat()
    }

    /* ---- the four triggers ---- */

    /** On-change: watch the store, diff each emission, deliver changed stateful signals — and fire any
     *  subscription whose *gate* just opened. Seeds on the first emission without delivering (the
     *  activation dump, not this, primes an already-populated store for a module coming online). */
    private fun watchStore() = scope.launch {
        var previous: Map<String, StoredSignal>? = null
        state.values.collect { now ->
            val prev = previous
            if (prev == null) { previous = now; return@collect }
            synchronized(lock) {
                val changed = now.keys.filter { prev[it]?.value != now[it]?.value }
                for (function in changed) {
                    val value = now[function]?.value ?: continue
                    // (a) direct subscribers to this signal
                    forEachActiveSubscription { id, sub ->
                        if (sub.function == function && gateOpen(sub, now)) deliverThrottled(id, sub, value)
                    }
                    // (b) subscriptions gated on this signal — the gate may have just opened
                    forEachActiveSubscription { id, sub ->
                        if (sub.gate == function && !gateOpen(sub, prev) && gateOpen(sub, now)) {
                            now[sub.function]?.value?.let { deliverImmediate(id, sub, it) }
                        }
                    }
                }
            }
            previous = now
        }
    }

    /** Event-only: momentary controls fire on the bus. The bus also carries store-and-event changes —
     *  those are the store watcher's job, so only EVENT_ONLY signals are handled here. Replayed history
     *  (the bus keeps a cache) is dropped so opening the desk can't re-deliver old presses. */
    private fun watchEvents() = scope.launch {
        val startAt = System.currentTimeMillis()
        state.events.collect { event ->
            if (event.at < startAt) return@collect
            if (SystemCommands.behaviourOf(event.function) != SignalBehaviour.EVENT_ONLY) return@collect
            synchronized(lock) {
                val values = state.values.value
                forEachActiveSubscription { id, sub ->
                    if (sub.function == event.function && gateOpen(sub, values)) {
                        emitListen(id, sub.function, null)   // no value, no throttle, no threshold
                    }
                }
            }
        }
    }

    /** On activation: a module turning ACTIVE gets an immediate dump of every subscribed stateful
     *  signal's current value. A module leaving ACTIVE (deactivate/uninstall) has its throttle state
     *  pruned, so a fresh session starts clean and the next dump re-primes it. */
    private fun watchActivations() = scope.launch {
        var previous = emptyMap<String, ModuleActivity>()
        activity.collect { now ->
            synchronized(lock) {
                for ((id, act) in now) {
                    if (act == ModuleActivity.ACTIVE && previous[id] != ModuleActivity.ACTIVE) dump(id)
                }
                // Drop throttle state for any module no longer ACTIVE (deactivated or uninstalled).
                val active = now.filterValues { it == ModuleActivity.ACTIVE }.keys
                val stale = streams.keys.filter { it.substringBefore('|') !in active }
                stale.forEach { streams.remove(it)?.flushJob?.cancel() }
            }
            previous = now
        }
    }

    /** Heartbeat: resend every active subscriber's subscribed stateful signals, changed or not. */
    private fun runHeartbeat() = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_MS)
            synchronized(lock) {
                val values = state.values.value
                forEachActiveSubscription { id, sub ->
                    if (isStateful(sub.function) && gateOpen(sub, values)) {
                        values[sub.function]?.value?.let { deliverImmediate(id, sub, it) }
                    }
                }
            }
        }
    }

    /** Dump the current stored value of every stateful subscribed signal for one module (gate honoured). */
    private fun dump(id: String) {
        val module = installed.value[id] ?: return
        val values = state.values.value
        for (sub in module.subscriptions) {
            if (isStateful(sub.function) && gateOpen(sub, values)) {
                values[sub.function]?.value?.let { deliverImmediate(id, sub, it) }
            }
        }
    }

    /* ---- delivery ---- */

    /** On-change delivery: apply the deadband, then the rate throttle (leading + trailing). */
    private fun deliverThrottled(id: String, sub: Subscription, value: String) {
        val stream = streamFor(id, sub.function)

        // Deadband: hold a move smaller than the threshold from the last delivered value.
        val threshold = numericField(sub.threshold, "threshold", sub)
        if (threshold != null) {
            val last = stream.lastSentValue?.toDoubleOrNull()
            val now = value.toDoubleOrNull()
            if (last != null && now != null && abs(now - last) < threshold) return
        }

        val interval = rateIntervalMs(sub)
        val now = System.currentTimeMillis()
        if (interval == null || now - stream.lastSentAt >= interval) {
            emitAndRecord(id, sub.function, value, stream)   // leading edge — the window is open
        } else {
            // Within the cooldown window: remember the latest and let it out at the window's end.
            stream.pendingValue = value
            if (stream.flushJob?.isActive != true) {
                val wait = interval - (now - stream.lastSentAt)
                stream.flushJob = scope.launch {
                    delay(wait)
                    synchronized(lock) {
                        val pending = stream.pendingValue ?: return@synchronized
                        // Only if the module is still active and the gate still open.
                        val still = installed.value[id]?.subscriptions?.any { it.function == sub.function && it.gate == sub.gate } == true
                        if (activity.value[id] == ModuleActivity.ACTIVE && still && gateOpen(sub, state.values.value)) {
                            emitAndRecord(id, sub.function, pending, stream)
                        }
                        stream.pendingValue = null
                    }
                }
            }
        }
    }

    /** Immediate delivery, bypassing threshold and rate — for heartbeat, activation dump, and gate-open.
     *  Resets the throttle baseline so normal throttling resumes cleanly afterwards. */
    private fun deliverImmediate(id: String, sub: Subscription, value: String) {
        val stream = streamFor(id, sub.function)
        stream.flushJob?.cancel()
        stream.pendingValue = null
        emitAndRecord(id, sub.function, value, stream)
    }

    private fun emitAndRecord(id: String, function: String, value: String, stream: Stream) {
        emitListen(id, function, value)
        stream.lastSentValue = value
        stream.lastSentAt = System.currentTimeMillis()
        stream.pendingValue = null
    }

    private fun emitListen(id: String, function: String, value: String?) {
        send(if (value == null) "LISTEN|$id|$function" else "LISTEN|$id|$function|$value")
    }

    /* ---- helpers ---- */

    private inline fun forEachActiveSubscription(action: (id: String, sub: Subscription) -> Unit) {
        val active = activity.value
        for ((id, module) in installed.value) {
            if (active[id] != ModuleActivity.ACTIVE) continue
            for (sub in module.subscriptions) action(id, sub)
        }
    }

    private fun gateOpen(sub: Subscription, values: Map<String, StoredSignal>): Boolean =
        sub.gate.isBlank() || values[sub.gate]?.value == sub.gateValue

    /** Stateful = anything but an event-only control. An unknown signal (not in the vocabulary) is
     *  never in the store, so it never delivers regardless — the check just keeps event-only out. */
    private fun isStateful(function: String): Boolean =
        SystemCommands.behaviourOf(function) != SignalBehaviour.EVENT_ONLY

    private fun streamFor(id: String, function: String): Stream =
        streams.getOrPut("$id|$function") { Stream() }

    /** `Nhz` → the minimum inter-delivery interval in ms. Blank ⇒ null (uncapped). A present-but-bad
     *  value is logged and treated as absent — not accommodated, not guessed at. */
    private fun rateIntervalMs(sub: Subscription): Long? {
        val raw = sub.rate.trim()
        if (raw.isEmpty()) return null
        val hz = raw.removeSuffix("hz").removeSuffix("HZ").removeSuffix("Hz").toDoubleOrNull()
        if (hz == null || hz <= 0) {
            Log.w(TAG, "unparseable rate '${sub.rate}' on ${sub.function} — treated as uncapped")
            return null
        }
        return (1000.0 / hz).toLong().coerceAtLeast(1)
    }

    /** A numeric optional field, or null if blank; a present-but-non-numeric value is logged and ignored. */
    private fun numericField(raw: String, name: String, sub: Subscription): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toDoubleOrNull() ?: run {
            Log.w(TAG, "non-numeric $name '$raw' on ${sub.function} — ignored")
            null
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val TAG = "DashStreams"
    }
}
