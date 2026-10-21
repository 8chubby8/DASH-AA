package com.dash.android.terminal

import com.dash.android.system.linux.Keyboards
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 1.1.10: is a keyboard plugged in, and a real shell in DASH's terminal, end to end. */
class TerminalTest {

    // The G14's own devices, cut down: buttons, hot keys and a touchpad the kernel also lists with keys.
    private val g14 = """
        I: Bus=0019 Vendor=0000 Product=0001 Version=0000
        N: Name="Power Button"
        H: Handlers=kbd event0
        B: EV=3
        B: KEY=8000 10000000000000 0

        I: Bus=0011 Vendor=0001 Product=0001 Version=ab83
        N: Name="AT Raw Set 2 keyboard"
        H: Handlers=sysrq kbd leds event3
        B: EV=120013
        B: KEY=402000007 ff803078f800d001 feffffdfffcfffff fffffffffffffffe

        I: Bus=0019 Vendor=0000 Product=0000 Version=0000
        N: Name="Asus WMI hotkeys"
        H: Handlers=kbd event7
        B: EV=100013
        B: KEY=400000000000000 10000000c0000 400400000 800000000000 0 0 181606f00900c00 380027801701000 e000000000000 0

        I: Bus=0018 Vendor=093a Product=3011 Version=0100
        N: Name="ASUP1208:00 093A:3011 Touchpad"
        H: Handlers=mouse1 event12
        B: EV=10001b
        B: KEY=e520 10000 0 0 0 0
    """.trimIndent()

    @Test fun aKeyboardIsOneWithEveryLetter() {
        assertEquals(listOf("AT Raw Set 2 keyboard"), Keyboards.plugged(g14).map { it.name })
    }

    @Test fun buttonsAndHotKeysAreNotKeyboards() {
        val noKeyboard = g14.split("\n\n").filterNot { "AT Raw" in it }.joinToString("\n\n")
        assertEquals(emptyList(), Keyboards.plugged(noKeyboard))
        assertEquals(emptyList(), Keyboards.plugged(null), "an unreadable list is no keyboard, not a fault")
    }

    @Test fun thisMachineAnswers() {
        // Whatever is plugged in here, reading the real file never fails.
        println("keyboards here: " + Keyboards.plugged().joinToString { it.name })
    }

    // ---- A real shell ----

    private fun TerminalSession.screen(): String {
        buffer.lock()
        try { return buffer.getScreenLines() } finally { buffer.unlock() }
    }

    private fun TerminalSession.waitFor(what: String, seconds: Int = 8): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            if (what in screen()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test fun aRealShell() {
        if (!TerminalSession.available()) { println("no terminal on this machine — skipped"); return }
        val s = TerminalSession.start(80, 24, "/bin/sh")
        try {
            Thread.sleep(500) // the shell's own start
            // printf, so the answer differs from the command that asked for it, in any shell.
            s.send("printf 'DASH-%s\\n' 42\r")
            assertTrue(s.waitFor("DASH-42"), "the shell answers:\n${s.screen()}")

            s.send("clear; stty size\r")
            assertTrue(s.waitFor("24 80"), "the shell knows its size:\n${s.screen()}")
            s.resize(100, 30)
            Thread.sleep(800)
            s.send("clear; stty size\r")
            assertTrue(s.waitFor("30 100"), "and is told when it changes:\n${s.screen()}")

            // Ctrl+C reaches the program in front — the terminal is the shell's controlling terminal.
            s.send("sleep 30\r")
            Thread.sleep(500)
            s.send("\u0003")
            s.send("printf 'AFTER-%s\\n' 1\r")
            assertTrue(s.waitFor("AFTER-1", 4), "Ctrl+C stopped the sleep:\n${s.screen()}")

            s.send("id -u\r")
            Thread.sleep(500)
            assertTrue(s.screen().lines().none { it.trim() == "0" }, "never root")

            s.send("exit\r")
            val end = System.currentTimeMillis() + 5000
            while (!s.ended.value && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(s.ended.value, "exit ends the session")
        } finally {
            s.close()
        }
    }

    @Test fun closingHangsUp() {
        if (!TerminalSession.available()) return
        val s = TerminalSession.start(80, 24, "/bin/sh")
        Thread.sleep(1000)
        s.send("sleep 300\r")
        Thread.sleep(300)
        s.close()
        val end = System.currentTimeMillis() + 5000
        while (!s.ended.value && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(s.ended.value, "START AGAIN ends the old shell and what it was running")
    }
}
