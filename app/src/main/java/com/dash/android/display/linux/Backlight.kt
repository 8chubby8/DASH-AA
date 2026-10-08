package com.dash.android.display.linux

import java.io.File
import kotlin.math.roundToInt

/**
 * The built-in screen's backlight, set through logind (DASH-AA 1.1.5) — for display programs that do not
 * set brightness themselves. The kernel's `/sys/class/backlight` is readable by anyone but writable only
 * by root; logind's `Session.SetBrightness` is the seat user's way in, no root and no password, which is
 * what desktops use underneath.
 *
 * Capability-detected: no backlight (a desktop monitor, a machine with no panel) and [available] is false.
 */
internal class Backlight(private val root: File = File("/sys/class/backlight")) {
    /** The backlight to use, in the kernel's own order of preference: firmware, then platform, then raw. */
    // A laptop with two graphics chips can show two (the G14: amdgpu_bl2 and nvidia_0); the panel hangs
    // off the integrated one, so NVIDIA's is taken last.
    private val device: File? get() = root.listFiles()
        ?.sortedWith(compareBy<File>({ typeRank(it) }, { it.name.startsWith("nvidia") }, { it.name }))
        ?.firstOrNull { File(it, "max_brightness").canRead() }

    val available: Boolean get() = device != null

    /** 0–1, or null when there is no backlight. */
    fun level(): Float? {
        val d = device ?: return null
        val max = File(d, "max_brightness").readText().trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val now = File(d, "brightness").readText().trim().toIntOrNull() ?: return null
        return now.toFloat() / max
    }

    /** Set to [level] (0–1). Zero is allowed here — it is how blanking works with no display program to ask. */
    fun set(level: Float): Boolean {
        val d = device ?: return false
        val max = File(d, "max_brightness").readText().trim().toIntOrNull() ?: return false
        val value = (max * level.coerceIn(0f, 1f)).roundToInt()
        return run(listOf(
            "busctl", "call", "org.freedesktop.login1", "/org/freedesktop/login1/session/auto",
            "org.freedesktop.login1.Session", "SetBrightness", "ssu", "backlight", d.name, value.toString(),
        )).first == 0
    }

    private fun typeRank(d: File): Int = when (runCatching { File(d, "type").readText().trim() }.getOrNull()) {
        "firmware" -> 0
        "platform" -> 1
        else -> 2
    }
}
