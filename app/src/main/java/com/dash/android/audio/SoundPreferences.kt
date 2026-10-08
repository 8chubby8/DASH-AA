package com.dash.android.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

/**
 * DASH's own sound settings (DASH-AA 1.1.2) — the ones that belong to DASH rather than to the sound
 * system (the speakers chosen and their volume are the machine's, and live there), and from 1.1.3 the
 * car's sound: the speaker layout, equaliser, balance, fade and crossover ([CarSound]). Kept apart from
 * native's DashPreferences so that file stays identical; stored in the same file all the same.
 */
data class SoundSettings(
    /** Audio › Speakers › Start-up volume limit, 0–1; [LIMIT_OFF] leaves the volume as it was. */
    val startupLimit: Float = LIMIT_OFF,
    /** Audio › Speakers › Volume buttons: what the steering wheel's volume turns. As before 1.1.2 by default. */
    val volumeButtons: VolumeTarget = VolumeTarget.VIEWPORT,
    /** Audio › Speakers's speaker layout and Audio › Equaliser (1.1.3). */
    val car: CarSound = CarSound(),
) {
    companion object {
        const val LIMIT_OFF = 0f
        const val LIMIT_STEP = 0.05f
    }
}

class SoundPreferences(private val context: Context) {

    val settings: Flow<SoundSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (SoundSettings) -> SoundSettings) {
        context.dataStore.edit { p ->
            val next = transform(read(p))
            p[STARTUP_LIMIT] = next.startupLimit
            p[VOLUME_BUTTONS] = next.volumeButtons.name
            p[CAR] = json.encodeToString(CarSound.serializer(), next.car)
        }
    }

    private fun read(p: Preferences) = SoundSettings(
        startupLimit = p[STARTUP_LIMIT] ?: SoundSettings.LIMIT_OFF,
        volumeButtons = p[VOLUME_BUTTONS]?.let { runCatching { VolumeTarget.valueOf(it) }.getOrNull() } ?: VolumeTarget.VIEWPORT,
        // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
        car = p[CAR]?.let { runCatching { json.decodeFromString(CarSound.serializer(), it) }.getOrNull() } ?: CarSound(),
    )

    private companion object {
        val STARTUP_LIMIT = floatPreferencesKey("sound_startup_limit")
        val VOLUME_BUTTONS = stringPreferencesKey("sound_volume_buttons")
        val CAR = stringPreferencesKey("sound_car")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
