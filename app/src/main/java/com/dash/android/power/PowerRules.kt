package com.dash.android.power

import com.dash.android.display.DisplaySystem
import com.dash.android.display.UserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * DASH's power rules (DASH-AA 1.1.7), carried out on the machine through [PowerSystem]:
 * - **Sleep when left alone.** After the chosen minutes with nobody touching DASH, the machine sleeps —
 *   but **never while a phone is projecting** ([busy]): in a car, maps and music are watched and heard
 *   for an hour without a touch.
 * - **A profile on the charger and one on the battery.** Applied when the machine moves from one to the
 *   other, or the choice changes — never fighting a profile changed by hand in between.
 * - **The lid.** Sleep is the machine's own way and DASH leaves the lid alone; Screen off and Nothing take
 *   it, so the music plays on with the lid shut.
 * - **Ready for sleep.** Before the machine sleeps [prepare] runs (Android Auto closed, the amplifiers told);
 *   on waking [woke] runs and the screens count as just touched.
 *
 * Shared code: native follows with the same rules where its [PowerSystem] offers them.
 */
class PowerRules(
    private val power: PowerSystem,
    private val display: DisplaySystem,
    private val settings: Flow<PowerSettings>,
    private val scope: CoroutineScope,
    private val busy: () -> Boolean,
    private val prepare: () -> Unit = {},
    private val woke: () -> Unit = {},
) {
    /** The touch the last idle sleep counted from, so one quiet spell sleeps the machine once. */
    private var sleptFrom = -1L
    private var lidBlanked = false

    fun start() {
        power.onSleep(
            prepare = { prepare() },
            woke = {
                UserActivity.touch()
                woke()
            },
        )
        scope.launch {
            combine(power.state.map { s -> Triple(s.onBattery, s.profiles.map { it.id }, s.battery != null) }.distinctUntilChanged(), settings) { (onBattery, ids, _), s ->
                (if (onBattery) s.profileOnBattery else s.profileOnCharger)?.takeIf { it in ids }
            }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted != null && wanted != power.state.value.activeProfile) power.setProfile(wanted) }
        }
        scope.launch {
            combine(settings.map { it.lid }.distinctUntilChanged(), power.state.map { it.lid != null }.distinctUntilChanged()) { action, hasLid ->
                hasLid && action != LidAction.SLEEP
            }
                .distinctUntilChanged()
                .collect { power.holdLid(it) }
        }
        scope.launch {
            combine(settings.map { it.lid }.distinctUntilChanged(), power.state.map { it.lid?.closed == true }.distinctUntilChanged()) { a, closed -> a to closed }
                .collect { (action, closed) ->
                    when {
                        closed && action == LidAction.SCREEN_OFF -> { lidBlanked = true; display.setScreensOn(false) }
                        !closed && lidBlanked -> { lidBlanked = false; display.setScreensOn(true); UserActivity.touch() }
                    }
                }
        }
        scope.launch { idleLoop() }
    }

    private suspend fun idleLoop() {
        while (true) {
            delay(CHECK_MS)
            val after = settings.first().sleepAfterMinutes * 60_000L
            val last = UserActivity.lastAt
            if (shouldSleep(after, System.currentTimeMillis() - last, last == sleptFrom, busy(), PowerAction.SLEEP in power.state.value.actions)) {
                sleptFrom = last
                power.act(PowerAction.SLEEP)
            }
        }
    }

    internal companion object {
        const val CHECK_MS = 5_000L

        /** The idle-sleep rule on its own, for tests. */
        fun shouldSleep(afterMs: Long, idleMs: Long, alreadyFor: Boolean, busy: Boolean, canSleep: Boolean) =
            afterMs > 0 && idleMs >= afterMs && !alreadyFor && !busy && canSleep
    }
}
