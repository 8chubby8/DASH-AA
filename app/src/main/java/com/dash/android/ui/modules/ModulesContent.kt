package com.dash.android.ui.modules

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dash.android.panel.LayoutSlot
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.modulepanel.ModulePanelConfig
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.DashStatus
import com.dash.android.ui.theme.LocalDashTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dash.android.transport.FailReason
import com.dash.android.transport.InstallState
import com.dash.android.transport.InstalledModule
import com.dash.android.transport.ModuleActivity
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.SUBHEADING


// The unconfirmed-deactivation warning stays a distinct dark modal surface (CARD_BG); the in-list
// cards moved onto the settings-surface token in 1.5.8. INACTIVE stays as the neutral-action sentinel
// and the progress track; MUTED is the DORMANT chip colour and the warning-dialog body text.
private val CARD_BG = Color(0xFF14141F)
// Inks — read off the shared status palette (roadmap 1.5.15) so these and Transport Manager's lights
// can never disagree. They are foregrounds on the settings surface: chip labels, borders, text.
private val INSTALLED_BORDER = DashStatus.ok
private val MUTED = DashStatus.idle
private val UPDATE_INK = DashStatus.wait        // amber — a firmware version mismatch (1.4.13)
private val NO_REPLY_ACCENT = DashStatus.alert  // orange — an installed module that has gone silent (1.4.14)
private val FAIL_ACCENT = DashStatus.fail       // red — a failed install (1.4.14)

// Fills — the opposite job, and deliberately not from the palette above. These sit *behind* a
// button's label, so they must stay dark enough for their ink; brightening them would be exactly
// backwards. ActionButton picks the ink from the fill's luminance rather than assuming white.
private val INACTIVE = Color(0xFF2A2A2A)
private val INSTALL_ACCENT = Color(0xFF00695C)
private val UNINSTALL_ACCENT = Color(0xFF7A2222)
private val UPDATE_FILL = Color(0xFFC98A2B)

/**
 * Module Management — the Modules › Module Management settings tab (roadmap 1.5.8). The full 1.4.x
 * instrument, migrated verbatim into the settings shell: no rebuild, the same cards, chips, dialogs
 * and the whole discovery/install/reconciliation flow behind them. The sweep (1.4.6) finds modules;
 * INSTALL (1.4.4) runs the handshake; the module database (1.4.5) makes the result permanent;
 * reconciliation (1.4.6) keeps it alive.
 *
 * Its four managers are stateful and live on the controller, so — unlike the stateless prefs the
 * other tabs rebuild from context — they are reached through [LocalModuleDesk], provided in MainScreen.
 * The tab claims the whole content box (`fillsBox`, so the shell doesn't wrap it in the outer scroll):
 * the header and REFRESH stay pinned at the top and only the card list scrolls beneath them.
 *
 * **One physical module = one card.** The list is a merge of two sources keyed by id: the on-disk
 * installed list (green cards, present the moment the tab opens — plugged in or not, each wearing
 * its ACTIVE/DORMANT state) and the discovered set (INSTALL cards for modules answering the sweep that
 * aren't installed). A module in both is one green card. Nothing here can remove an installed card:
 * those are sustained by the database.
 *
 * *(Amended for 1.4.6, 2026-07-06: the DISCOVER button became SYNC. As built in 1.4.2 the button was
 * the only broadcast — user-driven, list rebuilt per press. The reconciliation sweep now broadcasts
 * continuously, so cards appear when hardware is plugged in and age away when it stops answering;
 * SYNC simply runs the sweep immediately — the §6 manual "check now" — rather than owning discovery.)*
 */
