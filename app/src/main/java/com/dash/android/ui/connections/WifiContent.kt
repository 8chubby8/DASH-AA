package com.dash.android.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.connections.AdapterJob
import com.dash.android.connections.AdapterKind
import com.dash.android.connections.Band
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.connections.DashNetwork
import com.dash.android.connections.NetworkAdapter
import com.dash.android.connections.NetworkState
import com.dash.android.connections.NetworkSystem
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.keyboard.DashKeyboard
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader

/**
 * Connections › Wi-Fi (DASH-AA 1.1.6): what each Wi-Fi adapter is for, the network it is on, the networks
 * nearby and saved, and the DASH network — the car's own Wi-Fi.
 *
 * **Every car its own way** (Roger, 2026-10-08): an adapter joins networks, hosts the DASH network, or is
 * off. One adapter does one job; two adapters can do two. Nothing here assumes there is internet, modules
 * on Wi-Fi, or wireless Android Auto.
 */
@Composable
fun WifiContent() {
    val (network, state) = rememberNetwork()
    val (settings, update) = rememberConnectionsSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Wi-Fi")
        if (!state.available) {
            Note(state.note)
            // The fallback, where there is a desktop to fall back on.
            DashButton("Open the desktop's Wi-Fi settings", onClick = { com.dash.android.system.DesktopSettings.open("wifi") }, modifier = Modifier.width(controlWidth(LocalDensity.current.fontScale)))
            return@Column
        }
        val wifi = state.adapters.filter { it.kind == AdapterKind.WIFI }
        if (wifi.isEmpty()) { Note("This machine has no Wi-Fi adapter. A USB Wi-Fi adapter appears here when it is plugged in."); return@Column }
        Failure(state.failure)

        SettingBlock(
            name = "Wi-Fi",
            help = if (state.wifiOn) null else "Every Wi-Fi adapter is off, and so is the DASH network.",
            control = { PresetSegment(listOf("Off", "On"), if (state.wifiOn) 1 else 0, controlWidth) { network.setWifiOn(it == 1) } },
        )
        if (!state.wifiOn) return@Column

        // ---- Each adapter's job ----
        SettingsSectionHeader(if (wifi.size > 1) "Adapters" else "This adapter")
        wifi.forEach { a -> JobBlock(a, wifi, settings, update) }

        // ---- Joining ----
        val joiners = wifi.filter { it.job == AdapterJob.JOIN }
        if (joiners.isNotEmpty()) {
            joiners.filter { it.connected }.forEach { a ->
                SettingsSectionHeader(if (joiners.size > 1) "Connected — ${a.id}" else "Connected")
                AddressCard(network, state, a)
                DashButton("Disconnect", onClick = { network.disconnect(a.id) }, modifier = controlWidth)
            }
            Nearby(network, state)
            Saved(network, state)
        }

        // ---- The DASH network ----
        // Its settings appear only once an adapter has the job (Roger, 2026-10-08) — until then there is no
        // DASH network to set up. Choosing the job makes a password, so it starts ready to use.
        if (wifi.any { it.job == AdapterJob.HOST }) DashNetworkSection(network, state, settings, update)
    }
}

