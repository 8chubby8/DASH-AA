package com.dash.android.ui.transports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.transport.DASH_ABSENT_MS
import com.dash.android.transport.DeviceRef
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.TransportState
import com.dash.android.transport.TransportStatus
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.common.DashStatus
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.delay
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.SUBHEADING
import com.dash.android.ui.common.MAINBODY_LINE


// Semantic light colours — these carry meaning (a board's health), so they are deliberately not
// theme tokens: green means reached in every preset, in every car.
// The local names carry this screen's meaning; the values come from the shared status palette
// (roadmap 1.5.15), so Transport Manager's lights and Module Manager's chips cannot drift apart —
// they were three duplicated hex literals until then.
private val GOOD = DashStatus.ok    // green — this stage is reached
private val WAIT = DashStatus.wait  // amber — not yet, but nothing is wrong
private val BAD = DashStatus.fail   // red — this is a fault: something is there and it is wrong

/** A pipe's kind, from its tag — decides the wording and which helper the card offers. */
private enum class Kind { USB, WIFI, BT, OTHER }

/** What a connected board is actually doing. Derived from how recently it sent anything at all, and
 *  how recently it sent something DASH could understand. */
private enum class Health {
    /** Connected, but nothing has come from it lately — powered down, or its sketch isn't running. */
    SILENT,
    /** Sending, but none of it is DASH grammar — wrong firmware, or not using the module SDK. */
    GIBBERISH,
    /** Sending DASH messages. Everything a board is supposed to do, it is doing. */
    GOOD
}

/**
 * Modules › Transport Manager (roadmap 1.5.10) — the surface that helps a user *connect* a board
 * and *diagnose* why one won't show up.
 *
 * **It lists boards, not modules.** A board is a physical thing on a pipe; one board may host many
 * modules, so two boards on the bench can reveal a whole catalogue in Module Manager. That is the line
 * between the two screens, and it is why every fact here is stated per board: "data arrived on the WiFi
 * pipe" is meaningless with two boards connected, since one can be streaming while the other lies
 * unplugged.
 *
 * Each pipe gets a card: its name, an address panel if a board has to be *aimed* at it, its own
 * condition in one honest line, and then the boards on it — each with DATA and DASH lights and, where
 * the card is wide enough to say so, a plain sentence explaining what those lights mean right now.
 *
 * It reaches the live transport flows through [LocalTransportDesk] and stays generic: a fourth
 * transport ever added gets a card, board rows and an address panel for free.
 */
@Composable
fun TransportManagerContent() {
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

    val statuses by desk.transportStatuses.collectAsState()
    val devices by desk.devices.collectAsState()

    // The two lights, as *timestamps* held by the controller and the transport manager for the app's
    // life — not flags accumulated here. Recency rather than stickiness is the point: a board that died
    // two minutes ago should say so, not stay green because it was healthy once. Both age on
    // DASH_ABSENT_MS, the same clock Module Manager ages its cards on, so the board view and the module
    // view can never contradict each other.
    val lastInbound by desk.lastInboundAt.collectAsState()
    val lastDash by desk.lastDashAt.collectAsState()

    // Freshness is a function of the clock, and a board going quiet emits nothing — without a tick the
    // lights would sit green until some unrelated event happened to recompose. Only runs while the tab
    // is open.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(BOX_PAD).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        statuses.forEach { (tag, status) ->
            TransportCard(
                tag = tag,
                status = status,
                boards = devices.filter { it.transportTag == tag },
                health = { device ->
                    val ref = DeviceRef(tag, device.key)
                    healthOf(fresh(lastInbound[ref], now), fresh(lastDash[ref], now))
                },
                onOpenWifiSettings = desk.onOpenWifiSettings,
                onOpenBluetoothSettings = desk.onOpenBluetoothSettings,
            )
        }
        if (statuses.isEmpty()) {
            Text(
                "No transports.",
                color = theme.textColourSecondary.copy(alpha = 0.7f),
                fontSize = MAINBODY,
                fontFamily = theme.font,
            )
        }
    }
}

@Composable
private fun TransportCard(
    tag: String,
    status: TransportStatus,
    boards: List<TransportDevice>,
    health: (TransportDevice) -> Health,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val ink = theme.textColourSecondary
    val inkMuted = ink.copy(alpha = 0.72f)
    val inkFaint = ink.copy(alpha = 0.55f)
    val hairline = ink.copy(alpha = 0.32f)

    val kind = when (tag.lowercase()) {
        "usb" -> Kind.USB
        "wifi" -> Kind.WIFI
        "bt" -> Kind.BT
        else -> Kind.OTHER
    }
    val name = when (kind) {
        Kind.USB -> "USB"
        Kind.WIFI -> "WiFi"
        Kind.BT -> "Bluetooth"
        Kind.OTHER -> tag.uppercase()
    }

    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.backgroundColourSecondary)
            .border(1.dp, hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(name, color = ink, fontSize = MAINBODY, fontFamily = theme.font)

        // "Point your board here" — the single thing that makes connecting an addressable board
        // painless. Driven off the pipe *declaring* an address rather than off its kind, so this is not
        // a WiFi special case: any transport a module must be aimed at gets the panel by filling in
        // TransportStatus.address.
        status.address?.let { AddressPanel(it, ink, inkFaint, theme.font) }

        // One line for the pipe's own condition. Normally "No boards connected" — but a pipe has states
        // that are nothing to do with boards (waiting on USB permission, a port it couldn't bind), and
        // calling those "no boards connected" would be a lie at the moment the user most needs the
        // truth. Suppressed once boards are listed, since the list then says it better.
        if (boards.isEmpty()) {
            Text(
                pipeLine(status, kind),
                color = inkMuted,
                fontSize = MAINBODY,
                lineHeight = MAINBODY_LINE,
                fontFamily = theme.font,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                boards.forEach { board -> BoardRow(board.label, health(board), ink, inkFaint, theme.font) }
            }
        }

        // Deep-links out for radio-level / pairing controls DASH never reimplements.
        when (kind) {
            Kind.WIFI -> LinkButton("Wi-Fi settings →") { onOpenWifiSettings() }
            Kind.BT -> LinkButton("Bluetooth settings →") { onOpenBluetoothSettings() }
            else -> {}
        }
    }
}

