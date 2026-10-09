package com.dash.android.power

import kotlinx.coroutines.flow.StateFlow

/**
 * The machine's power, as DASH's Power tabs see it (DASH-AA 1.1.7): sleeping, shutting down and
 * restarting, the performance profile, the battery and its charge limit, and the lid.
 *
 * **The seam between DASH and the platform**, as [com.dash.android.display.DisplaySystem] is for the
 * screens. The Power tabs and [PowerRules] only ever talk to this interface. DASH-AA's implementation asks
 * the machine's own services as the seat user — logind, UPower and the power-profiles service
 * (`power/linux/`) — the same ones a desktop's power settings ask, so no root is ever needed.
 * **For native:** Android sleeps and wakes itself; native's implementation reports the battery from
 * `BatteryManager` and everything else absent, so those controls do not appear.
 *
 * **Capability detection, as everywhere.** What this machine does not have is null or absent — no
 * battery, no lid, no profiles, no hibernate — and the tabs leave it out. With no power service at all,
 * [PowerState.available] is false, [PowerState.note] says why in plain words, and nothing else is
 * affected.
 *
 * Every call returns at once; the work happens off the caller's thread.
 */
interface PowerSystem {
    val state: StateFlow<PowerState>

    fun start()

    /** Do [action] now. [PowerState.failure] says so in plain words if the machine refuses. */
    fun act(action: PowerAction)

    /** Make [profileId] (one of [PowerState.profiles]) the machine's performance profile. */
    fun setProfile(profileId: String)

    /** Turn the battery's charge limit on or off, where [Battery.chargeLimit] is offered. */
    fun setChargeLimit(on: Boolean)

    /**
     * Take the lid from the machine (true) — closing it then does only what DASH does — or give it back
     * (false), so the machine does its own thing with it (usually sleep).
     */
    fun holdLid(hold: Boolean)

    /**
     * Run [prepare] each time the machine is about to sleep, before it does — closing Android Auto, quieting
     * the amplifiers — and [woke] each time it wakes. The machine waits a few seconds at most.
     */
    fun onSleep(prepare: () -> Unit, woke: () -> Unit)
}

enum class PowerAction(val label: String) {
    SLEEP("Sleep"),
    HIBERNATE("Hibernate"),
    RESTART("Restart"),
    SHUT_DOWN("Shut Down"),
}

data class PowerState(
    val available: Boolean,
    val note: String,
    /** What the machine will do when asked — capability-detected; anything else is not offered. */
    val actions: Set<PowerAction> = emptySet(),
    /** How this machine sleeps, in plain words, or null when it does not say. */
    val sleepKind: SleepKind? = null,
    /** The performance profiles on offer, slowest first; empty when the machine has no profile service. */
    val profiles: List<PowerProfile> = emptyList(),
    val activeProfile: String? = null,
    /** The battery, or null for a machine with none — a mini PC on the car's supply. */
    val battery: Battery? = null,
    /** True when running from the battery; false on a charger or a machine with no battery. */
    val onBattery: Boolean = false,
    /** The lid, or null for a machine with none. */
    val lid: Lid? = null,
    /** The desktop DASH is running inside ("GNOME", "KDE"), or null with none — what Leave DASH goes back to. */
    val desktop: String? = null,
    /** The last request that did not happen, in plain words; null when the last one did. */
    val failure: String? = null,
)

enum class SleepKind(val label: String, val detail: String) {
    STANDBY(
        "Modern standby",
        "Everything stays in memory and the machine wakes in a moment. It uses a little battery while asleep — " +
            "about what a phone does on standby.",
    ),
    DEEP(
        "Deep sleep",
        "Everything stays in memory and the machine wakes in a few seconds. It uses very little power while asleep.",
    ),
}

data class PowerProfile(val id: String, val label: String, val detail: String) {
    companion object {
        /** The three every profile service offers, by its own names. */
        fun of(id: String) = when (id) {
            "power-saver" -> PowerProfile(id, "Power Saver", "Cooler and quieter, and the battery lasts longest.")
            "balanced" -> PowerProfile(id, "Balanced", "Full speed when it is needed, saving power when it is not.")
            "performance" -> PowerProfile(id, "Performance", "As fast as the machine goes, at the cost of heat, fans and battery.")
            else -> PowerProfile(id, id.replace('-', ' ').replaceFirstChar { it.uppercase() }, "")
        }
    }
}

data class Battery(
    /** 0–100. */
    val percent: Int,
    val charging: Boolean,
    val full: Boolean,
    /** Minutes left, while it runs on the battery; null when unknown. */
    val minutesToEmpty: Int? = null,
    /** Minutes to full, while charging; null when unknown. */
    val minutesToFull: Int? = null,
    /** The charge limit, or null when this battery has none DASH can set. */
    val chargeLimit: ChargeLimit? = null,
)

/** Charging stops at [stopAt] percent and starts again below [startBelow] — set by the machine, turned on or off by DASH. */
data class ChargeLimit(val on: Boolean, val stopAt: Int, val startBelow: Int)

data class Lid(val closed: Boolean)
