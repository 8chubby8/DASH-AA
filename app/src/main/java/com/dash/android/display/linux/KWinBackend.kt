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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * The screens through KDE's display program, KWin (DASH-AA 1.1.5) — by `kscreen-doctor`, KDE's own
 * screen tool, which speaks KWin's output-management protocol as the seat user. KWin is the likely
 * display program for the 1.2.x head unit (it does HDR, colour and night light best), run with DASH
 * alone and no Plasma desktop.
 *
 * The values are kscreen-doctor's own words (`libkscreen/src/doctor`); the JSON is libkscreen's
 * `ConfigSerializer`. KWin announces nothing DASH can hear without a Wayland client of its own, so the
 * screens are re-read every few seconds instead. Night light is KWin's, in `kwinrc`; a touchscreen's
 * screen is KWin's `InputDevice.outputName` on the session bus.
 *
 * [env] reaches a KWin other than the session's — the tests' own, headless one.
 */
internal class KWinBackend(private val env: Map<String, String> = emptyMap()) : ScreenBackend {
    override val program = "KDE (KWin)"

    @Volatile private var last: List<KOutput> = emptyList()
    private val canMirror by lazy { "mirror" in run(listOf("kscreen-doctor", "--help"), env).second }

    override fun read(): Snapshot? {
        val (code, out) = run(listOf("kscreen-doctor", "-j"), env)
        if (code != 0) return null
        val outputs = runCatching { parse(out) }.onFailure { Log.w(TAG, "could not read the screens: ${it.message}") }.getOrNull() ?: return null
        last = outputs
        val features = DisplayFeatures(
            arrange = true,
            mirror = canMirror,
            nightLight = readNightLight(),
            blank = true,
            touchMapping = true,
        )
        return Snapshot(screens(outputs), features)
    }

    override fun write(target: List<Screen>, now: Snapshot): String? {
        if (target.none { it.enabled && it.mirrorOf == null }) return "At least one screen has to stay on."
        val args = configArgs(target, last, canMirror)
        val (code, out) = run(listOf("kscreen-doctor") + args, env, seconds = 10)
        if (code != 0) return "KWin would not make the change: ${out.trim().take(160)}"
        return null
    }

    override fun setBrightness(screen: Screen, level: Float): Boolean {
        if (screen.brightness == null) return false
        return run(listOf("kscreen-doctor", "output.${screen.id}.brightness.${(level * 100).roundToInt().coerceIn(0, 100)}"), env).first == 0
    }

    override fun setNightLight(on: Boolean, kelvin: Int): Boolean {
        if (!onPath("kwriteconfig6")) return false
        val sets = listOf("Active" to on.toString(), "Mode" to "Constant", "NightTemperature" to kelvin.toString())
        val ok = sets.all { (k, v) -> run(listOf("kwriteconfig6", "--file", "kwinrc", "--group", "NightColor", "--key", k, v), env).first == 0 }
        return ok && run(listOf("busctl", "--user", "call", "org.kde.KWin", "/KWin", "org.kde.KWin", "reconfigure"), env).first == 0
    }

    override fun setScreensOn(on: Boolean): Boolean =
        run(listOf("kscreen-doctor", "--dpms", if (on) "on" else "off"), env).first == 0

    override fun touchMapping(usbIds: List<String>, now: Snapshot): Map<String, String> = emptyMap()

    /** KWin's mapping is by kernel device, read per device in [touchOutput]. */
    fun touchOutput(eventDevice: String): String? {
        val node = eventDevice.substringAfterLast('/')
        val (code, out) = run(listOf("busctl", "--user", "get-property", "org.kde.KWin", "/org/kde/KWin/InputDevice/$node",
            "org.kde.KWin.InputDevice", "outputName"), env)
        if (code != 0) return null
        return Regex("\"([^\"]*)\"").find(out)?.groupValues?.get(1)?.ifEmpty { null }
    }

