package com.dash.android.time

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
 * DASH's own date and time settings (DASH-AA 1.1.8) — how DASH shows the time, and whether the time zone
 * follows System › Location. The clock and the zone themselves are the machine's, read from [TimeSystem].
 * Kept apart from native's DashPreferences so that file stays identical; stored in the same file all the
 * same. Shared code.
 */
@Serializable
data class TimeSettings(
    /** 24-hour (true) or 12-hour (false) time — for DASH's clocks, and every element and overlay that reads [DashTime]. */
    val hours24: Boolean = true,
    val dateStyle: DateStyle = DateStyle.SHORT_NAMES,
    /** Set the machine's time zone from where System › Location says DASH is. */
    val zoneFromLocation: Boolean = false,
)

/** How a date is written. [pattern] is java.time's (Android's too). */
enum class DateStyle(val pattern: String) {
    NAMES("EEEE d MMMM yyyy"),
    SHORT_NAMES("EEE d MMM yyyy"),
    DAY_FIRST("dd/MM/yyyy"),
    MONTH_FIRST("MM/dd/yyyy"),
    YEAR_FIRST("yyyy-MM-dd"),
}

class TimePreferences(private val context: Context) {

    val settings: Flow<TimeSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (TimeSettings) -> TimeSettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(TimeSettings.serializer(), transform(read(p))) }
    }

    // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(TimeSettings.serializer(), it) }.getOrNull() } ?: TimeSettings()

    private companion object {
        val KEY = stringPreferencesKey("time_settings")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
