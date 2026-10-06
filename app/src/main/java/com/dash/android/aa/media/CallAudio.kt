package com.dash.android.aa.media

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Phone calls, through the car — the half of Android Auto that does not travel over USB.
 *
 * During a call the phone talks to the head unit as a **Bluetooth hands-free kit**: its voice arrives
 * as a Bluetooth audio source and the car's microphone goes back as a Bluetooth audio sink. PipeWire
 * creates both, but as *devices*, and nothing joins a device to a device — so on a desktop the call
 * connects and stays silent. A car's head unit is the mixer, so DASH-AA does that joining itself:
 *
 * - the phone's voice → the default speakers;
 * - the default microphone → the phone.
 *
 * Each is one `pw-loopback`, started when the phone's call nodes appear and ended when they go. **If
 * something else has already connected a call node** (a desktop configured to do it, the user by hand)
 * DASH-AA leaves that node alone rather than doubling the sound. Only hands-free nodes are touched —
 * never a Bluetooth music stream, which would duplicate the music Android Auto already sends over USB.
 *
 * Capability-detected: no `pw-dump` or `pw-loopback` and calls simply stay as PipeWire left them.
 */
class CallAudio(
    /** Whether to run calls through the echo canceller — Audio › Calls › Echo cancelling. */
    private val echoCancel: () -> Boolean = { true },
    /** The gain on the friend's voice — Audio › Calls › Call volume. */
    private val callVolume: () -> Float = { 1.0f },
) {
    /** The gain last set on each voice stream, so it is set again only when it changes. */
    private val appliedVolume = mutableMapOf<Int, Float>()
    @Volatile var phoneAddress: String? = null

    /** The echo canceller for the call in progress, and the call streams moved through it. */
    private var canceller: Process? = null
    private val retargeted = mutableSetOf<Int>()
    @Volatile private var cancellerFailed = false

    private val loopbacks = HashMap<String, Process>()   // our loopback, by the call node it serves
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        reapOrphans()
        Thread({ loop() }, "dash-aa-call-audio").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        synchronized(loopbacks) { loopbacks.values.forEach { it.destroy() }; loopbacks.clear() }
        stopCanceller()
    }

    private fun loop() {
        while (running) {
            runCatching { sweep() }.onFailure { Log.w(TAG, "call routing: ${it.message}") }
            Thread.sleep(1000)
        }
    }

    private data class Node(val id: Int, val name: String, val mediaClass: String, val address: String?, val profile: String?)

    private fun sweep() {
        val phone = phoneAddress ?: return
        val dump = pwDump() ?: return
        val nodes = dump.filter { it.type().endsWith("Node") }.mapNotNull { o ->
            val p = o["info"]?.jsonObject?.get("props")?.jsonObject ?: return@mapNotNull null
            Node(
                id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                name = p.str("node.name") ?: return@mapNotNull null,
                mediaClass = p.str("media.class") ?: "",
                address = p.str("api.bluez5.address")?.uppercase(),
                profile = p.str("api.bluez5.profile"),
            )
        }
        val ours = nodes.filter { it.name.contains(LOOPBACK_PREFIX) }.map { it.id }.toSet()
        val links = dump.filter { it.type().endsWith("Link") }.mapNotNull { o ->
            val i = o["info"]?.jsonObject ?: return@mapNotNull null
            (i["output-node-id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null) to
                (i["input-node-id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null)
        }
        // The phone's hands-free nodes: its address, and not a Bluetooth music profile.
        val call = nodes.filter { it.address == phone.uppercase() && it.profile?.startsWith("a2dp") != true &&
            (it.mediaClass == "Audio/Source" || it.mediaClass == "Audio/Sink") }

        synchronized(loopbacks) {
            // Calls that have ended: their node is gone, so is our loopback.
            (loopbacks.keys - call.map { it.name }.toSet()).forEach { gone ->
                loopbacks.remove(gone)?.destroy(); Log.i(TAG, "call audio from $gone ended")
            }
            for (n in call) {
                if (loopbacks[n.name]?.isAlive == true) continue
                val linkedElsewhere = links.any { (out, inp) ->
                    (out == n.id && inp !in ours) || (inp == n.id && out !in ours)
                }
                if (linkedElsewhere) continue                      // someone else routes it — leave it be
                val cmd = if (n.mediaClass == "Audio/Source") listOf(
                    "pw-loopback", "-n", "$LOOPBACK_PREFIX-voice",
                    "--capture-props=target.object=${n.name} node.dont-reconnect=true",
                    "--playback-props=media.role=Phone node.description=DASH-AA_call",
                ) else listOf(
                    "pw-loopback", "-n", "$LOOPBACK_PREFIX-mic",
                    "--capture-props=media.role=Phone",
                    "--playback-props=target.object=${n.name} node.dont-reconnect=true",
                )
                val p = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
                    .onFailure { Log.w(TAG, "pw-loopback unavailable: ${it.message}") }.getOrNull() ?: continue
                loopbacks[n.name] = p
                Log.i(TAG, "call audio: ${if (n.mediaClass == "Audio/Source") "phone → speakers" else "microphone → phone"} (${n.name}, ${n.profile})")
            }
        }

        echoCancelling(phone, nodes, links)
    }

    /**
     * **Echo cancelling** (DASH-AA 1.0.7). On a call the friend's voice comes out of the laptop speakers,
     * the laptop microphone hears it, and it goes back to him — he hears himself. Every hands-free kit
     * removes that by knowing what it is playing and subtracting it from the microphone. PipeWire ships
     * exactly that (WebRTC's canceller); DASH-AA runs it for the length of a call only:
     *
     * - the call starts — WirePlumber has made the phone's two Communication *streams* (seen on Roger's
     *   recorded call, 2026-10-05): the voice to the speakers, the microphone to the phone;
     * - DASH-AA starts the canceller (`aa/echo-cancel.conf`): a "call speaker" that plays to the real
     *   speakers and remembers what it played, and a "call microphone" — the real one with that removed;
     * - it moves the voice onto the call speaker and the phone's microphone onto the call microphone
     *   (`target.object` in PipeWire's metadata, which WirePlumber honours by relinking);
     * - the call ends, the streams go, the canceller stops — and the microphone with it.
     *
     * Nothing else on the desktop is moved, and the default devices are never changed. If the canceller
     * cannot start, the call simply carries on as WirePlumber wired it.
     */
    private fun echoCancelling(phone: String, nodes: List<Node>, links: List<Pair<Int, Int>>) {
        val voice = nodes.filter { it.address == phone.uppercase() && it.mediaClass == "Stream/Output/Audio" && it.profile?.startsWith("a2dp") != true }
        applyCallVolume(voice)
        val mic = nodes.filter { it.address == phone.uppercase() && it.mediaClass == "Stream/Input/Audio" && it.profile?.startsWith("a2dp") != true }
        if ((voice.isEmpty() && mic.isEmpty()) || !echoCancel()) {
            if (canceller != null) { stopCanceller(); Log.i(TAG, "call ended — echo cancelling stopped") }
            if (voice.isEmpty() && mic.isEmpty()) cancellerFailed = false
            return
        }
        if (cancellerFailed) return
        if (canceller?.isAlive != true) { startCanceller(); return }       // nodes appear by the next sweep
        val sink = nodes.firstOrNull { it.name == EC_SINK } ?: return
        val source = nodes.firstOrNull { it.name == EC_SOURCE } ?: return
        for (v in voice) if (v.id !in retargeted || links.none { it == v.id to sink.id }) retarget(v.id, EC_SINK)
        for (m in mic) if (m.id !in retargeted || links.none { it == source.id to m.id }) retarget(m.id, EC_SOURCE)
    }

    /**
     * **Call volume** (DASH-AA 1.0.8; Roger: "the call volume is quite low… it might need to be overdriven").
     * The phone sends the friend's voice over Bluetooth at a fixed level, so the only headroom is gain on
     * the laptop — up to 3×, past full scale, which is the overdrive asked for (loud peaks may clip).
     *
     * **It is set on the voice stream itself, before the echo canceller**, deliberately: the canceller then
     * knows the louder sound it is about to subtract. Boost after it and the echo it removed comes back.
     * Music's volume is separate and untouched.
     */
    private fun applyCallVolume(voice: List<Node>) {
        appliedVolume.keys.retainAll(voice.map { it.id }.toSet())
        val gain = callVolume().coerceIn(0.1f, 4f)
        for (v in voice) {
            if (appliedVolume[v.id] == gain) continue
            val ok = runCatching {
                ProcessBuilder("wpctl", "set-volume", "${v.id}", "%.2f".format(java.util.Locale.ROOT, gain))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (ok) { appliedVolume[v.id] = gain; Log.i(TAG, "call volume: ${(gain * 100).toInt()}% on node ${v.id}") }
        }
    }

    private fun retarget(nodeId: Int, target: String) {
        val ok = runCatching {
            ProcessBuilder("pw-metadata", "-n", "default", "$nodeId", "target.object", target)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(3, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (ok && retargeted.add(nodeId)) Log.i(TAG, "echo cancelling: node $nodeId → $target")
    }

    private fun startCanceller() {
        val conf = runCatching {
            java.io.File.createTempFile("dash-aa-echo-cancel", ".conf").apply {
                deleteOnExit()
                writeBytes(CallAudio::class.java.getResourceAsStream("/aa/echo-cancel.conf")!!.readBytes())
            }
        }.getOrNull()
        val p = conf?.let { runCatching { ProcessBuilder("pipewire", "-c", it.absolutePath)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() }
        if (p == null) {
            cancellerFailed = true
            Log.w(TAG, "echo cancelling unavailable (pipewire could not start the canceller) — call left as wired")
            return
        }
        canceller = p
        if (!hookInstalled) {
            hookInstalled = true
            // DASH-AA quitting or being stopped mid-call must not leave the canceller — and the microphone
            // it holds open — running behind it.
            Runtime.getRuntime().addShutdownHook(Thread { canceller?.destroyForcibly() })
        }
        Log.i(TAG, "call started — echo cancelling on")
    }

    @Volatile private var hookInstalled = false

    /**
     * A DASH-AA killed outright (no shutdown hook runs on SIGKILL or a crash) leaves its canceller behind,
     * still holding the microphone. Found 2026-10-05 when a test died mid-call. So on start, any DASH-AA
     * canceller whose parent is gone — orphaned, adopted by the system — is stopped. A canceller belonging
     * to another running DASH-AA still has its parent and is left alone.
     */
    private fun reapOrphans() {
        ProcessHandle.allProcesses()
            // Only the pipewire program itself, running a DASH-AA canceller config — never anything that
            // merely mentions the name (a shell or an editor would; the first version of this matched them).
            .filter { ph ->
                val info = ph.info()
                info.command().orElse("").substringAfterLast('/') == "pipewire" &&
                    info.arguments().orElse(emptyArray()).any { it.substringAfterLast('/').startsWith("dash-aa-echo-cancel") }
            }
            .filter { ph ->
                val parent = ph.parent().orElse(null)
                val parentCmd = parent?.info()?.command()?.orElse("") ?: ""
                parent == null || !(parentCmd.contains("java") || parentCmd.contains("dash-aa"))
            }
            .forEach { ph -> Log.w(TAG, "stopping an orphaned echo canceller (pid ${ph.pid()}) left by an earlier DASH-AA"); ph.destroy() }
    }

    private fun stopCanceller() {
        retargeted.forEach { id ->
            runCatching { ProcessBuilder("pw-metadata", "-n", "default", "-d", "$id", "target.object")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(2, TimeUnit.SECONDS) }
        }
        retargeted.clear()
        canceller?.destroy()
        canceller = null
    }

    private fun pwDump(): JsonArray? {
        val p = runCatching { ProcessBuilder("pw-dump").start() }.getOrNull() ?: return null
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        return runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.type(): String =
        (this as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull ?: ""
    private operator fun kotlinx.serialization.json.JsonElement.get(key: String) = (this as? JsonObject)?.get(key)
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "DashAaCalls"
        const val LOOPBACK_PREFIX = "dash-aa-call"
        const val EC_SINK = "dash-aa-ec-sink"
        const val EC_SOURCE = "dash-aa-ec-source"
    }
}
