package com.dash.android.logs

import android.util.Log
import com.dash.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

/** One line of a log — DASH's own or the machine's. [part] is the plain-words group the viewer filters by. */
data class LogLine(
    val time: Long,
    val level: Int,
    val tag: String,
    val message: String,
    val part: String = LogParts.of(tag),
)

/**
 * One run of DASH, start to finish, as kept on disk. [stoppedUnexpectedly] when it never said it closed,
 * or closed straight after an error nobody caught — the power going, the program killed, a crash.
 */
data class LogRun(
    val file: File,
    val started: Long,
    val ended: Long?,
    val current: Boolean,
    val stoppedUnexpectedly: Boolean,
    val noticed: Boolean,
)

/**
 * **DASH's own log** (1.1.9) — Developer › Logs.
 *
 * Every line DASH logs (through `android.util.Log`, the one door every part of DASH logs through) and
 * anything else written to stderr is kept twice: the latest lines in memory, for the live view, and the
 * whole run on disk in `<data>/logs`, one file per run. The last [KEEP_RUNS] runs are kept (Roger,
 * 2026-10-21: "10 sounds fine"). A run's file is capped at [PART_BYTES]: past that it rolls over once,
 * keeping the newest, so one run can never hold more than twice that and a looping fault cannot fill the disk.
 *
 * **On disk it is complete**, private details and all — the files are the user's own, readable by their
 * account alone, and they are what is read when somebody is helping on the machine itself. Blanking
 * ([Blanker]) is applied to what is shown and to what leaves the machine.
 *
 * **A run that stopped unexpectedly** is one whose file has no closing line: DASH killed, the power cut,
 * or DASH ending within seconds of an error nothing caught. The next start says so once ([previousStop]).
 *
 * Writing is done by one thread of its own, so a slow disk never holds up whoever is logging — the
 * Android Auto session thread least of all.
 */
object LogBook {
    const val KEEP_RUNS = 10
    const val PART_BYTES = 5_000_000L
    private const val MEMORY_LINES = 5000
    private const val TAG = "DashLogs"

    /** An error nothing caught, this close to the end, means the end was the error's doing. */
    private const val CRASH_WINDOW_MS = 10_000L

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val CLOSED = "# DASH closed"
    private const val NOTICED = "# noticed"

    private val memory = ArrayDeque<LogLine>()
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var lastCrash = 0L
    @Volatile private var dir: File? = null
    @Volatile private var runFile: File? = null

    private val _revision = MutableStateFlow(0L)
    /** Moves on with every new line; the live view reads [recent] when it does. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _previousStop = MutableStateFlow<LogRun?>(null)
    /** The last run, when it stopped unexpectedly and nobody has been told yet. */
    val previousStop: StateFlow<LogRun?> = _previousStop.asStateFlow()

    private val _detail = MutableStateFlow(Log.minLevel <= Log.DEBUG)
    /** Detail on: DASH's debug lines are kept as well. Off by default; the choice is remembered. */
    val detail: StateFlow<Boolean> = _detail.asStateFlow()

    /** The run Developer › Logs should open on — set when the stopped notice's SHOW LOG is pressed. */
    val focus = MutableStateFlow<File?>(null)

