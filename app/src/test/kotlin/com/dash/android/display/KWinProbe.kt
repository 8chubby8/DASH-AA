package com.dash.android.display

import com.dash.android.display.linux.KWinBackend
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in (-Dkwin=1): a real KWin, run headless with two pretend screens (`kwin_wayland --virtual`) on a
 * socket of its own — no window, and the desktop is never touched. DASH's KWin side reads it, turns,
 * moves, mirrors and switches off a screen, and reads each change back. Needs `kwin` and `libkscreen`.
 */
class KWinProbe {
    @Test fun `DASH sets up a real KWin`() {
        if (System.getProperty("kwin") == null) return
        val socket = "dash-kwin-probe"
        val runtime = System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/1000"
        val kwin = ProcessBuilder("kwin_wayland", "--virtual", "--no-lockscreen", "--socket", socket,
            "--width", "1920", "--height", "1080", "--output-count", "2")
            .apply { environment().remove("WAYLAND_DISPLAY"); environment().remove("DISPLAY") }
            .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "dash-kwin-probe.log")).start()
        try {
            val until = System.currentTimeMillis() + 10_000
            while (!File(runtime, socket).exists() && System.currentTimeMillis() < until) Thread.sleep(100)
            Thread.sleep(1000)
            val k = KWinBackend(mapOf("WAYLAND_DISPLAY" to socket, "QT_QPA_PLATFORM" to "wayland"))
            fun read() = assertNotNull(k.read(), "KWin did not answer — see dash-kwin-probe.log")
            val first = read()
            first.screens.forEach { println("screen: $it") }
            assertEquals(2, first.screens.size)
            val (a, b) = first.screens

            // Turn the first, and move the second to its new edge.
            assertNull(k.write(listOf(a.copy(quarterTurns = 1), b.copy(x = 1080)), first))
            val turned = read()
            println("turned: ${turned.screens}")
            assertEquals(1, turned.screens.first { it.id == a.id }.quarterTurns)
            assertEquals(1080, turned.screens.first { it.id == b.id }.x)

            // Mirror, where this KWin can.
            if (turned.features.mirror) {
                assertNull(k.write(listOf(a.copy(quarterTurns = 0), b.copy(mirrorOf = a.id, x = 0)), turned))
                val mirrored = read()
                println("mirrored: ${mirrored.screens}")
                assertEquals(a.id, mirrored.screens.first { it.id == b.id }.mirrorOf)
            }

            // Off, and on again.
            val now = read()
            assertNull(k.write(listOf(a.copy(quarterTurns = 0), b.copy(enabled = false, mirrorOf = null)), now))
            assertTrue(read().screens.first { it.id == b.id }.enabled.not())
            assertNull(k.write(listOf(a.copy(quarterTurns = 0), b.copy(enabled = true, mirrorOf = null, x = 1920)), read()))
            assertTrue(read().screens.first { it.id == b.id }.enabled)
        } finally {
            kwin.destroy()
        }
    }
}
