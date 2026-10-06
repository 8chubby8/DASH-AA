package com.dash.android.ui.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.TINY_LINE
import com.dash.android.ui.modulepanel.ModulePanelConfig
import com.dash.android.ui.modulepanel.ModulePanelSpec
import com.dash.android.ui.modulepanel.ModuleTabsSpec
import com.dash.android.ui.modulepanel.PanelSize
import com.dash.android.ui.modulepanel.PanelDwellSpec
import com.dash.android.ui.modulepanel.PanelVisibility
import com.dash.android.ui.modulepanel.PanelEdge
import com.dash.android.ui.modulepanel.TabColour
import com.dash.android.ui.modulepanel.TabSpread
import com.dash.android.ui.modulepanel.TabStyle
import com.dash.android.ui.modulepanel.effectiveEdge
import com.dash.android.ui.systembar.BarPosition
import com.dash.android.ui.systembar.SystemBarConfig
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch

/**
 * Layout › Module Panel — how big the panel is (roadmap 1.6.4) and where it docks (1.6.3).
 *
 * **Why Layout.** The panel is a *wall* DASH owns, so its size and placement are structural
 * decisions and belong beside the System Bar and Rotation. What happens *inside* the panel belongs
 * to the module and is never configured here — that is the Module Mantra as a settings tree.
 *
 * **Size first, position second** (Roger, 1.6.4). Size is the larger decision, and the position
 * tiles draw the panel at whatever size is chosen above them, so the page reads top to bottom as one
 * continuous answer rather than two unrelated controls.
 *
 * **Every tile is a miniature of the real screen**, following the Rotation tab's precedent: the
 * device at its true proportions, the user's own bar on the edge it actually occupies, and the panel
 * drawn from the same ratio the real one uses — so a Small tile really is a sixteenth of its edge
 * and the three sizes are honestly to scale against each other. That matters most on the position
 * row, where two tiles show something the user did not literally ask for: the panel and the bar can
 * never share an edge, so the bar's edge is greyed and shows the bar alone.
 *
 * The stored preference is still the edge they picked. Move the bar away and the panel returns
 * there on its own; nothing the user chose is ever quietly rewritten.
 */
