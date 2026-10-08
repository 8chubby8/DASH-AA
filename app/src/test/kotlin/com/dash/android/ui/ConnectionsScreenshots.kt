package com.dash.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.connections.PairingRequest
import com.dash.android.connections.PretendBluetooth
import com.dash.android.connections.PretendNetwork
import com.dash.android.ui.connections.PairingPrompt
import com.dash.android.ui.keyboard.DashKeyboard
import com.dash.android.ui.keyboard.KeyboardOverlay
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): every Connections tab, the keyboard and the pairing question, against pretend networks. */
class ConnectionsScreenshots {
    private fun app(request: PairingRequest? = null): DashApplication {
        val app = DashApplication(createTempDirectory("dash-aa-connections").toFile())
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(request)
        app.bluetoothMusic = {}
        app.onCreate()
        return app
    }

    private fun draw(name: String, w: Int, h: Int, app: DashApplication, content: @androidx.compose.runtime.Composable () -> Unit) {
        val out = File("build/screenshots").apply { mkdirs() }
        val scene = ImageComposeScene(w, h, Density(1.5f)) { CompositionLocalProvider(LocalContext provides app) { content() } }
        val end = System.currentTimeMillis() + 1200
        while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
        val file = File(out, "$name.png")
        file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${file.absolutePath}")
        scene.close()
    }

    @Test fun `draw the Connections tabs`() {
        if (System.getProperty("screenshots") == null) return
        val app = app()
        Thread.sleep(800)
        for (sub in DASH_SETTINGS_TREE.first { it.id == "connections" }.subs) {
            draw("connections-${sub.id.substringAfter('.')}", 1100, 2600, app) {
                val theme = LocalDashTheme.current
                Box(Modifier.fillMaxSize().background(theme.backgroundColourSecondary).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    SettingsContent(sub)
                }
            }
        }
    }

    @Test fun `draw the keyboard and the pairing question`() {
        if (System.getProperty("screenshots") == null) return
        val app = app(PairingRequest.Confirm("94:45:60:56:B3:CA", "Rogers Phone", "482913"))
        draw("connections-pairing", 1600, 900, app) {
            val theme = LocalDashTheme.current
            Box(Modifier.fillMaxSize().background(theme.backgroundColourPrimary)) { PairingPrompt() }
        }
        val app2 = app(PairingRequest.EnterPin("94:54:C5:74:AA:7A", "D.A.S.H-Climate"))
        DashKeyboard.open(DashKeyboard.Target("PIN", mutableStateOf("12"), secret = false, digits = true) {})
        draw("connections-pin-keyboard", 1600, 900, app2) {
            val theme = LocalDashTheme.current
            Box(Modifier.fillMaxSize().background(theme.backgroundColourPrimary)) {
                PairingPrompt()
                KeyboardOverlay(Modifier.align(Alignment.BottomCenter))
            }
        }
        DashKeyboard.open(DashKeyboard.Target("Password for BT-7XK2", mutableStateOf("hunter2"), secret = true) {})
        draw("connections-keyboard", 1600, 900, app2) {
            val theme = LocalDashTheme.current
            Box(Modifier.fillMaxSize().background(theme.backgroundColourPrimary)) { KeyboardOverlay(Modifier.align(Alignment.BottomCenter)) }
        }
        DashKeyboard.close()
    }
}
