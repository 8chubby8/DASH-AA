package com.dash.android.display.linux

import android.util.Log
import com.dash.android.aa.input.EvdevTouch
import com.dash.android.display.DisplayState
import com.dash.android.display.DisplaySystem
import com.dash.android.display.RgbRange
import com.dash.android.display.Screen
import com.dash.android.display.Touchscreen
import com.dash.android.display.transformFor
import com.dash.android.ui.rotation.DashOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DASH-AA's screens (1.1.5): whichever display program is running, asked through its [ScreenBackend] —
 * GNOME's Mutter, KDE's KWin, or a wlroots one — tried in that order, the first that answers kept.
 *
 * **DASH's setup, applied every start.** What the Display tabs choose is remembered here, screen by
 * screen, by each screen's own identity (maker, model, serial) rather than the socket it is in
 * (`screens.json`): plug the same monitor in next week and it is set up as it was. The main screen's
 * turn is native's Rotation preference, handed in by [rotate]. With no desktop, this memory *is* the
 * machine's display settings; on the laptop, every change is temporary in the display program's terms,
 * and GNOME's own saved layout is never touched.
 *
 * **The way DASH found them.** The screens as they were when DASH started are written to `found.json`
 * the first time DASH changes anything, so a crash cannot lose them, and put back by [restore] when DASH
 * closes — then the file goes.
 *
 * **Places.** A screen's place is kept as which side of the main screen it is on; after anything changes
 * a size (resolution, scale, a turn) the screens are packed edge to edge again from the main screen, so
 * no display program is ever asked for a gap or an overlap it would refuse.
 *
 * Everything runs on one thread of its own, never the caller's.
 */
