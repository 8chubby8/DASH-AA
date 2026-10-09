package com.dash.android.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.DashApplication
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.motion.DashTransitions
import com.dash.android.ui.motion.LocalDashTransitions
import com.dash.android.ui.motion.TransitionId
import com.dash.android.ui.weather.LocalWeatherSnapshot
import com.dash.android.weather.WeatherProvider
import com.dash.android.weather.WeatherSnapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.dash.android.ui.scale.DASH_TEXT_SCALE_DEFAULT
import com.dash.android.ui.modules.LocalModuleDesk
import com.dash.android.ui.modules.ModuleDesk
import com.dash.android.ui.transports.LocalTransportDesk
import com.dash.android.ui.transports.TransportDesk
import com.dash.android.ui.modulepanel.ModulePanel
import com.dash.android.ui.modulepanel.ModulePanelConfig
import com.dash.android.ui.modulepanel.ModulePanelSpec
import com.dash.android.ui.modulepanel.PanelEdge
import com.dash.android.ui.modulepanel.PanelYield
import com.dash.android.ui.modulepanel.PanelVisibility
import com.dash.android.ui.modulepanel.rememberPanelExpansion
import com.dash.android.ui.modulepanel.canFill
import com.dash.android.ui.modulepanel.resolvePanelYield
import com.dash.android.ui.modulepanel.effectiveEdge
import com.dash.android.ui.modulepanel.ModuleTabs
import com.dash.android.ui.modulepanel.rememberPanelCandidates
import com.dash.android.ui.modulepanel.rememberPanelDocument
import com.dash.android.ui.modulepanel.slotFor
import com.dash.android.ui.modulepanel.rememberPanelPresses
import com.dash.android.ui.settings.SettingsShell
import com.dash.android.ui.theme.DashTheme
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.theme.SPLASH_BACKGROUND_COLOUR_DEFAULT
import com.dash.android.ui.splash.LocalSplashPreview
import com.dash.android.ui.splash.SPLASH_CROP_DEFAULT
import com.dash.android.ui.splash.SPLASH_DWELL_DEFAULT_MS
import com.dash.android.ui.splash.SplashScreen
import com.dash.android.ui.systembar.LocalEnterBarEdit
import com.dash.android.ui.systembar.BarPosition
import com.dash.android.ui.systembar.DashAction
import com.dash.android.ui.systembar.EditRuler
import com.dash.android.ui.systembar.SystemBar
import com.dash.android.ui.systembar.SystemBarConfig
import com.dash.android.ui.viewport.AndroidAutoViewport
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DASH's screen.
 *
 * **DASH-AA:** upstream's MainScreen, with three kinds of change and no others —
 * - Android's activity plumbing is gone (permission prompts, the screen-on receiver that replayed the
 *   splash on wake, `requestedOrientation`): there is no activity, and the desktop compositor owns
 *   rotation. The window is [window], used only to place the touchscreen over the viewport.
 * - **The viewport exists** — [AndroidAutoViewport], drawn in exactly the rectangle the settings blind
 *   rolls into, beneath the blind and beneath the module panel.
 * - The Transport Manager's "settings" links open the desktop's Wi-Fi and Bluetooth panels.
 */
