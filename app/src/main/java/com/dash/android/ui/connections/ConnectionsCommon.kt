package com.dash.android.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.connections.AdapterKind
import com.dash.android.connections.ConnectionsPreferences
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.connections.FixedAddress
import com.dash.android.connections.Internet
import com.dash.android.connections.NetworkAdapter
import com.dash.android.connections.NetworkState
import com.dash.android.connections.NetworkSystem
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch

/*
 * What the three Connections tabs share (DASH-AA 1.1.6): reaching the network and Bluetooth, DASH's own
 * Connections settings, lists whose rows open to show their controls, and the address card that tells a
 * module builder where to point their firmware.
 *
 * **For native:** shared, and taken as it is.
 */

/** The port Wi-Fi modules connect to DASH on (transport.md, *WiFi*). */
const val MODULE_PORT = 3274

@Composable
internal fun rememberNetwork(): Pair<NetworkSystem, NetworkState> {
    val app = LocalContext.current.applicationContext as DashApplication
    val state by app.network.state.collectAsState()
    return app.network to state
}

@Composable
internal fun rememberConnectionsSettings(): Pair<ConnectionsSettings, ((ConnectionsSettings) -> ConnectionsSettings) -> Unit> {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { ConnectionsPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = ConnectionsSettings())
    val scope = rememberCoroutineScope()
    return settings to { change -> scope.launch { prefs.update(change) } }
}

@Composable
internal fun Note(text: String) {
    val theme = LocalDashTheme.current
    Text(text, color = theme.textColourSecondary.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
}

/** What did not happen, said once, where the person is looking. */
@Composable
internal fun Failure(text: String?) {
    if (text == null) return
    val theme = LocalDashTheme.current
    Text(
        text,
        color = theme.textColourSecondary,
        fontSize = BODY,
        lineHeight = BODY_LINE,
        fontFamily = theme.font,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

internal class ListRow(
    val key: String,
    val label: String,
    val detail: String?,
    val lit: Boolean = false,
    /** A tap does this; with [open] given as well, a tap opens the row instead. */
    val onTap: (() -> Unit)? = null,
    /** What the row shows when opened: its own controls. */
    val open: (@Composable () -> Unit)? = null,
)

/**
 * Rows in one box, as the Audio and Display lists: a row with controls of its own opens to show them
 * when tapped, one at a time; the rest act at once.
 */
@Composable
internal fun RowList(rows: List<ListRow>) {
    if (rows.isEmpty()) return
    val theme = LocalDashTheme.current
    val opened = remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { r ->
            val isOpen = opened.value == r.key && r.open != null
            val ink = if (r.lit) theme.backgroundColourSecondary else theme.textColourSecondary
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (r.lit) theme.textColourSecondary else if (isOpen) theme.textColourSecondary.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (r.open != null) opened.value = if (isOpen) null else r.key else r.onTap?.invoke()
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(r.label, color = ink, fontSize = BODY, fontFamily = theme.font)
                    if (r.detail != null) Text(r.detail, color = ink.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
                }
                if (isOpen) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(theme.backgroundColourSecondary)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { r.open!!.invoke() }
                }
            }
        }
    }
}

/** Buttons side by side, wrapping to the next line when the row runs out. */
@Composable
internal fun ButtonRow(vararg buttons: Pair<String, () -> Unit>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { (label, onClick) -> DashButton(label, onClick = onClick) }
    }
}

internal fun signalWords(signal: Int) = when {
    signal >= 70 -> "Strong"
    signal >= 45 -> "Good"
    signal >= 25 -> "Weak"
    else -> "Very weak"
}

/**
 * Where an adapter is: its network, its address, and — the reason the tab exists for a module builder —
 * the address a Wi-Fi module must be pointed at. With the address controls: let the network choose it,
 * or fix it, so the modules always find DASH in the same place.
 */
@Composable
internal fun AddressCard(network: NetworkSystem, state: NetworkState, adapter: NetworkAdapter) {
    val ip = adapter.address?.substringBefore('/')
    InfoRows(
        listOfNotNull(
            adapter.network?.let { (if (adapter.kind == AdapterKind.WIFI) "Network" else "Connection") to it },
            adapter.speed?.takeIf { adapter.kind == AdapterKind.ETHERNET }?.let { "Speed" to "$it Mb/s" },
            "Address" to (ip ?: "None yet"),
            ip?.let { "Modules connect to" to "$it : $MODULE_PORT" },
            adapter.gateway?.let { "Router" to it },
            "Internet" to state.internet.label,
        )
    )
    if (ip == null) return
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val editing = remember(adapter.id) { mutableStateOf(false) }
    SettingBlock(
        name = "Address",
        help = if (adapter.fixed) "Fixed: the same every time, so modules always find DASH at $ip."
        else "Chosen by the network, so it may change. Fix it and modules always find DASH at the same address " +
            "(or reserve it for DASH in the router's own settings).",
        control = {
            PresetSegment(listOf("Automatic", "Fixed"), if (adapter.fixed) 1 else 0, controlWidth) { i ->
                when {
                    i == 0 && adapter.fixed -> network.setAddress(adapter.id, null)
                    // One tap keeps the address it has now — the common wish.
                    i == 1 && !adapter.fixed -> network.setAddress(
                        adapter.id,
                        FixedAddress(ip, adapter.address.substringAfter('/', "24").toIntOrNull() ?: 24, adapter.gateway, adapter.dns),
                    )
                }
            }
        },
    )
    if (adapter.fixed) {
        if (!editing.value) {
            DashButton("Change the fixed address", onClick = { editing.value = true }, modifier = controlWidth)
        } else {
            val address = remember(adapter.id) { mutableStateOf(ip) }
            val gateway = remember(adapter.id) { mutableStateOf(adapter.gateway.orEmpty()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note("Address"); KeyboardField(address, "Address", Modifier.weight(1f), placeholder = "192.168.8.50", digits = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note("Router"); KeyboardField(gateway, "Router", Modifier.weight(1f), placeholder = "192.168.8.1", digits = true)
                }
                val ok = FixedAddress.valid(address.value) && (gateway.value.isBlank() || FixedAddress.valid(gateway.value))
                if (!ok) Note("An address is four numbers, 0–255, with dots between: 192.168.8.50.")
                ButtonRow(
                    "Use this address" to {
                        if (ok) {
                            network.setAddress(
                                adapter.id,
                                FixedAddress(address.value.trim(), adapter.address.substringAfter('/', "24").toIntOrNull() ?: 24,
                                    gateway.value.trim().ifBlank { null }, adapter.dns),
                            )
                            editing.value = false
                        }
                    },
                    "Cancel" to { editing.value = false },
                )
            }
        }
    }
}

