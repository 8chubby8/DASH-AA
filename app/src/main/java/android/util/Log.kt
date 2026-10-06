package android.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * DASH-AA platform shim — `android.util.Log` on the desktop JVM.
 *
 * **Why a shim rather than an edit.** Eleven upstream files log through this class and nothing else
 * Android-specific. Providing the same API here lets those files compile byte-identical to upstream,
 * which is what keeps the fork cheap to sync (see FORK.md). Output goes to stderr in logcat's own
 * one-line shape, so `./gradlew run` reads like `adb logcat` did.
 */
object Log {
    @Volatile var minLevel: Int = if (System.getenv("DASH_VERBOSE") != null) VERBOSE else INFO

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @JvmStatic fun v(tag: String?, msg: String): Int = write(VERBOSE, "V", tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String, tr: Throwable?): Int = write(VERBOSE, "V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String): Int = write(DEBUG, "D", tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String, tr: Throwable?): Int = write(DEBUG, "D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String): Int = write(INFO, "I", tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String, tr: Throwable?): Int = write(INFO, "I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String): Int = write(WARN, "W", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String, tr: Throwable?): Int = write(WARN, "W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(WARN, "W", tag, "", tr)
    @JvmStatic fun e(tag: String?, msg: String): Int = write(ERROR, "E", tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String, tr: Throwable?): Int = write(ERROR, "E", tag, msg, tr)

    private fun write(level: Int, letter: String, tag: String?, msg: String, tr: Throwable?): Int {
        if (level < minLevel) return 0
        val line = "${LocalTime.now().format(clock)} $letter/${tag ?: "DASH"}: $msg"
        synchronized(this) {
            System.err.println(line)
            tr?.printStackTrace()
        }
        return line.length
    }
}
