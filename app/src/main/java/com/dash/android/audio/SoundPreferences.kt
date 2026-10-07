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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

/**
 * DASH's own sound settings (DASH-AA 1.1.2) — the ones that belong to DASH rather than to the sound
 * system (the speakers chosen and their volume are the machine's, and live there). Kept apart from
 * native's DashPreferences so that file stays identical; stored in the same file all the same.
 */
data class SoundSettings(
    /** Audio › Output › Start-up volume limit, 0–1; [LIMIT_OFF] leaves the volume as it was. */
    val startupLimit: Float = LIMIT_OFF,
    /** Audio › Output › Volume buttons: what the steering wheel's volume turns. As before 1.1.2 by default. */
    val volumeButtons: VolumeTarget = VolumeTarget.VIEWPORT,
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
        }
    }

    private fun read(p: Preferences) = SoundSettings(
        startupLimit = p[STARTUP_LIMIT] ?: SoundSettings.LIMIT_OFF,
        volumeButtons = p[VOLUME_BUTTONS]?.let { runCatching { VolumeTarget.valueOf(it) }.getOrNull() } ?: VolumeTarget.VIEWPORT,
    )

    private companion object {
        val STARTUP_LIMIT = floatPreferencesKey("sound_startup_limit")
        val VOLUME_BUTTONS = stringPreferencesKey("sound_volume_buttons")
    }
}
