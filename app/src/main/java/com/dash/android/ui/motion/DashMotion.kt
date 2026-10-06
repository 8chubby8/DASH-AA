package com.dash.android.ui.motion

import androidx.compose.runtime.compositionLocalOf

/**
 * DASH's motion model (roadmap 1.5.5). Every animated *transition* in DASH — a surface revealing,
 * hiding, or moving between states — is user-controllable, and each breaks out to its own control in
 * Appearance › Transitions. The rule is simple: **if it's a transition, it goes in Transitions.**
 * Control-feedback micro-animation intrinsic to a widget (a toggle thumb, a ruler handle) is *not* a
 * transition and stays fixed — it belongs to how the control works, not to the user's motion taste.
 *
 * This is the DASH ethos as a setting: DASH has no opinion on how fast the user wants their interface
 * to move. A master pace sets everything at once; any single transition can then be dialled on its
 * own, exactly like a game's graphics presets — touch one and the master reads "Custom".
 */

/** The named speeds offered per transition; [millis] is the duration. INSTANT is a true hard cut. */
enum class TransitionSpeed(val label: String, val millis: Int) {
    INSTANT("INSTANT", 0),
    FAST("FAST", 250),
    NORMAL("NORMAL", 450),
    SLOW("SLOW", 750),
    CINEMATIC("CINEMATIC", 1100),
    LABORIOUS("LABORIOUS", 3000),
}

/**
 * The registry of DASH transitions. Every transition that exists in the code gets an entry here, and
 * the Transitions page renders one control per entry automatically — so a new surface's transition
 * gains a control the moment it registers, with no settings rework (the same self-growing pattern the
 * transport list uses). [key] is the stable DataStore key; [label] is what the user sees.
 *
 * There was a `hint` here too — a sentence of plain-language help under each control — dropped at
 * 1.5.15 when the settings pages stopped carrying explanatory text beneath their settings.
 *
 * Order is chronological-as-added for now; it gets tidied into a sensible order once every transition
 * in DASH exists.
 */
enum class TransitionId(
    val key: String,
    val label: String,
    val default: TransitionSpeed = TransitionSpeed.NORMAL,
) {
    SETTINGS_PANEL_OPEN("settings_panel_open", "Settings panel — open"),
    SETTINGS_PANEL_CLOSE("settings_panel_close", "Settings panel — close"),
    SETTINGS_NAV_DRILL_IN("settings_nav_drill_in", "Settings nav — drill in"),
    SETTINGS_NAV_BACK_OUT("settings_nav_back_out", "Settings nav — back out"),
    SETTINGS_CONTENT_SWAP("settings_content_swap", "Settings content — swap"),
    SPLASH_FADE_IN("splash_fade_in", "Splash — fade in"),
    SPLASH_FADE_OUT("splash_fade_out", "Splash — fade out"),
    MODULE_PANEL_MOVE("module_panel_move", "Module panel — move edge"),
    MODULE_PANEL_SWITCH("module_panel_switch", "Module panel — switch module"),
}

/**
 * The resolved per-transition speeds, provided once at the composition root via [LocalDashTransitions]
 * so any animation can read its own duration without being plumbed the value. Falls back to each
 * transition's [TransitionId.default] for anything not yet stored.
 */
class DashTransitions(private val presets: Map<TransitionId, TransitionSpeed>) {
    fun speed(id: TransitionId): TransitionSpeed = presets[id] ?: id.default
    fun millis(id: TransitionId): Int = speed(id).millis
}

val LocalDashTransitions = compositionLocalOf { DashTransitions(emptyMap()) }
