package com.dash.android.ui.modulepanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.transport.InstalledModule
import com.dash.android.ui.theme.LocalDashTheme

/**
 * The module tab bar — DASH's switch between installed module panels (roadmap 1.6.8).
 *
 * **This exists because the swipe belongs to the module.** *(Roger, 2026-08-19.)* interface.md had
 * modules cycled by swiping inside the panel; they are not. Everything inside the panel boundary is
 * the module's box under the Module Mantra, and a single-finger gesture inside it is the module's
 * input to claim — hold-to-repeat on a stepper, a drag slider, a swipe through presets. **Once DASH
 * takes that gesture it can never give it back** without breaking every module built in the
 * meantime, so it is not taken at all. DASH's control therefore lives *outside* the box: DASH owns
 * the walls, and this makes one wall thick enough to touch.
 *
 * **Tapping a tab goes straight to that module** rather than cycling — six modules are one tap
 * apart, not five. That is also why the switch is a cross-fade rather than a slide: with direct
 * selection you can jump from the first tab to the fourth, so there is nothing meaningful to slide
 * past, and sliding would tell a story about modules living side by side that the bar does not
 * support.
 *
 * **This is the one place DASH may name a module.** The label is the module's own `HELLO` name, and
 * DASH does not decorate it, disambiguate two modules that chose the same one, or annotate it with
 * the state of its board. Two boards running the same firmware are two modules and get two identical
 * tabs *(Roger)*.
 *
 * **Its dress is the user's** — thickness since 1.6.8, and at **1.6.9** tab style, spread, colour
 * pairing and whether the bar shows at all, alongside its second job as the floating panel's peek
 * strip. All of it is DASH's own surface outside the castle walls, which is why it may be dressed.
 */
object ModuleTabsSpec {
    /**
     * The bar's thickness — **the user's, since 2026-08-26** *(Roger)*. The value itself lives in
     * [com.dash.android.ui.modulepanel.ModulePanelConfig.tabThicknessDp], stored with the panel's
     * edge and size because it belongs to that assembly; what lives here is the shape of the
     * control — where it starts and how far it goes.
     *
     * **The bar sits outside the panel and cuts into the viewport** *(Roger)*. Not out of the
     * panel's own footprint: the module's box would then be thinner than the slot ratio it was
     * authored to, and a module drawn for 8 × 3 must be drawn into 8 × 3. The panel keeps its exact
     * shape and the cost lands on content area instead. **That is what makes this a setting rather
     * than a number DASH picks:** the same thickness is nearly free beside a large 8 × 3 panel and a
     * real bite out of a 16 × 1 one, so no single value can serve both, and the person looking at
     * the screen is the one who can see which case they are in.
     *
     * [DEFAULT_DP] is 36 — where the constant stood from 1.6.8, a comfortable target for a gloved
     * hand without spending more viewport than the job needs. It is only a starting point now.
     *
     * **The range is deliberately wide and has no hard floor.** [MIN_DP] is 24 because that is
     * already the smallest DASH will let anything be — `SystemBarConfig.MIN_ELEMENT_HEIGHT_DP` — so
     * it is a floor the system has agreed to elsewhere rather than a new opinion invented here. 24dp
     * *is* small for a gloved hand, and that is the user's call to make: interface.md reserves hard
     * floors for safety-critical targets, and the reachability question this bar really raises —
     * what happens when the only way off a module is made too small to hit, or hidden altogether —
     * is answered properly at 1.6.9 rather than pre-empted with a floor here.
     *
     * [STEP_DP] is 4, matching every other size stepper in DASH. A different feel under the thumb on
     * one control and not the others would read as a fault rather than a choice.
     *
     * *There is no global UI scale to bind any of this to: the old fluid `LocalDashScale` was removed
     * at 1.5.15, and per-surface sizing — each surface on its own stepper — is the established
     * pattern this now joins.*
     */
    const val DEFAULT_DP = 36
    const val MIN_DP = 24
    const val MAX_DP = 96
    const val STEP_DP = 4
}

/**
 * The tab bar, laid out along the panel's inboard edge.
 *
 * [horizontal] follows the *panel's* orientation rather than the bar's own reading direction: a
 * panel docked top or bottom gives a wide bar with tabs side by side, and a panel docked left or
 * right gives a tall one with tabs stacked and their labels turned to read bottom-to-top.
 *
 * [style], [spread] and [colour] are the user's since 1.6.9. **Under [TabSpread.FILL] every tab is
 * a share of the edge; under the other three each tab is as long as what it carries**, with the
 * bar's own thickness as a minimum so a pip-only tab is never thinner than it is deep.
 */
