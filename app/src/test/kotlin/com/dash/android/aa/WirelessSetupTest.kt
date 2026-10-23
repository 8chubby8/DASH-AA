package com.dash.android.aa

import com.dash.android.aa.protocol.ProtoMessage
import com.dash.android.aa.wireless.WirelessNetwork
import com.dash.android.aa.wireless.WirelessSetup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Wireless Android Auto's Bluetooth start (1.2.3), against a pretend phone that asks for the network. */
class WirelessSetupTest {

    @Test
    fun `the phone is told where DASH is, then the network`() {
        val n = WirelessNetwork("DASH", "abcdefgh2345", "C0:33:5E:0B:76:FF", "10.42.0.1")
        // The phone asks for the network, reports it has started, then closes the link.
        val phone = ByteArrayOutputStream().apply {
            write(WirelessSetup.frame(WirelessSetup.INFO_REQUEST, ByteArray(0)))
            write(WirelessSetup.frame(WirelessSetup.START_RESPONSE, byteArrayOf(0x18, 0x00)))
        }.toByteArray()
        val toPhone = ByteArrayOutputStream()
        val steps = mutableListOf<String>()
        WirelessSetup.talk(ByteArrayInputStream(phone), toPhone, n) { steps += it }

        val sent = ByteArrayInputStream(toPhone.toByteArray())
        val (id1, start) = WirelessSetup.readFrame(sent)!!
        assertEquals(WirelessSetup.START_REQUEST, id1)
        assertEquals("10.42.0.1", ProtoMessage.parse(start).string(1))
        assertEquals(WirelessSetup.PORT, ProtoMessage.parse(start).int(2))

        val (id2, info) = WirelessSetup.readFrame(sent)!!
        assertEquals(WirelessSetup.INFO_RESPONSE, id2)
        val m = ProtoMessage.parse(info)
        assertEquals("DASH", m.string(1))
        assertEquals("abcdefgh2345", m.string(2))
        assertEquals("C0:33:5E:0B:76:FF", m.string(3))
        assertEquals(8, m.int(4))           // WPA2 personal
        assertEquals(listOf("joining", "started, status 0"), steps)
    }
}
