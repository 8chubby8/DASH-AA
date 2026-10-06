package com.dash.android.ui.modules

import androidx.compose.runtime.compositionLocalOf
import com.dash.android.core.SystemState
import com.dash.android.transport.Discovery
import com.dash.android.transport.Install
import com.dash.android.transport.ModuleDatabase
import com.dash.android.transport.Reconciliation

/**
 * The live module desk handed to the Modules settings tab (roadmap 1.5.8).
 *
 * Every other live settings tab rebuilds its dependency from context — [com.dash.android.prefs.DashPreferences]
 * is a stateless wrapper over DataStore, cheap and safe to reconstruct wherever it's read. The four
 * managers below are the opposite: they are *stateful*, they hold the live discovery / install /
 * reconciliation state, and they live on [com.dash.android.transport.DashController] for the app's
 * whole life. Reconstruct them and the tab shows a dead, empty screen. So they must be *reached*, not
 * rebuilt — provided once in MainScreen (beside `LocalEnterBarEdit`) and read by [ModulesContent].
 *
 * The default is null so a read outside the provider (a preview, a stray call) is inert rather than a
 * crash — [ModulesContent] shows an honest empty state when the desk isn't wired.
 */
data class ModuleDesk(
    val discovery: Discovery,
    val install: Install,
    val database: ModuleDatabase,
    val reconciliation: Reconciliation,
    val onUpdate: (String) -> Unit,
    /**
     * The sourceless core the Signal Monitor renders (roadmap 1.5.12). It belongs on this desk because
     * modules are what fill it: every `BROADCAST` that passes the gatekeeper lands here as a value.
     * It is deliberately *sourceless* (arduino.md §5) — the store holds "the current state of
     * `gear_position`", never "what module X said" — which is exactly why two boards can both feed
     * `ambient_temp` and the monitor simply shows the latest.
     */
    val systemState: SystemState,
)

val LocalModuleDesk = compositionLocalOf<ModuleDesk?> { null }
