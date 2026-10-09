package com.dash.android.power

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
 * DASH's own power settings (DASH-AA 1.1.7) — the rules; the machine's own state (the profile in use, the
 * charge limit) stays the machine's and is read from [PowerSystem]. Kept apart from native's
 * DashPreferences so that file stays identical; stored in the same file all the same. Shared code.
 */
@Serializable
data class PowerSettings(
    /** Minutes with nobody touching DASH before the machine sleeps; 0 never. Never while a phone projects. */
    val sleepAfterMinutes: Int = 0,
    /** What closing the lid does. */
    val lid: LidAction = LidAction.SLEEP,
    /** The profile to use on a charger, by the profile service's id; null leaves it as it is. */
    val profileOnCharger: String? = null,
    /** The profile to use on the battery; null leaves it as it is. */
    val profileOnBattery: String? = null,
    /** The car's own power — the stages and the switched outputs (Power › Car and Outputs). */
    val car: CarPowerSettings = CarPowerSettings(),
) {
    companion object {
        val SLEEP_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120)
    }
}

enum class LidAction(val label: String) {
    /** The machine's own behaviour — DASH leaves the lid alone. */
    SLEEP("Sleep"),
    SCREEN_OFF("Screen off"),
    NOTHING("Nothing"),
}

class PowerPreferences(private val context: Context) {

    val settings: Flow<PowerSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (PowerSettings) -> PowerSettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(PowerSettings.serializer(), transform(read(p))) }
    }

    // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(PowerSettings.serializer(), it) }.getOrNull() } ?: PowerSettings()

    private companion object {
        val KEY = stringPreferencesKey("power_settings")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
