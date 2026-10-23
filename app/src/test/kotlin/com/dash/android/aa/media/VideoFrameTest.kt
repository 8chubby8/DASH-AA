package com.dash.android.aa.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The graphics chip's colour conversion (1.2.4), run here by Skia on the processor: same shader. */
class VideoFrameTest {

    /** Four flat 8×4 blocks in BT.601 limited range — what an encoder makes of pure colours. */
    @Test fun `NV12 planes become the right colours`() {
        // Y, U, V for white, red, green, blue (BT.601, limited).
        val blocks = listOf(
            intArrayOf(235, 128, 128) to intArrayOf(255, 255, 255),
            intArrayOf(81, 90, 240) to intArrayOf(255, 0, 0),
            intArrayOf(145, 54, 34) to intArrayOf(0, 255, 0),
            intArrayOf(41, 240, 110) to intArrayOf(0, 0, 255),
        )
        val w = 32; val h = 4
        val luma = ByteArray(w * h)
        val chroma = ByteArray(w * h / 2)
        blocks.forEachIndexed { i, (yuv, _) ->
            for (row in 0 until h) for (col in 0 until 8) luma[row * w + i * 8 + col] = yuv[0].toByte()
            for (row in 0 until h / 2) for (pair in 0 until 4) {
                chroma[row * w + (i * 4 + pair) * 2] = yuv[1].toByte(); chroma[row * w + (i * 4 + pair) * 2 + 1] = yuv[2].toByte()
            }
        }
        val pixels = draw(VideoFrame.make(w, h, luma, chroma, VideoColour.BT601_LIMITED), w, h)
        blocks.forEachIndexed { i, (_, rgb) ->
            // The middle of each block: the blending between pairs happens only at block edges.
            val argb = pixels.getColor(i * 8 + 4, 1)
            val got = intArrayOf((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff)
            for (c in 0..2) assertTrue(abs(got[c] - rgb[c]) <= 6, "block $i: got ${got.toList()}, wanted ${rgb.toList()}")
        }
    }

    @Test fun `the frame is scaled into the viewport`() {
        val luma = ByteArray(4 * 4) { 235.toByte() }
        val chroma = ByteArray(4 * 4 / 2) { 128.toByte() }
        val pixels = draw(VideoFrame.make(4, 4, luma, chroma, VideoColour.BT601_LIMITED), 16, 16, Rect.makeWH(4f, 4f))
        for ((x, y) in listOf(0 to 0, 15 to 15, 7 to 9)) {
            val argb = pixels.getColor(x, y)
            for (shift in listOf(0, 8, 16)) assertTrue(((argb shr shift) and 0xff) >= 252, "($x, $y): ${Integer.toHexString(argb)}")
        }
    }

    @Test fun `colour is read from ffmpeg's stream line`() {
        val line = { tags: String -> "[info]   Stream #0:0: Video: h264 (High), $tags, 1920x1080 [SAR 1:1 DAR 16:9], 30 fps" }
        assertEquals(VideoColour.BT601_LIMITED, VideoColour.fromStreamLine(line("yuv420p(progressive)")))
        assertEquals(VideoColour.BT709_LIMITED, VideoColour.fromStreamLine(line("yuv420p(tv, bt709, progressive)")))
        assertEquals(VideoColour.BT601_LIMITED, VideoColour.fromStreamLine(line("yuv420p(tv, smpte170m/bt709/bt709, progressive)")))
        assertEquals(VideoColour.BT709_FULL, VideoColour.fromStreamLine(line("yuvj420p(pc, bt709, progressive)")))
        assertEquals(VideoColour.BT601_FULL, VideoColour.fromStreamLine(line("yuvj420p(pc, progressive)")))
    }

    private fun draw(frame: VideoFrame, w: Int, h: Int, src: Rect = Rect.makeWH(w.toFloat(), h.toFloat())): Bitmap {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL)) }
        frame.use { Canvas(bitmap).use { c -> it.draw(c, src, Rect.makeWH(w.toFloat(), h.toFloat())) } }
        return bitmap
    }
}