@Composable
fun MainScreen(isColdBoot: Boolean, window: java.awt.Window? = null) {
    val context = LocalContext.current
    val prefs = remember { DashPreferences(context) }
    val scope = rememberCoroutineScope()

    // Transport layer (1.4.1) + the controller/brain (1.4.2) above it. *Reached*, never created here
    // (roadmap 1.5.11): both are owned by [DashApplication] and live for the life of the process, so
    // an activity recreation reflows the UI without touching the bus. They used to be `remember`ed
    // right here, which tied DASH's whole module conversation to a composable — see DashApplication
    // for what that cost and how it was caught. There is deliberately no DisposableEffect: this screen
    // does not start the stack and must never stop it.
    val dashApp = remember(context) { context.applicationContext as DashApplication }
    val transport = dashApp.transport
    val controller = dashApp.controller

    // DASH-AA: upstream asked Android for BLUETOOTH_CONNECT here. Linux needs no runtime grant for an
    // outbound RFCOMM socket, so there is nothing to ask — the transport simply finds what is bonded.

    // Weather for the settings landing scene, cached at the root so the panel opens on real weather
    // instead of the clock-only floor. Warmed once at start and refreshed on each open — always off
    // the main thread, so a slow network never blocks the panel opening (the fetch does up to two IP
    // lookups and a forecast call, each on a 5s socket timeout; gating the open on that could freeze
    // the button for seconds). See openSettings().
    val weatherProvider = remember { WeatherProvider(context) }
    var weather by remember { mutableStateOf<WeatherSnapshot?>(null) }
    LaunchedEffect(Unit) { weather = weatherProvider.current() }

    var showSplash by remember { mutableStateOf(isColdBoot) }
    // The ignition turning the screen on plays the splash, as a cold boot does (DASH-AA 1.1.7).
    LaunchedEffect(Unit) { dashApp.carPower.splash.collect { showSplash = true } }
    var showSettings by remember { mutableStateOf(false) }
    // Where to reopen settings after a focused task (bar edit mode) takes over the screen — so Save/
    // Cancel returns to the tab the user left, not the home screen.
    var settingsReturnTarget by remember { mutableStateOf<String?>(null) }
    // DASH-AA 1.1.6: bumped to reopen the settings shell on [settingsReturnTarget] while it is already open —
    // Transport Manager's Wi-Fi and Bluetooth buttons lead to Connections, DASH's own, not the desktop's.
    var settingsJump by remember { mutableStateOf(0) }
    // DASH's keyboard and the Bluetooth pairing question, over everything (1.1.6).
    val keyboardUp by com.dash.android.ui.keyboard.DashKeyboard.target.collectAsState()
    val pairingUp by dashApp.bluetooth.request.collectAsState()
    val overlayUp = keyboardUp != null || pairingUp != null
    var editMode by remember { mutableStateOf(false) }
    var editConfig by remember { mutableStateOf<SystemBarConfig?>(null) }
    var elementWidths by remember { mutableStateOf(mapOf<String, Int>()) }

    // Opening settings refreshes the landing weather in the background (never gating the open); closing
    // is a plain toggle. Both settings-button sites route through this so the behaviour is identical.
    val toggleSettings: () -> Unit = {
        if (showSettings) {
            showSettings = false
        } else {
            showSettings = true
            scope.launch { weather = weatherProvider.current() }
        }
    }

    // DASH-AA: upstream replayed the splash when Android woke the screen with DASH as the launcher. A
    // laptop has no ignition-driven screen-on event to hear yet; the splash plays on launch.

    val dashTextScale by prefs.dashTextScale.collectAsState(initial = DASH_TEXT_SCALE_DEFAULT)
    val transitionMap by prefs.transitions.collectAsState(initial = emptyMap())
    val transitions = remember(transitionMap) { DashTransitions(transitionMap) }
    val splashMode by prefs.splashMode.collectAsState(initial = "COLOUR")
    val splashColour by prefs.splashBackgroundColour.collectAsState(initial = SPLASH_BACKGROUND_COLOUR_DEFAULT)
    val splashImageUri by prefs.splashImageUri.collectAsState(initial = "")
    val splashAnimationUri by prefs.splashAnimationUri.collectAsState(initial = "")
    val splashCropPortrait by prefs.splashCropPortrait.collectAsState(initial = SPLASH_CROP_DEFAULT)
    val splashCropLandscape by prefs.splashCropLandscape.collectAsState(initial = SPLASH_CROP_DEFAULT)
    val splashDwell by prefs.splashDwellMillis.collectAsState(initial = SPLASH_DWELL_DEFAULT_MS)
    val barConfig by prefs.systemBarConfig.collectAsState(initial = SystemBarConfig.default())
    val modulePanelConfig by prefs.modulePanelConfig.collectAsState(initial = ModulePanelConfig.default())

    // DASH text size — override the composition's fontScale with DASH's own value so every sp in
    // DASH chrome follows the DASH text-size control and ignores Android's font setting entirely
    // (Android's font size is left for the viewport apps). The device density (dp mapping) is kept
    // as-is; only text scaling is taken over here.
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, dashTextScale),
        LocalDashTransitions provides transitions,
        LocalWeatherSnapshot provides weather,
        LocalDashTheme provides DashTheme.default(),
        LocalSplashPreview provides { showSplash = true },
        LocalEnterBarEdit provides {
            editConfig = barConfig
            editMode = true
            showSettings = false
            settingsReturnTarget = "layout.systembar"
        },
        // The live module desk for the Modules › Module Management tab (1.5.8). Its four managers are
        // stateful and live on the controller for the app's life, so the tab reaches them here rather
        // than rebuilding them from context the way the stateless-prefs tabs do.
        LocalModuleDesk provides ModuleDesk(
            discovery = controller.discovery,
            install = controller.install,
            database = controller.database,
            reconciliation = controller.reconciliation,
            onUpdate = controller::updateModule,
            systemState = controller.systemState,
        ),
        // The live transport desk for Modules › Transport Manager (1.5.10). Live flows off the
        // TransportManager (held for the app's life), plus the check-now sweep and the deep-links out
        // to Android's own Wi-Fi / Bluetooth settings for radio-level and pairing controls.
        LocalTransportDesk provides TransportDesk(
            transportStatuses = transport.transportStatuses,
            devices = transport.devices,
            lastInboundAt = transport.lastInboundAt,
            lastDashAt = controller.lastDashAt,
            wire = transport.wire,
            send = transport::send,
            // DASH-AA: Connections' own Wi-Fi and Bluetooth tabs (1.1.6) — pairing an SPP module is done
            // there, by DASH, with no desktop needed.
            onOpenWifiSettings = { settingsReturnTarget = "connections.wifi"; settingsJump++; showSettings = true },
            onOpenBluetoothSettings = { settingsReturnTarget = "connections.bluetooth"; settingsJump++; showSettings = true },
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            // Captured here so the whole screen can measure against the window, not against whatever
            // nested BoxWithConstraints happens to be in scope further down.
            val screenWidth = maxWidth

            // The not-default-launcher banner is gone (roadmap 1.6.2). A purple full-width strip
            // anchored opposite the bar, it wanted exactly the edge the module panel now takes, and
            // Roger's call was that he would not miss it. Nobody is stranded: System › Android
            // Settings Links offers Android's own home-settings and default-apps screens, which is
            // where a "make me the launcher" action belongs anyway.

            // The module panel measures the screen for every other DASH surface — **when there is
            // one to measure.**
            //
            // **The long edge is the docked edge minus what the bar has already taken from it**
            // (Roger, 1.6.3). Docked top or bottom the bar is on the opposite horizontal edge and
            // takes nothing, so the panel runs the full screen width. Docked left or right the bar
            // spans the full width at top or bottom and eats into the vertical run, so the panel
            // runs the screen height *less the bar* — which also means changing the bar height
            // resizes a vertical panel.
            //
            // **No layout, no panel** (module-layout.md §6, ruled 2026-08-12, built at 1.6.6). With
            // no installed module able to fill the selected slot there is no panel at all, and the
            // screen reclaims the space rather than reserving a strip to display nothing. That is
            // why the thickness below is zero rather than the panel merely being skipped at the
            // point it would be drawn: every inset on this screen is measured from it, so an absent
            // panel has to be absent from the arithmetic too, or settings would still roll out into
            // a band that had been set aside for a king who never arrived.
            val barThickness = barConfig.heightDp.dp
            val panelEdge = effectiveEdge(modulePanelConfig.edge, barConfig.position)

            /*
             * **The panel has two slots now: the one it rests at and the one it expands to**
             * (roadmap 1.6.9). Under FULL they are the same slot; under RETRACTED the resting state
             * is off screen and there is no resting slot at all; under SHRUNK the resting slot is a
             * smaller layout the module's author drew on purpose.
             */
            val fullSlot = slotFor(modulePanelConfig.size, panelEdge)
            val restSlot = modulePanelConfig.restingSize?.let { slotFor(it, panelEdge) }

            /*
             * **Expansion, and the timer that folds it back.** Opening settings puts the panel at
             * rest first — which is what keeps rule 2 independent of this feature, since the yield
             * then only ever measures a resting assembly.
             */
            val expansion = rememberPanelExpansion(
                expandable = modulePanelConfig.visibility.expands,
                dwellSeconds = modulePanelConfig.dwellSeconds,
                forceRest = showSettings,
            )

            /*
             * **The bar lists the modules that can fill *any* slot the panel can reach** — resting
             * or full (roadmap 1.6.9, Roger). Not the slot currently drawn, which is the trap: at
             * rest the drawn slot is the small one, so a module shipping only Large would lose the
             * very tab that is the only way to reach it. And not the full slot alone either, which
             * is the mirror of the same fault: a module shipping only Small sits on screen at rest
             * with no tab of its own, so the bar names something other than what you are looking at.
             *
             * **The bar lists what you can go to. It is never filtered by what happens to be on
             * screen.** That one sentence also covers 1.6.8's membership rule and rule 2's — the
             * settings yield changes what a module draws, never who is in the bar.
             *
             * Database order — no ordering in this version, since the panel order and the dominant
             * module are one story, told once at 1.6.11.
             */
            // In the user's tab order (roadmap 1.6.11) — Modules › Module Management's arrows.
            val fullCandidates = rememberPanelCandidates(controller.database, fullSlot)
                .let { list -> modulePanelConfig.inOrder(list, { it.id }, { it.name }) }
            val restCandidates = (restSlot?.let { rememberPanelCandidates(controller.database, it) }
                ?: emptyList()).let { list -> modulePanelConfig.inOrder(list, { it.id }, { it.name }) }
            val panelCandidates = remember(fullCandidates, restCandidates) {
                (fullCandidates + restCandidates).distinctBy { it.id }
            }

            // **Which module is on screen.** The user's tap wins; before there has been one, the
            // module DASH was last left on; before there has ever been one, the first candidate. A
            // stored id that is no longer installed — or can no longer fill the selected size — is
            // simply not found here and falls back the same way, with nothing to clean up.
            val lastPanelModule by prefs.modulePanelLastModule.collectAsState(initial = null)
            var tappedModuleId by remember { mutableStateOf<String?>(null) }
            //
            // **A main module replaces "last shown" as the starting point** (roadmap 1.6.11, Roger):
            // with one set, DASH starts on it every ignition; with none, 1.6.8's rule stands.
            val mainId = modulePanelConfig.mainModuleId
            val chosenModule = remember(panelCandidates, tappedModuleId, lastPanelModule, mainId) {
                val wanted = tappedModuleId ?: mainId ?: lastPanelModule
                panelCandidates.firstOrNull { it.id == wanted }
                    ?: panelCandidates.firstOrNull { it.id == mainId }
                    ?: panelCandidates.firstOrNull()
            }

            /*
             * **The panel hands itself back to the main module** after [returnSeconds] on another
             * one (roadmap 1.6.11). Never by default. A touch inside the panel restarts the count, so
             * it never leaves while you are using it — the same courtesy the fold-back dwell gives.
             */
            var panelTouchTick by remember { mutableIntStateOf(0) }
            val returnSeconds = modulePanelConfig.returnSeconds
            LaunchedEffect(chosenModule?.id, mainId, returnSeconds, panelTouchTick) {
                if (returnSeconds <= 0 || mainId == null) return@LaunchedEffect
                if (chosenModule == null || chosenModule.id == mainId) return@LaunchedEffect
                if (panelCandidates.none { it.id == mainId }) return@LaunchedEffect
                delay(returnSeconds * 1000L)
                tappedModuleId = mainId
                prefs.saveModulePanelLastModule(mainId)
            }

            /*
             * **The slot actually being drawn**, and with it the one place DASH puts a module on
             * screen the user did not pick.
             *
             * At rest the drawn slot is the small one, and the chosen module may have no layout for
             * it — Tank ships only Large, so when the dwell folds the panel back, Tank cannot be
             * what rests there. DASH draws the first module that *can*, which with one small-capable
             * module installed is not a judgement at all but arithmetic: there is exactly one valid
             * answer. **The user chooses the resting module by choosing which module ships the
             * resting layout** *(Roger)* — 1362 modules installed, one shipping Small, and that one
             * is the resting panel, with no "which module rests here" setting needed anywhere.
             *
             * **The substitution is never stored.** [tappedModuleId] is written only when a tab is
             * actually pressed, so what DASH remembers you chose survives the rest and survives a
             * restart — the same discipline [effectiveEdge] applies when the system bar displaces
             * the panel, and the same one rule 2 applies to size.
             */
            val drawSlot = if (expansion.expanded || restSlot == null) fullSlot else restSlot
            val drawCandidates = if (drawSlot == fullSlot) fullCandidates else restCandidates
            val selectedModule = remember(drawCandidates, chosenModule) {
                chosenModule?.takeIf { m -> drawCandidates.any { it.id == m.id } }
                    ?: drawCandidates.firstOrNull()
            }
            /*
             * **Rule 2 — the panel yields so the settings panel can open (roadmap 1.6.9).**
             *
             * The measure is the *shape of what is left over* rather than the size of what was
             * taken: DASH knows the screen, the bar and the assembly, so it knows the rectangle the
             * settings blind would get, and it checks whether that rectangle is a usable shape. If
             * it is not, the panel steps down to the largest smaller slot **the module on screen**
             * actually ships, and retracts only when there is no such slot.
             *
             * **This supersedes the 1.6.2 rule that settings never covers the panel** (Roger). That
             * rule was right about the Module Mantra and wrong about what happens when honouring it
             * makes DASH unusable — a large vertical slot on a phone left the settings panel a 54dp
             * band, so the module panel's own setting became unreachable *by the settings panel*,
             * with no way out. The courtesy loses to the trap.
             *
             * **The stored preference is never rewritten**, exactly as [effectiveEdge] does not
             * rewrite the user's edge when the bar displaces the panel. This is that same decision
             * on a different axis, and the displacement lasts precisely as long as settings is open.
             */
            val drawnSize = if (drawSlot == fullSlot) modulePanelConfig.size
                else modulePanelConfig.restingSize ?: modulePanelConfig.size
            val panelYield = if (!showSettings || modulePanelConfig.visibility == PanelVisibility.OFF) {
                PanelYield.Draw(drawnSize)
            } else {
                // Rule 2 measures the *resting* assembly, because opening settings put the panel at
                // rest before this ran. It therefore fires far less often than it did when every
                // panel was permanent — a panel resting off screen never trips it at all.
                resolvePanelYield(
                    chosen = drawnSize,
                    edge = panelEdge,
                    screenWidth = screenWidth,
                    screenHeight = maxHeight,
                    barThickness = barThickness,
                    tabThickness = modulePanelConfig.tabBarDp.dp,
                    fontScale = LocalDensity.current.fontScale,
                    canDraw = { size ->
                        selectedModule?.canFill(slotFor(size, panelEdge)) == true
                    },
                )
            }
            val drawSize = (panelYield as? PanelYield.Draw)?.size ?: drawnSize
            // Off is off: no panel, and by the rule set at 1.6.8, no tab bar with it.
            val panelOff = modulePanelConfig.visibility == PanelVisibility.OFF
            // Retracted at rest is the same displacement rule 2 uses, reached by a different road.
            val panelRetracted = panelYield is PanelYield.Retract ||
                (!expansion.expanded && modulePanelConfig.visibility == PanelVisibility.RETRACTED)

            val panelDocument = if (panelOff) null else rememberPanelDocument(
                database = controller.database,
                module = selectedModule,
                slot = slotFor(drawSize, panelEdge),
            )

            val panelLongEdge = if (panelEdge.horizontal) screenWidth else (maxHeight - barThickness)

            // **The tab bar sits outside the panel and cuts into the viewport** (Roger, 1.6.8) — it
            // is never taken out of the panel's own footprint, because the module's box would then
            // be thinner than the slot ratio its author drew to. **It shows whenever the panel
            // shows, including with a single module installed**: the viewport is then the same size
            // on Monday as on Friday, installing a module changes what is *in* the panel rather than
            // how much content area the screen has, and a lone tab is a label rather than a dead
            // control. With no module able to fill the slot there is no panel (§6) and no bar — and
            // since 1.6.9, no panel at all when the user has simply not asked for one.
            //
            // It is measured here, above the panel's own geometry, because compacting has to know
            // what the bar has already taken before it can say what is left for the panel.
            val tabThickness =
                if (panelDocument == null) 0.dp else modulePanelConfig.tabBarDp.dp

            /*
             * **What the assembly has to grow into, perpendicular to the docked edge.** The bar is
             * subtracted only where it takes from *this* axis: docked left or right the system bar
             * spans the top or bottom and eats the panel's long edge instead, which [panelLongEdge]
             * has already accounted for, so subtracting it here as well would charge for it twice.
             * The tab bar is always paid, on either axis, because it is always drawn.
             */
            val panelAvailableThickness =
                (if (panelEdge.horizontal) maxHeight - barThickness else screenWidth) - tabThickness

            /*
             * **Compacting** (roadmap 1.6.9). A panel is a shape, so its thickness is derived from
             * the edge it is docked to — and on an elongated screen that can ask for more than the
             * screen has. [ModulePanelSpec.boxFor] caps it and pulls the long edge in with it, so
             * the panel keeps its exact ratio and becomes an island on its edge rather than a wall
             * across it. Exactly one combination DASH supports needs this — a Large panel along the
             * long edge of a 1280 × 480 head unit — which is why it is mitigation and not a feature.
             */
            val panelBox = if (panelDocument == null) ModulePanelSpec.PanelBox(0.dp, 0.dp, false)
                else ModulePanelSpec.boxFor(drawSize, panelLongEdge, panelAvailableThickness)
            // The panel as it is drawn right now — full when expanded, resting otherwise.
            val panelThickness = panelBox.thickness
            val panelWidth = if (panelEdge.horizontal) panelBox.longEdge else panelThickness
            val panelHeight = if (panelEdge.horizontal) panelThickness else panelBox.longEdge

            /*
             * **A compacted panel centres on its edge** *(Roger)*. Half the shortfall on each side,
             * and zero whenever the panel spans its edge normally — so the ordinary case pays
             * nothing and needs no branch. The band this leaves either side is DASH's own surface,
             * outside the castle walls, and DASH paints its own floor there.
             */
            val panelLongInset = (panelLongEdge - panelBox.longEdge) / 2

            /*
             * **The screen is laid out for the *resting* state and never for the expanded one**
             * (Roger, 2026-08-27). An expanded panel is drawn *over* the viewport rather than
             * pushing it, so the running app never relayouts when the panel opens — Maps and
             * Spotify reflowing every time you glance at a climate module would be intolerable, and
             * it would get worse the more you used it.
             *
             * **The consequence is the point of the whole feature: the user pays for the state the
             * panel is usually in, not the state it is briefly in.** A Large panel resting off
             * screen costs the viewport nothing but the tab bar, and covers the screen only while
             * you are actually looking at it. That is what makes a big panel reasonable on a device
             * where a permanent one never was.
             *
             * Rule 2's yield applies here rather than to the expanded panel, because opening
             * settings put the panel at rest before any of this ran.
             */
            val restingSizeNow = when {
                panelOff -> null
                modulePanelConfig.visibility == PanelVisibility.RETRACTED -> null
                showSettings -> (panelYield as? PanelYield.Draw)?.size
                else -> modulePanelConfig.restingSize
            }
            // Compacted on the same rule as the drawn panel: this is what the viewport is actually
            // laid out for, so if the resting panel had to be scaled down the screen must be told
            // the smaller number or it would reserve space nothing occupies.
            val restingThickness =
                if (panelDocument == null || restingSizeNow == null) 0.dp
                else ModulePanelSpec.boxFor(restingSizeNow, panelLongEdge, panelAvailableThickness)
                    .thickness

            // The panel is off its edge when retracted, so it costs the screen nothing — but the tab
            // bar is always paid for. It is DASH's own chrome rather than the king's castle, it is
            // the peek strip, and it is the one thing that must never become unreachable.
            val visiblePanelThickness = if (panelRetracted) 0.dp else panelThickness
            val assemblyThickness = restingThickness + tabThickness

            // The diagnostic overlay is gone (roadmap 1.5.15). Four lines of grey 10sp — pixel size,
            // native dpi, the applied density preset and the old dashScale — pinned permanently over
            // the top-left of the user's home screen since 1.1.x, when reading those numbers while
            // changing density was the whole job. It never got a gate. System › About DASH now
            // reports all of it and more, on demand, which is where a device readout belongs.
            // Settings shell (roadmap 1.5.2). Drawn *before* the bar so the bar stays on top and
            // reachable while the panel is open. It rolls out from under the bar like a blind: an
            // explicitly animated height from 0 to the region's measured full height, clipped so the
            // content is revealed rather than stretched. This is duration-accurate on purpose —
            // AnimatedVisibility's expandVertically does not animate when the container is forced to
            // fillMaxSize, which is why the transition-speed setting had no visible effect.
            // The bounds conform to the surrounding chrome on both edges — the bar on one, the module
            // panel on the other. The panel is persistent, so settings always yields to it and can
            // never cover it (Roger, 1.6.2): DASH's own chrome does not sit on top of the king's
            // castle. That leaves the blind rolling out into the band between the two.
            val barIsTop = barConfig.position == BarPosition.TOP
            // Settings conforms to both surfaces on whichever edges they hold — the bar on its one,
            // the panel on its own, and they can never be the same edge. A fixed-ratio panel derives
            // its thickness from the length of its edge, so on a wide screen it can ask for more
            // than the screen has; the vertical insets are clamped so the blind is never handed
            // negative space to lay out in. That guards the arithmetic only — the panel still draws
            // at its true ratio, so an overflowing shape stays visibly wrong rather than trimmed.
            //
            // **Settings conforms to the whole assembly, panel and tab bar together** (1.6.8). The
            // bar is DASH's own chrome rather than the king's castle, so covering it would not break
            // 1.6.2's rule — but it is also the only way to change modules, and a surface that can
            // be covered by another DASH surface is the exact trap 1.6.9 exists to prevent. One
            // inset around both is simpler than two rules and cannot drift apart.
            val panelVerticalInset =
                if (panelEdge.horizontal) (assemblyThickness).coerceAtMost(maxHeight - barThickness) else 0.dp
            val settingsTopInset =
                (if (barIsTop) barThickness else 0.dp) + (if (panelEdge == PanelEdge.TOP) panelVerticalInset else 0.dp)
            val settingsBottomInset =
                (if (!barIsTop) barThickness else 0.dp) + (if (panelEdge == PanelEdge.BOTTOM) panelVerticalInset else 0.dp)
            val settingsStartInset = if (panelEdge == PanelEdge.LEFT) assemblyThickness else 0.dp
            val settingsEndInset = if (panelEdge == PanelEdge.RIGHT) assemblyThickness else 0.dp
            /*
             * **The viewport** (DASH-AA) — the space the bar and the *resting* panel assembly leave over,
             * which is exactly the rectangle the settings blind rolls into (upstream 1.7.1 measured the
             * same one). Drawn before the blind and before the panel, so both cover it as they cover an
             * app on Android. Hidden in bar edit mode, which is a focused workspace (upstream's rule).
             *
             * It is told what covers it, so the touchscreen reader never reaches through DASH's chrome to
             * the phone: the settings blind or edit mode covers it all; an expanded panel covers its own
             * rectangle (it is drawn *over* the viewport at 1.6.9, never laid out into it).
             */
            if (!editMode) {
                val viewportW = screenWidth - settingsStartInset - settingsEndInset
                val viewportH = maxHeight - settingsTopInset - settingsBottomInset
                LaunchedEffect(showSettings, showSplash, overlayUp, expansion.expanded, panelEdge, panelThickness, tabThickness, viewportW, viewportH) {
                    val extra = if (expansion.expanded && !panelRetracted) (panelThickness + tabThickness - assemblyThickness) else 0.dp
                    val areas = if (extra <= 0.dp || viewportW <= 0.dp || viewportH <= 0.dp) emptyList() else {
                        val fx = (extra / viewportW).coerceIn(0f, 1f)
                        val fy = (extra / viewportH).coerceIn(0f, 1f)
                        listOf(when (panelEdge) {
                            PanelEdge.TOP -> java.awt.geom.Rectangle2D.Float(0f, 0f, 1f, fy)
                            PanelEdge.BOTTOM -> java.awt.geom.Rectangle2D.Float(0f, 1f - fy, 1f, fy)
                            PanelEdge.LEFT -> java.awt.geom.Rectangle2D.Float(0f, 0f, fx, 1f)
                            PanelEdge.RIGHT -> java.awt.geom.Rectangle2D.Float(1f - fx, 0f, fx, 1f)
                        })
                    }
                    dashApp.androidAuto.updateCovered(fully = showSettings || showSplash || overlayUp, areas = areas)
                }
                AndroidAutoViewport(
                    host = dashApp.androidAuto,
                    window = window,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            top = settingsTopInset,
                            start = settingsStartInset,
                        )
                        .width(viewportW.coerceAtLeast(0.dp))
                        .height(viewportH.coerceAtLeast(0.dp)),
                )
            } else {
                LaunchedEffect(Unit) { dashApp.androidAuto.updateCovered(fully = true, areas = emptyList()) }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxSize()
                    .padding(
                        top = settingsTopInset,
                        bottom = settingsBottomInset,
                        start = settingsStartInset,
                        end = settingsEndInset,
                    )
            ) {
                val fullHeight = maxHeight
                // Open and close are two separate transitions with their own durations — the blind
                // can roll out slow and snap back fast, or any pairing the user picks. The direction
                // is read from the target: expanding uses OPEN, collapsing uses CLOSE.
                val revealed by animateDpAsState(
                    targetValue = if (showSettings) fullHeight else 0.dp,
                    animationSpec = tween(
                        transitions.millis(
                            if (showSettings) TransitionId.SETTINGS_PANEL_OPEN else TransitionId.SETTINGS_PANEL_CLOSE
                        )
                    ),
                    label = "settingsReveal"
                )
                if (revealed > 0.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(revealed)
                            .align(if (barIsTop) Alignment.TopStart else Alignment.BottomStart)
                            .clipToBounds(),
                        // Anchor the full-height content to the bar's edge so the blind uncovers it
                        // from that edge — content stays put, the clip window grows over it.
                        contentAlignment = if (barIsTop) Alignment.TopStart else Alignment.BottomStart
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().height(fullHeight)) {
                            key(settingsJump) {
                                SettingsShell(
                                    initialSubId = settingsReturnTarget,
                                    onClose = { showSettings = false; settingsReturnTarget = null },
                                )
                            }
                        }
                    }

                    // A thin rule at the bar boundary sets the panel apart from the system bar.
                    // The panel region is inset by the bar height, so this rides the bar's inner
                    // edge — growing the bar with the size slider visibly moves it. Most of the
                    // width, centred, in the secondary surface colour; fades in with the blind.
                    Box(
                        modifier = Modifier
                            .align(if (barIsTop) Alignment.TopCenter else Alignment.BottomCenter)
                            .fillMaxWidth(0.86f)
                            .height(1.dp)
                            // Nearly imperceptible — just enough to catch the boundary. The reveal
                            // progress fades it in; the 0.16 ceiling keeps it a whisper at full open.
                            .alpha((revealed / fullHeight).coerceIn(0f, 1f) * 0.16f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LocalDashTheme.current.backgroundColourSecondary)
                    )
                }
            }

            // Bar + ruler column. Anchored to the same edge as the bar; ruler slides in adjacent
            // to the bar on its inner side (below if top-docked, above if bottom-docked).
            // The Column grows away from the screen edge as the ruler expands — the bar stays
            // fixed against the edge and the ruler grows inward.
            val activeConfig = editConfig ?: barConfig
            Column(
                modifier = Modifier
                    .align(if (activeConfig.position == BarPosition.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                if (activeConfig.position == BarPosition.TOP) {
                    SystemBar(
                        config = activeConfig,
                        onAction = { action ->
                            // The settings button toggles the panel: open when closed, close when open.
                            if (!editMode && action is DashAction.OpenSettings) toggleSettings()
                        },
                        onElementMeasured = { id, width -> elementWidths = elementWidths + (id to width) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AnimatedVisibility(
                        visible = editMode,
                        enter = fadeIn(tween(200)) + expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                        ),
                        exit = fadeOut(tween(150)) + shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(180)
                        )
                    ) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            EditRuler(
                                config = activeConfig,
                                elementWidths = elementWidths,
                                barPosition = activeConfig.position,
                                onConfigChange = { editConfig = it }
                            )
                        }
                    }
                } else {
                    AnimatedVisibility(
                        visible = editMode,
                        enter = fadeIn(tween(200)) + expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                        ),
                        exit = fadeOut(tween(150)) + shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(180)
                        )
                    ) {
                        Column {
                            EditRuler(
                                config = activeConfig,
                                elementWidths = elementWidths,
                                barPosition = activeConfig.position,
                                onConfigChange = { editConfig = it }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    SystemBar(
                        config = activeConfig,
                        onAction = { action ->
                            // The settings button toggles the panel: open when closed, close when open.
                            if (!editMode && action is DashAction.OpenSettings) toggleSettings()
                        },
                        onElementMeasured = { id, width -> elementWidths = elementWidths + (id to width) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Edit workspace — with the bar's configuration now living in settings (Position and Zones
            // in Layout › System Bar; Bar Height and Element Size in Appearance › Size & Scale), edit
            // mode is reduced to its one irreducible job: the ruler beside the bar, plus Save / Cancel.
            // SAVE commits the ruler's in-progress config to DataStore; CANCEL discards it (barConfig,
            // from DataStore, is the implicit snapshot). Colours read from the theme tokens.
            if (editMode) {
                val editTheme = LocalDashTheme.current
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    // On the DASH button idiom since 1.5.15. These two were the last controls in
                    // DASH still wearing Material's shape, elevation, ripple and padding — everything
                    // else moved across at 1.5.8 and these were missed, which is why edit mode was
                    // the one workspace that didn't look like the rest of the system.
                    DashButton(
                        label = "CANCEL",
                        fill = editTheme.accentColourSecondary,
                        ink = editTheme.textColourSecondary,
                        onClick = {
                            editConfig = null
                            editMode = false
                            if (settingsReturnTarget != null) showSettings = true
                        },
                    )
                    DashButton(
                        label = "SAVE",
                        fill = editTheme.backgroundColourPrimary,
                        ink = editTheme.textColourPrimary,
                        onClick = {
                            editConfig?.let { scope.launch { prefs.saveSystemBarConfig(it) } }
                            editConfig = null
                            editMode = false
                            if (settingsReturnTarget != null) showSettings = true
                        },
                    )
                }
            }

            // The module panel (roadmap 1.6.2) — DASH's defining surface, on screen for the first
            // time. Large slot, persistent, one panel; docking is 1.6.3, the other sizes 1.6.4.
            //
            // **It never shares an edge with the bar** (Roger, 1.6.2) — where interface.md previously
            // had the panel beginning where the bar ends, the two now cannot meet at all. The user's
            // chosen edge is honoured unless the bar is already there, in which case the panel is
            // displaced to the opposite edge for as long as the collision lasts; see [effectiveEdge]
            // for why the stored preference is never rewritten.
            //
            // Positioned from the top-left rather than by Alignment so the move between edges can be
            // animated: an edge change moves the panel in two dimensions and resizes it (the long
            // edge changes from screen width to screen height less the bar), which four animated
            // values express directly and an Alignment switch cannot express at all. Registered as
            // MODULE_PANEL_MOVE, so its speed is the user's like every other transition.
            //
            // Hidden in edit mode, carrying forward the placeholder's rule: edit mode is a focused
            // workspace holding nothing but the bar, its ruler and Save/Cancel, and a panel this
            // size would crowd the ruler being dragged. Permanent means permanent on the home
            // screen, not inside a temporary task.
            if (!editMode && panelDocument != null) {
                // A vertical panel starts below a top bar and ends above a bottom one, so it always
                // clears the bar rather than running under it.
                // Retraction is a displacement of exactly the panel's own thickness, off whichever
                // edge it holds — so it rides the existing MODULE_PANEL_MOVE animation and slides
                // rather than blinking out. Covering it with the settings blind was the alternative
                // and reads worse: the panel would simply be gone, where this shows the castle
                // stepping aside and visibly coming back.
                val hide = if (panelRetracted) panelThickness else 0.dp
                // [panelLongInset] centres a compacted panel on its edge and is zero otherwise, so
                // the ordinary full-width case is unchanged and needs no branch of its own.
                val panelTargetX = when (panelEdge) {
                    PanelEdge.RIGHT -> screenWidth - panelWidth + hide
                    PanelEdge.LEFT -> -hide
                    else -> panelLongInset
                }
                val panelTargetY = when {
                    panelEdge == PanelEdge.TOP -> -hide
                    panelEdge == PanelEdge.BOTTOM -> maxHeight - panelHeight + hide
                    else -> (if (barIsTop) barThickness else 0.dp) + panelLongInset
                }
                val moveSpec = tween<Dp>(transitions.millis(TransitionId.MODULE_PANEL_MOVE))
                val panelX by animateDpAsState(panelTargetX, moveSpec, label = "modulePanelX")
                val panelY by animateDpAsState(panelTargetY, moveSpec, label = "modulePanelY")
                val panelW by animateDpAsState(panelWidth, moveSpec, label = "modulePanelW")
                val panelH by animateDpAsState(panelHeight, moveSpec, label = "modulePanelH")

                /*
                 * **The band either side of a compacted panel** *(Roger — `backgroundColourPrimary`
                 * either side)*. It spans the full edge behind the panel, so what a compacted panel
                 * leaves uncovered reads as DASH's own floor rather than a hole onto the app behind.
                 *
                 * **It is not part of the module's box.** The castle walls are the panel rectangle
                 * itself; this is DASH painting its own surface outside them, which is why it is a
                 * sibling of the panel rather than padding inside it — the Module Mantra is about
                 * what DASH puts *within* the boundary, and nothing here crosses it.
                 *
                 * It takes the panel's own offsets so it retracts with it, and is drawn only when
                 * the panel actually compacted — otherwise the panel covers it exactly and it would
                 * be pure overdraw on every device that never needed it.
                 */
                if (panelBox.compacted) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = if (panelEdge.horizontal) 0.dp else panelX,
                                y = if (panelEdge.horizontal) panelY else
                                    (if (barIsTop) barThickness else 0.dp),
                            )
                            .width(if (panelEdge.horizontal) panelLongEdge else panelW)
                            .height(if (panelEdge.horizontal) panelH else panelLongEdge)
                            .background(LocalDashTheme.current.backgroundColourPrimary)
                    )
                }

                /*
                 * **The switch is a cross-fade, and the outgoing panel is held until the incoming one
                 * is ready** (roadmap 1.6.8). `Crossfade` gives that second half for free rather than
                 * needing a gate: its target only changes when [rememberPanelDocument] has a document
                 * in hand, so a panel still being read off disk simply has not arrived yet and the one
                 * on screen stays drawn. There is never an empty box.
                 *
                 * Not a slide. Tapping a tab can jump from the first module to the fourth, so there is
                 * nothing meaningful to slide past, and sliding would tell a story about modules
                 * living side by side that the tab bar does not support.
                 *
                 * **This is DASH's transition, so it takes the user's speed setting** — the exact
                 * opposite of anything *inside* a panel, where `module-layout.md` §5 gives the module's
                 * own durations and DASH's setting never enters.
                 */
                Crossfade(
                    targetState = panelDocument,
                    animationSpec = tween(transitions.millis(TransitionId.MODULE_PANEL_SWITCH)),
                    modifier = Modifier.align(Alignment.TopStart).offset(x = panelX, y = panelY),
                    label = "modulePanelSwitch",
                ) { document ->
                    /*
                     * **The values and the press handler are per-panel, not per-screen** (1.6.8).
                     * Both panels are composed during a cross-fade, and each draws its own module's
                     * reports — so this moved down here from the screen, where a single call could
                     * only ever describe one of them.
                     *
                     * Keying on the module also keeps 1.6.7's rule that switching panels retires the
                     * outgoing module's outstanding presses with it. That is deliberate rather than
                     * incidental: a prediction exists so the panel does not look dead under a finger,
                     * and once the panel is not on screen there is nothing to draw ahead of.
                     */
                    val presses = rememberPanelPresses(
                        moduleData = controller.moduleData,
                        actions = controller.actions,
                        moduleId = document.moduleId,
                        variables = document.layout.variables,
                    )
                    ModulePanel(
                        document = document,
                        values = presses.values,
                        width = panelW,
                        height = panelH,
                        onPress = presses.press,
                        // Observed, never claimed — the dwell must not fold the panel shut under a
                        // finger, and 1.6.8 gave the gesture itself to the module.
                        onTouch = { expansion.noteTouch(); panelTouchTick++ },
                    )
                }

                // The tab bar, on the panel's inboard edge — the side facing the content area. It
                // moves and resizes with the panel on the same MODULE_PANEL_MOVE spec, because it is
                // part of the same assembly and the two arriving at an edge separately would read as
                // a fault.
                val tabTargetX = when (panelEdge) {
                    PanelEdge.RIGHT -> screenWidth - visiblePanelThickness - tabThickness
                    PanelEdge.LEFT -> visiblePanelThickness
                    else -> 0.dp
                }
                val tabTargetY = when (panelEdge) {
                    PanelEdge.TOP -> visiblePanelThickness
                    PanelEdge.BOTTOM -> maxHeight - visiblePanelThickness - tabThickness
                    else -> if (barIsTop) barThickness else 0.dp
                }
                val tabW = if (panelEdge.horizontal) screenWidth else tabThickness
                val tabH = if (panelEdge.horizontal) tabThickness else panelLongEdge
                val tabX by animateDpAsState(tabTargetX, moveSpec, label = "moduleTabsX")
                val tabY by animateDpAsState(tabTargetY, moveSpec, label = "moduleTabsY")

                // Hidden is hidden — no bar, and [ModulePanelConfig.tabBarDp] has already handed its
                // thickness back to the viewport.
                if (modulePanelConfig.tabShown) ModuleTabs(
                    modules = panelCandidates,
                    selectedId = panelDocument.moduleId,
                    horizontal = panelEdge.horizontal,
                    width = tabW,
                    height = tabH,
                    style = modulePanelConfig.tabStyle,
                    spread = modulePanelConfig.tabSpread,
                    colour = modulePanelConfig.tabColour,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = tabX, y = tabY),
                    onSelect = { id ->
                        /*
                         * **A tap means: show me this module, at the largest slot it can fill.**
                         *
                         * Tapping the module already on screen toggles it, which is how the panel is
                         * folded away by hand rather than waiting out the timer. Tapping a different
                         * one selects it and opens it — unless it has no full layout to open into,
                         * in which case it simply becomes what rests there. That last case is what
                         * keeps every tab a live control: a module shipping only a small layout is
                         * reached by a tap that returns the panel to rest, rather than by a tab that
                         * does nothing.
                         */
                        val target = panelCandidates.firstOrNull { it.id == id }
                        val canExpand = modulePanelConfig.visibility.expands &&
                            target?.canFill(fullSlot) == true
                        if (id == selectedModule?.id && expansion.expanded) {
                            expansion.collapse()
                        } else if (canExpand) {
                            expansion.expand()
                        } else {
                            expansion.collapse()
                        }
                        tappedModuleId = id
                        scope.launch { prefs.saveModulePanelLastModule(id) }
                    },
                )
            }

            // The legacy flat settings panel is gone (roadmap 1.5.15). It existed from 1.5.2 as a
            // temporary bridge so nothing built in 1.1.x–1.4.x was unreachable while the settings
            // shell was filled in one tab at a time. Every control it held has a real home now —
            // the last one, ROTATION, went to Layout › Rotation in this same version.

            // Module Management is now a settings tab (Modules › Module Management, 1.5.8) rendered
            // inside the shell — no standalone route. It reaches the controller's managers through
            // LocalModuleDesk, provided above.

            // The Serial Monitor and Signal Monitor are settings tabs since 1.5.12 (Modules › Serial
            // Monitor / Signal Monitor), rendered inside the shell — no standalone routes any more.
            // They reach the transport layer and the sourceless core through the two desks above.

            // DASH-AA 1.1.6: the Bluetooth pairing question, then the keyboard (which a PIN is typed with) over it.
            com.dash.android.ui.connections.PairingPrompt()
            com.dash.android.ui.keyboard.KeyboardOverlay(Modifier.align(Alignment.BottomCenter))
            LaunchedEffect(showSettings) { if (!showSettings && pairingUp == null) com.dash.android.ui.keyboard.DashKeyboard.close() }

            // Splash overlay — sits above everything, including the settings panel. "None" is a real
            // choice (roadmap 1.5.15, Roger): DASH has no opinion on whether you want a splash, so
            // that mode skips it entirely rather than showing an empty one for zero milliseconds.
            if (showSplash && splashMode != "NONE") {
                SplashScreen(
                    mode = splashMode,
                    backgroundColour = splashColour,
                    imageUri = splashImageUri,
                    animationUri = splashAnimationUri,
                    imageCropPortrait = splashCropPortrait,
                    imageCropLandscape = splashCropLandscape,
                    dwellMillis = splashDwell,
                    fadeInMillis = transitions.millis(TransitionId.SPLASH_FADE_IN),
                    fadeOutMillis = transitions.millis(TransitionId.SPLASH_FADE_OUT),
                    onDismiss = { showSplash = false }
                )
            }
        }
    }
}
