package com.dash.android.time

import kotlinx.coroutines.flow.StateFlow

/**
 * The machine's clock, as DASH's Date & Time tab sees it (DASH-AA 1.1.8): the time zone, automatic time
 * from the internet, and setting the clock by hand.
 *
 * **The seam between DASH and the platform**, as [com.dash.android.power.PowerSystem] is for power. The tab
 * and [TimeRules] only ever talk to this interface. DASH-AA's implementation asks the machine's own time
 * service (`time/linux/`), the same one a desktop's date settings ask.
 * **For native:** Android will not let an app set the clock or the zone; native's implementation reports
 * the zone and whether Android sets the time automatically, with [TimeState.canChange] false, and the tab
 * points to Android's own Date & Time instead.
 *
 * **No password, ever** (Roger, 2026-10-21: DASH has no passwords; the key in the ignition is its
 * password). Where the machine would want one, [TimeState.canChange] is false, the tab shows the clock as
 * it is and says how to let DASH change it — it never brings up a password box.
 *
 * Every call returns at once; the work happens off the caller's thread.
 */
interface TimeSystem {
    val state: StateFlow<TimeState>

    fun start()

    /** Set the clock from the internet (true), or leave it to be set by hand (false). */
    fun setAutomatic(on: Boolean)

    /** Make [zone] (one of [TimeState.zones], as "Europe/London") the machine's time zone. */
    fun setZone(zone: String)

    /** Set the clock to [epochMillis] — only while automatic time is off; the machine refuses otherwise. */
    fun setTime(epochMillis: Long)
}

data class TimeState(
    val available: Boolean,
    val note: String,
    /** The machine's time zone, as "Europe/London"; null when it does not say. */
    val zone: String? = null,
    /** True when the clock is set from the internet. */
    val automatic: Boolean = false,
    /** Whether this machine can set its clock from the internet at all (it has a time-sync service). */
    val canAutomatic: Boolean = false,
    /** True once the clock has been set from the internet and is keeping in step. */
    val synced: Boolean = false,
    /**
     * Whether DASH may change the clock and the zone without a password: true yes, false no (the machine
     * would ask for one), null when it cannot tell — then DASH tries, and [failure] says if it was refused.
     */
    val canChange: Boolean? = null,
    /** Every zone the machine knows, as "Region/City". */
    val zones: List<String> = emptyList(),
    /** The last request that did not happen, in plain words; null when the last one did. */
    val failure: String? = null,
)
