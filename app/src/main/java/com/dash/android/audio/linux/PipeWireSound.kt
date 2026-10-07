package com.dash.android.audio.linux

import android.util.Log
import com.dash.android.audio.SoundDevice
import com.dash.android.audio.SoundState
import com.dash.android.audio.SoundStream
import com.dash.android.audio.SoundSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.log10

/**
 * The machine's sound through PipeWire (DASH-AA 1.1.2) — DASH-AA's [SoundSystem].
 *
 * **Through PipeWire's own tools, never its library.** One `pw-dump --monitor` runs for the life of
 * DASH and reports every change as it happens: a USB sound card plugged in, an app starting to play, a
 * volume moved by the desktop. Changes go out through `wpctl`, one short process each. A sound system
 * that hangs can only hang one of those processes, which is killed after a few seconds; nothing here can
 * stall DASH, or the acknowledgements Android Auto waits on (the 1.0.5 rule). It all runs as the person
 * at the seat: PipeWire is theirs already, so nothing needs root.
 *
 * **The choices are the machine's.** Choosing the speakers makes them PipeWire's default, which
 * WirePlumber remembers across restarts and every app follows — on the 1.2.x PC, with no desktop,
 * DASH's Audio tabs *are* the sound settings.
 *
 * Capability-detected: with no `pw-dump` or `wpctl` the state says so, and the tabs say it in plain words.
 */
class PipeWireSound : SoundSystem {

    private val _state = MutableStateFlow(SoundState(available = false, note = "Looking for PipeWire…"))
    override val state: StateFlow<SoundState> = _state.asStateFlow()

    private val graph = PwGraph()
    @Volatile private var started = false

    /** Commands, one at a time and never on the caller's thread. */
    private val commands = Executors.newSingleThreadExecutor { r -> Thread(r, "dash-sound-commands").apply { isDaemon = true } }
    /** The latest volume asked for each id while its command waits — a held stepper sends one, not ten. */
    private val pendingVolume = ConcurrentHashMap<String, Float>()

    override fun start() {
        if (started) return
        started = true
        val dump = which("pw-dump")
        if (dump == null || which("wpctl") == null) {
            _state.value = SoundState(false, "PipeWire's tools (pw-dump, wpctl) are not installed — install pipewire and wireplumber")
            return
        }
        Thread({ monitor(dump) }, "dash-sound-monitor").apply { isDaemon = true }.start()
    }