    /**
     * Start catching every line, before anything else runs. Lines are kept in memory and queued for the
     * disk until [start] knows where the data folder is.
     */
    fun capture() {
        if (Log.sink != null) return
        val err = System.err
        Log.console = err
        Log.sink = ::record
        // Anything else on stderr — Java's and the libraries' own complaints — as lines of their own.
        System.setErr(PrintStream(LineCatcher(err), true))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            lastCrash = System.currentTimeMillis()
            Log.e("DashCrash", "uncaught in thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    /** Open this run's file under [home]/logs, put away runs past [KEEP_RUNS], and look at how the last one ended. */
    fun start(home: File) {
        if (dir != null) return
        val logs = File(home, "logs").apply { mkdirs() }
        dir = logs
        if (File(logs, "detail").exists()) setDetail(true, remember = false)

        val before = runs()
        before.firstOrNull()?.takeIf { it.stoppedUnexpectedly && !it.noticed }?.let { _previousStop.value = it }
        before.drop(KEEP_RUNS - 1).forEach { r -> r.file.delete(); part(r.file).delete() }

        var file = File(logs, "run-${LocalDateTime.now().format(fileStamp)}.log")
        var n = 2
        while (file.exists()) file = File(logs, file.nameWithoutExtension.substringBefore('~') + "~${n++}.log")
        runFile = file
        queue.put("# DASH ${BuildConfig.VERSION_NAME} started ${stamp.format(Instant.now())}")
        Thread({ writeLoop(file) }, "dash-logs").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread {
            val crashed = System.currentTimeMillis() - lastCrash < CRASH_WINDOW_MS
            queue.put(if (crashed) "# DASH stopped after an error" else "$CLOSED ${stamp.format(Instant.now())}")
            val done = java.util.concurrent.CountDownLatch(1)
            flushed = done
            queue.put(FLUSH)
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        })
    }

    /** The fatal end of the program itself — `main` failing — said before it goes. */
    fun crashed(e: Throwable) {
        lastCrash = System.currentTimeMillis()
        Log.e("DashCrash", "DASH stopped", e)
    }

    /** The latest lines of this run, oldest first. */
    fun recent(): List<LogLine> = synchronized(memory) { memory.toList() }

    /** Every kept run, newest first, this one included. */
    fun runs(): List<LogRun> {
        val logs = dir ?: return emptyList()
        return logs.listFiles { f -> f.name.startsWith("run-") && f.name.endsWith(".log") }.orEmpty()
            .sortedByDescending { it.name }
            .map { describe(it) }
    }

    /** A kept run, read back. This run comes from memory, which is complete up to [MEMORY_LINES]. */
    fun read(run: LogRun): List<LogLine> =
        if (run.current) recent() else (part(run.file).takeIf { it.exists() }?.let { parse(it) }.orEmpty() + parse(run.file))

    /** The user has been told about [run]; it is not mentioned again. */
    fun noticed(run: LogRun) {
        runCatching { run.file.appendText("$NOTICED\n") }
        if (_previousStop.value?.file == run.file) _previousStop.value = null
    }

    fun setDetail(on: Boolean, remember: Boolean = true) {
        Log.minLevel = if (on) Log.VERBOSE else Log.INFO
        _detail.value = on
        if (remember) dir?.let { d -> runCatching { File(d, "detail").let { if (on) it.writeText("on\n") else it.delete() } } }
    }

    // ---- Recording -----------------------------------------------------------------------------------

    private fun record(level: Int, tag: String, msg: String, tr: Throwable?) {
        val text = if (tr == null) msg else (if (msg.isEmpty()) "" else "$msg\n") + tr.stackTraceToString().trimEnd()
        add(LogLine(System.currentTimeMillis(), level, tag, text))
    }

    private fun add(line: LogLine) {
        synchronized(memory) {
            memory.addLast(line)
            if (memory.size > MEMORY_LINES) memory.removeFirst()
        }
        queue.put(format(line))
        _revision.value = _revision.value + 1
    }

    internal fun format(line: LogLine): String {
        val head = "${stamp.format(Instant.ofEpochMilli(line.time))} ${letter(line.level)} ${line.tag}: "
        return head + line.message.replace("\n", "\n\t")
    }

    private val FLUSH = String(charArrayOf('\u0000'))
    @Volatile private var flushed: java.util.concurrent.CountDownLatch? = null

    private fun writeLoop(file: File) {
        var out: BufferedWriter? = null
        var bytes = 0L
        fun open() { out = file.bufferedWriter(); bytes = 0 }
        runCatching { open() }.onFailure { Log.console.println("DASH: could not open its log ${file}: ${it.message}") }
        while (true) {
            val first = queue.take()
            val batch = mutableListOf(first).also { queue.drainTo(it) }
            for (s in batch) {
                if (s == FLUSH) { runCatching { out?.flush() }; flushed?.countDown(); continue }
                val w = out ?: continue
                runCatching {
                    w.write(s); w.newLine()
                    bytes += s.length + 1
                    if (bytes > PART_BYTES) {
                        // Roll over: the older half becomes the run's ".1", and the run carries on fresh.
                        w.close()
                        val older = part(file)
                        older.delete()
                        file.renameTo(older)
                        open()
                        out?.write("# continued — the lines before are in ${older.name}"); out?.newLine()
                    }
                }
            }
            runCatching { out?.flush() }
        }
    }

    // ---- Reading kept runs ---------------------------------------------------------------------------

    private fun part(f: File) = File(f.parentFile, f.name + ".1")

    private fun describe(f: File): LogRun {
        val current = f == runFile
        val head = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()
        val started = head?.let { STARTED.find(it)?.groupValues?.get(1) }?.let { parseTime(it) }
            ?: runCatching { LocalDateTime.parse(f.name.removePrefix("run-").take(15), fileStamp).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(f.lastModified())
        val tail = tail(f)
        val closed = tail.any { it.startsWith(CLOSED) }
        val lastTime = tail.asReversed().firstNotNullOfOrNull { LINE.find(it)?.groupValues?.get(1)?.let(::parseTime) }
        val ended = if (current) null else tail.firstOrNull { it.startsWith(CLOSED) }?.removePrefix(CLOSED)?.trim()?.let(::parseTime) ?: lastTime
        return LogRun(f, started, ended, current, stoppedUnexpectedly = !current && !closed, noticed = tail.any { it.startsWith(NOTICED) })
    }

    /** The last few kilobytes of a file, as lines — enough for how a run ended. */
    private fun tail(f: File): List<String> = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val from = maxOf(0L, r.length() - 8192)
            r.seek(from)
            val b = ByteArray((r.length() - from).toInt()); r.readFully(b)
            String(b, Charsets.UTF_8).lines().let { if (from > 0) it.drop(1) else it }
        }
    }.getOrDefault(emptyList())

