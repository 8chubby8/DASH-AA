package com.dash.android.ui.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.DashApplication
import com.dash.android.aa.AaPreferences
import com.dash.android.aa.AaSettings
import com.dash.android.location.DashFix
import com.dash.android.location.DashLocation
import com.dash.android.location.GpsFix
import com.dash.android.location.LocationMode
import com.dash.android.location.LocationPreferences
import com.dash.android.location.LocationSettings
import com.dash.android.location.LocationSource
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.display.Note
import java.util.Locale
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.weather.WeatherProvider
import kotlinx.coroutines.launch
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.CONTROL_WIDTH
import com.dash.android.ui.common.DashButton
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.controlWidth

/**
 * System › Location (roadmap 1.5.4; refreshed DASH-AA 1.1.11, Roger 2026-10-21) — *how DASH knows where it
 * is*, for the weather, Date & Time's Follow Location, the `location_*` signals, every element, and the phone.
 *
 * - **Where DASH is, live** — the place, the source, and from a GPS: position, speed, direction of travel,
 *   altitude, accuracy and satellites. What is not known right now says so.
 * - **Source** — Automatic (a GPS fix, otherwise the internet), GPS, Internet, Manual or Off. The user's
 *   choice; a GPS receiver plugged into the machine is found by itself (the USB transport recognises it).
 * - **Manual location** — a place the user types, resolved once via the geocoder.
 * - **Android Auto** — offer the phone DASH's GPS fix, as a car with its own GPS does.
 *
 * ~~**Use device location**~~ — *DASH-AA: dropped with the Android tabs (Roger, 2026-10-05).* The GPS
 * receiver is DASH-AA's device location.
 *
 * Shared code. **For native:** take as it is; native's GPS side is Android's location.
 */
@Composable
fun LocationContent() {
    val context = LocalContext.current
    val app = context.applicationContext as DashApplication
    val scope = rememberCoroutineScope()
    val prefs = remember { DashPreferences(context) }
    val locationPrefs = remember { LocationPreferences(app) }
    val aaPrefs = remember { AaPreferences(app) }
    val provider = remember { WeatherProvider(context) }
    val manual by prefs.manualLocation.collectAsState(initial = null)
    val settings by locationPrefs.settings.collectAsState(initial = LocationSettings())
    val aa by aaPrefs.settings.collectAsState(initial = AaSettings())
    val here by DashLocation.current.collectAsState()
    fun mode(m: LocationMode) { scope.launch { locationPrefs.update { it.copy(mode = m) } } }

    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Location")

        InfoRows(whereRows(here))
        when {
            settings.mode == LocationMode.OFF -> Note("Location is off: no position, and nothing is looked up. The weather shows the sky from the clock alone.")
            here.receiver == null && (settings.mode == LocationMode.GPS || settings.mode == LocationMode.AUTOMATIC) ->
                Note("No GPS receiver is plugged in. Plug one into a USB port and DASH finds it by itself — or a module can report the location.")
            here.receiver != null && here.source != LocationSource.GPS && settings.mode != LocationMode.MANUAL && settings.mode != LocationMode.NETWORK ->
                Note("The GPS has no fix yet. It needs a clear view of the sky; from cold that can take a minute or two.")
        }

        SettingsSectionHeader("Source")
        SettingBlock(
            name = "Take the location from",
            help = when (settings.mode) {
                LocationMode.AUTOMATIC -> "A GPS fix when there is one; without one — a car park, a cold start — where the internet connection is."
                LocationMode.GPS -> "A GPS only: a receiver plugged into this machine, or a module that reports the location. Without a fix, none."
                LocationMode.NETWORK -> "Where the internet connection is — usually the right town, never the street. Looked up every half hour."
                LocationMode.MANUAL -> "The place set below, always — wherever the car is."
                LocationMode.OFF -> null
            },
            fullWidthControl = true,
            control = {
                PresetSegment(LocationMode.entries.map { it.label }, settings.mode.ordinal, Modifier.fillMaxWidth()) { i -> mode(LocationMode.entries[i]) }
            },
        )

        if (settings.mode == LocationMode.MANUAL) {
            ManualLocationBlock(
                current = manual?.name,
                onResolve = { query, onResult ->
                    scope.launch {
                        val found = provider.geocodeCity(query)
                        if (found != null) prefs.saveManualLocation(found)
                        onResult(found != null)
                    }
                },
                onClear = {
                    scope.launch {
                        prefs.clearManualLocation()
                        locationPrefs.update { it.copy(mode = LocationMode.AUTOMATIC) }
                    }
                },
            )
        }

        SettingsSectionHeader("Android Auto")
        SettingBlock(
            name = "Send the GPS to the phone",
            help = "Offers the phone DASH's GPS fix, as a car with its own GPS does. The phone decides whether its maps use it; " +
                "off, or with no GPS, the phone uses its own. Takes effect the next time the phone connects.",
            control = {
                PresetSegment(listOf("Off", "On"), if (aa.carLocation) 1 else 0, controlWidth) { i ->
                    scope.launch { aaPrefs.update { it.copy(carLocation = i == 1) } }
                }
            },
        )
    }
}

