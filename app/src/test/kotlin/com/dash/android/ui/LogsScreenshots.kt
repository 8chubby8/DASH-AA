package com.dash.android.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.dash.android.DashApplication
import com.dash.android.connections.PretendBluetooth
import com.dash.android.connections.PretendNetwork
import com.dash.android.logs.LogBook
import com.dash.android.power.PretendPower
import com.dash.android.ui.logs.StoppedNotice
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): Developer › Logs with a run going and one that stopped unexpectedly, and the notice. */
class LogsScreenshots {
    @Test fun `draw Logs`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val home = createTempDirectory("dash-logs-ui").toFile()
        File(home, "logs").mkdirs()
        File(home, "logs/run-20261021-091200.log").writeText("# DASH 1.1.9 started 2026-10-21 09:12:00.000\n2026-10-21 09:40:12.345 I DashAaUsb: phone found\n")
        LogBook.capture()
        val app = DashApplication(home)
        LogBook.start(home)
        app.network = PretendNetwork()
        app.bluetooth = PretendBluetooth(null)
        app.bluetoothMusic = {}
        app.power = PretendPower()
        app.onCreate()
        Log.i("DashAaUsb", "18d1:4ee7 asked to switch (AOA v2): ok")
        Log.i("DashAaSession", "phone speaks Android Auto 1.7 (status 0)")
        Log.w("DashAaAudio", "media: the sound system is not taking audio — carrying on in silence")
        Log.i("DashBtSpp", "connect to D.A.S.H-Climate (94:54:C5:74:AA:7A) failed: rfcomm connect failed (errno 112)")
        Log.i("DashWeather", "located Swindon (51.5558, -1.7797)")
        Log.e("DashSoundChain", "car sound not applied: pw-cli timed out")
        Log.i("DashNetwork", "client 2 connected from 192.168.8.20")
        Thread.sleep(300)

        fun shot(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(w, h, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    Box(Modifier.fillMaxSize().background(LocalDashTheme.current.backgroundColourSecondary)) { content() }
                }
            }
            val end = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
            File(out, "logs-$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote logs-$name.png")
            scene.close()
        }
        val sub = DASH_SETTINGS_TREE.first { it.id == "developer" }.subs.first { it.id == "developer.logs" }
        shot("dash", 2000, 1100) { SettingsContent(sub) }
        shot("notice", 1200, 500) { StoppedNotice {} }
    }
}
