package com.dash.android.display

import com.dash.android.display.linux.MutterBackend
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in (-Ddisplay=1): the real GNOME. Reads the screens, then has Mutter *check* — never make — the
 * setup as it is and the main screen turned to portrait (method 0). Nothing on screen changes, so it is
 * safe to run beside a DASH in use.
 */
class DisplayProbe {
    @Test fun `GNOME accepts what DASH would ask for`() {
        if (System.getProperty("display") == null) return
        val mutter = MutterBackend()
        val now = assertNotNull(mutter.read(), "Mutter did not answer")
        now.screens.forEach { println("screen: $it") }
        println("features: ${now.features}")
        val main = now.screens.first { it.primary }
        for (target in listOf(now.screens, now.screens.map { if (it.id == main.id) it.copy(quarterTurns = 1) else it })) {
            val raw = MutterBackend.parse(
                ProcessBuilder("busctl", "--user", "--json=short", "call", "org.gnome.Mutter.DisplayConfig",
                    "/org/gnome/Mutter/DisplayConfig", "org.gnome.Mutter.DisplayConfig", "GetCurrentState").start().inputStream.bufferedReader().readText(),
            )
            val args = MutterBackend.configArgs(raw, target, full = true).getOrThrow()(MutterBackend.METHOD_VERIFY)
            val p = ProcessBuilder(listOf("busctl", "--user", "call", "org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig",
                "org.gnome.Mutter.DisplayConfig", "ApplyMonitorsConfig") + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            println("check ${target.first { it.id == main.id }.quarterTurns} turns: exit ${p.waitFor()} $out")
            assertTrue(p.exitValue() == 0, out)
        }
    }
}
