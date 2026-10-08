package com.dash.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.dash.android.DashApplication
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.modulepanel.ModulePanelConfig
import com.dash.android.ui.modulepanel.PanelEdge
import com.dash.android.ui.modulepanel.PanelSize
import com.dash.android.ui.modulepanel.PanelVisibility
import com.dash.android.ui.screen.MainScreen
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders DASH-AA's real screen headlessly, with a module installed through the real handshake, and
 * writes PNGs to `app/build/screenshots/`. Not an assertion of looks — a way to *see* the build on a
 * machine with no display attached, and the evidence the module path works on the desktop.
 *
 * Runs only when asked: `./gradlew test --tests '*UiScreenshots*' -Dscreenshots=1`.
 */
class UiScreenshots {

    @Test
    fun `render the screen with the Climate module installed`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val app = DashApplication(createTempDirectory("dash-aa-ui").toFile())
        app.network = com.dash.android.connections.PretendNetwork(); app.bluetooth = com.dash.android.connections.PretendBluetooth(); app.bluetoothMusic = {}
        app.onCreate()
        val prefs = DashPreferences(app)
        runBlocking {
            prefs.saveModulePanelConfig(
                ModulePanelConfig(edge = PanelEdge.RIGHT, visibility = PanelVisibility.FULL, size = PanelSize.MEDIUM)
            )
        }

        // The fake Climate module dials DASH's WiFi server, exactly as the ClimateWifi sketch would.
        val module = ProcessBuilder("python3", File("../tools/fake_module.py").absolutePath)
            .redirectErrorStream(true).redirectOutput(File(out, "fake_module.log")).start()
        try {
            val id = "0000DA58AC04"
            waitFor(20_000) { app.controller.discovery.modules.value.any { it.id == id } }
            app.controller.install.install(id)
            waitFor(20_000) { app.controller.database.modules.value.containsKey(id) }
            Thread.sleep(6_000)    // reconciliation activates it; the activation dump fills the panel

            val scene = ImageComposeScene(1920, 1080, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) { MainScreen(isColdBoot = false) }
            }
            settle(scene, 3_000)
            save(scene, File(out, "1-home.png"))

            // As if a phone were projecting: a frame made the way the phone would draw it for this
            // viewport — its interface in the content area, the margins empty — shown through the
            // real viewport, which must crop the margins away and fill edge to edge.
            val g = app.androidAuto.geometryForViewport()
            val frame = File(out, "phone-frame.png")
            val p = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc2=size=${g.contentWidth}x${g.contentHeight}",
                "-vf", "pad=${g.mode.width}:${g.mode.height}:${g.contentLeft}:${g.contentTop}:color=magenta",
                "-frames:v", "1", frame.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readAllBytes(); p.waitFor()
            app.androidAuto.debugShowFrame(g, org.jetbrains.skia.Image.makeFromEncoded(frame.readBytes()))
            settle(scene, 1_000)
            save(scene, File(out, "1b-projecting.png"))

            // -Dclicks="x,y;x,y;…" — tap each point in turn and save the screen after each.
            System.getProperty("clicks")?.split(';')?.filter { it.isNotBlank() }?.forEachIndexed { i, pt ->
                val (x, y) = pt.split(',').map { it.trim().toFloat() }
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                settle(scene, 1_800)
                save(scene, File(out, "click-${i + 1}.png"))
            }
            scene.close()
        } finally {
            module.destroy()
        }
    }

    private fun settle(scene: ImageComposeScene, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
    }

    private fun save(scene: ImageComposeScene, file: File) {
        val image = scene.render(System.nanoTime())
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${file.absolutePath}")
    }

    private fun waitFor(ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            assertTrue(System.currentTimeMillis() < end, "timed out")
            Thread.sleep(100)
        }
    }
}
