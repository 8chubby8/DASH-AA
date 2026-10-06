package com.dash.android.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The discovery desk (roadmap 1.4.2, with 1.4.3's HELLO parsing merged in). One handler behind the
 * [DashController] router — the router hands it every `HELLO` line; it owns nothing else.
 *
 * As built in 1.4.2, discovery was a **user-driven method of installation**: DASH broadcast
 * `DISCOVER` only on the DISCOVER button, never on a timer, and each press rebuilt the list from
 * scratch. *(Amended for 1.4.6, 2026-07-06, with Roger's agreement: the [Reconciliation] desk now
 * owns the `DISCOVER` broadcast and runs it as a persistent low-rate sweep — reconciliation needs
 * the sweep anyway, and one shared broadcast serves both purposes because the install/reconnect
 * distinction lives in DASH, not on the wire. This desk became the passive collector of the sweep's
 * `HELLO`s: install candidates now appear on Module Management without a button press, entries age
 * out via [prune] when a module stops answering, and the clear-and-rebroadcast [discover] method
 * retired along with the DISCOVER button — its manual role is the SYNC button, which just asks the
 * sweep to run now.)*
 *
 * [onHello] upserts by module id, so a module that answers every sweep appears once, refreshed. The
 * list is app-lifetime state, so it survives closing and reopening the screen.
 */
class Discovery {

    private val _modules = MutableStateFlow<List<DiscoveredModule>>(emptyList())
    val modules: StateFlow<List<DiscoveredModule>> = _modules.asStateFlow()

    /** When each id last answered, for [prune] — the reconciliation sweep supplies the cutoff. */
    private val seenAt = mutableMapOf<String, Long>()

    /** Handle a `HELLO` line routed here by the controller: parse it and upsert by id (last wins). */
    @Synchronized
    fun onHello(line: String) {
        val module = DiscoveredModule.parseHello(line) ?: return
        seenAt[module.id] = System.currentTimeMillis()
        _modules.update { current -> current.filterNot { it.id == module.id } + module }
    }

    /** Drop modules not heard from since [cutoffMs] — unplugged hardware leaves the list the same
     *  way it arrived: by the sweep noticing, no button press needed. */
    @Synchronized
    fun prune(cutoffMs: Long) {
        val stale = seenAt.filterValues { it < cutoffMs }.keys
        if (stale.isEmpty()) return
        stale.forEach(seenAt::remove)
        _modules.update { current -> current.filterNot { it.id in stale } }
    }
}
