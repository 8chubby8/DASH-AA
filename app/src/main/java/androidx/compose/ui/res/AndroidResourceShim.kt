package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import com.dash.android.R
import org.jetbrains.skia.Image
import org.xml.sax.InputSource

/**
 * DASH-AA platform shim — Android's integer-id resource loaders, resolved through the [R] shim.
 * Desktop Compose already loads both PNGs and Android vector-drawable XML from the classpath; these
 * overloads only translate the id upstream passes into the file it names.
 */
@Composable
fun painterResource(id: Int): Painter {
    val path = R.paths[id] ?: error("No resource for id $id")
    return if (path.endsWith(".xml")) {
        val density = LocalDensity.current
        val vector = remember(path, density) {
            openResource(path).use { loadXmlImageVector(InputSource(it), density) }
        }
        rememberVectorPainter(vector)
    } else {
        val bitmap = remember(path) { ImageBitmap.imageResource(id) }
        remember(bitmap) { BitmapPainter(bitmap) }
    }
}

fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap {
    val path = R.paths[id] ?: error("No resource for id $id")
    return openResource(path).use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

private fun openResource(path: String) =
    R::class.java.getResourceAsStream("/$path") ?: error("Resource not bundled: $path")
