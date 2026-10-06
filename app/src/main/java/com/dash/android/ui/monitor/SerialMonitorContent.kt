package com.dash.android.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.prefs.DashPreferences
import com.dash.android.prefs.SERIAL_BUFFER_DEFAULT
import com.dash.android.prefs.SERIAL_BUFFER_OPTIONS
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.WireDirection
import com.dash.android.transport.WireEvent
import com.dash.android.ui.settings.content.DashMenu
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.settings.content.MenuOption
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.transports.LocalTransportDesk
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.TINY


private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
private const val ALL = "ALL"
private const val NONE = "—"

// The wire colours. These carry meaning (a line's message type, a line's direction), so they stay
// literal rather than theme tokens — Roger's call, 2026-07-27: a message's colour distinguishes a kind
// of traffic and should not shift when a user picks a preset.
//
// The bright set, because the settings surface went dark at 1.5.12 (backgroundColourSecondary
// 848482 → 2C2C2E). On the old mid-grey these ran 1.06–1.28:1 and were unreadable whatever hue was
// tried; on the dark surface they run 4.7–8.1:1.
private val C_GREEN = Color(0xFF81C784)
private val C_BLUE = Color(0xFF4FC3F7)
private val C_AMBER = Color(0xFFFFB74D)
private val C_PURPLE = Color(0xFFBA9EDB)
private val C_RED = Color(0xFFE57373)

/**
 * The measured width of every column.
 *
 * **Not constants.** DASH lets the user change its text size (Appearance › Size & Scale, 1.5.3), and
 * `sp` follows that scale — so any hardcoded `dp` column width truncates its content for somebody. It
 * did: TIME was widened twice by estimate and still clipped, because the estimate was made at a scale
 * the user was not using. Each column is instead measured from the *actual* rendered width of its own
 * 13sp header plus chevron and a representative 12sp sample, whichever is wider, so the grid is
 * correct at any text size and after any font change a v2 theme makes.
 */
private data class ColumnWidths(
    val time: Dp,
    val dir: Dp,
    val transport: Dp,
    val board: Dp,
    val message: Dp,
    val module: Dp,
    val payload: Dp,
) {
    val total: Dp = time + dir + transport + board + message + module + payload + COL_GAP * 6
}

private val COL_GAP = 12.dp

/** Breathing room either side of a cell's content, so the widest row never touches its neighbour. */
private val CELL_SLACK = 10.dp

@Composable
private fun rememberColumnWidths(font: FontFamily): ColumnWidths {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Keyed on the things that change rendered width: the font itself, and the display/text scale.
    return remember(font, density.density, density.fontScale) {
        val cell = TextStyle(fontSize = BODY, fontFamily = font)
        val head = TextStyle(fontSize = BODY, letterSpacing = 1.2.sp, fontFamily = font)
        fun px(text: String, style: TextStyle) = measurer.measure(AnnotatedString(text), style).size.width
        // The header carries a chevron on every filterable column; TIME and PAYLOAD do not, but
        // measuring one costs nothing and keeps the columns from sitting tight against each other.
        fun col(header: String, sample: String): Dp = with(density) {
            maxOf(px("$header ▾", head), px(sample, cell)).toDp() + CELL_SLACK
        }
        ColumnWidths(
            // Samples are the realistic worst case, fixed rather than taken from live traffic — widths
            // derived from what has arrived would jump about as new rows land, and a grid whose columns
            // move while you read it is worse than one that occasionally ellipsises.
            time = col("TIME", "88:88:88.888"),
            dir = col("DIR", "→"),
            transport = col("TRANSPORT", "Bluetooth"),
            board = col("BOARD", "Arduino UNO R4 WiFi"),
            message = col("MESSAGE", "SYSTEM_SIGNAL"),
            module = col("MODULE ID", "0000DA58EE07"),
            payload = col("PAYLOAD", "SYSTEM|Body WiFi|R4 body over WiFi: doors, lights|v1.1"),
        )
    }
}

