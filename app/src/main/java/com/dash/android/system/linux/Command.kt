package com.dash.android.system.linux

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * **Running a program with a time limit that holds** (DASH-AA 1.2.1). Everything DASH-AA asks of the machine
 * through a program (`bluetoothctl`, `nmcli`, `wlr-randr`, `pw-dump`, `journalctl`…) goes through here.
 *
 * Why it exists: reading a program's output to the end and only *then* waiting for it with a time limit means
 * the limit never applies — the read itself waits for as long as the program runs. `bluetoothctl` on a machine
 * with no Bluetooth service waits for the service forever, and DASH with it: on DASH-AA Linux in a virtual
 * machine, opening System › This Machine froze DASH (Roger, 2026-10-21). Here the output is read alongside,
 * and the limit is the program's: past it, the program is stopped and the answer is null.
 *
 * The program's input is empty (never DASH's own), so nothing can sit waiting to be typed to.
 */
object Command {
    class Result(val code: Int, val out: String)

    /**
     * Run [cmd] for at most [seconds]. Null when it did not finish in time (it is stopped). Throws when the
     * program is not there — callers that care say so; most treat it as "not on this machine".
     * [errors]: its error output joined to its output (true), or thrown away.
     */
    fun run(cmd: List<String>, seconds: Long, env: Map<String, String> = emptyMap(), errors: Boolean = true): Result? {
        val pb = ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        if (errors) pb.redirectErrorStream(true) else pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = CompletableFuture<String>()
        Thread({ out.complete(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }, "dash-cmd")
            .apply { isDaemon = true }.start()
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        // Finished: what it printed is all there, unless something it started still holds its output open.
        return Result(p.exitValue(), runCatching { out.get(2, TimeUnit.SECONDS) }.getOrDefault(""))
    }

    fun run(vararg cmd: String, seconds: Long): Result? = run(cmd.toList(), seconds)
}
