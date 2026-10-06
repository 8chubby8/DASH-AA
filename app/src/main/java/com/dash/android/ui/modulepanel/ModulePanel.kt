package com.dash.android.ui.modulepanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dash.android.panel.PanelDocument
import com.dash.android.panel.TouchBinding
import com.dash.android.ui.theme.LocalDashTheme

/**
 * The module panel — the display area an installed ACCESSORY module owns (roadmap 1.6.2).
 *
 * **DASH draws the container. The module fills it.** This file is the container and stops at its
 * boundary: a plain filled box, no border, no radius, no content of its own. Everything *inside*
 * the boundary belongs to the module — background, art, fonts, controls — and DASH never reaches
 * in. Since 1.6.6 a real module fills it, drawn from the layout document it shipped at install.
 *
 * **Shape, not size.** The panel is a fixed aspect ratio, not a measurement — that is what lets a
 * module drawn once render correctly on a phone, a tablet and a head unit alike. The author draws
 * to a known shape; DASH scales it to whatever screen it lands on.
 */
object ModulePanelSpec {
    /**
     * Panel thickness for a given long-edge length, at the requested size.
     *
     * **The long edge is the docked edge minus whatever the system bar has already taken from it**
     * (Roger, 1.6.3). Docked top or bottom, the bar is on the opposite horizontal edge and takes
     * nothing, so the long edge is the full screen width. Docked left or right, the bar spans the
     * full width at top or bottom and eats into the vertical run, so the long edge is the screen
     * height *less the bar*. A consequence worth knowing: changing the bar height resizes a
     * vertical panel.
     *
     * One number per size serves both orientations, because [PanelSize.aspect] is expressed against
     * the long edge rather than against width — a vertical panel is the same shape stood on its end.
     */
    fun thicknessFor(size: PanelSize, longEdge: Dp): Dp = longEdge / size.aspect

    /**
     * The box a panel actually gets, once the screen has had its say.
     *
     * [compacted] is false in the ordinary case, where the panel spans its edge at the exact
     * thickness [thicknessFor] asks for. It is true when the screen could not give that thickness
     * and the panel was scaled down to fit — see [boxFor].
     */
    data class PanelBox(val longEdge: Dp, val thickness: Dp, val compacted: Boolean)

    /**
     * The panel's box on an edge of [longEdge], given [availableThickness] to grow into.
     *
     * **Compacting: the mitigation, not the feature** *(Roger, 2026-08-27)*. A panel is a shape
     * rather than a measurement, so its thickness is derived from the edge it is docked to — and on
     * an elongated screen that derivation can ask for more thickness than the screen has. A Large
     * 8 × 3 panel along the bottom of a 1280 × 480 head unit asks for 480dp of a 388dp band: 124%,
     * running clean under the system bar. **The shape is not wrong; the screen is the wrong shape
     * for it.** Every other combination DASH supports fits — on a Tab S9 Ultra the same panel takes
     * 65% — so this is the arithmetic of one honest bad pairing rather than a common case.
     *
     * When it happens the panel **scales down uniformly, keeps its aspect exactly, and centres on
     * its edge** *(Roger)*, with `backgroundColourPrimary` either side. Thickness is capped at what
     * exists and the long edge follows the ratio down, so the panel stops spanning its edge and
     * becomes an island on it. **The author's shape is never distorted** — a compacted panel is the
     * drawing the module shipped, smaller. That is the entire point: *"the byproduct of a stupid
     * user selecting the wrong panel layout size is mitigation of that stupidity, not a feature."*
     *
     * **The bands either side are DASH's own surface, not the module's box.** The castle walls are
     * the returned rectangle; what DASH paints outside them is its own floor, so the Module Mantra
     * is untouched — nothing reaches inside the boundary, the boundary simply moved inward.
     *
     * **Compacting never trades away the long edge to keep thickness**, which is the other way this
     * could have been solved. Thickness is what the user chose a size *for*, and a panel that kept
     * its 480dp by spanning only half its edge would be obeying the letter of the ratio while
     * ignoring what was asked for. Capping thickness and letting the ratio pull the length in is the
     * same decision `PanelYield` makes: give the user as much of what they asked for as the screen
     * allows, and never silently rewrite the request.
     */
    fun boxFor(size: PanelSize, longEdge: Dp, availableThickness: Dp): PanelBox {
        val asked = thicknessFor(size, longEdge)
        // A band with nothing in it is not a panel. Guarded rather than clamped so the caller gets
        // an honestly empty box instead of a sliver, and the screen's arithmetic stays sane.
        if (availableThickness <= 0.dp || longEdge <= 0.dp) return PanelBox(0.dp, 0.dp, true)
        if (asked <= availableThickness) return PanelBox(longEdge, asked, false)
        return PanelBox(availableThickness * size.aspect, availableThickness, true)
    }
}

/**
 * The module panel — the container, and the module's panel drawn inside it.
 *
 * Sized and positioned entirely by the caller, which owns the screen geometry. **DASH draws the
 * container and stops at its boundary**: no border, no radius, no padding, nothing of DASH's own
 * inside the walls. [PanelContent] fills it with what the module described and nothing else.
 *
 * The fill beneath is `backgroundColourPrimary` — a floor to draw on rather than a designed frame,
 * and one a full-panel layer covers completely. It is visible only where a module's own layout
 * leaves it visible, which is the module's decision to have made.
 *
 * **This composable is never reached with no module to draw.** §6's *no layout, no panel* rule is
 * settled a level up, in [rememberActivePanel] and the screen geometry that reads it, because the
 * absence has to change the *layout of the screen* rather than merely what this box contains.
 */
@Composable
fun ModulePanel(
    document: PanelDocument,
    values: Map<String, String>,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onPress: (TouchBinding) -> Unit = {},
    onTouch: () -> Unit = {},
) {
    val theme = LocalDashTheme.current
    Box(
        modifier = modifier
            .size(width.coerceAtLeast(0.dp), height.coerceAtLeast(0.dp))
            .background(theme.backgroundColourPrimary)
            // The king's walls are the edge of his domain in both directions: nothing DASH draws
            // reaches in, and nothing the module draws spills out over DASH's own surfaces.
            .clipToBounds()
    ) {
        PanelContent(
            document = document,
            values = values,
            modifier = Modifier.fillMaxSize(),
            onPress = onPress,
            onTouch = onTouch,
        )
    }
}