@Composable
fun ModulePanelContent() {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { DashPreferences(appContext) }
    val scope = rememberCoroutineScope()

    val config by prefs.modulePanelConfig.collectAsState(initial = ModulePanelConfig.default())
    val barConfig by prefs.systemBarConfig.collectAsState(initial = SystemBarConfig.default())

    // The device's own proportions, so the tiles picture this screen rather than a generic one.
    val metrics = remember { appContext.resources.displayMetrics }
    val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
    val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
    val ratio = if (longSide > 0f) shortSide / longSide else 0.6f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SETTING_SPACING),
    ) {
        SettingsContentHeader("Module Panel")

        val barAtTop = barConfig.position == BarPosition.TOP
        val drawnNow = effectiveEdge(config.edge, barConfig.position)

        /*
         * **The page is built from the visibility choice** (Roger, 2026-08-27). Picking one of the
         * four decides which other controls exist at all, rather than showing four controls of which
         * two are meaningless most of the time. The pay-off is that the resting-size constraint
         * becomes *structural*: the full-size list is built from the resting size, so a resting
         * panel thicker than its full panel cannot be expressed, and therefore never has to be
         * detected, warned about, or corrected.
         */
        SettingsSectionHeader("Visibility")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            PanelVisibility.entries.forEach { mode ->
                VisibilityTile(
                    label = mode.label,
                    selected = config.visibility == mode,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch { prefs.saveModulePanelConfig(config.copy(visibility = mode)) }
                }
            }
        }

        // Off needs nothing else. DASH has no panel and no opinion about one, which is the default
        // state of a fresh install (Roger) — the whole screen is the user's until they ask for a
        // panel, and there is therefore no default *size* for DASH to have picked on their behalf.
        if (config.visibility == PanelVisibility.OFF) return@Column

        SettingsSectionHeader("Position")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            PanelEdge.entries.forEach { edge ->
                PanelTile(
                    label = edge.label,
                    selected = config.edge == edge,
                    unavailable = edge == (if (barAtTop) PanelEdge.TOP else PanelEdge.BOTTOM),
                    ratio = ratio,
                    barAtTop = barAtTop,
                    drawnEdge = effectiveEdge(edge, barConfig.position),
                    panelSize = config.size,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch { prefs.saveModulePanelConfig(config.copy(edge = edge)) }
                }
            }
        }

        /*
         * **Resting size first, then full size** — the order matters, because it is what keeps both
         * lists non-empty. Choosing the rest first leaves at least one thicker size to expand into;
         * choosing the full first could leave nothing thinner to rest at.
         *
         * **Neither list is filtered by what the installed modules actually ship** *(Roger)*. These
         * are DASH's own surfaces describing DASH's own capabilities, not a report on somebody's
         * modules — the same reasoning that rejected putting module counts on the size tiles at
         * 1.6.8, where it would have been DASH narrating a choice the user made and can unmake.
         * Install a module tomorrow that ships the missing layout and the setting starts working
         * with nothing changed. Until then it degrades at runtime, silently and safely.
         */
        if (config.visibility == PanelVisibility.SHRUNK) {
            SettingsSectionHeader("Rest size")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                ModulePanelConfig.restChoices().forEach { size ->
                    PanelTile(
                        label = size.label,
                        selected = config.restSize == size,
                        unavailable = false,
                        ratio = ratio,
                        barAtTop = barAtTop,
                        drawnEdge = drawnNow,
                        panelSize = size,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Choosing a thicker rest can invalidate the full size, so it is pulled back
                        // to the thickest that is still legal in the same write. The alternative is
                        // an error state, and there is no error here — only a stale pairing.
                        val full = ModulePanelConfig.fullChoices(size)
                        val keep = if (config.size in full) config.size else full.first()
                        scope.launch {
                            prefs.saveModulePanelConfig(config.copy(restSize = size, size = keep))
                        }
                    }
                }
            }
        }

        val fullChoices = if (config.visibility == PanelVisibility.SHRUNK)
            ModulePanelConfig.fullChoices(config.restSize) else PanelSize.entries.toList()

        SettingsSectionHeader(if (config.visibility == PanelVisibility.FULL) "Size" else "Full size")

        if (fullChoices.size == 1) {
            // **A one-item list is not a control** (1.5.15's no-dead-controls rule). It is still
            // worth showing — hide it and the user cannot see what "full" actually is — so it reads
            // as a statement of the only remaining pairing rather than as a choice to make.
            Text(
                text = "${fullChoices.first().label} — the only size larger than the resting size",
                color = LocalDashTheme.current.textColourPrimary,
                fontFamily = LocalDashTheme.current.font,
                fontSize = TINY,
                lineHeight = TINY_LINE,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                fullChoices.forEach { size ->
                    PanelTile(
                        label = size.label,
                        selected = config.size == size,
                        unavailable = false,
                        ratio = ratio,
                        barAtTop = barAtTop,
                        drawnEdge = drawnNow,
                        panelSize = size,
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch { prefs.saveModulePanelConfig(config.copy(size = size)) }
                    }
                }
            }
        }

        if (config.visibility.expands) {
            SettingsSectionHeader("Timer")
            SettingBlock(
                name = "Folds back after",
                help = "How long the panel stays expanded once you tap a tab. Touching anything " +
                    "inside the panel starts the count again, so it never folds away while you are " +
                    "using it.",
                control = {
                    val v = config.dwellSeconds
                    Stepper(
                        value = "$v s",
                        sub = if (v <= PanelDwellSpec.MIN_SECONDS) "min"
                        else if (v >= PanelDwellSpec.MAX_SECONDS) "max" else null,
                        modifier = Modifier.width(controlWidth(LocalDensity.current.fontScale)),
                        onMinus = {
                            val n = (v - PanelDwellSpec.STEP_SECONDS)
                                .coerceAtLeast(PanelDwellSpec.MIN_SECONDS)
                            scope.launch { prefs.saveModulePanelConfig(config.copy(dwellSeconds = n)) }
                        },
                        onPlus = {
                            val n = (v + PanelDwellSpec.STEP_SECONDS)
                                .coerceAtMost(PanelDwellSpec.MAX_SECONDS)
                            scope.launch { prefs.saveModulePanelConfig(config.copy(dwellSeconds = n)) }
                        },
                    )
                },
            )
        }

        /*
         * **Return to main** (roadmap 1.6.11). The main module itself is chosen in Modules › Module
         * Management, against the module's own record; this is panel behaviour, so it sits with the
         * panel's other timer. Never by default (Roger) — DASH does nothing here until asked.
         */
        SettingsSectionHeader("Main module")
        SettingBlock(
            name = "Return to main after",
            help = "How long another module stays on the panel before the main module comes back. " +
                "Touching the panel starts the count again. Choose the main module in Modules › " +
                "Module Management.",
            control = {
                val stops = ModulePanelConfig.RETURN_STOPS
                val v = config.returnSeconds
                val i = stops.indexOfFirst { it >= v }.let { if (it < 0) stops.lastIndex else it }
                Stepper(
                    value = when {
                        v <= 0 -> "Never"
                        v < 60 -> "$v s"
                        v % 60 == 0 -> "${v / 60} min"
                        else -> "${v / 60} min ${v % 60} s"
                    },
                    sub = if (config.mainModuleId == null) "no main set"
                        else if (i == stops.lastIndex) "max" else null,
                    modifier = Modifier.width(controlWidth(LocalDensity.current.fontScale)),
                    onMinus = {
                        val n = stops[(i - 1).coerceAtLeast(0)]
                        scope.launch { prefs.saveModulePanelConfig(config.copy(returnSeconds = n)) }
                    },
                    onPlus = {
                        val n = stops[(i + 1).coerceAtMost(stops.lastIndex)]
                        scope.launch { prefs.saveModulePanelConfig(config.copy(returnSeconds = n)) }
                    },
                )
            },
        )

        // Selector last, and last on purpose (Roger, 2026-08-26). The page reads as one continuous
        // answer top to bottom — what the panel does, where it sits, how big it is, then the bar
        // that hangs off it. The bar is the smallest of the decisions and the only one that means
        // nothing until there are two modules to switch between, so it earns the bottom.
        SettingsSectionHeader("Selector")

        // **Shown or hidden first** — like Visibility above, the rest of the section is built from
        // it. Hiding needs no guard (roadmap 1.6.9): rule 2 guarantees this page can always be
        // reached, so the bar can always be brought back from here.
        ChoiceRow(
            choices = listOf(true to "Shown", false to "Hidden"),
            selected = config.tabShown,
        ) { v -> scope.launch { prefs.saveModulePanelConfig(config.copy(tabShown = v)) } }

        if (!config.tabShown) return@Column

        SettingBlock(
            name = "Selector size",
            help = "How thick the module tab bar is. It sits outside the panel, so its thickness " +
                "comes out of the content area rather than out of the module's box.",
            control = {
                val thickness = config.tabThicknessDp
                val atMin = thickness <= ModuleTabsSpec.MIN_DP
                val atMax = thickness >= ModuleTabsSpec.MAX_DP
                Stepper(
                    value = "$thickness dp",
                    sub = if (atMin) "min" else if (atMax) "max" else null,
                    modifier = Modifier.width(controlWidth(LocalDensity.current.fontScale)),
                    onMinus = {
                        val v = (thickness - ModuleTabsSpec.STEP_DP).coerceAtLeast(ModuleTabsSpec.MIN_DP)
                        scope.launch { prefs.saveModulePanelConfig(config.copy(tabThicknessDp = v)) }
                    },
                    onPlus = {
                        val v = (thickness + ModuleTabsSpec.STEP_DP).coerceAtMost(ModuleTabsSpec.MAX_DP)
                        scope.launch { prefs.saveModulePanelConfig(config.copy(tabThicknessDp = v)) }
                    },
                )
            },
        )

        SettingsSectionHeader("Tab style")
        ChoiceRow(
            choices = TabStyle.entries.map { it to it.label },
            selected = config.tabStyle,
        ) { v -> scope.launch { prefs.saveModulePanelConfig(config.copy(tabStyle = v)) } }

        // Start and End are named for the bar as it is actually drawn — left and right on a
        // horizontal bar, top and bottom on a vertical one — so the words match what moves.
        SettingsSectionHeader("Spread")
        ChoiceRow(
            choices = TabSpread.entries.map { spread ->
                spread to when (spread) {
                    TabSpread.START -> if (drawnNow.horizontal) "Left" else "Top"
                    TabSpread.END -> if (drawnNow.horizontal) "Right" else "Bottom"
                    else -> spread.label
                }
            },
            selected = config.tabSpread,
        ) { v -> scope.launch { prefs.saveModulePanelConfig(config.copy(tabSpread = v)) } }

        SettingsSectionHeader("Colour")
        ChoiceRow(
            choices = TabColour.entries.map { it to it.label },
            selected = config.tabColour,
        ) { v -> scope.launch { prefs.saveModulePanelConfig(config.copy(tabColour = v)) } }
    }
}

