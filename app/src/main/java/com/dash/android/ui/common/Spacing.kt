package com.dash.android.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The settings panel's spacing, defined once (roadmap 1.5.15).
 *
 * These were four separate numbers scattered across the shell and the tabs — 24 top, 28 bottom, 16 at
 * each screen edge, and a 24-or-28 box inset depending on whether a tab claimed the box for itself.
 * No two edges agreed, which read as the box sitting slightly wrong rather than as any one gap being
 * obviously off.
 *
 * They live in `common` rather than in `SettingsShell` because a `fillsBox` tab is handed the bare box
 * and has to apply the inset itself — and those tabs live in four different packages, so a private
 * constant in the shell meant four copies of the same number to keep in step by hand.
 */

/** The panel's outer margin — the same on all four sides. */
val PANEL_GAP = 28.dp

/**
 * The content box's inner padding: the inset from the box's edge to its first heading.
 *
 * Two things depend on this being one value. Ordinary tabs get it from the shell and `fillsBox` tabs
 * apply it themselves, so every box insets its content identically; and the tree's breadcrumb is
 * dropped by exactly this much, which is what puts the tree's heading on the same line as the heading
 * inside the box.
 */
val BOX_PAD = 28.dp

/**
 * The channel between the tree column and the content box. Set to match [PANEL_GAP] so the box has
 * the same air on all four sides, the gutter included (roadmap 1.5.15, Roger).
 */
val TREE_GUTTER = 28.dp

/**
 * A nav row's own horizontal padding — the gap between its filled background and its label.
 *
 * The tree's heading is indented by the same amount, so the heading and the row labels beneath it
 * share one left edge (roadmap 1.5.15, Roger). Aligning the heading to the rows' *background* edge
 * instead left the text on two different lines, which is what it looked like before.
 */
val NAV_ROW_INSET = 14.dp

/**
 * Extra air above a section heading, on top of whatever the page's own arrangement already gives it.
 *
 * A section heading needs to read as belonging to what follows it rather than to what came before —
 * without it, a subheading sits as close to the line above as the settings under it do, and the eye
 * has nothing to group by (roadmap 1.5.15, Roger). Held in the heading itself rather than left to
 * each page to remember.
 */
val SECTION_HEADER_GAP = 34.dp

/**
 * The gap between one setting and the next inside a section.
 *
 * Every page used to define its own: 34 on Size & Scale, 28 on Transitions, Splash and System Bar,
 * and an ad-hoc 24 on the pages with no constants at all — so the distance between a heading and its
 * first control depended on which page you happened to be looking at (roadmap 1.5.15, Roger).
 *
 * There is deliberately no companion for the gap *between* sections. A section heading carries
 * [SECTION_HEADER_GAP] above itself, and that is the whole separation — pages used to add a second
 * explicit spacer on top of it, which is how the rhythm drifted apart in the first place.
 */
val SETTING_SPACING = 28.dp

/**
 * The width of a control in a setting's right-hand nook, at 1.0 text scale — multiply by the font
 * scale at the call site, as the stepper's readout does.
 *
 * **The rule (roadmap 1.5.15, Roger).** Every clickable control in a settings page — button, segment,
 * stepper — sits on the **right**, at **this one width**, and grows **downwards** when its content
 * needs more room. A label wraps to a second line and the box gets taller; it never truncates, because
 * an action you cannot read is worse than a tall box. Controls used to size themselves to their own
 * content, so a Bottom/Top segment and a 1/2/3 segment on the same page were different widths and the
 * column never lined up.
 *
 * **The one exception** is a control that genuinely cannot fit — the six-speed segments on
 * Transitions would leave about 31dp a cell, which no amount of shrinking rescues. Those stack full
 * width beneath their label instead, via `SettingBlock`'s `fullWidthControl`. That flag is the escape
 * hatch and nothing else: reach for it only when the control is unusable at this width.
 */
const val CONTROL_WIDTH = 190

/**
 * The control width at a given text scale.
 *
 * **It grows with the text but never shrinks below the base width**, which is why the scale is
 * clamped at 1.0. A control is not only its label: a stepper's − and + are fixed 38dp touch targets,
 * so at 0.5× a linearly-scaled 95dp box had 76dp of buttons in it and squeezed the readout out
 * altogether (roadmap 1.5.15, Roger). Smaller text does not need a narrower control; larger text
 * does need a wider one.
 */
fun controlWidth(fontScale: Float): Dp = (CONTROL_WIDTH * fontScale.coerceAtLeast(1f)).dp
