package com.dash.android.ui.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.splash.CroppedImage
import com.dash.android.ui.splash.LocalSplashPreview
import com.dash.android.ui.splash.SPLASH_CROP_DEFAULT
import com.dash.android.ui.splash.SPLASH_DWELL_DEFAULT_MS
import com.dash.android.ui.splash.SplashCrop
import com.dash.android.ui.splash.SplashImage
import com.dash.android.ui.splash.rememberSplashBitmap
import com.dash.android.ui.theme.LocalDashTheme
import kotlin.math.max
import kotlin.math.min
import com.dash.android.ui.theme.SPLASH_BACKGROUND_COLOUR_DEFAULT
import com.dash.android.ui.theme.splashColourPresets
import kotlinx.coroutines.launch
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.CONTROL_WIDTH
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.controlWidth

/**
 * Appearance › Splash Screen (roadmap 1.5.6). Exposes the 1.2.x splash feature: pick a **background
 * colour** (BackgroundColourSplash — the splash's own independent colour, seedable from a theme token
 * but free to be anything), a **still image**, or an **animation** (a GIF / animated WebP that plays);
 * and set how long a colour/image splash holds. The two fades either side are transitions and live in
 * Appearance › Transitions.
 *
 * Layout: type at the top, then a preview in the **real shape of the screen** so what you see is what
 * boots, then the control for the chosen type, then — for colour and image only — the timer. Pick
 * Animation and the timer disappears: the animation's own length is its duration.
 */

// Dwell is dialled in half-second steps, from an instant flash to a long hold. The floor is 0 — DASH
// has no opinion on whether you want a splash at all — and the ceiling is generous.
private const val DWELL_STEP_MS = 500
private const val DWELL_MIN_MS = 0
private const val DWELL_MAX_MS = 10_000

