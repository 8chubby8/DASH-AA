package com.dash.android.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dash.android.density.DensityPreset
import com.dash.android.ui.motion.TransitionId
import com.dash.android.ui.motion.TransitionSpeed
import com.dash.android.ui.scale.DASH_TEXT_SCALE_DEFAULT
import com.dash.android.ui.splash.SPLASH_DWELL_DEFAULT_MS
import com.dash.android.ui.splash.SplashCrop
import com.dash.android.ui.splash.decodeSplashCrop
import com.dash.android.ui.modulepanel.ModulePanelConfig
import com.dash.android.ui.systembar.SystemBarConfig
import com.dash.android.ui.theme.SPLASH_BACKGROUND_COLOUR_DEFAULT
import com.dash.android.weather.ManualLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dash_prefs")

class DashPreferences(private val context: Context) {

    // Lenient on purpose: unknown keys are ignored and defaults are written, so a config saved by
    // an older or newer DASH build still decodes rather than throwing.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val densityKey = stringPreferencesKey("density_preset")
    private val textScaleKey = floatPreferencesKey("dash_text_scale")
    private val autoRotateKey = booleanPreferencesKey("auto_rotate")
    private val lockedOrientationKey = stringPreferencesKey("locked_orientation")
    // Splash mode: COLOUR, IMAGE (a still) or ANIMATION (a GIF / animated WebP that plays).
    private val splashModeKey = stringPreferencesKey("splash_mode")
    // The splash background is its own independent colour — the BackgroundColourSplash token (roadmap
    // 1.5.6). It stores a raw ARGB value, seedable from a theme token as a preset but free to be any
    // colour. Deliberately independent: the splash does not inherit the theme, it is the user's own.
    private val splashColourKey = longPreferencesKey("splash_bg_colour")
    // Separate file slots for the still image and the animation, so switching mode remembers each pick.
    private val splashImageUriKey = stringPreferencesKey("splash_image_uri")
    private val splashAnimationUriKey = stringPreferencesKey("splash_animation_uri")
    // How long the splash holds fully visible between the fade in and the fade out, in ms. The two
    // fades either side are transitions (Appearance › Transitions); this dwell is the splash's own.
    // COLOUR and IMAGE use it; ANIMATION ignores it — the animation's own length is its duration.
    private val splashDwellKey = intPreferencesKey("splash_dwell_millis")
    private val serialBufferKey = intPreferencesKey("serial_buffer_lines")
    // Per-orientation image crops (roadmap 1.5.6 Phase 2), each encoded "zoom,panX,panY". A tall slice
    // and a wide slice of the same picture cannot come from one rectangle, so portrait and landscape
    // are stored apart.
    private val splashCropPortraitKey = stringPreferencesKey("splash_crop_portrait")
    private val splashCropLandscapeKey = stringPreferencesKey("splash_crop_landscape")
    private val systemBarKey = stringPreferencesKey("system_bar_config")
    private val modulePanelKey = stringPreferencesKey("module_panel_config")
    // Which module's panel was on screen when DASH last ran (roadmap 1.6.8). **Deliberately not part
    // of ModulePanelConfig**, which holds the user's *settings* — this is DASH remembering where it
    // was, written on every tab press, and folding it in would have the settings page and the tab bar
    // writing the same record from two places.
    private val modulePanelModuleKey = stringPreferencesKey("module_panel_last_module")
    private val manualLocationKey = stringPreferencesKey("manual_location")

    private fun transitionKey(id: TransitionId) = stringPreferencesKey("transition_" + id.key)

    val densityPreset: Flow<DensityPreset?> = context.dataStore.data.map { prefs ->
        prefs[densityKey]?.let { name -> DensityPreset.entries.find { it.name == name } }
    }


