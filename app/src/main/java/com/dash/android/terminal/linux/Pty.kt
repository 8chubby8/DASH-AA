package com.dash.android.terminal.linux

import android.util.Log
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * **The terminal device** (DASH-AA 1.1.10): a Linux pseudo-terminal with the user's shell on the far end,
 * as JediTerm's [TtyConnector].
 *
 * Opened through libc with JNA (already DASH's for Bluetooth and USB), not with JetBrains' pty4j: pty4j is
 * EPL-1.0, which cannot be combined with DASH's GPL-3.0. The shell is started by `setsid --ctty`
 * (util-linux, on every Linux), so it leads a session of its own with this terminal as its controlling
 * terminal — Ctrl+C, job control and `sudo`'s prompt all work as in any terminal. The JVM never forks.
 *
 * **As the user, never as root** (CLAUDE.md, *The No-Root Constraint*): the shell is DASH's own child and
 * has exactly DASH's rights. On DASH-AA Linux `sudo` asks no password (*No Passwords*) — that is the user
 * at the screen commanding their own machine. DASH's code never types it.
 */
class Pty private constructor(private val master: Int, private val process: Process) : TtyConnector {

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun posix_openpt(flags: Int): Int
        fun grantpt(fd: Int): Int
        fun unlockpt(fd: Int): Int
        fun ptsname_r(fd: Int, buf: ByteArray, len: NativeLong): Int
        fun ioctl(fd: Int, request: NativeLong, winsize: ShortArray): Int
        fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun write(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun close(fd: Int): Int
        fun kill(pid: Int, sig: Int): Int
    }

    @Volatile private var closed = false

    /** What the shell writes. Ends (-1) when the shell and everything it started have let go of the terminal. */
    private val output = object : InputStream() {
        private val buf = ByteArray(8192)
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            val n = libc.read(master, buf, NativeLong(minOf(len, buf.size).toLong())).toInt()
            // The kernel answers EIO once the far end is closed: that is the end, not a fault.
            if (n <= 0) return -1
            System.arraycopy(buf, 0, b, off, n)
            return n
        }
    }
    private val reader = InputStreamReader(output, Charsets.UTF_8)

    override fun read(buf: CharArray, offset: Int, length: Int): Int = reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val chunk = if (sent == 0) bytes else bytes.copyOfRange(sent, bytes.size)
            val n = libc.write(master, chunk, NativeLong(chunk.size.toLong())).toInt()
            if (n <= 0) throw IOException("terminal write failed (errno ${Native.getLastError()})")
            sent += n
        }
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun isConnected(): Boolean = !closed && process.isAlive

    /** The window's size, in characters; the kernel tells the program in front (SIGWINCH). */
    override fun resize(termSize: TermSize) {
        setSize(libc, master, termSize.columns, termSize.rows)
    }

    override fun waitFor(): Int = process.waitFor()

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = "DASH Terminal"

    /** The shell's process number, for the tab to say what is running. */
    val pid: Long get() = process.pid()

    /**
     * Hang up, as closing a terminal window does: the shell is sent SIGHUP and passes it to its jobs. If
     * it is still there after two seconds it is stopped outright. The device itself is let go once the
     * shell has gone, so the reader is never left waiting on a closed descriptor.
     */
    override fun close() {
        if (closed) return
        val pid = process.pid().toInt()
        libc.kill(pid, SIGHUP)
        Thread({
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            closed = true
            libc.close(master)
        }, "terminal-hangup").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "Terminal"
        private const val O_RDWR = 0x2
        private const val O_NOCTTY = 0x100
        private const val O_CLOEXEC = 0x80000
        private val TIOCSWINSZ = NativeLong(0x5414)
        private const val SIGHUP = 1

        private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

        /**
         * The user's shell: `$SHELL`, else their entry in the password file, else bash, else sh — the
         * first that exists. Null only on a machine with none of them.
         */
        fun shell(): String? {
            val user = System.getProperty("user.name")
            val fromPasswd = runCatching {
                File("/etc/passwd").useLines { lines -> lines.map { it.split(':') }.firstOrNull { it.size >= 7 && it[0] == user }?.get(6) }
            }.getOrNull()
            return listOfNotNull(System.getenv("SHELL"), fromPasswd, "/bin/bash", "/bin/sh")
                .firstOrNull { it.isNotBlank() && File(it).canExecute() }
        }

        /** Whether this machine can give DASH a terminal at all: a shell, `setsid`, and the kernel's pseudo-terminals. */
        fun available(): Boolean = shell() != null && File("/dev/ptmx").exists() && setsid() != null

        private fun setsid(): String? = listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() }

        private fun setSize(libc: LibC, fd: Int, cols: Int, rows: Int) {
            libc.ioctl(fd, TIOCSWINSZ, shortArrayOf(rows.toShort(), cols.toShort(), 0, 0))
        }

        /** Opens a terminal [cols] × [rows] with the user's shell (or [shell]) running in it, in their home folder. */
        fun start(cols: Int, rows: Int, shell: String? = null): Pty {
            val shell = shell ?: shell() ?: throw IOException("no shell on this machine")
            val setsid = setsid() ?: throw IOException("setsid is missing (util-linux)")
            val master = libc.posix_openpt(O_RDWR or O_NOCTTY or O_CLOEXEC)
            if (master < 0) throw IOException("no pseudo-terminal (errno ${Native.getLastError()})")
            try {
                if (libc.grantpt(master) != 0 || libc.unlockpt(master) != 0) throw IOException("pseudo-terminal refused (errno ${Native.getLastError()})")
                val name = ByteArray(128)
                if (libc.ptsname_r(master, name, NativeLong(name.size.toLong())) != 0) throw IOException("pseudo-terminal has no name")
                val slave = String(name, 0, name.indexOf(0).let { if (it < 0) name.size else it })
                setSize(libc, master, cols, rows)

                // setsid gives the shell a session of its own and makes stdin (the terminal) its controlling
                // terminal; sh then reopens all three streams on it read-write, and becomes the user's shell.
                val pb = ProcessBuilder(
                    setsid, "--ctty", "/bin/sh", "-c",
                    "exec 0<>\"\$1\" 1>&0 2>&0; shift; exec \"\$@\"",
                    "sh", slave, shell,
                )
                pb.redirectInput(File(slave))
                pb.redirectOutput(File(slave))
                pb.redirectError(File(slave))
                pb.directory(File(System.getProperty("user.home")))
                pb.environment().apply {
                    put("TERM", "xterm-256color")
                    put("COLORTERM", "truecolor")
                    put("TERM_PROGRAM", "DASH")
                    // DASH's own switches are for DASH, not for programs started from its terminal.
                    keys.filter { it.startsWith("DASH_") && it != "DASH_HOME" }.forEach { remove(it) }
                }
                val process = pb.start()
                Log.i(TAG, "Shell started: $shell on $slave (pid ${process.pid()}), ${cols}×$rows")
                return Pty(master, process)
            } catch (e: Throwable) {
                libc.close(master)
                throw e
            }
        }
    }
}
