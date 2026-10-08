package com.dash.android.connections

import com.dash.android.connections.linux.BlueZBluetooth
import com.dash.android.connections.linux.NetworkManagerNetwork
import com.dash.android.ui.keyboard.CarStill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionsTest {
    @Test fun `nmcli's escaped colons stay inside their field`() {
        assertEquals(listOf("*", "Rogers-AP-2.4", "94:83:C4:91:84:FE", "88"), NetworkManagerNetwork.splitTerse("""*:Rogers-AP-2.4:94\:83\:C4\:91\:84\:FE:88"""))
        assertEquals(listOf("a\\b", ""), NetworkManagerNetwork.splitTerse("""a\\b:"""))
    }

    @Test fun `networks nearby are one per name, strongest first, the hosted one left out`() {
        val text = """
            |:Cafe:40:WPA2:2437 MHz:Infra
            |:Cafe:70:WPA2:5180 MHz:Infra
            |*:Home:60:WPA2:2412 MHz:Infra
            |::90:WPA2:2412 MHz:Infra
            |:DASH:99:WPA2:2412 MHz:Infra
            |:Open:20:--:2462 MHz:Infra
            """.trimMargin()
        val n = NetworkManagerNetwork.parseNearby(text, known = setOf("Home"), hosted = "DASH")
        assertEquals(listOf("Home", "Cafe", "Open"), n.map { it.ssid })
        assertEquals(Band.GHZ_5, n[1].band)
        assertEquals(70, n[1].signal)
        assertTrue(n[0].inUse && n[0].known)
        assertFalse(n[2].secured)
    }

    @Test fun `device blocks split on blank lines, values keep their colons`() {
        val d = NetworkManagerNetwork.parseDevices("GENERAL.DEVICE:wlan0\nGENERAL.HWADDR:84:9E:56:E4:7C:95\n\nGENERAL.DEVICE:enp5s0\n")
        assertEquals(2, d.size)
        assertEquals("84:9E:56:E4:7C:95", d[0]["GENERAL.HWADDR"])
    }

    @Test fun `nmcli failures in plain words`() {
        assertEquals("Could not join Home: the password was not accepted.",
            NetworkManagerNetwork.plain("join Home", "Error: Connection activation failed: Secrets were required, but not provided."))
        assertTrue(NetworkManagerNetwork.plain("x", "Error: Not authorized to control networking.").contains("permission refused"))
    }

    @Test fun `BlueZ failures in plain words`() {
        assertTrue(BlueZBluetooth.plain(null, "pair with it", timedOut = true).contains("no answer in time"))
    }

    @Test fun `a device is a module by DASH's marker, otherwise by what it says it is`() {
        assertEquals(DeviceKind.MODULE, DeviceKind.of("D.A.S.H-Climate", 0x1F00, null))
        assertEquals(DeviceKind.PHONE, DeviceKind.of("Rogers Phone", 5915148, "phone"))   // the Pixel's own class
        assertEquals(DeviceKind.AUDIO, DeviceKind.of("Speaker", 0x240404, null))
        assertEquals(DeviceKind.OTHER, DeviceKind.of("Thing", null, null))
    }

    @Test fun `the car is still at zero speed, or by the handbrake with no speed, unknown with neither`() {
        assertNull(CarStill.decide(null, null))
        assertEquals(false, CarStill.decide(true, 50f))      // a speed outranks the handbrake: rolling with it on
        assertEquals(true, CarStill.decide(true, null))
        assertEquals(true, CarStill.decide(null, 0f))
        assertEquals(false, CarStill.decide(null, 30f))
        assertEquals(false, CarStill.decide(false, null))
        assertEquals(true, CarStill.decide(false, 0f))       // waiting at the lights, handbrake off
    }

    @Test fun `addresses are checked`() {
        assertTrue(FixedAddress.valid("192.168.8.50"))
        assertFalse(FixedAddress.valid("192.168.8.256"))
        assertFalse(FixedAddress.valid("192.168.8"))
    }

    @Test fun `a new DASH network password is readable and long enough`() {
        val p = DashNetwork.newPassword()
        assertEquals(12, p.length)
        assertTrue(p.none { it in "0O1lI" })
    }
}
