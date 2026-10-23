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
import com.dash.android.aa.AaPreferences
import com.dash.android.aa.media.VideoColour
import com.dash.android.aa.media.VideoFrame
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): each Android Auto tab, and the frame counter over a projected picture (1.2.4). */
class AndroidAutoScreenshots {
    @Test fun `draw the Android Auto tabs and the frame counter`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val home = createTempDirectory("dash-aa").toFile()
        val app = DashApplication(home)
        app.network = com.dash.android.connections.PretendNetwork(); app.bluetooth = com.dash.android.connections.PretendBluetooth(); app.bluetoothMusic = {}
        app.onCreate()
        runBlocking { AaPreferences(app).update { it.copy(showFps = true) } }
        for (sub in DASH_SETTINGS_TREE.first { it.id == "androidauto" }.subs) {
            val scene = ImageComposeScene(1100, 1200, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    val theme = LocalDashTheme.current
                    Box(Modifier.fillMaxSize().background(theme.backgroundColourSecondary).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        SettingsContent(sub)
                    }
                }
            }
            val end = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
            File(out, "androidauto-${sub.id.substringAfter('.')}.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }

        // A grey frame, as if projecting, with the counter on: it must sit top left, clear and large.
        val scene = ImageComposeScene(1920, 1080, Density(1.5f)) {
            CompositionLocalProvider(LocalContext provides app) { com.dash.android.ui.screen.MainScreen(isColdBoot = false) }
        }
        val g = app.androidAuto.geometryForViewport()
        val w = g.mode.width; val h = g.mode.height
        app.androidAuto.debugShowFrame(g, VideoFrame.make(w, h, ByteArray(w * h) { 110.toByte() }, ByteArray(w * h / 2) { 128.toByte() }, VideoColour.BT601_LIMITED))
        val end = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
        File(out, "androidauto-fps-counter.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