    val dashTextScale: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[textScaleKey] ?: DASH_TEXT_SCALE_DEFAULT
    }

    /**
     * How many wire lines the Serial Monitor keeps (roadmap 1.5.12). A *user* setting rather than a
     * constant because the right answer depends entirely on how busy their bus is, which DASH cannot
     * know: one door module on a bench and twenty modules in a vehicle want very different buffers —
     * at twenty modules reporting ten times a second, 500 lines is about two and a half seconds.
     */
    val serialBufferLines: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[serialBufferKey] ?: SERIAL_BUFFER_DEFAULT
    }

    val autoRotate: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[autoRotateKey] ?: true
    }

    val lockedOrientation: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lockedOrientationKey] ?: "LANDSCAPE"
    }

    val splashMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[splashModeKey] ?: "COLOUR"
    }

    val splashBackgroundColour: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[splashColourKey] ?: SPLASH_BACKGROUND_COLOUR_DEFAULT
    }

    val splashImageUri: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[splashImageUriKey] ?: ""
    }

    val splashAnimationUri: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[splashAnimationUriKey] ?: ""
    }

    val splashDwellMillis: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[splashDwellKey] ?: SPLASH_DWELL_DEFAULT_MS
    }

    val splashCropPortrait: Flow<SplashCrop> = context.dataStore.data.map { prefs ->
        decodeSplashCrop(prefs[splashCropPortraitKey])
    }

    val splashCropLandscape: Flow<SplashCrop> = context.dataStore.data.map { prefs ->
        decodeSplashCrop(prefs[splashCropLandscapeKey])
    }

    val systemBarConfig: Flow<SystemBarConfig> = context.dataStore.data.map { prefs ->
        prefs[systemBarKey]
            ?.let { runCatching { json.decodeFromString<SystemBarConfig>(it) }.getOrNull() }
            ?: SystemBarConfig.default()
    }

    /**
     * The module panel's configuration (roadmap 1.6.3). Holds the user's docking *preference* — a
     * collision with the system bar displaces the panel at render time without ever rewriting what
     * is stored here, so their choice comes back when the bar moves away.
     */
    val modulePanelConfig: Flow<ModulePanelConfig> = context.dataStore.data.map { prefs ->
        prefs[modulePanelKey]
            ?.let { runCatching { json.decodeFromString<ModulePanelConfig>(it) }.getOrNull() }
            ?: ModulePanelConfig.default()
    }

    /**
     * The module whose panel was last on screen (roadmap 1.6.8).
     *
     * **A head unit restarts every ignition cycle**, so "the panel you left it on" is a thing DASH
     * has to remember deliberately or lose several times a day. Null before anything has been
     * chosen, and a stored id that is no longer installed — or can no longer fill the selected slot
     * — is simply not found among the candidates and falls back to the first of them.
     */
    val modulePanelLastModule: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[modulePanelModuleKey]
    }

    /**
     * The per-transition speeds (roadmap 1.5.5). Every [TransitionId] resolves to a stored speed or
     * its own default, so the map is always complete. A speed is stored by name, so relabelling the
     * millis of a preset never orphans a saved choice.
     */
    val transitions: Flow<Map<TransitionId, TransitionSpeed>> = context.dataStore.data.map { prefs ->
        TransitionId.entries.associateWith { id ->
            prefs[transitionKey(id)]
                ?.let { name -> runCatching { TransitionSpeed.valueOf(name) }.getOrNull() }
                ?: id.default
        }
    }

    /** A user-pinned place for the weather scene, or null when location is automatic (GPS/IP). */
    val manualLocation: Flow<ManualLocation?> = context.dataStore.data.map { prefs ->
        prefs[manualLocationKey]
            ?.let { runCatching { json.decodeFromString<ManualLocation>(it) }.getOrNull() }
    }

    suspend fun saveDensityPreset(preset: DensityPreset) {
        context.dataStore.edit { it[densityKey] = preset.name }
    }


    suspend fun saveDashTextScale(scale: Float) {
        context.dataStore.edit { it[textScaleKey] = scale }
    }

    suspend fun saveSerialBufferLines(lines: Int) {
        context.dataStore.edit { it[serialBufferKey] = lines }
    }

    suspend fun saveAutoRotate(auto: Boolean) {
        context.dataStore.edit { it[autoRotateKey] = auto }
    }

    suspend fun saveLockedOrientation(orientation: String) {
        context.dataStore.edit { it[lockedOrientationKey] = orientation }
    }

    suspend fun saveSplashMode(mode: String) {
        context.dataStore.edit { it[splashModeKey] = mode }
    }

    suspend fun saveSplashBackgroundColour(colour: Long) {
        context.dataStore.edit { it[splashColourKey] = colour }
    }

    suspend fun saveSplashImageUri(uri: String) {
        context.dataStore.edit { it[splashImageUriKey] = uri }
    }

    suspend fun saveSplashAnimationUri(uri: String) {
        context.dataStore.edit { it[splashAnimationUriKey] = uri }
    }

    suspend fun saveSplashDwellMillis(millis: Int) {
        context.dataStore.edit { it[splashDwellKey] = millis }
    }

    suspend fun saveSplashCrop(landscape: Boolean, crop: SplashCrop) {
        context.dataStore.edit {
            it[if (landscape) splashCropLandscapeKey else splashCropPortraitKey] = crop.encode()
        }
    }

    suspend fun saveSystemBarConfig(config: SystemBarConfig) {
        context.dataStore.edit { it[systemBarKey] = json.encodeToString(config) }
    }

    suspend fun saveModulePanelConfig(config: ModulePanelConfig) {
        context.dataStore.edit { it[modulePanelKey] = json.encodeToString(config) }
    }

    suspend fun saveModulePanelLastModule(id: String) {
        context.dataStore.edit { it[modulePanelModuleKey] = id }
    }

    /** Sets one transition's speed — the per-transition breakout. Diverging from the rest makes the
     *  master read "Custom". */
    suspend fun setTransition(id: TransitionId, speed: TransitionSpeed) {
        context.dataStore.edit { it[transitionKey(id)] = speed.name }
    }

    /** The master pace: writes every transition to one speed in a single edit. */
    suspend fun setAllTransitions(speed: TransitionSpeed) {
        context.dataStore.edit { prefs ->
            TransitionId.entries.forEach { prefs[transitionKey(it)] = speed.name }
        }
    }

    /** Clears the stored bar config so it falls back to [SystemBarConfig.default]. */
    suspend fun resetSystemBar() {
        context.dataStore.edit { it.remove(systemBarKey) }
    }

    suspend fun saveManualLocation(location: ManualLocation) {
        context.dataStore.edit { it[manualLocationKey] = json.encodeToString(location) }
    }

    /** Clears the pinned place so the weather scene returns to automatic (GPS → IP) location. */
    suspend fun clearManualLocation() {
        context.dataStore.edit { it.remove(manualLocationKey) }
    }
}

/** The Serial Monitor's default line buffer — enough to read an install handshake on a quiet bench. */
const val SERIAL_BUFFER_DEFAULT = 500

/**
 * The buffer sizes the Serial Monitor offers.
 *
 * The spread is deliberately wide because bus traffic is: at twenty modules reporting ten times a
 * second, 50 lines is a quarter of a second and 5,000 is under half a minute, while on a bench with one
 * board 5,000 lines is hours. Small is for watching a single exchange without pressing CLEAR first;
 * large is for catching something intermittent. Neither is the "right" answer, which is exactly why it
 * is the user's choice and not a constant.
 */
val SERIAL_BUFFER_OPTIONS = listOf(50, 200, 500, 1000, 5000)
