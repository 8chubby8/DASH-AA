package android.content

import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * DASH-AA platform shim — opens the files a user picked. On Android a picked image is a
 * `content://` URI granted by the system picker; on the desktop it is simply a path (or a `file://`
 * URI), so opening one is opening the file. Returns null for something that is not there, as
 * Android's does, so upstream's splash code falls back exactly as it would on a revoked grant.
 */
class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? {
        val file = uri.toFile() ?: return null
        return if (file.isFile) file.inputStream() else null
    }
}

/** The local file a [Uri] names, or null if it is not a local file. */
fun Uri.toFile(): File? = when (scheme) {
    null, "", "file" -> path?.let { File(it) }
    else -> null
}
