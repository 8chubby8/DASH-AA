package com.dash.android.ui.modulepanel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.PANEL_GAP
import com.dash.android.ui.common.controlWidth

/**
 * **Rule 2 — the panel yields so the settings panel can open. (roadmap 1.6.9, TEST BUILD.)**
 *
 * This is an intermediate build of one idea and nothing else. It is here to be driven on real
 * devices and either kept or thrown away; nothing about it is recorded in the Bible yet.
 *
 * ---
 *
 * **The problem it exists for.** A module panel is a fixed aspect ratio anchored to the screen edge
 * it docks to, so its thickness is a *consequence* of the screen rather than a number anybody chose.
 * On a tall narrow device that consequence is brutal: a large vertical slot on a 412dp-wide phone
 * takes 358dp of it, and the settings panel — which conforms to the panel and rolls out into what is
 * left — gets a 54dp band to lay itself out in. **The module panel's own setting therefore became
 * unreachable by the settings panel**, with no way out, because DASH sets its own
 * `requestedOrientation` and no Android rotation control gets round it. A configuration the user can
 * reach but cannot leave is a trap, and `module-layout.md` §6 explicitly permits a module to ship
 * the layout that springs it.
 *
 * **The answer, and it is Roger's.** *"If the percentage of used space is above a certain amount then
 * the module panel must be made to move out of the way."* This supersedes the 1.6.2 rule that
 * settings *never* covers the panel. That rule was right about the Module Mantra and wrong about
 * what happens when honouring it makes DASH unusable — and the courtesy loses to the trap.
 *
 * **The measure is the shape of what is left over**, not the size of what was taken. DASH already
 * knows the screen, the bar and the assembly, so it knows the rectangle the settings panel would get.
 * If that rectangle is too far from a usable shape, the panel yields:
 *
 *  1. **Step down to the largest smaller slot the module on screen actually ships** — Large → Medium
 *     → Small, never crossing orientation, never a slot the author did not draw.
 *  2. **If there is no such slot, retract the panel** rather than covering it. Covering means DASH's
 *     blind rolls out on top of the king's castle; retracting means the castle steps aside and
 *     visibly comes back, which the user can read. The tab bar stays put throughout — it is DASH's
 *     own chrome, it is the peek strip, and it is the thing that must never become unreachable.
 *
 * **The preference is never rewritten**, exactly as [effectiveEdge] does not rewrite the user's edge
 * when the bar displaces the panel. This is that decision applied to size instead of position: the
 * displacement lasts as long as the settings panel is open and not one frame longer.
 *
 * **Tab membership is judged on the user's chosen slot, never the yielded one.** Stepping the panel
 * down must not change *who is in the tab bar* — a module that ships only Large would lose its tab
 * mid-yield and DASH would have to put somebody else's module on screen, which is DASH choosing a
 * module on the user's behalf. It never does that. So the step-down is judged against the module
 * already on screen and affects only what that module draws.
 */
object SettingsFitSpec {

    /**
     * **The tolerance is the aspect ratio of the remaining space, signed — width ÷ height.**
     *
     * Signed, not long-edge-over-short, and that distinction is the whole finding. Measured
     * unsigned the rule collapses immediately: a leftover space 2.41 : 1 *wide* is perfectly usable
     * and one 2.29 : 1 *tall* is not. Same ratio, opposite verdicts, so a single unsigned bound
     * cannot separate them and a *pair* of signed bounds separates all 24 test cases cleanly.
     *
     * **The two bounds are deliberately asymmetric — about 4 : 1 wide against about 2 : 1 tall.**
     * That is not a preference invented here; it is the same asymmetry the settings shell is
     * physically built from. A setting's control is a fixed [com.dash.android.ui.common.CONTROL_WIDTH]
     * and cannot shrink, so **width is structural** and a narrow band simply breaks. The content box
     * scrolls, so **height is elastic** and a short band degrades instead. Roger's own device
     * verdicts land on the same 2 : 1, from his eye rather than from the code.
     *
     * **Settled at 0.48 and 4.40 on 2026-08-27**, after being exposed as temporary steppers and
     * driven on three devices — a Samsung SM-X910, a Samsung SM-T710 and a Pixel phone. The steppers
     * were scaffolding for finding the numbers and came out once they were found; these are the
     * numbers they found.
     */
    const val NARROW = 0.48f
    const val WIDE = 4.40f

