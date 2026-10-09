package com.dash.android.power.linux

import android.util.Log
import java.io.File

/**
 * Restart DASH (1.1.7) — Power's way out on a machine with no desktop to leave to: DASH closes, and starts
 * again the way it was started. A small shell, on its own (`setsid`), waits for this DASH to be gone — its
 * screens put back, its phone and modules let go — then starts the same command with the same environment.
 * Not root: DASH starting itself, as its user. With no shell to do it, nothing happens and DASH stays.
 */
object Relaunch {
    /** Start DASH again once this one has gone. True when the new one is on its way — the caller then closes this one. */
    fun schedule(): Boolean {
        val command = runCatching { File("/proc/self/cmdline").readText().split('\u0000').filter { it.isNotEmpty() } }.getOrNull()
        if (command.isNullOrEmpty()) return false
        val pid = ProcessHandle.current().pid()
        return runCatching {
            ProcessBuilder(listOf("setsid", "sh", "-c", "while kill -0 $pid 2>/dev/null; do sleep 0.2; done; exec \"\$@\"", "dash-aa") + command)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            true
        }.onFailure { Log.w("DashPower", "could not restart DASH: ${it.message}") }.getOrDefault(false)
    }
}
