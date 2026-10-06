package com.dash.android.ui.scale

// The old fluid global "DASH UI scale" — LocalDashScale and its DASH_SCALE_* bounds — was removed
// at 1.5.15. It was superseded at 1.5.3 by per-surface sizing (each surface on its own stepper) plus
// DASH-owned text below, and after that nothing consumed it: a preference no control wrote and no
// surface read, kept alive only by the diagnostic overlay printing its value.

// DASH text size — DASH's own font scaling, independent of Android's font setting. Provided at the
// composition root as a LocalDensity fontScale override (see MainScreen), so all DASH chrome text
// follows this value and nothing else. 1.0 is the default.
//
// The range widened to 0.4–2.0 at 1.5.15 (Roger) from a cautious 0.8–1.4. DASH runs on everything
// from a phone held at arm's length to a large head unit read across a cabin, and it is not DASH's
// place to decide that someone's screen wants a range narrower than that. The layout follows: the
// tree column, every control and the stepper readouts are all sized from this scale, so they grow
// and shrink with the type rather than leaving it to overflow them.
const val DASH_TEXT_SCALE_DEFAULT = 1.0f
const val DASH_TEXT_SCALE_MIN = 0.4f
const val DASH_TEXT_SCALE_MAX = 2.0f
const val DASH_TEXT_SCALE_STEP = 0.1f