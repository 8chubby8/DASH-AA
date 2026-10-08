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
import com.dash.android.display.DisplayFeatures
import com.dash.android.display.NightLight
import com.dash.android.display.Overscan
import com.dash.android.display.RgbRange
import com.dash.android.display.Screen
import com.dash.android.display.ScreenMode
import com.dash.android.display.linux.LinuxDisplay
import com.dash.android.display.linux.ScreenBackend
import com.dash.android.display.linux.Snapshot
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Opt-in (-Dscreenshots=1): each Display tab drawn headlessly against two pretend screens — a laptop
 * panel and a new HDMI monitor — so the tabs can be seen without touching a real screen.
 */
class DisplayScreenshots {
    private class Pretend : ScreenBackend {
        override val program = "Pretend"
        private val fhd = ScreenMode("2880x1800@120", 2880, 1800, 120.0, true)
        private val modes = listOf(fhd, ScreenMode("2880x1800@60", 2880, 1800, 60.0), ScreenMode("1920x1200@120", 1920, 1200, 120.0))
        private val hd = ScreenMode("1920x1080@60", 1920, 1080, 60.0, true)
        var screens = listOf(
            Screen("eDP-1", "Built-in display", "SDC|panel|1", true, true, true, width = 1728, height = 1080, scale = 1.6667,
                scales = listOf(1.0, 1.25, 1.5, 1.6667, 2.0), modes = modes, mode = fhd, vrr = false, overscan = Overscan(0, false),
                rgbRange = RgbRange.AUTO, brightness = 0.7f),
            Screen("HDMI-A-1", "Dell U2415", "DEL|U2415|7", false, true, false, x = 1728, width = 1920, height = 1080,
                modes = listOf(hd, ScreenMode("1280x720@60", 1280, 720, 60.0)), mode = hd, hdr = false, rgbRange = RgbRange.LIMITED,
                overscan = Overscan(0, true)),
        )
        override fun read() = Snapshot(screens, DisplayFeatures(arrange = true, mirror = true, nightLight = NightLight(false, 4000), blank = true, touchMapping = true))
        override fun write(target: List<Screen>, now: Snapshot): String? { screens = target; return null }
        override fun watch(onChange: () -> Unit) = AutoCloseable { }
    }

    @Test fun `draw DASH turning its own picture`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val home = createTempDirectory("dash-aa-turned").toFile()
        val app = DashApplication(home)
        app.network = com.dash.android.connections.PretendNetwork(); app.bluetooth = com.dash.android.connections.PretendBluetooth(); app.bluetoothMusic = {}
        app.display = LinuxDisplay(File(home, "display"), { emptyList() })
        app.onCreate()
        for ((name, turns) in listOf("portrait" to 1, "landscape-reversed" to 2)) {
            val scene = ImageComposeScene(1920, 1080, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    com.dash.android.ui.rotation.TurnedWindow(turns) { com.dash.android.ui.screen.MainScreen(isColdBoot = false) }
                }
            }
            val end = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
            val file = File(out, "turned-$name.png")
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote ${file.absolutePath}")
            scene.close()
        }
    }

    @Test fun `draw the Display tabs`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val home = createTempDirectory("dash-aa-display").toFile()
        val app = DashApplication(home)
        app.network = com.dash.android.connections.PretendNetwork(); app.bluetooth = com.dash.android.connections.PretendBluetooth(); app.bluetoothMusic = {}
        app.display = LinuxDisplay(File(home, "display"), { listOf(Pretend()) })
        app.onCreate()
        Thread.sleep(1500)
        val subs = DASH_SETTINGS_TREE.first { it.id == "display" }.subs
        for (sub in subs) {
            val scene = ImageComposeScene(1100, 1500, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    val theme = LocalDashTheme.current
                    Box(Modifier.fillMaxSize().background(theme.backgroundColourSecondary).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        SettingsContent(sub)
                    }
                }
            }
            val end = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
            val file = File(out, "display-${sub.id.substringAfter('.')}.png")
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote ${file.absolutePath}")
            scene.close()
        }
    }
}
