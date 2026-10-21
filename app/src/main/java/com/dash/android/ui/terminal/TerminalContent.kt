package com.dash.android.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.DashApplication
import com.dash.android.connections.ConnectionsPreferences
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.system.linux.Keyboards
import com.dash.android.terminal.DashTerminal
import com.dash.android.terminal.TerminalPreferences
import com.dash.android.terminal.TerminalSession
import com.dash.android.terminal.TerminalSettings
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.keyboard.CarStill
import com.dash.android.ui.keyboard.Key
import com.dash.android.ui.keyboard.KeyPad
import com.dash.android.ui.keyboard.KeyRow
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.theme.LocalDashTheme
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle as TermStyle
import com.jediterm.terminal.util.CharUtils
import java.awt.event.KeyEvent as Awt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val C_GREEN = Color(0xFF81C784)
private val C_AMBER = Color(0xFFFFB74D)

/**
 * **Developer › Terminal** (DASH-AA 1.1.10). The machine's own command line, inside DASH — on DASH-AA
 * Linux there is no desktop behind DASH, so this is the only way to look at or fix the machine without
 * leaving it. It runs the user's shell **as the user, never as root** (on DASH-AA Linux `sudo` asks no
 * password: the machine is theirs). No safety gate, as native decided for its tools.
 *
 * **Which keyboard** (Roger, 2026-10-21: "If a keyboard is plugged in then it should use that. If there's
 * no keyboard then it can default to an on-screen keyboard"): with one plugged in, the tab is all
 * terminal and the real keys type into it; with none, DASH's keyboard sits under the terminal with the
 * keys a shell needs that a password does not (Esc, Tab, Ctrl, Alt, the arrows). Either can be swapped by
 * hand for the moment; a keyboard plugged in or pulled out puts it back to the rule. The car-moving lock
 * is the on-screen keyboard's own (Display › Touchscreen); a real keyboard is never locked.
 *
 * Scrolled with a finger or the wheel; text chosen with the mouse, or a long press and drag, then COPY;
 * PASTE types the clipboard in. The shell keeps running when the tab is left ([DashTerminal]).
 * [keyboardOnScreen] opens it with DASH's keyboard shown or hidden whatever is plugged in (the screenshots).
 *
 * **For native:** take this file, `TerminalSession` and the KeyPad change. The device is Linux's
 * (`terminal/linux/Pty`): native gives the session a connector over Android's own shell
 * (`/system/bin/sh`, run as the app), which is all an unrooted app has. The keyboard question is answered
 * by Android's `Configuration.keyboard` in place of `Keyboards`.
 */
