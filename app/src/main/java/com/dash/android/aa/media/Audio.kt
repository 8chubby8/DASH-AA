package com.dash.android.aa.media

import android.util.Log
import com.dash.android.aa.protocol.Aa
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Android Auto's three audio streams — media (48 kHz stereo), directions and the assistant
 * (16 kHz mono), and system sounds (16 kHz mono) — played through the laptop's sound system.
 *
 * **Straight to PipeWire, with `pw-cat`.** 1.0.0–1.0.4 used Java's sound API, which opens ALSA and, to
 * find a device, *probes every sound device on the machine* — the Nvidia card's HDMI audio included.
 * On 2026-10-05 that probe hung for 30 seconds (WirePlumber had wedged after a Bluetooth call), and
 * because the line was opened on the thread that listens to the phone, Android Auto stalled on its
 * splash screen. Now each stream is one `pw-cat` process fed raw PCM: no probing, a proper PipeWire
 * stream with a role (Music / Navigation / Notification) the desktop can route and show, and Java's
 * sound API kept only as a fallback where `pw-cat` is absent.
 *
 * **Nothing here can stall the phone.** A stream's output is opened on that stream's own thread; the
 * session only ever queues. If the output never opens, or the sound system stops taking audio, the
 * queue overflows by dropping its oldest chunk — *and acknowledging it* — so the phone keeps going in
 * silence instead of freezing, and the log says why.
 *
 * **What DASH-AA does to the sound** is unchanged: its own volume (the steering-wheel `media_volume_*`),
 * mute (`media_muted`), and lowering music under spoken directions — the head unit is the car's mixer.
 * From 1.1.2 the Mixer also sets each sound's own level beneath that volume.
 */
