package com.dash.android.display

import com.dash.android.aa.input.EvdevTouch
import com.dash.android.display.linux.MutterBackend
import com.dash.android.ui.rotation.DashOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Display (1.1.5) through GNOME: reading Mutter, asking it for a change, and turning the touchscreen to match. */
class MutterDisplayTest {

    /** The G14's own answer to GetCurrentState, through `busctl --json=short`, with most modes trimmed. */
    private val g14 = """{"type":"ua((ssss)a(siiddada{sv})a{sv})a(iiduba(ssss)a{sv})a{sv}","data":[3,[[["eDP-2","SDC","ATNA40CU05-0 ","0x00000000"],[["2880x1800@120.000",2880,1800,120.00029754638672,1.6666666269302368,[1.0,1.25,1.3333333730697632,1.5,1.6666666269302368,2.0,2.25,2.5,2.6666667461395264,3.0,3.3333332538604736,3.75],{"is-current":{"type":"b","data":true},"is-preferred":{"type":"b","data":true}}],["2880x1800@120.000+vrr",2880,1800,120.00029754638672,1.6666666269302368,[1.0,1.25,1.3333333730697632,1.5,1.6666666269302368,2.0],{"refresh-rate-mode":{"type":"s","data":"variable"}}],["1920x1080@120.000",1920,1080,120.00029754638672,1.25,[1.0,1.25,1.3333333730697632,1.5,1.6666666269302368,2.0],{}]],{"is-underscanning":{"type":"b","data":false},"is-builtin":{"type":"b","data":true},"display-name":{"type":"s","data":"Built-in display"},"min-refresh-rate":{"type":"i","data":48},"is-for-lease":{"type":"b","data":false},"color-mode":{"type":"u","data":0},"supported-color-modes":{"type":"au","data":[0,2]},"rgb-range":{"type":"u","data":1}}]],[[0,0,1.6666666269302368,0,true,[["eDP-2","SDC","ATNA40CU05-0 ","0x00000000"]],{}]],{"layout-mode":{"type":"u","data":1},"supports-changing-layout-mode":{"type":"b","data":true}}]}"""

    @Test
    fun readsTheG14() {
        val raw = MutterBackend.parse(g14)
        assertEquals(3, raw.serial)
        val m = raw.monitors.single()
        assertEquals("eDP-2", m.connector)
        assertEquals("Built-in display", m.displayName)
        assertEquals(listOf(0, 2), m.colorModes)
        assertEquals(false, m.underscanning)
        assertEquals(3, m.modes.size)
        assertTrue(m.modes[1].variable)
        assertEquals(1, raw.layoutMode)
        assertEquals(1728 to 1080, MutterBackend.logicalSize(2880, 1800, raw.logical[0].scale, 0, 1))
        assertEquals(1080 to 1728, MutterBackend.logicalSize(2880, 1800, raw.logical[0].scale, 1, 1))
    }

    private fun g14Screen(raw: MutterBackend.Raw = MutterBackend.parse(g14)) = Screen(
        id = "eDP-2", name = "Built-in display", identity = "SDC|ATNA40CU05-0 |0x00000000", builtin = true,
        enabled = true, primary = true, scale = raw.logical[0].scale,
        mode = ScreenMode("2880x1800@120.000", 2880, 1800, 120.0, true),
        overscan = Overscan(0, false), hdr = null, rgbRange = RgbRange.AUTO, vrr = false,
    )

    /** Exactly the arguments checked by hand against the G14's Mutter (method 0, verify only). */
    @Test
    fun asksMutterToTurnTheScreen() {
        val raw = MutterBackend.parse(g14)
        val args = MutterBackend.configArgs(raw, listOf(g14Screen(raw).copy(quarterTurns = 1)), full = true).getOrThrow()(MutterBackend.METHOD_VERIFY)
        assertEquals(
            listOf(
                "uua(iiduba(ssa{sv}))a{sv}", "3", "0", "1",
                "0", "0", "1.6666666269302368", "1", "true", "1",
                "eDP-2", "2880x1800@120.000", "3", "underscanning", "b", "false", "color-mode", "u", "0", "rgb-range", "u", "1",
                "1", "layout-mode", "u", "1",
            ),
            args,
        )
        val bare = MutterBackend.configArgs(raw, listOf(g14Screen(raw)), full = false).getOrThrow()(MutterBackend.METHOD_TEMPORARY)
        assertEquals(listOf("eDP-2", "2880x1800@120.000", "0"), bare.subList(10, 13))
    }

