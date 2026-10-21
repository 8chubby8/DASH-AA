package com.dash.android.time

import android.util.Log
import com.dash.android.weather.ManualLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Date & Time's one rule (DASH-AA 1.1.8): **the time zone follows System › Location**, when the user turns it
 * on. Started with DASH rather than in the tab, so a car driven across a border changes its clocks whether or
 * not settings are open.
 *
 * Where DASH is comes from the same place the weather's does: the place the user pinned in Location, or,
 * with none pinned, where the internet connection says it is. The zone for it is looked up on start, when
 * the pinned place changes, and every half hour; with no internet the zone simply stays as it is.
 *
 * Shared code. **For native:** Android sets its own zone from the phone network; native leaves this off.
 */
class TimeRules(
    private val time: TimeSystem,
    private val settings: Flow<TimeSettings>,
    private val location: Flow<ManualLocation?>,
    private val scope: CoroutineScope,
    private val find: suspend (ManualLocation?) -> String? = { withContext(Dispatchers.IO) { ZoneFinder.find(it) } },
    private val every: Long = 30 * 60_000L,
) {
    private val _found = MutableStateFlow<ZoneLookup?>(null)
    /** The last look-up while Follow Location is on, for the tab to show; null before the first or while off. */
    val found: StateFlow<ZoneLookup?> = _found

    fun start() {
        scope.launch {
            combine(settings, location) { s, l -> if (s.zoneFromLocation) Follow(l) else null }
                .distinctUntilChanged()
                .collectLatest { follow ->
                    if (follow == null) { _found.value = null; return@collectLatest }
                    while (true) {
                        lookUp(follow.pinned)
                        delay(every)
                    }
                }
        }
    }

    private suspend fun lookUp(pinned: ManualLocation?) {
        val zone = runCatching { find(pinned) }.getOrNull()
        val from = pinned?.name ?: "this connection"
        if (zone == null) {
            _found.value = ZoneLookup(null, from)
            return
        }
        _found.value = ZoneLookup(zone, from)
        // The machine's own answer first, so a zone already right is never set again; none, and nothing is set.
        val machine = withTimeoutOrNull(10_000) { time.state.first { it.available } } ?: return
        if (machine.zone != zone && machine.canChange != false && (machine.zones.isEmpty() || zone in machine.zones)) {
            Log.i(TAG, "time zone follows $from: $zone")
            time.setZone(zone)
        }
    }

    private data class Follow(val pinned: ManualLocation?)

    private companion object {
        const val TAG = "DashTime"
    }
}

/** What Follow Location last found: [zone] for [from] (a pinned place, or "this connection"); [zone] null when it could not look. */
data class ZoneLookup(val zone: String?, val from: String)

/**
 * The time zone for a place, from the services the weather already uses: Open-Meteo for a pinned place
 * (by its coordinates), and with none pinned, where the internet connection is (ipwho.is, then ipapi.co).
 * Null with no internet or no answer.
 */
object ZoneFinder {
    private val json = Json { ignoreUnknownKeys = true }

    fun find(pinned: ManualLocation?): String? =
        if (pinned != null) {
            get("https://api.open-meteo.com/v1/forecast?latitude=${pinned.latitude}&longitude=${pinned.longitude}&timezone=auto&forecast_days=1")
                ?.let { field(it, "timezone") }
        } else {
            get("https://ipwho.is/")?.let { body ->
                runCatching { json.parseToJsonElement(body).jsonObject["timezone"]?.jsonObject?.get("id")?.jsonPrimitive?.content }.getOrNull()
            } ?: get("https://ipapi.co/json/")?.let { field(it, "timezone") }
        }?.takeIf { it.contains('/') || it == "UTC" }

    private fun field(body: String, name: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive?.content }.getOrNull()

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                // As the weather: some free services refuse Java's own name.
                setRequestProperty("User-Agent", "DASH-HeadUnit/1.5 (Linux)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            Log.w("DashTime", "time zone look-up failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