/**
 * One wire line, split into the fields the grid shows and the filters work on.
 *
 * The DASH grammar is `TYPE|id|…` (arduino.md §2), so the message is the first field and — for every
 * message that names one — the module id is the second. `DISCOVER` is the deliberate exception: it is
 * the one broadcast addressed to everybody, so it carries no id and shows [NONE].
 */
private data class WireRowData(
    val timestamp: Long,
    val outbound: Boolean,
    val transport: String,
    val board: String,
    val message: String,
    val moduleId: String,
    val payload: String,
    val raw: String,
)

private fun parseRow(ev: WireEvent, devices: List<TransportDevice>): WireRowData {
    val fields = ev.line.split('|')
    val message = fields.getOrNull(0)?.trim().orEmpty().ifBlank { NONE }
    val moduleId = fields.getOrNull(1)?.trim().orEmpty().ifBlank { NONE }
    // Everything past the id is the payload — kept whole so nothing on the wire is hidden by the split.
    val payload = fields.drop(2).joinToString("|").ifBlank { NONE }
    return WireRowData(
        timestamp = ev.timestamp,
        outbound = ev.direction == WireDirection.OUT,
        transport = transportName(ev.transportTag),
        board = boardLabel(ev, devices),
        message = message,
        moduleId = moduleId,
        payload = payload,
        raw = ev.line,
    )
}

/** The pipe's name as a person would say it. The tag is DASH's internal word for a transport; a grid
 *  column is read by a human, so "bt" becomes "Bluetooth". A transport DASH doesn't know by name falls
 *  back to its own tag, uppercased — a fourth pipe still gets a sensible column and filter entry. */
private fun transportName(tag: String): String = when (tag.lowercase()) {
    "usb" -> "USB"
    "wifi" -> "WiFi"
    "bt" -> "Bluetooth"
    else -> tag.uppercase()
}

/**
 * The board a line came from or went to — its friendly name when the device is in the live list, else
 * its raw key.
 *
 * A line with no device key has no board: it is a broadcast that went to the *pipe*, so every board on
 * that pipe heard it. It shows [NONE] rather than borrowing the transport tag — the tag is its own
 * column now, and letting it stand in for a board made the board filter list a mix of two different
 * kinds of thing (Roger, 2026-07-27).
 */
private fun boardLabel(ev: WireEvent, devices: List<TransportDevice>): String {
    val key = ev.deviceKey ?: return NONE
    return devices.firstOrNull { it.key == key && it.transportTag == ev.transportTag }?.label ?: key
}

/**
 * Modules › Serial Monitor (roadmap 1.5.12) — the 1.4.1 instrument rehomed into the settings shell and
 * rebuilt as a **filterable grid**.
 *
 * A **read-only** view onto the transport's wire tap: watch the traffic fly past live. It never owns
 * the connection — since 1.5.11 that belongs to `DashApplication` — so leaving this tab leaves every
 * link running underneath, exactly as closing the old full-screen route did.
 *
 * **It no longer sends.** The 1.4.1 send box and its SEND TO selector were both cut in 1.5.12: the
 * lines a user actually needs to put on the wire are driven from Module Manager (REFRESH sweeps the
 * bus, install and uninstall run their own handshakes), and a text field that injects arbitrary
 * protocol was not earning the space it took. `TransportManager.send`/`sendTo` are untouched, so the
 * capability is there if a builder-facing use for it ever appears.
 *
 * **Why a grid.** Every line on the wire is already a structured record, not a sentence: `TYPE|id|…`.
 * Rendering it as one string made the eye do the parsing, and made the interesting question — "show me
 * only what this board said" or "only the BROADCASTs" — impossible on a bus with three pipes and a
 * board chattering ten times a second. Splitting the fields into columns is what gives the filters
 * something real to bite on.
 *
 * **The filters live in the header**, one per column that has a meaningful set of values: direction,
 * transport, board, message and module id. Time has none, deliberately. Each option list is built from what has
 * actually been *seen* — DASH renders exactly what exists rather than a hardcoded vocabulary, so a
 * message invented by a community module appears in the message filter the first time it is sent.
 * They combine, and they filter the *view* only: nothing is dropped from the buffer, so clearing a
 * filter brings the traffic straight back.
 *
 * There are no per-pipe status lights here — Transport Manager owns "what is connected and is it
 * healthy", and saying it in two tabs only invites the two to disagree.
 */
