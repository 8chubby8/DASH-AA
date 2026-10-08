package com.dash.android.ui.rotation

import android.content.pm.ActivityInfo

/**
 * The four fixed orientations DASH can be held in (roadmap 1.5.15), plus the mapping to Android's
 * own constants.
 *
 * **Why an enum over the raw string.** The stored preference was a bare `String` holding only
 * `"PORTRAIT"` or `"LANDSCAPE"`, read in one place with an `else ->` catch-all — which silently
 * treated anything unrecognised as landscape. Adding two more values to that arrangement would have
 * meant a second place to keep in step. The stored names are unchanged, so everything already saved
 * still reads correctly and there is no migration.
 *
 * **The reversed pair are reliably honoured**, unlike sensor auto-rotation into reverse portrait:
 * a fixed `requestedOrientation` is enforced by the window manager rather than negotiated with the
 * sensor stack, so there is nothing to capability-detect here. The place that distinction *does*
 * matter is [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR] — see the Rotation tab.
 */
enum class DashOrientation(
    val stored: String,
    val label: String,
    val activityInfo: Int,
    /** True where DASH is upside down relative to its natural presentation — the only thing that
     *  tells this option apart from its partner on screen, and so the thing the tab's glyph draws. */
    val reversed: Boolean,
    val portrait: Boolean,
) {
    PORTRAIT("PORTRAIT", "Portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, false, true),
    LANDSCAPE("LANDSCAPE", "Landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, false, false),
    PORTRAIT_REVERSED(
        "PORTRAIT_REVERSED",
        "Portrait reversed",
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        true,
        true,
    ),
    LANDSCAPE_REVERSED(
        "LANDSCAPE_REVERSED",
        "Landscape reversed",
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        true,
        false,
    );

    companion object {
        /** Falls back to landscape, which was the old string's default and suits a car screen. */
        fun from(stored: String?): DashOrientation =
            entries.firstOrNull { it.stored == stored } ?: LANDSCAPE
    }
}
