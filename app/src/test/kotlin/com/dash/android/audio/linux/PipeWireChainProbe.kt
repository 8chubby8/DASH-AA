package com.dash.android.audio.linux

import com.dash.android.audio.CarSound
import com.dash.android.audio.SpeakerAssignment
import com.dash.android.audio.SpeakerPosition
import com.dash.android.audio.defaultOutputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in (-Dsound=1): the chain's generated configs on the real PipeWire, run as child processes rather
 * than as services. Lays the front and subwoofer on the machine's default speakers, plays silence into
 * DASH's output, and checks the whole route links; that live changes reach both halves; and that
 * restarting the layout relinks it while the app stays on DASH's output. Never changes the default device
 * or any volume; plays only silence.
 */
/**
 * The probe's chain has names of its own (`dash-aa-probe.…`), so it can run beside a live Car sound: with
 * the same names, WirePlumber cannot tell the two apart, and once moved the default onto the live chain's
 * internal splitter (2026-10-08).
 */
private fun probeNames(text: String) = text.replace("\"dash-aa.", "\"dash-aa-probe.")
private object N {
    const val SINK = "dash-aa-probe.sound"
    const val PROCESSED = "dash-aa-probe.processed"
    const val CALLS = "dash-aa-probe.calls"
    const val CALLS_PROCESSED = "dash-aa-probe.calls.processed"
    const val LAYOUT = "dash-aa-probe.layout"
    const val LAYOUT_CALLS = "dash-aa-probe.layout.calls"
    const val SPEAKERS = "dash-aa-probe.speakers"
    const val SPEAKERS_CALLS = "dash-aa-probe.speakers.calls"
}

class PipeWireChainProbe {
    private val json = Json { ignoreUnknownKeys = true }

    private fun dump(): List<JsonObject> {
        val p = ProcessBuilder("pw-dump", "--no-colors").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return json.parseToJsonElement(p.inputStream.bufferedReader().readText()).jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.props() = (this["info"] as? JsonObject)?.get("props") as? JsonObject
    private fun JsonObject.name() = props()?.get("node.name")?.jsonPrimitive?.contentOrNull

    /** Every link as "from -> to", by node name. */
    private fun links(): Set<String> {
        val d = dump()
        val names = d.filter { it["type"]!!.jsonPrimitive.content.endsWith("Node") }.associate { it["id"]!!.jsonPrimitive.intOrNull to it.name() }
        return d.filter { it["type"]!!.jsonPrimitive.content.endsWith("Link") }.map {
            val i = it["info"]!!.jsonObject
            "${names[i["output-node-id"]!!.jsonPrimitive.intOrNull]} -> ${names[i["input-node-id"]!!.jsonPrimitive.intOrNull]}"
        }.toSet()
    }

    private fun waitFor(what: String, test: () -> Boolean) {
        val until = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < until) { if (runCatching(test).getOrDefault(false)) return; Thread.sleep(100) }
        error("timed out waiting for $what — links now: ${links().filter { "dash" in it }}")
    }

    /** A control's value as the running filter reports it. */
    private fun control(node: String, key: String): Float? {
        val n = dump().firstOrNull { it.name() == node } ?: return null
        val props = (n["info"]!!.jsonObject["params"] as? JsonObject)?.get("Props") as? JsonArray ?: return null
        for (p in props.map { it.jsonObject }) {
            val list = p["params"] as? JsonArray ?: continue
            for (i in 0 until list.size - 1 step 2) if (list[i].jsonPrimitive.contentOrNull == key) return list[i + 1].jsonPrimitive.floatOrNull
        }
        return null
    }

    private fun setParam(node: String, key: String, value: Float) {
        val id = dump().first { it.name() == node }["id"]!!.jsonPrimitive.content
        ProcessBuilder("pw-cli", "set-param", id, "Props", "{ params = [ \"$key\" $value ] }").start().waitFor()
    }

    /** Six seconds of 440 Hz left and 660 Hz right, at a quarter of full scale. */
    private fun tone(): ByteArray {
        val b = java.nio.ByteBuffer.allocate(48000 * 6 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 48000 * 6) {
            b.putShort((8000 * kotlin.math.sin(2 * Math.PI * 440 * i / 48000)).toInt().toShort())
            b.putShort((8000 * kotlin.math.sin(2 * Math.PI * 660 * i / 48000)).toInt().toShort())
        }
        return b.array()
    }

