package com.dash.android.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The DASH theme token set — colours and font. Every DASH chrome surface (system bar, elements,
 * edit ruler, settings panel, overlays) reads from here, never from hardcoded values. Adding a
 * token is one field with a default; nothing existing breaks. Version 2 introduces user-facing
 * presets by providing a different [DashTheme] instance at the top of the composition tree; no
 * component needs to change.
 *
 * The colours come in matched primary/secondary pairs so a surface and the text/icons on it stay
 * legible. **The pairing must not be crossed:** primary text/icons sit on the primary background,
 * secondary text/icons sit on the secondary background. Secondary text on the primary background
 * (both currently the same light grey) would be invisible.
 */
data class DashTheme(
    val backgroundColourPrimary: Color,
    val backgroundColourSecondary: Color,
    val textColourPrimary: Color,
    val textColourSecondary: Color,
    val iconColourPrimary: Color,
    val iconColourSecondary: Color,
    val accentColourPrimary: Color,
    val accentColourSecondary: Color,
    val font: FontFamily,
) {
    companion object {
        // One default set. The light-grey primary surface carries black text/icons; the dark
        // secondary surface carries light-grey text/icons. Accents are the mid greys the system bar
        // and edit ruler use for structure.
        //
        // The secondary surface was a mid-grey (848482) until 1.5.12. It was changed because mid-grey
        // sits in the middle of the luminance range, so *nothing* can get far from it — even pure black
        // reaches only 5.6:1 on it, and the Serial Monitor's colour-coded message words managed
        // 1.06–1.28:1, which is invisible. No palette can fix that; only the surface can. At 2C2C2E the
        // paired ink runs 11.1:1 and colour-coding runs 4.7–8.1:1.
        //
        // The primary surface was then eased down too (E5E5EA → D2D2D7, 1.5.12), so the near-white
        // system bar sits less harshly against the now-dark settings panel. Black text on it stays at
        // 13.9:1. **accentColourPrimary had to move with it** (D1D1D6 → C2C2C7): the old accent was a
        // subtly *darker* divider on the old surface, and on D2D2D7 it landed at 1.01:1 — invisible,
        // and inverting to lighter-than-the-surface if the background were taken any further down.
        // Whenever a surface token moves, the accent paired with it has to be re-checked.
        fun default() = DashTheme(
            backgroundColourPrimary   = Color(0xFFD2D2D7),
            backgroundColourSecondary = Color(0xFF2C2C2E),
            textColourPrimary         = Color(0xFF000000),
            textColourSecondary       = Color(0xFFE5E5EA),
            iconColourPrimary         = Color(0xFF000000),
            iconColourSecondary       = Color(0xFFE5E5EA),
            accentColourPrimary       = Color(0xFFC2C2C7),
            accentColourSecondary     = Color(0xFF8E8E93),
            font                      = FontFamily.Monospace,
        )
    }
}

/**
 * The active DASH theme. Always provided by [com.dash.android.ui.screen.MainScreen] before any
 * chrome renders. The fallback here is a safety net only — in practice the provider is present.
 */
val LocalDashTheme = compositionLocalOf { DashTheme.default() }

/**
 * The default value of the BackgroundColourSplash token — the splash's own independent background
 * colour (roadmap 1.5.6). Black, the classic boot backdrop. It is a *raw colour the user owns*, not a
 * reference to a theme token: the splash deliberately does not inherit the theme. The theme tokens are
 * offered only as convenient presets to seed it from (see [splashColourPresets]).
 */
const val SPLASH_BACKGROUND_COLOUR_DEFAULT = 0xFF000000L

/**
 * The swatch presets offered under the splash colour picker, as (label, colour). The theme's own
 * colours are handy starting points; black and white are the universal pair. Tapping one copies its
 * value into BackgroundColourSplash — after which the user is free to nudge it anywhere with the
 * custom picker. Sourced live from [theme] so a v2 theme change re-seeds the presets.
 */
fun splashColourPresets(theme: DashTheme): List<Pair<String, Color>> = listOf(
    "Primary" to theme.backgroundColourPrimary,
    "Secondary" to theme.backgroundColourSecondary,
    "Accent" to theme.accentColourSecondary,
    "Black" to Color(0xFF000000),
    "White" to Color(0xFFFFFFFF),
)
