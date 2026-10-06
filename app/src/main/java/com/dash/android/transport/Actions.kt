package com.dash.android.transport

import android.util.Log

/**
 * The actions desk (roadmap 1.4.9) — the outbound half of the "specific" column. When a user operates
 * one of a module's panel controls, DASH sends `ACTION|id|control|value` back to that module
 * (arduino.md §4). It is to [ModuleReports] what [Streams] is to [Broadcasts]: the return direction of
 * the same private, one-to-one wire.
 *
 * **Deliberately thin, and that is the design — not a shortcut.** DASH does not, and must not, validate
 * the control id, because it does not know a module's controls: they live inside the panel assets
 * (BLOCKs) DASH does not parse until the module panel exists (roadmap 1.6.x). Validation is the panel's
 * job and it does it for free — the panel only ever *renders* real controls, so the only ids that can
 * ever reach [sendAction] are real ones. This desk is exactly the seam the 1.6.x panel plugs into:
 * render a control, user taps it, call [sendAction] with the id read from the asset. Building it now
 * means the front end is one method call from working (Roger's ask, this session).
 *
 * **It stores nothing.** Like a `LISTEN`, an `ACTION` is a stateless outbound emit — [Streams] is driven
 * by *watching the core*, this is driven by *a user gesture*; neither keeps state. Only the two inbound
 * halves (`BROADCAST`, `REPORT`) fill stores.
 *
 * **The gatekeeper still applies (§5).** An action only ever reaches an installed, ACTIVE module — there
 * is no point sending a control press to a module that is gone or asleep, and doing so would be sending
 * to a dead wire. A dropped action is logged and nothing leaves.
 */
class Actions(
    private val send: (String) -> Unit,
    private val isInstalled: (String) -> Boolean,
    private val isActive: (String) -> Boolean
) {

    /** Send `ACTION|id|control|value` to a module. [value] may be null for a momentary control whose
     *  press carries no value — the field is simply omitted, mirroring an event-only broadcast. */
    fun sendAction(id: String, control: String, value: String? = null) {
        if (id.isBlank() || control.isBlank()) {
            Log.w(TAG, "refusing to send action with blank id/control (id='$id', control='$control')")
            return
        }
        if (!isInstalled(id)) {
            Log.i(TAG, "action to uninstalled module $id dropped ($control)")
            return
        }
        if (!isActive(id)) {
            Log.i(TAG, "action to dormant module $id dropped ($control)")
            return
        }
        val line = if (value.isNullOrEmpty()) "ACTION|$id|$control" else "ACTION|$id|$control|$value"
        send(line)
    }

    private companion object {
        const val TAG = "DashActions"
    }
}