@Composable
fun TerminalContent(keyboardOnScreen: Boolean? = null) {
    val theme = LocalDashTheme.current
    val app = LocalContext.current.applicationContext as DashApplication
    val ink = theme.textColourSecondary
    val faint = ink.copy(alpha = 0.55f)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    if (remember { !TerminalSession.available() }) {
        Column(Modifier.padding(BOX_PAD), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No terminal on this machine.", color = ink, fontSize = MAINBODY, fontFamily = theme.font)
            Text(
                "DASH needs a shell (bash or sh), setsid (part of util-linux) and the kernel's pseudo-terminals " +
                    "(/dev/ptmx). Install util-linux and a shell to use the Terminal.",
                color = faint, fontSize = BODY, fontFamily = theme.font,
            )
        }
        return
    }

    // ---- Settings and the keyboard question ----
    val prefs = remember { TerminalPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = TerminalSettings())
    val keyboards by remember { Keyboards.watch() }.collectAsState(initial = remember { Keyboards.plugged() })
    // The user's word for now; null follows the rule. A keyboard coming or going returns it to the rule.
    var choice by remember(keyboards.isEmpty()) { mutableStateOf(keyboardOnScreen) }
    val onScreen = choice ?: keyboards.isEmpty()
    val connections by remember { ConnectionsPreferences(app).settings }.collectAsState(initial = ConnectionsSettings())
    val still by remember {
        CarStill.of(app.controller.systemState.values, app.controller.database.modules, app.controller.reconciliation.activity)
    }.collectAsState(initial = null)
    val locked = connections.keyboardOnlyWhenStill && still == false

    // ---- The shell ----
    val session by DashTerminal.session.collectAsState()
    val ended by (session?.ended ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    var grid by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(grid, session) {
        val (cols, rows) = grid
        if (cols < 2 || rows < 2) return@LaunchedEffect
        val s = session
        if (s == null) DashTerminal.open(cols, rows) else s.resize(cols, rows)
    }

    // ---- What is shown: lines back into the scrollback, and the text chosen ----
    var scroll by remember(session) { mutableIntStateOf(0) }
    var selection by remember(session) { mutableStateOf<Pair<Cell, Cell>?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The real keyboard types into the terminal again after any of the tab's own buttons.
    LaunchedEffect(session, onScreen, settings.fontSize, selection == null) { runCatching { focus.requestFocus() } }

    fun send(text: String) {
        val s = DashTerminal.session.value ?: return
        scroll = 0; selection = null
        s.send(text)
    }
    fun sendKey(vk: Int) {
        val s = DashTerminal.session.value ?: return
        val mods = (if (ctrl) CTRL else 0) or (if (alt) ALT else 0)
        ctrl = false; alt = false
        scroll = 0; selection = null
        when (vk) {
            Awt.VK_ESCAPE -> s.send("\u001b")
            Awt.VK_TAB -> s.send("\t")
            else -> s.keyCode(vk, mods)?.let { s.send(it) }
        }
    }
    // A character from DASH's keyboard, with its Ctrl or Alt if one is lit.
    fun typed(text: String) {
        var out = text
        if (ctrl && text.length == 1) controlChar(text[0])?.let { out = it.toString() }
        if (alt) out = "\u001b" + out
        ctrl = false; alt = false
        send(out)
    }
    fun copy() {
        val s = session ?: return
        val sel = selection ?: return
        clipboard.setText(AnnotatedString(textOf(s, sel.first, sel.second)))
        selection = null
    }
    fun paste() {
        val t = clipboard.getText()?.text ?: return
        scroll = 0; selection = null
        DashTerminal.session.value?.paste(t)
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(BOX_PAD), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                when {
                    keyboards.isNotEmpty() -> "Keyboard: " + keyboards.first().name.let { if (it.startsWith("AT ")) "this machine's own" else it } +
                        if (keyboards.size > 1) " and ${keyboards.size - 1} more" else ""
                    else -> "No keyboard plugged in — DASH's keyboard is on the screen"
                },
                color = faint, fontSize = BODY, fontFamily = theme.font, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            LinkButton("A−") { scope.launch { prefs.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(TerminalPreferences.SMALLEST)) } } }
            LinkButton("A+") { scope.launch { prefs.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(TerminalPreferences.LARGEST)) } } }
            if (selection != null) LinkButton("COPY", C_GREEN) { copy() }
            LinkButton("PASTE") { paste() }
            LinkButton(if (onScreen) "HIDE KEYBOARD" else "SHOW KEYBOARD") { choice = !onScreen }
            LinkButton("START AGAIN", C_AMBER) { DashTerminal.restart() }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val s = session
            TerminalScreen(
                session = s,
                fontSize = settings.fontSize,
                scroll = scroll,
                selection = selection,
                ink = ink,
                modifier = Modifier
                    .fillMaxSize()
                    // Outside the focus target, so the keys reach it: every key is the terminal's.
                    .onPreviewKeyEvent { e -> handleKey(e.awtEventOrNull, s, onCopy = ::copy, onPaste = ::paste) { scroll = 0; selection = null } }
                    .focusRequester(focus)
                    .focusable(),
                onGrid = { cols, rows -> grid = cols to rows },
                onScroll = { by ->
                    val most = s?.buffer?.historyLinesCount ?: 0
                    scroll = (scroll + by).coerceIn(0, most)
                },
                onWheelInFullScreen = { up -> sendKey(if (up) Awt.VK_UP else Awt.VK_DOWN) },
                onSelect = { selection = it },
                onTap = { runCatching { focus.requestFocus() } },
            )
            if (ended) {
                Column(
                    Modifier.align(Alignment.Center).background(theme.backgroundColourSecondary.copy(alpha = 0.92f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("The shell has ended.", color = ink, fontSize = MAINBODY, fontFamily = theme.font)
                    LinkButton("START AGAIN", C_GREEN) { DashTerminal.restart() }
                }
            }
            if (scroll > 0) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    LinkButton("BACK TO THE BOTTOM", C_GREEN) { scroll = 0 }
                }
            }
        }

        if (onScreen) {
            // On the keyboard's own floor, as when it comes up for a box: its keys are drawn for that colour.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.backgroundColourPrimary)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (!locked) {
                    KeyRow {
                        Key("Esc") { sendKey(Awt.VK_ESCAPE) }
                        Key("Tab") { sendKey(Awt.VK_TAB) }
                        Key("Ctrl", lit = ctrl) { ctrl = !ctrl }
                        Key("Alt", lit = alt) { alt = !alt }
                        Key("←") { sendKey(Awt.VK_LEFT) }
                        Key("↑") { sendKey(Awt.VK_UP) }
                        Key("↓") { sendKey(Awt.VK_DOWN) }
                        Key("→") { sendKey(Awt.VK_RIGHT) }
                        Key("Home") { sendKey(Awt.VK_HOME) }
                        Key("End") { sendKey(Awt.VK_END) }
                        Key("PgUp") { sendKey(Awt.VK_PAGE_UP) }
                        Key("PgDn") { sendKey(Awt.VK_PAGE_DOWN) }
                        Key("|") { typed("|") }
                        Key("~") { typed("~") }
                        Key("/") { typed("/") }
                        Key("-") { typed("-") }
                    }
                }
                KeyPad(
                    locked = locked,
                    key = session,
                    type = { typed(it) },
                    back = { send("\u007f") },
                    doneLabel = "Enter",
                    done = { send("\r") },
                )
            }
        }
    }
}

