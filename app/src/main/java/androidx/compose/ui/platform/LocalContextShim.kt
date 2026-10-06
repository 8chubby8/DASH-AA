package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * DASH-AA platform shim — `LocalContext`, which Compose provides on Android and not on the desktop.
 *
 * Fifteen upstream composables read it, almost all to build a `DashPreferences(context)`. Providing
 * it once at the window root (Main.kt) lets every one of them compile and behave unchanged.
 */
val LocalContext = staticCompositionLocalOf<Context> {
    error("LocalContext not provided — Main.kt provides the DashApplication at the window root.")
}
