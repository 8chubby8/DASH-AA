package com.dash.android.logs.linux

import android.util.Log
import com.dash.android.DashApplication
import com.dash.android.connections.DeviceKind
import com.dash.android.logs.Blanker
import com.dash.android.logs.LogBook
import com.dash.android.logs.LogLine
import com.dash.android.logs.LogRun
import com.dash.android.prefs.DashPreferences
import com.dash.android.system.buildDeviceReport
import com.dash.android.system.formatDeviceReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * **What is private on this machine, and saving the logs to a USB stick** (1.1.9).
 *
 * [known] gathers what DASH knows to be personal — the Bluetooth devices it has seen, the Wi-Fi networks,
 * the place the weather uses, the user's and the machine's names — for [Blanker]. [save] writes one folder
 * to a stick: DASH's kept runs, the machine's log for the run being looked at, and This Machine's report,
 * all blanked, with a note saying what was taken out.
 */
object LogSave {
    private const val TAG = "DashLogs"
    private const val MACHINE_LINES = 20_000
    private val folderStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").withZone(ZoneId.systemDefault())

    fun known(app: DashApplication): Blanker.Known {
        val bt = app.bluetooth.state.value
        val net = app.network.state.value
        val place = runCatching { runBlocking { DashPreferences(app).manualLocation.first()?.name } }.getOrNull()
        return Blanker.Known(
            devices = bt.devices.map { Blanker.Device(it.address, it.name, phone = it.kind == DeviceKind.PHONE, module = it.kind == DeviceKind.MODULE) },
            networks = (net.nearby.map { it.ssid } + net.known.map { it.ssid } + net.adapters.mapNotNull { it.network }).filter { it.isNotBlank() }.toSet(),
            places = setOfNotNull(place),
            user = System.getProperty("user.name"),
            hostname = hostname() ?: bt.name.takeIf { it.isNotBlank() },
        )
    }

    fun blanker(app: DashApplication) = Blanker(known(app), ::maker)

    private fun hostname(): String? =
        runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()

    /** The maker of a hardware address, from systemd's own list (the IEEE's), if this machine has it. */
    private fun maker(oui: String): String? = runCatching {
        val p = ProcessBuilder("systemd-hwdb", "query", "OUI:${oui.uppercase()}").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out.lineSequence().firstOrNull { it.startsWith("ID_OUI_FROM_DATABASE=") }?.substringAfter('=')?.trim()
    }.getOrNull()

    /**
     * Save to [stick]. [focus] is the run being looked at, whose time the machine's log is taken for; null
     * for this run. Returns where it went, in words, or why it could not.
     */
    fun save(app: DashApplication, stick: UsbSticks.Stick, focus: LogRun?): Result<String> = runCatching {
        val wasOpen = stick.mountpoint != null
        val (root, why) = UsbSticks.mount(stick)
        root ?: error(why)
        val blank = blanker(app)
        val folder = File(root, "DASH logs ${folderStamp.format(Instant.now())}")
        var dir = folder
        var n = 2
        while (dir.exists()) dir = File(root, "${folder.name} ($n)").also { n++ }
        if (!dir.mkdirs()) error("Could not write to the stick — is it full, or locked?")

        val runs = LogBook.runs()
        for (run in runs) {
            val name = "DASH ${folderStamp.format(Instant.ofEpochMilli(run.started))}" +
                (if (run.current) " (this run)" else if (run.stoppedUnexpectedly) " (stopped unexpectedly)" else "") + ".log"
            write(File(dir, name), LogBook.read(run), blank)
        }
        val window = focus ?: runs.firstOrNull { it.current }
        val machine = Journal.read(since = window?.started, until = window?.ended, max = MACHINE_LINES)
        if (machine.isNotEmpty()) write(File(dir, "Machine log.log"), machine, blank)

        val scale = runCatching { runBlocking { DashPreferences(app).dashTextScale.first() } }.getOrDefault(1f)
        File(dir, "This machine.txt").writeText(blank.blank(formatDeviceReport(buildDeviceReport(app, scale))) + "\n")
        File(dir, "Read me.txt").writeText(README)

        runCatching { ProcessBuilder("sync").start().waitFor(30, TimeUnit.SECONDS) }
        if (!wasOpen) UsbSticks.unmount(stick)
        Log.i(TAG, "logs saved to a USB stick (${runs.size} runs${if (machine.isNotEmpty()) " and the machine's log" else ""})")
        "Saved to ${stick.name}, in “${dir.name}”." + if (!wasOpen) " The stick can be taken out." else ""
    }.onFailure { Log.w(TAG, "logs not saved: ${it.message}") }

    private fun write(f: File, lines: List<LogLine>, blank: Blanker) =
        f.bufferedWriter().use { w -> lines.forEach { w.write(blank.blank(LogBook.format(it))); w.newLine() } }

    private val README = """
        |DASH logs
        |
        |"DASH …" files are DASH's own log, one file for each time it ran (the last ten are kept).
        |"Machine log" is what the rest of the machine said while the run being looked at was going:
        |the kernel, USB, Bluetooth, Wi-Fi, the networks and the sound system.
        |"This machine" is the report from System › This Machine.
        |
        |Private details have been taken out. In their place are labels that stay the same all the way
        |through these files, so one phone is always "Phone 1":
        |  - Bluetooth and Wi-Fi addresses (the maker is kept), and the names of phones and devices
        |  - Wi-Fi network names
        |  - the place DASH found itself, and map positions
        |  - internet addresses (addresses inside a home or car network, like 192.168.x.x, are kept)
        |  - email addresses, phone numbers and USB serial numbers
        |  - the user's name and the machine's name
        |Module names are kept: they are the module builder's, not a person's.
        |
        |The machine's log is written by every program on the machine, so a name DASH has never seen can
        |slip through. Have a look before sharing it.
        |""".trimMargin()
}
