package com.dash.android.system

import android.content.Context
import com.dash.android.BuildConfig
import com.dash.android.aa.AaCapabilities
import com.dash.android.audio.linux.PipeWireSound
import com.dash.android.transport.bluetooth.linux.BlueZ
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * What DASH found on this device (roadmap 1.5.14) — the report block at the foot of About DASH.
 *
 * **DASH-AA:** upstream's purpose and rules unchanged — *facts and capabilities, not identity*, the
 * first thing to ask for before asking anything else. Its Android probes (API level, launcher,
 * density control, Android's feature flags) are replaced by the ones DASH-AA's pipes actually turn
 * on: whether this user may open USB serial ports, whether BlueZ has a powered adapter, whether the
 * phone can be reached for Android Auto, and whether a video decoder and a touchscreen are present.
 *
 * As upstream says of Bronze, "unavailable" here is a fact, not a fault — and each line names the
 * thing that would change it.
 */
data class ReportLine(val label: String, val value: String)

fun buildDeviceReport(context: Context, dashTextScale: Float): List<ReportLine> {
    val screen = runCatching {
        if (GraphicsEnvironment.isHeadless()) null
        else GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString(" + ") { d ->
            val m = d.displayMode
            "${m.width} × ${m.height} @ ${m.refreshRate} Hz"
        }
    }.getOrNull() ?: "unknown"

    return listOf(
        ReportLine("DASH-AA", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · built ${BuildConfig.BUILD_DATE}"),
        ReportLine("Mirrors DASH", BuildConfig.UPSTREAM_VERSION),
        ReportLine("System", osPrettyName()),
        ReportLine("Kernel", System.getProperty("os.version") ?: "unknown"),
        ReportLine("Machine", readFirst("/sys/class/dmi/id/sys_vendor", "/sys/class/dmi/id/product_name").joinToString(" ").ifBlank { "unknown" }),
        ReportLine("Java", "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"),
        ReportLine("Session", listOfNotNull(System.getenv("XDG_SESSION_TYPE"), System.getenv("XDG_CURRENT_DESKTOP")).joinToString(" · ").ifBlank { "unknown" }),
        ReportLine("Screen", screen),
        ReportLine("DASH text scale", "%.2f".format(dashTextScale)),
        ReportLine("USB serial access", serialAccess()),
        ReportLine("Bluetooth", when (runCatching { BlueZ.adapter() }.getOrDefault(BlueZ.Adapter.ABSENT)) {
            BlueZ.Adapter.POWERED -> "Yes"
            BlueZ.Adapter.OFF -> "Adapter off"
            BlueZ.Adapter.ABSENT -> "No adapter"
        }),
        ReportLine("Sound", PipeWireSound.summary()),
        ReportLine("Android Auto USB", AaCapabilities.usbSummary()),
        ReportLine("Video decoder", AaCapabilities.decoderSummary()),
        ReportLine("Touchscreen", AaCapabilities.touchSummary()),
        ReportLine("Data folder", context.filesDir.parentFile?.absolutePath ?: "unknown"),
    )
}

/** The report as plain text, for the clipboard — the form it actually travels in. */
fun formatDeviceReport(lines: List<ReportLine>): String {
    val width = lines.maxOfOrNull { it.label.length } ?: 0
    return lines.joinToString("\n") { "${it.label.padEnd(width)}  ${it.value}" }
}

private fun osPrettyName(): String =
    runCatching {
        File("/etc/os-release").readLines()
            .firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim('"')
    }.getOrNull() ?: (System.getProperty("os.name") ?: "Linux")

private fun readFirst(vararg paths: String): List<String> =
    paths.mapNotNull { p -> runCatching { File(p).readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() } }

/** Whether this user may open USB serial ports — the desktop's version of Android's USB permission. */
private fun serialAccess(): String {
    val ports = File("/dev").listFiles { f -> f.name.startsWith("ttyACM") || f.name.startsWith("ttyUSB") }
        .orEmpty()
    if (ports.isNotEmpty()) {
        val open = ports.count { it.canRead() && it.canWrite() }
        return if (open == ports.size) "Yes (${ports.size} port${if (ports.size == 1) "" else "s"})"
        else "Blocked on ${ports.size - open} port(s) — join the 'uucp' or 'dialout' group"
    }
    val groups = runCatching { ProcessBuilder("id", "-Gn").start().inputStream.bufferedReader().readText() }
        .getOrDefault("")
    return if (groups.split(' ', '\n').any { it == "uucp" || it == "dialout" }) "Yes (no port plugged in)"
    else "Not in 'uucp'/'dialout' — modules on USB will be refused"
}