class LinuxDisplay internal constructor(
    private val home: File,
    private val backends: () -> List<ScreenBackend>,
    private val backlight: Backlight = Backlight(),
) : DisplaySystem {

    constructor(home: File) : this(home, { defaultBackends() })

    private val _state = MutableStateFlow(DisplayState(false, "Looking for the display program…"))
    override val state: StateFlow<DisplayState> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dash-display").apply { isDaemon = true } }
    private var backend: ScreenBackend? = null
    private var watcher: AutoCloseable? = null
    private var snapshot: Snapshot? = null
    private var found: List<Screen>? = null
    private var memory: Map<String, Remembered> = emptyMap()
    /** The main screen's turn from native's Rotation preference; null is Auto — the turn DASH found. */
    private var rotation: DashOrientation? = null
    private var rotationSet = false
    private var failure: String? = null
    private var blankedByBacklight: Float? = null
    private var inhibitor: Process? = null

    private val foundFile get() = File(home, "found.json")
    private val memoryFile get() = File(home, "screens.json")

    override fun start() = worker.execute {
        memory = readMemory()
        val chosen = backends().firstOrNull { b -> runCatching { b.read() }.getOrNull() != null }
        if (chosen == null) {
            _state.value = DisplayState(
                false,
                "No display program answered, so DASH cannot change the screens. DASH knows GNOME, KDE's KWin " +
                    "and the wlroots ones (labwc, sway, cage).",
                ownOrientation = _state.value.ownOrientation,
            )
            return@execute
        }
        backend = chosen
        Log.i(TAG, "screens through ${chosen.program}")
        val now = chosen.read() ?: return@execute
        snapshot = now
        found = readFound() ?: now.screens
        publish()
        watcher = chosen.watch { worker.execute { refresh() } }
        // GNOME blanks the screen by itself after a while; DASH looks after that now (Display › Screen
        // Blanking), so GNOME is asked not to for as long as DASH runs. Gone when DASH goes.
        if (chosen is MutterBackend && onPath("gnome-session-inhibit")) {
            inhibitor = runCatching {
                ProcessBuilder("gnome-session-inhibit", "--inhibit", "idle", "--inhibit-only", "--reason", "DASH looks after the screen")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull()
        }
    }

    override fun rotate(orientation: DashOrientation?) = worker.execute {
        rotation = orientation
        rotationSet = true
        // No display program to turn the screen: DASH turns its own picture (Main.kt reads this).
        if (backend == null) _state.value = _state.value.copy(ownOrientation = orientation) else reconcile()
    }

    override fun arrange(screens: List<Screen>, keep: Boolean) = worker.execute {
        val b = backend ?: return@execute
        val now = b.read() ?: return@execute
        val target = pack(merge(now.screens, screens), now.scaledLayout)
        if (sameSetup(target, now.screens)) failure = null else write(b, now, target)
        if (keep && failure == null) {
            memory = memory + target.filter { it.identity.isNotBlank() }.associate { it.identity to Remembered.of(it, target) }
            writeMemory()
        }
        refresh()
    }

    override fun setBrightness(screenId: String, level: Float) = worker.execute {
        val b = backend
        val screen = snapshot?.screens?.firstOrNull { it.id == screenId }
        val done = (b != null && screen != null && b.setBrightness(screen, level)) ||
            (screen?.builtin == true && backlight.set(BRIGHTNESS_FLOOR + (1f - BRIGHTNESS_FLOOR) * level))
        if (!done) Log.i(TAG, "brightness: nothing could set $screenId")
        refresh()
    }

    override fun setNightLight(on: Boolean, kelvin: Int) = worker.execute {
        backend?.setNightLight(on, kelvin)
        refresh()
    }

    override fun setScreensOn(on: Boolean) = worker.execute {
        if (backend?.setScreensOn(on) == true) return@execute
        // No display program to ask: the backlight goes to nothing, and back to where it was.
        if (!on) {
            if (blankedByBacklight == null) blankedByBacklight = backlight.level()
            backlight.set(0f)
        } else blankedByBacklight?.let { backlight.set(it); blankedByBacklight = null }
    }

    override fun mapTouchscreen(deviceId: String, screenId: String) = worker.execute {
        val b = backend ?: return@execute
        val screen = snapshot?.screens?.firstOrNull { it.id == screenId } ?: return@execute
        val device = EvdevTouch.touchscreens().firstOrNull { it.path == deviceId } ?: return@execute
        if (!b.mapTouchscreen(device.path, device.usbId, screen)) failure = "The display program would not move the touchscreen."
        refresh()
    }

    override fun restore() {
        val done = runCatching {
            worker.submit {
                val b = backend ?: return@submit
                blankedByBacklight?.let { b.setScreensOn(true); backlight.set(it) }
                (b as? MutterBackend)?.restoreNightLight()
                val f = found ?: return@submit
                val now = b.read() ?: return@submit
                val target = pack(merge(now.screens, f.filter { s -> now.screens.any { it.id == s.id } }), now.scaledLayout)
                if (!sameSetup(target, now.screens)) b.write(target, now)?.let { Log.w(TAG, "could not put the screens back: $it") }
                foundFile.delete()
            }.get(8, TimeUnit.SECONDS)
        }
        if (done.isFailure) Log.w(TAG, "could not put the screens back: ${done.exceptionOrNull()?.message}")
        watcher?.close()
        inhibitor?.destroy()
    }

    /** Wait until everything asked so far is done — for tests. */
    internal fun flush() { worker.submit { }.get(5, TimeUnit.SECONDS) }

    /** Re-read, and when the screens connected have changed, set them up as remembered. */
    private fun refresh() {
        val b = backend ?: return
        val now = b.read() ?: return
        val before = snapshot?.screens?.map { it.identity }?.toSet()
        snapshot = now
        if (before != null && before != now.screens.map { it.identity }.toSet()) {
            Log.i(TAG, "screens changed: ${now.screens.map { it.id }}")
            reconcile()
        } else publish()
    }

    /** Set the screens up as DASH remembers them, with the main one turned as Rotation says. */
    private fun reconcile() {
        val b = backend ?: return
        val now = b.read() ?: return
        snapshot = now
        val target = pack(desired(now), now.scaledLayout)
        if (!sameSetup(target, now.screens)) write(b, now, target) else failure = null
        snapshot = b.read() ?: now
        publish()
    }

    private fun write(b: ScreenBackend, now: Snapshot, target: List<Screen>) {
        if (!foundFile.exists()) found?.let { f ->
            runCatching { home.mkdirs(); foundFile.writeText(json.encodeToString(ListSerializer(Screen.serializer()), f)) }
        }
        failure = b.write(target, now)
        failure?.let { Log.w(TAG, it) }
    }

    /** The screens as DASH would have them: the memory, then the main screen's turn. */
    private fun desired(now: Snapshot): List<Screen> {
        var screens = now.screens.map { s -> memory[s.identity]?.applyTo(s, now.screens) ?: s }
        if (screens.none { it.enabled }) screens = now.screens
        val main = screens.firstOrNull { it.primary && it.enabled } ?: screens.firstOrNull { it.enabled } ?: return screens
        if (!rotationSet) return screens
        val turns = rotation?.let { transformFor(it, main.naturalPortrait) }
            ?: found?.firstOrNull { it.identity == main.identity }?.quarterTurns
            ?: main.quarterTurns
        return screens.map { if (it.id == main.id) it.copy(quarterTurns = turns) else it }
    }

    private fun publish() {
        val b = backend ?: return
        val now = snapshot ?: return
        val devices = EvdevTouch.touchscreens()
        val mapped = runCatching { b.touchMapping(devices.mapNotNull { it.usbId }, now) }.getOrDefault(emptyMap())
        val touch = devices.map { d ->
            Touchscreen(d.path, d.name, d.usbId, (b as? KWinBackend)?.touchOutput(d.path) ?: d.usbId?.let { mapped[it] })
        }
        // A built-in panel's brightness through the backlight, where the display program does not do it.
        val screens = now.screens.map { s ->
            if (s.brightness == null && s.builtin && backlight.available) s.copy(brightness = backlight.level()?.let { fromBacklight(it) }) else s
        }
        _state.value = DisplayState(
            available = true,
            note = "",
            program = b.program,
            screens = screens,
            features = now.features,
            touchscreens = touch,
            // A built-in panel needs no asking; any other screen is asked about once.
            unfamiliar = screens.filter { !it.builtin && it.identity !in memory }.map { it.id }.toSet(),
            failure = failure,
        )
    }

    private fun readMemory(): Map<String, Remembered> = runCatching {
        json.decodeFromString(MapSerializer(String.serializer(), Remembered.serializer()), memoryFile.readText())
    }.getOrDefault(emptyMap())

    private fun writeMemory() {
        runCatching {
            home.mkdirs()
            memoryFile.writeText(json.encodeToString(MapSerializer(String.serializer(), Remembered.serializer()), memory))
        }
    }

    private fun readFound(): List<Screen>? = runCatching {
        json.decodeFromString(ListSerializer(Screen.serializer()), foundFile.readText())
    }.getOrNull()

    /** One screen as remembered: everything the Screens tab sets, with its neighbours named by identity. */
    @Serializable
    internal data class Remembered(
        val enabled: Boolean,
        val primary: Boolean,
        /** Which side of the main screen: "right", "left", "above", "below", or "" for the main one itself. */
        val side: String,
        val modeWidth: Int?,
        val modeHeight: Int?,
        val refresh: Double?,
        val scale: Double,
        val quarterTurns: Int,
        val flipped: Boolean,
        val mirrorOf: String?,
        val vrr: Boolean?,
        val overscan: Int?,
        val hdr: Boolean?,
        val rgbRange: RgbRange?,
    ) {
        fun applyTo(s: Screen, all: List<Screen>): Screen {
            val mode = s.modes.filter { it.width == modeWidth && it.height == modeHeight }
                .minByOrNull { abs(it.refresh - (refresh ?: it.refresh)) } ?: s.mode
            val main = all.firstOrNull { it.primary && it.enabled }
            val (x, y) = when (side) {
                "right" -> (main?.let { it.x + it.width } ?: 0) to (main?.y ?: 0)
                "left" -> (main?.let { it.x - s.width } ?: 0) to (main?.y ?: 0)
                "below" -> (main?.x ?: 0) to (main?.let { it.y + it.height } ?: 0)
                "above" -> (main?.x ?: 0) to (main?.let { it.y - s.height } ?: 0)
                else -> s.x to s.y
            }
            return s.copy(
                enabled = enabled,
                primary = primary,
                x = x, y = y,
                mode = mode,
                scale = scale,
                quarterTurns = quarterTurns,
                flipped = flipped,
                mirrorOf = mirrorOf?.let { id -> all.firstOrNull { it.identity == id }?.id },
                vrr = if (s.vrr != null) vrr ?: s.vrr else null,
                overscan = s.overscan?.let { o -> overscan?.let { o.copy(percent = it) } ?: o },
                hdr = if (s.hdr != null) hdr ?: s.hdr else null,
                rgbRange = if (s.rgbRange != null) rgbRange ?: s.rgbRange else null,
            )
        }

        companion object {
            fun of(s: Screen, all: List<Screen>): Remembered = Remembered(
                enabled = s.enabled,
                primary = s.primary,
                side = sideOf(s, all),
                modeWidth = s.mode?.width,
                modeHeight = s.mode?.height,
                refresh = s.mode?.refresh,
                scale = s.scale,
                quarterTurns = s.quarterTurns,
                flipped = s.flipped,
                mirrorOf = s.mirrorOf?.let { id -> all.firstOrNull { it.id == id }?.identity },
                vrr = s.vrr,
                overscan = s.overscan?.percent,
                hdr = s.hdr,
                rgbRange = s.rgbRange,
            )
        }
    }

    companion object {
        private const val TAG = "DashDisplay"
        /** The dimmest DASH sets a backlight to by choice — 0 is off, and that is blanking's, not brightness's. */
        private const val BRIGHTNESS_FLOOR = 0.02f
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun fromBacklight(level: Float) = ((level - BRIGHTNESS_FLOOR) / (1f - BRIGHTNESS_FLOOR)).coerceIn(0f, 1f)

        /** `DASH_DISPLAY=window` asks no display program — DASH turns its own picture, even on GNOME. For trying it. */
        internal fun defaultBackends(): List<ScreenBackend> = if (System.getenv("DASH_DISPLAY") == "window") emptyList() else buildList {
            add(MutterBackend())
            if (onPath("kscreen-doctor")) add(KWinBackend())
            if (onPath("wlr-randr")) add(WlrootsBackend())
        }

        /** [changed] laid over [now]: screens not mentioned stay as they are. */
        internal fun merge(now: List<Screen>, changed: List<Screen>): List<Screen> =
            now.map { s -> changed.firstOrNull { it.id == s.id } ?: s }

        /** Whether two setups are the same in everything DASH sets — sizes and names aside. */
        internal fun sameSetup(a: List<Screen>, b: List<Screen>): Boolean {
            if (a.size != b.size) return false
            return a.all { x ->
                val y = b.firstOrNull { it.id == x.id } ?: return false
                x.enabled == y.enabled && (!x.enabled || (
                    x.primary == y.primary && x.x == y.x && x.y == y.y && x.mode?.id == y.mode?.id &&
                        abs(x.scale - y.scale) < 0.001 && x.quarterTurns == y.quarterTurns && x.flipped == y.flipped &&
                        x.mirrorOf == y.mirrorOf && x.vrr == y.vrr && x.overscan == y.overscan && x.hdr == y.hdr &&
                        x.rgbRange == y.rgbRange))
            }
        }

        /** Which side of the main screen [s] is on, by where its middle lies. */
        internal fun sideOf(s: Screen, all: List<Screen>): String {
            val main = all.firstOrNull { it.primary && it.enabled } ?: return ""
            if (s.id == main.id || s.mirrorOf != null) return ""
            val dx = (s.x + s.width / 2.0) - (main.x + main.width / 2.0)
            val dy = (s.y + s.height / 2.0) - (main.y + main.height / 2.0)
            return if (abs(dx) * main.height.coerceAtLeast(1) >= abs(dy) * main.width.coerceAtLeast(1)) {
                if (dx >= 0) "right" else "left"
            } else if (dy >= 0) "below" else "above"
        }

        /**
         * Edge to edge from the main screen, each screen keeping its side, sized by its resolution, scale
         * and turn — then moved so the top-left is 0,0. Screens showing another's picture take its place.
         */
        internal fun pack(screens: List<Screen>, scaledLayout: Boolean): List<Screen> {
            fun sized(s: Screen): Screen {
                val m = s.mode ?: return s
                val w = if (scaledLayout) (m.width / s.scale).roundToInt() else m.width
                val h = if (scaledLayout) (m.height / s.scale).roundToInt() else m.height
                return if (s.quarterTurns % 2 == 1) s.copy(width = h, height = w) else s.copy(width = w, height = h)
            }
            val all = screens.map { sized(it) }
            val leaders = all.filter { it.enabled && it.mirrorOf == null }
            val main = leaders.firstOrNull { it.primary } ?: leaders.firstOrNull() ?: return all
            val sides = leaders.filter { it.id != main.id }.groupBy { sideOf(it, screens) }
            val placed = mutableMapOf(main.id to (0 to 0))
            var cursor = main.width
            sides["right"].orEmpty().sortedBy { it.x }.forEach { placed[it.id] = cursor to 0; cursor += it.width }
            cursor = 0
            sides["left"].orEmpty().sortedByDescending { it.x }.forEach { cursor -= it.width; placed[it.id] = cursor to 0 }
            cursor = main.height
            sides["below"].orEmpty().sortedBy { it.y }.forEach { placed[it.id] = 0 to cursor; cursor += it.height }
            cursor = 0
            sides["above"].orEmpty().sortedByDescending { it.y }.forEach { cursor -= it.height; placed[it.id] = 0 to cursor }
            val minX = placed.values.minOf { it.first }
            val minY = placed.values.minOf { it.second }
            return all.map { s ->
                val at = placed[s.mirrorOf ?: s.id] ?: return@map s
                s.copy(x = at.first - minX, y = at.second - minY, primary = s.id == main.id)
            }
        }
    }
}