    /** The loudest sample heard from [node] over a second: from it as a recording, or from what plays into it. */
    private fun peak(node: String, monitor: Boolean): Int {
        val p = ProcessBuilder(
            "pw-cat", "--record", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
            "--target", node, "-P", "node.name=dash-probe-ear stream.capture.sink=$monitor", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val buf = ByteArray(48000 * 4)
        var n = 0
        val until = System.currentTimeMillis() + 1500
        val input = p.inputStream
        while (n < buf.size && System.currentTimeMillis() < until) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        p.destroy()
        var loudest = 0
        for (i in 0 until n / 2) loudest = maxOf(loudest, kotlin.math.abs(((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt()))
        return loudest
    }

    private fun pipewire(conf: File) = ProcessBuilder("pipewire", "-c", conf.absolutePath)
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

    @Test fun `the generated chain links, changes live, and survives a layout restart`() {
        if (System.getProperty("sound") == null) return
        val sound = PipeWireSound().apply { start() }
        waitFor("PipeWire") { sound.state.value.available && sound.state.value.outputs.isNotEmpty() }
        val speakers = sound.state.value.outputs.filterNot { it.dash }.let { o -> o.firstOrNull { it.isDefault } ?: o.first() }
        println("laying out on ${speakers.key} ${speakers.channels}")
        val car = CarSound(enabled = true, surroundMode = com.dash.android.audio.SurroundMode.WIDE, surroundDelay = 15, speakers = mapOf(
            SpeakerPosition.FRONT to SpeakerAssignment(speakers.key, speakers.label, defaultOutputs(SpeakerPosition.FRONT, speakers.channels)),
            SpeakerPosition.SURROUND to SpeakerAssignment(speakers.key, speakers.label, defaultOutputs(SpeakerPosition.SURROUND, speakers.channels)),
            SpeakerPosition.SUBWOOFER to SpeakerAssignment(speakers.key, speakers.label, defaultOutputs(SpeakerPosition.SUBWOOFER, speakers.channels)),
        ))
        val dir = Files.createTempDirectory("dash-chain").toFile()
        val wayIn = File(dir, "a.conf").apply { writeText(probeNames(PipeWireChain.wayInConfig(car))) }
        val layout = File(dir, "b.conf").apply { writeText(probeNames(PipeWireChain.layoutConfig(car)!!)) }

        val a = pipewire(wayIn)
        var b: Process? = null
        var player: Process? = null
        try {
            waitFor("the way in") { sound.nodeId(N.PROCESSED) != null }
            b = pipewire(layout)
            player = ProcessBuilder(
                "pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
                "--target", N.SINK, "-P", "node.name=dash-probe-player", "/dev/zero",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

            val route = listOf(
                "dash-probe-player -> ${N.SINK}",
                "${N.PROCESSED} -> ${N.LAYOUT}",
                "${N.LAYOUT}.out -> ${N.SPEAKERS}",
                "${N.CALLS_PROCESSED} -> ${N.LAYOUT_CALLS}",
                "${N.LAYOUT_CALLS}.out -> ${N.SPEAKERS_CALLS}",
            )
            waitFor("the whole route") { links().let { l -> route.all { it in l } && l.any { it.endsWith("-> ${speakers.key}") && "${N.SPEAKERS}_" in it } } }
            println("route: ${links().filter { "dash" in it }}")

            assertTrue(links().any { it.startsWith("output.${N.SPEAKERS_CALLS}_") && it.endsWith("-> ${speakers.key}") },
                "the calls reach the speakers too")

            // A call, playing: sent "DASH calls"' way as the chain does it, it moves there and plays on.
            val call = ProcessBuilder(
                "pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "48000", "--channels", "1",
                "--target", N.SINK, "-P", "node.name=dash-probe-call media.role=Phone", "/dev/zero",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                waitFor("the call to play") { "dash-probe-call -> ${N.SINK}" in links() }
                val id = sound.streamsInto(setOf(N.SINK), calls = true).single()
                ProcessBuilder("pw-metadata", "-n", "default", id, "target.object", N.CALLS).start().waitFor()
                waitFor("the call to move to DASH calls") { "dash-probe-call -> ${N.CALLS}" in links() }
                assertTrue(sound.streamsInto(setOf(N.SINK), calls = false).none { it == id })
            } finally {
                call.destroy()
            }

            // A stray, straight on the speakers: moved back onto DASH, as after a PipeWire restart.
            val stray = ProcessBuilder(
                "pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
                "--target", speakers.key, "-P", "node.name=dash-probe-stray", "/dev/zero",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                waitFor("the stray on the speakers") { "dash-probe-stray -> ${speakers.key}" in links() }
                val id = sound.streamsInto(setOf(speakers.key), calls = false).single()
                ProcessBuilder("pw-metadata", "-n", "default", id, "target.object", N.SINK).start().waitFor()
                waitFor("the stray back on DASH") { "dash-probe-stray -> ${N.SINK}" in links() }
            } finally {
                stray.destroy()
            }

            // Sound, not just links: a tone in, measured out of each half — music, then a call. (Links alone
            // passed while the way in played silence, 2026-10-08.)
            val tone = File(dir, "tone.raw").apply { writeBytes(tone()) }
            for ((into, out, splitter, role) in listOf(
                listOf(N.SINK, N.PROCESSED, N.SPEAKERS, "Music"), listOf(N.CALLS, N.CALLS_PROCESSED, N.SPEAKERS_CALLS, "Phone"),
            )) {
                val playing = ProcessBuilder(
                    "pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
                    "--target", into, "-P", "node.name=dash-probe-tone media.role=$role", tone.absolutePath,
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                try {
                    Thread.sleep(800)
                    val wayOut = peak(out, monitor = false)
                    val atSpeakers = peak(splitter, monitor = true)
                    println("$role: tone 8000 in, $wayOut out of the way in, $atSpeakers into the speakers")
                    assertTrue(wayOut > 2000, "$role comes out of the way in ($wayOut)")
                    assertTrue(atSpeakers > 2000, "$role reaches the speakers ($atSpeakers)")
                } finally {
                    playing.destroy()
                }
            }

            setParam(N.SINK, "eqL0:Gain", 5f)
            setParam(N.LAYOUT, "out0:Gain 1", 0.25f)
            waitFor("the live changes") { control(N.SINK, "eqL0:Gain") == 5f && control(N.LAYOUT, "out0:Gain 1") == 0.25f }

            b.destroy(); b.waitFor()
            waitFor("the layout to go") { links().none { it.startsWith("${N.PROCESSED} ->") } }
            assertTrue("dash-probe-player -> ${N.SINK}" in links(), "the app stays on DASH's output while the layout is away")
            b = pipewire(layout)
            waitFor("the layout to relink") { links().let { l -> route.all { it in l } } }
            assertEquals(1f, control(N.LAYOUT, "out0:Gain 1"), "a restarted layout starts from its file")
            println("relinked: ${links().filter { "dash" in it }}")
        } finally {
            player?.destroy(); b?.destroy(); a.destroy()
            dir.deleteRecursively()
        }
    }
}

/** Opt-in (-Dsound=1): the two services' unit files, as systemd itself reads them. Installs nothing. */
class PipeWireChainUnitsProbe {
    @Test fun `systemd accepts both units, and the layout's wait finds the way in`() {
        if (System.getProperty("sound") == null) return
        val dir = Files.createTempDirectory("dash-units").toFile()
        val chain = PipeWireChain(PipeWireSound(), configDir = dir, unitDir = dir).apply { start() }
        val until = System.currentTimeMillis() + 5000
        while (!chain.state.value.available && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(chain.state.value.available, chain.state.value.note)
        File(dir, PipeWireChain.SOUND_CONF).writeText(probeNames(PipeWireChain.wayInConfig(CarSound())))
        val sound = File(dir, PipeWireChain.SOUND_UNIT).apply { writeText(chain.soundUnit()) }
        val speakers = File(dir, PipeWireChain.SPEAKERS_UNIT).apply { writeText(chain.speakersUnit()) }
        println(speakers.readText())
        val verify = ProcessBuilder("systemd-analyze", "--user", "verify", sound.absolutePath, speakers.absolutePath)
            .redirectErrorStream(true).start()
        val said = verify.inputStream.bufferedReader().readText()
        verify.waitFor()
        println("systemd-analyze: ${said.ifBlank { "(nothing to say)" }}")
        assertTrue(said.lines().none { it.contains(dir.name) && !it.contains("dash-aa-sound.service: Unit") }, said)

        // The wait, as systemd will run it once its $$ has become $: quick when the way in is up.
        val pre = speakers.readLines().first { it.startsWith("ExecStartPre=") }.removePrefix("ExecStartPre=")
            .replace("$$", "$").removePrefix("/bin/sh -c '").removeSuffix("'")
            .replace("dash-aa.", "dash-aa-probe.")
        val a = ProcessBuilder("pipewire", "-c", File(dir, PipeWireChain.SOUND_CONF).absolutePath)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val t0 = System.currentTimeMillis()
            val sh = ProcessBuilder("/bin/sh", "-c", pre.replace("\\\"", "\"")).start()
            sh.waitFor()
            val took = System.currentTimeMillis() - t0
            println("the layout's wait took $took ms")
            assertTrue(took < 5000, "found the way in before giving up")
        } finally {
            a.destroy(); dir.deleteRecursively()
        }
    }
}
