package com.dash.android.location

import android.util.Log
import com.dash.android.core.StoredSignal
import com.dash.android.core.SystemState
import com.dash.android.weather.ManualLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **System › Location's rules** (DASH-AA 1.1.11, Roger 2026-10-21) — where DASH is, from the source the user
 * chose, published as [DashLocation] and raised as the `location_*` signals (system_commands.md, Location).
 * Started with DASH rather than in the tab, so the signals, the elements and Android Auto have it whether or
 * not settings are open.
 *
 * **The sources.** A GPS receiver on the machine ([gnss] — on Linux, one the USB transport recognised by
 * its sentences); a module reporting `location_position` (an ESP32 with a GPS chip, say — its readings are
 * already in the core, so DASH reads them there); the internet connection (looked up every half hour); and
 * the place pinned by name. [LocationMode.AUTOMATIC] takes a GPS fix when there is one and the internet
 * otherwise, so a car park or a cold start falls back to the town rather than to nothing.
 *
 * **The signals.** Raised by DASH from whichever source it is using, `unknown` for anything not known right
 * now. **While a module is reporting the location itself, DASH raises none**: the core is sourceless, and two
 * writers to one signal would only fight. The module's own signals stand, and DASH reads them like any other.
 *
 * Shared code. **For native:** take as it is; Android's location fills [gnss].
 */
