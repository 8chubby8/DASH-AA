package com.dash.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.connections.PretendBluetooth
import com.dash.android.connections.PretendNetwork
import com.dash.android.power.PowerState
import com.dash.android.power.PretendPower
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): every Power tab, on a pretend laptop and a pretend mini PC on the car's supply. */
class PowerScreenshots {
    private fun app(machine: PowerState): DashApplication {
        val app = DashApplication(createTempDirectory("dash-aa-power").toFile())
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(null)
        app.bluetoothMusic = {}
        app.power = PretendPower(machine)
        app.onCreate()
        return app
    }

    @Test fun `draw the Power tabs`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        for ((name, machine) in listOf("laptop" to PretendPower.LAPTOP, "minipc" to PretendPower.MINI_PC)) {
            val app = app(machine)
            for (sub in DASH_SETTINGS_TREE.first { it.id == "power" }.subs) {
                val scene = ImageComposeScene(1100, 1600, Density(1.5f)) {
                    CompositionLocalProvider(LocalContext provides app) {
                        val theme = LocalDashTheme.current
                        Box(Modifier.fillMaxSize().background(theme.backgroundColourSecondary).verticalScroll(rememberScrollState()).padding(24.dp)) {
                            SettingsContent(sub)
                        }
                    }
                }
                val end = System.currentTimeMillis() + 800
                while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
                val file = File(out, "power-$name-${sub.id.substringAfter('.')}.png")
                file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("wrote ${file.absolutePath}")
                scene.close()
            }
        }
    }
}