class AudioOut internal constructor(
    /** Where sound goes — PipeWire normally; a test can hand in an output that misbehaves. */
    private val open: (rate: Int, channels: Int, role: String, name: String) -> PcmOut?,
) {
    constructor() : this(::openOutput)

    @Volatile var volume: Float = 1.0f
    @Volatile var muted: Boolean = false
    @Volatile var duckMedia: Boolean = true
    /** Audio › Volumes: the level of each of the three sounds, under [volume]. */
    @Volatile var musicLevel: Float = 1.0f
    @Volatile var directionsLevel: Float = 1.0f
    @Volatile var systemLevel: Float = 1.0f
    /** Called when a stream finds the sound system no longer taking audio. */
    @Volatile var onStuck: (() -> Unit)? = null

    private val streams = HashMap<Int, Stream>()
    @Volatile private var speechActive = false

    @Synchronized
    fun start(channel: Int) {
        if (streams.containsKey(channel)) return
        streams[channel] = Stream(channel)
        if (channel == Aa.CH_SPEECH_AUDIO) speechActive = true
    }

    /**
     * Queue audio for [channel]; [onPlayed] runs once it has gone to the sound system (or been dropped).
     * Never blocks. Returns false if the channel was never started — the caller acknowledges at once.
     */
    fun write(channel: Int, data: ByteArray, offset: Int, length: Int, onPlayed: () -> Unit): Boolean {
        val stream = synchronized(this) { streams[channel] } ?: return false
        stream.enqueue(data.copyOfRange(offset, offset + length), onPlayed)
        return true
    }

    @Synchronized
    fun stop(channel: Int) {
        streams.remove(channel)?.close()
        if (channel == Aa.CH_SPEECH_AUDIO) speechActive = false
    }

    @Synchronized
    fun stopAll() {
        streams.values.forEach { it.close() }
        streams.clear()
        speechActive = false
    }

    private fun gainFor(channel: Int): Float {
        if (muted) return 0f
        val duck = if (duckMedia && channel == Aa.CH_MEDIA_AUDIO && speechActive) DUCK_GAIN else 1f
        val level = when (channel) {
            Aa.CH_MEDIA_AUDIO -> musicLevel
            Aa.CH_SPEECH_AUDIO -> directionsLevel
            else -> systemLevel
        }
        return volume * level * duck
    }

    /** One chunk of sound and its acknowledgement, which happens exactly once whoever gets there first. */
    private class Pending(val pcm: ByteArray, private val onPlayed: () -> Unit) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun ack() { if (done.compareAndSet(false, true)) onPlayed() }
    }

    private inner class Stream(val channel: Int) {
        private val rate = if (channel == Aa.CH_MEDIA_AUDIO) 48000 else 16000
        private val channels = if (channel == Aa.CH_MEDIA_AUDIO) 2 else 1
        private val queue = ArrayBlockingQueue<Pending>(64)
        @Volatile private var running = true

        /** The chunk being written now, and since when — what the watchdog checks. */
        @Volatile private var writing: Pending? = null
        @Volatile private var writingSince = 0L
        /** The sound system stopped taking audio: acknowledge on arrival and drop, until it moves again. */
        @Volatile private var stalled = false

        init {
            Thread({ play() }, "dash-aa-audio-${Aa.channelName(channel)}").apply { isDaemon = true }.start()
            Thread({ watch() }, "dash-aa-audio-watch-${Aa.channelName(channel)}").apply { isDaemon = true }.start()
        }

        fun enqueue(pcm: ByteArray, onPlayed: () -> Unit) {
            val item = Pending(pcm, onPlayed)
            if (stalled) { item.ack(); return }
            while (!queue.offer(item)) queue.poll()?.ack()
        }

        /**
         * The phone sends the next chunk only once this one is acknowledged, so a sound system that stops
         * taking audio would otherwise stop the phone too — it waits, then drops the connection (seen
         * 2026-10-05 when WirePlumber wedged). A write held for longer than [STALL_MS] is acknowledged
         * anyway, and everything after it, so Android Auto carries on in silence until sound returns.
         */
        private fun watch() {
            while (running) {
                Thread.sleep(100)
                val since = writingSince
                if (since != 0L && System.currentTimeMillis() - since > STALL_MS && !stalled) {
                    stalled = true
                    writing?.ack()
                    while (true) { (queue.poll() ?: break).ack() }
                    onStuck?.invoke()
                    Log.w(TAG, "${Aa.channelName(channel)}: the sound system is not taking audio — carrying on in silence " +
                        "(Android Auto › Connection › Restart sound, or: systemctl --user restart wireplumber)")
                }
            }
        }

        private fun play() {
            val out = open(rate, channels, role(channel), "dash-aa-${Aa.channelName(channel)}")
            if (out == null) {
                Log.w(TAG, "no audio output for ${Aa.channelName(channel)} — acknowledging in silence")
                stalled = true
                while (true) { (queue.poll() ?: break).ack() }
                return
            }
            try {
                while (running) {
                    val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    applyGain(item.pcm, gainFor(channel))
                    writing = item
                    writingSince = System.currentTimeMillis()
                    out.write(item.pcm)          // blocks while the sound system's buffer is full
                    writingSince = 0L
                    writing = null
                    if (stalled) { stalled = false; Log.i(TAG, "${Aa.channelName(channel)}: sound is flowing again") }
                    item.ack()
                }
            } catch (e: Exception) {
                Log.w(TAG, "${Aa.channelName(channel)} output ended: ${e.message}")
                stalled = true
                writing?.ack()
                while (true) { (queue.poll() ?: break).ack() }
            } finally {
                writingSince = 0L
                out.close()
            }
        }

        fun close() {
            running = false
            writing?.ack()
            while (true) { (queue.poll() ?: break).ack() }
        }
    }

    companion object {
        private const val TAG = "DashAaAudio"
        private const val DUCK_GAIN = 0.3f
        /** A write held this long means the sound system has stopped taking audio. A full device buffer
         *  holds well under this, so ordinary back-pressure never trips it. */
        private const val STALL_MS = 400L

        private fun role(channel: Int) = when (channel) {
            Aa.CH_MEDIA_AUDIO -> "Music"
            Aa.CH_SPEECH_AUDIO -> "Navigation"
            else -> "Notification"
        }

        /** Scale 16-bit little-endian samples in place. */
        fun applyGain(pcm: ByteArray, gain: Float) {
            if (gain >= 0.999f) return
            var i = 0
            while (i + 1 < pcm.size) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                val v = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[i] = v.toByte()
                pcm[i + 1] = (v shr 8).toByte()
                i += 2
            }
        }

        /** Whether DASH-AA can play sound at all. Answered without touching a device. */
        fun outputAvailable(): Boolean = PwCat.path != null || javaSoundPresent()

        private fun javaSoundPresent() = runCatching { AudioSystem.getMixerInfo().isNotEmpty() }.getOrDefault(false)

        /** An output for raw 16-bit PCM: `pw-cat` if present, Java sound otherwise; null if neither opens. */
        internal fun openOutput(rate: Int, channels: Int, role: String, name: String): PcmOut? =
            PwCat.playback(rate, channels, role, name) ?: runCatching {
                val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                val bytes = (format.frameRate * format.frameSize * 0.12f).toInt().let { it - it % format.frameSize }
                line.open(format, bytes); line.start()
                object : PcmOut {
                    override fun write(pcm: ByteArray) { line.write(pcm, 0, pcm.size - pcm.size % 2) }
                    override fun close() { runCatching { line.stop(); line.close() } }
                }
            }.onFailure { Log.w(TAG, "Java sound could not open $name: ${it.message}") }.getOrNull()
    }
}

