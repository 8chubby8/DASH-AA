package com.dash.android.connections

import com.dash.android.connections.linux.NetworkManagerNetwork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in (-Dconnections=1): the machine's real NetworkManager, read only — no settings applied, nothing joined. */
class NetworkProbe {
    @Test
    fun readsTheRealNetworks() {
        if (System.getProperty("connections") == null) return
        val net = NetworkManagerNetwork()
        net.start()
        val s = runBlocking { withTimeout(15_000) { net.state.first { it.available || !it.note.startsWith("Looking") } } }
        println("NETWORK: available=${s.available} '${s.note}' wifiOn=${s.wifiOn} internet=${s.internet} phoneInternet=${s.phoneInternet}")
        s.adapters.forEach { println("  ADAPTER $it") }
        s.known.forEach { println("  KNOWN ${it.ssid} auto=${it.autoJoin} metered=${it.metered} prio=${it.priority} inUse=${it.inUse}") }
        s.nearby.take(8).forEach { println("  NEARBY ${it.ssid} ${it.signal}% ${it.band} secured=${it.secured} known=${it.known} inUse=${it.inUse}") }
        assertTrue(s.available, s.note)
    }
}
