package com.dash.android.aa.media

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * is a native crash, and a frame that is never freed is a leak of megabytes thirty times a second.
 * [tick] changes on every frame so the viewport redraws — without recomposing anything.
 */
class FrameStore {
    private val lock = Any()
    private var current: VideoFrame? = null
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun publish(frame: VideoFrame) {
        val old = synchronized(lock) { current.also { current = frame } }
        old?.close()
        _tick.value = _tick.value + 1
    }

    fun clear() {
        val old = synchronized(lock) { current.also { current = null } }
        old?.close()
        _tick.value = _tick.value + 1
    }

    fun <T> withFrame(block: (VideoFrame?) -> T): T = synchronized(lock) { block(current) }
}

/**
 * H.264 → frames, by handing the stream to `ffmpeg`.
 *
 * **Why a process rather than a library.** Every Java video binding either bundles a ~100 MB native
 * build or wraps GStreamer's object model; `ffmpeg` is already on the machine and already knows every
 * graphics chip's video engine.
 *
 * **As much as possible on the graphics chip** (1.2.4). Measured on the Surface Pro 3 (i3-4020Y) at
 * 1080p, the old path — decode on the processor, convert to BGRA, pipe 8 MB a frame — took about 270%
 * of its 400%, and only a sixth of that was the decoding. Now:
 * - the frame leaves ffmpeg as NV12, as the decoder made it — 3 MB at 1080p, not 8 — and the graphics
 *   chip turns it into colour as it draws ([VideoFrame]), so ffmpeg does no colour conversion at all;
 * - where the machine's graphics chip can decode H.264 (VA-API: [hardware]), it does, and ffmpeg only
 *   copies the finished frame out. Where it cannot, the processor decodes, as before.
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
    @Volatile private var colour = VideoColour.BT601_LIMITED

    /** Everything fed before the first frame, so a graphics chip that fails at the first packet can
     *  be replaced by the processor without waiting for the phone's next key frame. */
    private val opening = ArrayList<ByteArray>()
    @Volatile private var firstFrame = false

    init { require(width % 2 == 0 && height % 2 == 0) { "NV12 needs an even frame size: ${width}x$height" } }

    fun start() {
        running = true
        launch(hardware())
    }

    /** Queue one packet of H.264. Never blocks the session: if the decoder falls behind by [QUEUE]
     *  packets, the oldest is dropped (a brief smear, corrected at the next key frame). */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        val packet = data.copyOfRange(offset, offset + length)
        synchronized(opening) { if (!firstFrame && opening.size < OPENING_MAX) opening.add(packet) }
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
        synchronized(opening) { opening.clear() }
    }

    private fun launch(device: String?, replay: List<ByteArray> = emptyList()) {
        val ffmpeg = ffmpegPath() ?: run { Log.e(TAG, "ffmpeg not found — no video"); return }
        val p = try { ProcessBuilder(command(ffmpeg, device)).start() }
            catch (e: IOException) { Log.e(TAG, "ffmpeg would not start: ${e.message}"); return }
        process = p
        Thread({ writeLoop(p, replay) }, "dash-video-in").apply { isDaemon = true }.start()
        Thread({ readLoop(p, device) }, "dash-video-out").apply { isDaemon = true }.start()
        Thread({ errorLoop(p) }, "dash-video-log").apply { isDaemon = true }.start()
        Log.i(TAG, "decoding ${width}x$height on " + (device?.let { "the graphics chip ($it)" } ?: "the processor"))
    }

    private fun writeLoop(p: Process, replay: List<ByteArray>) {
        val out = p.outputStream
        try {
            for (packet in replay) out.write(packet)
            out.flush()
            while (running) {
                val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(packet)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    /** Each frame is two planes, read straight into the buffers Skia copies from — no other copy. */
    private fun readLoop(p: Process, device: String?) {
        val luma = ByteArray(width * height)
        val chroma = ByteArray(width * height / 2)
        val input = DataInputStream(p.inputStream)
        var count = 0L
        try {
            while (running) {
                input.readFully(luma)
                input.readFully(chroma)
                frames.publish(VideoFrame.make(width, height, luma, chroma, colour))
                if (++count == 1L) {
                    synchronized(opening) { firstFrame = true; opening.clear() }
                    Log.i(TAG, "first frame")
                }
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, "decoder ended after $count frames")
        if (count == 0L && device != null && running && process === p) {
            // The probe said yes, the real stream said no: the processor takes over, from the start.
            Log.w(TAG, "the graphics chip ($device) could not decode this stream — the processor takes over")
            hardwareFailed = true
            val replay = synchronized(opening) { queue.clear(); opening.toList() }
            launch(null, replay)
        }
    }

    /** ffmpeg's own messages: its problems into DASH's log, and the stream's colour from its header. */
    private fun errorLoop(p: Process) {
        try {
            p.errorStream.bufferedReader().forEachLine { line ->
                when {
                    line.contains("not enough frames to estimate rate") -> Unit   // every live stream; harmless
                    line.contains("Stream #0:0: Video: h264") -> {
                        colour = VideoColour.fromStreamLine(line)
                        Log.i(TAG, "stream: ${line.substringAfter("Video: ").trim()} — colour $colour")
                    }
                    line.startsWith("[error]") || line.startsWith("[fatal]") || line.startsWith("[warning]") ->
                        Log.w(TAG, "ffmpeg: ${line.substringAfter("] ")}")
                    line.contains("] [error]") || line.contains("] [fatal]") || line.contains("] [warning]") ->
                        Log.w(TAG, "ffmpeg: ${line.substringAfterLast("] ")}")
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "DashAaVideo"
        private const val QUEUE = 240
        private const val OPENING_MAX = 300
        private const val PROBE_CLIP = "/aa/probe.h264"
        // The clip: 128 × 128, three frames, labelled plain Baseline as phones label theirs.
        private const val PROBE_SIZE = 128
        private const val PROBE_FRAMES = 3

        fun ffmpegPath(): String? =
            (System.getenv("PATH") ?: "/usr/bin").split(':')
                .map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }?.absolutePath

        fun command(ffmpeg: String, device: String?): List<String> = buildList {
            addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "level+info", "-nostats", "-nostdin"))
            // No `-fflags nobuffer`: measured 2026-10-05 on ffmpeg 9.0, it makes the raw-H.264 reader
            // discard every frame. These four alone gave the first frame 0.1 s after the stream began,
            // with the decoder holding back about two frames (~66 ms at 30 fps) — its own lookahead.
            addAll(listOf("-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0", "-thread_type", "slice"))
            // The frames stay on the graphics chip until hwdownload, so a chip that cannot decode is an
            // error rather than ffmpeg quietly decoding on the processor instead.
            // allow_profile_mismatch: phones label their stream plain *Baseline* (the Pixel 8 Pro does),
            // which VA-API no longer lists, and ffmpeg refuses it without this. What they send decodes
            // as Constrained Baseline, which every chip does — found on the Surface, 1.2.4a.
            if (device != null) addAll(listOf("-hwaccel", "vaapi", "-hwaccel_device", device, "-hwaccel_output_format", "vaapi",
                "-hwaccel_flags", "allow_profile_mismatch"))
            addAll(listOf("-f", "h264", "-i", "pipe:0", "-an"))
            if (device != null) addAll(listOf("-vf", "hwdownload,format=nv12"))
            addAll(listOf("-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "nv12", "pipe:1"))
        }

        // ---- The graphics chip's video engine ----

        @Volatile private var probed: String? = null
        @Volatile private var probeDone = false
        @Volatile private var hardwareFailed = false
        private val probeLock = Any()

        /**
         * The render node whose video engine decodes H.264, or null to decode on the processor.
         * Capability detection: each node is asked to decode a three-frame clip, and the first to
         * return every frame wins. The chip driving the screen (`boot_vga`) is asked first — on a
         * laptop with two, the other is usually the power-hungry one. Asked once; it can take a
         * couple of seconds (the probe wakes the chip), so [AndroidAutoHost] asks early, off the UI.
         *
         * `DASH_AA_VIDEO=processor` skips the graphics chip — for comparing the two.
         */
        fun hardware(): String? {
            if (hardwareFailed) return null
            synchronized(probeLock) {
                if (!probeDone) { probed = probe(); probeDone = true }
                return probed
            }
        }

        /** What [hardware] found, without waiting for it: null until it has been asked. */
        fun hardwareSummary(): String? = when {
            !probeDone -> null
            hardwareFailed -> "the processor (the graphics chip ${probed} failed on the phone's stream)"
            probed != null -> "the graphics chip ($probed)"
            else -> "the processor — no video driver for this graphics chip"
        }

        private fun probe(): String? {
            if (System.getenv("DASH_AA_VIDEO") == "processor") { Log.i(TAG, "DASH_AA_VIDEO=processor: no graphics chip"); return null }
            val ffmpeg = ffmpegPath() ?: return null
            val clip = VideoDecoder::class.java.getResourceAsStream(PROBE_CLIP)?.use { it.readBytes() } ?: return null
            val nodes = File("/sys/class/drm").listFiles { f -> f.name.startsWith("renderD") }.orEmpty()
                .sortedWith(compareByDescending<File> { File(it, "device/boot_vga").readTextOrNull()?.trim() == "1" }.thenBy { it.name })
                .map { "/dev/dri/${it.name}" }
            for (node in nodes) {
                if (!File(node).canRead()) continue
                val ok = runCatching { decodes(ffmpeg, node, clip) }.getOrDefault(false)
                Log.i(TAG, "graphics chip $node: " + if (ok) "decodes H.264" else "cannot decode H.264")
                if (ok) return node
            }
            return null
        }

        private fun decodes(ffmpeg: String, node: String, clip: ByteArray): Boolean {
            val p = ProcessBuilder(command(ffmpeg, node).map { if (it == "level+info") "fatal" else it })
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            Thread { runCatching { p.outputStream.use { it.write(clip) } } }.apply { isDaemon = true }.start()
            var bytes = 0L
            val reader = Thread { bytes = runCatching { p.inputStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }.getOrDefault(0L) }
                .apply { isDaemon = true; start() }
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return false }
            reader.join(1000)
            return p.exitValue() == 0 && bytes == PROBE_SIZE.toLong() * PROBE_SIZE * 3 / 2 * PROBE_FRAMES
        }

        private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
    }
}
