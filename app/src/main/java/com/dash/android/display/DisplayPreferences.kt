package com.dash.android.display

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
 * DASH's own display settings (DASH-AA 1.1.5) — the ones that are rules rather than the screens' setup
 * (that is the display program's, remembered by [DisplaySystem]): brightness by day and by night,
 * night light, and blanking. Kept apart from native's DashPreferences so that file stays identical;
 * stored in the same file all the same. Shared code: native follows with the same rules.
 */
@Serializable
data class DisplaySettings(
    /** Each screen's brightness, 0–1, by its identity: by day, and by night — null at night means the same as by day. */
    val brightness: Map<String, BrightnessLevels> = emptyMap(),
    val nightLight: NightLightMode = NightLightMode.OFF,
    /** How warm, in kelvin — lower is warmer. */
    val nightKelvin: Int = 4000,
    /** Minutes with nobody touching DASH before the screens go dark; 0 never. */
    val blankAfterMinutes: Int = 0,
    /** Dim for the last half-minute before going dark, so a glance at the screen can stop it. */
    val dimFirst: Boolean = true,
) {
    companion object {
        val BLANK_CHOICES = listOf(0, 1, 2, 5, 10, 15, 30)
        const val KELVIN_MIN = 2500
        const val KELVIN_MAX = 6000
    }
}

@Serializable
data class BrightnessLevels(val day: Float, val night: Float? = null)

enum class NightLightMode(val label: String) {
    OFF("Off"),
    ON("On"),
    /** Warm while a module reports the headlights on — the car's own idea of night. */
    HEADLIGHTS("With the headlights"),
}

class DisplayPreferences(private val context: Context) {

    val settings: Flow<DisplaySettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (DisplaySettings) -> DisplaySettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(DisplaySettings.serializer(), transform(read(p))) }
    }

    // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(DisplaySettings.serializer(), it) }.getOrNull() } ?: DisplaySettings()

    private companion object {
        val KEY = stringPreferencesKey("display_settings")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
