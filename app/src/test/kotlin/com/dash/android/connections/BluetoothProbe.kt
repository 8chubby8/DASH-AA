package com.dash.android.connections

import com.dash.android.connections.linux.BlueZBluetooth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (-Dconnections=1): the machine's real BlueZ. Reads the adapter and devices and becomes the pairing
 * agent for a moment. Changes nothing: no pairing, no power, no visibility.
 */
class BluetoothProbe {
    @Test
    fun readsTheRealBluetooth() {
        if (System.getProperty("connections") == null) return
        val bt = BlueZBluetooth()
        bt.start()
        val s = runBlocking { withTimeout(10_000) { bt.state.first { it.available || !it.note.startsWith("Looking") } } }
        Thread.sleep(1500); val s2 = bt.state.value; println("LATER devices=${s2.devices.size}")
        println("BLUETOOTH: available=${s.available} note='${s.note}' powered=${s.powered} name='${s.name}' ${s.address} visible=${s.visible}")
        s2.devices.forEach { println("  ${it.kind} ${it.address} '${it.name}' paired=${it.paired} connected=${it.connected} rssi=${it.rssi}") }
        assertTrue(s.available, s.note)
    }
}
