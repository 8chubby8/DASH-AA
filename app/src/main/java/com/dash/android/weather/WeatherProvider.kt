package com.dash.android.weather

import android.content.Context
import android.util.Log
import com.dash.android.prefs.DashPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Turns the outside world into a [WeatherSnapshot] for the settings-landing scene (roadmap 1.5.4).
 *
 * **Cascade, most-owned first (interface.md / design memory):**
 *   1. **GPS** — a last-known coarse fix, *only if* `ACCESS_COARSE_LOCATION` is already granted. DASH
 *      never prompts for it (the no-nag rule); if a user has granted it independently this tier lights
 *      up transparently — capability detection, exactly like every other privileged feature.
 *   2. **IP geolocation** — a keyless lookup that needs no permission at all. Tried across more than
 *      one provider so a single service being down or rate-limited doesn't sink the feature. This is
 *      the working path on a fresh install and is usually accurate to the town.
 *   3. **Clock-only floor** — if location *and* weather both fail (no network, blocked, timed out),
 *      [WeatherSnapshot.clockOnly] renders a correct time-of-day sky from the device clock alone. The
 *      scene is therefore never broken and never depends on connectivity.
 *
 * Manual city entry (the design's fourth rung) waits on a settings UI to enter it — the cascade is
 * structured to slot it in ahead of GPS later without disturbing the rest.
 *
 * All work is off the main thread and every failure is swallowed into the clock-only floor, so a
 * caller can `provider.current()` from a composable and simply receive the best snapshot available.
 * Each cascade step logs its outcome under [TAG] so a stuck "offline" can be diagnosed from logcat.
 */
class WeatherProvider(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = DashPreferences(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** The best snapshot available right now: live weather if any source succeeds, else clock-only. */
    suspend fun current(): WeatherSnapshot = withContext(Dispatchers.IO) {
        val located = resolveLocation()
        if (located == null) {
            Log.i(TAG, "no location from cascade → offline floor")
            return@withContext WeatherSnapshot.clockOnly()
        }
        Log.i(TAG, "located ${located.name} (${located.lat}, ${located.lon})")
        val live = fetchWeather(located)
        if (live == null) Log.i(TAG, "weather fetch failed → offline floor")
        live ?: WeatherSnapshot.clockOnly()
    }

    /**
     * Resolve a typed place name to a fixed [ManualLocation] via Open-Meteo's geocoder, for the manual
     * rung of the cascade. Returns null if the name matches nothing. On IO; safe to call from the UI.
     */
    suspend fun geocodeCity(query: String): ManualLocation? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        val encoded = URLEncoder.encode(q, "UTF-8")
        val body = httpGet(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
        ) ?: return@withContext null
        val result = runCatching { json.decodeFromString<GeoResponse>(body) }.getOrNull()
            ?.results?.firstOrNull() ?: return@withContext null
        val label = listOfNotNull(result.name, result.admin1).distinct().joinToString(", ")
        ManualLocation(label, result.latitude, result.longitude)
    }

    // ── Location cascade ─────────────────────────────────────────────────────────────────────────
    // Manual pin (user's chosen place) wins outright; otherwise GPS, then IP.
    private suspend fun resolveLocation(): Located? {
        prefs.manualLocation.first()?.let {
            Log.i(TAG, "manual pin ${it.name}")
            return Located(it.latitude, it.longitude, it.name)
        }
        return gpsLocation() ?: ipLocation()
    }

    /** Last-known coarse fix, only when the permission is already granted. Never requests it. */
    /**
     * DASH-AA: Android's device-location tier has no equivalent on the laptop yet, so this step of the
     * cascade answers nothing and the cascade falls through to IP — exactly what upstream does when the
     * location permission was never granted. A GNSS fix arrives with upstream's Location Service
     * (1.10.x), which on Linux would read the receiver over the module transport or GeoClue.
     */
    private fun gpsLocation(): Located? = null

    private fun ipLocation(): Located? = ipWhoIs() ?: ipApiCo()

    private fun ipWhoIs(): Located? {
        val body = httpGet("https://ipwho.is/") ?: return null
        val r = runCatching { json.decodeFromString<IpWhoIs>(body) }.getOrNull() ?: return null
        if (!r.success || r.latitude == null || r.longitude == null) return null
        return Located(r.latitude, r.longitude, r.city)
    }

    private fun ipApiCo(): Located? {
        val body = httpGet("https://ipapi.co/json/") ?: return null
        val ip = runCatching { json.decodeFromString<IpApiCo>(body) }.getOrNull() ?: return null
        if (ip.error || ip.latitude == null || ip.longitude == null) return null
        return Located(ip.latitude, ip.longitude, ip.city)
    }

    // ── Weather fetch ────────────────────────────────────────────────────────────────────────────
    private fun fetchWeather(at: Located): WeatherSnapshot? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${at.lat}&longitude=${at.lon}" +
            "&current=temperature_2m,weather_code,is_day,cloud_cover,wind_speed_10m," +
            "wind_direction_10m,precipitation" +
            "&wind_speed_unit=kmh&timezone=auto"
        val body = httpGet(url) ?: return null
        val cur = runCatching { json.decodeFromString<OpenMeteoResponse>(body) }.getOrNull()?.current
            ?: return null
        val condition = wmoToCondition(cur.weatherCode)
        return WeatherSnapshot(
            condition = condition,
            timeOfDay = timeOfDayFor(currentHour(), isDay = cur.isDay == 1),
            temperatureC = cur.temperature,
            windKph = cur.windSpeed,
            windFromDegrees = cur.windDirection,
            cloudCoverPercent = cur.cloudCover,
            precipitation = condition.precip(),
            locationName = at.name,
            live = true,
        )
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────────
    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                // Some free geolocation endpoints reject the default Java user-agent, so identify
                // ourselves plainly. Harmless to the others.
                setRequestProperty("User-Agent", "DASH-HeadUnit/1.5 (Linux; DASH-AA)")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $urlString → HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $urlString failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private data class Located(val lat: Double, val lon: Double, val name: String?)

    private companion object {
        const val TAG = "DashWeather"
    }
}

// ── Wire DTOs (only the fields the scene needs; unknown keys ignored) ─────────────────────────────
@Serializable
private data class IpWhoIs(
    val success: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
)

@Serializable
private data class IpApiCo(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val error: Boolean = false,
)

@Serializable
private data class GeoResponse(val results: List<GeoResult> = emptyList())

@Serializable
private data class GeoResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val admin1: String? = null,
    val country: String? = null,
)

@Serializable
private data class OpenMeteoResponse(val current: OpenMeteoCurrent? = null)

@Serializable
private data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int = 3,
    @SerialName("is_day") val isDay: Int = 1,
    @SerialName("cloud_cover") val cloudCover: Int = 0,
    @SerialName("wind_speed_10m") val windSpeed: Double = 0.0,
    @SerialName("wind_direction_10m") val windDirection: Double = 0.0,
    val precipitation: Double = 0.0,
)
