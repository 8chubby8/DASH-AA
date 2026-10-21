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
import com.dash.android.power.PretendPower
import com.dash.android.time.PretendTime
import com.dash.android.time.TimeState
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): Date & Time on a machine that lets DASH change the clock, one that would want a password, and one set by hand. */
class TimeScreenshots {
    private fun app(machine: TimeState): DashApplication {
        val app = DashApplication(createTempDirectory("dash-time").toFile())
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(null)
        app.bluetoothMusic = {}
        app.power = PretendPower()
        app.time = PretendTime(machine)
        app.onCreate()
        return app
    }

    @Test fun `draw Date and Time`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        for ((name, machine) in listOf("allowed" to PretendTime.ALLOWED, "refused" to PretendTime.REFUSED, "byhand" to PretendTime.BY_HAND)) {
            val app = app(machine)
            for (sub in DASH_SETTINGS_TREE.first { it.id == "system" }.subs.filter { it.id == "system.datetime" }) {
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
                val file = File(out, "time-$name.png")
                file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("wrote ${file.absolutePath}")
                scene.close()
            }
        }
    }
}