@Composable
fun SerialMonitorContent() {
    val theme = LocalDashTheme.current
    val desk = LocalTransportDesk.current
    if (desk == null) {
        Box(Modifier.fillMaxSize().padding(BOX_PAD)) {
            Text(
                "Transport desk unavailable.",
                color = theme.textColourSecondary.copy(alpha = 0.7f),
                fontSize = MAINBODY,
                fontFamily = theme.font,
            )
        }
        return
    }

    val events = remember { mutableStateListOf<WireEvent>() }
    var paused by remember { mutableStateOf(false) }
    val devices by desk.devices.collectAsState()

    // How many lines to keep — the user's call, persisted. See DashPreferences.serialBufferLines.
    val context = LocalContext.current
    val prefs = remember(context) { DashPreferences(context) }
    val scope = rememberCoroutineScope()
    val bufferLines by prefs.serialBufferLines.collectAsState(initial = SERIAL_BUFFER_DEFAULT)
    var bufferMenuOpen by remember { mutableStateOf(false) }

    // The COMMANDS drawer and what is typed into it.
    var commandsOpen by remember { mutableStateOf(false) }
    var sendText by remember { mutableStateOf("") }

    // Shrinking the buffer has to bite immediately, not merely cap future growth — picking 50 and
    // still seeing 500 lines would look broken.
    LaunchedEffect(bufferLines) {
        if (events.size > bufferLines) events.removeRange(0, events.size - bufferLines)
    }
    val listState = rememberLazyListState()

    // The four filters. ALL is the unset value in every one of them.
    var fDirection by remember { mutableStateOf(ALL) }
    var fTransport by remember { mutableStateOf(ALL) }
    var fBoard by remember { mutableStateOf(ALL) }
    var fMessage by remember { mutableStateOf(ALL) }
    var fModule by remember { mutableStateOf(ALL) }

    // Collect the wire tap. The flow's replay means recent history appears at once rather than a blank
    // screen — the tab is a window onto traffic that has been flowing all along.
    LaunchedEffect(desk) {
        desk.wire.collect { ev ->
            if (!paused) {
                events.add(ev)
                if (events.size > bufferLines) events.removeRange(0, events.size - bufferLines)
            }
        }
    }

    val rows = events.map { parseRow(it, devices) }
    val shown = rows.filter {
        (fDirection == ALL || (if (it.outbound) "OUT" else "IN") == fDirection) &&
            (fTransport == ALL || it.transport == fTransport) &&
            (fBoard == ALL || it.board == fBoard) &&
            (fMessage == ALL || it.message == fMessage) &&
            (fModule == ALL || it.moduleId == fModule)
    }

    LaunchedEffect(shown.size, paused) {
        if (!paused && shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
    }

    val ink = theme.textColourSecondary
    val inkFaint = ink.copy(alpha = 0.55f)
    val w = rememberColumnWidths(theme.font)

    // Always a broadcast — DASH messages carry their own addressing in field 1 of the grammar, so a
    // line finds the right module down whichever pipe it travels and every board ignores what is not
    // addressed to it. Sending closes the drawer: one line, one press, back to watching.
    val send: () -> Unit = {
        val line = sendText.trim()
        if (line.isNotEmpty()) {
            desk.send(line)
            sendText = ""
            commandsOpen = false
        }
    }
    val filtered = fDirection != ALL || fTransport != ALL || fBoard != ALL || fMessage != ALL || fModule != ALL

    Column(
        // imePadding() shrinks this column when the keyboard shows, so the weight(1f) grid resizes and
        // the COMMANDS drawer stays above the keyboard rather than behind it.
        modifier = Modifier.fillMaxSize().imePadding().padding(BOX_PAD),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Pinned controls. Actions left, readout right — two different kinds of thing, so separating
        // them stops the eye hunting. PAUSE and CLEAR carry a meaning of their own and so carry their
        // own colours; COMMANDS is an ordinary action and stays in the text colour.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LinkButton(if (paused) "RESUME" else "PAUSE", C_GREEN) { paused = !paused }
                LinkButton("CLEAR", C_RED) { events.clear() }
                LinkButton("COMMANDS") { commandsOpen = !commandsOpen }
                if (filtered) LinkButton("CLEAR FILTERS") {
                    fDirection = ALL; fTransport = ALL; fBoard = ALL; fMessage = ALL; fModule = ALL
                }
            }

            // The readout is also a control — click the count to choose how many lines are kept, the
            // same way each filter lives inside the column header it belongs to.
            Box {
                Text(
                    (if (filtered) "${shown.size} of ${rows.size} lines" else "${rows.size} lines") +
                        "  ·  keeping $bufferLines  ▾",
                    color = inkFaint,
                    fontSize = BODY,
                    fontFamily = theme.font,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { bufferMenuOpen = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                DashMenu(
                    expanded = bufferMenuOpen,
                    onDismiss = { bufferMenuOpen = false },
                    options = SERIAL_BUFFER_OPTIONS.map { MenuOption("$it lines", it.toString()) },
                    selected = bufferLines.toString(),
                    onSelect = { picked -> scope.launch { prefs.saveSerialBufferLines(picked.toInt()) } },
                )
            }
        }

        // COMMANDS — the send box, shown only when asked for. It was permanent furniture at the bottom
        // of the tab and did not earn the space: the monitor's job is watching, and typing a line by
        // hand is the rare case. Opening it pushes the grid down rather than floating over it, so it
        // never hides the traffic you are reading. Sending closes it, and so does pressing COMMANDS
        // again — a drawer, not a mode.
        if (commandsOpen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ink.copy(alpha = 0.08f))
                        .border(1.dp, ink.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    if (sendText.isEmpty()) {
                        Text(
                            "type a line, e.g. DISCOVER",
                            color = ink.copy(alpha = 0.4f),
                            fontSize = MAINBODY,
                            fontFamily = theme.font,
                        )
                    }
                    BasicTextField(
                        value = sendText,
                        onValueChange = { sendText = it.replace("\n", "") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = theme.font, fontSize = MAINBODY, color = ink),
                        cursorBrush = SolidColor(ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, ink.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable { send() }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("SEND", color = ink, fontSize = TINY, fontFamily = theme.font, letterSpacing = 1.sp)
                }
            }
        }

        // The grid. It scrolls sideways as one piece so the header and the rows can never drift out of
        // alignment, and the body scrolls vertically inside `fillsBox` with the header pinned above it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.width(w.total).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(COL_GAP),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderLabel("TIME", w.time, inkFaint, theme.font)
                FilterHeader("DIR", w.dir, fDirection, listOf("IN", "OUT"), ink, inkFaint, theme.font, centred = true) { fDirection = it }
                FilterHeader("TRANSPORT", w.transport, fTransport, rows.map { it.transport }.distinct().sorted(), ink, inkFaint, theme.font) { fTransport = it }
                FilterHeader("BOARD", w.board, fBoard, rows.map { it.board }.distinct().sorted(), ink, inkFaint, theme.font) { fBoard = it }
                FilterHeader("MESSAGE", w.message, fMessage, rows.map { it.message }.distinct().sorted(), ink, inkFaint, theme.font) { fMessage = it }
                FilterHeader("MODULE ID", w.module, fModule, rows.map { it.moduleId }.distinct().sorted(), ink, inkFaint, theme.font) { fModule = it }
                HeaderLabel("PAYLOAD", w.payload, inkFaint, theme.font)
            }

            Box(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    Text(
                        if (rows.isEmpty()) "Nothing on the wire yet." else "Nothing matches these filters.",
                        color = inkFaint,
                        fontSize = MAINBODY,
                        fontFamily = theme.font,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(shown) { row -> GridRow(row, w, ink, inkFaint, theme.font) }
                    }
                }
            }
        }

    }
}