class LocationRules(
    private val state: SystemState,
    private val gnss: Flow<GnssState>,
    private val settings: Flow<LocationSettings>,
    private val manual: Flow<ManualLocation?>,
    /** Whether an active module is reporting `location_position` (see [VehicleSpeed][com.dash.android.audio.VehicleSpeed]). */
    private val moduleReporting: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val network: suspend () -> NetworkPlace? = { withContext(Dispatchers.IO) { NetworkLocation.find() } },
    private val nameFor: suspend (Double, Double) -> String? = { lat, lon -> withContext(Dispatchers.IO) { NetworkLocation.placeNear(lat, lon) } },
    private val now: () -> Long = System::currentTimeMillis,
    private val every: Long = 30 * 60_000L,
    private val retry: Long = 2 * 60_000L,
) {
    private val networkPlace = MutableStateFlow<NetworkPlace?>(null)
    private val gpsPlace = MutableStateFlow<NamedPoint?>(null)

    fun start() {
        // The internet's answer, while a mode that may use it is chosen.
        scope.launch {
            settings.map { it.mode }.distinctUntilChanged().collectLatest { mode ->
                if (mode != LocationMode.AUTOMATIC && mode != LocationMode.NETWORK) return@collectLatest
                while (true) {
                    val found = runCatching { network() }.getOrNull()
                    if (found != null) networkPlace.value = found
                    delay(if (found != null) every else retry)
                }
            }
        }
        // A module's own location signals, only while one is reporting — DASH's own raising never loops back.
        val fromModule = combine(moduleReporting, state.values.map { v -> v.filterKeys { it.startsWith("location_") } }.distinctUntilChanged()) { r, v ->
            if (r) v else null
        }.distinctUntilChanged()
        scope.launch {
            combine(gnss, settings, manual, fromModule) { g, s, m, values ->
                Inputs(g, s.mode, m, values?.let { moduleFix(it, g.receiver) })
            }.combine(networkPlace) { i, n -> i to n }
                .combine(gpsPlace) { (i, n), named -> choose(i, n, named) to (i.module != null) }
                .collect { (fix, moduleOwns) ->
                    DashLocation.publish(fix)
                    if (!moduleOwns) raise(fix)
                    if (fix.source == LocationSource.GPS && fix.hasPosition) nameGps(fix.latitude!!, fix.longitude!!)
                }
        }
    }

    private data class Inputs(val gnss: GnssState, val mode: LocationMode, val manual: ManualLocation?, val module: DashFix?)

    private fun choose(i: Inputs, net: NetworkPlace?, named: NamedPoint?): DashFix {
        val g = i.gnss.reading
        val own = if (i.gnss.receiver != null && g.fix != GpsFix.NONE && g.latitude != null && g.longitude != null) DashFix(
            source = LocationSource.GPS, fix = g.fix, latitude = g.latitude, longitude = g.longitude,
            speedKmh = g.speedKmh, headingDegrees = g.headingDegrees, altitudeMetres = g.altitudeMetres,
            accuracyMetres = g.accuracyMetres, satellitesUsed = g.satellitesUsed, satellitesInView = g.satellitesInView,
            place = named?.takeIf { distanceKm(it.lat, it.lon, g.latitude, g.longitude) < NAME_KM }?.name,
            receiver = i.gnss.receiver, at = now(),
        ) else null
        val gps = own ?: i.module?.takeIf { it.source == LocationSource.GPS && it.hasPosition }
        // With no fix, what the receiver is doing still shows — how many satellites it can see.
        val idle = DashFix(receiver = i.gnss.receiver, satellitesUsed = g.satellitesUsed.takeIf { i.gnss.receiver != null },
            satellitesInView = g.satellitesInView.takeIf { i.gnss.receiver != null }, at = now())
        val fromNet = net?.let { idle.copy(source = LocationSource.NETWORK, latitude = it.latitude, longitude = it.longitude, place = it.place) }
        val fromPin = i.manual?.let { idle.copy(source = LocationSource.MANUAL, latitude = it.latitude, longitude = it.longitude, place = it.name) }
        return when (i.mode) {
            LocationMode.AUTOMATIC -> gps ?: i.module?.takeIf { it.hasPosition } ?: fromNet
            LocationMode.GPS -> gps
            LocationMode.NETWORK -> fromNet
            LocationMode.MANUAL -> fromPin
            LocationMode.OFF -> null
        } ?: idle
    }

    /** A module's own location, read back from the core. */
    private fun moduleFix(values: Map<String, StoredSignal>, receiver: String?): DashFix {
        fun v(name: String) = values[name]?.value?.takeIf { it != UNKNOWN }
        val pos = v("location_position")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }
        return DashFix(
            source = LocationSource.of(v("location_source")) ?: if (pos != null) LocationSource.GPS else LocationSource.NONE,
            fix = GpsFix.of(v("location_fix")) ?: GpsFix.NONE,
            latitude = pos?.get(0), longitude = pos?.get(1),
            speedKmh = v("location_speed")?.toDoubleOrNull(),
            headingDegrees = v("location_heading")?.toDoubleOrNull(),
            altitudeMetres = v("location_altitude")?.toDoubleOrNull(),
            accuracyMetres = v("location_accuracy")?.toDoubleOrNull(),
            satellitesUsed = v("location_satellites")?.toIntOrNull(),
            receiver = receiver, at = now(),
        )
    }

    /** A name near a GPS fix, looked up again only once DASH has moved a little way. */
    private fun nameGps(lat: Double, lon: Double) {
        val last = gpsPlace.value
        if (last != null && distanceKm(last.lat, last.lon, lat, lon) < RENAME_KM) return
        if (looking || now() - lastLook < LOOK_GAP_MS) return
        looking = true
        lastLook = now()
        scope.launch {
            val name = runCatching { nameFor(lat, lon) }.getOrNull()
            looking = false
            if (name != null) gpsPlace.value = NamedPoint(lat, lon, name)
        }
    }

    @Volatile private var looking = false
    @Volatile private var lastLook = 0L

    private fun raise(f: DashFix) {
        set("location_source", f.source.signal, event = true)
        set("location_fix", f.fix.signal, event = true)
        set("location_position", if (f.hasPosition) String.format(Locale.ROOT, "%.6f,%.6f", f.latitude, f.longitude) else UNKNOWN)
        set("location_speed", f.speedKmh?.let { String.format(Locale.ROOT, "%.1f", it) } ?: UNKNOWN)
        set("location_heading", f.headingDegrees?.let { (Math.round(it) % 360).toString() } ?: UNKNOWN)
        set("location_altitude", f.altitudeMetres?.let { String.format(Locale.ROOT, "%.0f", it) } ?: UNKNOWN)
        set("location_accuracy", f.accuracyMetres?.let { String.format(Locale.ROOT, "%.0f", it) } ?: UNKNOWN)
        set("location_satellites", f.satellitesUsed?.toString() ?: UNKNOWN)
    }

    private fun set(signal: String, value: String, event: Boolean = false) {
        if (state.current(signal) == value) return
        state.store(signal, value)
        if (event) {
            state.fire(signal, value)
            Log.i(TAG, "$signal $value")
        }
    }

    private data class NamedPoint(val lat: Double, val lon: Double, val name: String)

    companion object {
        private const val TAG = "DashLocation"
        const val UNKNOWN = "unknown"
        /** A name looked up for a GPS fix is kept until DASH is this far from where it was looked up. */
        private const val RENAME_KM = 2.0
        /** …and is shown only within this distance of it. */
        private const val NAME_KM = 5.0
        private const val LOOK_GAP_MS = 60_000L

        /** Great-circle distance in kilometres. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.toRadians(1.0)
            val a = sin((lat2 - lat1) * r / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(a))
        }

        /**
         * Where DASH is, as a place, each time it has moved [km] or more — or the source or name changed;
         * null with no position. Date & Time's Follow Location looks the zone up from this.
         */
        fun places(fixes: Flow<DashFix>, km: Double): Flow<ManualLocation?> = flow {
            var last: ManualLocation? = null
            var lastSource: LocationSource? = null
            var sent = false
            fixes.collect { f ->
                val here = if (f.hasPosition) ManualLocation(f.place ?: "this position", f.latitude!!, f.longitude!!) else null
                val moved = when {
                    !sent -> true
                    here == null || last == null -> here != last
                    f.source != lastSource -> true
                    else -> distanceKm(last!!.latitude, last!!.longitude, here.latitude, here.longitude) >= km
                }
                if (moved) {
                    last = here
                    lastSource = f.source
                    sent = true
                    emit(here)
                }
            }
        }.distinctUntilChanged()
    }
}