@Composable
fun ModulesContent() {
    val desk = LocalModuleDesk.current
    if (desk == null) {
        // Inert empty state — the desk isn't wired (a preview, or a read outside the provider).
        Box(Modifier.fillMaxSize().padding(BOX_PAD)) {
            Text(
                "Module desk unavailable.",
                color = LocalDashTheme.current.textColourSecondary.copy(alpha = 0.7f),
                fontSize = MAINBODY,
                fontFamily = LocalDashTheme.current.font,
            )
        }
        return
    }

    val discovered by desk.discovery.modules.collectAsState()
    val installStates by desk.install.states.collectAsState()
    val installed by desk.database.modules.collectAsState()
    val activity by desk.reconciliation.activity.collectAsState()
    val mismatch by desk.reconciliation.versionMismatch.collectAsState()
    val unconfirmed by desk.reconciliation.unconfirmedDeactivation.collectAsState()

    // 1.5.8: the DETAILS dialog is gone — it carried no detail of real use. A card is now a plain
    // selectable row; tapping one selects it, and its action (INSTALL / UNINSTALL / UPDATE) surfaces
    // in the pinned top bar beside REFRESH. Tap the same card again to clear the selection.
    var selectedId by remember { mutableStateOf<String?>(null) }

    // The panel's order and main module (roadmap 1.6.11) live with the panel's own settings.
    val context = LocalContext.current
    val prefs = remember { DashPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val panelConfig by prefs.modulePanelConfig.collectAsState(initial = ModulePanelConfig.default())

    // The merged list: installed modules first, **in the panel's tab order** since 1.6.11 — so this
    // list is the order, and the arrows move a module within it. Modules never placed fall back to
    // name order. Then this session's discovered-but-not-installed modules in the order they answered.
    val rows = remember(discovered, installed, panelConfig.order) {
        panelConfig.inOrder(installed.values.toList(), { it.id }, { it.name })
            .map { ModuleRow(it.id, it.type, it.name, it.description, it.version, installedRecord = it) } +
        discovered
            .filterNot { installed.containsKey(it.id) }
            .map { ModuleRow(it.id, it.type, it.name, it.description, it.version, installedRecord = null) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(BOX_PAD),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val theme = LocalDashTheme.current
        val selectedRow = rows.firstOrNull { it.id == selectedId }

        // Pinned control bar — REFRESH on the left, the selected module's action on the right (Roger's
        // 1.5.8 layout). No in-box heading: the settings nav already names the tab.
        //
        // REFRESH re-scans the bus right now — the §6 manual "check now" after fixing wiring or
        // plugging something in. Beyond an immediate sweep it *tidies* (roadmap 1.4.14): discovered
        // cards that no longer answer are dropped, and installed modules that have gone silent turn
        // orange (NOT_RESPONDING). The sweep broadcasts to every module on every active transport, so
        // this tab is about the whole bus of modules, not any one device. Always pressable: with
        // nothing connected it simply finds nothing. (DISCOVER until 1.4.6, SYNC until 1.4.14.)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkButton("REFRESH") { desk.reconciliation.refresh() }
            if (selectedRow != null) {
                SelectedActions(
                    row = selectedRow,
                    installState = installStates[selectedRow.id],
                    reportedVersion = mismatch[selectedRow.id],
                    onInstall = { desk.install.install(selectedRow.id) },
                    onCancel = { desk.install.cancel(selectedRow.id) },
                    onRetry = { desk.install.install(selectedRow.id) },
                    onDismiss = { desk.install.dismiss(selectedRow.id) },
                    // Since 1.4.6 uninstall goes through the reconciliation desk: the record is deleted
                    // immediately, but an active module is also sent DEACTIVATE so it stops transmitting.
                    onUninstall = {
                        selectedRow.installedRecord?.let { desk.reconciliation.uninstall(it) }
                        selectedId = null
                    },
                    // 1.4.13: a firmware update is a reinstall — the controller forgets the stale record
                    // and re-runs the handshake, re-capturing the contract from the new firmware.
                    onUpdate = { desk.onUpdate(selectedRow.id) },
                    isMain = selectedRow.id == panelConfig.mainModuleId,
                    onMove = { step ->
                        // Re-write the whole order from what is on screen, so a module never
                        // placed before gets a position the moment anything moves.
                        val ids = rows.filter { it.installedRecord != null }.map { it.id }.toMutableList()
                        val from = ids.indexOf(selectedRow.id)
                        val to = from + step
                        if (from >= 0 && to in ids.indices) {
                            ids.add(to, ids.removeAt(from))
                            scope.launch { prefs.saveModulePanelConfig(panelConfig.copy(order = ids)) }
                        }
                    },
                    onToggleMain = {
                        val next = if (selectedRow.id == panelConfig.mainModuleId) null else selectedRow.id
                        scope.launch { prefs.saveModulePanelConfig(panelConfig.copy(mainModuleId = next)) }
                    },
                )
            }
        }

        // Scrolling region — the card list takes the remaining height and scrolls on its own. No dark
        // well any more: the cards sit on the settings surface, so the whole tab reads as one surface.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty()) {
                Text(
                    text = "No modules yet.\n\nPlug a module in and it appears here within moments.\nREFRESH checks the bus right now instead of waiting.",
                    color = theme.textColourSecondary.copy(alpha = 0.7f),
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.id }) { row ->
                        ModuleCard(
                            row = row,
                            activity = activity[row.id],
                            reportedVersion = mismatch[row.id],
                            transport = desk.reconciliation.transportTag(row.id),
                            selected = row.id == selectedId,
                            isMain = row.id == panelConfig.mainModuleId,
                            onSelect = { selectedId = if (selectedId == row.id) null else row.id },
                        )
                    }
                }
            }
        }
    }

    // The §6 warning: a DEACTIVATE that was never ROGERed. The record is already gone; the module may
    // still be transmitting, and only physical action can silence it. (Distinct from the retired
    // details dialog — this is a safety warning, kept.)
    unconfirmed?.let { name ->
        UnconfirmedDeactivationDialog(name = name, onDismiss = { desk.reconciliation.clearUnconfirmed() })
    }
}

