package com.dash.android.connections

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
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

/**
 * DASH's own Connections settings (DASH-AA 1.1.6) — the choices that are DASH's rather than the network
 * service's: what each Wi-Fi adapter is for, the DASH network, the on-screen keyboard's lock, and keeping
 * phones' Bluetooth music away. Kept apart from native's DashPreferences so that file stays identical;
 * stored in the same file all the same. Shared code: native follows with what Android lets it do.
 */
@Serializable
data class ConnectionsSettings(
    /** Wi-Fi adapters given a job other than joining networks, by their hardware address. */
    val jobs: Map<String, AdapterJob> = emptyMap(),
    val network: DashNetwork = DashNetwork(),
    /**
     * The on-screen keyboard only while the car is still (Roger, 2026-10-08: on by default). Needs a
     * module that reports the speed or the handbrake; with neither, the keyboard is always allowed.
     */
    val keyboardOnlyWhenStill: Boolean = true,
    /**
     * Refuse phones' Bluetooth music (A2DP), which crashes the sound system when it plays beside
     * Android Auto's (changelog 1.0.6). Calls, and internet over Bluetooth, are not affected.
     */
    val noBluetoothMusic: Boolean = true,
) {
    fun jobOf(identity: String): AdapterJob = jobs[identity] ?: AdapterJob.JOIN
}

/** The DASH network — the car's own Wi-Fi, hosted by an adapter whose job is [AdapterJob.HOST]. */
@Serializable
data class DashNetwork(
    val name: String = "DASH",
    /** Empty until first needed; then a random one is made and kept. */
    val password: String = "",
    /** 2.4 GHz by default: the band every module's radio can hear (ESP32s hear nothing else). */
    val band: Band = Band.GHZ_2_4,
) {
    companion object {
        const val PASSWORD_MIN = 8
        const val PASSWORD_MAX = 63

        /** Twelve letters and digits, with the ones easily misread on a screen left out. */
        fun newPassword(): String {
            val letters = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            return (1..12).map { letters[r.nextInt(letters.length)] }.joinToString("")
        }
    }
}

class ConnectionsPreferences(private val context: Context) {

    val settings: Flow<ConnectionsSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (ConnectionsSettings) -> ConnectionsSettings) {
        context.dataStore.edit { p -> p[KEY] = json.encodeToString(ConnectionsSettings.serializer(), transform(read(p))) }
    }

    // Unreadable (written by a later DASH, say) starts again from the defaults rather than failing.
    private fun read(p: Preferences) =
        p[KEY]?.let { runCatching { json.decodeFromString(ConnectionsSettings.serializer(), it) }.getOrNull() } ?: ConnectionsSettings()

    private companion object {
        val KEY = stringPreferencesKey("connections_settings")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
