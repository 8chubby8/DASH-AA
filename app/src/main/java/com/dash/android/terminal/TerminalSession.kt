package com.dash.android.terminal

import android.util.Log
import com.dash.android.terminal.linux.Pty
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.model.TextBufferChangesListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **One shell in DASH's terminal** (DASH-AA 1.1.10). JetBrains' JediTerm does the understanding — what
 * each escape sequence the shell sends means, the screen, the scrollback, the cursor; DASH draws it
 * ([com.dash.android.ui.terminal.TerminalContent]) and carries the keys back. JediTerm's own Swing
 * widget is not used: drawn in Compose, the terminal sits under DASH's chrome like any other tab, and
 * the on-screen keyboard, the settings blind and the theme all work over it.
 *
 * Shared code apart from the [Pty] it is handed.
 */
class TerminalSession private constructor(cols: Int, rows: Int, shell: String?) {

    private val styles = StyleState()
    val buffer = TerminalTextBuffer(cols, rows, styles, HISTORY)

    @Volatile var cursorX = 0; private set
    /** 1 = the top row, as JediTerm counts. */
    @Volatile var cursorY = 1; private set
    @Volatile var cursorShown = true; private set
    @Volatile var title = ""; private set
    @Volatile var bracketedPaste = false; private set

    /** Goes up by one whenever anything on the screen changes; the tab redraws on it. */
    val revision: StateFlow<Long> get() = _revision.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    private val counter = AtomicLong()
    private fun changed() { _revision.value = counter.incrementAndGet() }

    private val _ended = MutableStateFlow(false)
    /** True once the shell has gone — `exit`, or it was closed. */
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    private val display = object : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) { cursorX = x; cursorY = y; changed() }
        override fun setCursorShape(shape: CursorShape?) {}
        override fun beep() {}
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) { changed() }
        override fun setCursorVisible(visible: Boolean) { cursorShown = visible; changed() }
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) { changed() }
        override fun getWindowTitle(): String = title
        override fun setWindowTitle(name: String) { title = name; changed() }
        override fun getSelection(): TerminalSelection? = null
        // Mouse reporting (mc, htop's clicks) is not offered yet: a tap on the terminal is DASH's.
        override fun terminalMouseModeSet(mode: MouseMode) {}
        override fun setMouseFormat(format: MouseFormat) {}
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
        override fun setBracketedPasteMode(enabled: Boolean) { bracketedPaste = enabled }
    }

    val terminal = JediTerminal(display, buffer, styles)

    private val pty: Pty = Pty.start(cols, rows, shell)

    private val executors = object : TerminalExecutorServiceManager {
        private val single = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "terminal-out").apply { isDaemon = true } }
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "terminal-work").apply { isDaemon = true } }
        override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = single
        override fun getUnboundedExecutorService(): ExecutorService = pool
        override fun shutdownWhenAllExecuted() { single.shutdown(); pool.shutdown() }
    }

    // Typing ahead of the shell (JetBrains' guess at what will be echoed) is for slow remote links; off here.
    private val noTypeAhead = object : TypeAheadTerminalModel {
        override fun insertCharacter(ch: Char, index: Int) {}
        override fun removeCharacters(from: Int, count: Int) {}
        override fun moveCursor(index: Int) {}
        override fun forceRedraw() {}
        override fun clearPredictions() {}
        override fun lock() {}
        override fun unlock() {}
        override fun isUsingAlternateBuffer(): Boolean = buffer.isUsingAlternateBuffer
        override fun getCurrentLineWithCursor() = TypeAheadTerminalModel.LineWithCursorX(StringBuffer(), 0)
        override fun getTerminalWidth(): Int = buffer.width
        override fun isTypeAheadEnabled(): Boolean = false
        override fun getLatencyThreshold(): Long = 0
        override fun getShellType() = TypeAheadTerminalModel.ShellType.Unknown
    }

    private val starter = TerminalStarter(terminal, pty, TtyBasedArrayDataStream(pty), TerminalTypeAheadManager(noTypeAhead), executors)

    init {
        buffer.addChangesListener(object : TextBufferChangesListener {
            override fun linesChanged(fromIndex: Int) = changed()
            override fun historyCleared() = changed()
            override fun widthResized() = changed()
        })
        Thread({
            try { starter.start() } catch (e: Throwable) { Log.w(TAG, "Terminal stopped: ${e.message}") }
            _ended.value = true
            changed()
            Log.i(TAG, "Shell ended")
            executors.shutdownWhenAllExecuted()
        }, "terminal-in").apply { isDaemon = true }.start()
    }

    /** Characters typed (from either keyboard), as the shell should receive them. */
    fun send(text: String) { if (text.isNotEmpty() && !_ended.value) starter.sendString(text, true) }

    fun send(bytes: ByteArray) { if (bytes.isNotEmpty() && !_ended.value) starter.sendBytes(bytes, true) }

    /** Pasted text: marked as pasted when the program asked to know (so a pasted line is not run by itself). */
    fun paste(text: String) {
        val clean = text.replace("\r\n", "\r").replace('\n', '\r')
        send(if (bracketedPaste) "\u001b[200~$clean\u001b[201~" else clean)
    }

    /** What a key with no character sends — arrows, F-keys, Home — in the mode the program asked for. */
    fun keyCode(vk: Int, modifiers: Int): ByteArray? = terminal.getCodeForKey(vk, modifiers)

    /** The space DASH has for it changed: [cols] × [rows] characters. */
    fun resize(cols: Int, rows: Int) {
        if (cols < 2 || rows < 2) return
        if (cols == buffer.width && rows == buffer.height) return
        starter.postResize(TermSize(cols, rows), RequestOrigin.User)
    }

    fun close() = pty.close()

    companion object {
        private const val TAG = "Terminal"
        /** Lines kept above the screen to scroll back through. */
        const val HISTORY = 5000

        fun available(): Boolean = Pty.available()

        /** A new shell — the user's own unless [shell] names another (the tests use plain sh). */
        fun start(cols: Int, rows: Int, shell: String? = null): TerminalSession = TerminalSession(cols, rows, shell)
    }
}

/**
 * **The terminal DASH keeps** — one shell for as long as DASH runs. Leaving the tab leaves it running
 * (a long job carries on), and coming back finds it as it was.
 */
object DashTerminal {
    private val _session = MutableStateFlow<TerminalSession?>(null)
    val session: StateFlow<TerminalSession?> = _session.asStateFlow()

    /** The shell to start in place of the user's own — for the tests and screenshots only. */
    internal var shell: String? = null

    /** The running shell, or a new one [cols] × [rows] when there is none or the last one has ended. */
    fun open(cols: Int, rows: Int): TerminalSession? {
        _session.value?.let { if (!it.ended.value) return it }
        return runCatching { TerminalSession.start(cols, rows, shell) }
            .onFailure { Log.w("Terminal", "No terminal: ${it.message}") }
            .getOrNull()
            .also { _session.value = it }
    }

    /** Ends the shell (and what it runs); the next [open] starts a fresh one. */
    fun restart() {
        _session.value?.close()
        _session.value = null
    }
}