/** The live readout: always where and from what; the GPS's own figures while there is a receiver. */
private fun whereRows(f: DashFix): List<Pair<String, String>> = buildList {
    val position = if (f.hasPosition) String.format(Locale.ROOT, "%.5f, %.5f", f.latitude, f.longitude) else null
    add("Where" to (f.place ?: position ?: "Not known"))
    add("From" to when (f.source) {
        LocationSource.GPS -> if (f.fix == GpsFix.FIX_3D) "GPS — 3D fix" else "GPS — 2D fix, no altitude"
        LocationSource.NETWORK -> "The internet connection — the town, roughly"
        LocationSource.MANUAL -> "The place you set"
        LocationSource.NONE -> "Nothing yet"
    })
    if (f.place != null && position != null) add("Position" to position)
    if (f.receiver != null || f.source == LocationSource.GPS) {
        val unknown = "Not known"
        add("Speed" to (f.speedKmh?.let { speed(it) } ?: unknown))
        add("Direction" to (f.headingDegrees?.let { "${compass(it)}  ${Math.round(it) % 360}°" } ?: if (f.source == LocationSource.GPS) "Not known while still" else unknown))
        add("Altitude" to (f.altitudeMetres?.let { "${Math.round(it)} m above sea level" } ?: unknown))
        add("Accuracy" to (f.accuracyMetres?.let { "About ${Math.max(1, Math.round(it))} m" } ?: unknown))
        add("Satellites" to when {
            f.satellitesUsed != null && f.satellitesInView != null -> "${f.satellitesUsed} in use, ${f.satellitesInView} in view"
            f.satellitesUsed != null -> "${f.satellitesUsed} in use"
            f.satellitesInView != null -> "${f.satellitesInView} in view"
            else -> "None yet"
        })
        add("GPS" to (f.receiver ?: "A module"))
    }
}

/** In the user's own units: miles an hour where the roads are signed in them. */
private fun speed(kmh: Double): String =
    if (Locale.getDefault().country.uppercase() in MPH_COUNTRIES) "${Math.round(kmh / 1.609344)} mph" else "${Math.round(kmh)} km/h"

private val MPH_COUNTRIES = setOf("GB", "US", "LR", "MM", "IM", "JE", "GG")

private fun compass(degrees: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((degrees + 22.5) % 360 + 360) % 360 / 45).toInt() % 8]

@Composable
private fun ManualLocationBlock(
    current: String?,
    onResolve: (String, (Boolean) -> Unit) -> Unit,
    onClear: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    var text by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    SettingBlock(
        name = "Manual location",
        control = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CityField(
                    modifier = controlWidth,
                    value = text,
                    onValueChange = { text = it; notFound = false },
                    onSubmit = {
                        val query = text.trim()
                        if (query.isNotEmpty()) {
                            searching = true
                            notFound = false
                            onResolve(query) { ok ->
                                searching = false
                                notFound = !ok
                                if (ok) text = ""
                            }
                        }
                    },
                )
                // Nothing is shown in the resting state. "Automatic (device or internet)" was
                // describing the absence of a setting, which is the same restating-the-obvious the
                // help lines were doing (roadmap 1.5.15, Roger). The line now only appears when it
                // has something to report.
                val status = when {
                    searching -> "Searching…"
                    notFound -> "No place found by that name."
                    current != null -> "Set to $current"
                    else -> null
                }
                if (status != null) {
                    Text(
                        status,
                        color = theme.textColourSecondary.copy(alpha = 0.7f),
                        fontSize = BODY,
                        fontFamily = theme.font,
                    )
                }
                if (current != null) {
                    DashButton("Use automatic", { onClear() }, modifier = controlWidth)
                }
            }
        },
    )
}

@Composable
private fun CityField(
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val theme = LocalDashTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Town or city",
                    color = theme.textColourSecondary.copy(alpha = 0.4f),
                    fontSize = BODY,
                    fontFamily = theme.font,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.textColourSecondary,
                    fontSize = BODY,
                    fontFamily = theme.font,
                ),
                cursorBrush = SolidColor(theme.textColourSecondary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Set",
            color = theme.textColourSecondary,
            fontSize = TINY,
            fontFamily = theme.font,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSubmit() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
