package com.dash.android.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sourceless core's system state (roadmap 1.4.7) — the state store and the event bus of
 * arduino.md §5a.
 *
 * This is the **main system** side of the §5 two-layer split. Everything in here is *sourceless*:
 * a value is "the current state of `gear_position`", never "what module X last said" — the sender's
 * id was consumed by the gatekeeper (the broadcast desk) before anything landed here. That is what
 * makes redundant sources for the same signal possible, and it is why nothing in this class ever
 * sees, stores, or exposes a module id.
 *
 * **Deliberately dumb.** The store is a map and the bus is a flow — all intelligence (the gatekeeper,
 * the behaviour lookup, §4b change detection) lives in the desk that calls [store] and [fire], per the
 * crossroads principle: everything clever happens at the crossroads, the destinations stay simple.
 *
 * **Lifetime.** In-memory only, empty on every DASH start, populated by module activation dumps and
 * kept current by heartbeats (§4b) — never persisted, because the vehicle's state outlives no session.
 * The interface era reads current values from [values]; event-driven components collect [events].
 */
class SystemState {

    private val _values = MutableStateFlow<Map<String, StoredSignal>>(emptyMap())
    /** Current value of every stateful signal heard since DASH started, keyed by function name. */
    val values: StateFlow<Map<String, StoredSignal>> = _values.asStateFlow()

    private val _events = MutableSharedFlow<SystemEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Events as they fire — store-and-event changes and event-only signals. Replays recent history
     *  to a new collector so a freshly-opened inspector isn't blank. */
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    /** Current stored value of [function], or null if never heard. The desk's §4b comparison reads this. */
    fun current(function: String): String? = _values.value[function]?.value

    /** Update the store. Called by the broadcast desk only, after its change detection has decided
     *  the value genuinely differs. */
    fun store(function: String, value: String) {
        _values.value = _values.value + (function to StoredSignal(value, System.currentTimeMillis()))
    }

    /** Fire an event onto the bus. [value] is null for event-only signals — the firing is the content. */
    fun fire(function: String, value: String?) {
        _events.tryEmit(SystemEvent(function, value, System.currentTimeMillis()))
    }

    private companion object {
        const val EVENT_REPLAY = 100
    }
}

/** One stateful signal's current value and when it last changed. */
data class StoredSignal(val value: String, val updatedAt: Long)

/** One event fired into the core: a store-and-event signal changing, or an event-only signal firing
 *  ([value] null — momentary controls carry no value; the message is the whole content, §5a). */
data class SystemEvent(val function: String, val value: String?, val at: Long)