@Composable
fun SplashContent() {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val prefs = remember { DashPreferences(appContext) }
    val scope = rememberCoroutineScope()
    val theme = LocalDashTheme.current
    val previewFullScreen = LocalSplashPreview.current

    val mode by prefs.splashMode.collectAsState(initial = "COLOUR")
    val storedColour by prefs.splashBackgroundColour.collectAsState(initial = SPLASH_BACKGROUND_COLOUR_DEFAULT)
    val imageUri by prefs.splashImageUri.collectAsState(initial = "")
    val animationUri by prefs.splashAnimationUri.collectAsState(initial = "")
    val dwell by prefs.splashDwellMillis.collectAsState(initial = SPLASH_DWELL_DEFAULT_MS)

    // Colour edit state is held as HSV (the picker's stable source of truth), seeded from the stored
    // colour once it has loaded. After that the user's edits own it; preset taps re-seed it.
    var hsv by remember { mutableStateOf<Hsv?>(null) }
    // The custom editor is collapsed by default (roadmap 1.5.15) — it used to sit ~200dp tall
    // permanently, whether or not anyone was using it.
    var customOpen by remember { mutableStateOf(false) }
    LaunchedEffect(storedColour) { if (hsv == null) hsv = colourToHsv(storedColour) }
    val currentColour = hsv?.toColour() ?: Color(storedColour)

    // Per-orientation crop (Phase 2). The preview's shape follows this toggle, *not* the device's
    // current orientation — which is what made a landscape phone show a portrait preview. Default to
    // landscape, DASH's home turf. The screen's long/short dimensions come from the config either way
    // round, so max/min gives the true aspect regardless of how the device is held.
    val cropPortrait by prefs.splashCropPortrait.collectAsState(initial = SPLASH_CROP_DEFAULT)
    val cropLandscape by prefs.splashCropLandscape.collectAsState(initial = SPLASH_CROP_DEFAULT)
    var showLandscape by remember { mutableStateOf(true) }
    var editingCrop by remember { mutableStateOf(false) }
    var liveCrop by remember { mutableStateOf<SplashCrop?>(null) }
    val imageBitmap = rememberSplashBitmap(if (mode == "IMAGE") imageUri else "")
    LaunchedEffect(mode) { if (mode != "IMAGE") { editingCrop = false; liveCrop = null } }

    // DASH-AA: the window's own size stands in for Android's screen configuration — DASH-AA runs
    // full screen, so the window *is* the screen whose shape the crop is for.
    val window = LocalWindowInfo.current.containerSize
    val longDim = max(window.width, window.height).toFloat().coerceAtLeast(1f)
    val shortDim = min(window.width, window.height).toFloat().coerceAtLeast(1f)
    val previewAspect = if (showLandscape) longDim / shortDim else shortDim / longDim
    val activeCrop = if (showLandscape) cropLandscape else cropPortrait
    val displayCrop = liveCrop ?: activeCrop

    // DASH-AA: the desktop's file dialog stands in for Android's SAF picker. A path needs no
    // persistable grant — the file is simply read again at the next boot — and "image/*" still covers
    // stills and animated GIF / WebP, which Skia plays everywhere.
    val imagePicker = remember {
        DesktopImagePicker { path -> scope.launch { prefs.saveSplashImageUri(path); prefs.saveSplashMode("IMAGE") } }
    }
    val animationPicker = remember {
        DesktopImagePicker { path -> scope.launch { prefs.saveSplashAnimationUri(path); prefs.saveSplashMode("ANIMATION") } }
    }

    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Type ───────────────────────────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsContentHeader("Splash Screen")
            // Type is the section rather than a setting inside one: it has a single control, and a
            // setting named "Type" under a heading named "Type" would say the word twice.
            SettingsSectionHeader("Type")
            FitPresetSegment(
                labels = listOf("None", "Colour", "Image", "Animation"),
                selected = when (mode) {
                    "COLOUR" -> 1
                    "IMAGE" -> 2
                    "ANIMATION" -> 3
                    else -> 0
                },
            ) { i ->
                val next = when (i) { 1 -> "COLOUR"; 2 -> "IMAGE"; 3 -> "ANIMATION"; else -> "NONE" }
                scope.launch { prefs.saveSplashMode(next) }
            }
        }


        if (mode == "NONE") return@Column

        // ── Background — the splash's own colour, kept for all three types. For Colour it is the
        //    whole screen; for Image / Animation it is the backdrop and the matte behind letterboxing.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
            SettingsSectionHeader("Background")
            ColourSwatches(
                presets = splashColourPresets(theme),
                selected = currentColour,
                customOpen = customOpen,
                onPick = { picked ->
                    hsv = colourToHsv(picked.toArgbLong())
                    customOpen = false
                    scope.launch { prefs.saveSplashBackgroundColour(picked.toArgbLong()) }
                },
                onToggleCustom = { customOpen = !customOpen },
            )

            // The editor is inline and pushes what follows down, rather than floating over it — it
            // holds text fields, and a popup would fight the keyboard and have to guess its own size.
            // Same behaviour as the Serial Monitor's COMMANDS drawer (1.5.13).
            if (customOpen) {
                hsv?.let { current ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.textColourSecondary.copy(alpha = 0.06f))
                            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        ColourEditor(
                            hsv = current,
                            onChange = { hsv = it },
                            onCommit = {
                                scope.launch {
                                    prefs.saveSplashBackgroundColour(current.toColour().toArgbLong())
                                }
                            },
                        )
                    }
                }
            }
        }

        // The preview is a different kind of thing from the controls above it — it is the result, not
        // a setting — so it gets more air than the gap between two settings would give it. It was
        // sitting hard up against the colour swatches (roadmap 1.5.15, Roger).
        Spacer(Modifier.height(40.dp))

        LivePreviewCard(label = "Preview") {
            PresetSegment(
                labels = listOf("Landscape", "Portrait"),
                selected = if (showLandscape) 0 else 1,
                modifier = controlWidth,
            ) { i -> showLandscape = (i == 0); liveCrop = null }

            // The chooser lives in the preview, under the orientation selector — you pick the image
            // where you are looking at it, rather than in a block somewhere above (roadmap 1.5.15,
            // Roger). Colour needs nothing here: its background *is* the splash.
            if (mode == "IMAGE") {
                DashButton(
                    label = if (imageUri.isNotEmpty()) "Change image" else "Choose image",
                    modifier = controlWidth,
                    onClick = { imagePicker.launch("image/*") },
                )
            } else if (mode == "ANIMATION") {
                DashButton(
                    label = if (animationUri.isNotEmpty()) "Change animation" else "Choose animation",
                    modifier = controlWidth,
                    onClick = { animationPicker.launch("image/*") },
                )
            }

            val canCrop = mode == "IMAGE" && imageBitmap != null
            val enterEdit: () -> Unit = { editingCrop = true }

            if (canCrop && editingCrop) {
                SplashCropEditor(
                    bitmap = imageBitmap!!,
                    aspect = previewAspect,
                    colour = currentColour,
                    crop = displayCrop,
                    maxHeight = 380.dp,
                    onChange = { liveCrop = it },
                    onCommit = { c -> scope.launch { prefs.saveSplashCrop(showLandscape, c) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    DashButton(
                        label = "Reset to standard",
                        modifier = controlWidth,
                        onClick = {
                            liveCrop = SPLASH_CROP_DEFAULT
                            scope.launch { prefs.saveSplashCrop(showLandscape, SPLASH_CROP_DEFAULT) }
                        },
                    )
                    DashButton("Done", { editingCrop = false; liveCrop = null }, modifier = controlWidth)
                }
            } else {
                SplashAspectPreview(
                    aspect = previewAspect,
                    colour = currentColour,
                    mode = mode,
                    imageBitmap = imageBitmap,
                    animationUri = animationUri,
                    crop = displayCrop,
                    onTap = if (canCrop) enterEdit else null,
                )
                if (canCrop) DashButton("Adjust crop", { enterEdit() }, modifier = controlWidth)
                DashButton("Preview full screen", { previewFullScreen() }, modifier = controlWidth)
            }
        }


        // ── Timing (colour + image only; an animation has no dwell) ───────────────────────────────
        if (mode != "ANIMATION") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
                SettingsSectionHeader("Timing")
                SettingBlock(
                    name = "Display time",
                    control = {
                        Stepper(
                            value = formatSeconds(dwell),
                            sub = "hold",
                            modifier = controlWidth,
                            onMinus = {
                                scope.launch { prefs.saveSplashDwellMillis((dwell - DWELL_STEP_MS).coerceAtLeast(DWELL_MIN_MS)) }
                            },
                            onPlus = {
                                scope.launch { prefs.saveSplashDwellMillis((dwell + DWELL_STEP_MS).coerceAtMost(DWELL_MAX_MS)) }
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * The preview, drawn in the **current screen's aspect ratio** so it honestly represents what boots —
 * a tall box in portrait, a wide one in landscape (it reflows on rotation). Fitted within a sensible
 * height so a portrait phone doesn't hand back a full-screen-tall preview.
 */
@Composable
private fun SplashAspectPreview(
    aspect: Float,
    colour: Color,
    mode: String,
    imageBitmap: ImageBitmap?,
    animationUri: String,
    crop: SplashCrop,
    maxHeight: Dp = 240.dp,
    onTap: (() -> Unit)? = null,
) {
    val theme = LocalDashTheme.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val byHeight = maxHeight * aspect
        val w = if (byHeight <= maxWidth) byHeight else maxWidth
        val h = w / aspect
        Box(
            Modifier
                .width(w)
                .height(h)
                .clip(RoundedCornerShape(8.dp))
                .background(colour)
                .border(1.dp, theme.textColourSecondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier),
        ) {
            when {
                mode == "IMAGE" && imageBitmap != null ->
                    CroppedImage(imageBitmap, Modifier.fillMaxSize()) { crop }

                mode == "ANIMATION" && animationUri.isNotEmpty() ->
                    SplashImage(animationUri, animate = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** The preset swatches under the colour picker — theme tokens plus black and white, tap to seed. */
@Composable
private fun ColourSwatches(
    presets: List<Pair<String, Color>>,
    selected: Color,
    customOpen: Boolean,
    onPick: (Color) -> Unit,
    onToggleCustom: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val isPreset = presets.any { it.second.toArgbLong() == selected.toArgbLong() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { (_, colour) ->
            Swatch(
                colour = colour,
                selected = colour.toArgbLong() == selected.toArgbLong(),
                onClick = { onPick(colour) },
            )
        }

        // The Custom swatch (roadmap 1.5.15). It is filled with the colour actually in force, so a
        // colour that is not one of the presets can be seen without opening anything — and it reads
        // as the selected swatch in that case, which is what makes the row honest. Tapping it opens
        // the editor below; tapping again closes it, as does choosing a preset.
        Swatch(
            colour = selected,
            selected = !isPreset,
            ring = customOpen,
            onClick = onToggleCustom,
        )
        Text(
            "CUSTOM",
            color = theme.textColourSecondary.copy(alpha = if (customOpen || !isPreset) 0.95f else 0.6f),
            fontSize = TINY,
            letterSpacing = 1.sp,
            fontFamily = theme.font,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onToggleCustom() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** One colour circle. [ring] draws the extra outer ring that marks the custom editor as open. */
@Composable
private fun Swatch(colour: Color, selected: Boolean, ring: Boolean = false, onClick: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(colour)
            .border(
                width = if (selected || ring) 2.5.dp else 1.dp,
                color = if (selected || ring) theme.textColourSecondary
                else theme.textColourSecondary.copy(alpha = 0.3f),
                shape = CircleShape,
            )
            .clickable { onClick() },
    )
}

/** "0.0s", "2.5s" — dwell shown in seconds to one decimal, from a millisecond store. */
private fun formatSeconds(millis: Int): String {
    val whole = millis / 1000
    val tenths = (millis % 1000) / 100
    return "$whole.${tenths}s"
}

/**
 * DASH-AA: a picker with the shape of upstream's `rememberLauncherForActivityResult(GetContent())` —
 * `launch(mime)` opens the chooser and the callback receives what was picked — so the call sites below
 * are unchanged. The desktop dialog is modal and pumps the window's events while it is open.
 */
private class DesktopImagePicker(private val onPicked: (String) -> Unit) {
    fun launch(@Suppress("UNUSED_PARAMETER") mime: String) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a splash image", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        }
        dialog.isVisible = true
        val file = dialog.file ?: return
        onPicked(java.io.File(dialog.directory, file).absolutePath)
    }
}
