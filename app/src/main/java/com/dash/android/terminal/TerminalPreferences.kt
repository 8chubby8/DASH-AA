package com.dash.android.terminal

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

/** The Terminal's own settings (DASH-AA 1.1.10), kept beside DASH's others. Shared code. */
@Serializable
data class TerminalSettings(
    /** The text size, in sp — A− and A+ on the tab. */
    val fontSize: Int = 15,
)

class TerminalPreferences(private val context: Context) {

    val settings: Flow<TerminalSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (TerminalSettings) -> TerminalSettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(TerminalSettings.serializer(), transform(read(p))) }
    }

    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(TerminalSettings.serializer(), it) }.getOrNull() } ?: TerminalSettings()

    companion object {
        const val SMALLEST = 9
        const val LARGEST = 30
        private val KEY = stringPreferencesKey("terminal_settings")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