    override fun mapTouchscreen(eventDevice: String, usbId: String?, screen: Screen): Boolean {
        val node = eventDevice.substringAfterLast('/')
        return run(listOf("busctl", "--user", "set-property", "org.kde.KWin", "/org/kde/KWin/InputDevice/$node",
            "org.kde.KWin.InputDevice", "outputName", "s", screen.id), env).first == 0
    }

    override fun watch(onChange: () -> Unit): AutoCloseable = poll(4000, onChange)

    private fun readNightLight(): NightLight? {
        if (!onPath("kreadconfig6")) return null
        val on = run(listOf("kreadconfig6", "--file", "kwinrc", "--group", "NightColor", "--key", "Active"), env).second.trim()
        val k = run(listOf("kreadconfig6", "--file", "kwinrc", "--group", "NightColor", "--key", "NightTemperature"), env).second.trim()
        return NightLight(on == "true", k.toIntOrNull() ?: 4500)
    }

    internal data class KMode(val id: String, val width: Int, val height: Int, val refresh: Double)

    internal data class KOutput(
        val id: Int,
        val name: String,
        val connected: Boolean,
        val enabled: Boolean,
        val priority: Int,
        val x: Int,
        val y: Int,
        val scale: Double,
        val rotation: Int,
        val currentModeId: String?,
        val modes: List<KMode>,
        val preferred: List<String>,
        val replicationSource: Int,
        val vrrPolicy: Int?,
        val overscan: Int?,
        val rgbRange: Int?,
        val hdr: Boolean?,
        val brightness: Double?,
        val builtin: Boolean,
    )

    private fun screens(outputs: List<KOutput>): List<Screen> {
        val connected = outputs.filter { it.connected }
        val primaryId = connected.filter { it.enabled && it.priority > 0 }.minByOrNull { it.priority }?.id
        return connected.map { o ->
            val mode = o.modes.firstOrNull { it.id == o.currentModeId }
            val (turns, flipped) = TURNS[o.rotation] ?: (0 to false)
            val lw = mode?.let { (it.width / o.scale).roundToInt() } ?: 0
            val lh = mode?.let { (it.height / o.scale).roundToInt() } ?: 0
            Screen(
                id = o.name,
                name = if (o.builtin) "Built-in display" else o.name,
                identity = "kwin|${o.name}",
                builtin = o.builtin,
                enabled = o.enabled,
                primary = o.id == primaryId,
                x = o.x,
                y = o.y,
                width = if (turns % 2 == 1) lh else lw,
                height = if (turns % 2 == 1) lw else lh,
                scale = o.scale,
                quarterTurns = turns,
                flipped = flipped,
                naturalPortrait = (mode?.height ?: 0) > (mode?.width ?: 0),
                modes = o.modes.map { ScreenMode(it.id, it.width, it.height, it.refresh, it.id in o.preferred) },
                mode = mode?.let { ScreenMode(it.id, it.width, it.height, it.refresh, it.id in o.preferred) },
                mirrorOf = connected.firstOrNull { it.id == o.replicationSource && o.replicationSource != 0 }?.name,
                vrr = o.vrrPolicy?.let { it != VRR_NEVER },
                overscan = o.overscan?.let { Overscan(it, adjustable = true) },
                hdr = o.hdr,
                rgbRange = o.rgbRange?.let { RANGES.getOrNull(it) },
                brightness = o.brightness?.let { (if (it > 1.0) it / 100.0 else it).toFloat() },
            )
        }
    }

