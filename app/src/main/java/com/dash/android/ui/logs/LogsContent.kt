package com.dash.android.ui.logs

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.DashApplication
import com.dash.android.logs.LogBook
import com.dash.android.logs.LogLine
import com.dash.android.logs.LogParts
import com.dash.android.logs.LogRun
import com.dash.android.logs.linux.Journal
import com.dash.android.logs.linux.LogSave
import com.dash.android.logs.linux.UsbSticks
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.settings.content.DashMenu
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.settings.content.MenuOption
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

// The level colours carry meaning, so they are literal, as the Serial Monitor's are.
private val C_RED = Color(0xFFE57373)
private val C_AMBER = Color(0xFFFFB74D)
private val C_BLUE = Color(0xFF4FC3F7)
private val C_GREEN = Color(0xFF81C784)

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.UK)
private val DAY = SimpleDateFormat("d MMM HH:mm", Locale.UK)
private const val ALL = "ALL"
private const val MACHINE_LINES = 5000

private enum class Levels(val label: String, val min: Int) {
    ALL_LEVELS("Everything", 0), WARNINGS("Warnings and errors", Log.WARN), ERRORS("Errors only", Log.ERROR)
}

/**
 * **Developer › Logs** (1.1.9).
 *
 * Two logs, one screen: **DASH**'s own — this run live, or any of the last ten runs kept on disk — and
 * the **machine**'s (the journal: USB, Bluetooth, Wi-Fi, sound, networks, power). Each is filtered by
 * level and by part in the column heads, as the Serial Monitor is, and searched.
 *
 * **Private details are blanked on screen** (Roger, 2026-10-21) — labels in their place, the same all the
 * way down ([com.dash.android.logs.Blanker]). SHOW PRIVATE shows them as they are: it is the user's own
 * machine. What is saved to a USB stick is always blanked.
 *
 * Read only. Leaving the tab stops nothing: DASH's log is kept whether or not it is open.
 */