internal interface PcmOut {
    fun write(pcm: ByteArray)
    fun close()
}

/** PipeWire's own `pw-cat`, for raw PCM in and out. */
internal object PwCat {
    val path: String? by lazy {
        (System.getenv("PATH") ?: "/usr/bin").split(':').map { File(it, "pw-cat") }.firstOrNull { it.canExecute() }?.absolutePath
    }

    /**
     * Each stream carries **its own application identity** (DASH-AA 1.0.9). WirePlumber remembers a
     * stream's volume per application and restores it to the next stream from the same one — and plain
     * `pw-cat` streams all share the identity "pw-cat". On 2026-10-05 a 250% boost set on a *test*
     * stream was remembered for "pw-cat" and came back on DASH-AA's Spotify stream. With its own id per
     * stream, DASH-AA's music, directions, system sounds and microphone keep their own volumes, and
     * nothing else on the desktop can leak into them.
     */
    private fun args(rate: Int, channels: Int, role: String, name: String) = listOf(
        "--raw", "--format", "s16", "--rate", "$rate", "--channels", "$channels", "--media-role", role,
        "-P", "node.name=$name node.description=DASH-AA_${name.removePrefix("dash-aa-")} " +
            "application.name=DASH-AA application.id=dash-aa.${name.removePrefix("dash-aa-")}", "-",
    )

    fun playback(rate: Int, channels: Int, role: String, name: String): PcmOut? {
        val exe = path ?: return null
        val p = runCatching {
            ProcessBuilder(listOf(exe, "--playback") + args(rate, channels, role, name))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        val pipe: OutputStream = p.outputStream
        return object : PcmOut {
            override fun write(pcm: ByteArray) { pipe.write(pcm, 0, pcm.size - pcm.size % 2); pipe.flush() }
            override fun close() { runCatching { pipe.close() }; if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroy() }
        }
    }

    fun record(rate: Int, channels: Int, role: String, name: String): Pair<Process, InputStream>? {
        val exe = path ?: return null
        val p = runCatching {
            ProcessBuilder(listOf(exe, "--record") + args(rate, channels, role, name))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        return p to p.inputStream
    }
}

/**
 * The laptop's microphone, for the assistant and calls: 16 kHz mono, 16-bit — what Android Auto asks
 * for. It records only while the phone has the microphone open; at every other moment DASH-AA is not
 * listening, and that is a design rule, not a setting. Opened off the session thread, like the outputs.
 */
class Microphone(private val onPcm: (ByteArray, Int) -> Unit) {
    @Volatile private var generation = 0
    @Volatile private var process: Process? = null

    @Synchronized
    fun open() {
        if (process != null) return
        val gen = ++generation
        Thread({
            val rec = PwCat.record(16000, 1, "Communication", "dash-aa-mic")
            if (rec == null) { Log.w(TAG, "no microphone (pw-cat missing)"); return@Thread }
            val (p, input) = rec
            synchronized(this) { if (gen != generation) { p.destroy(); return@Thread }; process = p }
            Log.i(TAG, "microphone open")
            val buf = ByteArray(CHUNK)
            try {
                while (gen == generation) {
                    var n = 0
                    while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                    if (n <= 0) break
                    onPcm(buf, n)
                }
            } catch (_: Exception) {
            }
        }, "dash-aa-mic").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun close() {
        generation++
        val p = process ?: return
        process = null
        p.destroy()
        Log.i(TAG, "microphone closed")
    }

    companion object {
        private const val TAG = "DashAaMic"
        /** 20 ms at 16 kHz mono 16-bit. */
        private const val CHUNK = 640

        /** Whether a microphone can be offered — answered without opening a device. */
        fun inputAvailable(): Boolean = PwCat.path != null
    }
}
