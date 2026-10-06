package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream

/**
 * DASH-AA platform shim — bundled assets, read from the classpath (`/assets/<path>`).
 * Throws [FileNotFoundException] for a missing asset, exactly as Android's does, because upstream's
 * callers rely on that to fall through to their next source.
 */
class AssetManager {
    fun open(path: String): InputStream =
        AssetManager::class.java.getResourceAsStream("/assets/${path.trimStart('/')}")
            ?: throw FileNotFoundException("asset not bundled: $path")

    /** Names directly inside an asset folder. Classpath resources cannot be listed reliably from a
     *  jar, so this answers from an index the build does not keep — an empty list, which upstream
     *  treats as "nothing bundled", the honest answer for the fork today. */
    fun list(path: String): Array<String> = emptyArray()
}
