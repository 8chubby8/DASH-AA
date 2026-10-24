package com.dash.android.system

import com.dash.android.system.linux.Command
import android.util.Log
import java.awt.GraphicsEnvironment
import java.io.File
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
 * primary monitor; **the screen's own size** (1.2.5); else 1. Probed once at start; every failure is a
 * quiet 1.
 *
 * **The screen's own size** is how Android itself arrives at a density: the screen's pixels over its
 * width, as a fraction of Android's 160 dots to the inch. Every screen reports its size in millimetres
 * (its EDID, which the kernel shows in `/sys/class/drm`), so this needs no desktop at all — DASH-AA
 * Linux's labwc says nothing about scale, and before 1.2.5 the Surface Pro 3 was drawn at 1× where Android
 * would use 1.35× (2160 pixels across 254 mm: 216 to the inch), everything a quarter too small.
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
        screenSizeScale()?.let { (scale, why) -> Log.i(TAG, "scale $scale from the screen's own size — $why"); return scale }
        return 1f
    }

    /**
     * The scale from the screen's size, for the screen Java shows DASH on: of the connected screens that
     * report a size, the one with Java's pixel count, else the built-in one, else the only one.
     */
    private fun screenSizeScale(): Pair<Float, String>? = runCatching {
        val shown = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.let { it.width to it.height }
        }.getOrNull()
        val screens = File("/sys/class/drm").listFiles().orEmpty().filter { it.name.matches(Regex("""card\d+-.+""")) }
            .filter { runCatching { File(it, "status").readText().trim() == "connected" }.getOrDefault(false) }
            .mapNotNull { dir ->
                val mode = File(dir, "modes").takeIf { it.exists() }?.readLines()?.firstOrNull()
                    ?.let { Regex("""(\d+)x(\d+)""").find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                    ?: return@mapNotNull null
                val scale = edidScale(File(dir, "edid").readBytes(), mode.first, mode.second) ?: return@mapNotNull null
                Triple(dir.name.substringAfter('-'), mode, scale)
            }
        val chosen = screens.firstOrNull { it.second == shown || it.second == shown?.let { (w, h) -> h to w } }
            ?: screens.firstOrNull { BUILT_IN.any { b -> it.first.startsWith(b) } }
            ?: screens.singleOrNull()
            ?: return@runCatching null
        chosen.third to "${chosen.first}, ${chosen.second.first} × ${chosen.second.second}"
    }.getOrNull()

    /** Connectors that are a machine's own screen. */
    private val BUILT_IN = listOf("eDP", "LVDS", "DSI")

    /**
     * Android's density for a screen [widthPx] × [heightPx] whose EDID is [edid]: its dots to the inch over
     * 160, to two places. The size is the first detailed timing's millimetres (the centimetres of the
     * basic block when that is blank). Null for a size no real screen has — a projector or television
     * that reports none, or an aspect ratio in place of a size.
     */
    internal fun edidScale(edid: ByteArray, widthPx: Int, heightPx: Int): Float? {
        if (edid.size < 128 || widthPx <= 0 || heightPx <= 0) return null
        fun u(i: Int) = edid[i].toInt() and 0xFF
        var wMm = u(66) or ((u(68) and 0xF0) shl 4)
        var hMm = u(67) or ((u(68) and 0x0F) shl 8)
        if (wMm == 0 || hMm == 0) { wMm = u(21) * 10; hMm = u(22) * 10 }
        if (wMm < 60 || hMm < 40) return null
        // The size is the panel's own way up; a mode may be reported turned, so match long side to long side.
        val (w, h) = if ((widthPx > heightPx) == (wMm > hMm)) widthPx to heightPx else heightPx to widthPx
        val dpi = (w / (wMm / 25.4f) + h / (hMm / 25.4f)) / 2f
        if (dpi !in 72f..600f) return null
        return Math.round(dpi / 160f * 100f) / 100f
    }

    /** The primary logical monitor's scale from Mutter's own display configuration. */
    private fun gnomePrimaryScale(): Float? = runCatching {
        val out = Command.run(
            "gdbus", "call", "--session", "--dest", "org.gnome.Mutter.DisplayConfig",
            "--object-path", "/org/gnome/Mutter/DisplayConfig",
            "--method", "org.gnome.Mutter.DisplayConfig.GetCurrentState", seconds = 3,
        )?.out ?: return null
        // Logical monitors: (x, y, scale, uint32 transform, primary, [...], {...})
        val monitors = Regex("""\((-?\d+), (-?\d+), ([\d.]+), (?:uint32 )?\d+, (true|false)""").findAll(out).toList()
        val chosen = monitors.firstOrNull { it.groupValues[4] == "true" } ?: monitors.firstOrNull()
        chosen?.groupValues?.get(3)?.toFloatOrNull()?.takeIf { it in 0.5f..4f }
    }.getOrNull()
}