@Composable
fun ModuleTabs(
    modules: List<InstalledModule>,
    selectedId: String?,
    horizontal: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    style: TabStyle = TabStyle.NAME,
    spread: TabSpread = TabSpread.FILL,
    colour: TabColour = TabColour.LIGHT,
    onSelect: (String) -> Unit = {},
) {
    val palette = tabPalette(colour)
    val fill = spread == TabSpread.FILL
    // The tab's minimum length along the bar is the bar's own inner thickness — square at least.
    val minLength = ((if (horizontal) height else width) - TAB_INSET * 2).coerceAtLeast(0.dp)
    val gather = when (spread) {
        TabSpread.FILL, TabSpread.START -> Arrangement.spacedBy(TAB_GAP, Alignment.Start)
        TabSpread.CENTRE -> Arrangement.spacedBy(TAB_GAP, Alignment.CenterHorizontally)
        TabSpread.END -> Arrangement.spacedBy(TAB_GAP, Alignment.End)
    }
    val gatherVertical = when (spread) {
        TabSpread.FILL, TabSpread.START -> Arrangement.spacedBy(TAB_GAP, Alignment.Top)
        TabSpread.CENTRE -> Arrangement.spacedBy(TAB_GAP, Alignment.CenterVertically)
        TabSpread.END -> Arrangement.spacedBy(TAB_GAP, Alignment.Bottom)
    }
    Box(
        modifier = modifier
            .size(width.coerceAtLeast(0.dp), height.coerceAtLeast(0.dp))
            // The bar takes one of the theme's two surfaces, whichever way round the user chose.
            // Under [TabColour.LIGHT] it matches the panel's own floor, which is the same token:
            // where a module leaves its floor bare the bar and the panel read as one surface.
            .background(palette.bar)
            .clipToBounds()
            .padding(TAB_INSET)
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = gather,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                modules.forEach { module ->
                    ModuleTab(
                        module = module,
                        selected = module.id == selectedId,
                        horizontal = true,
                        style = style,
                        palette = palette,
                        modifier = (if (fill) Modifier.weight(1f) else Modifier.widthIn(min = minLength))
                            .fillMaxHeight(),
                        onClick = { onSelect(module.id) },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                verticalArrangement = gatherVertical,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                modules.forEach { module ->
                    ModuleTab(
                        module = module,
                        selected = module.id == selectedId,
                        horizontal = false,
                        style = style,
                        palette = palette,
                        modifier = (if (fill) Modifier.weight(1f) else Modifier.heightIn(min = minLength))
                            .fillMaxWidth(),
                        onClick = { onSelect(module.id) },
                    )
                }
            }
        }
    }
}

/** The four colours a tab bar uses, all theme tokens. */
private class TabPalette(val bar: Color, val barInk: Color, val pill: Color, val pillInk: Color)

/**
 * The two pairings, each following `DashTheme`'s own stated rule rather than eye — the primary
 * surface carries black ink, the secondary surface carries light grey. The mid-grey accent is
 * deliberately not used: on the light surface it is far too close to the background to read.
 */
@Composable
private fun tabPalette(colour: TabColour): TabPalette {
    val theme = LocalDashTheme.current
    return when (colour) {
        TabColour.LIGHT -> TabPalette(
            bar = theme.backgroundColourPrimary, barInk = theme.textColourPrimary,
            pill = theme.backgroundColourSecondary, pillInk = theme.textColourSecondary,
        )
        TabColour.DARK -> TabPalette(
            bar = theme.backgroundColourSecondary, barInk = theme.textColourSecondary,
            pill = theme.backgroundColourPrimary, pillInk = theme.textColourPrimary,
        )
    }
}

/**
 * One tab.
 *
 * Selected is a filled pill in the opposite surface to the bar, carrying that surface's ink. Under
 * the default palette that is 11.1:1 selected and 13.9:1 unselected.
 *
 * **A tab is a full-thickness target, not just its text or its pip.** The whole cell is clickable,
 * so a tab is as easy to hit as the bar is thick — which is the entire reason for spending viewport
 * on a bar rather than hiding the switch in a gesture.
 */
@Composable
private fun ModuleTab(
    module: InstalledModule,
    selected: Boolean,
    horizontal: Boolean,
    style: TabStyle,
    palette: TabPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = if (selected) palette.pillInk else palette.barInk
    val theme = LocalDashTheme.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.pill else palette.bar)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // Labels on a vertical bar are turned as a whole, pip and name together, so "Both" reads
        // the same way round on either bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PIP_GAP),
            modifier = if (horizontal) Modifier.padding(horizontal = 6.dp)
                else Modifier.readingUpwards().padding(horizontal = 6.dp),
        ) {
            if (style != TabStyle.NAME) {
                Box(Modifier.size(PIP_SIZE).clip(CircleShape).background(ink))
            }
            if (style != TabStyle.PIPS) {
                Text(
                    text = module.name,
                    color = ink,
                    fontFamily = theme.font,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Turn a label a quarter turn so it reads bottom-to-top, for the bar on a vertical panel.
 *
 * A vertical bar is as thin as a horizontal one is shallow, and a module name will not fit across
 * 36dp. Rotating the *drawing* alone is not enough — the text would still be measured against the
 * bar's narrow width and ellipsise before it was ever turned — so the constraints are swapped
 * before measuring and the placement is offset to re-centre what is now a taller-than-wide box.
 *
 * The alternative was to show dots on a vertical bar and names on a horizontal one, which would have
 * been DASH deciding that a vertical panel's user needs less information than a horizontal panel's.
 */
private fun Modifier.readingUpwards(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth,
        )
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        ) { rotationZ = -90f }
    }
}

private val TAB_INSET = 3.dp
private val TAB_GAP = 3.dp
private val PIP_SIZE = 8.dp
private val PIP_GAP = 6.dp