    private fun monitor(dump: String) {
        while (true) {
            val p = runCatching {
                ProcessBuilder(dump, "--monitor", "--no-colors").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull()
            if (p == null) {
                _state.value = SoundState(false, "pw-dump would not start")
                return
            }
            synchronized(graph) { graph.clear() }
            val batch = StringBuilder()
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    batch.append(line).append('\n')
                    // pw-dump prints each batch of changes as one JSON array, its brackets alone at the margin.
                    if (line == "]" || line == "[]") {
                        val ok = synchronized(graph) { runCatching { graph.apply(batch.toString()) } }
                            .onFailure { Log.w(TAG, "unreadable update: ${it.message}") }.isSuccess
                        if (ok) publish()
                        batch.setLength(0)
                    }
                }
            }
            p.destroy()
            _state.value = SoundState(false, "PipeWire is not running — waiting for it to start")
            Log.w(TAG, "pw-dump ended — PipeWire stopped or restarted; watching again shortly")
            Thread.sleep(2000)
        }
    }

    private fun publish() = synchronized(graph) {
        if (!graph.loaded) return                           // PipeWire not (yet) running: keep saying so
        val s = graph.snapshot()
        // A volume still on its way out shows as asked, so a stepper pressed twice moves twice.
        _state.value = if (pendingVolume.isEmpty()) s else s.copy(
            outputs = s.outputs.map { d -> pendingVolume[d.id]?.let { d.copy(volume = it) } ?: d },
            inputs = s.inputs.map { d -> pendingVolume[d.id]?.let { d.copy(volume = it) } ?: d },
            streams = s.streams.map { t -> pendingVolume[t.id]?.let { t.copy(volume = it) } ?: t },
        )
    }

    override fun setDefault(device: SoundDevice) {
        _state.value = _state.value.let { s ->
            val outputs = device.id in s.outputs.map { it.id }
            if (outputs) s.copy(outputs = s.outputs.map { it.copy(isDefault = it.id == device.id) })
            else s.copy(inputs = s.inputs.map { it.copy(isDefault = it.id == device.id) })
        }
        commands.execute { wpctl("set-default", device.id) }
    }

    override fun setVolume(id: String, volume: Float) {
        val queued = pendingVolume.put(id, volume.coerceIn(0f, 1f)) != null
        publish()
        if (!queued) commands.execute { sendVolume(id) }    // else the waiting command takes the latest
    }

    private fun sendVolume(id: String) {
        while (true) {
            val latest = pendingVolume[id] ?: return
            wpctl("set-volume", id, "%.3f".format(Locale.ROOT, latest))
            if (pendingVolume.remove(id, latest)) return    // not moved again while it was being sent
        }
    }

    override fun setMuted(id: String, muted: Boolean) {
        commands.execute { wpctl("set-mute", id, if (muted) "1" else "0") }
    }

    override fun listen(onLevel: (Float) -> Unit): AutoCloseable? {
        val exe = which("pw-cat") ?: return null
        val p = runCatching {
            ProcessBuilder(
                exe, "--record", "--raw", "--format", "s16", "--rate", "16000", "--channels", "1",
                "-P", "node.name=dash-aa-meter node.description=DASH-AA_level_meter application.name=DASH-AA application.id=dash-aa.meter",
                "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        Thread({
            val buf = ByteArray(800)                        // 25 ms at 16 kHz mono
            runCatching {
                val input = p.inputStream
                while (true) {
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) return@runCatching; n += r }
                    onLevel(level(buf, n))
                }
            }
            onLevel(0f)
        }, "dash-sound-meter").apply { isDaemon = true }.start()
        return AutoCloseable { p.destroy() }
    }

    private fun wpctl(vararg args: String) {
        val ok = runCatching {
            val p = ProcessBuilder(listOf("wpctl") + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            p.waitFor(3, TimeUnit.SECONDS).also { if (!it) p.destroyForcibly() } && p.exitValue() == 0
        }.getOrDefault(false)
        if (!ok) Log.w(TAG, "wpctl ${args.joinToString(" ")} did not succeed")
    }

    companion object {
        private const val TAG = "DashSound"

        /** Whether PipeWire's tools are here, and which PipeWire — for System › This Machine. */
        fun summary(): String {
            if (which("pw-dump") == null || which("wpctl") == null) return "Missing — install pipewire and wireplumber"
            val version = runCatching {
                val p = ProcessBuilder("pipewire", "--version").redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(2, TimeUnit.SECONDS)
                Regex("""libpipewire (\S+)""").find(text)?.groupValues?.get(1)
            }.getOrNull()
            return if (version != null) "PipeWire $version" else "PipeWire"
        }

        private fun which(name: String): String? =
            (System.getenv("PATH") ?: "/usr/bin").split(':').map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

        /** The loudest sample in a chunk, on a decibel scale from −60 dB (0) to full scale (1). */
        internal fun level(pcm: ByteArray, n: Int): Float {
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val s = abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt())
                if (s > peak) peak = s
                i += 2
            }
            if (peak == 0) return 0f
            val db = 20 * log10(peak / 32768.0)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * PipeWire's objects as `pw-dump --monitor` reports them, and the [SoundState] they add up to. Kept
 * apart from the processes so it can be tested with recorded output.
 *
 * Each batch carries whole objects, replacing what was known; an object without a type has gone. The
 * one exception is metadata, which arrives as the entries that changed — so the defaults are merged.
 */
internal class PwGraph {
    private val json = Json { ignoreUnknownKeys = true }
    private val objects = HashMap<Int, JsonObject>()
    /** PipeWire's "default" metadata: `default.audio.sink` and the like, each to a node name. */
    private val defaults = HashMap<String, String>()

    /** Whether a first full picture has arrived since the monitor (re)started. */
    var loaded = false
        private set

    fun clear() { objects.clear(); defaults.clear(); loaded = false }

    fun apply(batch: String) {
        val items = json.parseToJsonElement(batch).jsonArray
        loaded = true
        for (e in items) {
            val o = e as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
            val type = o.str("type")
            if (type == null) { objects.remove(id); continue }
            if (type.endsWith("Metadata")) {
                if ((o["props"] as? JsonObject)?.str("metadata.name") == "default") mergeDefaults(o["metadata"])
                continue
            }
            objects[id] = o
        }
    }

    private fun mergeDefaults(entries: JsonElement?) {
        for (m in entries as? JsonArray ?: return) {
            val entry = m as? JsonObject ?: continue
            if (entry["subject"]?.jsonPrimitive?.intOrNull != 0) continue
            val key = entry.str("key") ?: continue
            var value = entry["value"]
            // WirePlumber writes the value as JSON; a hand-set one can arrive as a string of JSON.
            if (value is JsonPrimitive && value.isString) value = runCatching { json.parseToJsonElement(value.content) }.getOrNull()
            val name = (value as? JsonObject)?.str("name")
            if (name == null) defaults.remove(key) else defaults[key] = name
        }
    }

    fun snapshot(): SoundState {
        val nodes = objects.values.filter { it.str("type")?.endsWith("Node") == true && it.props() != null }
        fun devices(mediaClass: String, defaultKey: String, direction: String) = nodes
            .filter { it.props()!!.str("media.class") == mediaClass && !isOurs(it) && !isPhone(it) }
            .map { n ->
                val p = n.props()!!
                val name = p.str("node.name") ?: ""
                val description = p.str("node.description") ?: p.str("node.nick") ?: name
                val route = routeOf(p, direction)
                val (volume, muted) = volumeOf(n)
                SoundDevice(
                    id = n.id().toString(),
                    label = route ?: description,
                    detail = if (route != null) description else null,
                    isDefault = defaults[defaultKey] == name,
                    volume = volume,
                    muted = muted,
                )
            }
            .sortedBy { it.label.lowercase() }

        val streams = nodes
            .filter { val p = it.props()!!
                p.str("media.class") == "Stream/Output/Audio" && p.str("stream.monitor") != "true" && !isOurs(it) && !isPhone(it) }
            .map { n ->
                val p = n.props()!!
                val (volume, muted) = volumeOf(n)
                val app = p.str("application.name") ?: p.str("node.description") ?: p.str("node.name") ?: "Something"
                SoundStream(
                    id = n.id().toString(),
                    label = app,
                    detail = p.str("media.name")?.takeIf { it.isNotBlank() && it != app },
                    volume = volume,
                    muted = muted,
                )
            }
            .sortedBy { it.label.lowercase() }

        val outputs = devices("Audio/Sink", "default.audio.sink", "Output")
        return SoundState(
            available = true,
            note = if (outputs.isEmpty()) "PipeWire is running, but has nothing to play through" else "PipeWire",
            outputs = outputs,
            inputs = devices("Audio/Source", "default.audio.source", "Input"),
            streams = streams,
        )
    }

    /** DASH-AA's own nodes — Android Auto's streams, the call loopbacks, the echo canceller, the level meter. */
    private fun isOurs(n: JsonObject): Boolean {
        val p = n.props() ?: return false
        return p.str("node.name")?.startsWith("dash-aa") == true || p.str("application.name") == "DASH-AA"
    }

    /**
     * The phone's own Bluetooth nodes. Its hands-free side — the caller's voice coming in, the microphone
     * going back — belongs to Audio › Calls, which has its own level and routing; its Bluetooth music
     * would double what Android Auto already plays. None is somewhere the user would choose to play or
     * record. A Bluetooth headset or speaker is not the phone, and stays.
     */
    private fun isPhone(n: JsonObject): Boolean {
        val p = n.props() ?: return false
        val profile = p.str("api.bluez5.profile") ?: return false
        return profile == "audio-gateway" || profile.startsWith("a2dp-source") || p.str("media.class")?.startsWith("Stream") == true
    }

    /** The port in use on the card — "Speakers", "Headphones" — which says more than the card's own name. */
    private fun routeOf(p: JsonObject, direction: String): String? {
        val deviceId = p.str("device.id")?.toIntOrNull() ?: return null
        val index = p.str("card.profile.device")?.toIntOrNull() ?: return null
        val routes = objects[deviceId]?.info()?.obj("params")?.get("Route") as? JsonArray ?: return null
        return routes.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("direction") == direction && it["device"]?.jsonPrimitive?.intOrNull == index }
            ?.str("description")
    }

    /**
     * PipeWire keeps volumes as the gain applied, which falls off far faster than loudness; `wpctl` and
     * every desktop show its cube root, which sounds even. So does DASH.
     */
    private fun volumeOf(n: JsonObject): Pair<Float, Boolean> {
        val props = (n.info()?.obj("params")?.get("Props") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }?.firstOrNull { it.containsKey("channelVolumes") } ?: return 1f to false
        val gain = (props["channelVolumes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.floatOrNull }?.maxOrNull() ?: 1f
        val muted = props["mute"]?.jsonPrimitive?.booleanOrNull ?: false
        return cbrt(gain.toDouble()).toFloat() to muted
    }

    private fun JsonObject.id() = this["id"]?.jsonPrimitive?.intOrNull ?: -1
    private fun JsonObject.info() = this["info"] as? JsonObject
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.props() = info()?.obj("props")
    /** A property as text, whatever its JSON type — PipeWire writes ids as numbers or strings. */
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
