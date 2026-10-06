package com.dash.android.transport

import android.util.Log
import com.dash.android.core.SignalBehaviour
import com.dash.android.core.SystemCommands
import com.dash.android.core.SystemState

/**
 * The broadcast desk (roadmap 1.4.7) — system message routing. `BROADCAST|id|function|value` arrives
 * from a SYSTEM module; what leaves this desk is a *sourceless* update to the core's [SystemState].
 *
 * **This desk is the §5 gatekeeper.** It is the one place source-awareness exists on the system-message
 * path: it checks the sender is an installed, currently-ACTIVE module, then **consumes the id** — the
 * store and the event bus never learn who sent what, which is what keeps the core sourceless and
 * redundant signal sources possible. A broadcast from an unknown or dormant module is dropped (and
 * logged) — it still shows on the Serial Monitor wire tap, so the two instruments together make the
 * gatekeeper visible: the monitor shows a line arriving, the inspector shows it being refused.
 *
 * **The behaviour belongs to the signal (§5a).** Each function is looked up in [SystemCommands]:
 * store-and-event, store-only, or event-only. The module neither knows nor declares which. Unknown
 * functions are dropped and logged — the custom-signal fallthrough and patch-bay override are the
 * crossroads' later configurable stages, not 1.4.7.
 *
 * **Change detection lives here (§4b).** Modules report *current state*, not changes — the activation
 * dump and the ~5 s heartbeat resend every value whether or not anything moved. This desk compares each
 * stateful value against the store: different → store (and fire, if store-and-event); same → nothing.
 * That single comparison is the entire DASH side of §4b — it is what makes the dump and heartbeat
 * ordinary traffic, the store self-healing, and event listeners safe from heartbeat floods. Event-only
 * signals are never deduplicated: each firing is a fresh press.
 *
 * **Live broadcast traffic counts as being heard.** A module streaming ten times a second obviously
 * isn't absent, so the desk reports the sender to the reconciliation desk's liveness clock — without
 * this, a chatty module could age to DORMANT between sweeps and have its data refused mid-stream.
 */
class Broadcasts(
    private val state: SystemState,
    private val isInstalled: (String) -> Boolean,
    private val isActive: (String) -> Boolean,
    private val heard: (String) -> Unit
) {

    /** `BROADCAST|id|function|value` — value absent on event-only controls (its absence is the signal). */
    fun onBroadcast(line: String) {
        val parts = line.split('|')
        val id = parts.getOrNull(1)?.trim().orEmpty()
        val function = parts.getOrNull(2)?.trim().orEmpty()
        if (id.isEmpty() || function.isEmpty()) {
            Log.w(TAG, "malformed broadcast dropped: $line")
            return
        }

        // The gatekeeper. Installed AND active, or the message goes no further (§5).
        if (!isInstalled(id)) {
            Log.i(TAG, "broadcast from uninstalled module $id refused ($function)")
            return
        }
        if (!isActive(id)) {
            Log.i(TAG, "broadcast from dormant module $id refused ($function)")
            return
        }
        heard(id)
        // The id is consumed here. Everything below is sourceless.

        val value = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
        when (SystemCommands.behaviourOf(function)) {
            null -> Log.i(TAG, "unknown signal '$function' dropped — custom fallthrough is future work")

            SignalBehaviour.EVENT_ONLY -> state.fire(function, null)

            SignalBehaviour.STORE_ONLY, SignalBehaviour.STORE_AND_EVENT -> {
                if (value == null) {
                    Log.w(TAG, "stateful signal '$function' arrived with no value — dropped")
                    return
                }
                if (state.current(function) == value) return   // §4b: same value, do nothing
                state.store(function, value)
                if (SystemCommands.behaviourOf(function) == SignalBehaviour.STORE_AND_EVENT) {
                    state.fire(function, value)
                }
            }
        }
    }

    private companion object {
        const val TAG = "DashBroadcast"
    }
}