@Composable
fun LogsContent() {
    val theme = LocalDashTheme.current
    val app = LocalContext.current.applicationContext as DashApplication
    val ink = theme.textColourSecondary
    val faint = ink.copy(alpha = 0.55f)

    var machine by remember { mutableStateOf(false) }
    var runs by remember { mutableStateOf(LogBook.runs()) }
    // The run being looked at; null is this run. SHOW LOG on the stopped notice opens on that run.
    var run by remember { mutableStateOf<LogRun?>(null) }
    val focus by LogBook.focus.collectAsState()
    LaunchedEffect(focus) {
        focus?.let { f -> runs = LogBook.runs(); run = runs.firstOrNull { it.file == f }; LogBook.focus.value = null }
    }
    var paused by remember { mutableStateOf(false) }
    var blanked by remember { mutableStateOf(true) }
    var levels by remember { mutableStateOf(Levels.ALL_LEVELS) }
    var part by remember { mutableStateOf(ALL) }
    val search = remember { mutableStateOf("") }
    var open by remember { mutableStateOf<LogLine?>(null) }
    var saving by remember { mutableStateOf(false) }
    val detail by LogBook.detail.collectAsState()
    val stopped by LogBook.previousStop.collectAsState()
    val live = run == null || run?.current == true

    // ---- The lines ----
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val access = remember { Journal.access() }

    // DASH, live: a fresh copy when something new arrives, at most four times a second.
    LaunchedEffect(machine, live, paused) {
        if (machine || !live || paused) return@LaunchedEffect
        var seen = -1L
        while (true) {
            val now = LogBook.revision.value
            if (now != seen) { seen = now; lines = LogBook.recent() }
            delay(250)
        }
    }
    // A kept run, or the machine's log for its time, read once.
    LaunchedEffect(machine, run) {
        if (live) return@LaunchedEffect
        loading = true
        val r = run!!
        lines = withContext(Dispatchers.IO) {
            if (machine) Journal.read(r.started, r.ended, MACHINE_LINES) else LogBook.read(r)
        }
        loading = false
    }
    // The machine, live: journalctl followed for as long as the tab shows it.
    val incoming = remember { ConcurrentLinkedQueue<LogLine>() }
    DisposableEffect(machine, live) {
        val p = if (machine && live) { lines = emptyList(); Journal.follow(2000) { incoming.add(it) } } else null
        onDispose { p?.destroy(); incoming.clear() }
    }
    LaunchedEffect(machine, live, paused) {
        if (!machine || !live) return@LaunchedEffect
        while (true) {
            if (!paused && incoming.isNotEmpty()) {
                val add = generateSequence { incoming.poll() }.toList()
                lines = (lines + add).takeLast(MACHINE_LINES)
            }
            delay(250)
        }
    }

    // ---- Blanking: one Blanker per look, so the labels hold still; each line blanked once ----
    val blanker = remember(blanked, machine, run) { if (blanked) LogSave.blanker(app) else null }
    val shownText = remember(blanker) { IdentityHashMap<LogLine, String>() }
    fun text(l: LogLine): String = blanker?.let { b -> shownText.getOrPut(l) { b.blank(l.message) } } ?: l.message
    fun from(l: LogLine): String = if (blanker != null && machine) blanker.blank(l.tag) else l.tag

    val parts = if (machine) Journal.parts else LogParts.all
    val q = search.value.trim()
    val shown = lines.filter { l ->
        l.level >= levels.min && (part == ALL || l.part == part) &&
            (q.isEmpty() || text(l).contains(q, ignoreCase = true) || from(l).contains(q, ignoreCase = true))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, paused, live) {
        if (live && !paused && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(BOX_PAD), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // DASH stopped unexpectedly last time — said here as well as on the screen, until it is seen.
        stopped?.let { s ->
            Banner(
                "DASH stopped unexpectedly on ${DAY.format(Date(s.ended ?: s.started))}. That run's log is kept.",
                ink,
            ) {
                LinkButton("SHOW THAT RUN", C_AMBER) { machine = false; runs = LogBook.runs(); run = runs.firstOrNull { it.file == s.file } }
                LinkButton("DISMISS") { LogBook.noticed(s) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PresetSegment(listOf("DASH", "Machine"), if (machine) 1 else 0, Modifier.width(220.dp)) { i ->
                machine = i == 1; part = ALL; open = null
            }
            RunPicker(runs, run, ink, faint, theme.font, onOpen = { runs = LogBook.runs() }) { run = it; open = null }
            Box(Modifier.weight(1f))
            if (live) LinkButton(if (paused) "RESUME" else "PAUSE", C_GREEN) { paused = !paused }
            if (!machine) LinkButton(if (detail) "DETAIL: ON" else "DETAIL: OFF", if (detail) C_BLUE else null) { LogBook.setDetail(!detail) }
            LinkButton(if (blanked) "SHOW PRIVATE" else "HIDE PRIVATE", if (blanked) null else C_AMBER) { blanked = !blanked }
            LinkButton("SAVE TO USB") { saving = !saving }
        }

        if (saving) SavePanel(app, run, ink, faint, theme.font) { saving = false }

        if (machine && !access.available) {
            Text(access.note, color = faint, fontSize = MAINBODY, fontFamily = theme.font)
            return@Column
        }
        if (machine && !access.whole) Text(access.note, color = C_AMBER, fontSize = BODY, fontFamily = theme.font)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyboardField(search, "Search the log", Modifier.widthIn(max = 420.dp).weight(1f), placeholder = "search")
            if (q.isNotEmpty()) LinkButton("CLEAR") { search.value = "" }
            Box(Modifier.weight(0.01f))
            Text(
                (if (shown.size != lines.size) "${shown.size} of ${lines.size} lines" else "${lines.size} lines") +
                    if (blanked) "  ·  private details blanked" else "",
                color = faint, fontSize = BODY, fontFamily = theme.font,
            )
        }

        // The grid's heads, two of them filters.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COL_GAP), verticalAlignment = Alignment.CenterVertically) {
            Head("TIME", W_TIME, faint, theme.font)
            FilterHead("LEVEL", W_LEVEL, if (levels == Levels.ALL_LEVELS) ALL else levels.label,
                Levels.values().map { MenuOption(it.label, it.name) }, levels.name, ink, faint, theme.font) { levels = Levels.valueOf(it) }
            FilterHead("PART", W_PART, part, (listOf(ALL) + parts).map { MenuOption(it, it) }, part, ink, faint, theme.font) { part = it }
            Head("FROM", W_FROM, faint, theme.font)
            Head("MESSAGE", null, faint, theme.font)
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> Text("Reading…", color = faint, fontSize = MAINBODY, fontFamily = theme.font)
                shown.isEmpty() -> Text(
                    if (lines.isEmpty()) (if (machine && live) "Waiting for the machine's log…" else "Nothing in this log.") else "Nothing matches.",
                    color = faint, fontSize = MAINBODY, fontFamily = theme.font,
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(shown) { l ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(if (open === l) ink.copy(alpha = 0.07f) else Color.Transparent)
                                .clickable { open = if (open === l) null else l },
                            horizontalArrangement = Arrangement.spacedBy(COL_GAP),
                        ) {
                            Cell(TIME.format(Date(l.time)), faint, W_TIME, theme.font)
                            Cell(levelWord(l.level), levelColour(l.level, faint), W_LEVEL, theme.font)
                            Cell(l.part, faint, W_PART, theme.font)
                            Cell(from(l), faint, W_FROM, theme.font)
                            Text(
                                text(l),
                                color = if (l.level >= Log.ERROR) C_RED else if (l.level == Log.WARN) C_AMBER else ink,
                                fontSize = BODY, fontFamily = theme.font,
                                maxLines = if (open === l) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val COL_GAP = 12.dp
private val W_TIME = 70.dp
private val W_LEVEL = 74.dp
private val W_PART = 110.dp
private val W_FROM = 150.dp

private fun levelWord(level: Int) = when {
    level >= Log.ERROR -> "Error"; level == Log.WARN -> "Warning"; level == Log.INFO -> "Info"; else -> "Detail"
}

private fun levelColour(level: Int, faint: Color) = when {
    level >= Log.ERROR -> C_RED; level == Log.WARN -> C_AMBER; level == Log.INFO -> faint; else -> C_BLUE
}

@Composable
private fun Banner(text: String, ink: Color, actions: @Composable () -> Unit) {
    val theme = LocalDashTheme.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, C_AMBER.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, color = ink, fontSize = BODY, fontFamily = theme.font, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Which run: this one, or one of the kept ones — each with when it ran and how it ended. */
@Composable
private fun RunPicker(runs: List<LogRun>, run: LogRun?, ink: Color, faint: Color, font: FontFamily, onOpen: () -> Unit, onPick: (LogRun?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun name(r: LogRun?): String = when {
        r == null || r.current -> "This run"
        else -> DAY.format(Date(r.started)) + (r.ended?.let { " – " + TIME.format(Date(it)).take(5) } ?: "") +
            if (r.stoppedUnexpectedly) "  · stopped unexpectedly" else ""
    }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, ink.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .clickable { onOpen(); open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name(run), color = if (run?.stoppedUnexpectedly == true) C_AMBER else ink, fontSize = BODY, fontFamily = font)
            Text("  ▾", color = faint, fontSize = BODY, fontFamily = font)
        }
        DashMenu(
            expanded = open,
            onDismiss = { open = false },
            options = runs.map { MenuOption(name(it), it.file.name) },
            selected = (run ?: runs.firstOrNull { it.current })?.file?.name,
            onSelect = { picked -> onPick(runs.firstOrNull { it.file.name == picked }?.takeIf { !it.current }) },
        )
    }
}

/** SAVE TO USB: the sticks plugged in, each one press away. */
@Composable
private fun SavePanel(app: DashApplication, run: LogRun?, ink: Color, faint: Color, font: FontFamily, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sticks by remember { mutableStateOf<List<UsbSticks.Stick>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Looked for again every few seconds, so a stick plugged in now appears.
        while (true) { sticks = withContext(Dispatchers.IO) { UsbSticks.list() }; delay(3000) }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.06f))
            .border(1.dp, ink.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Saves DASH's last ${LogBook.KEEP_RUNS} runs, the machine's log for the run you are looking at, and This Machine's " +
                "report — with private details blanked.",
            color = faint, fontSize = BODY, fontFamily = font,
        )
        val list = sticks
        when {
            busy -> Text("Saving…", color = ink, fontSize = BODY, fontFamily = font)
            result != null -> Text(result!!, color = ink, fontSize = BODY, fontFamily = font)
            list == null -> Text("Looking for USB sticks…", color = faint, fontSize = BODY, fontFamily = font)
            list.isEmpty() -> Text("No USB stick found — plug one in.", color = ink, fontSize = BODY, fontFamily = font)
            else -> list.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${s.name}  ${s.size}", color = ink, fontSize = BODY, fontFamily = font)
                    LinkButton("SAVE HERE", C_GREEN) {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { LogSave.save(app, s, run?.takeIf { !it.current }) }
                            result = r.getOrElse { "Not saved: ${it.message}" }
                            busy = false
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (result != null) LinkButton("SAVE AGAIN") { result = null }
            LinkButton("CLOSE") { onClose() }
        }
    }
}

@Composable
private fun Head(text: String, width: Dp?, colour: Color, font: FontFamily) {
    Text(text, color = colour, fontSize = BODY, letterSpacing = 1.2.sp, fontFamily = font,
        modifier = if (width != null) Modifier.width(width) else Modifier)
}

/** A column head that is its filter, as in the Serial Monitor: quiet when unset, outlined when set. */
@Composable
private fun FilterHead(
    name: String, width: Dp, shownValue: String, options: List<MenuOption>, selected: String,
    ink: Color, faint: Color, font: FontFamily, onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val on = shownValue != ALL
    Box(Modifier.width(width)) {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp))
                .then(if (on) Modifier.border(1.dp, ink.copy(alpha = 0.45f), RoundedCornerShape(5.dp)) else Modifier)
                .clickable { open = true }
                .padding(horizontal = if (on) 5.dp else 0.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (on) shownValue else name, color = if (on) ink else faint, fontSize = BODY,
                letterSpacing = if (on) 0.sp else 1.2.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Text(" ▾", color = if (on) ink else faint, fontSize = BODY, fontFamily = font)
        }
        DashMenu(expanded = open, onDismiss = { open = false }, options = options, selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun Cell(text: String, colour: Color, width: Dp, font: FontFamily) {
    Text(text, color = colour, fontSize = BODY, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width))
}