/** A row of plain labelled tiles, one per choice — the same tile Visibility uses. */
@Composable
private fun <T> ChoiceRow(choices: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        choices.forEach { (value, label) ->
            VisibilityTile(label = label, selected = value == selected, modifier = Modifier.weight(1f)) {
                onPick(value)
            }
        }
    }
}

/**
 * One visibility choice — the control the rest of the page is built from.
 *
 * Deliberately a plain labelled tile rather than a miniature like [PanelTile]: what these four
 * describe is *behaviour over time*, and a still picture of a screen cannot show a panel that folds
 * away after ten seconds. Drawing one would suggest a difference in shape where the difference is
 * in motion. The word is the honest control here.
 */
@Composable
private fun VisibilityTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) theme.textColourSecondary.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                if (selected) 2.dp else 1.dp,
                theme.textColourSecondary.copy(alpha = if (selected) 0.75f else 0.18f),
                shape,
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = theme.textColourSecondary.copy(alpha = if (selected) 1f else 0.72f),
            fontSize = TINY,
            lineHeight = TINY_LINE,
            textAlign = TextAlign.Center,
            fontFamily = theme.font,
        )
    }
}

@Composable
private fun PanelTile(
    label: String,
    selected: Boolean,
    unavailable: Boolean,
    ratio: Float,
    barAtTop: Boolean,
    drawnEdge: PanelEdge,
    panelSize: PanelSize,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val shape = RoundedCornerShape(11.dp)
    // Greyed out rather than removed: the set of edges is fixed at four, and dropping one would
    // reshuffle the row every time the bar moved. A dimmed tile keeps the four in place and says
    // why this one cannot be had.
    val dim = if (unavailable) 0.34f else 1f
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) theme.textColourSecondary.copy(alpha = 0.14f * dim) else Color.Transparent
            )
            .border(
                if (selected) 2.dp else 1.dp,
                theme.textColourSecondary.copy(alpha = (if (selected) 0.75f else 0.18f) * dim),
                shape,
            )
            .then(if (unavailable) Modifier else Modifier.clickable { onClick() })
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.height(GLYPH_BOX).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ScreenGlyph(ratio, barAtTop, drawnEdge, panelSize, selected, unavailable)
        }
        Text(
            if (unavailable) "$label — bar" else label,
            color = theme.textColourSecondary.copy(alpha = (if (selected) 1f else 0.72f) * dim),
            fontSize = TINY,
            lineHeight = TINY_LINE,
            textAlign = TextAlign.Center,
            fontFamily = theme.font,
        )
    }
}