/** A place on the terminal: [row] counts as JediTerm does — 0 the top of the screen, below 0 the scrollback. */
private data class Cell(val col: Int, val row: Int)

// java.awt.event.InputEvent's masks, which JediTerm's key encoder reads.
private const val SHIFT = 1
private const val CTRL = 2
private const val META = 4
private const val ALT = 8

/**
 * A key from a real keyboard. Keys with no character (arrows, F-keys, Enter, Backspace) and Ctrl+letter
 * are sent as they are pressed; characters as they are typed, so the keyboard's own layout — £ on a UK
 * board, AltGr's € — comes through. Ctrl+Shift+C and V copy and paste, as in a desktop terminal. Every
 * key is the terminal's, except F11, which stays the window's.
 */
private fun handleKey(e: Awt?, s: TerminalSession?, onCopy: () -> Unit, onPaste: () -> Unit, onTyped: () -> Unit): Boolean {
    if (e == null || s == null) return false
    when (e.id) {
        Awt.KEY_PRESSED -> {
            val vk = e.keyCode
            if (vk == Awt.VK_F11) return false
            val mods = (if (e.isShiftDown) SHIFT else 0) or (if (e.isControlDown) CTRL else 0) or
                (if (e.isAltDown) ALT else 0) or (if (e.isMetaDown) META else 0)
            if (e.isControlDown && e.isShiftDown && vk == Awt.VK_C) { onCopy(); return true }
            if (e.isControlDown && e.isShiftDown && vk == Awt.VK_V) { onPaste(); return true }
            val out: ByteArray? = when {
                vk == Awt.VK_ESCAPE -> byteArrayOf(27)
                vk == Awt.VK_TAB && !e.isShiftDown -> byteArrayOf(9)
                vk == Awt.VK_BACK_SPACE && e.isControlDown -> byteArrayOf(8)
                e.isControlDown && !e.isAltGraphDown -> controlCode(vk)?.let { byteArrayOf(it.toByte()) } ?: s.keyCode(vk, mods)
                else -> s.keyCode(vk, mods)
            }
            if (out != null) {
                onTyped()
                s.send(if (e.isAltDown && out.size == 1 && vk != Awt.VK_ESCAPE) byteArrayOf(27) + out else out)
            }
            return true
        }
        Awt.KEY_TYPED -> {
            val c = e.keyChar
            // Control characters were sent as their key went down.
            if (c == Awt.CHAR_UNDEFINED || Character.isISOControl(c)) return true
            if (e.isControlDown && !e.isAltGraphDown) return true
            onTyped()
            s.send(if (e.isAltDown && !e.isAltGraphDown) "\u001b$c" else c.toString())
            return true
        }
        else -> return true
    }
}

/** Ctrl with a key: the control character a terminal sends (Ctrl+C is 3, Ctrl+[ is Esc). */
private fun controlCode(vk: Int): Int? = when (vk) {
    in Awt.VK_A..Awt.VK_Z -> vk - Awt.VK_A + 1
    Awt.VK_SPACE, Awt.VK_2 -> 0
    Awt.VK_OPEN_BRACKET, Awt.VK_3 -> 27
    Awt.VK_BACK_SLASH, Awt.VK_4 -> 28
    Awt.VK_CLOSE_BRACKET, Awt.VK_5 -> 29
    Awt.VK_6 -> 30
    Awt.VK_SLASH, Awt.VK_MINUS, Awt.VK_7 -> 31
    Awt.VK_8 -> 127
    else -> null
}

