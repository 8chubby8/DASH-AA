package com.dash.android.display

import com.dash.android.ui.rotation.DashOrientation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * The machine's screens, as DASH's Display tabs see them (DASH-AA 1.1.5): which are on and where, their
 * resolution, refresh, colour and brightness, which way they face, and turning them dark.
 *
 * **The seam between DASH and the platform**, as [com.dash.android.audio.SoundSystem] is for sound.
 * The Display tabs only ever talk to this interface. DASH-AA's implementation asks whichever display
 * program is running — GNOME's Mutter on the laptop, KDE's KWin, or a wlroots one such as labwc or
 * sway (`display/linux/`) — because on Linux the display program owns the screens, and asking it keeps
 * every other program, and the touchscreen, in step. **For native:** Android owns its one screen;
 * native's implementation is `requestedOrientation` for rotation, the window's brightness for
 * brightness, and reports everything else absent, so those controls do not appear.
 *
 * **Capability detection, as everywhere.** A screen's optional settings are null when this display
 * program cannot change them on that screen, and the tabs leave them out; [DisplayFeatures] says the
 * same for what is not per screen. With no display program that answers, [DisplayState.available] is
 * false, [DisplayState.note] says why in plain words, and nothing else is affected.
 *
 * Every call returns at once; the work happens off the caller's thread, and [state] shows the result as
 * the display program reports it.
 */
interface DisplaySystem {
    val state: StateFlow<DisplayState>

    fun start()

    /**
     * Turn the main screen to [orientation], or with null put it back the way DASH found it — what Auto
     * means on a machine with no tilt sensor.
     */
    fun rotate(orientation: DashOrientation?)

    /**
     * Set the screens up as [screens] describes — whole, as the Screens tab edits them: each one's
     * on/off, main, place, resolution and refresh, scale, mirroring, adaptive sync, overscan and colour.
     * Screens not listed are left as they are. With [keep] false the change is tried but not remembered —
     * the "Keep this?" countdown; the same call with [keep] true then keeps it.
     */
    fun arrange(screens: List<Screen>, keep: Boolean = true)

    /** Set [screenId]'s brightness, 0–1 (0 is the dimmest the screen goes, never off). */
    fun setBrightness(screenId: String, level: Float)

    /** Warm the colours for night, at [kelvin], or not. */
    fun setNightLight(on: Boolean, kelvin: Int)

    /** Turn every screen's picture off (blanked) or back on — the screen-blanking timer's job. */
    fun setScreensOn(on: Boolean)

    /** Point touchscreen [deviceId] at [screenId]. */
    fun mapTouchscreen(deviceId: String, screenId: String)

    /** Put the screens back the way DASH found them, and wait until it is done. For leaving DASH. */
    fun restore()
}

data class DisplayState(
    val available: Boolean,
    val note: String,
    /** The display program DASH is talking to, in plain words: "GNOME", "KDE Plasma (KWin)", "labwc". */
    val program: String? = null,
    val screens: List<Screen> = emptyList(),
    val features: DisplayFeatures = DisplayFeatures(),
    val touchscreens: List<Touchscreen> = emptyList(),
    /** Screens DASH has not been told what to do with yet — plugged in for the first time. By [Screen.id]. */
    val unfamiliar: Set<String> = emptySet(),
    /** The last change that did not happen, in plain words; null when the last one did. */
    val failure: String? = null,
    /**
     * With no display program that can turn the screen: the way DASH turns its own picture instead, inside
     * its window (Rotation's choice, null for as it is). Other programs, and the desktop, stay as they are.
     */
    val ownOrientation: DashOrientation? = null,
) {
    /** The main screen — the one DASH is on. */
    val main: Screen? get() = screens.firstOrNull { it.primary && it.enabled } ?: screens.firstOrNull { it.enabled }

    /** Which way the main screen faces, for the Rotation tab. */
    val orientation: DashOrientation? get() = main?.orientation ?: ownOrientation
}

/** What the display program can do beyond any one screen. */
data class DisplayFeatures(
    /** Screens can be turned on and off, moved, and made the main one. */
    val arrange: Boolean = false,
    /** One screen can show the same as another. */
    val mirror: Boolean = false,
    /** Every screen must use the same scale. */
    val oneScale: Boolean = false,
    val nightLight: NightLight? = null,
    /** The picture can be turned off and on again (blanking) through the display program. */
    val blank: Boolean = false,
    /** A touchscreen can be pointed at a chosen screen. */
    val touchMapping: Boolean = false,
)

