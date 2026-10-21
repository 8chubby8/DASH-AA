package com.dash.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.dash.android.DashApplication
import com.dash.android.connections.PretendBluetooth
import com.dash.android.connections.PretendNetwork
import com.dash.android.power.PretendPower
import com.dash.android.terminal.DashTerminal
import com.dash.android.ui.terminal.TerminalContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.awt.event.KeyEvent as Awt
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (-Dscreenshots=1): Developer › Terminal with a real shell, typed into through Compose as a real
 * keyboard types — keys down, characters typed, Ctrl+C — with and without DASH's keyboard on the screen.
 */
class TerminalScreenshots {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `draw Terminal`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val app = DashApplication(createTempDirectory("dash-term-ui").toFile())
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(null)
        app.bluetoothMusic = {}
        app.power = PretendPower()
        app.onCreate()
        DashTerminal.shell = "/bin/bash"

        val source = java.awt.Canvas()
        // As the window does it: Compose's own wrapping of an AWT key (internal to Compose, so reached by name).
        val wrap = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", Awt::class.java)
        fun Awt.compose() = KeyEvent(wrap.invoke(null, this))
        fun ImageComposeScene.key(vk: Int, char: Char = Awt.CHAR_UNDEFINED, mods: Int = 0) {
            val t = System.currentTimeMillis()
            sendKeyEvent(Awt(source, Awt.KEY_PRESSED, t, mods, vk, char).compose())
            if (char != Awt.CHAR_UNDEFINED) sendKeyEvent(Awt(source, Awt.KEY_TYPED, t, mods, Awt.VK_UNDEFINED, char).compose())
            sendKeyEvent(Awt(source, Awt.KEY_RELEASED, t, mods, vk, char).compose())
        }
        fun ImageComposeScene.type(text: String) = text.forEach { c ->
            val vk = Awt.getExtendedKeyCodeForChar(c.code)
            key(vk, c, if (c.isUpperCase() || c in "'|>%()") Awt.SHIFT_DOWN_MASK else 0)
        }
        fun ImageComposeScene.settle(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { render(System.nanoTime()); Thread.sleep(16) }
        }
        fun screen(): String = DashTerminal.session.value?.let { s -> s.buffer.lock(); try { s.buffer.getScreenLines() } finally { s.buffer.unlock() } } ?: ""

        fun shot(name: String, onScreen: Boolean, w: Int, h: Int, play: ImageComposeScene.() -> Unit) {
            val scene = ImageComposeScene(w, h, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    Box(Modifier.fillMaxSize().background(LocalDashTheme.current.backgroundColourSecondary)) { TerminalContent(onScreen) }
                }
            }
            scene.settle(1500)
            scene.play()
            File(out, "terminal-$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote terminal-$name.png")
            scene.close()
        }

        shot("keyboard", false, 2000, 1100) {
            type("printf 'TYPED-%s\\n' ok"); key(Awt.VK_ENTER, '\n'); settle(600)
            assertTrue("TYPED-ok" in screen(), "typed through Compose:\n${screen()}")
            type("sleep 30"); key(Awt.VK_ENTER, '\n'); settle(300)
            key(Awt.VK_C, 3.toChar(), Awt.CTRL_DOWN_MASK); settle(300)
            type("ls --color=always /usr | head -5; echo"); key(Awt.VK_ENTER, '\n'); settle(400)
            type("printf '\\e[1;31mred \\e[32mgreen \\e[44;97m blue \\e[0m\\n'"); key(Awt.VK_ENTER, '\n'); settle(400)
            type("echo AFTER"); key(Awt.VK_ENTER, '\n'); settle(600)
            assertTrue("AFTER" in screen().substringAfter("sleep 30"), "Ctrl+C from the keyboard stopped the sleep:\n${screen()}")
        }
        shot("onscreen", true, 2000, 1300) {
            type("tput cols; tput lines"); key(Awt.VK_ENTER, '\n'); settle(600)
        }
        DashTerminal.restart()
    }

    /**
     * 1.1.10's crash on Roger's G14: DASH's keyboard came up, the space shrank, and for a moment the shell's
     * screen was bigger than it — drawing text past the edge threw, and the throw closed DASH. Always run.
     */
    @Test fun `a screen bigger than its space draws`() {
        val app = DashApplication(createTempDirectory("dash-term-big").toFile())
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(null)
        app.bluetoothMusic = {}
        app.power = PretendPower()
        app.onCreate()
        DashTerminal.shell = "/bin/sh"
        val s = DashTerminal.open(300, 120)!!
        // A full screen of text, right to the last column of the last row.
        s.send("i=0; while [ \$i -lt 130 ]; do printf '%0300d\\n' 0; i=\$((i+1)); done\r")
        Thread.sleep(1500)
        val scene = ImageComposeScene(900, 700, Density(1.5f)) {
            CompositionLocalProvider(LocalContext provides app) { TerminalContent(true) }
        }
        try {
            // Rendered before the resize reaches the shell: the bug drew here and threw.
            repeat(5) { scene.render(System.nanoTime()); Thread.sleep(16) }
        } finally {
            scene.close()
            DashTerminal.restart()
        }
    }
}
