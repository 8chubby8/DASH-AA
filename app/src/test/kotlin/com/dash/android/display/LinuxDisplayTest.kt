package com.dash.android.display

import com.dash.android.display.linux.KWinBackend
import com.dash.android.display.linux.LinuxDisplay
import com.dash.android.display.linux.ScreenBackend
import com.dash.android.display.linux.Snapshot
import com.dash.android.display.linux.WlrootsBackend
import com.dash.android.ui.rotation.DashOrientation
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Display (1.1.5): DASH's own memory of the screens, packing them, putting them back — and KWin's and wlroots' words. */
class LinuxDisplayTest {

    private val fhd = ScreenMode("1920x1080@60", 1920, 1080, 60.0, true)
    private val hd = ScreenMode("1280x720@60", 1280, 720, 60.0, true)

    private fun laptop() = Screen("eDP-1", "Built-in display", "BOE|panel|1", builtin = true, enabled = true, primary = true,
        width = 1920, height = 1080, modes = listOf(fhd), mode = fhd)

    private fun monitor(enabled: Boolean = true) = Screen("HDMI-1", "Dell", "DEL|U2415|ABC", builtin = false, enabled = enabled, primary = false,
        x = if (enabled) 1920 else 0, width = if (enabled) 1280 else 0, height = if (enabled) 720 else 0, modes = listOf(hd), mode = hd)

    /** A display program that does exactly as asked, and remembers every request. */
    private class FakeProgram(var screens: List<Screen>) : ScreenBackend {
        override val program = "Fake"
        val writes = mutableListOf<List<Screen>>()
        var onChange: (() -> Unit)? = null
        override fun read() = Snapshot(screens, DisplayFeatures(arrange = true, mirror = true))
        override fun write(target: List<Screen>, now: Snapshot): String? {
            writes += target
            screens = target.map { s -> if (s.enabled) s else s.copy(width = 0, height = 0) }
            return null
        }
        override fun watch(onChange: () -> Unit): AutoCloseable { this.onChange = onChange; return AutoCloseable { } }
    }

    private fun home(): File = Files.createTempDirectory("dash-display").toFile()

    private fun LinuxDisplay.settle() = flush()

    @Test
    fun packsScreensEdgeToEdgeAfterATurn() {
        val turned = LinuxDisplay.pack(listOf(laptop().copy(quarterTurns = 1), monitor()), scaledLayout = true)
        assertEquals(1080 to 1920, turned[0].width to turned[0].height)
        assertEquals(1080, turned[1].x)                       // the monitor moves in to the turned laptop's edge
        assertEquals(0, turned[1].y)
        val left = LinuxDisplay.pack(listOf(laptop(), monitor().copy(x = -1281)), scaledLayout = true)
        assertEquals(0, left[1].x)                            // packed, then moved so the top-left is 0,0
        assertEquals(1280, left[0].x)
        val mirror = LinuxDisplay.pack(listOf(laptop(), monitor().copy(mirrorOf = "eDP-1")), scaledLayout = true)
        assertEquals(0 to 0, mirror[1].x to mirror[1].y)
    }

    @Test
    fun rotatesTheMainScreenAndPutsItBackOnClosing() {
        val program = FakeProgram(listOf(laptop()))
        val home = home()
        val d = LinuxDisplay(home, { listOf(program) })
        d.start()
        d.rotate(DashOrientation.PORTRAIT)
        d.settle()
        assertEquals(1, program.screens[0].quarterTurns)
        assertTrue(File(home, "found.json").exists())          // a crash now cannot lose the way it was
        assertEquals(DashOrientation.PORTRAIT, d.state.value.orientation)
        d.restore()
        assertEquals(0, program.screens[0].quarterTurns)
        assertFalse(File(home, "found.json").exists())
    }

    @Test
    fun autoMeansTheTurnDashFound() {
        val program = FakeProgram(listOf(laptop().copy(quarterTurns = 2)))
        val d = LinuxDisplay(home(), { listOf(program) })
        d.start()
        d.rotate(DashOrientation.LANDSCAPE)
        d.rotate(null)
        d.settle()
        assertEquals(2, program.screens[0].quarterTurns)
    }

    @Test
    fun aKeptChangeIsRememberedAndAppliedWhenTheScreenComesBack() {
        val program = FakeProgram(listOf(laptop(), monitor()))
        val home = home()
        val d = LinuxDisplay(home, { listOf(program) })
        d.start()
        d.settle()
        assertEquals(setOf("HDMI-1"), d.state.value.unfamiliar)
        // Mirror the monitor, and keep it.
        d.arrange(listOf(monitor().copy(mirrorOf = "eDP-1")), keep = true)
        d.settle()
        assertEquals("eDP-1", program.screens[1].mirrorOf)
        assertTrue(d.state.value.unfamiliar.isEmpty())
        // Unplugged, then plugged in again (the display program set it up its own way).
        program.screens = listOf(laptop())
        program.onChange!!()
        d.settle()
        program.screens = listOf(laptop(), monitor())
        program.onChange!!()
        d.settle()
        assertEquals("eDP-1", program.screens[1].mirrorOf)
        // And a fresh DASH remembers too.
        val again = LinuxDisplay(home, { listOf(FakeProgram(listOf(laptop(), monitor()))) })
        again.start(); again.settle()
        assertTrue(again.state.value.unfamiliar.isEmpty())
    }