data class NightLight(val on: Boolean, val kelvin: Int)

@Serializable
data class Screen(
    /** The display program's name for it — the connector: `eDP-1`, `HDMI-A-1`. */
    val id: String,
    /** What the user calls it: "Built-in display", or the maker and model. */
    val name: String,
    /** Who it is, unchanged by which socket it is in: maker, model and serial. DASH remembers screens by it. */
    val identity: String,
    val builtin: Boolean,
    val enabled: Boolean,
    val primary: Boolean,
    val x: Int = 0,
    val y: Int = 0,
    /** Its place on the desktop, after scaling and turning — what the arrangement picture draws. */
    val width: Int = 0,
    val height: Int = 0,
    val scale: Double = 1.0,
    /** The scales this screen can take at its current resolution; empty when any is allowed. */
    val scales: List<Double> = emptyList(),
    /** Anticlockwise quarter turns (0–3), and mirroring — the display program's own transform. */
    val quarterTurns: Int = 0,
    val flipped: Boolean = false,
    /** True when the panel is taller than it is wide before any turn. */
    val naturalPortrait: Boolean = false,
    val modes: List<ScreenMode> = emptyList(),
    val mode: ScreenMode? = null,
    /** The screen this one shows the same picture as; null when it shows its own. */
    val mirrorOf: String? = null,
    /** Adaptive sync (VRR): on or off, or null when this screen or display program cannot. */
    val vrr: Boolean? = null,
    /** Shrinking the picture to undo a screen that crops its edges, or null when not offered. */
    val overscan: Overscan? = null,
    /** HDR on or off, or null when the screen cannot. */
    val hdr: Boolean? = null,
    /** Full or limited colour range, or null when not offered. */
    val rgbRange: RgbRange? = null,
    /** 0–1, or null when DASH cannot set this screen's brightness. */
    val brightness: Float? = null,
) {
    val orientation: DashOrientation get() = orientationOf(quarterTurns, naturalPortrait)
}

@Serializable
data class ScreenMode(
    /** The display program's own name for it, passed back unchanged. */
    val id: String,
    val width: Int,
    val height: Int,
    /** Times a second, as the screen reports it (59.94, 120.0). */
    val refresh: Double,
    val preferred: Boolean = false,
) {
    val resolution: String get() = "$width × $height"
}

/** [percent] of the edge given back. On GNOME it is on or off ([adjustable] false), and on is GNOME's own amount. */
@Serializable
data class Overscan(val percent: Int, val adjustable: Boolean)

enum class RgbRange(val label: String) { AUTO("Automatic"), FULL("Full"), LIMITED("Limited") }

/** A touchscreen, and the screen its touches land on. */
data class Touchscreen(
    /** The kernel's device, `/dev/input/event7`. */
    val id: String,
    val name: String,
    /** Its USB identity, `0eef:0001`, by which the display program remembers it. */
    val usbId: String?,
    /** The screen its touches land on, when the display program says; null when it decides by itself. */
    val screenId: String?,
)

/** Anticlockwise quarter turns from the panel's natural shape, for [o]. Landscape panels and portrait ones alike. */
fun transformFor(o: DashOrientation, naturalPortrait: Boolean): Int {
    val fromLandscape = when (o) {
        DashOrientation.LANDSCAPE -> 0
        DashOrientation.PORTRAIT -> 1
        DashOrientation.LANDSCAPE_REVERSED -> 2
        DashOrientation.PORTRAIT_REVERSED -> 3
    }
    return if (naturalPortrait) (fromLandscape + 3) % 4 else fromLandscape
}

fun orientationOf(quarterTurns: Int, naturalPortrait: Boolean): DashOrientation =
    when (if (naturalPortrait) ((quarterTurns and 3) + 1) % 4 else quarterTurns and 3) {
        0 -> DashOrientation.LANDSCAPE
        1 -> DashOrientation.PORTRAIT
        2 -> DashOrientation.LANDSCAPE_REVERSED
        else -> DashOrientation.PORTRAIT_REVERSED
    }