/** Where the internet connection says DASH is: a town's middle, give or take many kilometres. */
data class NetworkPlace(val latitude: Double, val longitude: Double, val place: String?)

/**
 * The internet's side of Location: where the connection is (ipwho.is, then ipapi.co — the services the
 * weather used before 1.1.11), and a town's name near a GPS fix (OpenStreetMap's Nominatim). The fix sent for
 * a name is rounded to about a kilometre: a town is all that is asked for. Null with no internet.
 */
object NetworkLocation {
    private val json = Json { ignoreUnknownKeys = true }

    fun find(): NetworkPlace? = ipWhoIs() ?: ipApiCo()

    fun placeNear(lat: Double, lon: Double): String? {
        val url = String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=%.2f&lon=%.2f", lat, lon)
        val body = get(url) ?: return null
        return runCatching {
            val address = json.parseToJsonElement(body).jsonObject["address"]?.jsonObject ?: return null
            listOf("city", "town", "village", "hamlet", "municipality", "county")
                .firstNotNullOfOrNull { address[it]?.jsonPrimitive?.content }
        }.getOrNull()
    }

    private fun ipWhoIs(): NetworkPlace? {
        val o = get("https://ipwho.is/")?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        if (o["success"]?.jsonPrimitive?.content == "false") return null
        val lat = o["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = o["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return NetworkPlace(lat, lon, o["city"]?.jsonPrimitive?.content)
    }

    private fun ipApiCo(): NetworkPlace? {
        val o = get("https://ipapi.co/json/")?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        if (o["error"]?.jsonPrimitive?.content == "true") return null
        val lat = o["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = o["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return NetworkPlace(lat, lon, o["city"]?.jsonPrimitive?.content)
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                // As the weather: some free services refuse Java's own name, and Nominatim asks for a real one.
                setRequestProperty("User-Agent", "DASH-HeadUnit/1.5 (Linux)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            Log.w("DashLocation", "look-up failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
