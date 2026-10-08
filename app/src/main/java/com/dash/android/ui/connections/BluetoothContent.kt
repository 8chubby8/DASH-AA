package com.dash.android.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.connections.BluetoothDevice
import com.dash.android.connections.BluetoothSystem
import com.dash.android.connections.DeviceKind
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.keyboard.DashKeyboard
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader

/**
 * Connections › Bluetooth (DASH-AA 1.1.6): the radio, the name phones see, pairing, and every paired
 * device by what it is to DASH — phones, modules, sound, controllers. Pairing questions ("do these codes
 * match?") are asked over everything by [PairingPrompt], since a phone may start pairing at any time.
 *
 * A phone's controls include **internet from this phone** — its *Bluetooth tethering*, the backup when
 * there is no router — and modules point to the transport that reaches them.
 */
@Composable
fun BluetoothContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val bt = app.bluetooth
    val state by bt.state.collectAsState()
    val net by app.network.state.collectAsState()
    val (settings, update) = rememberConnectionsSettings()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    // Searching is for while the tab is open.
    DisposableEffect(Unit) { onDispose { if (bt.state.value.searching) bt.search(false) } }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Bluetooth")
        if (!state.available) {
            Note(state.note)
            // The fallback, where there is a desktop to fall back on.
            DashButton("Open the desktop's Bluetooth settings", onClick = { com.dash.android.system.DesktopSettings.open("bluetooth") }, modifier = Modifier.width(controlWidth(LocalDensity.current.fontScale)))
            return@Column
        }
        Failure(state.failure)
        SettingBlock(
            name = "Bluetooth",
            control = { PresetSegment(listOf("Off", "On"), if (state.powered) 1 else 0, controlWidth) { bt.setPowered(it == 1) } },
        )
        if (!state.powered) return@Column

        val name = remember(state.name) { mutableStateOf(state.name) }
        SettingBlock(
            name = "Name",
            help = "What phones and modules see this machine as.",
            control = {
                Column(controlWidth, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardField(name, "Bluetooth name", Modifier.fillMaxWidth(), onDone = { bt.setName(name.value) })
                    if (name.value.trim() != state.name && name.value.isNotBlank()) DashButton("Save name", onClick = { bt.setName(name.value); DashKeyboard.close() })
                }
            },
        )
        SettingBlock(
            name = "Visible",
            help = "So a phone can find this machine from its own Bluetooth settings and pair from there. " +
                "Hidden again after ${BluetoothSystem.VISIBLE_SECONDS / 60} minutes.",
            control = { PresetSegment(listOf("Hidden", "Visible"), if (state.visible) 1 else 0, controlWidth) { bt.setVisible(it == 1) } },
        )
        SettingBlock(
            name = "Music from phones",
            help = "Refused, phones send their music through Android Auto only. Bluetooth music beside Android Auto's " +
                "crashes the sound system, so it is refused unless you choose otherwise. Calls are not affected. " +
                "Changing this pauses the sound for a second.",
            control = {
                PresetSegment(listOf("Refused", "Allowed"), if (settings.noBluetoothMusic) 0 else 1, controlWidth) { i ->
                    update { it.copy(noBluetoothMusic = i == 0) }
                }
            },
        )

        state.pairing?.let { addr -> Note("Pairing with ${state.devices.firstOrNull { it.address == addr }?.name ?: addr}…") }

        val paired = state.devices.filter { it.paired }
        DeviceKind.entries.forEach { kind ->
            val these = paired.filter { it.kind == kind }
            if (these.isEmpty()) return@forEach
            SettingsSectionHeader(kind.label)
            RowList(these.map { d -> pairedRow(d, bt, net.phoneInternet[d.address], app) })
        }

        SettingsSectionHeader("Pair a new device")
        Note("Search, then tap a device to pair. A phone asks you to check a code on both screens; most modules ask for a PIN (often 1234 or 0000).")
        DashButton(if (state.searching) "Stop searching" else "Search", onClick = { bt.search(!state.searching) }, modifier = controlWidth)
        val nearby = state.devices.filter { !it.paired }.sortedByDescending { it.rssi ?: -200 }
        if (state.searching && nearby.isEmpty()) Note("Searching…")
        RowList(nearby.map { d ->
            ListRow(
                key = d.address,
                label = d.name,
                detail = listOfNotNull(d.kind.label.removeSuffix("s"), d.rssi?.let { if (it > -60) "Close by" else if (it > -80) "Nearby" else "Far" }).joinToString(" · "),
                onTap = { bt.pair(d.address) },
            )
        })
    }
}

private fun pairedRow(d: BluetoothDevice, bt: BluetoothSystem, internet: Boolean?, app: DashApplication) = ListRow(
    key = d.address,
    label = d.name,
    detail = listOfNotNull(
        if (d.connected) "Connected" else "Not connected",
        if (internet == true) "Giving internet" else null,
    ).joinToString(" · "),
    open = {
        when (d.kind) {
            DeviceKind.MODULE -> Note("DASH reaches modules itself, through its Bluetooth transport (Modules › Transport Manager).")
            else -> ButtonRow(if (d.connected) "Disconnect" to { bt.disconnect(d.address) } else "Connect" to { bt.connect(d.address) })
        }
        if (d.kind == DeviceKind.PHONE) {
            SettingBlock(
                name = "Internet from this phone",
                help = "For when there is no router: the phone shares its mobile data over Bluetooth. Switch on " +
                    "Bluetooth tethering in the phone's Hotspot settings first.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (internet != null) 1 else 0, Modifier.width(controlWidth(LocalDensity.current.fontScale))) { i ->
                        app.network.setPhoneInternet(d.address, d.name, i == 1)
                    }
                },
            )
        }
        ButtonRow("Forget" to { bt.forget(d.address) })
    },
)
