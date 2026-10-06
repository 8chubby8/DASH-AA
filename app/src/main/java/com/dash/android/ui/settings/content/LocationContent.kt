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
import com.dash.android.prefs.DashPreferences
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
 * System › Location (roadmap 1.5.4). The two controls that let a user decide *how DASH knows where
 * they are* for the weather scene — and, later, anything else that wants a fix.
 *
 * - ~~**Use device location**~~ — *DASH-AA: dropped with the Android tabs (Roger, 2026-10-05).* It
 *   asked Android for its location permission; the laptop has no such fix to grant, so the cascade
 *   runs manual pin → IP → clock.
 * - **Manual location** — a place the user types, resolved once via the geocoder and pinned at the
 *   very top of the cascade, overriding GPS and IP both.
 *
 * Nothing here is required: with everything untouched the scene still works from IP, and offline it
 * still works from the clock. These controls only ever sharpen it.
 */
@Composable
fun LocationContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { DashPreferences(context) }
    val provider = remember { WeatherProvider(context) }
    val manual by prefs.manualLocation.collectAsState(initial = null)

    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))

    Column(verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Location")

        ManualLocationBlock(
            current = manual?.name,
            onResolve = { query, onResult ->
                scope.launch {
                    val found = provider.geocodeCity(query)
                    if (found != null) prefs.saveManualLocation(found)
                    onResult(found != null)
                }
            },
            onClear = { scope.launch { prefs.clearManualLocation() } },
        )
    }
}

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
