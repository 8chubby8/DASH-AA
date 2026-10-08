package com.dash.android.display.linux

import android.util.Log
import com.dash.android.display.DisplayFeatures
import com.dash.android.display.NightLight
import com.dash.android.display.Overscan
import com.dash.android.display.RgbRange
import com.dash.android.display.Screen
import com.dash.android.display.ScreenMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The screens through GNOME's display program, Mutter (DASH-AA 1.1.5) — `org.gnome.Mutter.DisplayConfig`
 * on the session bus, which any program the seat user runs may ask. No root, no password: the same
 * requests GNOME's own Settings makes.
 *
 * Every change is first *checked* by Mutter (method 0) and only then made, as **temporary** (method 1):
 * GNOME's saved display layout is never touched, and when DASH closes it puts back what it found.
 *
 * Talks through `busctl` (systemd's, on any machine with a session bus), which reads replies as JSON;
 * `gdbus monitor` hears about screens plugged in or changed elsewhere. Night light is GNOME's own,
 * through `gsettings`; a touchscreen's screen likewise. Mutter's own words for values are documented
 * in its `org.gnome.Mutter.DisplayConfig.xml`.
 */
internal class MutterBackend : ScreenBackend {
    override val program = "GNOME"

    /** The last state read — the details [write] needs that a [Screen] does not carry. */
    @Volatile private var last: Raw? = null
    private var foundNightLight: List<Pair<String, String>>? = null

    override fun read(): Snapshot? {
        val (code, out) = busctl(listOf("--json=short", "call") + DEST + listOf("GetCurrentState"))
        if (code != 0) return null
        val raw = runCatching { parse(out) }.onFailure { Log.w(TAG, "could not read the screens: ${it.message}") }.getOrNull() ?: return null
        val backlight = readBacklight()
        last = raw
        val night = readNightLight()
        val features = DisplayFeatures(
            arrange = true,
            mirror = true,
            oneScale = raw.globalScaleRequired,
            nightLight = night,
            blank = true,
            touchMapping = onPath("gsettings"),
        )
        return Snapshot(screens(raw, backlight), features, scaledLayout = raw.layoutMode != 2)
    }

    override fun write(target: List<Screen>, now: Snapshot): String? {
        val raw = last ?: return "The display program has not been read yet."
        val plans = listOf(true, false).map { full -> configArgs(raw, target, full) }
        var refusal: String? = null
        for (plan in plans) {
            val args = plan.getOrElse { return it.message }
            val check = busctl(listOf("call") + DEST + listOf("ApplyMonitorsConfig") + args(METHOD_VERIFY))
            if (check.first != 0) { refusal = check.second.trim(); Log.i(TAG, "Mutter refused a check: $refusal"); continue }
            val done = busctl(listOf("call") + DEST + listOf("ApplyMonitorsConfig") + args(METHOD_TEMPORARY))
            return if (done.first == 0) null else "GNOME would not make the change: ${done.second.trim().take(160)}"
        }
        return "GNOME would not accept that arrangement of screens" + (refusal?.let { ": ${it.take(160)}" } ?: ".")
    }

    override fun setBrightness(screen: Screen, level: Float): Boolean {
        val b = readBacklight()[screen.id] ?: return false
        val value = (b.min + (b.max - b.min) * level.coerceIn(0f, 1f)).roundToInt()
        return busctl(listOf("call") + DEST + listOf("SetBacklight", "usi", b.serial.toString(), screen.id, value.toString())).first == 0
    }

    override fun setNightLight(on: Boolean, kelvin: Int): Boolean {
        if (!onPath("gsettings")) return false
        if (foundNightLight == null) {
            foundNightLight = NIGHT_KEYS.map { k -> k to run(listOf("gsettings", "get", NIGHT, k)).second.trim() }
        }
        // Always on while DASH says so: GNOME's own clock schedule is set to the whole day.
        val sets = listOf(
            "night-light-schedule-automatic" to "false",
            "night-light-schedule-from" to "0.0",
            "night-light-schedule-to" to "0.0",
            "night-light-temperature" to "uint32 $kelvin",
            "night-light-enabled" to on.toString(),
        )
        return sets.all { (k, v) -> run(listOf("gsettings", "set", NIGHT, k, v)).first == 0 }
    }

    /** GNOME's own night-light settings, as DASH found them — they are GNOME's to keep. */
    fun restoreNightLight() {
        foundNightLight?.forEach { (k, v) -> if (v.isNotEmpty()) run(listOf("gsettings", "set", NIGHT, k, v)) }
        foundNightLight = null
    }

    override fun setScreensOn(on: Boolean): Boolean =
        busctl(listOf("set-property") + DEST + listOf("PowerSaveMode", "i", if (on) "0" else "3")).first == 0

    override fun touchMapping(usbIds: List<String>, now: Snapshot): Map<String, String> = usbIds.mapNotNull { usb ->
        val (code, out) = run(listOf("gsettings", "get", "$TOUCH_SCHEMA:$TOUCH_PATH$usb/", "output"))
        if (code != 0) return@mapNotNull null
        val parts = Regex("'([^']*)'").findAll(out).map { it.groupValues[1] }.toList()
        if (parts.size < 3 || parts.all { it.isEmpty() }) return@mapNotNull null
        val identity = parts.take(3).joinToString("|")
        now.screens.firstOrNull { it.identity == identity }?.let { usb to it.id }
    }.toMap()

    override fun mapTouchscreen(eventDevice: String, usbId: String?, screen: Screen): Boolean {
        if (usbId == null) return false
        val (vendor, product, serial) = screen.identity.split('|').let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        val value = "['${vendor.replace("'", "")}', '${product.replace("'", "")}', '${serial.replace("'", "")}']"
        return run(listOf("gsettings", "set", "$TOUCH_SCHEMA:$TOUCH_PATH$usbId/", "output", value)).first == 0
    }

    override fun watch(onChange: () -> Unit): AutoCloseable? {
        val p = runCatching {
            ProcessBuilder("gdbus", "monitor", "--session", "--dest", "org.gnome.Mutter.DisplayConfig")
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return poll(5000, onChange)
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if ("MonitorsChanged" in line || "Backlight" in line) onChange()
                }
            }
        }, "dash-display-watch").apply { isDaemon = true }.start()
        return AutoCloseable { p.destroy() }
    }

    private data class Backlight(val serial: Long, val min: Int, val max: Int, val value: Int)

    private fun readBacklight(): Map<String, Backlight> {
        val (code, out) = busctl(listOf("--json=short", "get-property") + DEST + listOf("Backlight"))
        if (code != 0) return emptyMap()
        return runCatching { parseBacklight(out) }.getOrDefault(emptyMap())
    }

    private fun parseBacklight(json: String): Map<String, Backlight> {
        val data = Json.parseToJsonElement(json).jsonObject["data"]!!.jsonArray
        val serial = data[0].jsonPrimitive.long
        return data[1].jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val c = o["connector"]?.data()?.jsonPrimitive?.content ?: return@mapNotNull null
            val min = o["min"]?.data()?.jsonPrimitive?.int ?: 0
            val max = o["max"]?.data()?.jsonPrimitive?.int ?: return@mapNotNull null
            val value = o["value"]?.data()?.jsonPrimitive?.int ?: return@mapNotNull null
            c to Backlight(serial, min, max, value)
        }.toMap()
    }

    private fun readNightLight(): NightLight? {
        if (!onPath("gsettings")) return null
        val (c1, on) = run(listOf("gsettings", "get", NIGHT, "night-light-enabled"))
        val (c2, k) = run(listOf("gsettings", "get", NIGHT, "night-light-temperature"))
        if (c1 != 0 || c2 != 0) return null
        return NightLight(on.trim() == "true", k.trim().removePrefix("uint32 ").toIntOrNull() ?: 4000)
    }

    private fun screens(raw: Raw, backlight: Map<String, Backlight>): List<Screen> = raw.monitors.map { m ->
        val logical = raw.logical.firstOrNull { m.connector in it.connectors }
        val leader = logical?.connectors?.first()
        val current = m.modes.firstOrNull { it.current }
        val fixed = current?.let { c -> if (c.variable) m.modes.firstOrNull { it.fixedTwinOf(c) } ?: c else c }
        val bl = backlight[m.connector]
        val (w, h) = if (logical != null && fixed != null) logicalSize(fixed.width, fixed.height, logical.scale, logical.transform, raw.layoutMode) else 0 to 0
        Screen(
            id = m.connector,
            name = m.displayName ?: listOf(m.vendor, m.product).filter { it.isNotBlank() }.joinToString(" ").ifBlank { m.connector },
            identity = "${m.vendor}|${m.product}|${m.serial}",
            builtin = m.builtin,
            enabled = logical != null,
            primary = logical?.primary == true,
            x = logical?.x ?: 0,
            y = logical?.y ?: 0,
            width = w,
            height = h,
            scale = logical?.scale ?: 1.0,
            scales = fixed?.scales.orEmpty(),
            quarterTurns = (logical?.transform ?: 0) and 3,
            flipped = (logical?.transform ?: 0) >= 4,
            naturalPortrait = (fixed?.height ?: 0) > (fixed?.width ?: 0),
            modes = m.modes.filter { !it.variable }.map { it.screenMode() },
            mode = fixed?.screenMode(),
            mirrorOf = if (leader != null && leader != m.connector) leader else null,
            vrr = if (fixed != null && m.modes.any { it.variable && it.sameShape(fixed) }) current.variable else null,
            overscan = m.underscanning?.let { Overscan(if (it) 1 else 0, adjustable = false) },
            hdr = if (BT2100 in m.colorModes) m.colorMode == BT2100 else null,
            rgbRange = m.rgbRange?.let { RGB_FROM_MUTTER[it] ?: RgbRange.AUTO },
            brightness = bl?.let { if (it.max > it.min) (it.value - it.min).toFloat() / (it.max - it.min) else null },
        )
    }

    internal data class RawMode(
        val id: String, val width: Int, val height: Int, val refresh: Double,
        val preferred: Boolean, val current: Boolean, val variable: Boolean, val scales: List<Double>,
    ) {
        fun sameShape(o: RawMode) = width == o.width && height == o.height && abs(refresh - o.refresh) < 0.01
        fun fixedTwinOf(o: RawMode) = !variable && sameShape(o)
        fun screenMode() = ScreenMode(id, width, height, refresh, preferred)
    }

    internal data class RawMonitor(
        val connector: String, val vendor: String, val product: String, val serial: String,
        val modes: List<RawMode>, val displayName: String?, val builtin: Boolean,
        val underscanning: Boolean?, val colorMode: Int?, val colorModes: List<Int>, val rgbRange: Int?,
    )

    internal data class RawLogical(
        val x: Int, val y: Int, val scale: Double, val transform: Int, val primary: Boolean, val connectors: List<String>,
    )

    internal data class Raw(
        val serial: Long,
        val monitors: List<RawMonitor>,
        val logical: List<RawLogical>,
        val layoutMode: Int?,
        val canChangeLayout: Boolean,
        val globalScaleRequired: Boolean,
    )

    companion object {
        private const val TAG = "DashDisplay"
        private val DEST = listOf("org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig", "org.gnome.Mutter.DisplayConfig")
        internal const val METHOD_VERIFY = 0
        internal const val METHOD_TEMPORARY = 1
        private const val NIGHT = "org.gnome.settings-daemon.plugins.color"
        private val NIGHT_KEYS = listOf(
            "night-light-enabled", "night-light-temperature", "night-light-schedule-automatic",
            "night-light-schedule-from", "night-light-schedule-to",
        )
        private const val TOUCH_SCHEMA = "org.gnome.desktop.peripherals.touchscreen"
        private const val TOUCH_PATH = "/org/gnome/desktop/peripherals/touchscreens/"

        /** Mutter's colour modes: 0 default, 1 BT.2100 (HDR), 2 SDR native. */
        private const val BT2100 = 1
        private val RGB_FROM_MUTTER = mapOf(1 to RgbRange.AUTO, 2 to RgbRange.FULL, 3 to RgbRange.LIMITED)
        private val RGB_TO_MUTTER = RGB_FROM_MUTTER.entries.associate { (k, v) -> v to k }

        private fun busctl(args: List<String>) = run(listOf("busctl", "--user") + args)

        private fun JsonElement.data(): JsonElement = (this as? JsonObject)?.get("data") ?: this

        /** A logical monitor's size on the desktop, after scale and turn. */
        internal fun logicalSize(w: Int, h: Int, scale: Double, transform: Int, layoutMode: Int?): Pair<Int, Int> {
            val lw = if (layoutMode == 2) w else (w / scale).roundToInt()
            val lh = if (layoutMode == 2) h else (h / scale).roundToInt()
            return if (transform % 2 == 1) lh to lw else lw to lh
        }

        internal fun parse(json: String): Raw {
            val data = Json.parseToJsonElement(json).jsonObject["data"]!!.jsonArray
            val monitors = data[1].jsonArray.map { m ->
                val a = m.jsonArray
                val spec = a[0].jsonArray.map { it.jsonPrimitive.content }
                val props = a[2].jsonObject
                RawMonitor(
                    connector = spec[0], vendor = spec[1], product = spec[2], serial = spec[3],
                    modes = a[1].jsonArray.map { mode ->
                        val ma = mode.jsonArray
                        val mp = ma[6].jsonObject
                        RawMode(
                            id = ma[0].jsonPrimitive.content,
                            width = ma[1].jsonPrimitive.int,
                            height = ma[2].jsonPrimitive.int,
                            refresh = ma[3].jsonPrimitive.double,
                            preferred = mp["is-preferred"]?.data()?.jsonPrimitive?.boolean == true,
                            current = mp["is-current"]?.data()?.jsonPrimitive?.boolean == true,
                            variable = mp["refresh-rate-mode"]?.data()?.jsonPrimitive?.content == "variable",
                            scales = ma[5].jsonArray.map { it.jsonPrimitive.double },
                        )
                    },
                    displayName = props["display-name"]?.data()?.jsonPrimitive?.content,
                    builtin = props["is-builtin"]?.data()?.jsonPrimitive?.boolean == true,
                    underscanning = props["is-underscanning"]?.data()?.jsonPrimitive?.boolean,
                    colorMode = props["color-mode"]?.data()?.jsonPrimitive?.int,
                    colorModes = (props["supported-color-modes"]?.data() as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty(),
                    rgbRange = props["rgb-range"]?.data()?.jsonPrimitive?.int,
                )
            }
            val logical = data[2].jsonArray.map { l ->
                val a = l.jsonArray
                RawLogical(
                    x = a[0].jsonPrimitive.int,
                    y = a[1].jsonPrimitive.int,
                    scale = a[2].jsonPrimitive.double,
                    transform = a[3].jsonPrimitive.int,
                    primary = a[4].jsonPrimitive.boolean,
                    connectors = a[5].jsonArray.map { it.jsonArray[0].jsonPrimitive.content },
                )
            }
            val props = data[3].jsonObject
            return Raw(
                serial = data[0].jsonPrimitive.long,
                monitors = monitors,
                logical = logical,
                layoutMode = props["layout-mode"]?.data()?.jsonPrimitive?.int,
                canChangeLayout = props["supports-changing-layout-mode"]?.data()?.jsonPrimitive?.boolean == true,
                globalScaleRequired = props["global-scale-required"]?.data()?.jsonPrimitive?.boolean == true,
            )
        }

        /**
         * The arguments to `ApplyMonitorsConfig` for [target], as busctl takes them, for a given method.
         * With [full] each screen's colour, range and overscan are passed as wanted; without, Mutter's
         * defaults (the second try, should a Mutter not know them). A failure says why in plain words.
         */
        internal fun configArgs(raw: Raw, target: List<Screen>, full: Boolean): Result<(Int) -> List<String>> {
            val byId = target.associateBy { it.id }
            val leaders = target.filter { it.enabled && it.mirrorOf == null }
            if (leaders.isEmpty()) return Result.failure(IllegalStateException("At least one screen has to stay on."))
            val groups = leaders.map { lead -> lead to listOf(lead) + target.filter { it.enabled && it.mirrorOf == lead.id } }
            val lines = mutableListOf<List<String>>()
            for ((lead, members) in groups) {
                val leadMode = lead.mode ?: return Result.failure(IllegalStateException("${lead.name} has no resolution chosen."))
                val leadRaw = raw.monitors.firstOrNull { it.connector == lead.id }
                    ?: return Result.failure(IllegalStateException("${lead.name} is no longer connected."))
                val fixedLead = leadRaw.modes.firstOrNull { it.id == leadMode.id } ?: leadRaw.modes.first()
                val scale = nearestScale(lead.scale, fixedLead.scales)
                val transform = lead.quarterTurns + if (lead.flipped) 4 else 0
                val monitorArgs = members.map { s ->
                    val m = raw.monitors.firstOrNull { it.connector == s.id }
                        ?: return Result.failure(IllegalStateException("${s.name} is no longer connected."))
                    val wanted = if (s === lead) fixedLead else
                        m.modes.filter { !it.variable && it.width == fixedLead.width && it.height == fixedLead.height }
                            .minByOrNull { abs(it.refresh - fixedLead.refresh) }
                            ?: return Result.failure(IllegalStateException(
                                "${s.name} and ${lead.name} share no resolution, so one cannot show the same as the other."))
                    val mode = if ((byId[s.id]?.vrr ?: s.vrr) == true) m.modes.firstOrNull { it.variable && it.sameShape(wanted) } ?: wanted else wanted
                    val props = buildList {
                        if (!full) return@buildList
                        if (m.underscanning != null) s.overscan?.let { add(listOf("underscanning", "b", (it.percent > 0).toString())) }
                        val colour = when (s.hdr) {
                            true -> BT2100
                            false -> if (m.colorMode == BT2100) 0 else m.colorMode
                            null -> m.colorMode
                        }
                        if (colour != null && m.colorModes.isNotEmpty()) add(listOf("color-mode", "u", colour.toString()))
                        if (m.rgbRange != null) add(listOf("rgb-range", "u", (s.rgbRange?.let { RGB_TO_MUTTER[it] } ?: m.rgbRange).toString()))
                    }
                    listOf(s.id, mode.id, props.size.toString()) + props.flatten()
                }
                lines += listOf(lead.x.toString(), lead.y.toString(), scale.toString(), transform.toString(), lead.primary.toString(), members.size.toString()) +
                    monitorArgs.flatten()
            }
            // Exactly one main screen, as Mutter requires.
            if (groups.none { it.first.primary }) lines[0] = lines[0].toMutableList().also { it[4] = "true" }
            return Result.success { method ->
                buildList {
                    add("uua(iiduba(ssa{sv}))a{sv}")
                    add(raw.serial.toString()); add(method.toString())
                    add(lines.size.toString())
                    lines.forEach { addAll(it) }
                    if (raw.canChangeLayout && raw.layoutMode != null) { add("1"); add("layout-mode"); add("u"); add(raw.layoutMode.toString()) }
                    else add("0")
                }
            }
        }

        /** The scale nearest [want] that Mutter allows at this resolution. */
        internal fun nearestScale(want: Double, allowed: List<Double>): Double =
            allowed.minByOrNull { abs(it - want) } ?: want
    }
}