/**
 * The selected module's action cluster, shown in the top bar beside REFRESH (roadmap 1.5.8). It runs
 * the same install-state machine the card used to own: INSTALL for a discovered module → progress with
 * CANCEL → the module becomes installed; a Failed install shows its reason with RETRY / DISMISS; an
 * installed module offers UNINSTALL, plus UPDATE when its firmware version no longer matches (1.4.13).
 */
@Composable
private fun SelectedActions(
    row: ModuleRow,
    installState: InstallState?,
    reportedVersion: String?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    isMain: Boolean,
    onMove: (Int) -> Unit,
    onToggleMain: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (val state = installState) {
            is InstallState.Installing -> {
                InstallingBar(state.progress, Modifier.width(150.dp))
                ActionButton("CANCEL", UNINSTALL_ACCENT, onCancel)
            }
            is InstallState.Failed -> FailedContent(state.reason, Modifier, onRetry, onDismiss)
            null -> if (row.installedRecord != null) {
                // Order and main only mean something for a module that can be drawn in the panel.
                if (row.installedRecord.slots.isNotEmpty()) {
                    ActionButton("▲", INACTIVE) { onMove(-1) }
                    ActionButton("▼", INACTIVE) { onMove(1) }
                    ActionButton(if (isMain) "CLEAR MAIN" else "SET MAIN", INACTIVE, onToggleMain)
                }
                if (reportedVersion != null) ActionButton("UPDATE", UPDATE_FILL, onUpdate)
                ActionButton("UNINSTALL", UNINSTALL_ACCENT, onUninstall)
            } else {
                ActionButton("INSTALL", INSTALL_ACCENT, onInstall)
            }
        }
    }
}

/**
 * One row of the merged list — one physical module, whichever source knows it. Identity fields come
 * from the installed record when there is one (the durable truth), otherwise from this session's HELLO.
 */
private data class ModuleRow(
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val version: String,
    val installedRecord: InstalledModule?
)

/**
 * One module, one card — now a plain **selectable row** (roadmap 1.5.8). Tapping anywhere selects it
 * and raises its action into the top bar; there is no per-card button and no DETAILS dialog. The
 * identity lines never wrap — each scrolls sideways if it outruns the card, so a long id or description
 * stays one line rather than reflowing and growing the card.
 */
