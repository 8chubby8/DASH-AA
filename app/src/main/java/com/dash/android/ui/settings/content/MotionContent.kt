package com.dash.android.ui.settings.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.motion.LocalDashTransitions
import com.dash.android.ui.motion.TransitionId
import com.dash.android.ui.motion.TransitionSpeed
import kotlinx.coroutines.launch
import com.dash.android.ui.common.SETTING_SPACING

/**
 * Appearance › Transitions (roadmap 1.5.5). The rule: **if it's a transition, it goes here.** A
 * *master pace* sets every transition at once; each transition then breaks out to its own control, so
 * the user can dial one slower or off without disturbing the rest — exactly like a game's graphics
 * presets. Touch any single one and the master reads "Custom".
 *
 * The list is driven straight off the [TransitionId] registry, so a new surface's transition gains a
 * control here the moment it registers — no rework. Order is chronological-as-added for now.
 */

@Composable
fun MotionContent() {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { DashPreferences(appContext) }
    val scope = rememberCoroutineScope()
    val speeds = TransitionSpeed.entries

    // Read the live registry provided at the composition root, not a fresh collector. It is populated
    // once at app launch and stays alive across all settings navigation, so re-entering this tab shows
    // the stored speeds immediately — no one-frame flash through the defaults while DataStore loads.
    val transitions = LocalDashTransitions.current
    fun current(id: TransitionId): TransitionSpeed = transitions.speed(id)

    // Master is *derived*, never stored: if every transition shares one speed it shows that speed;
    // if they diverge it shows nothing selected and wears the "Custom" tag. Re-tapping a preset
    // re-syncs everything to it.
    val master: TransitionSpeed? = TransitionId.entries.map { current(it) }.distinct().singleOrNull()

    Column(modifier = Modifier.fillMaxWidth()) {

        // The page is titled once; its two sections sit a rank below (roadmap 1.5.15).
        SettingsContentHeader("Transitions")

        // ── Master pace ────────────────────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("Master pace")

            SettingBlock(
                name = "Every transition at once",
                tag = if (master == null) "Custom" else null,
                fullWidthControl = true,
                control = {
                    FitPresetSegment(
                        labels = speeds.map { it.label },
                        selected = master?.let { speeds.indexOf(it) } ?: -1,
                    ) { i -> scope.launch { prefs.setAllTransitions(speeds[i]) } }
                },
            )
        }


        // ── Every transition ───────────────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("One at a time")

            TransitionId.entries.forEach { id ->
                val speed = current(id)
                SettingBlock(
                    name = id.label,
                    fullWidthControl = true,
                    control = {
                        FitPresetSegment(speeds.map { it.label }, speeds.indexOf(speed)) { i ->
                            scope.launch { prefs.setTransition(id, speeds[i]) }
                        }
                    },
                )
            }
        }
    }
}
