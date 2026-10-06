import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

/**
 * The version DASH-AA reports is read from changelog.md, not written here — upstream's rule
 * (roadmap 1.6.11) carried across unchanged: one file, in git, read by everything that needs it.
 * The digits in the pattern are load-bearing for the same reason upstream gives: the changelog
 * documents its own entry format with a literal `## Version X.x.x` template.
 */
val changelogText: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file("changelog.md")).asText

val dashVersionName: String = run {
    val text = changelogText.orNull ?: error("changelog.md is unreadable — the version is derived from it.")
    Regex("""^## Version (\d+\.\d+\.\d+)\s*$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        ?: error("No '## Version <n.n.n>' heading in changelog.md — add the entry before building.")
}

/** The upstream DASH version this build mirrors — the `**Mirrors upstream:**` line of the top entry. */
val upstreamVersion: String = run {
    val text = changelogText.orNull ?: ""
    Regex("""\*\*Mirrors upstream:\*\*\s*DASH\s+(\d+\.\d+\.\d+)""").find(text)?.groupValues?.get(1) ?: "unknown"
}

/** Android's install counter has no meaning on a laptop; kept as a build counter so the shared About
 *  and DeviceReport code, which print it, stay byte-identical to upstream. */
val dashVersionCode = 1

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // DataStore's preferences core is multiplatform; only the Android `preferencesDataStore` delegate
    // is missing on the desktop, and DashPreferences replaces that one line.
    implementation("androidx.datastore:datastore-preferences-core:1.1.7")

    // The pull-parser API upstream's SvgParser is written against (Android ships it in the framework).
    implementation("net.sf.kxml:kxml2:2.3.0")

    // Module transports on Linux: tty serial ports, and native calls for Bluetooth RFCOMM + libusb.
    implementation("com.fazecast:jSerialComm:2.11.4")
    implementation("net.java.dev.jna:jna:5.19.1")

    testImplementation(kotlin("test"))
}

// --- Generated sources: BuildConfig, so upstream's About and DeviceReport compile unchanged. ---
val generatedSrc = layout.buildDirectory.dir("generated/dash/kotlin")
val generateBuildConfig by tasks.registering {
    val out = generatedSrc
    val name = dashVersionName
    val upstream = upstreamVersion
    inputs.property("version", name)
    inputs.property("upstream", upstream)
    outputs.dir(out)
    doLast {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))
        val file = out.get().file("com/dash/android/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.dash.android
            |
            |/** Generated from changelog.md by app/build.gradle.kts — do not edit. */
            |object BuildConfig {
            |    const val VERSION_NAME = "$name"
            |    const val VERSION_CODE = $dashVersionCode
            |    const val BUILD_DATE = "$date"
            |    /** The upstream DASH version this DASH-AA build mirrors. */
            |    const val UPSTREAM_VERSION = "$upstream"
            |}
            |""".trimMargin()
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generatedSrc)
tasks.named("compileKotlin") { dependsOn(generateBuildConfig) }

// --- Shipped assets: the licence and the SVG subset, one file each, in git, copied in (upstream's
// copyLicence / copySvgSubset discipline — never two hand-maintained copies). ---
val copyAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("svg-subset.json"))
    into(layout.buildDirectory.dir("generated/dash/resources/assets"))
}
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/dash/resources"))
tasks.named("processResources") { dependsOn(copyAssets) }

compose.desktop {
    application {
        mainClass = "com.dash.android.MainKt"
        jvmArgs += listOf(
            "-Xmx1g",
            // Compose on the JVM picks a renderer itself; OpenGL is the safe one on Mesa/AMD.
            "-Dskiko.renderApi=OPENGL",
        )
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "dash-aa"
            packageVersion = dashVersionName
            modules("java.naming", "jdk.unsupported")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The screenshot harness is opt-in; forward its switches from the Gradle command line.
    listOf("screenshots", "clicks", "sound").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    outputs.upToDateWhen { System.getProperty("screenshots") == null && System.getProperty("sound") == null }
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