@Composable
private fun ModuleCard(
    row: ModuleRow,
    activity: ModuleActivity?,
    reportedVersion: String?,
    transport: String?,
    selected: Boolean,
    isMain: Boolean,
    onSelect: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val isInstalled = row.installedRecord != null
    // Cards sit on the settings surface (roadmap 1.5.8) — backgroundColourSecondary, the box's own
    // colour — defined by a border rather than a contrasting fill. Installed cards keep the green
    // border as their semantic marker; the rest take a quiet neutral border. Selection thickens the
    // border and lifts the fill a touch so the picked card is unmistakable.
    val shape = RoundedCornerShape(6.dp)
    val ink = theme.textColourSecondary
    val inkMuted = ink.copy(alpha = 0.72f)
    val inkFaint = ink.copy(alpha = 0.55f)
    val borderColour = when {
        isInstalled -> INSTALLED_BORDER
        selected -> ink.copy(alpha = 0.85f)
        else -> ink.copy(alpha = 0.32f)
    }
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(theme.backgroundColourSecondary)
        .then(if (selected) Modifier.background(ink.copy(alpha = 0.07f)) else Modifier)
        .border(if (selected) 2.dp else 1.dp, borderColour, shape)
        .clickable { onSelect() }
        .padding(horizontal = 14.dp, vertical = 12.dp)

    Column(modifier = cardModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Text(
                    text = row.name.ifBlank { "(unnamed)" },
                    color = ink,
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isMain) MainChip()
                if (isInstalled && reportedVersion != null) MismatchChip()
                if (isInstalled) ActivityChip(activity ?: ModuleActivity.DORMANT)
                if (transport != null) TransportChip(transport)
                TypeChip(row.type)
            }
        }
        if (row.description.isNotBlank()) {
            Text(
                row.description,
                color = inkMuted,
                fontSize = BODY,
                fontFamily = theme.font,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(row.id, color = inkFaint, fontSize = TINY, fontFamily = theme.font, maxLines = 1, softWrap = false)
            if (row.version.isNotBlank()) {
                Text(row.version, color = inkFaint, fontSize = TINY, fontFamily = theme.font, maxLines = 1, softWrap = false)
            }
        }
        /*
         * **What the author shipped, stated against the module's record** (roadmap 1.6.11, Roger) —
         * the count and which of the twelve slots, since "three layouts" does not tell you whether
         * you will see this module at small. A plain fact about the module, not DASH explaining
         * its own behaviour, which is why it lives here and never on the panel size tiles.
         */
        val slots = row.installedRecord?.slots.orEmpty()
        if (slots.isNotEmpty()) {
            Text(
                "${slots.size} layout${if (slots.size == 1) "" else "s"} — " +
                    slots.sortedBy { LayoutSlot.ALL.indexOf(it) }.joinToString(" · ") { it.blockName },
                color = inkMuted,
                fontSize = TINY,
                fontFamily = theme.font,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** The designed install-failure state (roadmap 1.4.14): an honest reason with RETRY and DISMISS —
 *  never a silent snap-back to the plain card. */
@Composable
private fun FailedContent(reason: FailReason, modifier: Modifier, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val message = when (reason) {
        FailReason.STALLED -> "Install stalled — no response"
        FailReason.DISCONNECTED -> "Module disconnected during install"
        FailReason.CORRUPT -> "Corrupt asset — install aborted"
        FailReason.OVERSIZE -> "Asset too large for this device — install aborted"
    }
    Text(message, color = FAIL_ACCENT, fontSize = BODY, fontFamily = LocalDashTheme.current.font, modifier = modifier)
    ActionButton("DISMISS", INACTIVE, onDismiss)
    ActionButton("RETRY", INSTALL_ACCENT, onRetry)
}

/** The in-progress bar. Determinate once an ACCESSORY MANIFEST gives a total; indeterminate otherwise. */
@Composable
private fun InstallingBar(progress: Float?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier.weight(1f),
                color = INSTALLED_BORDER,
                trackColor = INACTIVE
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
                color = INSTALLED_BORDER,
                trackColor = INACTIVE
            )
        }
        Text(
            text = if (progress == null) "INSTALLING…" else "${(progress * 100).toInt()}%",
            color = LocalDashTheme.current.textColourSecondary.copy(alpha = 0.72f),
            fontSize = BODY,
            fontFamily = LocalDashTheme.current.font
        )
    }
}

/**
 * The modern DASH button (the scaffold idiom — a rounded, token-styled tap, not a Material button).
 * [fill] null gives the quiet outline style for a neutral action (CANCEL / DISMISS / DETAILS); a
 * colour gives a filled accent — the semantic install / uninstall / update colours. [ink] defaults to
 * white on a filled accent and the secondary-text token on the outline.
 */

/** A card/dialog action in the modern idiom — a filled accent by its semantic colour, or the quiet
 *  outline style for the neutral [INACTIVE] sentinel (CANCEL / DISMISS / DETAILS). */