    companion object {
        private const val TAG = "DashDisplay"
        private const val VRR_NEVER = 0
        /** libkscreen's rotation flags: none, left, inverted, right, then the flipped four. */
        private val TURNS = mapOf(
            1 to (0 to false), 2 to (1 to false), 4 to (2 to false), 8 to (3 to false),
            16 to (0 to true), 32 to (1 to true), 64 to (2 to true), 128 to (3 to true),
        )
        private val ROTATION_WORDS = listOf("normal", "left", "inverted", "right")
        private val FLIPPED_WORDS = listOf("flipped", "flipped90", "flipped180", "flipped270")
        private val RANGES = listOf(RgbRange.AUTO, RgbRange.FULL, RgbRange.LIMITED)

        internal fun parse(json: String): List<KOutput> {
            val root = Json.parseToJsonElement(json).jsonObject
            return root["outputs"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                val pos = o["pos"] as? JsonObject
                KOutput(
                    id = o["id"]!!.jsonPrimitive.int,
                    name = o["name"]!!.jsonPrimitive.content,
                    connected = o["connected"]?.jsonPrimitive?.booleanOrNull ?: true,
                    enabled = o["enabled"]?.jsonPrimitive?.boolean ?: false,
                    priority = o["priority"]?.jsonPrimitive?.intOrNull ?: 0,
                    x = pos?.get("x")?.jsonPrimitive?.int ?: 0,
                    y = pos?.get("y")?.jsonPrimitive?.int ?: 0,
                    scale = o["scale"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                    rotation = o["rotation"]?.jsonPrimitive?.intOrNull ?: 1,
                    currentModeId = o["currentModeId"]?.jsonPrimitive?.content,
                    modes = (o["modes"] as? JsonArray).orEmpty().map { m ->
                        val mo = m.jsonObject
                        val size = mo["size"]!!.jsonObject
                        KMode(
                            id = mo["id"]!!.jsonPrimitive.content,
                            width = size["width"]!!.jsonPrimitive.int,
                            height = size["height"]!!.jsonPrimitive.int,
                            refresh = mo["refreshRate"]?.jsonPrimitive?.double ?: 60.0,
                        )
                    },
                    preferred = (o["preferredModes"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                    replicationSource = o["replicationSource"]?.jsonPrimitive?.intOrNull ?: 0,
                    vrrPolicy = o["vrrPolicy"]?.jsonPrimitive?.intOrNull,
                    overscan = o["overscan"]?.jsonPrimitive?.intOrNull,
                    rgbRange = o["rgbRange"]?.jsonPrimitive?.intOrNull,
                    hdr = o["hdr"]?.jsonPrimitive?.booleanOrNull,
                    brightness = o["brightness"]?.jsonPrimitive?.doubleOrNull,
                    builtin = o["type"]?.jsonPrimitive?.intOrNull == TYPE_PANEL ||
                        o["name"]!!.jsonPrimitive.content.let { it.startsWith("eDP") || it.startsWith("LVDS") || it.startsWith("DSI") },
                )
            }
        }

        /** libkscreen's output type for a built-in panel. */
        private const val TYPE_PANEL = 7

        /** kscreen-doctor's arguments for [target], all in one call so KWin takes them as one change. */
        internal fun configArgs(target: List<Screen>, now: List<KOutput>, canMirror: Boolean): List<String> = buildList {
            val leaders = target.filter { it.enabled && it.mirrorOf == null }
            val primary = leaders.firstOrNull { it.primary } ?: leaders.firstOrNull()
            for (s in target) {
                val p = "output.${s.id}"
                val was = now.firstOrNull { it.name == s.id }
                if (!s.enabled) { add("$p.disable"); continue }
                add("$p.enable")
                s.mode?.let { add("$p.mode.${it.id}") }
                add("$p.position.${s.x},${s.y}")
                add("$p.scale.${s.scale}")
                add("$p.rotation.${(if (s.flipped) FLIPPED_WORDS else ROTATION_WORDS)[s.quarterTurns and 3]}")
                if (s.id == primary?.id) add("$p.primary")
                if (canMirror) add("$p.mirror.${s.mirrorOf ?: "none"}")
                s.vrr?.let { if (was?.vrrPolicy != null) add("$p.vrrpolicy.${if (it) "automatic" else "never"}") }
                s.overscan?.let { if (was?.overscan != null) add("$p.overscan.${it.percent.coerceIn(0, 100)}") }
                s.rgbRange?.let { if (was?.rgbRange != null) add("$p.rgbrange.${it.name.lowercase().replace("auto", "automatic")}") }
                s.hdr?.let { if (was?.hdr != null && it != was.hdr) add("$p.hdr.${if (it) "enable" else "disable"}") }
            }
        }
    }
}