    @Test
    fun aTriedChangeIsNotRemembered() {
        val program = FakeProgram(listOf(laptop(), monitor()))
        val home = home()
        val d = LinuxDisplay(home, { listOf(program) })
        d.start()
        d.arrange(listOf(monitor(enabled = false)), keep = false)
        d.settle()
        assertFalse(program.screens[1].enabled)
        assertFalse(File(home, "screens.json").exists())
    }

    @Test
    fun noDisplayProgramSaysSoAndNothingBreaks() {
        val d = LinuxDisplay(home(), { emptyList() })
        d.start(); d.settle()
        assertFalse(d.state.value.available)
        assertTrue(d.state.value.note.isNotBlank())
        d.rotate(DashOrientation.PORTRAIT)
        d.restore()
    }

    @Test
    fun sameSetupIgnoresSizesAndNames() {
        assertTrue(LinuxDisplay.sameSetup(listOf(laptop()), listOf(laptop().copy(width = 5, name = "x", brightness = 0.3f))))
        assertFalse(LinuxDisplay.sameSetup(listOf(laptop()), listOf(laptop().copy(quarterTurns = 1))))
    }

    // ---- KWin ----

    private val kwinJson = """
        {"outputs":[
          {"id":1,"name":"eDP-1","type":7,"connected":true,"enabled":true,"priority":1,"pos":{"x":0,"y":0},"scale":1.5,
           "rotation":1,"currentModeId":"2","modes":[{"id":"1","name":"1280x800@60","size":{"width":1280,"height":800},"refreshRate":60.0},
           {"id":"2","name":"2560x1600@120","size":{"width":2560,"height":1600},"refreshRate":120.0}],"preferredModes":["2"],
           "replicationSource":0,"vrrPolicy":2,"overscan":0,"rgbRange":0,"hdr":false,"brightness":0.8},
          {"id":2,"name":"HDMI-A-1","type":3,"connected":true,"enabled":true,"priority":2,"pos":{"x":1707,"y":0},"scale":1.0,
           "rotation":2,"currentModeId":"5","modes":[{"id":"5","name":"1920x1080@60","size":{"width":1920,"height":1080},"refreshRate":60.0}],
           "preferredModes":["5"],"replicationSource":0,"vrrPolicy":0,"overscan":4,"rgbRange":2,"hdr":true}
        ]}
    """.trimIndent()

    @Test
    fun readsKWin() {
        val outputs = KWinBackend.parse(kwinJson)
        assertEquals(2, outputs.size)
        assertTrue(outputs[0].builtin)
        assertEquals(2, outputs[1].rotation)
        assertEquals(4, outputs[1].overscan)
    }

    @Test
    fun speaksKscreenDoctor() {
        val outputs = KWinBackend.parse(kwinJson)
        val target = listOf(
            Screen("eDP-1", "Built-in display", "kwin|eDP-1", true, true, true, scale = 1.5, quarterTurns = 1,
                mode = ScreenMode("2", 2560, 1600, 120.0), vrr = false, overscan = Overscan(0, true), hdr = false, rgbRange = RgbRange.AUTO),
            Screen("HDMI-A-1", "HDMI-A-1", "kwin|HDMI-A-1", false, true, false, x = 1067, scale = 1.0,
                mode = ScreenMode("5", 1920, 1080, 60.0), mirrorOf = null, rgbRange = RgbRange.FULL, hdr = false),
        )
        val args = KWinBackend.configArgs(target, outputs, canMirror = true)
        assertTrue("output.eDP-1.rotation.left" in args)
        assertTrue("output.eDP-1.primary" in args)
        assertTrue("output.eDP-1.vrrpolicy.never" in args)
        assertTrue("output.HDMI-A-1.position.1067,0" in args)
        assertTrue("output.HDMI-A-1.rgbrange.full" in args)
        assertTrue("output.HDMI-A-1.hdr.disable" in args)
        assertTrue("output.HDMI-A-1.mirror.none" in args)
        assertFalse(args.any { it.startsWith("output.eDP-1.hdr") })   // unchanged, so not asked
    }

    // ---- wlroots ----

    private val wlrJson = """
        [{"name":"eDP-1","description":"BOE 0x0BCA","make":"BOE","model":"0x0BCA","serial":"","enabled":true,
          "modes":[{"width":1920,"height":1200,"refresh":60.002,"preferred":true,"current":true}],
          "position":{"x":0,"y":0},"transform":"flipped-90","scale":1.25,"adaptive_sync":false},
         {"name":"DP-2","description":"Dell","make":"Dell Inc.","model":"U2415","serial":"7","enabled":false,
          "modes":[{"width":1920,"height":1200,"refresh":59.95,"preferred":true,"current":false}],
          "position":{"x":0,"y":0},"transform":"normal","scale":1.0,"adaptive_sync":false}]
    """.trimIndent()

    @Test
    fun readsAndSpeaksWlrRandr() {
        val screens = WlrootsBackend.parse(wlrJson, null)
        assertEquals(1, screens[0].quarterTurns)
        assertTrue(screens[0].flipped)
        assertTrue(screens[0].primary)
        assertEquals(960 to 1536, screens[0].width to screens[0].height)
        assertFalse(screens[1].enabled)
        assertNull(screens[1].mode)
        val args = WlrootsBackend.configArgs(listOf(screens[0].copy(quarterTurns = 0, flipped = false), screens[1]))
        assertEquals(listOf("--output", "eDP-1", "--on", "--mode", "1920x1200@60.002Hz", "--pos", "0,0", "--scale", "1.25",
            "--transform", "normal", "--adaptive-sync", "disabled", "--output", "DP-2", "--off"), args)
    }
}