@Composable
private fun ActionButton(label: String, colour: Color, onClick: () -> Unit) {
    if (colour == INACTIVE) {
        DashButton(label, onClick)
        return
    }
    // The ink is chosen from the fill, not assumed to be white. UPDATE's amber is light enough that
    // white on it measured 2.94:1 — unreadable, and a bug that predates 1.5.15 rather than one the
    // brightening introduced. Deriving it means the next accent colour anyone adds cannot repeat it.
    val ink = if (colour.luminance() > 0.4f) Color(0xFF1A1A1A) else Color.White
    DashButton(label, onClick, fill = colour, ink = ink)
}

/** The transport a module last spoke over (roadmap 1.5.8) — usb / wifi / bt — as a small neutral tag
 *  in the card's chip row, beside the type and activity chips. Blue-grey so it reads as connection
 *  metadata rather than status. */
@Composable
private fun TransportChip(tag: String) {
    val colour = DashStatus.info
    Box(
        modifier = Modifier
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(tag.uppercase(), color = colour, fontSize = TINY, fontFamily = LocalDashTheme.current.font, letterSpacing = 1.sp)
    }
}

/** An installed module's liveness (arduino.md §6): green ACTIVE — it answered the sweep and ROGERed
 *  its ACTIVATE — grey DORMANT, or orange NO REPLY (roadmap 1.4.14) for a module that was here this
 *  session and then went silent. */
@Composable
private fun ActivityChip(activity: ModuleActivity) {
    val (label, colour) = when (activity) {
        ModuleActivity.ACTIVE -> "ACTIVE" to INSTALLED_BORDER
        ModuleActivity.NOT_RESPONDING -> "NO REPLY" to NO_REPLY_ACCENT
        ModuleActivity.DORMANT -> "DORMANT" to MUTED
    }
    Box(
        modifier = Modifier
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = colour, fontSize = TINY, fontFamily = LocalDashTheme.current.font, letterSpacing = 1.sp)
    }
}

/** The 1.4.13 firmware-mismatch marker: the module is reporting a version different from the one
 *  stored at install, so its stored contract may be stale and UPDATE is offered in the top bar when
 *  the card is selected. */
/** Marks the main module (roadmap 1.6.11) — neutral, since it is a choice and not a status. */
@Composable
private fun MainChip() {
    val ink = LocalDashTheme.current.textColourSecondary
    Box(
        modifier = Modifier
            .background(ink.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("MAIN", color = ink, fontSize = TINY, fontFamily = LocalDashTheme.current.font, letterSpacing = 1.sp)
    }
}

@Composable
private fun MismatchChip() {
    Box(
        modifier = Modifier
            .background(UPDATE_INK.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("UPDATE", color = UPDATE_INK, fontSize = TINY, fontFamily = LocalDashTheme.current.font, letterSpacing = 1.sp)
    }
}

/** The arduino.md §6 unconfirmed-deactivation warning, shown once per occurrence. */
@Composable
private fun UnconfirmedDeactivationDialog(name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(CARD_BG, RoundedCornerShape(10.dp))
                .border(1.dp, UNINSTALL_ACCENT, RoundedCornerShape(10.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("UNINSTALLED — NOT CONFIRMED", color = Color.White, fontSize = SUBHEADING, fontFamily = LocalDashTheme.current.font, letterSpacing = 2.sp)
            Text(
                text = "$name has been uninstalled, but it never confirmed deactivation. It could still " +
                    "send misleading data, so either disconnect the module or power-cycle the module.",
                color = MUTED,
                fontSize = MAINBODY,
                fontFamily = LocalDashTheme.current.font
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton("UNDERSTOOD", INACTIVE, onDismiss)
            }
        }
    }
}

/** Colour the type word by the three module types (arduino.md §4a). Unknown types read neutral. */
@Composable
private fun TypeChip(type: String) {
    val colour = when (type.uppercase()) {
        "SYSTEM" -> Color(0xFF4FC3F7)
        "ACCESSORY" -> Color(0xFF81C784)
        "LISTENER" -> Color(0xFFBA9EDB)
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(type.ifBlank { "?" }.uppercase(), color = colour, fontSize = TINY, fontFamily = LocalDashTheme.current.font, letterSpacing = 1.sp)
    }
}
