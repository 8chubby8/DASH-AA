package com.dash.android.panel

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dash.android.transport.InstalledModule
import com.dash.android.transport.ModuleDatabase
import java.io.File

/**
 * A panel, loaded and ready to draw (roadmap 1.6.6).
 *
 * Everything here came off the disk during the install handshake. **The module is never consulted
 * again** (`module-layout.md` §1) — it is not asked to resend its layout, not told when the panel is
 * drawn, and plays no part in rendering. It hands over a document; how DASH stores and reads it
 * back carries no contract.
 */
data class PanelDocument(
    val moduleId: String,
    val moduleName: String,
    val slot: LayoutSlot,
    val layout: PanelLayout,
    /** Decoded artwork, keyed by the block name a layer references. */
    val raster: Map<String, ImageBitmap>,
    val vector: Map<String, VectorArt>,
    /** Everything the author should know — from the layout, and from every piece of its artwork. */
    val warnings: List<Warning>,
)

/**
 * Reads an installed module's panel off disk.
 *
 * **Not cached, not pre-parsed at install.** §1 explicitly permits DASH to normalise a layout into
 * whatever form renders fastest, and that licence is deliberately **not taken** at 1.6.6: the parser
 * spike measured the full panel rate with zero dropped frames, because the expensive work already
 * happens once — paths are built at parse time and a frame costs a matrix and a paint. Caching a
 * derived form would buy nothing measured and would add a cache to invalidate whenever a module is
 * reinstalled or the format grows. If a Bronze-tier device later says otherwise, the licence is
 * still there and nothing in the format has to change to take it.
 */
class PanelLoader(
    private val context: Context,
    private val database: ModuleDatabase,
) {

    /**
     * The panel for [module] in [slot], or null if it has none to give.
     *
     * **A missing `night` variant falls back to the `day` one** (§6) — uncontroversial, because it
     * is the same artwork rather than a distorted shape. There is no other fallback: DASH never
     * scales a layout into a slot it was not drawn for, so a module with no layout for the selected
     * size is simply not drawn.
     */
    fun load(module: InstalledModule, slot: LayoutSlot): PanelDocument? {
        val available = module.slots.toSet()
        val resolved = when {
            slot in available -> slot
            slot.ambient == Ambient.NIGHT && slot.withAmbient(Ambient.DAY) in available ->
                slot.withAmbient(Ambient.DAY)
            else -> return null
        }

        val source = database.assetFile(module.id, resolved.blockName)?.let { file ->
            runCatching { file.readText() }.getOrNull()
        } ?: run {
            Log.w(TAG, "module ${module.id} declares ${resolved.blockName} but its bytes are unreadable")
            return null
        }

        val layout = PanelLayoutReader().read(source)
        val warnings = layout.warnings.toMutableList()
        val raster = mutableMapOf<String, ImageBitmap>()
        val vector = mutableMapOf<String, VectorArt>()

        // If the subset itself will not load, that is DASH's fault and not the author's, and the
        // difference matters: told "this is not an SVG DASH could read", an author goes and stares
        // at perfectly good artwork. Told the subset is missing, they know it is nothing they did.
        val subsetLoad = runCatching { SvgSubset.load(context) }
        val subset = subsetLoad.getOrNull()
        if (subset == null && layout.layers.any { it is VectorLayer }) {
            warnings += Warning(
                Severity.SKIPPED, "DASH itself",
                "Could not load `svg-subset.json`, so no vector layer can be drawn on this build. " +
                    "This is a fault in DASH, not in your layout or your artwork.",
            )
            Log.e(TAG, "svg-subset.json is missing from the app's assets — no vector can be drawn", subsetLoad.exceptionOrNull())
        }

        for (layer in layout.layers.filterIsInstance<BoxedLayer>()) {
            if (layer.asset in raster || layer.asset in vector) continue
            val file = database.assetFile(module.id, layer.asset)
            if (file == null) {
                warnings += Warning(
                    Severity.SKIPPED, "layer #${layer.id}",
                    "References `${layer.asset}`, which this module never shipped as a block. Check " +
                        "the name against what the firmware sends.",
                )
                continue
            }
            when (layer) {
                is RasterLayer -> loadRaster(file)?.let { raster[layer.asset] = it }
                    ?: run { warnings += unreadable(layer.id, layer.asset, "an image DASH could decode") }

                is VectorLayer -> {
                    val parser = subset?.let { SvgParser(it) }
                    val art = parser?.let { p -> runCatching { file.inputStream().use { p.parse(it) } }.getOrNull() }
                    if (art == null) {
                        warnings += unreadable(layer.id, layer.asset, "an SVG DASH could read")
                    } else {
                        vector[layer.asset] = art
                        warnings += art.warnings
                    }
                }
            }
        }

        // **The warnings are the product** (the parser spike's phrase, and it holds here too).
        // Rendering tells an author what their panel looks like; these tell them what DASH did with
        // the parts it could not draw, which is the half they cannot see. Their real home is the
        // previewer and, later, the Module Manager — but a warning that reaches nobody at all is
        // how a whole layer goes missing and looks like a mystery, so at minimum they are said out
        // loud where anyone debugging a panel will find them.
        for (warning in warnings) Log.i(TAG, "${module.name} ${resolved.blockName}: $warning")

        return PanelDocument(
            moduleId = module.id,
            moduleName = module.name,
            slot = resolved,
            layout = layout,
            raster = raster,
            vector = vector,
            warnings = warnings,
        )
    }

    /**
     * Decode a raster at full fidelity, and if the device will not give the memory, **decode it
     * smaller rather than refuse it** (§2).
     *
     * This is the Capability Detection Principle applied to artwork: attempt, and where it will not
     * go, fall back. DASH imposes no maximum asset size and rejects nothing for being too large —
     * an author draws what they want to draw, and the panel renders a little softer on weak hardware
     * and pin-sharp on strong hardware. A fixed cap would have punished the capable hardware in
     * order to protect the weak.
     *
     * It does not breach the Module Mantra: DASH already scales every asset to whatever size the
     * panel happens to be on a given screen, and this is the same proportional operation with a
     * different trigger. It renders the author's design at lower fidelity; it does not change the
     * design.
     */
    private fun loadRaster(file: File): ImageBitmap? {
        var sample = 1
        while (sample <= MAX_DOWNSAMPLE) {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            try {
                val bitmap = BitmapFactory.decodeFile(file.path, options)
                // Null without an OutOfMemoryError means the bytes are not an image DASH can decode.
                // Halving the sample size will not make a corrupt PNG decodable, so stop asking.
                if (bitmap == null) return null
                if (sample > 1) Log.i(TAG, "${file.name} decoded at 1/$sample to fit available memory")
                return bitmap.asImageBitmap()
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "${file.name} would not fit at 1/$sample — halving", e)
                sample *= 2
            }
        }
        return null
    }

    private fun unreadable(layerId: String, asset: String, expected: String) = Warning(
        Severity.SKIPPED, "layer #$layerId",
        "`$asset` arrived, but it is not $expected. The layer is not drawn.",
    )

    private companion object {
        const val TAG = "DashPanelLoader"

        // 1/16 of each axis is 1/256 of the pixels. Past that an asset is no longer recognisable as
        // the author's work, and drawing an unrecognisable smear would be a worse answer than the
        // honest nothing the browser rule gives.
        const val MAX_DOWNSAMPLE = 16
    }
}
