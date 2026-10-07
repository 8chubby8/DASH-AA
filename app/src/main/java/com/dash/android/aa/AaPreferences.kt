package com.dash.android.aa

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dash.android.aa.protocol.AaVideoMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

// The same store upstream's DashPreferences uses — the shim keeps one store per name per process.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

/** Where Android Auto takes day and night from. */
enum class NightSource(val label: String) {
    /** From the car: `headlights_on` when a SYSTEM module reports it; otherwise the phone decides. */
    AUTO("Headlights"),
    DAY("Day"),
    NIGHT("Night"),
}

/** Which side the driver sits — Android Auto mirrors its layout to keep controls near the driver. */
enum class DriverSide(val label: String) { AUTO("Auto"), LEFT("Left"), RIGHT("Right") }

/**
 * Android Auto's settings (DASH-AA). Kept apart from upstream's DashPreferences so that file stays
 * byte-identical; stored in the same file all the same.
 *
 * Every field that changes what the phone was told at connection (video mode, frame rate, density,
 * driver side, microphone, audio) restarts projection when changed — Android Auto fixes them at
 * connection and has no way to change them mid-session.
 */
data class AaSettings(
    val enabled: Boolean = true,
    val videoMode: AaVideoMode = AaVideoMode.P1080,
    val fps60: Boolean = false,
    val dpi: Int = DPI_DEFAULT,
    val nightSource: NightSource = NightSource.AUTO,
    val driverSide: DriverSide = DriverSide.AUTO,
    val audio: Boolean = true,
    val microphone: Boolean = true,
    val duckMedia: Boolean = true,
    val echoCancel: Boolean = true,
    /** The friend's voice on a call, as a gain: 1.0 as the phone sends it, above 1.0 boosted. */
    val callVolume: Float = 1.0f,
    val volume: Float = 1.0f,
    /** Audio › Mixer (1.1.2): each of Android Auto's three sounds, under [volume]. */
    val musicLevel: Float = 1.0f,
    val directionsLevel: Float = 1.0f,
    val systemLevel: Float = 1.0f,
) {
    /** Driver side resolved — AUTO follows the country the laptop is set to. */
    val leftHandDrive: Boolean get() = when (driverSide) {
        DriverSide.LEFT -> true
        DriverSide.RIGHT -> false
        DriverSide.AUTO -> Locale.getDefault().country.uppercase() !in RIGHT_HAND_DRIVE_COUNTRIES
    }

    companion object {
        const val DPI_DEFAULT = 160
        const val DPI_MIN = 80
        const val DPI_MAX = 400
        const val DPI_STEP = 10

        const val CALL_VOLUME_MIN = 0.5f
        const val CALL_VOLUME_MAX = 3.0f
        const val CALL_VOLUME_STEP = 0.1f

        /** Countries that drive on the left, so cars there are right-hand drive. */
        val RIGHT_HAND_DRIVE_COUNTRIES = setOf(
            "GB", "IE", "IM", "JE", "GG", "MT", "CY", "AU", "NZ", "JP", "IN", "PK", "BD", "LK", "NP",
            "ZA", "ZW", "ZM", "KE", "UG", "TZ", "MW", "MZ", "BW", "NA", "SZ", "LS", "MU", "SG", "MY",
            "BN", "TH", "ID", "HK", "MO", "JM", "TT", "BB", "BS", "GY", "SR", "FJ", "PG", "TO", "WS",
        )
    }
}

class AaPreferences(private val context: Context) {

    val settings: Flow<AaSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (AaSettings) -> AaSettings) {
        context.dataStore.edit { p ->
            val next = transform(read(p))
            p[ENABLED] = next.enabled
            p[VIDEO_MODE] = next.videoMode.name
            p[FPS60] = next.fps60
            p[DPI] = next.dpi
            p[NIGHT] = next.nightSource.name
            p[SIDE] = next.driverSide.name
            p[AUDIO] = next.audio
            p[MIC] = next.microphone
            p[DUCK] = next.duckMedia
            p[ECHO] = next.echoCancel
            p[CALL_VOLUME] = next.callVolume
            p[VOLUME] = next.volume
            p[MUSIC_LEVEL] = next.musicLevel
            p[DIRECTIONS_LEVEL] = next.directionsLevel
            p[SYSTEM_LEVEL] = next.systemLevel
        }
    }

    private fun read(p: Preferences) = AaSettings(
        enabled = p[ENABLED] ?: true,
        videoMode = p[VIDEO_MODE]?.let { runCatching { AaVideoMode.valueOf(it) }.getOrNull() } ?: AaVideoMode.P1080,
        fps60 = p[FPS60] ?: false,
        dpi = p[DPI] ?: AaSettings.DPI_DEFAULT,
        nightSource = p[NIGHT]?.let { runCatching { NightSource.valueOf(it) }.getOrNull() } ?: NightSource.AUTO,
        driverSide = p[SIDE]?.let { runCatching { DriverSide.valueOf(it) }.getOrNull() } ?: DriverSide.AUTO,
        audio = p[AUDIO] ?: true,
        microphone = p[MIC] ?: true,
        duckMedia = p[DUCK] ?: true,
        echoCancel = p[ECHO] ?: true,
        callVolume = p[CALL_VOLUME] ?: 1.0f,
        volume = p[VOLUME] ?: 1.0f,
        musicLevel = p[MUSIC_LEVEL] ?: 1.0f,
        directionsLevel = p[DIRECTIONS_LEVEL] ?: 1.0f,
        systemLevel = p[SYSTEM_LEVEL] ?: 1.0f,
    )

    private companion object {
        val ENABLED = booleanPreferencesKey("aa_enabled")
        val VIDEO_MODE = stringPreferencesKey("aa_video_mode")
        val FPS60 = booleanPreferencesKey("aa_fps60")
        val DPI = intPreferencesKey("aa_dpi")
        val NIGHT = stringPreferencesKey("aa_night_source")
        val SIDE = stringPreferencesKey("aa_driver_side")
        val AUDIO = booleanPreferencesKey("aa_audio")
        val MIC = booleanPreferencesKey("aa_microphone")
        val DUCK = booleanPreferencesKey("aa_duck_media")
        val ECHO = booleanPreferencesKey("aa_echo_cancel")
        val CALL_VOLUME = floatPreferencesKey("aa_call_volume")
        val VOLUME = floatPreferencesKey("aa_volume")
        val MUSIC_LEVEL = floatPreferencesKey("aa_music_level")
        val DIRECTIONS_LEVEL = floatPreferencesKey("aa_directions_level")
        val SYSTEM_LEVEL = floatPreferencesKey("aa_system_level")
    }
}
