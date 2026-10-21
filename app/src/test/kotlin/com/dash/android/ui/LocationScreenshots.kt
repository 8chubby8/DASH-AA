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
import com.dash.android.location.DashFix
import com.dash.android.location.DashLocation
import com.dash.android.location.GpsFix
import com.dash.android.location.LocationSource
import com.dash.android.ui.settings.DASH_SETTINGS_TREE
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Opt-in (-Dscreenshots=1): System › Location with a GPS fix, a GPS still searching, and no GPS. Invented places only. */
class LocationScreenshots {
    @Test fun `draw Location`() {
        if (System.getProperty("screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        val app = DashApplication(createTempDirectory("dash-location").toFile())
        val fixes = listOf(
            "gps" to DashFix(LocationSource.GPS, GpsFix.FIX_3D, 52.5, -1.75, 88.5, 47.0, 101.0, 3.0, 9, 13, "Testbury", "u-blox 7 - GPS/GNSS Receiver", 1),
            "searching" to DashFix(LocationSource.NETWORK, GpsFix.NONE, 52.48, -1.9, satellitesUsed = 2, satellitesInView = 7, place = "Elsewhere", receiver = "u-blox 7 - GPS/GNSS Receiver", at = 1),
            "nogps" to DashFix(LocationSource.NETWORK, GpsFix.NONE, 52.48, -1.9, place = "Elsewhere", at = 1),
        )
        for ((name, fix) in fixes) {
            DashLocation.publish(fix)
            val sub = DASH_SETTINGS_TREE.first { it.id == "system" }.subs.first { it.id == "system.location" }
            val scene = ImageComposeScene(1100, 1500, Density(1.5f)) {
                CompositionLocalProvider(LocalContext provides app) {
                    val theme = LocalDashTheme.current
                    Box(Modifier.fillMaxSize().background(theme.backgroundColourSecondary).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        SettingsContent(sub)
                    }
                }
            }
            val end = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < end) { scene.render(System.nanoTime()); Thread.sleep(16) }
            val file = File(out, "location-$name.png")
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote ${file.absolutePath}")
            scene.close()
        }
    }
}
