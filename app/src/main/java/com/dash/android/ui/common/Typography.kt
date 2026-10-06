package com.dash.android.ui.common

import androidx.compose.ui.unit.sp

/**
 * DASH's type scale (roadmap 1.5.15).
 *
 * **Why it exists.** Before this there were seventeen distinct font sizes across ninety-eight call
 * sites — including 12, 12.5, 13 and 13.5 all in use at once. Nobody can see half a point; those were
 * not decisions, they were the residue of iterating one screen at a time. Five tiers cover every job
 * DASH's chrome actually has.
 *
 * **These are defaults, not locks.** DASH overrides `fontScale` at the composition root with the
 * user's `dashTextScale` (Appearance › Size & Scale), so every `sp` below follows the text-size
 * control. That is also why the tiers are `sp` and never `dp` — a size in `dp` would silently opt out
 * of the user's slider, which is the one thing this scale must not do.
 *
 * **The sizes suit a car.** They run roughly 40% larger than what DASH used before, because the
 * screen is read at arm's length across a cabin rather than held. interface.md pins no font size —
 * its only hard floor is the 48dp settings-button touch target — so the scale is DASH's to set.
 *
 * **This is DASH chrome only.** Elements and module panels are deliberately excluded: font sizing
 * inside an element is the element's own business, and DASH never reaches inside a module panel.
 * A built-in element gets no size policy a community element could not also ignore.
 */

/** Page and section titles. */
val HEADING = 28.sp

/** Secondary titles — card headers, the second rank within a page. */
val SUBHEADING = 22.sp

/** Prose and anything that wants to read large — the tier above the settings default. */
val MAINBODY = 18.sp

/** The settings default: setting names, chips, tags, column headers, help text. */
val BODY = 14.sp

/** Links, dense readouts, unit suffixes. The floor: below this, stop shrinking and cut words. */
val TINY = 11.sp

// ── Leading ──────────────────────────────────────────────────────────────────────────────────────
// Each tier's line box. Explicit rather than left to the font's own metrics, because DASH aligns
// headings across two columns by line height — see HeadingRule — and because prose wants more air
// than a font's default leading gives it.

/** The line box a [HEADING] occupies. Also the box a tree heading occupies, which is what puts the
 *  two headings' rules on one line despite their different type sizes. */
val HEADING_LINE = 36.sp
val SUBHEADING_LINE = 28.sp
val MAINBODY_LINE = 24.sp
val BODY_LINE = 19.sp
val TINY_LINE = 15.sp
