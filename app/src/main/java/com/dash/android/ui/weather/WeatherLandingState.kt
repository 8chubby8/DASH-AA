package com.dash.android.ui.weather

import androidx.compose.runtime.compositionLocalOf
import com.dash.android.weather.WeatherSnapshot

/**
 * The current weather snapshot, resolved once and cached at the composition root (roadmap 1.5.5) and
 * provided here so the settings landing scene opens on **real weather** rather than flashing through
 * the clock-only floor while a fetch runs. It is warmed at app start and refreshed each time settings
 * opens, both off the main thread — the panel never waits on the network.
 *
 * `null` means "not fetched yet"; the landing falls back to [WeatherSnapshot.clockOnly] for that first
 * moment only (a settings open within a second or two of a cold boot, before the warm-up returns).
 */
val LocalWeatherSnapshot = compositionLocalOf<WeatherSnapshot?> { null }