/**
 * One connected board. The label leads at a size that makes the card read as *a list of boards*, then
 * its two lights, then — only if the card is wide enough to hold it without crushing anything — a
 * plain sentence saying what those lights mean. Narrow screens keep the lights and drop the sentence;
 * the colours still carry the state, so nothing is lost, only elaborated.
 */
@Composable
private fun BoardRow(
    label: String,
    health: Health,
    ink: Color,
    inkFaint: Color,
    font: FontFamily,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomForWords = maxWidth >= EXPLAIN_MIN_WIDTH
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("•", color = inkFaint, fontSize = BODY, fontFamily = font)
            Text(
                label,
                color = ink,
                fontSize = BODY,
                fontFamily = font,
                modifier = if (roomForWords) Modifier else Modifier.weight(1f),
            )
            Light("DATA", if (health == Health.SILENT) WAIT else GOOD, font)
            Light("DASH", dashLightColour(health), font)
            if (roomForWords) {
                Text(
                    explain(health),
                    color = inkFaint,
                    fontSize = BODY,
                    fontFamily = font,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Amber is "not yet, nothing is wrong"; red is "something is there and it is wrong". A board sending
 *  nothing at all hasn't failed the DASH test — it hasn't sat it. A board sending noise has. */
private fun dashLightColour(health: Health): Color = when (health) {
    Health.SILENT -> WAIT
    Health.GIBBERISH -> BAD
    Health.GOOD -> GOOD
}

private fun explain(health: Health): String = when (health) {
    Health.SILENT ->
        "Board connected but sending nothing — check it is powered and its sketch is running."
    Health.GIBBERISH ->
        "Board connected but not speaking DASH — check it is running the DASH module SDK."
    Health.GOOD -> "Board fully functional."
}

/** The pipe's own condition, for the line shown when no boards are on it. */
private fun pipeLine(status: TransportStatus, kind: Kind): String = when (status.state) {
    TransportState.ERROR -> status.detail.ifBlank { "This pipe failed to start." }
    TransportState.PERMISSION_REQUIRED ->
        "Waiting for USB permission — accept Android's prompt. If you missed it, unplug and replug the board."
    TransportState.CONNECTING -> "Connecting…"
    TransportState.CONNECTED, TransportState.NO_DEVICE -> when (kind) {
        Kind.WIFI -> "No boards connected. Point your board's firmware at the address above, and make sure it joins the same Wi-Fi network as this device."
        Kind.USB -> "No boards connected. Connect one with a data-capable USB cable, and an OTG adapter if your device needs one."
        Kind.BT -> "No boards connected. Pair your board once in Android's Bluetooth settings, then DASH connects to it automatically."
        Kind.OTHER -> "No boards connected."
    }
}

private fun healthOf(dataFresh: Boolean, dashFresh: Boolean): Health = when {
    dashFresh -> Health.GOOD
    dataFresh -> Health.GIBBERISH
    else -> Health.SILENT
}

/** Has this board been heard from recently enough to count? Never heard at all reads the same as gone
 *  quiet — in both cases there is nothing coming from it now, which is what the light is claiming. */
private fun fresh(at: Long?, now: Long): Boolean = at != null && now - at <= DASH_ABSENT_MS

@Composable
private fun AddressPanel(address: String, ink: Color, inkFaint: Color, font: FontFamily) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ink.copy(alpha = 0.06f))
            .border(1.dp, ink.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("POINT YOUR BOARD AT", color = inkFaint, fontSize = TINY, letterSpacing = 1.5.sp, fontFamily = font)
            Text(address, color = ink, fontSize = MAINBODY, fontFamily = font)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, ink.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .clickable { clipboard.setText(AnnotatedString(address)) }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("COPY", color = ink, fontSize = TINY, fontFamily = font, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun Light(label: String, colour: Color, font: FontFamily) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colour.copy(alpha = 0.18f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(label, color = colour, fontSize = TINY, fontFamily = font, letterSpacing = 1.sp)
    }
}

/** How often the tab re-asks the clock. Well under [DASH_ABSENT_MS], so a board falling quiet shows
 *  within a few seconds of it actually being true. */
private const val TICK_MS = 5_000L

/** Below this card width the explanation sentence is dropped and the lights stand alone — on a narrow
 *  screen the words would squeeze the board's name down to nothing, and the name is the point. */
private val EXPLAIN_MIN_WIDTH = 560.dp