/** The same, for a character from DASH's keyboard with its Ctrl lit. */
private fun controlChar(c: Char): Char? = when (val u = c.uppercaseChar()) {
    in 'A'..'Z' -> (u - 'A' + 1).toChar()
    '@', ' ', '2' -> 0.toChar()
    '[', '3' -> 27.toChar()
    '\\', '4' -> 28.toChar()
    ']', '5' -> 29.toChar()
    '^', '6' -> 30.toChar()
    '_', '/', '-', '7' -> 31.toChar()
    '?', '8' -> 127.toChar()
    else -> null
}

/** The text between two places, a line to a line — lines the shell wrapped are joined back up. */
private fun textOf(s: TerminalSession, a: Cell, b: Cell): String {
    val (from, to) = ordered(a, b)
    val out = StringBuilder()
    s.buffer.lock()
    try {
        for (row in from.row..to.row) {
            if (row < -s.buffer.historyLinesCount || row >= s.buffer.height) continue
            val line = s.buffer.getLine(row)
            val text = line.text.replace(CharUtils.DWC.toString(), "")
            val start = if (row == from.row) from.col.coerceAtMost(text.length) else 0
            val end = if (row == to.row) (to.col + 1).coerceAtMost(text.length) else text.length
            if (start < end) out.append(text, start, end)
            if (row != to.row) { if (line.isWrapped) Unit else { while (out.endsWith(" ")) out.setLength(out.length - 1); out.append('\n') } }
        }
    } finally {
        s.buffer.unlock()
    }
    return out.toString().trimEnd()
}

private fun ordered(a: Cell, b: Cell): Pair<Cell, Cell> =
    if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a to b else b to a

/**
 * The screen: JediTerm's buffer drawn cell by cell in a fixed-width font. Measures how many characters
 * fit and says so ([onGrid]), so the shell always knows the true size of its window.
 */
