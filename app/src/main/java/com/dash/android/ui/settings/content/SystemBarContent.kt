package com.dash.android.ui.settings.content

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.systembar.BarPosition
import com.dash.android.ui.systembar.LocalEnterBarEdit
import com.dash.android.ui.systembar.SystemBarConfig
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.CONTROL_WIDTH
import com.dash.android.ui.common.DashButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import com.dash.android.ui.common.controlWidth

/**
 * Layout › System Bar (roadmap 1.5.7). Per interface.md this tab is the *door* to the bar, not a
 * duplicate of the in-edit sliders: **Position** (which edge it docks to, applied live), an **Edit bar
 * layout** entry into the edit-mode workspace where height, zones and element sizing are set on the
 * bar itself, and **Reset** to defaults.
 */

@Composable
fun SystemBarContent() {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { DashPreferences(appContext) }
    val scope = rememberCoroutineScope()
    val enterEdit = LocalEnterBarEdit.current

    val barConfig by prefs.systemBarConfig.collectAsState(initial = SystemBarConfig.default())
    var confirmReset by remember { mutableStateOf(false) }
    // The confirm state is a moment, not a mode — it lapses on its own if the user thinks better of it.
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(3500); confirmReset = false } }

    // Every control in the nook takes the same width, so the four line up as a column rather than
    // each sizing itself to its own words (roadmap 1.5.15, Roger).
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth()) {

        // One flow, not three. The four settings all concern the same bar, so there are no sections
        // to name — the page used to split them across three Columns, which only made the gaps
        // between them uneven (roadmap 1.5.15).
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsContentHeader("System Bar")
            SettingBlock(
                name = "Position",
                control = {
                    PresetSegment(
                        labels = listOf("Bottom", "Top"),
                        selected = if (barConfig.position == BarPosition.TOP) 1 else 0,
                        modifier = controlWidth,
                    ) { i ->
                        val pos = if (i == 1) BarPosition.TOP else BarPosition.BOTTOM
                        scope.launch { prefs.saveSystemBarConfig(barConfig.copy(position = pos)) }
                    }
                },
                preview = { BarPositionPreview(barConfig.position) },
            )
            SettingBlock(
                name = "Zones",
                control = {
                    PresetSegment(
                        labels = listOf("1", "2", "3"),
                        selected = (barConfig.zones.size - 1).coerceIn(0, 2),
                        modifier = controlWidth,
                    ) { i -> scope.launch { prefs.saveSystemBarConfig(barConfig.withZoneCount(i + 1)) } }
                },
            )

            SettingBlock(
                name = "Layout",
                control = { DashButton("Edit bar layout", { enterEdit() }, modifier = controlWidth) },
            )

            SettingBlock(
                name = "Reset",
                tag = if (confirmReset) "Tap again to confirm" else null,
                control = {
                    DashButton(
                        label = if (confirmReset) "Confirm reset" else "Reset to defaults",
                        modifier = controlWidth,
                        onClick = {
                            if (confirmReset) {
                                scope.launch { prefs.saveSystemBarConfig(SystemBarConfig.default()) }
                                confirmReset = false
                            } else {
                                confirmReset = true
                            }
                        },
                    )
                },
            )
        }
    }
}

/** A small "screen" with the bar drawn on its chosen edge — it slides across when the toggle flips. */
@Composable
private fun BarPositionPreview(position: BarPosition) {
    val theme = LocalDashTheme.current
    val bias by animateFloatAsState(
        targetValue = if (position == BarPosition.TOP) -1f else 1f,
        label = "barPosition",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D0D14))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
    ) {
        Box(
            Modifier
                .align(BiasAlignment(0f, bias))
                .fillMaxWidth()
                .height(24.dp)
                .background(theme.backgroundColourPrimary),
        ) {
            // A few footprint boxes so the strip reads as the bar, not just a band.
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(width = 16.dp, height = 9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(theme.iconColourPrimary.copy(alpha = 0.45f)),
                    )
                }
            }
        }
    }
}
