package com.dash.android.logs.linux

import com.dash.android.system.linux.Command
import android.util.Log
import com.dash.android.logs.LogLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **The machine's log** (1.1.9) — the second view in Developer › Logs: what the kernel, Bluetooth, the
 * networks, the sound system and the power service said, read from systemd's journal with `journalctl`.
 *
 * It matters most on the machine DASH-AA Linux runs on, where USB, Wi-Fi and Bluetooth trouble is reported
 * nowhere else. Read only, as the user — never root.
 *
 * **Capability detection:** the whole machine's log is readable by the administrators' groups (`wheel`,
 * `systemd-journal`, `adm`). Without one, journalctl still shows the user's own programs, and the tab says
 * so and how to allow the rest. Without journalctl at all (a machine without systemd) the view is absent.
 */
object Journal {
    private const val TAG = "DashLogs"

    data class Access(val available: Boolean, val whole: Boolean, val note: String)

    @Volatile private var cached: Access? = null

    fun access(): Access = cached ?: probe().also { cached = it }

    private fun probe(): Access {
        val journalctl = listOf("/usr/bin/journalctl", "/bin/journalctl").firstOrNull { File(it).canExecute() }
            ?: return Access(false, false, "This machine has no journal (journalctl is not installed).")
        val said = runCatching {
            Command.run(journalctl, "-b", "-n", "1", "-q", "--no-pager", "-o", "cat", seconds = 10)?.out.orEmpty()
        }.getOrElse { return Access(false, false, "The journal could not be read: ${it.message}") }
        val limited = said.contains("insufficient permissions") || said.contains("not seeing messages from other users")
        return if (limited) Access(true, false,
            "Only your own programs' messages can be read. To read the whole machine's, add yourself to its " +
                "journal group once, in a terminal:  sudo usermod -aG systemd-journal \$USER  (then log out and in).")
        else Access(true, true, "")
    }

    /** The journal between two moments (milliseconds; null = this boot's start, or now), newest [max] lines. */
    fun read(since: Long?, until: Long?, max: Int): List<LogLine> {
        if (!access().available) return emptyList()
        val cmd = mutableListOf("journalctl", "--no-pager", "-q", "-o", "json", "-n", max.toString())
        if (since == null) cmd += "-b" else cmd += listOf("--since", "@${since / 1000}")
        if (until != null) cmd += listOf("--until", "@${until / 1000 + 1}")
        return runCatching {
            val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val lines = p.inputStream.bufferedReader().useLines { seq -> seq.mapNotNull(::parse).toList() }
            p.waitFor(30, TimeUnit.SECONDS)
            lines
        }.onFailure { Log.w(TAG, "could not read the journal: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * Follow the journal from now, a line at a time, until the returned process is stopped. The last [back]
     * lines of this boot come first.
     */
    fun follow(back: Int, onLine: (LogLine) -> Unit): Process? {
        if (!access().available) return null
        return runCatching {
            val p = ProcessBuilder("journalctl", "--no-pager", "-q", "-o", "json", "-b", "-n", back.toString(), "-f")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            Thread({
                runCatching { p.inputStream.bufferedReader().forEachLine { s -> parse(s)?.let(onLine) } }
            }, "dash-journal").apply { isDaemon = true }.start()
            p
        }.onFailure { Log.w(TAG, "could not follow the journal: ${it.message}") }.getOrNull()
    }

    private val json = Json { ignoreUnknownKeys = true }

    internal fun parse(s: String): LogLine? = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        val time = (o.str("__REALTIME_TIMESTAMP")?.toLongOrNull() ?: 0L) / 1000
        val who = o.str("SYSLOG_IDENTIFIER") ?: o.str("_COMM") ?: o.str("_SYSTEMD_UNIT") ?: "?"
        val msg = message(o["MESSAGE"]) ?: return null
        val level = when (o.str("PRIORITY")?.toIntOrNull() ?: 6) {
            in 0..3 -> Log.ERROR; 4 -> Log.WARN; 5, 6 -> Log.INFO; else -> Log.DEBUG
        }
        LogLine(time, level, who, msg.trimEnd(), part(who, o.str("_SYSTEMD_UNIT"), msg))
    }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    /** A message the journal could not store as text comes as a list of bytes. */
    private fun message(e: kotlinx.serialization.json.JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.content
        is JsonArray -> runCatching { String(ByteArray(e.size) { i -> e[i].jsonPrimitive.int.toByte() }, Charsets.UTF_8) }.getOrNull()
        else -> null
    }

    const val USB = "USB"
    const val BLUETOOTH = "Bluetooth"
    const val WIFI = "Wi-Fi"
    const val SOUND = "Sound"
    const val NETWORK = "Network"
    const val POWER = "Power"
    const val DASH = "DASH"
    const val OTHER = "Other"

    /** In the order the viewer lists them — the ones Roger asked for first. */
    val parts = listOf(USB, BLUETOOTH, WIFI, SOUND, NETWORK, POWER, DASH, OTHER)

    private val WIFI_WORDS = Regex("""\b(wlan|wlp|wifi|wi-fi|802-11|80211|iwlwifi|mwifiex|ath\d+k|mt79|rtw|brcmfmac|supplicant)""", RegexOption.IGNORE_CASE)

    internal fun part(who: String, unit: String?, msg: String): String {
        val w = who.lowercase()
        return when {
            w == "kernel" -> when {
                msg.startsWith("Bluetooth:") || msg.contains("btusb") || msg.contains("hci0") -> BLUETOOTH
                WIFI_WORDS.containsMatchIn(msg) -> WIFI
                msg.startsWith("usb ") || msg.contains("xhci") || msg.contains("usbcore") || msg.startsWith("hub ") ||
                    msg.contains("cdc_acm") || msg.contains("ttyUSB") || msg.contains("ttyACM") -> USB
                msg.startsWith("snd") || msg.contains("snd_") || msg.contains("sof-") || msg.contains("HDA") -> SOUND
                msg.contains("ACPI") && (msg.contains("battery", true) || msg.contains("AC")) -> POWER
                else -> OTHER
            }
            w.startsWith("bluetooth") || w == "obexd" -> BLUETOOTH
            w == "wpa_supplicant" || w == "iwd" -> WIFI
            w == "networkmanager" || w.startsWith("nm-") || w == "dhcpcd" || w == "systemd-networkd" || w == "systemd-resolved" ->
                if (WIFI_WORDS.containsMatchIn(msg)) WIFI else NETWORK
            w == "pipewire" || w == "wireplumber" || w.startsWith("pipewire") || w == "pulseaudio" || w == "rtkit-daemon" -> SOUND
            w == "systemd-logind" || w == "upowerd" || w == "power-profiles-daemon" || w == "tuned" || w == "thermald" -> POWER
            w == "systemd-udevd" || w == "mtp-probe" || w == "udisksd" -> USB
            w.startsWith("dash") || w == "java" || unit?.contains("dash") == true -> DASH
            else -> OTHER
        }
    }
}
