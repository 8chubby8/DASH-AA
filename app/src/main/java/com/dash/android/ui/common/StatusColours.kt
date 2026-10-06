package com.dash.android.ui.common

import androidx.compose.ui.graphics.Color

/**
 * The status palette (roadmap 1.5.15) — the colours that carry a *meaning* rather than an identity.
 *
 * **Why these are not theme tokens.** 1.5.12 settled the principle when it designed and then rejected
 * a `categoryColours` token for the Serial Monitor: a colour that distinguishes a kind of traffic, or
 * a state of health, is not part of DASH's visual identity and must not shift when the user picks a
 * theme preset. Green means reached, amber means waiting, red means faulted — in every preset, in
 * every car. So they live here as a fixed set, beside [DashButton], rather than in `DashTheme`.
 *
 * **Why they moved.** These values were chosen in 1.4.x against the near-black `#14141F` card wells
 * the module screens used then. 1.5.8 put those cards on the settings surface and 1.5.12 took that
 * surface to `#2C2C2E`, and the colours never followed — green fell from 5.74:1 to 4.38:1, red from
 * 4.61:1 to **3.52:1**, which is under the 4.5:1 floor for the small chip text it was being used for.
 * Meanwhile the Serial Monitor next door ran 4.67–8.05:1 on the same surface, so one tab's indicators
 * were visibly duller than another's.
 *
 * They were also **defined twice** — `3DA35D` was `GOOD` in Transport Manager and `INSTALLED_BORDER`
 * in Module Manager, `C98A2B` was `WAIT` and `UPDATE_ACCENT`, `D9534F` was `BAD` and `FAIL_ACCENT`.
 * Same meaning, same hex, two places to keep in step by hand.
 *
 * **These are inks, not fills.** Every value here is measured as a foreground on the settings surface
 * — a light, a chip label, a border, a line of text. A filled button needs the opposite (a dark fill
 * behind light text), so those stay where they are used; see `ActionButton`, which now picks its ink
 * from the fill's luminance rather than assuming white.
 *
 * Contrast against `backgroundColourSecondary` `#2C2C2E`, all above the 4.5:1 small-text floor.
 */
object DashStatus {

    /** Reached, installed, healthy, active. 6.93:1 */
    val ok = Color(0xFF81C784)

    /** Waiting, or a mismatch that is not yet a fault — nothing is wrong. 8.05:1 */
    val wait = Color(0xFFFFB74D)

    /** A fault: something is there and it is wrong. 4.67:1 */
    val fail = Color(0xFFE57373)

    /** Present but gone quiet — an installed module that has stopped replying. 7.09:1 */
    val alert = Color(0xFFFFA366)

    /** Dormant, muted, secondary — present and not doing anything. 5.62:1 */
    val idle = Color(0xFFA4A4AA)

    /** Neutral fact rather than health — the transport tag on a module card. 7.31:1 */
    val info = Color(0xFFB0BEC5)
}
