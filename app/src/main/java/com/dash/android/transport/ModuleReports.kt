package com.dash.android.transport

import android.util.Log
import com.dash.android.core.ModuleData

/**
 * The module reports desk (roadmap 1.4.9) — module message routing. `REPORT|id|variable|value` arrives
 * from an ACCESSORY; what leaves this desk is a value in the per-module [ModuleData] store, still
 * carrying the id it came in on. This is the "specific" column's inbound half — the private mirror of
 * the broadcast desk ([Broadcasts]), which handles the "general" column.
 *
 * **Same gatekeeper as broadcasts (§5).** Sender must be an installed, currently-ACTIVE module or the
 * report goes no further (and is logged). A refused report still shows on the Serial Monitor wire tap,
 * so the two instruments make the gatekeeper visible exactly as they do for broadcasts.
 *
 * **But the id is kept, not consumed — the one real difference.** A broadcast is sourceless: the desk
 * drops the id so redundant sources for `reverse` can coexist. A report is the opposite. `temperature`
 * from module X is *that module's panel data* and must never merge with anyone else's, so the id is the
 * store key (arduino.md §4, "specific"). That single choice is what 1.4.9 is really about.
 *
 * **No behaviour lookup, no event bus.** Unlike a `BROADCAST`, a `REPORT` variable is agnostic — DASH
 * knows nothing about it and holds no vocabulary for it (that would be reaching into the module's box).
 * Every report is simply latest-value-wins into the store; there are no store-only / event-only kinds
 * to distinguish, because a panel variable is always a value to display. The change check below is the
 * same §4b idea as the broadcast desk — an ACCESSORY may resend on a heartbeat/dump like any module, so
 * an unchanged value is dropped before it touches the store — but here it exists only to spare the store
 * needless churn, never to gate an event (there is no event).
 *
 * **Live report traffic counts as being heard**, same as broadcasts: a chattering ACCESSORY feeds the
 * reconciliation liveness clock so it can't age DORMANT mid-stream and have its data refused.
 */
class ModuleReports(
    private val data: ModuleData,
    private val isInstalled: (String) -> Boolean,
    private val isActive: (String) -> Boolean,
    private val heard: (String) -> Unit
) {

    /** `REPORT|id|variable|value` — all three fields required; a report with no value is malformed. */
    fun onReport(line: String) {
        val parts = line.split('|')
        val id = parts.getOrNull(1)?.trim().orEmpty()
        val variable = parts.getOrNull(2)?.trim().orEmpty()
        val value = parts.getOrNull(3)?.trim().orEmpty()
        if (id.isEmpty() || variable.isEmpty() || value.isEmpty()) {
            Log.w(TAG, "malformed report dropped: $line")
            return
        }

        // The gatekeeper. Installed AND active, or the report goes no further (§5).
        if (!isInstalled(id)) {
            Log.i(TAG, "report from uninstalled module $id refused ($variable)")
            return
        }
        if (!isActive(id)) {
            Log.i(TAG, "report from dormant module $id refused ($variable)")
            return
        }
        heard(id)

        // The id is *kept* — it is the store key. This is the specific column's defining choice.
        if (data.current(id, variable) == value) return   // §4b: same value, do nothing
        data.store(id, variable, value)
    }

    private companion object {
        const val TAG = "DashReports"
    }
}