    internal fun parse(f: File): List<LogLine> {
        val lines = mutableListOf<LogLine>()
        runCatching {
            f.forEachLine { s ->
                when {
                    s.startsWith("\t") && lines.isNotEmpty() -> lines[lines.lastIndex] = lines.last().let { it.copy(message = it.message + "\n" + s.drop(1)) }
                    s.startsWith("# ") && !s.startsWith(NOTICED) -> {
                        val t = STARTED.find(s)?.groupValues?.get(1)?.let(::parseTime)
                            ?: s.removePrefix(CLOSED).trim().takeIf { s.startsWith(CLOSED) }?.let(::parseTime)
                        lines += LogLine(t ?: lines.lastOrNull()?.time ?: f.lastModified(), Log.INFO, "DASH", s.removePrefix("# "))
                    }
                    else -> LINE.find(s)?.let { m ->
                        val (time, level, tag, msg) = m.destructured
                        lines += LogLine(parseTime(time) ?: 0L, levelOf(level), tag, msg)
                    }
                }
            }
        }
        return lines
    }

    private val LINE = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) ([VDIWE]) ([^:]+): (.*)$""")
    private val STARTED = Regex("""started (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})""")

    private fun parseTime(s: String): Long? =
        runCatching { LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    fun letter(level: Int) = when (level) {
        Log.VERBOSE -> "V"; Log.DEBUG -> "D"; Log.INFO -> "I"; Log.WARN -> "W"; else -> "E"
    }

    private fun levelOf(c: String) = when (c) {
        "V" -> Log.VERBOSE; "D" -> Log.DEBUG; "I" -> Log.INFO; "W" -> Log.WARN; else -> Log.ERROR
    }

    /** stderr that is not DASH's own log lines, cut into lines and kept as "Java" — still printed as before. */
    private class LineCatcher(private val through: OutputStream) : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(b: Int) {
            through.write(b)
            synchronized(pending) {
                if (b == '\n'.code) flushLine() else pending.write(b)
            }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            through.write(b, off, len)
            synchronized(pending) {
                for (i in off until off + len) if (b[i] == '\n'.code.toByte()) flushLine() else pending.write(b[i].toInt())
            }
        }
        override fun flush() = through.flush()
        private fun flushLine() {
            val s = pending.toString(Charsets.UTF_8).trimEnd('\r')
            pending.reset()
            if (s.isBlank()) return
            // A stack trace's "at …" lines belong to the line before them.
            synchronized(memory) {
                val last = memory.lastOrNull()
                if (last != null && last.tag == "Java" && (s.startsWith("\tat ") || s.startsWith("\t...") || s.startsWith("Caused by"))) {
                    memory.removeLast(); memory.addLast(last.copy(message = last.message + "\n" + s.trimStart('\t')))
                    queue.put("\t" + s.trimStart('\t'))
                    _revision.value = _revision.value + 1
                    return
                }
            }
            add(LogLine(System.currentTimeMillis(), Log.WARN, "Java", s))
        }
    }
}
