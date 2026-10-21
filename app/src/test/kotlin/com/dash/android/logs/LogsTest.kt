package com.dash.android.logs

import android.util.Log
import com.dash.android.logs.linux.Journal
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 1.1.9: blanking, reading kept runs back, and the machine's log sorted into parts. */
class LogsTest {

    private val known = Blanker.Known(
        devices = listOf(
            Blanker.Device("F8:DA:0C:11:22:33", "Roger's Pixel 8 Pro", phone = true),
            Blanker.Device("24:0A:C4:00:00:01", "D.A.S.H Climate", module = true),
        ),
        networks = setOf("Mudi-5G"),
        places = setOf("Swindon"),
        user = "roger",
        hostname = "victor",
    )

    private fun blanker() = Blanker(known) { oui -> if (oui == "F8DA0C") "Hon Hai Precision Ind. Co.,Ltd." else null }

    @Test fun addressesAndNamesShareOneLabel() {
        val b = blanker()
        assertEquals("Phone 1 · Hon Hai Precision connected", b.blank("F8:DA:0C:11:22:33 connected"))
        assertEquals("Phone 1 asks about pairing", b.blank("Roger's Pixel 8 Pro asks about pairing"))
        assertEquals("/org/bluez/hci0/dev_(Phone 1 · Hon Hai Precision)", b.blank("/org/bluez/hci0/dev_F8_DA_0C_11_22_33"))
        assertEquals("Device 1 dropped", b.blank("7A:11:22:33:44:55 dropped"), "a random address has no maker")
    }

    @Test fun modulesKeepTheirNames() {
        assertEquals("connected to D.A.S.H Climate", blanker().blank("connected to D.A.S.H Climate"))
    }

    @Test fun networksPlacesAndPeople() {
        val b = blanker()
        assertEquals("joined Network 1", b.blank("joined Mudi-5G"))
        assertEquals("Trying to associate (SSID='Network 2' freq=2437 MHz)", b.blank("Trying to associate (SSID='Rogers-AP-2.4' freq=2437 MHz)"))
        assertEquals("located (place) (map position)", b.blank("located Swindon (51.5558, -1.7797)"))
        assertEquals("located (place) (map position)", Blanker().blank("located Bristol (51.4545, -2.5879)"), "a place DASH found is learnt as it goes past")
        assertEquals("data in ~/.local/share/dash", b.blank("data in /home/roger/.local/share/dash"))
        assertEquals("this-machine kernel: hello user", b.blank("victor kernel: hello roger"))
        assertEquals("mail (email address)", b.blank("mail roger@example.com"))
        assertEquals("usb 1-2: SerialNumber: (serial)", b.blank("usb 1-2: SerialNumber: 3A281FDJH00B9X"))
    }

    @Test fun addressesInsideTheCarStay() {
        val b = blanker()
        assertEquals("client connected from 192.168.8.20", b.blank("client connected from 192.168.8.20"))
        assertEquals("GET from (internet address)", b.blank("GET from 81.2.69.160"))
        assertEquals("SRC=(internet address) DST=(internet address)",
            b.blank("SRC=fe80:0000:0000:0000:9b9c:8780:1121:ef00 DST=ff12:0000:0000:0000:0000:0000:0000:8384"))
    }

    @Test fun ordinaryLinesAreLeftAlone() {
        val b = blanker()
        for (s in listOf("10:41:29.123 sound is flowing again", "volume 0.500, 0.750", "media stopped after 12.5s: 3401 packets",
                "module 0000DA58EE07 installed", "phone speaks Android Auto 1.7 (status 0)", "Class::method",
                "modprobe@sd_mod.service: Deactivated successfully.", "Started dbus-:1.2-org.gnome.Shell.Screencast@0.service.")) {
            assertEquals(s, b.blank(s))
        }
    }

    @Test fun keptRunsReadBack() {
        val dir = createTempDirectory("dash-logs").toFile()
        val f = java.io.File(dir, "run-20261021-104100.log")
        f.writeText(
            "# DASH 1.1.9 started 2026-10-21 10:41:00.000\n" +
                LogBook.format(LogLine(1_792_577_000_000, Log.INFO, "DashAaUsb", "phone found")) + "\n" +
                LogBook.format(LogLine(1_792_577_001_000, Log.ERROR, "DashCrash", "DASH stopped\njava.lang.IllegalStateException: x\n\tat a.b(c.kt:1)")) + "\n"
        )
        val lines = LogBook.parse(f)
        assertEquals(3, lines.size)
        assertEquals(LogParts.ANDROID_AUTO, lines[1].part)
        assertEquals(Log.ERROR, lines[2].level)
        assertTrue(lines[2].message.endsWith("at a.b(c.kt:1)"), lines[2].message)
    }

    @Test fun theMachinesLogInParts() {
        assertEquals(Journal.USB, Journal.part("kernel", null, "usb 1-2: new high-speed USB device number 7 using xhci_hcd"))
        assertEquals(Journal.BLUETOOTH, Journal.part("kernel", null, "Bluetooth: hci0: unexpected event"))
        assertEquals(Journal.WIFI, Journal.part("kernel", null, "mwifiex_pcie 0000:01:00.0: info: successfully disconnected"))
        assertEquals(Journal.WIFI, Journal.part("NetworkManager", null, "device (wlan0): state change: activated"))
        assertEquals(Journal.NETWORK, Journal.part("NetworkManager", null, "device (enp5s0): carrier: link connected"))
        assertEquals(Journal.SOUND, Journal.part("wireplumber", null, "spa.bluez5: failed"))
        assertFalse(Journal.part("gnome-shell", null, "whatever") == Journal.DASH)
        val l = Journal.parse("""{"__REALTIME_TIMESTAMP":"1792577159308759","PRIORITY":"3","SYSLOG_IDENTIFIER":"bluetoothd","MESSAGE":"a2dp failed"}""")!!
        assertEquals(Log.ERROR, l.level); assertEquals(Journal.BLUETOOTH, l.part); assertEquals(1792577159308, l.time)
    }
}
