package com.dash.android.connections

import kotlinx.coroutines.flow.MutableStateFlow

/** Pretend networks and Bluetooth for the drawings and the tests — two Wi-Fi adapters, a cable, a phone and a module. */
class PretendNetwork : NetworkSystem {
    override val state = MutableStateFlow(
        NetworkState(
            available = true, note = "", wifiOn = true, internet = Internet.FULL,
            adapters = listOf(
                NetworkAdapter("enp5s0u1", "00:E0:4C:68:01:02", AdapterKind.ETHERNET, "Realtek RTL8153 (Ethernet, enp5s0u1)", true, "Connected",
                    network = "Wired connection 1", address = "192.168.8.50/24", gateway = "192.168.8.1", dns = "192.168.8.1", fixed = true, speed = 1000),
                NetworkAdapter("wlan0", "84:9E:56:E4:7C:95", AdapterKind.WIFI, "MEDIATEK MT7925 802.11be (Wi-Fi, wlan0)", true, "Connected",
                    network = "Rogers-AP-2.4", address = "192.168.8.109/24", gateway = "192.168.8.1", canHost = true, bands = setOf(Band.GHZ_2_4, Band.GHZ_5)),
                NetworkAdapter("wlan1", "00:C0:CA:11:22:33", AdapterKind.WIFI, "ALFA AWUS036ACM (Wi-Fi, wlan1)", true, "Connected",
                    network = "DASH", address = "10.42.0.1/24", canHost = true, bands = setOf(Band.GHZ_2_4, Band.GHZ_5), job = AdapterJob.HOST),
            ),
            nearby = listOf(
                WifiNetwork("Rogers-AP-2.4", 88, true, Band.GHZ_2_4, inUse = true, known = true),
                WifiNetwork("Rogers Phone", 61, true, Band.GHZ_5, inUse = false, known = true),
                WifiNetwork("BT-7XK2", 40, true, Band.GHZ_2_4, inUse = false, known = false),
                WifiNetwork("Costa Free", 22, false, Band.GHZ_2_4, inUse = false, known = false),
            ),
            known = listOf(
                KnownNetwork("1", "Rogers-AP-2.4", true, true, 30, true),
                KnownNetwork("2", "Rogers Home", true, false, 20, false),
                KnownNetwork("3", "Rogers Phone", false, true, 10, false),
            ),
            phoneInternet = mapOf("94:45:60:56:B3:CA" to false),
        )
    )
    override fun start() {}
    override fun apply(settings: ConnectionsSettings) {}
    override fun setWifiOn(on: Boolean) {}
    override fun scan() {}
    override fun join(ssid: String, password: String?, hidden: Boolean) {}
    override fun joinKnown(id: String) {}
    override fun disconnect(adapter: String) {}
    override fun forget(id: String) {}
    override fun reorder(ids: List<String>) {}
    override fun setAutoJoin(id: String, on: Boolean) {}
    override fun setMetered(id: String, on: Boolean) {}
    override fun setAddress(adapter: String, fixed: FixedAddress?) {}
    override fun setPhoneInternet(address: String, name: String, on: Boolean) {}
}

class PretendBluetooth(request: PairingRequest? = null) : BluetoothSystem {
    override val state = MutableStateFlow(
        BluetoothState(
            available = true, note = "", powered = true, name = "X-Type", address = "84:9E:56:E4:7C:96", searching = true,
            devices = listOf(
                BluetoothDevice("94:45:60:56:B3:CA", "Rogers Phone", DeviceKind.PHONE, paired = true, connected = true),
                BluetoothDevice("94:54:C5:74:AA:7A", "D.A.S.H-Climate", DeviceKind.MODULE, paired = true, connected = false),
                BluetoothDevice("AC:36:1B:C4:FA:A2", "DualSense Wireless Controller", DeviceKind.INPUT, paired = true, connected = false),
                BluetoothDevice("98:D3:31:F5:12:34", "D.A.S.H-Wheel", DeviceKind.MODULE, paired = false, connected = false, rssi = -58),
                BluetoothDevice("F0:99:B6:01:02:03", "Pixel 7", DeviceKind.PHONE, paired = false, connected = false, rssi = -77),
            ),
        )
    )
    override val request = MutableStateFlow(request)
    override fun start() {}
    override fun setPowered(on: Boolean) {}
    override fun setName(name: String) {}
    override fun setVisible(on: Boolean) {}
    override fun search(on: Boolean) {}
    override fun pair(address: String) {}
    override fun connect(address: String) {}
    override fun disconnect(address: String) {}
    override fun forget(address: String) {}
    override fun answer(request: PairingRequest, accept: Boolean, typed: String?) {}
}
