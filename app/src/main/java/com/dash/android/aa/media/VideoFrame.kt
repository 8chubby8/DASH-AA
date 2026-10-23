package com.dash.android.aa.media

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * One Android Auto frame as the decoder made it — NV12: a full-size brightness plane and a quarter-size
 * plane of the two colour differences — turned into colour **by the graphics chip, as it is drawn**.
 *
 * The planes go up to the graphics chip as two small images (one byte a pixel, and one byte per colour
 * value: 3 MB at 1080p against BGRA's 8 MB), and [COLOUR] — a few lines of shader — does per pixel what ffmpeg's
 * converter did on the processor, along with the scaling the viewport always did there.
 */
class VideoFrame private constructor(
    val width: Int,
    val height: Int,
    private val luma: Image,
    private val chroma: Image,
    private val colour: VideoColour,
) : AutoCloseable {
    private val lumaShader: Shader = luma.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
    private val chromaShader: Shader = chroma.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT, null)

    /** Draw the [src] part of the frame (in frame pixels) into [dst]. */
    fun draw(canvas: Canvas, src: Rect, dst: Rect) {
        val sx = dst.width / src.width
        val sy = dst.height / src.height
        // Frame pixels → the viewport: the shader is evaluated in frame pixels, wherever it is drawn.
        val toDst = Matrix33(sx, 0f, dst.left - src.left * sx, 0f, sy, dst.top - src.top * sy, 0f, 0f, 1f)
        val builder = RuntimeShaderBuilder(effect)
        builder.child("luma", lumaShader)
        builder.child("chroma", chromaShader)
        builder.uniform("range", colour.lumaScale, colour.lumaOffset)
        builder.uniform("k", colour.rv, colour.gu, colour.gv, colour.bu)
        builder.uniform("pairs", (width / 2).toFloat(), (height / 2).toFloat())
        val shader = builder.makeShader(toDst)
        Paint().use { paint ->
            paint.shader = shader
            canvas.drawRect(dst, paint)
        }
        shader.close()
        builder.close()
    }

    override fun close() {
        lumaShader.close()
        chromaShader.close()
        luma.close()
        chroma.close()
    }

    companion object {
        /**
         * NV12 → RGB. `range` undoes limited ("TV") range — brightness 16–235 → 0–1 — and `k` holds the
         * colour matrix's four non-trivial terms; chroma is centred on ½.
         *
         * The chroma plane is half the size each way, its two values side by side (U V U V…), and goes
         * up as a one-byte image twice as wide as it has pairs: skiko 0.9.22's two-byte `R8G8_UNORM`
         * reaches Skia as a different colour type (its numbering is one behind Skia's) and is refused.
         * So the shader reads each pair itself, unfiltered, and blends the four nearest pairs — the
         * filtering the graphics chip would otherwise have done.
         */
        private const val COLOUR = """
            uniform shader luma;
            uniform shader chroma;
            uniform float2 range;
            uniform float4 k;
            uniform float2 pairs;
            float2 pairAt(float2 i) {
                i = clamp(i, float2(0), pairs - 1);
                float x = i.x * 2;
                return float2(chroma.eval(float2(x + 0.5, i.y + 0.5)).r, chroma.eval(float2(x + 1.5, i.y + 0.5)).r);
            }
            half4 main(float2 p) {
                float y = (luma.eval(p).r - range.y) * range.x;
                float2 at = p * 0.5 - 0.5;
                float2 i = floor(at);
                float2 f = at - i;
                float2 c = mix(mix(pairAt(i), pairAt(i + float2(1, 0)), f.x),
                               mix(pairAt(i + float2(0, 1)), pairAt(i + float2(1, 1)), f.x), f.y) - 0.5;
                float3 rgb = float3(y + k.x * c.y, y - k.y * c.x - k.z * c.y, y + k.w * c.x);
                return half4(half3(saturate(rgb)), 1.0);
            }
        """

        private val effect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(COLOUR) }

        /** Wrap one frame's planes; Skia copies them, so the caller may reuse its buffers at once. */
        fun make(width: Int, height: Int, luma: ByteArray, chroma: ByteArray, colour: VideoColour): VideoFrame {
            val y = Image.makeRaster(ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE), luma, width)
            val uv = Image.makeRaster(ImageInfo(width, height / 2, ColorType.GRAY_8, ColorAlphaType.OPAQUE), chroma, width)
            return VideoFrame(width, height, y, uv, colour)
        }
    }
}

/**
 * How a stream's brightness and colour differences become RGB — read from ffmpeg's description of the
 * stream, so a phone that says BT.709 or full range is shown as it means. Untagged, as ffmpeg's own
 * converter assumed: BT.601, limited range.
 */
data class VideoColour(
    val name: String,
    val lumaScale: Float, val lumaOffset: Float,
    val rv: Float, val gu: Float, val gv: Float, val bu: Float,
) {
    override fun toString() = name

    companion object {
        private const val LIMITED_SCALE = 255f / 219f
        private const val LIMITED_OFFSET = 16f / 255f
        // Limited range also narrows the colour differences (16–240): 255/224 widens them back.
        private const val C = 255f / 224f

        val BT601_LIMITED = VideoColour("BT.601, limited range", LIMITED_SCALE, LIMITED_OFFSET, 1.402f * C, 0.344136f * C, 0.714136f * C, 1.772f * C)
        val BT709_LIMITED = VideoColour("BT.709, limited range", LIMITED_SCALE, LIMITED_OFFSET, 1.5748f * C, 0.187324f * C, 0.468124f * C, 1.8556f * C)
        val BT601_FULL = VideoColour("BT.601, full range", 1f, 0f, 1.402f, 0.344136f, 0.714136f, 1.772f)
        val BT709_FULL = VideoColour("BT.709, full range", 1f, 0f, 1.5748f, 0.187324f, 0.468124f, 1.8556f)

        /** From ffmpeg's `Stream #0:0: Video: h264 (High), yuv420p(tv, bt709, progressive), …` line. */
        fun fromStreamLine(line: String): VideoColour {
            // "tv" or "pc", then the matrix — alone, or first of matrix/primaries/transfer.
            val tags = Regex("""yuv[a-z0-9]*\(([^)]*)\)""").find(line)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().substringBefore('/') }.orEmpty()
            val full = "pc" in tags
            val bt709 = "bt709" in tags
            return when {
                bt709 && full -> BT709_FULL
                bt709 -> BT709_LIMITED
                full -> BT601_FULL
                else -> BT601_LIMITED
            }
        }
    }
}
