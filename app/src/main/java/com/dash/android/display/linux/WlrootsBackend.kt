package com.dash.android.display.linux

import android.util.Log
import com.dash.android.display.DisplayFeatures
import com.dash.android.display.NightLight
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * The screens through a wlroots display program — labwc, sway, cage, Wayfire — by `wlr-randr`, which
 * speaks their shared output-management protocol (DASH-AA 1.1.5). The plainest of the three: resolution,
 * refresh, scale, turning, place and adaptive sync. No mirroring, HDR, colour range or overscan, so those
 * controls do not appear; wlroots has no main screen either, so DASH keeps which one is its own.
 *
 * Blanking is `wlopm` where installed; night light is `gammastep` where installed, run for as long as
 * it is on (wlroots drops a colour change when the program that made it exits). Re-read every few
 * seconds, as wlroots announces nothing DASH can hear without a Wayland client of its own.
 */
internal class WlrootsBackend(private val env: Map<String, String> = emptyMap()) : ScreenBackend {
    override val program: String = System.getenv("XDG_CURRENT_DESKTOP")?.takeIf { it.isNotBlank() } ?: "wlroots"

    @Volatile private var primary: String? = null
    private var gammastep: Process? = null
    @Volatile private var night: NightLight? = if (onPath("gammastep")) NightLight(false, 4500) else null

    override fun read(): Snapshot? {
        val (code, out) = run(listOf("wlr-randr", "--json"), env)
        if (code != 0) return null
        val screens = runCatching { parse(out, primary) }.onFailure { Log.w(TAG, "could not read the screens: ${it.message}") }.getOrNull() ?: return null
        primary = screens.firstOrNull { it.primary }?.id
        val features = DisplayFeatures(arrange = true, nightLight = night, blank = onPath("wlopm"))
        return Snapshot(screens, features)
    }

    override fun write(target: List<Screen>, now: Snapshot): String? {
        if (target.none { it.enabled }) return "At least one screen has to stay on."
        val (code, out) = run(listOf("wlr-randr") + configArgs(target), env, seconds = 10)
        if (code != 0) return "The display program would not make the change: ${out.trim().take(160)}"
        primary = target.firstOrNull { it.primary && it.enabled }?.id ?: primary
        return null
    }

    override fun setNightLight(on: Boolean, kelvin: Int): Boolean {
        if (!onPath("gammastep")) return false
        synchronized(this) {
            gammastep?.destroy()
            gammastep = if (on) runCatching {
                ProcessBuilder("gammastep", "-P", "-O", kelvin.toString()).apply { environment().putAll(env) }
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull() else null
            night = NightLight(on && gammastep != null, kelvin)
        }
        return true
    }

    override fun setScreensOn(on: Boolean): Boolean =
        onPath("wlopm") && run(listOf("wlopm", if (on) "--on" else "--off", "*"), env).first == 0

    override fun watch(onChange: () -> Unit): AutoCloseable = poll(4000, onChange)

    companion object {
        private const val TAG = "DashDisplay"
        private val TRANSFORMS = listOf("normal", "90", "180", "270")

        internal fun parse(json: String, keptPrimary: String?): List<Screen> {
            val outputs = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
            val enabled = outputs.filter { it["enabled"]?.jsonPrimitive?.boolean == true }.map { it["name"]!!.jsonPrimitive.content }
            val primary = keptPrimary?.takeIf { it in enabled } ?: enabled.firstOrNull()
            return outputs.map { o ->
                val name = o["name"]!!.jsonPrimitive.content
                val modes = (o["modes"] as? JsonArray).orEmpty().map { m ->
                    val mo = m.jsonObject
                    val w = mo["width"]!!.jsonPrimitive.int
                    val h = mo["height"]!!.jsonPrimitive.int
                    val r = mo["refresh"]!!.jsonPrimitive.double
                    Triple(ScreenMode("${w}x$h@${"%.3f".format(java.util.Locale.ROOT, r)}", w, h, r, mo["preferred"]?.jsonPrimitive?.booleanOrNull == true),
                        mo["current"]?.jsonPrimitive?.booleanOrNull == true, Unit)
                }
                val current = modes.firstOrNull { it.second }?.first
                val transform = o["transform"]?.jsonPrimitive?.content ?: "normal"
                val flipped = transform.startsWith("flipped")
                val turns = TRANSFORMS.indexOf(transform.removePrefix("flipped").removePrefix("-").ifEmpty { "normal" }).coerceAtLeast(0)
                val scale = o["scale"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                val pos = o["position"] as? JsonObject
                val lw = current?.let { (it.width / scale).roundToInt() } ?: 0
                val lh = current?.let { (it.height / scale).roundToInt() } ?: 0
                val make = o["make"]?.jsonPrimitive?.content.orEmpty()
                val model = o["model"]?.jsonPrimitive?.content.orEmpty()
                Screen(
                    id = name,
                    name = listOf(make, model).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" ").ifBlank { name },
                    identity = "$make|$model|${o["serial"]?.jsonPrimitive?.content.orEmpty()}",
                    builtin = name.startsWith("eDP") || name.startsWith("LVDS") || name.startsWith("DSI"),
                    enabled = name in enabled,
                    primary = name == primary,
                    x = pos?.get("x")?.jsonPrimitive?.int ?: 0,
                    y = pos?.get("y")?.jsonPrimitive?.int ?: 0,
                    width = if (turns % 2 == 1) lh else lw,
                    height = if (turns % 2 == 1) lw else lh,
                    scale = scale,
                    quarterTurns = turns,
                    flipped = flipped,
                    naturalPortrait = (current?.height ?: 0) > (current?.width ?: 0),
                    modes = modes.map { it.first },
                    mode = current,
                    vrr = o["adaptive_sync"]?.jsonPrimitive?.booleanOrNull,
                )
            }
        }

        internal fun configArgs(target: List<Screen>): List<String> = buildList {
            for (s in target) {
                add("--output"); add(s.id)
                if (!s.enabled) { add("--off"); continue }
                add("--on")
                s.mode?.let { add("--mode"); add("${it.width}x${it.height}@${"%.3f".format(java.util.Locale.ROOT, it.refresh)}Hz") }
                add("--pos"); add("${s.x},${s.y}")
                add("--scale"); add(s.scale.toString())
                val turn = TRANSFORMS[s.quarterTurns and 3]
                add("--transform"); add(if (!s.flipped) turn else if (turn == "normal") "flipped" else "flipped-$turn")
                s.vrr?.let { add("--adaptive-sync"); add(if (it) "enabled" else "disabled") }
            }
        }
    }
}