@Composable
private fun JobBlock(
    a: NetworkAdapter,
    wifi: List<NetworkAdapter>,
    settings: ConnectionsSettings,
    update: ((ConnectionsSettings) -> ConnectionsSettings) -> Unit,
) {
    val jobs = AdapterJob.entries.filter { it != AdapterJob.HOST || a.canHost }
    val help = when (a.job) {
        AdapterJob.JOIN -> when {
            a.connected -> "On ${a.network}."
            else -> a.status + "."
        } + if (wifi.size == 1 && a.canHost) " It can host the DASH network instead — one job at a time; a second Wi-Fi adapter does both." else ""
        AdapterJob.HOST -> (if (a.connected) "Hosting the DASH network." else "Starting the DASH network…") +
            if (wifi.size == 1) " While it hosts, it joins no other network." else ""
        AdapterJob.OFF -> "Off: it joins nothing and hosts nothing."
    }
    SettingBlock(
        name = a.name,
        help = help,
        fullWidthControl = true,
        control = {
            PresetSegment(jobs.map { it.label }, jobs.indexOf(a.job).coerceAtLeast(0), Modifier.fillMaxWidth()) { i ->
                val job = jobs[i]
                update { s ->
                    // One adapter hosts at a time: giving the job to this one hands the other back to joining.
                    val others = if (job == AdapterJob.HOST) s.jobs.filterValues { it != AdapterJob.HOST } else s.jobs
                    val jobs2 = if (job == AdapterJob.JOIN) others - a.identity else others + (a.identity to job)
                    val net = if (job == AdapterJob.HOST && s.network.password.length < DashNetwork.PASSWORD_MIN)
                        s.network.copy(password = DashNetwork.newPassword()) else s.network
                    s.copy(jobs = jobs2, network = net)
                }
            }
        },
    )
}

@Composable
private fun Nearby(network: NetworkSystem, state: NetworkState) {
    LaunchedEffect(Unit) { network.scan() }
    SettingsSectionHeader("Networks nearby")
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    DashButton(if (state.scanning) "Looking…" else "Look again", onClick = { if (!state.scanning) network.scan() }, modifier = controlWidth)
    state.joining?.let { Note("Joining $it…") }
    val rows = state.nearby.map { n ->
        val detail = listOfNotNull(
            if (n.inUse) "Connected" else if (n.known) "Saved" else null,
            signalWords(n.signal),
            n.band?.label,
            if (n.secured) null else "No password",
        ).joinToString(" · ")
        when {
            n.inUse -> ListRow(n.ssid, n.ssid, detail, lit = true)
            n.known -> ListRow(n.ssid, n.ssid, detail, onTap = { state.known.firstOrNull { it.ssid == n.ssid }?.let { network.joinKnown(it.id) } })
            !n.secured -> ListRow(n.ssid, n.ssid, detail, onTap = { network.join(n.ssid, null) })
            else -> ListRow(n.ssid, n.ssid, detail, open = { PasswordJoin(network, n.ssid) })
        }
    } + ListRow("\u0000other", "Another network…", "One that does not show its name", open = { HiddenJoin(network) })
    RowList(rows)
}

@Composable
private fun PasswordJoin(network: NetworkSystem, ssid: String) {
    val password = remember(ssid) { mutableStateOf("") }
    val join = {
        if (password.value.isNotEmpty()) { network.join(ssid, password.value); DashKeyboard.close() }
    }
    KeyboardField(password, "Password for $ssid", Modifier.fillMaxWidth(), placeholder = "Password", secret = true, onDone = join)
    ButtonRow("Join" to join)
}

@Composable
private fun HiddenJoin(network: NetworkSystem) {
    val name = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    KeyboardField(name, "Network name", Modifier.fillMaxWidth(), placeholder = "Network name")
    KeyboardField(password, "Password", Modifier.fillMaxWidth(), placeholder = "Password (empty if none)", secret = true)
    ButtonRow("Join" to {
        if (name.value.isNotBlank()) { network.join(name.value.trim(), password.value.ifEmpty { null }, hidden = true); DashKeyboard.close() }
    })
}

