package com.dash.android.aa.media

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The latest decoded Android Auto frame, shared between the decoder thread and the UI.
 *
 * The viewport draws [withFrame] inside its draw pass; the decoder [publish]es under the same lock and
 * frees the frame it replaced. That lock is the whole point: a Skia image freed while it is being drawn
 * is a native crash, and a frame that is never freed is a leak of several megabytes thirty times a
 * second. [tick] changes on every frame so the viewport redraws — without recomposing anything.
 */
class FrameStore {
    private val lock = Any()
    private var current: Image? = null
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun publish(image: Image) {
        val old = synchronized(lock) { current.also { current = image } }
        old?.close()
        _tick.value = _tick.value + 1
    }

    fun clear() {
        val old = synchronized(lock) { current.also { current = null } }
        old?.close()
        _tick.value = _tick.value + 1
    }

    fun <T> withFrame(block: (Image?) -> T): T = synchronized(lock) { block(current) }
}

/**
 * H.264 → frames, by handing the stream to `ffmpeg`.
 *
 * **Why a process rather than a library.** Every Java video binding either bundles a ~100 MB native
 * build or wraps GStreamer's object model; `ffmpeg` is already on the machine, already uses the CPU's
 * fastest paths, and the pipe between them is a few megabytes a second. Decoding Android Auto's 1080p
 * stream costs a few percent of one core on this laptop.
 *
 * The flags are all about latency — a head unit is interactive, so a frame is shown the moment it is
 * decoded: no input probing (by default ffmpeg reads 5 MB or 5 s of a pipe before it starts, which on
 * a live stream means *nothing appears at all*), low-delay decoding, slice threading only (frame
 * threading holds frames back), and timestamps passed straight through rather than resampled.
 */
class VideoDecoder(
    private val width: Int,
    private val height: Int,
    private val frames: FrameStore,
) {
    private var process: Process? = null
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
    @Volatile private var running = false

    fun start() {
        val ffmpeg = ffmpegPath() ?: run { Log.e(TAG, "ffmpeg not found — no video"); return }
        val cmd = listOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            // No `-fflags nobuffer`: measured 2026-10-05 on ffmpeg 9.0, it makes the raw-H.264 reader
            // discard every frame. These four alone gave the first frame 0.1 s after the stream began,
            // with the decoder holding back about two frames (~66 ms at 30 fps) — its own lookahead.
            "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
            "-thread_type", "slice",
            "-f", "h264", "-i", "pipe:0",
            "-an", "-fps_mode", "passthrough",
            "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1",
        )
        val p = try { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start() }
            catch (e: IOException) { Log.e(TAG, "ffmpeg would not start: ${e.message}"); return }
        process = p
        running = true
        Thread({ writeLoop(p) }, "dash-aa-video-in").apply { isDaemon = true }.start()
        Thread({ readLoop(p) }, "dash-aa-video-out").apply { isDaemon = true }.start()
        Log.i(TAG, "decoding ${width}x$height")
    }

    /** Queue one packet of H.264. Never blocks the session: if the decoder falls behind by [QUEUE]
     *  packets, the oldest is dropped (a brief smear, corrected at the next key frame). */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        val packet = data.copyOfRange(offset, offset + length)
        if (!queue.offer(packet)) { queue.poll(); queue.offer(packet) }
    }

    fun stop() {
        running = false
        process?.let { p ->
            runCatching { p.outputStream.close() }
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
        queue.clear()
    }

    private fun writeLoop(p: Process) {
        val out = p.outputStream
        try {
            while (running) {
                val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(packet)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLoop(p: Process) {
        val frameBytes = width * height * 4
        val buffer = ByteArray(frameBytes)
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val input = DataInputStream(p.inputStream.buffered(frameBytes))
        var count = 0L
        try {
            while (running) {
                input.readFully(buffer)
                frames.publish(Image.makeRaster(info, buffer, width * 4))
                if (++count == 1L) Log.i(TAG, "first frame")
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, "decoder ended after $count frames")
    }

    companion object {
        private const val TAG = "DashAaVideo"
        private const val QUEUE = 240

        fun ffmpegPath(): String? =
            (System.getenv("PATH") ?: "/usr/bin").split(':')
                .map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }?.absolutePath
    }
}
