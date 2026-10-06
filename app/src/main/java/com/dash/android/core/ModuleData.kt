package com.dash.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The per-module data store (roadmap 1.4.9) — the **sourceful** twin of the sourceless core
 * ([SystemState]). Where `SystemState` holds the shared vehicle state a `BROADCAST` fills, this holds
 * the private, agnostic data a module pushes to *its own* panel with `REPORT|id|variable|value`
 * (arduino.md §4, the "specific" column of the 2×2).
 *
 * **The id is the key, never consumed.** This is the one architectural difference from the broadcast
 * path and the whole point of the specific column: a `REPORT` variable belongs to *one* module's panel
 * and nowhere else, so the reports desk keeps the id rather than throwing it away. A value here is
 * "module X's `temperature`", never "the current temperature" — the opposite of a sourceless signal,
 * where redundant sources are a feature. Two modules may each have a `temperature` variable; they are
 * different data in different panels and never collide, because each lives under its own id.
 *
 * **Deliberately dumb**, exactly like [SystemState]: a nested map, all the cleverness (the gatekeeper,
 * the change check) at the desk that calls [store] — the crossroads principle. No event bus here: a
 * panel variable is a value to display, not a momentary control, so there is nothing to fire. (The
 * inbound event side of the specific column — a user operating a control — is `ACTION` going the other
 * way, DASH→module; it stores nothing.)
 *
 * **Lifetime.** In-memory only, empty on every DASH start, kept current by REPORTs while a module is
 * active — never persisted, same as [SystemState]. The module panel (roadmap 1.6.x) is the real reader;
 * today the State Inspector is its window. Data from a module that later uninstalls simply lingers in
 * the map until the next DASH start — harmless (keyed by id, and only installed modules are ever
 * rendered), and consistent with how the sourceless core also never clears itself mid-session.
 */
class ModuleData {

    private val _values = MutableStateFlow<Map<String, Map<String, ReportedValue>>>(emptyMap())
    /** Every active module's reported variables, keyed by module id then by variable name. */
    val values: StateFlow<Map<String, Map<String, ReportedValue>>> = _values.asStateFlow()

    /** Current reported value of [variable] for module [id], or null if never reported. The desk's
     *  change check reads this. */
    fun current(id: String, variable: String): String? = _values.value[id]?.get(variable)?.value

    /** Update the store. Called by the reports desk only, after its gatekeeper and change check. */
    fun store(id: String, variable: String, value: String) {
        val moduleMap = _values.value[id].orEmpty()
        _values.value = _values.value + (id to (moduleMap + (variable to ReportedValue(value, System.currentTimeMillis()))))
    }
}

/** One reported variable's current value and when it last changed. */
data class ReportedValue(val value: String, val updatedAt: Long)
