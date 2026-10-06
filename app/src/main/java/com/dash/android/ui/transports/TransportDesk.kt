package com.dash.android.ui.transports

import androidx.compose.runtime.compositionLocalOf
import com.dash.android.transport.DeviceRef
import com.dash.android.transport.TransportDevice
import com.dash.android.transport.TransportStatus
import com.dash.android.transport.WireEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live transport desk handed to Modules › Transport Manager (roadmap 1.5.10).
 *
 * Like [com.dash.android.ui.modules.ModuleDesk], these are *stateful* flows owned by
 * [com.dash.android.transport.TransportManager] for the app's life, so the tab reaches them here
 * rather than rebuilding anything. The tab reads:
 *
 *  - [transportStatuses] — each pipe's own live status (usb / wifi / bt), tag + [TransportStatus].
 *  - [devices] — every **board** across all pipes. The card lists these; a board is not a module, and
 *    one board may host many, so this list and Module Manager's are different things by design.
 *  - [lastInboundAt] / [lastDashAt] — per board: when it last sent any bytes, and when it last sent a
 *    line DASH understood. Together they give the DATA and DASH lights. Both are recorded by the layer
 *    that knows the fact and held for the app's life, so a row reads the same truth whether the tab has
 *    been open for an hour or a second. (They replaced a first cut that sniffed the wire tap from
 *    inside the composable — that forgot everything on leaving the tab and could only refill from a
 *    200-event buffer shared across all pipes, so a healthy but quiet board read as silent for up to
 *    30 s exactly when a worried user was looking at it.)
 *
 * The deep-links hand the user out to Android's own Wi-Fi / Bluetooth settings — DASH links out for
 * radio-level and pairing controls rather than reimplementing them.
 *
 * There is deliberately no "check now" action. The pipes already greet an arriving board within
 * ~100 ms, sweep every 30 s, and close a silent client on the idle horizon; a manual refresh on a
 * self-refreshing surface only invites the user to press something before believing the screen.
 *
 * Default null so a read outside the provider (a preview) is inert rather than a crash.
 */
data class TransportDesk(
    val transportStatuses: StateFlow<List<Pair<String, TransportStatus>>>,
    val devices: StateFlow<List<TransportDevice>>,
    val lastInboundAt: StateFlow<Map<DeviceRef, Long>>,
    val lastDashAt: StateFlow<Map<DeviceRef, Long>>,
    val onOpenWifiSettings: () -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    // The Serial Monitor's half (roadmap 1.5.12): the read-only wire tap, plus the broadcast the
    // COMMANDS panel puts back on it. It is a *view* onto this same transport layer, so it shares this
    // desk rather than gaining one of its own, and it never owns the connection — leaving the tab
    // leaves every link up.
    val wire: SharedFlow<WireEvent>,
    val send: (String) -> Unit,
)

val LocalTransportDesk = compositionLocalOf<TransportDesk?> { null }