    @Test
    fun adaptiveSyncIsTheVariableTwinOfTheMode() {
        val raw = MutterBackend.parse(g14)
        val args = MutterBackend.configArgs(raw, listOf(g14Screen(raw).copy(vrr = true)), full = false).getOrThrow()(0)
        assertTrue("2880x1800@120.000+vrr" in args)
    }

    @Test
    fun aScaleNotAllowedAtTheResolutionIsMovedToTheNearest() {
        val raw = MutterBackend.parse(g14)
        val args = MutterBackend.configArgs(raw, listOf(g14Screen(raw).copy(scale = 1.6)), full = false).getOrThrow()(0)
        assertEquals("1.6666666269302368", args[6])
    }

    @Test
    fun theLastScreenCannotBeTurnedOff() {
        val raw = MutterBackend.parse(g14)
        assertTrue(MutterBackend.configArgs(raw, listOf(g14Screen(raw).copy(enabled = false)), full = true).isFailure)
    }

    @Test
    fun orientationsMapBothWaysForEitherPanelShape() {
        for (natural in listOf(false, true)) {
            for (o in DashOrientation.entries) {
                val t = transformFor(o, natural)
                assertEquals(o, orientationOf(t, natural))
                assertEquals(o, orientationOf(t + 4, natural))   // mirrored still faces the same way
            }
        }
        assertEquals(0, transformFor(DashOrientation.LANDSCAPE, false))
        assertEquals(0, transformFor(DashOrientation.PORTRAIT, true))
        assertEquals(2, transformFor(DashOrientation.LANDSCAPE_REVERSED, false))
    }

    @Test
    fun touchesTurnWithTheScreen() {
        // The panel's top-left corner, at each quarter turn anticlockwise.
        assertEquals(0f to 0f, EvdevTouch.panelToScreen(0f, 0f, 0, false))
        assertEquals(1f to 0f, EvdevTouch.panelToScreen(0f, 0f, 1, false))
        assertEquals(1f to 1f, EvdevTouch.panelToScreen(0f, 0f, 2, false))
        assertEquals(0f to 1f, EvdevTouch.panelToScreen(0f, 0f, 3, false))
        // Mirrored: the panel's left becomes the screen's right.
        assertEquals(1f to 0f, EvdevTouch.panelToScreen(0f, 0f, 0, true))
        // A turn and its opposite bring any touch back where it was.
        val (x, y) = EvdevTouch.panelToScreen(0.2f, 0.7f, 1, false)
        val (bx, by) = EvdevTouch.panelToScreen(x, y, 3, false)
        assertEquals(0.2f, bx, 1e-6f)
        assertEquals(0.7f, by, 1e-6f)
    }

    @Test
    fun findsEveryTouchscreenAndItsUsbId() {
        val devices = """
            I: Bus=0003 Vendor=0eef Product=0001 Version=0100
            N: Name="eGalax Touch Screen"
            H: Handlers=mouse2 event7
            B: PROP=2
            B: ABS=260800000000003

            I: Bus=0018 Vendor=093a Product=3011 Version=0100
            N: Name="ASUP1208:00 093A:3011 Touchpad"
            H: Handlers=mouse1 event5
            B: PROP=5
            B: ABS=2e0800000000003
        """.trimIndent()
        val found = EvdevTouch.touchscreens(devices)
        assertEquals(listOf(EvdevTouch.Device("/dev/input/event7", "eGalax Touch Screen", "0eef:0001")), found)
    }
}