    /**
     * The hard backstop, kept alongside the ratio rather than replaced by it.
     *
     * The aspect rule is about proportion and fires first in practice, but a settings control is
     * [com.dash.android.ui.common.CONTROL_WIDTH] wide plus the shell's own chrome — `PANEL_GAP` and
     * `BOX_PAD` on both sides — and no ratio rescues a band narrower than that. It is a fact about
     * how the shell is built, not a taste, so it is checked as a fact. It moves with the text scale
     * because [controlWidth] does.
     */
    fun minimumSettingsWidth(fontScale: Float): Dp =
        controlWidth(fontScale) + (PANEL_GAP * 2) + (BOX_PAD * 2)
}

/** What the panel does while the settings panel is open. */
sealed interface PanelYield {
    /** Draw at this size — the user's own, if it fits, or the largest smaller one that does. */
    data class Draw(val size: PanelSize) : PanelYield

    /** No smaller slot this module ships will fit. Slide the panel off its edge until settings closes. */
    data object Retract : PanelYield
}

/**
 * Would the settings panel have a usable shape to open into, with the panel drawn at [size]?
 *
 * The rectangle measured is exactly the one `MainScreen` insets the settings blind to: the screen,
 * less the system bar, less the whole assembly — panel *and* tab bar — on whichever edge it holds.
 */
private fun settingsFits(
    size: PanelSize,
    edge: PanelEdge,
    screenWidth: Dp,
    screenHeight: Dp,
    barThickness: Dp,
    tabThickness: Dp,
    fontScale: Float,
    narrow: Float,
    wide: Float,
): Boolean {
    val longEdge = if (edge.horizontal) screenWidth else screenHeight - barThickness
    if (longEdge <= 0.dp) return false
    // Measured through the same compacting rule the screen draws by, so there is one place that
    // knows a panel can be capped and not two that might drift. It changes no verdict — an
    // overflowing panel leaves settings a zero band where it used to leave a negative one, and both
    // fail — but it means this reads the geometry that will actually be on screen.
    val available =
        (if (edge.horizontal) screenHeight - barThickness else screenWidth) - tabThickness
    val assembly = ModulePanelSpec.boxFor(size, longEdge, available).thickness + tabThickness

    val w = if (edge.horizontal) screenWidth else screenWidth - assembly
    val h = if (edge.horizontal) screenHeight - barThickness - assembly else screenHeight - barThickness
    if (w <= 0.dp || h <= 0.dp) return false

    if (w < SettingsFitSpec.minimumSettingsWidth(fontScale)) return false
    val aspect = w / h
    return aspect in narrow..wide
}

/**
 * What the panel should do, given the screen it is on and the slots the module on screen shipped.
 *
 * [canDraw] is asked only about the module currently on screen — see the note on tab membership in
 * [SettingsFitSpec]. Sizes are tried thickest-first below the user's choice, so the user keeps as
 * much of the panel they asked for as the screen allows.
 */
fun resolvePanelYield(
    chosen: PanelSize,
    edge: PanelEdge,
    screenWidth: Dp,
    screenHeight: Dp,
    barThickness: Dp,
    tabThickness: Dp,
    fontScale: Float,
    canDraw: (PanelSize) -> Boolean,
): PanelYield {
    fun fits(size: PanelSize) = settingsFits(
        size, edge, screenWidth, screenHeight, barThickness, tabThickness, fontScale,
        SettingsFitSpec.NARROW, SettingsFitSpec.WIDE,
    )

    if (fits(chosen)) return PanelYield.Draw(chosen)

    // Thinner slots only, thickest first — a higher aspect is a thinner panel, so this walks
    // Large → Medium → Small and stops at the first one that both exists and fits.
    val thinner = PanelSize.entries
        .filter { it.aspect > chosen.aspect }
        .sortedBy { it.aspect }
    for (size in thinner) {
        if (canDraw(size) && fits(size)) return PanelYield.Draw(size)
    }
    return PanelYield.Retract
}
