package com.dash.android.display.linux

import com.dash.android.display.DisplayFeatures
import com.dash.android.display.NightLight
import com.dash.android.display.Screen
import java.util.concurrent.TimeUnit

/**
 * One display program's way of reading and changing the screens (DASH-AA 1.1.5). [LinuxDisplay] finds
 * the one that answers and does everything else — remembering, turning, putting back — the same way
 * whichever it is.
 *
 * Each call runs on [LinuxDisplay]'s own thread and may take a moment; none is ever made from the UI.
 */
internal interface ScreenBackend {
    /** The display program, in plain words. */
    val program: String

    /** The screens now, or null when the display program has stopped answering. */
    fun read(): Snapshot?

    /** Set the screens up as [target]. Null when done; otherwise why not, in plain words. */
    fun write(target: List<Screen>, now: Snapshot): String?

    /** Set a screen's brightness through the display program; false when it cannot. */
    fun setBrightness(screen: Screen, level: Float): Boolean = false

    fun setNightLight(on: Boolean, kelvin: Int): Boolean = false

    /** Turn every screen's picture off or on; false when this display program cannot. */
    fun setScreensOn(on: Boolean): Boolean = false

    /** Which screen each touchscreen (by USB id) is pointed at, where the display program keeps that. */
    fun touchMapping(usbIds: List<String>, now: Snapshot): Map<String, String> = emptyMap()

    /** Point a touchscreen at a screen; false when this display program cannot. */
    fun mapTouchscreen(eventDevice: String, usbId: String?, screen: Screen): Boolean = false

    /** Call [onChange] whenever the screens change outside DASH. Returns a handle to stop. */
    fun watch(onChange: () -> Unit): AutoCloseable?
}

internal data class Snapshot(
    val screens: List<Screen>,
    val features: DisplayFeatures,
    /** Whether a screen's place on the desktop is its resolution divided by its scale (else the resolution). */
    val scaledLayout: Boolean = true,
    val nightLight: NightLight? = features.nightLight,
)

/** Run a program and return its exit code and everything it printed; -1 when it would not run or hung. */
internal fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), seconds: Long = 5): Pair<Int, String> = runCatching {
    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
    pb.environment().putAll(env)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "no answer" }
    p.exitValue() to out
}.getOrElse { -1 to (it.message ?: "${cmd.first()} missing") }

internal fun onPath(name: String): Boolean =
    (System.getenv("PATH") ?: "/usr/bin").split(':').any { java.io.File(it, name).canExecute() }

/** A watcher that polls — for display programs that announce nothing DASH can hear. */
internal fun poll(everyMs: Long, onChange: () -> Unit): AutoCloseable {
    val t = Thread({
        try {
            while (true) { Thread.sleep(everyMs); onChange() }
        } catch (_: InterruptedException) {
        }
    }, "dash-display-poll").apply { isDaemon = true; start() }
    return AutoCloseable { t.interrupt() }
}
