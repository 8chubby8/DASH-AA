package com.dash.android.aa.input

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.io.File

/**
 * Real multi-touch, read straight from the touchscreen's kernel device.
 *
 * **Why this exists.** DASH-AA's window runs under XWayland, and X11 (as Java's AWT uses it) sees a
 * touchscreen as one emulated mouse: one finger, no pinch. That is fine for DASH's own chrome — every
 * DASH control is a tap — but Android Auto's map wants two fingers. So when a touchscreen is present
 * and readable (the user is in the `input` group, as on this machine), DASH-AA reads its multi-touch
 * protocol directly and hands gestures that *start inside the viewport* to Android Auto; everything
 * else carries on reaching DASH through the window as normal.
 *
 * **Capability-detected, never required.** No touchscreen, no `input` group, or a device that will not
 * open: [start] reports false and the viewport takes touches from the window instead — one finger,
 * which still drives Android Auto completely bar pinch.
 *
 * **Where a touch lands.** The touchscreen is assumed to cover the monitor DASH-AA is shown on, which is
 * what an in-car touch display is. Its raw range is normalised to that monitor and handed to [onTouch]
 * as fractions of the *panel*; the host turns them with the screen ([panelToScreen], 1.1.5) and maps
 * them into the viewport.
 */
class EvdevTouch(
    /** [timeMillis]: when the touchscreen saw it, on the wall clock (the kernel's stamp, 1.2.5). */
    private val onTouch: (kind: Kind, slotKey: Long, screenX: Float, screenY: Float, timeMillis: Long) -> Unit,
) {
    enum class Kind { DOWN, MOVE, UP }

    data class Device(val path: String, val name: String, val usbId: String? = null)

    @Volatile var device: Device? = null
        private set
    @Volatile var lastActivityNanos: Long = 0L
        private set
    @Volatile private var running = false

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun open(path: String, flags: Int): Int
        fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun ioctl(fd: Int, request: NativeLong, arg: Memory): Int
        fun close(fd: Int): Int
        fun poll(fds: Memory, nfds: NativeLong, timeout: Int): Int
    }

    fun start(): Boolean {
        val found = findTouchscreen() ?: return false
        val lib = runCatching { Native.load("c", LibC::class.java) }.getOrNull() ?: return false
        val fd = lib.open(found.path, 0 /* O_RDONLY */)
        if (fd < 0) {
            Log.i(TAG, "touchscreen ${found.name} not readable (${found.path}) — single-touch through the window")
            return false
        }
        val xRange = absRange(lib, fd, ABS_MT_POSITION_X) ?: return false.also { lib.close(fd) }
        val yRange = absRange(lib, fd, ABS_MT_POSITION_Y) ?: return false.also { lib.close(fd) }
        device = found
        running = true
        Thread({ readLoop(lib, fd, xRange, yRange) }, "dash-touch").apply { isDaemon = true }.start()
        Log.i(TAG, "multi-touch from ${found.name} (${found.path}), x ${xRange}, y $yRange")
        return true
    }

    fun stop() { running = false }

    private fun absRange(lib: LibC, fd: Int, code: Int): IntRange? {
        val info = Memory(24)
        // EVIOCGABS(code) = _IOR('E', 0x40 + code, struct input_absinfo)
        val request = (2L shl 30) or (24L shl 16) or ('E'.code.toLong() shl 8) or (0x40L + code)
        if (lib.ioctl(fd, NativeLong(request), info) < 0) return null
        val min = info.getInt(4)
        val max = info.getInt(8)
        return if (max > min) min..max else null
    }

    private class Slot { var tracking = -1; var x = 0; var y = 0; var down = false; var moved = false; var lifted = false }

    private fun readLoop(lib: LibC, fd: Int, xr: IntRange, yr: IntRange) {
        val slots = Array(MAX_SLOTS) { Slot() }
        val fingers = Fingers(onTouch)
        var slot = 0
        val buf = ByteArray(EVENT_SIZE * 64)
        val poll = Memory(8).apply { setInt(0, fd); setShort(4, 1 /* POLLIN */); setShort(6, 0) }
        try {
            while (running) {
                // A lift held back (see Fingers) is passed on when its moment is up, even if the screen is quiet.
                val wait = fingers.due(System.currentTimeMillis())
                if (wait != null && lib.poll(poll, NativeLong(1), wait.toInt().coerceAtLeast(1)) == 0) continue
                val n = lib.read(fd, buf, NativeLong(buf.size.toLong())).toInt()
                if (n <= 0) break
                var off = 0
                while (off + EVENT_SIZE <= n) {
                    val millis = s64(buf, off) * 1000 + s64(buf, off + 8) / 1000
                    val type = u16(buf, off + 16)
                    val code = u16(buf, off + 18)
                    val value = s32(buf, off + 20)
                    off += EVENT_SIZE
                    if (type == EV_ABS) {
                        val s = slots[slot]
                        when (code) {
                            ABS_MT_SLOT -> slot = value.coerceIn(0, MAX_SLOTS - 1)
                            ABS_MT_TRACKING_ID -> if (value < 0) { if (s.tracking >= 0) s.lifted = true }
                                else { s.tracking = value; s.down = true }
                            ABS_MT_POSITION_X -> { s.x = value; s.moved = true }
                            ABS_MT_POSITION_Y -> { s.y = value; s.moved = true }
                        }
                    } else if (type == EV_SYN && code == SYN_REPORT) {
                        lastActivityNanos = System.nanoTime()
                        fun fx(s: Slot) = (s.x - xr.first).toFloat() / (xr.last - xr.first)
                        fun fy(s: Slot) = (s.y - yr.first).toFloat() / (yr.last - yr.first)
                        // Lifts first, so a finger the screen lost and found again in this same report rejoins.
                        slots.forEachIndexed { i, s ->
                            if (s.lifted && !s.down) { fingers.up(i, millis); s.tracking = -1 }
                        }
                        slots.forEachIndexed { i, s ->
                            when {
                                s.down -> {
                                    fingers.down(i, fx(s), fy(s), millis)
                                    if (s.lifted) { fingers.up(i, millis); s.tracking = -1 }
                                }
                                s.moved && s.tracking >= 0 && !s.lifted -> fingers.move(i, fx(s), fy(s), millis)
                            }
                            s.down = false; s.moved = false; s.lifted = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "touchscreen read ended: ${e.message}")
        } finally {
            lib.close(fd)
        }
    }

    /**
     * The fingers on the screen, as fingers rather than as the touchscreen's numbered slots — and a finger
     * the screen loses for a moment in a gesture of two or more is not lifted (roadmap 1.2.5).
     *
     * **Why.** The Surface Pro 3's touchscreen sometimes loses a finger in the middle of a gesture and
     * finds it again a moment later, at the same spot, as a new touch (recorded 2026-10-24: one finger of
     * a two-finger rotate reported lifted, and a "new" one 110 ms later where it had been; the finger never
     * left the glass). To the phone that was a lift and a fresh touch: the rotate broke off, a drag flicked
     * away and stopped. So the lift of a finger that has had company — a pinch, a rotate, where losing one
     * does harm — is held back for [REJOIN_MS], and a touch that comes down within [REJOIN_DISTANCE] of it
     * meanwhile is the same finger, carrying on. A lift not followed in time is passed on with its own
     * time. A finger alone is passed on at once: holding its lift would pause every flick, and taps and
     * double taps must stay as they are.
     *
     * Its own class so it can be tested without a screen. Positions are fractions of the panel.
     */
    internal class Fingers(private val out: (Kind, Long, Float, Float, Long) -> Unit) {
        private class Finger(val key: Long, var x: Float, var y: Float) {
            var company = false
        }
        private class Held(val finger: Finger, val liftedAt: Long)

        private val bySlot = HashMap<Int, Finger>()
        private val held = ArrayList<Held>()
        private var nextKey = 0L

        fun down(slot: Int, x: Float, y: Float, millis: Long) {
            val back = held.filter { distance(it.finger.x, it.finger.y, x, y) <= REJOIN_DISTANCE }
                .minByOrNull { distance(it.finger.x, it.finger.y, x, y) }
            if (back != null) {
                held.remove(back)
                bySlot[slot] = back.finger
                move(slot, x, y, millis)
                return
            }
            val f = Finger(nextKey++, x, y)
            if (bySlot.isNotEmpty() || held.isNotEmpty()) { f.company = true; bySlot.values.forEach { it.company = true } }
            bySlot[slot] = f
            out(Kind.DOWN, f.key, x, y, millis)
        }

        fun move(slot: Int, x: Float, y: Float, millis: Long) {
            val f = bySlot[slot] ?: return
            f.x = x; f.y = y
            out(Kind.MOVE, f.key, x, y, millis)
        }

        fun up(slot: Int, millis: Long) {
            val f = bySlot.remove(slot) ?: return
            if (f.company) held += Held(f, millis)
            else out(Kind.UP, f.key, f.x, f.y, millis)
        }

        /** Passes on the held lifts whose moment has come; the milliseconds until the next one, or null if none. */
        fun due(now: Long): Long? {
            val it = held.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (now - h.liftedAt >= REJOIN_MS) { it.remove(); out(Kind.UP, h.finger.key, h.finger.x, h.finger.y, h.liftedAt) }
            }
            return held.minOfOrNull { it.liftedAt + REJOIN_MS - now }
        }

        private fun distance(ax: Float, ay: Float, bx: Float, by: Float) =
            kotlin.math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

        companion object {
            /** How long a lost finger is waited for. Longer than the Surface's gap (110 ms seen), short to feel. */
            const val REJOIN_MS = 150L
            /** How near the lost finger a touch must come down to be it: about 1 cm on the Surface. */
            const val REJOIN_DISTANCE = 0.04f
        }
    }

    companion object {
        private const val TAG = "DashAaTouch"
        private const val EVENT_SIZE = 24                // struct input_event on 64-bit
        private const val EV_SYN = 0
        private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val INPUT_PROP_DIRECT = 1
        private const val MAX_SLOTS = 10

        /**
         * A touch at ([px], [py]) — fractions of the panel's own fixed corners, as the kernel reports
         * them — as fractions of the screen as it is now shown, when the display service has turned the
         * picture [quarterTurns] anticlockwise (Mutter's transform, Wayland's), first mirroring it if
         * [mirrored] (1.1.5). The same turn the display service gives the touchscreen for every other
         * program; DASH reads the device directly, so it makes the turn itself.
         */
        fun panelToScreen(px: Float, py: Float, quarterTurns: Int, mirrored: Boolean): Pair<Float, Float> {
            val x = if (mirrored) 1f - px else px
            return when (quarterTurns and 3) {
                1 -> (1f - py) to x
                2 -> (1f - x) to (1f - py)
                3 -> py to (1f - x)
                else -> x to py
            }
        }

        private fun s64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }
        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun s32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)

        /**
         * A touchscreen, from `/proc/bus/input/devices`: a device marked *direct* (drawn-on, unlike a
         * touchpad) that reports multi-touch positions.
         */
        fun findTouchscreen(): Device? = touchscreens().firstOrNull()

        /** Every touchscreen (1.1.5: Display › Touchscreen lists them all). */
        fun touchscreens(text: String? = runCatching { File("/proc/bus/input/devices").readText() }.getOrNull()): List<Device> {
            if (text == null) return emptyList()
            return text.split("\n\n").mapNotNull { block ->
                val name = Regex("""N: Name="(.*)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                val handler = Regex("""H: Handlers=.*?(event\d+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                val prop = bits(Regex("""B: PROP=([0-9a-f ]+)""").find(block)?.groupValues?.get(1))
                val abs = bits(Regex("""B: ABS=([0-9a-f ]+)""").find(block)?.groupValues?.get(1))
                val usb = Regex("""Vendor=([0-9a-f]{4}) Product=([0-9a-f]{4})""").find(block)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
                if (INPUT_PROP_DIRECT in prop && ABS_MT_POSITION_X in abs) Device("/dev/input/$handler", name, usb) else null
            }
        }

        /** The kernel prints a bitmap as hex words, most significant first. */
        private fun bits(words: String?): Set<Int> {
            if (words.isNullOrBlank()) return emptySet()
            val set = mutableSetOf<Int>()
            words.trim().split(' ').reversed().forEachIndexed { w, hex ->
                val v = hex.toULongOrNull(16) ?: return@forEachIndexed
                for (b in 0 until 64) if ((v shr b) and 1uL == 1uL) set += w * 64 + b
            }
            return set
        }
    }
}
