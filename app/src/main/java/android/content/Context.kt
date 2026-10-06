package android.content

import android.content.res.AssetManager
import java.io.File

/**
 * DASH-AA platform shim — the slice of `android.content.Context` that upstream's shared code uses.
 *
 * Upstream reaches for a Context for four things only: where to keep files (`filesDir`), where
 * bundled read-only data lives (`assets`), a user-visible folder for drop-in art
 * (`getExternalFilesDir`), and opening a picked file (`contentResolver`). Each maps onto an ordinary
 * place on a Linux desktop, so the files that ask compile unchanged. Anything Android-shaped beyond
 * that — permissions, intents, system services — is deliberately *not* here: code that needs it is
 * Android-only and was dropped or edited in the fork, and a compile error is the honest signal.
 *
 * **Where the data lives.** `$XDG_DATA_HOME/dash-aa` (normally `~/.local/share/dash-aa`), overridable
 * with `DASH_HOME` for tests and for running two copies side by side.
 */
open class Context protected constructor(private val home: File) {

    constructor() : this(defaultHome())

    open val applicationContext: Context get() = this

    val packageName: String = "com.dash.android"

    /** Private app storage — the module database and DataStore live under here. */
    open val filesDir: File get() = File(home, "files").apply { mkdirs() }

    /** Read-only data bundled into the build (`src/main/resources/assets`). */
    val assets: AssetManager = AssetManager()

    val contentResolver: ContentResolver = ContentResolver()

    /** Only `displayMetrics` — upstream's Module Panel tab draws its tiles in this screen's proportions. */
    val resources: android.content.res.Resources = android.content.res.Resources()

    /** A user-reachable folder — upstream's drop-in weather art reads from here. */
    fun getExternalFilesDir(type: String?): File? =
        File(home, if (type == null) "external" else "external/$type").apply { mkdirs() }

    companion object {
        fun defaultHome(): File {
            System.getenv("DASH_HOME")?.let { return File(it) }
            val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return File(data, "dash-aa")
        }
    }
}
