package android.graphics

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import java.io.File
import java.io.InputStream

/**
 * DASH-AA platform shim — `android.graphics.BitmapFactory`, decoded by Skia (which is also what
 * Android decodes with underneath).
 *
 * The two options upstream relies on are honoured, because they carry behaviour rather than style:
 * - `inJustDecodeBounds` — read the size without decoding (the splash picker sizes before it loads);
 * - `inSampleSize` — decode at 1/n. **This is module-layout.md §2's graceful degradation**: the panel
 *   loader halves until an asset fits in memory rather than refusing it, so the shim must genuinely
 *   produce a smaller image, not ignore the request.
 */
object BitmapFactory {

    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var outWidth: Int = -1
        @JvmField var outHeight: Int = -1
    }

    @JvmStatic fun decodeFile(pathName: String): Bitmap? = decodeFile(pathName, null)

    @JvmStatic fun decodeFile(pathName: String, opts: Options?): Bitmap? =
        runCatching { File(pathName).readBytes() }.getOrNull()?.let { decode(it, opts) }

    @JvmStatic fun decodeStream(stream: InputStream?): Bitmap? = decodeStream(stream, null, null)

    @JvmStatic fun decodeStream(stream: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? =
        stream?.let { s -> runCatching { s.readBytes() }.getOrNull()?.let { decode(it, opts) } }

    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? =
        decodeByteArray(data, offset, length, null)

    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? =
        decode(data.copyOfRange(offset, offset + length), opts)

    private fun decode(bytes: ByteArray, opts: Options?): Bitmap? {
        if (opts?.inJustDecodeBounds == true) {
            val codec = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull() ?: return null
            opts.outWidth = codec.width
            opts.outHeight = codec.height
            return null
        }
        val full = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        val sample = (opts?.inSampleSize ?: 1).coerceAtLeast(1)
        opts?.apply { outWidth = full.width / sample; outHeight = full.height / sample }
        if (sample == 1) return Bitmap(full)
        val w = (full.width / sample).coerceAtLeast(1)
        val h = (full.height / sample).coerceAtLeast(1)
        val target = org.jetbrains.skia.Bitmap()
        if (!target.allocPixels(ImageInfo.makeN32Premul(w, h))) throw OutOfMemoryError("bitmap ${w}x$h")
        val pixmap = target.peekPixels() ?: return null
        if (!full.scalePixels(pixmap, SamplingMode.LINEAR, false)) return null
        target.setImmutable()
        return Bitmap(Image.makeFromBitmap(target))
    }
}

/** DASH-AA platform shim — a decoded image. Holds the Skia image Compose draws directly. */
class Bitmap internal constructor(val skiaImage: Image) {
    val width: Int get() = skiaImage.width
    val height: Int get() = skiaImage.height
}

/** DASH-AA platform shim — present only so `decodeStream(stream, null, opts)` resolves. */
class Rect
