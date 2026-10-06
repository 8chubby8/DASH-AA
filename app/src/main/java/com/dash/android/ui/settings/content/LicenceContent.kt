package com.dash.android.ui.settings.content

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.MAINBODY_LINE
import com.dash.android.ui.common.BODY_LINE

/**
 * System › Licence (roadmap 1.5.14).
 *
 * **This tab is an obligation, not a nicety.** DASH is GPL-3.0, and §5(d) of that licence requires
 * an interactive program to display Appropriate Legal Notices — the copyright, the absence of any
 * warranty, and how the user may see the licence itself. Until 1.5.14 DASH displayed none of it
 * anywhere. The same is true one dependency down: the Apache 2.0 components DASH is built on require
 * their attribution to travel with the binary, and it was not travelling.
 *
 * The full text is read from the `LICENSE` asset, which the build copies from the repository root —
 * so what DASH shows and what the project ships are the same file, permanently. It opens as a view
 * of its own rather than a well inside this page: 674 lines inside a scrolling page means a scroll
 * within a scroll, which is unpleasant on a touchscreen and worse in a moving vehicle.
 */
@Composable
fun LicenceContent() {
    var showingFullText by remember { mutableStateOf(false) }
    if (showingFullText) FullLicence { showingFullText = false }
    else LicenceOverview { showingFullText = true }
}

@Composable
private fun LicenceOverview(onViewFullText: () -> Unit) {
    val theme = LocalDashTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(BOX_PAD),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {

        // ── The notice (GPL-3.0 §5(d)) ───────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsContentHeader("Licence")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "DASH — Dynamic Automotive System Hub",
                    color = theme.textColourSecondary,
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                )
                Text(
                    "Copyright © 2026 8chubby8",
                    color = theme.textColourSecondary.copy(alpha = 0.72f),
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                )
            }
            Body(
                "This program is free software: you can redistribute it and/or modify it under the " +
                    "terms of the GNU General Public License as published by the Free Software " +
                    "Foundation, either version 3 of the License, or (at your option) any later version."
            )
            Body(
                "This program is distributed in the hope that it will be useful, but WITHOUT ANY " +
                    "WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A " +
                    "PARTICULAR PURPOSE. See the GNU General Public License for more details."
            )
            LinkButton("VIEW FULL LICENCE →") { onViewFullText() }
        }

        // ── What that means ──────────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsContentHeader("In plain language")
            Body(
                "You may use DASH for anything you like, including in a vehicle you sell. You may " +
                    "read the source, change it, and share your changes. If you distribute a " +
                    "modified DASH, you pass the same freedoms on — your users get the source too."
            )
            Body(
                "It comes with no warranty of any kind. DASH reads vehicle data and never writes to " +
                    "the CAN bus, but what you install it on and what you do with it is yours to " +
                    "judge."
            )
        }

        // ── Third-party ──────────────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsContentHeader("Open source components")
            DEPENDENCIES.forEach { dep ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${dep.name} ${dep.version}",
                        color = theme.textColourSecondary,
                        fontSize = MAINBODY,
                        fontFamily = theme.font,
                    )
                    Text(
                        "${dep.licence} · ${dep.url}",
                        color = theme.textColourSecondary.copy(alpha = 0.62f),
                        fontSize = BODY,
                        lineHeight = BODY_LINE,
                        fontFamily = theme.font,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullLicence(onBack: () -> Unit) {
    val theme = LocalDashTheme.current
    val context = LocalContext.current
    val lines = remember { readLicenceAsset(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(BOX_PAD),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsContentHeader("GNU General Public License v3")
        LinkButton("← BACK TO LICENCE") { onBack() }

        if (lines == null) {
            Body(
                "The licence text could not be read from this installation. The full GNU General " +
                    "Public License version 3 is published at https://www.gnu.org/licenses/gpl-3.0.html " +
                    "and is included with the DASH source."
            )
            return@Column
        }

        // A lazy list rather than one enormous Text: 674 lines composed at once is a visible pause on
        // a Bronze tablet, and only a screenful is ever on show.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(theme.textColourSecondary.copy(alpha = 0.05f))
                .border(1.dp, theme.textColourSecondary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            items(lines) { line ->
                Text(
                    line.ifBlank { " " },
                    color = theme.textColourSecondary.copy(alpha = 0.78f),
                    fontSize = BODY,
                    lineHeight = BODY_LINE,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    val theme = LocalDashTheme.current
    Text(
        text,
        color = theme.textColourSecondary.copy(alpha = 0.8f),
        fontSize = MAINBODY,
        lineHeight = MAINBODY_LINE,
        fontFamily = theme.font,
    )
}

/** The GPL text as shipped, or null if the asset is missing — in which case the screen says so and
 *  points at gnu.org rather than pretending the notice is complete. */
private fun readLicenceAsset(context: Context): List<String>? = runCatching {
    context.assets.open("LICENSE").bufferedReader().use { it.readLines() }
}.getOrNull()

private data class Dependency(
    val name: String,
    val version: String,
    val licence: String,
    val url: String,
)

/**
 * Maintained by hand, deliberately. It is nine lines that change perhaps twice a year, against a
 * Gradle plugin that would add a build dependency, a generated file and a failure mode — on the one
 * screen in DASH whose entire job is to be accurate about dependencies.
 *
 * **Update this when `gradle/libs.versions.toml` changes.**
 */
// DASH-AA: the components this build actually bundles (FORK.md) — the attribution duty is the same one
// upstream discharges, applied to a different set. FFmpeg and libusb are the system's own, run or loaded
// from the machine rather than shipped, and keep their own notices there.
private val DEPENDENCIES = listOf(
    Dependency("Kotlin standard library", "2.2.21", "Apache 2.0", "kotlinlang.org"),
    Dependency("Compose Multiplatform for Desktop (UI, Foundation, Material 3)", "1.9.3", "Apache 2.0", "jetbrains.com/compose-multiplatform"),
    Dependency("Skiko / Skia", "via Compose 1.9.3", "Apache 2.0 / BSD-3-Clause", "github.com/JetBrains/skiko"),
    Dependency("AndroidX DataStore Preferences Core", "1.1.7", "Apache 2.0", "developer.android.com/jetpack"),
    Dependency("kotlinx.serialization JSON", "1.7.3", "Apache 2.0", "github.com/Kotlin/kotlinx.serialization"),
    Dependency("kotlinx.coroutines (Swing)", "1.10.2", "Apache 2.0", "github.com/Kotlin/kotlinx.coroutines"),
    Dependency("jSerialComm", "2.11.4", "Apache 2.0 / LGPL-3.0", "github.com/Fazecast/jSerialComm"),
    Dependency("Java Native Access (JNA)", "5.19.1", "Apache 2.0 / LGPL-2.1", "github.com/java-native-access/jna"),
    Dependency("kXML2", "2.3.0", "MIT", "kxml.sourceforge.net"),
    Dependency("OpenJDK runtime (bundled)", "17", "GPL-2.0 with Classpath Exception", "openjdk.org"),
    Dependency("aasdk — Android Auto message definitions", "master", "GPL-3.0", "github.com/f1xpl/aasdk"),
)
