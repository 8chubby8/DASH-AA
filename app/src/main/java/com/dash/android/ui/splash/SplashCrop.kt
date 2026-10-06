package com.dash.android.ui.splash

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A splash image crop (roadmap 1.5.6, Phase 2), the "Model B" transform: a fixed frame the shape of
 * the screen, with the image positioned and scaled behind it. Resolution-independent so the same crop
 * looks identical in the small settings preview and on the full boot screen.
 *
 * - [zoom] is **relative to the cover fit** — the smallest scale that still fills the frame. `1.0` is
 *   the standard centred crop (the detent). Below 1 the image no longer covers, and the background
 *   colour shows through as a matte (letterbox / pillarbox). The floor is the *contain* fit, where the
 *   whole image is visible on its tight axis — computed per image + frame, see [containZoom].
 * - [panX] / [panY] are the pan within the overflow, normalised to −1..1 (0 = centred). When an axis
 *   is letterboxed (no overflow) its pan is pinned to centre.
 *
 * Two are stored — one for portrait, one for landscape — because a tall slice and a wide slice of the
 * same picture can't come from one rectangle.
 */
data class SplashCrop(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun encode(): String = "$zoom,$panX,$panY"
}

val SPLASH_CROP_DEFAULT = SplashCrop()

const val SPLASH_ZOOM_MAX = 5f

// Within this band of the standard crop the zoom snaps to exactly 1.0 — the "click into place".
const val SPLASH_DETENT_EPS = 0.02f

fun decodeSplashCrop(encoded: String?): SplashCrop {
    if (encoded.isNullOrBlank()) return SPLASH_CROP_DEFAULT
    val parts = encoded.split(",")
    if (parts.size != 3) return SPLASH_CROP_DEFAULT
    val z = parts[0].toFloatOrNull() ?: return SPLASH_CROP_DEFAULT
    val x = parts[1].toFloatOrNull() ?: return SPLASH_CROP_DEFAULT
    val y = parts[2].toFloatOrNull() ?: return SPLASH_CROP_DEFAULT
    return SplashCrop(z, x, y)
}

/** Where the image lands within the frame, in frame pixels. Parts outside 0..frame are cropped. */
data class Placement(val left: Float, val top: Float, val width: Float, val height: Float)

fun placeImage(crop: SplashCrop, fw: Float, fh: Float, iw: Float, ih: Float): Placement {
    if (iw <= 0f || ih <= 0f || fw <= 0f || fh <= 0f) return Placement(0f, 0f, fw, fh)
    val cover = max(fw / iw, fh / ih)
    val scale = crop.zoom * cover
    val dw = iw * scale
    val dh = ih * scale
    val maxPanX = max(0f, (dw - fw) / 2f)
    val maxPanY = max(0f, (dh - fh) / 2f)
    val left = (fw - dw) / 2f + crop.panX.coerceIn(-1f, 1f) * maxPanX
    val top = (fh - dh) / 2f + crop.panY.coerceIn(-1f, 1f) * maxPanY
    return Placement(left, top, dw, dh)
}

/** The zoom (relative to cover) at which the whole image is just visible on its tight axis. ≤ 1. */
fun containZoom(fw: Float, fh: Float, iw: Float, ih: Float): Float {
    if (iw <= 0f || ih <= 0f || fw <= 0f || fh <= 0f) return 1f
    val cover = max(fw / iw, fh / ih)
    val contain = min(fw / iw, fh / ih)
    return contain / cover
}

/**
 * Draws [bitmap] into the frame under [cropFor]. The crop is chosen per frame orientation — the boot
 * splash asks for the landscape or portrait crop by the real screen shape at that moment; the preview
 * and editor hand back a fixed one. The background colour behind shows through any matte.
 */
@Composable
fun CroppedImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    cropFor: (landscape: Boolean) -> SplashCrop,
) {
    Canvas(modifier.clipToBounds()) {
        val landscape = size.width >= size.height
        val crop = cropFor(landscape)
        val p = placeImage(crop, size.width, size.height, bitmap.width.toFloat(), bitmap.height.toFloat())
        val w = p.width.roundToInt()
        val h = p.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(p.left.roundToInt(), p.top.roundToInt()),
            dstSize = IntSize(w, h),
        )
    }
}

/** Decode a still image from a content URI, downsampled so a large photo can't blow up memory. */
fun loadSplashBitmap(context: Context, uriString: String, maxDim: Int = 2048): ImageBitmap? {
    return runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val biggest = max(bounds.outWidth, bounds.outHeight)
        while (biggest / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
        }
    }.getOrNull()
}

/** Loads a splash still off the main thread; null until ready (or when there is no image). */
@Composable
fun rememberSplashBitmap(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, uriString) {
        value = if (uriString.isEmpty()) null
        else withContext(Dispatchers.IO) { loadSplashBitmap(context, uriString) }
    }.value
}
