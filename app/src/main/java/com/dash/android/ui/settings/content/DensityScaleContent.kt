package com.dash.android.ui.settings.content

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.scale.DASH_TEXT_SCALE_MAX
import com.dash.android.ui.scale.DASH_TEXT_SCALE_MIN
import com.dash.android.ui.scale.DASH_TEXT_SCALE_STEP
import com.dash.android.ui.systembar.SystemBarConfig
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.CONTROL_WIDTH
import com.dash.android.ui.common.DashButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import com.dash.android.ui.common.controlWidth

/**
 * Appearance › Size & Scale (roadmap 1.5.3). Two clearly separated sections, each under its own
 * heading so DASH's settings never blur into Android's:
 *
 *  **DASH Scale** — DASH's own chrome, each surface on its own ± stepper: system bar size, element
 *  size, the (not-yet-built) app-favourites bar, and DASH text size (applied at the composition
 *  root in MainScreen, so DASH text follows this and ignores Android's font setting).
 *
 *  ~~**Android Density**~~ — *DASH-AA: dropped (Roger, 2026-10-05).* It set Android's system density
 *  for the viewport apps; DASH-AA's viewport is Android Auto, whose own density is negotiated with
 *  the phone and lives in Layout › Android Auto.
 *
 * Each stepper persists on the tap, so the bar and the panel's own text resize immediately.
 */

private fun snapTenth(v: Float): Float = (v * 10).roundToInt() / 10f

@Composable
fun SizeScaleContent() {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { DashPreferences(appContext) }
    val scope = rememberCoroutineScope()
    val barConfig by prefs.systemBarConfig.collectAsState(initial = SystemBarConfig.default())
    val dashTextScale by prefs.dashTextScale.collectAsState(initial = 1.0f)

    // Every control in the nook shares one width — steppers, segments and buttons alike — so the
    // right-hand column reads as a column (roadmap 1.5.15, Roger).
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth()) {

        // The page is titled once, and its two sections sit a rank below it — DASH's own sizing, then
        // Android's. Before 1.5.15 both sections wore the page-title heading, so nothing said which
        // of them the page was actually about (Roger).
        SettingsContentHeader("Size & Scale")

        // ── DASH Scale ───────────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SETTING_SPACING),
        ) {
            SettingsSectionHeader("DASH Scale")

            SettingBlock(
                name = "System bar size",
                control = {
                    Stepper(
                        value = "${barConfig.heightDp} dp",
                        modifier = controlWidth,
                        onMinus = {
                            val h = (barConfig.heightDp - SystemBarConfig.HEIGHT_STEP_DP)
                                .coerceAtLeast(SystemBarConfig.MIN_HEIGHT_DP)
                            val element = barConfig.elementHeightDp
                                .coerceIn(SystemBarConfig.MIN_ELEMENT_HEIGHT_DP, h - SystemBarConfig.HEIGHT_STEP_DP)
                            scope.launch { prefs.saveSystemBarConfig(barConfig.copy(heightDp = h, elementHeightDp = element)) }
                        },
                        onPlus = {
                            val h = (barConfig.heightDp + SystemBarConfig.HEIGHT_STEP_DP)
                                .coerceAtMost(SystemBarConfig.MAX_HEIGHT_DP)
                            scope.launch { prefs.saveSystemBarConfig(barConfig.copy(heightDp = h)) }
                        },
                    )
                },
            )

            SettingBlock(
                name = "Element size",
                control = {
                    val elementMax = (barConfig.heightDp - SystemBarConfig.HEIGHT_STEP_DP)
                        .coerceAtLeast(SystemBarConfig.MIN_ELEMENT_HEIGHT_DP)
                    Stepper(
                        value = "${barConfig.elementHeightDp} dp",
                        modifier = controlWidth,
                        onMinus = {
                            val e = (barConfig.elementHeightDp - SystemBarConfig.ELEMENT_HEIGHT_STEP_DP)
                                .coerceAtLeast(SystemBarConfig.MIN_ELEMENT_HEIGHT_DP)
                            scope.launch { prefs.saveSystemBarConfig(barConfig.copy(elementHeightDp = e)) }
                        },
                        onPlus = {
                            val e = (barConfig.elementHeightDp + SystemBarConfig.ELEMENT_HEIGHT_STEP_DP)
                                .coerceAtMost(elementMax)
                            scope.launch { prefs.saveSystemBarConfig(barConfig.copy(elementHeightDp = e)) }
                        },
                    )
                },
            )

            SettingBlock(
                name = "App favourites bar size",
                tag = "Arrives with the App Launcher · 1.8.x",
                control = { Stepper(value = "—", enabled = false, modifier = controlWidth, onMinus = {}, onPlus = {}) },
            )

            SettingBlock(
                name = "DASH text size",
                control = {
                    Stepper(
                        value = "%.1f×".format(dashTextScale),
                        modifier = controlWidth,
                        onMinus = {
                            val v = snapTenth(dashTextScale - DASH_TEXT_SCALE_STEP)
                                .coerceIn(DASH_TEXT_SCALE_MIN, DASH_TEXT_SCALE_MAX)
                            scope.launch { prefs.saveDashTextScale(v) }
                        },
                        onPlus = {
                            val v = snapTenth(dashTextScale + DASH_TEXT_SCALE_STEP)
                                .coerceIn(DASH_TEXT_SCALE_MIN, DASH_TEXT_SCALE_MAX)
                            scope.launch { prefs.saveDashTextScale(v) }
                        },
                    )
                },
            )
        }


    }
}
