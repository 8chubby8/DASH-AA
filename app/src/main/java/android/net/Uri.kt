package android.net

import java.net.URI

/**
 * DASH-AA platform shim — a URI as upstream uses one: parsed from the string it was stored as,
 * asked for its scheme and path, and turned back into that same string. Plain paths (what the
 * desktop file picker produces) parse as scheme-less URIs, so a stored "/home/…/splash.png" and a
 * stored "file:///home/…/splash.png" both work.
 */
class Uri private constructor(private val text: String) {

    val scheme: String? get() = if (text.startsWith("/")) null else parsed?.scheme
    val path: String? get() = if (text.startsWith("/")) text else parsed?.path

    private val parsed: URI? by lazy { runCatching { URI(text) }.getOrNull() }

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)
        val EMPTY: Uri = Uri("")
    }
}