@Composable
private fun Saved(network: NetworkSystem, state: NetworkState) {
    if (state.known.isEmpty()) return
    SettingsSectionHeader("Saved networks")
    Note("Joined in this order when more than one is in range.")
    val ids = state.known.map { it.id }
    RowList(state.known.mapIndexed { i, k ->
        ListRow(
            key = k.id,
            label = "${i + 1}.  ${k.ssid}",
            detail = listOfNotNull(
                if (k.inUse) "Connected" else null,
                if (k.autoJoin) "Joins by itself" else "Only when chosen",
                if (k.metered) "Metered" else null,
            ).joinToString(" · "),
            open = {
                val buttons = mutableListOf<Pair<String, () -> Unit>>()
                if (!k.inUse) buttons += "Join now" to { network.joinKnown(k.id) }
                if (i > 0) buttons += "Move up" to { network.reorder(ids.toMutableList().apply { removeAt(i); add(i - 1, k.id) }) }
                if (i < ids.size - 1) buttons += "Move down" to { network.reorder(ids.toMutableList().apply { removeAt(i); add(i + 1, k.id) }) }
                ButtonRow(*buttons.toTypedArray())
                SettingBlock(
                    name = "Joins by itself",
                    control = { PresetSegment(listOf("No", "Yes"), if (k.autoJoin) 1 else 0, Modifier.width(controlWidth(LocalDensity.current.fontScale))) { network.setAutoJoin(k.id, it == 1) } },
                )
                SettingBlock(
                    name = "Metered",
                    help = "For a phone or a SIM router: the machine goes easy on its data.",
                    control = { PresetSegment(listOf("No", "Yes"), if (k.metered) 1 else 0, Modifier.width(controlWidth(LocalDensity.current.fontScale))) { network.setMetered(k.id, it == 1) } },
                )
                ButtonRow("Forget" to { network.forget(k.id) })
            },
        )
    })
}

@Composable
private fun DashNetworkSection(
    network: NetworkSystem,
    state: NetworkState,
    settings: ConnectionsSettings,
    update: ((ConnectionsSettings) -> ConnectionsSettings) -> Unit,
) {
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val n = settings.network
    val host = state.adapters.firstOrNull { it.kind == AdapterKind.WIFI && it.job == AdapterJob.HOST } ?: return
    SettingsSectionHeader("DASH network")
    Note("The car's own Wi-Fi, on ${host.id}: modules join it to reach DASH, and later a phone for wireless Android Auto.")
    run {
        val ip = host.address?.substringBefore('/')
        InfoRows(listOfNotNull(
            "Name" to n.name,
            ip?.let { "Modules connect to" to "$it : $MODULE_PORT" },
            "Band" to n.band.label,
        ))
    }
    val name = remember(n.name) { mutableStateOf(n.name) }
    SettingBlock(
        name = "Name",
        help = "What modules and phones see it as.",
        control = {
            Column(controlWidth, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardField(name, "DASH network name", Modifier.fillMaxWidth(), onDone = {
                    val v = name.value.trim()
                    if (v.isNotEmpty() && v.length <= 32) update { it.copy(network = it.network.copy(name = v)) }
                })
                if (name.value.trim() != n.name) DashButton("Save name", onClick = {
                    val v = name.value.trim()
                    if (v.isNotEmpty() && v.length <= 32) { update { it.copy(network = it.network.copy(name = v)) }; DashKeyboard.close() }
                })
            }
        },
    )
    val password = remember(n.password) { mutableStateOf(n.password) }
    val pwOk = password.value.length in DashNetwork.PASSWORD_MIN..DashNetwork.PASSWORD_MAX
    SettingBlock(
        name = "Password",
        help = if (pwOk || password.value.isEmpty()) "Type it into each module's firmware along with the name." else "At least ${DashNetwork.PASSWORD_MIN} characters.",
        control = {
            Column(controlWidth, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardField(password, "DASH network password", Modifier.fillMaxWidth(), secret = true, onDone = {
                    if (pwOk) update { it.copy(network = it.network.copy(password = password.value)) }
                })
                if (password.value != n.password && pwOk) DashButton("Save password", onClick = {
                    update { it.copy(network = it.network.copy(password = password.value)) }; DashKeyboard.close()
                })
                DashButton("Make a new one", onClick = { update { it.copy(network = it.network.copy(password = DashNetwork.newPassword())) } })
            }
        },
    )
    val bands = Band.entries.filter { b -> b in host.bands }
    SettingBlock(
        name = "Band",
        help = "2.4 GHz reaches every module (ESP32 boards hear nothing else). 5 GHz is quicker, for wireless Android Auto.",
        control = { PresetSegment(bands.map { it.label }, bands.indexOf(n.band).coerceAtLeast(0), controlWidth) { i -> update { it.copy(network = it.network.copy(band = bands[i])) } } },
    )
}

