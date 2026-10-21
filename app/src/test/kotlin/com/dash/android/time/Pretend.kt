package com.dash.android.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A machine's clock, for the tab's pictures and the rule's tests: London, set from the internet, DASH allowed to change it. */
class PretendTime(start: TimeState = ALLOWED) : TimeSystem {
    val s = MutableStateFlow(start)
    override val state: StateFlow<TimeState> = s
    val done = mutableListOf<String>()

    override fun start() {}
    override fun setAutomatic(on: Boolean) { done += "automatic $on"; s.value = s.value.copy(automatic = on) }
    override fun setZone(zone: String) { done += "zone $zone"; s.value = s.value.copy(zone = zone) }
    override fun setTime(epochMillis: Long) { done += "time $epochMillis" }

    companion object {
        private val ZONES = listOf("Europe/London", "Europe/Paris", "Europe/Dublin", "America/New_York", "America/Argentina/Buenos_Aires", "Asia/Kolkata", "UTC")

        val ALLOWED = TimeState(
            available = true, note = "", zone = "Europe/London", automatic = true, canAutomatic = true,
            synced = true, canChange = true, zones = ZONES,
        )

        /** No rule installed: the machine would want a password. */
        val REFUSED = ALLOWED.copy(canChange = false)

        /** Set by hand, no time-sync service. */
        val BY_HAND = ALLOWED.copy(automatic = false, canAutomatic = false, synced = false)
    }
}
