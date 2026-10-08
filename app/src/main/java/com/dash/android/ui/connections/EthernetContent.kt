package com.dash.android.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dash.android.connections.AdapterKind
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader

/**
 * Connections › Ethernet (DASH-AA 1.1.6, its own tab at Roger's ruling): each cable connection — a port
 * on the machine, or a USB hub's — what it is connected to, its address, and that address fixed so Wi-Fi
 * modules on the same router always find DASH. Roger's car: a SIM router on a cable for the internet,
 * modules on the router's Wi-Fi, and the machine's own Wi-Fi left free for the DASH network.
 *
 * There is nothing to join: a cable is plugged in, and the network service does the rest.
 */
@Composable
fun EthernetContent() {
    val (network, state) = rememberNetwork()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Ethernet")
        if (!state.available) { Note(state.note); return@Column }
        val wired = state.adapters.filter { it.kind == AdapterKind.ETHERNET }
        if (wired.isEmpty()) {
            Note("No Ethernet adapter. A port appears here when it is plugged in — a USB hub with an Ethernet socket, or a USB Ethernet adapter.")
            return@Column
        }
        Failure(state.failure)
        wired.forEach { a ->
            if (wired.size > 1) SettingsSectionHeader(a.name) else Note(a.name)
            if (!a.connected) {
                InfoRows(listOf("Status" to a.status))
                if (a.status == "Cable unplugged") Note("Plug a cable into it, from a router or a switch.")
            } else {
                AddressCard(network, state, a)
            }
        }
    }
}