/**
 * The screen at its real proportions, with the bar on its edge and the panel on the edge it will
 * actually be drawn on. The panel block is drawn at roughly its true share of the screen so the
 * tiles read as a picture of the result rather than as four identical diagrams.
 */
@Composable
private fun ScreenGlyph(
    ratio: Float,
    barAtTop: Boolean,
    drawnEdge: PanelEdge,
    panelSize: PanelSize,
    selected: Boolean,
    unavailable: Boolean,
) {
    val theme = LocalDashTheme.current
    val dim = if (unavailable) 0.34f else 1f
    val ink = theme.textColourSecondary.copy(alpha = (if (selected) 0.95f else 0.55f) * dim)
    val panelInk = theme.textColourSecondary.copy(alpha = (if (selected) 0.55f else 0.3f) * dim)
    val shape = RoundedCornerShape(3.dp)

    // Landscape proportions, matching how a head unit is usually mounted.
    val w = GLYPH_LONG
    val h = GLYPH_LONG * ratio

    Box(
        modifier = Modifier.size(w, h).clip(shape).border(1.5.dp, ink, shape),
    ) {
        // The bar — full width on its own edge, always senior.
        Box(
            Modifier
                .align(if (barAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BAR_THICKNESS)
                .padding(horizontal = 2.dp)
                .background(ink)
        )
        // An unavailable tile draws the bar alone — no panel. Showing the displaced panel here would
        // contradict the label: the tile says this edge belongs to the bar, so the picture should
        // say the same rather than illustrating a fallback the user cannot currently choose.
        if (unavailable) return@Box

        // The panel. On a vertical edge it runs the screen height *less the bar* and butts against
        // the end away from the bar — the same geometry as the real layout, where the bar's edge is
        // the one the panel has to clear.
        // Thickness is derived from the same ratio the real panel uses, against the same long edge,
        // so a Small tile really is a sixteenth of its edge and the three sizes are honestly to
        // scale against each other rather than three arbitrary bands.
        when (drawnEdge) {
            PanelEdge.TOP, PanelEdge.BOTTOM -> Box(
                Modifier
                    .align(if (drawnEdge == PanelEdge.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(ModulePanelSpec.thicknessFor(panelSize, w).coerceAtLeast(GLYPH_MIN_THICK))
                    .background(panelInk)
            )
            PanelEdge.LEFT, PanelEdge.RIGHT -> Box(
                Modifier
                    .align(
                        when {
                            drawnEdge == PanelEdge.LEFT && barAtTop -> Alignment.BottomStart
                            drawnEdge == PanelEdge.LEFT -> Alignment.TopStart
                            barAtTop -> Alignment.BottomEnd
                            else -> Alignment.TopEnd
                        }
                    )
                    .width(
                        ModulePanelSpec.thicknessFor(panelSize, h - BAR_THICKNESS)
                            .coerceAtLeast(GLYPH_MIN_THICK)
                    )
                    .height(h - BAR_THICKNESS)
                    .background(panelInk)
            )
        }
    }
}

private val TILE_GAP = 10.dp
private val GLYPH_BOX = 58.dp
private val GLYPH_LONG = 52.dp
private val BAR_THICKNESS = 5.dp

/** A floor so Small stays visible in a 52dp glyph, where a true sixteenth would be barely a hair. */
private val GLYPH_MIN_THICK: Dp = 3.dp
