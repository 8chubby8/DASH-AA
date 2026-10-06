package com.dash.android.system

import android.util.Log
import java.awt.GraphicsEnvironment
import java.util.concurrent.TimeUnit

/**
 * How many physical pixels one DASH `dp` should be on this desktop (DASH-AA).
 *
 * **Why DASH-AA has to ask.** On Android the system tells every app its density. Java's toolkit runs
 * under XWayland here, and when GNOME uses *fractional* scaling (this G14 runs its panel at 1.67×)
 * XWayland apps are handed the raw pixels at a scale of 1 — so a 64 dp system bar would be 64 physical
 * pixels on a 3456-pixel-wide panel, a sliver. Upstream's sizing model is built on `dp` meaning the same
 * physical size everywhere (interface.md, 1.5.3), so DASH-AA restores that by reading the desktop's
 * own scale and applying it as the density, exactly the number Android would have supplied.
 *
 * In order: `DASH_SCALE` (a user override — the in-car screen is the user's to size); Java's own scale
 * if it already has one (integer scaling, or another desktop that tells X apps); GNOME's scale for the
 * primary monitor; else 1. Probed once at start; every failure is a quiet 1.
 */
object DesktopScale {
    private const val TAG = "DashScale"

    fun detect(): Float {
        System.getenv("DASH_SCALE")?.toFloatOrNull()?.takeIf { it in 0.5f..4f }?.let {
            Log.i(TAG, "scale $it from DASH_SCALE"); return it
        }
        val java = runCatching {
            if (GraphicsEnvironment.isHeadless()) 1.0
            else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0)
        if (java > 1.01) return 1f                                   // the toolkit already scales
        gnomePrimaryScale()?.let { Log.i(TAG, "scale $it from GNOME"); return it }
        return 1f
    }

    /** The primary logical monitor's scale from Mutter's own display configuration. */
    private fun gnomePrimaryScale(): Float? = runCatching {
        val p = ProcessBuilder(
            "gdbus", "call", "--session", "--dest", "org.gnome.Mutter.DisplayConfig",
            "--object-path", "/org/gnome/Mutter/DisplayConfig",
            "--method", "org.gnome.Mutter.DisplayConfig.GetCurrentState",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        // Logical monitors: (x, y, scale, uint32 transform, primary, [...], {...})
        val monitors = Regex("""\((-?\d+), (-?\d+), ([\d.]+), (?:uint32 )?\d+, (true|false)""").findAll(out).toList()
        val chosen = monitors.firstOrNull { it.groupValues[4] == "true" } ?: monitors.firstOrNull()
        chosen?.groupValues?.get(3)?.toFloatOrNull()?.takeIf { it in 0.5f..4f }
    }.getOrNull()
}
