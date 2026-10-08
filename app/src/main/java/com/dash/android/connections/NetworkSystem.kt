package com.dash.android.connections

import kotlinx.coroutines.flow.StateFlow

/**
 * The machine's networks, as DASH's Connections › Wi-Fi and Ethernet tabs see them (DASH-AA 1.1.6):
 * each network adapter and what it is for, the Wi-Fi networks nearby and known, the cable, the DASH
 * network, and a phone's internet over Bluetooth.
 *
 * **Every car is set up its own way** (Roger, 2026-10-08): internet from a router on a cable, from a
 * phone, or none; modules on Wi-Fi or not; wireless Android Auto or not; one Wi-Fi adapter or two. So
 * DASH decides none of it. Each Wi-Fi adapter is given a job by the person ([AdapterJob]) — join networks,
 * host the DASH network, or nothing — and the rest follows from that.
 *
 * **The seam between DASH and the platform**, as [com.dash.android.display.DisplaySystem] is for the
 * screens. DASH-AA's implementation is NetworkManager, as the seat user (`connections/linux/`).
 * **For native:** Android owns its Wi-Fi; native's implementation reports what `ConnectivityManager`
 * says, opens Android's own Wi-Fi panel to join, and reports no adapters it can give jobs to and no DASH
 * network (Android's hotspot is the system's), so the tabs leave those out.
 *
 * DASH's own choices — the jobs and the DASH network — are [ConnectionsSettings], kept by DASH and handed to
 * [apply] whenever they change, so they hold from the moment DASH starts. Everything else is the
 * network service's own, changed straight away.
 *
 * Every call returns at once; the work happens off the caller's thread, and [state] shows the result.
 */
interface NetworkSystem {
    val state: StateFlow<NetworkState>

    fun start()

    /** Carry out DASH's choices: each Wi-Fi adapter's job, and the DASH network. */
    fun apply(settings: ConnectionsSettings)

    /** Wi-Fi as a whole, on or off (the radio). */
    fun setWifiOn(on: Boolean)

    /** Look for Wi-Fi networks again. */
    fun scan()

    /** Join a Wi-Fi network, with its password if it has one; [hidden] for one that does not announce its name. */
    fun join(ssid: String, password: String?, hidden: Boolean = false)

    /** Join a known network now. */
    fun joinKnown(id: String)

    /** Leave whatever network [adapter] is on (it may join another known one later). */
    fun disconnect(adapter: String)

    /** Forget a known network: its password goes, and it is never joined again by itself. */
    fun forget(id: String)

    /** The known networks in the order DASH should prefer them, first most wanted. */
    fun reorder(ids: List<String>)

    fun setAutoJoin(id: String, on: Boolean)

    /** A network that costs by the gigabyte (a phone, a SIM router): the machine goes easy on it. */
    fun setMetered(id: String, on: Boolean)

    /** Give [adapter]'s current network a fixed address, or with null let the network choose. */
    fun setAddress(adapter: String, fixed: FixedAddress?)

    /** Internet from a paired phone over Bluetooth (the phone's *Bluetooth tethering*), or not. */
    fun setPhoneInternet(address: String, name: String, on: Boolean)
}

data class NetworkState(
    val available: Boolean,
    val note: String,
    val wifiOn: Boolean = false,
    val adapters: List<NetworkAdapter> = emptyList(),
    val nearby: List<WifiNetwork> = emptyList(),
    val known: List<KnownNetwork> = emptyList(),
    /** Whether the machine reaches the internet, by the network service's own check. */
    val internet: Internet = Internet.UNKNOWN,
    /** Phones (by Bluetooth address) set up to give their internet over Bluetooth, and whether they are now. */
    val phoneInternet: Map<String, Boolean> = emptyMap(),
    /** The network being joined now, by name. */
    val joining: String? = null,
    val scanning: Boolean = false,
    /** The last thing that did not happen, in plain words; null when the last one did. */
    val failure: String? = null,
)

enum class Internet(val label: String) {
    FULL("Connected to the internet"),
    LIMITED("On a network, but not reaching the internet"),
    NONE("No internet"),
    UNKNOWN("Not known"),
}

enum class AdapterKind { WIFI, ETHERNET }

data class NetworkAdapter(
    /** The network service's name for it: `wlan0`, `enp5s0`. */
    val id: String,
    /** Who it is, unchanged by which socket it is in — its hardware address. DASH remembers jobs by it. */
    val identity: String,
    val kind: AdapterKind,
    /** What the person calls it: the maker and model. */
    val name: String,
    val connected: Boolean,
    /** In plain words: "Connected", "Connecting…", "Cable unplugged", "Off". */
    val status: String,
    /** The network it is on — a Wi-Fi network's name, or the cable's connection. */
    val network: String? = null,
    /** The address it has there, with its prefix: `192.168.8.109/24`. */
    val address: String? = null,
    val gateway: String? = null,
    val dns: String? = null,
    /** The address is fixed by DASH rather than given by the network. */
    val fixed: Boolean = false,
    /** Link speed, in Mb/s. */
    val speed: Int? = null,
    /** This adapter can host a network of its own — the DASH network. */
    val canHost: Boolean = false,
    val bands: Set<Band> = emptySet(),
    val job: AdapterJob = AdapterJob.JOIN,
)

enum class AdapterJob(val label: String) {
    JOIN("Join networks"),
    HOST("DASH network"),
    OFF("Off"),
}

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
}

data class WifiNetwork(
    val ssid: String,
    /** 0–100. */
    val signal: Int,
    val secured: Boolean,
    val band: Band?,
    val inUse: Boolean,
    val known: Boolean,
)

data class KnownNetwork(
    val id: String,
    val ssid: String,
    val autoJoin: Boolean,
    val metered: Boolean,
    val priority: Int,
    val inUse: Boolean,
)

data class FixedAddress(val address: String, val prefix: Int, val gateway: String?, val dns: String?) {
    companion object {
        private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
        fun valid(ip: String) = IPV4.matches(ip.trim())
    }
}
