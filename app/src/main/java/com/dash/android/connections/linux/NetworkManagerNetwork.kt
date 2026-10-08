package com.dash.android.connections.linux

import android.util.Log
import com.dash.android.connections.AdapterJob
import com.dash.android.connections.AdapterKind
import com.dash.android.connections.Band
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.connections.DashNetwork
import com.dash.android.connections.FixedAddress
import com.dash.android.connections.Internet
import com.dash.android.connections.KnownNetwork
import com.dash.android.connections.NetworkAdapter
import com.dash.android.connections.NetworkState
import com.dash.android.connections.NetworkSystem
import com.dash.android.connections.WifiNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DASH-AA's networks (1.1.6): NetworkManager, through its own command-line tool `nmcli`, as the seat
 * user. NetworkManager's policy lets the person at the machine join and forget networks, host one and
 * change addresses with no password and no root — the same requests GNOME's and KDE's network settings
 * make. With no desktop, this is the machine's network settings.
 *
 * **Watched, not polled:** `nmcli monitor` runs for the life of DASH and every change it reports (a cable
 * in, a network dropped, the internet found) rebuilds [state].
 *
 * **Jobs** ([apply]): an adapter told to host carries the *DASH network* — one NetworkManager connection
 * named [DASH_NETWORK], an access point with WPA2 (the security every module's radio speaks) whose address
 * is shared, so devices on it get an address from DASH and DASH's internet if it has any. An adapter told
 * Off is disconnected and stops joining by itself. One adapter can do one job: NetworkManager cannot
 * join a network and host one on the same adapter, so two jobs at once need two adapters.
 */
class NetworkManagerNetwork : NetworkSystem {
    private val _state = MutableStateFlow(NetworkState(available = false, note = "Looking for the network service…"))
    override val state: StateFlow<NetworkState> = _state

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dash-network").apply { isDaemon = true } }
    private var refreshQueued: ScheduledFuture<*>? = null
    @Volatile private var settings: ConnectionsSettings? = null
    /** What [reconcile] last carried out, so a refresh only acts when something has changed. */
    private var applied: Pair<ConnectionsSettings, Set<String>>? = null

    override fun start() {
        worker.execute {
            val running = nmcli("-t", "-f", "RUNNING", "general")
            if (running == null) {
                _state.value = NetworkState(false, "NetworkManager is not on this machine, so DASH cannot set up its networks.")
                return@execute
            }
            if (running.code != 0 || !running.out.contains("running")) {
                _state.value = NetworkState(false, "NetworkManager is not running, so DASH cannot set up its networks.")
                return@execute
            }
            refresh()
            watch()
        }
    }

    /** `nmcli monitor`: one line per change. Started again if NetworkManager restarts under it. */
    private fun watch() {
        Thread({
            while (true) {
                runCatching {
                    val p = ProcessBuilder("nmcli", "monitor").redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().forEachLine { queueRefresh() }
                    p.waitFor()
                }
                Thread.sleep(5_000)
                queueRefresh()
            }
        }, "dash-network-monitor").apply { isDaemon = true }.start()
    }

    private fun queueRefresh(delayMs: Long = 300) {
        refreshQueued?.cancel(false)
        refreshQueued = worker.schedule({ refresh() }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun apply(settings: ConnectionsSettings) {
        this.settings = settings
        worker.execute { reconcile() }
    }

    // ---- Reading ----

    private fun refresh() {
        runCatching { rebuild() }.onFailure { Log.w(TAG, "could not read the networks: ${it.message}") }
        reconcile()
    }

    private fun rebuild() {
        val general = nmcli("-t", "-f", "CONNECTIVITY,WIFI", "general")?.out?.trim()?.split(':') ?: return
        val internet = when (general.getOrNull(0)) {
            "full" -> Internet.FULL
            "limited", "portal" -> Internet.LIMITED
            "none" -> Internet.NONE
            else -> Internet.UNKNOWN
        }
        val wifiOn = general.getOrNull(1) == "enabled"

        // Every connection the machine knows, with the fields DASH needs, in two calls.
        val list = nmcli("-t", "-f", "UUID,TYPE,ACTIVE", "connection", "show")?.out.orEmpty().lines()
            .map { splitTerse(it) }.filter { it.size >= 3 }
        val ids = list.filter { it[1] == "802-11-wireless" || it[1] == "bluetooth" || it[1] == "802-3-ethernet" }.map { it[0] }
        val details = connectionDetails(ids)
        val active = list.filter { it[2] == "yes" }.map { it[0] }.toSet()

        val known = list.filter { it[1] == "802-11-wireless" }.mapNotNull { row ->
            val d = details[row[0]] ?: return@mapNotNull null
            if (d.mode == "ap") return@mapNotNull null          // a network this machine hosts, not one it joins
            KnownNetwork(row[0], d.ssid.ifBlank { d.name }, d.autoconnect, d.metered, d.priority, row[0] in active)
        }.sortedWith(compareByDescending<KnownNetwork> { it.priority }.thenBy { it.ssid.lowercase() })

        val phoneInternet = list.filter { it[1] == "bluetooth" }.mapNotNull { row ->
            val d = details[row[0]] ?: return@mapNotNull null
            if (d.btType != "panu" || d.bdaddr.isBlank()) return@mapNotNull null
            d.bdaddr.uppercase() to (row[0] in active)
        }.toMap()

        val s = settings
        val adapters = parseDevices(nmcli("-t", "-f", DEVICE_FIELDS, "device", "show")?.out.orEmpty()).mapNotNull { d ->
            val type = d["GENERAL.TYPE"]
            val kind = when (type) { "wifi" -> AdapterKind.WIFI; "ethernet" -> AdapterKind.ETHERNET; else -> return@mapNotNull null }
            val id = d["GENERAL.DEVICE"] ?: return@mapNotNull null
            val stateCode = d["GENERAL.STATE"]?.substringBefore(' ')?.toIntOrNull() ?: 0
            if (stateCode == 10) return@mapNotNull null         // unmanaged: not NetworkManager's, so not DASH's either
            val identity = (d["GENERAL.HWADDR"] ?: id).uppercase()
            val conUuid = d["GENERAL.CON-UUID"]?.takeIf { it.isNotBlank() }
            val con = conUuid?.let { details[it] }
            NetworkAdapter(
                id = id,
                identity = identity,
                kind = kind,
                name = productName(d["GENERAL.VENDOR"], d["GENERAL.PRODUCT"], kind, id),
                connected = stateCode == 100,
                status = statusOf(stateCode, kind, wifiOn, s?.jobOf(identity)),
                network = con?.let { if (kind == AdapterKind.WIFI) it.ssid.ifBlank { it.name } else it.name } ?: d["GENERAL.CONNECTION"]?.takeIf { it.isNotBlank() },
                address = d["IP4.ADDRESS[1]"]?.takeIf { it.isNotBlank() },
                gateway = d["IP4.GATEWAY"]?.takeIf { it.isNotBlank() },
                dns = d["IP4.DNS[1]"]?.takeIf { it.isNotBlank() },
                fixed = con?.ipv4 == "manual",
                speed = d["CAPABILITIES.SPEED"]?.substringBefore(' ')?.toIntOrNull(),
                canHost = d["WIFI-PROPERTIES.AP"] == "yes",
                bands = buildSet {
                    if (d["WIFI-PROPERTIES.2GHZ"] == "yes") add(Band.GHZ_2_4)
                    if (d["WIFI-PROPERTIES.5GHZ"] == "yes") add(Band.GHZ_5)
                },
                job = if (kind == AdapterKind.WIFI) (s?.jobOf(identity) ?: AdapterJob.JOIN) else AdapterJob.JOIN,
            )
        }.sortedWith(compareBy<NetworkAdapter> { it.kind }.thenBy { it.id })

        val knownSsids = known.map { it.ssid }.toSet()
        val nearby = if (!wifiOn) emptyList() else parseNearby(
            nmcli("-t", "-f", "IN-USE,SSID,SIGNAL,SECURITY,FREQ,MODE", "device", "wifi", "list", "--rescan", "no")?.out.orEmpty(),
            knownSsids,
            hosted = s?.network?.name,
        )

        _state.value = _state.value.copy(
            available = true,
            note = "",
            wifiOn = wifiOn,
            adapters = adapters,
            nearby = nearby,
            known = known,
            internet = internet,
            phoneInternet = phoneInternet,
        )
    }

    private data class Connection(
        val name: String, val ssid: String, val mode: String, val metered: Boolean, val ifname: String,
        val ipv4: String, val priority: Int, val autoconnect: Boolean, val bdaddr: String, val btType: String,
    )

    /** Each connection's fields, by id, in one call: one `key:value` block per connection, blank-line apart. */
    private fun connectionDetails(ids: List<String>): Map<String, Connection> {
        if (ids.isEmpty()) return emptyMap()
        val args = mutableListOf("-t", "-f", CONNECTION_FIELDS, "connection", "show")
        ids.forEach { args += listOf("uuid", it) }
        val out = nmcli(*args.toTypedArray())?.out ?: return emptyMap()
        return parseDevices(out).mapNotNull { f ->
            val uuid = f["connection.uuid"] ?: return@mapNotNull null
            uuid to Connection(
                name = f["connection.id"].orEmpty(),
                ssid = f["802-11-wireless.ssid"].orEmpty(),
                mode = f["802-11-wireless.mode"].orEmpty(),
                metered = f["connection.metered"] == "yes",
                ifname = f["connection.interface-name"].orEmpty(),
                ipv4 = f["ipv4.method"].orEmpty(),
                priority = f["connection.autoconnect-priority"]?.toIntOrNull() ?: 0,
                autoconnect = f["connection.autoconnect"] == "yes",
                bdaddr = f["bluetooth.bdaddr"].orEmpty(),
                btType = f["bluetooth.type"].orEmpty(),
            )
        }.toMap()
    }

    // ---- Carrying out the jobs ----

    /** Make each Wi-Fi adapter do the job it has been given. Acts only on what changed since last time. */
    private fun reconcile() {
        val s = settings ?: return
        val st = _state.value
        if (!st.available) return
        val wifi = st.adapters.filter { it.kind == AdapterKind.WIFI }
        val key = s to wifi.map { it.identity }.toSet()
        if (applied == key) return
        val before = applied?.first
        applied = key

        val host = wifi.firstOrNull { s.jobOf(it.identity) == AdapterJob.HOST }
        if (host == null) {
            nmcli("connection", "modify", "id", DASH_NETWORK, "connection.autoconnect", "no")
            nmcli("connection", "down", "id", DASH_NETWORK)
        }
        wifi.forEach { a ->
            when (s.jobOf(a.identity)) {
                AdapterJob.OFF -> {
                    nmcli("device", "set", a.id, "autoconnect", "no")
                    if (a.connected || a.status.startsWith("Connecting")) nmcli("device", "disconnect", a.id)
                }
                AdapterJob.HOST -> {
                    val changed = before == null || before.network != s.network || before.jobOf(a.identity) != AdapterJob.HOST
                    if (changed || a.network != s.network.name) host(a, s.network)
                }
                AdapterJob.JOIN -> {
                    nmcli("device", "set", a.id, "autoconnect", "yes")
                    val wasOther = before != null && before.jobOf(a.identity) != AdapterJob.JOIN
                    if (wasOther && !a.connected) nmcli("--wait", "15", "device", "connect", a.id)
                }
            }
        }
        queueRefresh(1_000)
    }

    /** Put the DASH network on adapter [a]: made, or changed to match, then started. */
    private fun host(a: NetworkAdapter, n: DashNetwork) {
        if (n.password.length < DashNetwork.PASSWORD_MIN) return
        val band = if (n.band == Band.GHZ_5 && Band.GHZ_5 in a.bands) Band.GHZ_5 else Band.GHZ_2_4
        val props = listOf(
            "connection.interface-name", a.id,
            "connection.autoconnect", "yes",
            "connection.autoconnect-priority", "50",
            "802-11-wireless.ssid", n.name,
            "802-11-wireless.mode", "ap",
            "802-11-wireless.band", if (band == Band.GHZ_5) "a" else "bg",
            // Channel 36: allowed for an access point almost everywhere, with no radar check to wait on.
            "802-11-wireless.channel", if (band == Band.GHZ_5) "36" else "0",
            "ipv4.method", "shared",
            "ipv6.method", "disabled",
            "wifi-sec.key-mgmt", "wpa-psk",
            "wifi-sec.proto", "rsn",
            "wifi-sec.pairwise", "ccmp",
            "wifi-sec.group", "ccmp",
            "wifi-sec.psk", n.password,
        )
        val exists = nmcli("-t", "-g", "connection.uuid", "connection", "show", "id", DASH_NETWORK)?.let { it.code == 0 && it.out.isNotBlank() } == true
        val made = if (exists) nmcli("connection", "modify", "id", DASH_NETWORK, *props.toTypedArray())
            else nmcli("connection", "add", "type", "wifi", "con-name", DASH_NETWORK, *props.toTypedArray())
        if (made == null || made.code != 0) { fail("set up the DASH network", made); return }
        val up = nmcli("--wait", "30", "connection", "up", "id", DASH_NETWORK, "ifname", a.id)
        if (up == null || up.code != 0) fail("start the DASH network", up) else ok()
    }

    // ---- What the tabs ask for ----

    override fun setWifiOn(on: Boolean) = act("switch Wi-Fi ${if (on) "on" else "off"}") { nmcli("radio", "wifi", if (on) "on" else "off") }

    override fun scan() = worker.execute {
        _state.value = _state.value.copy(scanning = true)
        // --rescan yes waits for the scan to finish; a scan already running is not a failure.
        nmcli("-t", "-f", "SSID", "device", "wifi", "list", "--rescan", "yes")
        _state.value = _state.value.copy(scanning = false)
        refresh()
    }

    override fun join(ssid: String, password: String?, hidden: Boolean) = worker.execute {
        val st = _state.value
        val adapter = st.adapters.firstOrNull { it.kind == AdapterKind.WIFI && it.job == AdapterJob.JOIN }
        if (adapter == null) {
            _state.value = st.copy(failure = "No Wi-Fi adapter has the job of joining networks. Give one that job first.")
            return@execute
        }
        val wasKnown = st.known.any { it.ssid == ssid }
        _state.value = st.copy(joining = ssid, failure = null)
        // The password goes on nmcli's command line for the moment it runs. On a one-person machine that is
        // no more exposed than the saved connection itself, which NetworkManager keeps readable by root only.
        val args = mutableListOf("--wait", "45", "device", "wifi", "connect", ssid, "ifname", adapter.id)
        if (!password.isNullOrEmpty()) args += listOf("password", password)
        if (hidden) args += listOf("hidden", "yes")
        val r = nmcli(*args.toTypedArray())
        if (r == null || r.code != 0) {
            fail("join $ssid", r)
            // A first try with the wrong password leaves a saved network that would keep failing; it goes.
            if (!wasKnown && r?.out?.contains("Secrets were required") == true) nmcli("connection", "delete", "id", ssid)
        } else ok()
        _state.value = _state.value.copy(joining = null)
        refresh()
    }

    override fun joinKnown(id: String) = worker.execute {
        val ssid = _state.value.known.firstOrNull { it.id == id }?.ssid
        _state.value = _state.value.copy(joining = ssid, failure = null)
        val r = nmcli("--wait", "45", "connection", "up", "uuid", id)
        if (r == null || r.code != 0) fail("join ${ssid ?: "it"}", r) else ok()
        _state.value = _state.value.copy(joining = null)
        refresh()
    }

    override fun disconnect(adapter: String) = act("disconnect") { nmcli("device", "disconnect", adapter) }

    override fun forget(id: String) = act("forget it") { nmcli("connection", "delete", "uuid", id) }

    override fun reorder(ids: List<String>) = worker.execute {
        // Tens apart, highest first, so NetworkManager tries them in this order; 0 and below is left to it.
        ids.forEachIndexed { i, id -> nmcli("connection", "modify", "uuid", id, "connection.autoconnect-priority", ((ids.size - i) * 10).toString()) }
        refresh()
    }

    override fun setAutoJoin(id: String, on: Boolean) =
        act("change it") { nmcli("connection", "modify", "uuid", id, "connection.autoconnect", if (on) "yes" else "no") }

    override fun setMetered(id: String, on: Boolean) =
        act("change it") { nmcli("connection", "modify", "uuid", id, "connection.metered", if (on) "yes" else "no") }

    override fun setAddress(adapter: String, fixed: FixedAddress?) = worker.execute {
        val uuid = nmcli("-t", "-g", "GENERAL.CON-UUID", "device", "show", adapter)?.out?.trim()?.takeIf { it.isNotBlank() }
        if (uuid == null) {
            _state.value = _state.value.copy(failure = "Could not change the address: it is not on a network.")
            return@execute
        }
        val props = if (fixed == null) listOf("ipv4.method", "auto", "ipv4.addresses", "", "ipv4.gateway", "", "ipv4.dns", "")
        else listOf(
            "ipv4.method", "manual",
            "ipv4.addresses", "${fixed.address}/${fixed.prefix}",
            "ipv4.gateway", fixed.gateway.orEmpty(),
            "ipv4.dns", fixed.dns ?: fixed.gateway.orEmpty(),
        )
        val m = nmcli("connection", "modify", "uuid", uuid, *props.toTypedArray())
        if (m == null || m.code != 0) { fail("change the address", m); refresh(); return@execute }
        // Reapplied in place where it can be, so the network is not dropped; brought up again where not.
        val r = nmcli("device", "reapply", adapter)?.takeIf { it.code == 0 } ?: nmcli("--wait", "30", "connection", "up", "uuid", uuid)
        if (r == null || r.code != 0) fail("change the address", r) else ok()
        refresh()
    }

    override fun setPhoneInternet(address: String, name: String, on: Boolean) = worker.execute {
        val existing = nmcli("-t", "-f", "UUID,TYPE", "connection", "show")?.out.orEmpty().lines().map { splitTerse(it) }
            .filter { it.size >= 2 && it[1] == "bluetooth" }.map { it[0] }
            .filter { uuid -> connectionDetails(listOf(uuid))[uuid]?.let { it.bdaddr.equals(address, true) && it.btType == "panu" } == true }
        if (on) {
            val uuid = existing.firstOrNull()
            if (uuid == null) {
                val r = nmcli("connection", "add", "type", "bluetooth", "con-name", "$name internet", "bluetooth.bdaddr", address,
                    "bluetooth.type", "panu", "connection.autoconnect", "yes")
                if (r == null || r.code != 0) { fail("set up the phone's internet", r); refresh(); return@execute }
            }
            val up = nmcli("--wait", "30", "connection", "up", if (uuid != null) "uuid" else "id", uuid ?: "$name internet")
            if (up == null || up.code != 0) fail("use the phone's internet", up, hint = "Is Bluetooth tethering switched on in the phone's Hotspot settings?") else ok()
        } else {
            existing.forEach { nmcli("connection", "delete", "uuid", it) }
            ok()
        }
        refresh()
    }

    private fun act(what: String, block: () -> Result?) = worker.execute {
        val r = block()
        if (r == null || r.code != 0) fail(what, r) else ok()
        refresh()
    }

    private fun ok() { _state.value = _state.value.copy(failure = null) }

    private fun fail(what: String, r: Result?, hint: String? = null) {
        val message = plain(what, r?.out, hint)
        Log.w(TAG, "$what: ${r?.out?.trim()}")
        _state.value = _state.value.copy(failure = message)
    }

    // ---- nmcli ----

    data class Result(val code: Int, val out: String)

    /** Run nmcli; null when it is not installed or does not finish. Its output is never translated. */
    private fun nmcli(vararg args: String): Result? = try {
        val p = ProcessBuilder(listOf("nmcli") + args).redirectErrorStream(true)
            .apply { environment()["LC_ALL"] = "C" }.start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else Result(p.exitValue(), text)
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val TAG = "DashNetwork"

        /** The DASH network's NetworkManager connection. */
        const val DASH_NETWORK = "DASH network"

        private const val DEVICE_FIELDS = "GENERAL.DEVICE,GENERAL.TYPE,GENERAL.STATE,GENERAL.CONNECTION,GENERAL.CON-UUID," +
            "GENERAL.HWADDR,GENERAL.VENDOR,GENERAL.PRODUCT,IP4.ADDRESS,IP4.GATEWAY,IP4.DNS,CAPABILITIES.SPEED," +
            "WIFI-PROPERTIES.AP,WIFI-PROPERTIES.2GHZ,WIFI-PROPERTIES.5GHZ"

        private const val CONNECTION_FIELDS = "connection.uuid,connection.id,802-11-wireless.ssid,802-11-wireless.mode," +
            "connection.metered,connection.interface-name,ipv4.method,connection.autoconnect-priority,connection.autoconnect," +
            "bluetooth.bdaddr,bluetooth.type"

        /** nmcli's terse lines: fields split on `:`, with `\:` and `\\` inside a field kept. */
        internal fun splitTerse(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> { cur.append(line[i + 1]); i++ }
                    c == ':' -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }

        /** `device show`'s blocks — `KEY:value` lines, one block per device, blank-line apart. */
        internal fun parseDevices(text: String): List<Map<String, String>> =
            text.split("\n\n").map { block ->
                block.lines().filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
            }.filter { it.isNotEmpty() }

        /** Networks in range, one per name (the strongest), named ones only, without the one DASH hosts. */
        internal fun parseNearby(text: String, known: Set<String>, hosted: String?): List<WifiNetwork> =
            text.lines().map { splitTerse(it) }.filter { it.size >= 6 && it[1].isNotBlank() && it[5] != "Ad-Hoc" }
                .map { f ->
                    val freq = f[4].substringBefore(' ').toIntOrNull()
                    WifiNetwork(
                        ssid = f[1],
                        signal = f[2].toIntOrNull() ?: 0,
                        secured = f[3].isNotBlank() && f[3] != "--",
                        band = when { freq == null -> null; freq < 3000 -> Band.GHZ_2_4; else -> Band.GHZ_5 },
                        inUse = f[0] == "*",
                        known = f[1] in known,
                    )
                }
                .filter { it.ssid != hosted || it.inUse }
                .groupBy { it.ssid }
                .map { (_, same) -> same.maxWith(compareBy<WifiNetwork> { it.inUse }.thenBy { it.signal }) }
                .sortedWith(compareByDescending<WifiNetwork> { it.inUse }.thenByDescending { it.known }.thenByDescending { it.signal })

        private fun statusOf(code: Int, kind: AdapterKind, wifiOn: Boolean, job: AdapterJob?): String = when {
            kind == AdapterKind.WIFI && !wifiOn -> "Wi-Fi is off"
            code == 100 -> "Connected"
            code in 40..90 -> "Connecting…"
            code == 110 -> "Disconnecting…"
            code == 20 && kind == AdapterKind.ETHERNET -> "Cable unplugged"
            code == 20 -> "Not available"
            job == AdapterJob.OFF -> "Off"
            code == 120 -> "Could not connect"
            else -> "Not connected"
        }

        private fun productName(vendor: String?, product: String?, kind: AdapterKind, id: String): String {
            // Makers' names are long ("MT7925 802.11be 160MHz 2x2 PCIe Wireless Network Adapter [Filogic 360]");
            // the first words carry the model.
            val v = vendor?.takeIf { it.isNotBlank() }?.substringBefore(" Corp")?.substringBefore(" Inc")?.substringBefore(",")
            val p = product?.takeIf { it.isNotBlank() }?.split(' ')?.take(2)?.joinToString(" ")
            val made = listOfNotNull(v, p).joinToString(" ").ifBlank { null }
            val what = if (kind == AdapterKind.WIFI) "Wi-Fi" else "Ethernet"
            return made?.let { "$it ($what, $id)" } ?: "$what ($id)"
        }

        /** nmcli's error, in plain words. */
        internal fun plain(what: String, out: String?, hint: String? = null): String {
            val text = out.orEmpty()
            val reason = when {
                text.isBlank() -> "the network service did not answer."
                "Secrets were required" in text || "802-1X supplicant" in text || "psk: property is invalid" in text ->
                    "the password was not accepted."
                "No network with SSID" in text -> "that network is not in range."
                "Not authorized" in text || "Insufficient privileges" in text || "not authorized" in text ->
                    "this machine does not allow it from DASH (permission refused)."
                "Timeout" in text || "timed out" in text -> "it took too long; the network did not answer."
                "unknown connection" in text || "not found" in text -> "it is no longer there."
                "No suitable device" in text || "no device" in text -> "no adapter can do it."
                else -> text.lines().firstOrNull { it.startsWith("Error:") }?.removePrefix("Error:")?.trim()
                    ?.replaceFirstChar { it.lowercase() }?.let { if (it.endsWith('.')) it else "$it." } ?: "something went wrong."
            }
            return "Could not $what: $reason" + (hint?.let { " $it" } ?: "")
        }

        /** Whether nmcli is on the PATH — This Machine's report. */
        fun installed(): Boolean = (System.getenv("PATH") ?: "/usr/bin").split(':').any { File(it, "nmcli").canExecute() }
    }
}