@Composable
private fun TerminalScreen(
    session: TerminalSession?,
    fontSize: Int,
    scroll: Int,
    selection: Pair<Cell, Cell>?,
    ink: Color,
    modifier: Modifier,
    onGrid: (Int, Int) -> Unit,
    onScroll: (Int) -> Unit,
    onWheelInFullScreen: (Boolean) -> Unit,
    onSelect: (Pair<Cell, Cell>?) -> Unit,
    onTap: () -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
    // One cell: the average of a long run, so a hundred columns land exactly where they should.
    val cell = remember(fontSize, measurer) {
        val r = measurer.measure("M".repeat(100), base)
        Size(r.size.width / 100f, r.size.height.toFloat())
    }
    var area by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(area, cell) {
        if (area.width > 0 && area.height > 0) onGrid((area.width / cell.width).toInt(), (area.height / cell.height).toInt())
    }
    val revision by (session?.revision ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(530); blink = !blink } }

    fun cellAt(p: Offset) = Cell((p.x / cell.width).toInt().coerceAtLeast(0), (p.y / cell.height).toInt() - scroll)

    Canvas(
        modifier
            .onSizeChanged { area = Size(it.width.toFloat(), it.height.toFloat()) }
            // The wheel scrolls back; in a full-screen program (less, an editor) it moves the cursor instead.
            .pointerInput(session, cell) {
                awaitPointerEventScope {
                    var rest = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Scroll) continue
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: continue
                        rest += dy * 3
                        val lines = rest.toInt()
                        if (lines == 0) continue
                        rest -= lines
                        if (session?.buffer?.isUsingAlternateBuffer == true) repeat(kotlin.math.abs(lines)) { onWheelInFullScreen(lines < 0) }
                        else onScroll(-lines)
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
            // A finger scrolls; a long press then a drag chooses text. The mouse chooses text by dragging.
            .pointerInput(session, cell, scroll) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTap()
                    val start = cellAt(down.position)
                    var choosing: Boolean
                    if (down.type == PointerType.Touch) {
                        var finished = false
                        var slop: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                            finished = true
                        }
                        when {
                            !finished -> { choosing = true; onSelect(start to start) }
                            slop == null -> { onSelect(null); return@awaitEachGesture }
                            else -> choosing = false
                        }
                    } else {
                        choosing = true
                        onSelect(null)
                    }
                    var rest = 0f
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (choosing) {
                            val at = cellAt(c.position)
                            if (at != start) moved = true
                            if (moved) onSelect(start to at)
                        } else {
                            rest += c.positionChange().y
                            val lines = (rest / cell.height).toInt()
                            if (lines != 0) { onScroll(lines); rest -= lines * cell.height }
                        }
                        c.consume()
                    }
                }
            },
    ) {
        val s = session ?: return@Canvas
        @Suppress("UNUSED_EXPRESSION") revision
        val sel = selection?.let { ordered(it.first, it.second) }
        s.buffer.lock()
        try {
            val rows = s.buffer.height
            for (y in 0 until rows) {
                val index = y - scroll
                if (index < -s.buffer.historyLinesCount) continue
                val line = s.buffer.getLine(index)
                var col = 0
                val top = y * cell.height
                // For a moment after the space shrinks (DASH's keyboard coming up, the window made smaller),
                // the shell's screen is bigger than the space until it is told; what falls outside waits.
                if (top >= size.height) break
                line.forEachEntry { entry ->
                    val len = entry.length
                    val style = entry.style
                    val (fg, bg) = colours(style, ink)
                    if (bg != null) drawRect(bg, Offset(col * cell.width, top), Size(len * cell.width, cell.height))
                    if (!entry.isNul) {
                        val text = entry.text.toString().replace(CharUtils.DWC.toString(), " ")
                        val x = col * cell.width
                        if (text.isNotBlank() && x < size.width) {
                            drawText(
                                measurer, text, Offset(x, top),
                                base.copy(
                                    color = fg,
                                    fontWeight = if (style.hasOption(TermStyle.Option.BOLD)) FontWeight.Bold else null,
                                    fontStyle = if (style.hasOption(TermStyle.Option.ITALIC)) FontStyle.Italic else null,
                                    textDecoration = if (style.hasOption(TermStyle.Option.UNDERLINED)) TextDecoration.Underline else null,
                                ),
                                softWrap = false,
                                size = Size(size.width - x, (size.height - top).coerceAtMost(cell.height * 2)),
                            )
                        }
                    }
                    col += len
                }
                // The text chosen to copy.
                if (sel != null && index in sel.first.row..sel.second.row) {
                    val from = if (index == sel.first.row) sel.first.col else 0
                    val to = if (index == sel.second.row) sel.second.col + 1 else s.buffer.width
                    if (to > from) drawRect(ink.copy(alpha = 0.3f), Offset(from * cell.width, top), Size((to - from) * cell.width, cell.height))
                }
            }
            // The cursor: a block, half-lit, blinking — only when looking at the live screen.
            if (scroll == 0 && s.cursorShown && blink && !s.ended.value) {
                val cy = s.cursorY - 1
                drawRect(ink.copy(alpha = 0.6f), Offset(s.cursorX * cell.width, cy * cell.height), cell)
            }
        } catch (e: Exception) {
            // A fault drawing the terminal costs one frame, never DASH: thrown here it would close the window.
            if (drawFaults++ == 0) android.util.Log.w("Terminal", "could not draw the terminal: $e")
        } finally {
            s.buffer.unlock()
        }
    }
}

private var drawFaults = 0

/** A cell's colours from its style; a null background is the tab's own. */
private fun colours(style: TermStyle, ink: Color): Pair<Color, Color?> {
    var fg = style.foreground?.let { colour(it) } ?: ink
    var bg = style.background?.let { colour(it) }
    if (style.hasOption(TermStyle.Option.INVERSE)) {
        val f = fg
        fg = bg ?: Color.Black
        bg = f
    }
    if (style.hasOption(TermStyle.Option.DIM)) fg = fg.copy(alpha = 0.6f)
    if (style.hasOption(TermStyle.Option.HIDDEN)) fg = Color.Transparent
    return fg to bg
}

private fun colour(c: TerminalColor): Color {
    if (!c.isIndexed) return c.toColor().let { Color(it.red, it.green, it.blue) }
    val i = c.colorIndex
    return when {
        i < 16 -> ANSI[i]
        i < 232 -> {
            val n = i - 16
            fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
            Color(level(n / 36), level((n / 6) % 6), level(n % 6))
        }
        else -> (8 + (i - 232) * 10).let { Color(it, it, it) }
    }
}

// The sixteen terminal colours: xterm's, softened a little for a dark screen.
private val ANSI = listOf(
    Color(0xFF000000), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
    Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFE5E5E5),
    Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
    Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
)
