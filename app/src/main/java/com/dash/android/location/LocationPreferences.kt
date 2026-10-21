package com.dash.android.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

/**
 * System › Location's settings (DASH-AA 1.1.11) — where DASH takes its location from. The pinned place
 * itself stays native's (`DashPreferences.manualLocation`); kept apart from native's DashPreferences so
 * that file stays identical, stored in the same file all the same. Shared code.
 */
@Serializable
data class LocationSettings(
    val mode: LocationMode = LocationMode.AUTOMATIC,
)

/** Where DASH takes its location from — the user's choice, every one of them open. */
enum class LocationMode(val label: String) {
    /** A GPS fix when there is one (a receiver on the machine, or a module), otherwise the internet. */
    AUTOMATIC("Automatic"),
    GPS("GPS"),
    NETWORK("Internet"),
    MANUAL("Manual"),
    /** Nothing at all: no position, no look-ups. The weather shows the clock. */
    OFF("Off"),
}

class LocationPreferences(private val context: Context) {

    val settings: Flow<LocationSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (LocationSettings) -> LocationSettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(LocationSettings.serializer(), transform(read(p))) }
    }

    // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(LocationSettings.serializer(), it) }.getOrNull() } ?: LocationSettings()

    private companion object {
        val KEY = stringPreferencesKey("location_settings")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
