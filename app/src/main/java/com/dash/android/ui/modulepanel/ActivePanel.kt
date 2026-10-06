package com.dash.android.ui.modulepanel

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dash.android.panel.Ambient
import com.dash.android.panel.LayoutSlot
import com.dash.android.panel.PanelDocument
import com.dash.android.panel.PanelLoader
import com.dash.android.panel.SlotOrientation
import com.dash.android.transport.InstalledModule
import com.dash.android.transport.ModuleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DashActivePanel"

/**
 * The modules that could fill this slot right now — one tab each (roadmap 1.6.8).
 *
 * > **No layout, no panel.** *(`module-layout.md` §6, ruled 2026-08-12.)*
 *
 * DASH holds a record of every layout every installed module provided, so it always knows what it
 * can honestly draw. A module with no layout for the selected size is **simply not shown** — not
 * stretched, not substituted, not apologised for with a placeholder. And with nothing in this list,
 * **no panel is drawn at all** and no tab bar with it.
 *
 * **Membership is the whole rule: installed ACCESSORY, has a layout for the selected slot.**
 * SYSTEM and LISTENER modules never appear — they have no panel to draw, so [InstalledModule.slots]
 * is empty and [canFill] answers false without needing to know the module's type.
 *
 * **A silent module keeps its tab** *(Roger, 2026-08-19)*. The install record is the tenancy: §6 is a
 * rule about layouts, not about liveness, and DASH does not annotate a tab with the state of its
 * board. It also stops tabs appearing and vanishing every time a board brownouts or reconnects,
 * which would move the tab the user was reaching for.
 *
 * **Different id, different module** *(Roger)*. `GaugeWifi` and `GaugeBt` are the same artwork on two
 * ids and get two identical tabs. DASH neither disambiguates them nor comments on it.
 *
 * **No ordering in this version** *(Roger)*. The database's own order, which is arrival order. The
 * panel order, the dominant module and the return dwell are one story and are told once, at 1.6.11.
 */
@Composable
fun rememberPanelCandidates(
    database: ModuleDatabase,
    slot: LayoutSlot,
): List<InstalledModule> {
    val modules by database.modules.collectAsState()
    // Which modules *could* fill this slot is cheap — each record already says which slots it
    // shipped, so answering it never touches the disk. Only the one on screen is ever loaded.
    val candidates by remember(modules, slot) {
        derivedStateOf { modules.values.filter { it.canFill(slot) } }
    }
    return candidates
}

/**
 * The panel document for one module, read off disk (roadmap 1.6.6, per-module since 1.6.8).
 *
 * **Load on demand, and nothing is cached** *(roadmap 1.6.8, measured rather than assumed)*. The
 * Tank Gauge is a 1.4 KB layout, a 10.6 KB vector and a 1600 × 600 PNG that decodes to 3.7 MB —
 * roughly 20–40 ms all in, one to three frames. Climate is a 14 KB layout and one 10 KB vector with
 * no raster at all, under 10 ms. **There is no load worth hiding**, so only the panel on screen is
 * held decoded and memory is bounded at one panel however many modules are installed. An LRU cache
 * was designed for this and thrown away when the numbers came in; it was machinery for a cost that
 * does not exist.
 *
 * **A document that fails to load leaves the previous one on screen.** Not a nicety: the tab bar is
 * drawn from the panel's own geometry, so letting a failed load collapse the panel would take the
 * tab bar down with it and strand the user on a module they cannot switch away from. DASH cannot
 * draw what it cannot read, but it can decline to throw away what it already has.
 */
@Composable
fun rememberPanelDocument(
    database: ModuleDatabase,
    module: InstalledModule?,
    slot: LayoutSlot,
): PanelDocument? {
    val context = LocalContext.current
    val loader = remember(database) { PanelLoader(context, database) }

    var document by remember { mutableStateOf<PanelDocument?>(null) }

    LaunchedEffect(module?.id, module?.assets, slot) {
        if (module == null) {
            document = null
            return@LaunchedEffect
        }
        // Reading a layout means reading files and decoding a PNG that may be several megabytes. The
        // panel is the surface that must never hitch, so none of that happens on the main thread.
        val loaded = withContext(Dispatchers.IO) { loader.load(module, slot) }
        if (loaded != null) {
            document = loaded
        } else {
            Log.w(TAG, "${module.id} could not be drawn for $slot — keeping the panel already shown")
        }
    }

    return document
}

/**
 * Whether this module has a layout for [slot], including §6's night→day fallback.
 *
 * That fallback is the only one there is, and it is uncontroversial because it is the same artwork
 * rather than a distorted shape. Nothing falls back across a *size*.
 */
fun InstalledModule.canFill(slot: LayoutSlot): Boolean {
    val available = slots.toSet()
    return slot in available ||
        (slot.ambient == Ambient.NIGHT && slot.withAmbient(Ambient.DAY) in available)
}

/**
 * The slot to draw, from where the panel is docked and how big the user asked for it.
 *
 * **Ambient is always [Ambient.DAY] for now.** The switch that would choose otherwise is a version
 * 2 setting, so six of the twelve slots are unreachable across the whole 1.6.x era — night artwork
 * a module ships today is accepted, stored and never selected. That is the honest state of it, and
 * it is why the night path is a fallback in [canFill] rather than a branch here pretending to
 * decide something.
 */
fun slotFor(size: PanelSize, edge: PanelEdge): LayoutSlot =
    LayoutSlot(
        size = size,
        orientation = if (edge.horizontal) SlotOrientation.HORIZONTAL else SlotOrientation.VERTICAL,
        ambient = Ambient.DAY,
    )

// The panel's values moved to PanelPress.kt at 1.6.7. Reading the store was only half the story
// once a control could be pressed: what the panel draws is the module's reported variables *with
// any outstanding prediction laid over the top*, and that belongs with the press that made it.
