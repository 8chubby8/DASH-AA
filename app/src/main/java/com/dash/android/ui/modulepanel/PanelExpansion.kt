package com.dash.android.ui.modulepanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Whether the panel is currently expanded, and the timer that folds it back (roadmap 1.6.9).
 *
 * **The panel has a resting state and an active one**, and a tap on the tab bar moves between them.
 * Under [PanelVisibility.RETRACTED] the resting state is off screen behind the panel's own edge;
 * under [PanelVisibility.SHRUNK] it is a smaller layout the module's author drew. Either way the
 * user pays screen for the *resting* state only — the expanded panel is drawn over the viewport
 * rather than pushing it, so the app underneath never relayouts *(Roger, 2026-08-27)*.
 *
 * **The timer must not fire while the panel is being used.** A panel that folded shut under a
 * finger mid-adjustment would be a fault rather than a feature, so any touch inside the panel
 * restarts the dwell.
 *
 * **Observing a touch is not taking it.** 1.6.8 ruled that a gesture inside the panel boundary
 * belongs to the module and DASH never claims it — hold-to-repeat, drag, swipe-through-presets are
 * all the author's to use. Nothing here claims anything: [noteTouch] is called from the existing
 * press loop after `awaitFirstDown`, which already runs with `requireUnconsumed = false` and leaves
 * an unhandled press unconsumed. DASH learns that a finger landed and does not take the finger.
 */
@Stable
class PanelExpansion internal constructor(
    expanded: Boolean,
    private val onChanged: () -> Unit,
) {
    var expanded: Boolean = expanded
        private set

    /** Bumped by every touch in the panel; the dwell effect is keyed on it, so a touch restarts it. */
    internal var touchTick by mutableIntStateOf(0)
        private set

    fun expand() {
        if (!expanded) { expanded = true; onChanged() }
        touchTick++
    }

    fun collapse() {
        if (expanded) { expanded = false; onChanged() }
    }

    fun toggle() = if (expanded) collapse() else expand()

    /** A finger landed inside the panel. Restarts the dwell; claims nothing. */
    fun noteTouch() {
        if (expanded) touchTick++
    }

    internal fun set(value: Boolean) {
        if (expanded != value) { expanded = value; onChanged() }
    }
}

/**
 * The panel's expansion state, its dwell timer, and the two rules that override both.
 *
 * [expandable] is false for [PanelVisibility.OFF] and [PanelVisibility.FULL] — neither has a larger
 * state to open into, so the panel is permanently at rest and the timer never runs.
 *
 * [forceRest] is the settings panel. **Opening settings puts the panel at rest before anything else
 * happens**, which is what keeps rule 2 and this feature genuinely independent: the settings yield
 * only ever measures a *resting* assembly, and never has to reason about an expanded one. If that
 * ordering were ever reversed, settings would measure an expanded panel and retract things for no
 * reason.
 */
@Composable
fun rememberPanelExpansion(
    expandable: Boolean,
    dwellSeconds: Int,
    forceRest: Boolean,
): PanelExpansion {
    var revision by remember { mutableStateOf(0) }
    val state = remember { PanelExpansion(expanded = false) { revision++ } }

    // Losing the ability to expand — the user switching to Full or Off — must not strand the panel
    // in an expanded state that no longer has a meaning.
    LaunchedEffect(expandable, forceRest) {
        if (!expandable || forceRest) state.set(false)
    }

    // The dwell. Keyed on the touch tick as well as the state, so every touch inside the panel
    // cancels this coroutine and starts a fresh one — which is the whole mechanism, and why it is
    // expressed as a key rather than as a mutable deadline.
    LaunchedEffect(state.expanded, state.touchTick, dwellSeconds, revision) {
        if (!state.expanded || dwellSeconds <= 0) return@LaunchedEffect
        delay(dwellSeconds * 1000L)
        state.collapse()
    }

    return state
}
