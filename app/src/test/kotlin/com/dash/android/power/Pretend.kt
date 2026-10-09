package com.dash.android.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A machine's power, for the tabs' pictures and the rules' tests: a laptop with a lid, a battery and three profiles. */
class PretendPower(start: PowerState = LAPTOP) : PowerSystem {
    val s = MutableStateFlow(start)
    override val state: StateFlow<PowerState> = s
    val done = mutableListOf<String>()
    var lidHeld = false
    var prepare: () -> Unit = {}
    var woke: () -> Unit = {}

    override fun start() {}
    override fun act(action: PowerAction) { done += action.name }
    override fun setProfile(profileId: String) { done += "profile $profileId"; s.value = s.value.copy(activeProfile = profileId) }
    override fun setChargeLimit(on: Boolean) {
        done += "limit $on"
        s.value = s.value.copy(battery = s.value.battery?.let { b -> b.copy(chargeLimit = b.chargeLimit?.copy(on = on)) })
    }
    override fun holdLid(hold: Boolean) { lidHeld = hold }
    override fun onSleep(prepare: () -> Unit, woke: () -> Unit) { this.prepare = prepare; this.woke = woke }

    companion object {
        val LAPTOP = PowerState(
            available = true,
            note = "",
            actions = setOf(PowerAction.SLEEP, PowerAction.RESTART, PowerAction.SHUT_DOWN),
            sleepKind = SleepKind.STANDBY,
            profiles = listOf("power-saver", "balanced", "performance").map { PowerProfile.of(it) },
            activeProfile = "balanced",
            battery = Battery(91, charging = true, full = false, minutesToFull = 23, chargeLimit = ChargeLimit(false, 80, 75)),
            onBattery = false,
            lid = Lid(closed = false),
            desktop = "GNOME",
        )

        /** A mini PC on the car's supply: no battery, no lid, no desktop, no profiles service. */
        val MINI_PC = PowerState(
            available = true,
            note = "",
            actions = setOf(PowerAction.SLEEP, PowerAction.HIBERNATE, PowerAction.RESTART, PowerAction.SHUT_DOWN),
            sleepKind = SleepKind.DEEP,
        )
    }
}