/** A column head with nothing to filter on — TIME and MESSAGE. */
@Composable
private fun HeaderLabel(text: String, width: Dp, colour: Color, font: FontFamily) {
    Text(text, color = colour, fontSize = BODY, letterSpacing = 1.2.sp, fontFamily = font, modifier = Modifier.width(width))
}

/**
 * A column head that *is* its filter. Unset it shows the column name quietly; set it shows the chosen
 * value in full ink with a border, so a filtered column is obvious at a glance rather than something
 * you discover by wondering where the traffic went.
 */
@Composable
private fun FilterHeader(
    name: String,
    width: Dp,
    selected: String,
    options: List<String>,
    ink: Color,
    inkFaint: Color,
    font: FontFamily,
    centred: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val on = selected != ALL
    Box(Modifier.width(width), contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .then(if (on) Modifier.border(1.dp, ink.copy(alpha = 0.45f), RoundedCornerShape(5.dp)) else Modifier)
                .clickable { open = true }
                .padding(horizontal = if (on) 5.dp else 0.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (on) selected else name,
                color = if (on) ink else inkFaint,
                fontSize = BODY,
                letterSpacing = if (on) 0.sp else 1.2.sp,
                fontFamily = font,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" ▾", color = if (on) ink else inkFaint, fontSize = BODY, fontFamily = font)
        }
        // ALL first, then whatever has actually been seen on the wire.
        DashMenu(
            expanded = open,
            onDismiss = { open = false },
            options = (listOf(ALL) + options).map { MenuOption(it, it) },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun GridRow(row: WireRowData, w: ColumnWidths, ink: Color, inkFaint: Color, font: FontFamily) {
    // Arrows are from a bystander's view of the wire: inbound (board → DASH) points right, outbound
    // (DASH → board) points left.
    val arrow = if (row.outbound) "←" else "→"
    val arrowColour = if (row.outbound) C_BLUE else C_GREEN
    Row(
        modifier = Modifier.width(w.total),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP),
    ) {
        Cell(TIME_FMT.format(Date(row.timestamp)), inkFaint, w.time, font)
        Cell(arrow, arrowColour, w.dir, font, TextAlign.Center)
        Cell(row.transport, inkFaint, w.transport, font)
        Cell(row.board, if (row.board == NONE) ink.copy(alpha = 0.3f) else inkFaint, w.board, font)
        Cell(row.message, messageColour(row.message, ink), w.message, font)
        Cell(row.moduleId, if (row.moduleId == NONE) ink.copy(alpha = 0.3f) else inkFaint, w.module, font)
        Cell(row.payload, if (row.payload == NONE) ink.copy(alpha = 0.3f) else ink, w.payload, font)
    }
}

@Composable
private fun Cell(
    text: String,
    colour: Color,
    width: Dp,
    font: FontFamily,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        color = colour,
        fontSize = BODY,
        fontFamily = font,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = Modifier.width(width),
    )
}

/** Colour-code a line by its message word (the first field) for at-a-glance scanning. */
private fun messageColour(message: String, ink: Color): Color = when (message) {
    "HELLO" -> C_GREEN
    "DISCOVER", "INSTALL", "ACTIVATE", "DEACTIVATE" -> C_BLUE
    "BROADCAST", "REPORT" -> C_AMBER
    "SYSTEM_SIGNAL", "SUBSCRIBE", "INSTALL_END", "MANIFEST", "BLOCK", "LISTEN", "ACTION" -> C_PURPLE
    "ROGER" -> ink.copy(alpha = 0.6f)
    "TRIGGER" -> C_RED
    else -> ink
}
