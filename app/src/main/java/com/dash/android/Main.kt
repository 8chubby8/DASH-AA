package com.dash.android

import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.dash.android.system.DesktopScale
import com.dash.android.ui.screen.MainScreen

/**
 * DASH-AA's entry point — where upstream has `MainActivity` and the launcher intent filters.
 *
 * **The process comes first, then the window.** [DashApplication] is created and its module bus and
 * Android Auto host started *before* any window exists, the desktop equivalent of upstream starting the
 * bus in `Application.onCreate` so a process with no screen yet (the boot receiver on a head unit) still
 * brings modules up. The window is a view onto that, never its owner.
 *
 * **Sized for the desktop's own scale** — see [DesktopScale]; `DASH_SCALE=1.5` overrides it.
 *
 * **Full screen by default**, because DASH-AA is the car's whole screen the way upstream is the Android
 * home screen. `DASH_WINDOWED=1` gives an ordinary window for the bench. Keys: **F11** toggles full
 * screen, **Ctrl+Q** quits — the desktop's way out, replacing upstream's Android Power/launcher controls.
 */
fun main() {
    val app = DashApplication()
    app.onCreate()
    Log.i("DASH", "DASH-AA ${BuildConfig.VERSION_NAME} (mirrors DASH ${BuildConfig.UPSTREAM_VERSION}) — data in ${app.filesDir.parentFile}")

    val windowed = System.getenv("DASH_WINDOWED") != null
    val scale = DesktopScale.detect()
    application {
        val state = rememberWindowState(
            placement = if (windowed) WindowPlacement.Floating else WindowPlacement.Fullscreen,
            size = DpSize(1280.dp, 720.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "DASH-AA",
            state = state,
            onKeyEvent = { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.F11 -> {
                        state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                            else WindowPlacement.Fullscreen
                        true
                    }
                    e.isCtrlPressed && e.key == Key.Q -> { exitApplication(); true }
                    else -> false
                }
            },
        ) {
            // The desktop's scale, applied as density so a dp is the same physical size it is on
            // Android (see DesktopScale). MainScreen then takes over font scale, as upstream does.
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides app,
                LocalDensity provides Density(base.density * scale, base.fontScale),
            ) {
                MainScreen(isColdBoot = true, window = window)
            }
        }
    }
}
