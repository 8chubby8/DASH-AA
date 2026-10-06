package com.dash.android.transport

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The module database (roadmap 1.4.5) — the on-disk installed list that arduino.md §6 names as the
 * single source of truth: "DASH holds the installed list on disk and tells the module what to do each
 * session." The module persists nothing; this is where DASH remembers.
 *
 * **The database holds *installed*; the [Install] desk holds *installing*.** An install that completes
 * lands here via [commit] and survives every restart from then on; the desk's per-session progress
 * state never touches disk. Startup reconciliation (1.4.6) reads the same [modules] flow to decide who
 * gets `ACTIVATE`d each boot.
 *
 * **Layout.** One folder per module under `filesDir/modules/`: a `module.json` (the serialised
 * [InstalledModule]) beside an `assets/` folder holding each ACCESSORY block's raw bytes as a plain
 * file. A record and its assets are born and die together, so uninstall is one recursive delete.
 * `module.json` is written *last* — a folder interrupted mid-write has no record and is skipped (and
 * swept away) on the next load, never half-loaded.
 *
 * **Threading.** The [modules] flow is updated synchronously — the UI sees a commit or uninstall
 * immediately — and the file work follows on this class's IO scope. A failed write is logged and the
 * in-memory record kept: DASH degrades to 1.4.4's session-only behaviour rather than erroring.
 */
class ModuleDatabase(context: Context) {

    private val root = File(context.filesDir, "modules")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Same lenient posture as DashPreferences: a record written by an older or newer DASH build
    // still decodes rather than throwing.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _modules = MutableStateFlow<Map<String, InstalledModule>>(emptyMap())
    /** Every installed module, keyed by id. THE installed list — absent here ⇒ not installed. */
    val modules: StateFlow<Map<String, InstalledModule>> = _modules.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** True once [load] has finished its disk read. Reconciliation (1.4.6) waits on this before its
     *  first sweep, so an early HELLO is matched against the real installed list, not an empty one. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Read the installed list off disk. Called once at controller start; replaces the flow wholesale. */
    fun load() {
        scope.launch {
            val loaded = mutableMapOf<String, InstalledModule>()
            root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val record = File(dir, RECORD_FILE)
                if (!record.isFile) {
                    // No record ⇒ an install that died mid-write. Sweep the debris.
                    Log.w(TAG, "module folder ${dir.name} has no $RECORD_FILE — removing")
                    dir.deleteRecursively()
                    return@forEach
                }
                runCatching { json.decodeFromString<InstalledModule>(record.readText()) }
                    .onSuccess { loaded[it.id] = it }
                    .onFailure { Log.w(TAG, "could not decode ${record.path} — skipped", it) }
            }
            _modules.value = loaded
            _loaded.value = true
            Log.i(TAG, "loaded ${loaded.size} installed module(s) from disk")
        }
    }

    /**
     * Commit a completed install: the record plus each asset's raw bytes, aligned index-for-index
     * with [InstalledModule.assets]. Filenames are assigned here (wire names sanitised, collisions
     * suffixed) and written into each [InstalledAsset.file] before the record is stored or saved.
     */
    fun commit(module: InstalledModule, payloads: List<ByteArray>) {
        val used = mutableSetOf<String>()
        val named = module.copy(
            assets = module.assets.mapIndexed { i, asset ->
                asset.copy(file = fileNameFor(asset.name, i, used))
            }
        )
        _modules.value = _modules.value + (named.id to named)

        scope.launch {
            runCatching {
                val dir = dirFor(named.id)
                dir.deleteRecursively()                       // a re-install replaces cleanly
                val assetsDir = File(dir, ASSETS_DIR).apply { mkdirs() }
                named.assets.forEachIndexed { i, asset ->
                    payloads.getOrNull(i)?.let { File(assetsDir, asset.file).writeBytes(it) }
                }
                File(dir, RECORD_FILE).writeText(json.encodeToString(named))
            }.onFailure {
                Log.w(TAG, "could not save module ${named.id} to disk — record is in memory only", it)
            }
        }
    }

    /** Forget a module: drop the record and delete its folder. No wire message — the module is
     *  dormant and persists nothing (arduino.md §6), so DASH forgetting *is* the uninstall. */
    fun uninstall(id: String) {
        _modules.value = _modules.value - id
        scope.launch {
            runCatching { dirFor(id).deleteRecursively() }
                .onFailure { Log.w(TAG, "could not delete module folder for $id", it) }
        }
    }

    /**
     * The file holding one of a module's asset blocks, or null if it never shipped that block
     * (roadmap 1.6.6 — the panel is the reader these bytes were written for since 1.4.5).
     *
     * Named on the **wire** name, which is what a layout references (`"dial.png"`), not the
     * sanitised filename the commit assigned. The two are usually the same and must not be assumed
     * to be: a module is free to name a block something the filesystem is not.
     */
    fun assetFile(id: String, wireName: String): File? {
        val file = _modules.value[id]?.assetFile(wireName) ?: return null
        return File(File(dirFor(id), ASSETS_DIR), file).takeIf { it.isFile }
    }

    private fun dirFor(id: String) = File(root, sanitise(id).ifBlank { "module" })

    /** A filesystem-safe name for an asset: wire name sanitised, blank fallback, collisions suffixed. */
    private fun fileNameFor(wireName: String, index: Int, used: MutableSet<String>): String {
        val base = sanitise(wireName).ifBlank { "asset_$index" }
        var candidate = base
        var n = 1
        while (!used.add(candidate)) candidate = "${n++}_$base"
        return candidate
    }

    // Wire-supplied names become file/folder names, so they must not traverse or hide: keep a plain
    // safe alphabet and never let a leading dot through.
    private fun sanitise(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')

    private companion object {
        const val RECORD_FILE = "module.json"
        const val ASSETS_DIR = "assets"
        const val TAG = "DashModuleDb"
    }
}
