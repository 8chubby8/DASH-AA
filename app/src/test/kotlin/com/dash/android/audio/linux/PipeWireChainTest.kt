package com.dash.android.audio.linux

import com.dash.android.audio.CarSound
import com.dash.android.audio.SpeakerAssignment
import com.dash.android.audio.SpeakerPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The config files the chain's two services run, and the live changes sent to them. */
class PipeWireChainTest {
    private val car = CarSound(
        enabled = true,
        speakers = mapOf(
            SpeakerPosition.FRONT to SpeakerAssignment("alsa_output.card", "Card", listOf("FL", "FR")),
            SpeakerPosition.REAR to SpeakerAssignment("alsa_output.usb", "USB", listOf("FL", "FR"), level = 0.7f),
            SpeakerPosition.SUBWOOFER to SpeakerAssignment("alsa_output.card", "Card", listOf("FL", "FR")),
        ),
        eq = listOf(4, 0, 0, 0, 0, 0, 0, 0, 0, -2),
        lowCut = 60,
    )

    /** The file, as PipeWire reads it: a comment line, then JSON. */
    private fun parse(text: String): JsonObject {
        assertTrue(text.startsWith("# "))
        return Json.parseToJsonElement(text.substringAfter('\n')).jsonObject
    }

    private fun module(conf: JsonObject, name: String) =
        conf["context.modules"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }["args"]!!.jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }

    @Test fun `the way in is DASH's output, its equaliser and crossover, offered on as left, right and low`() {
        val args = module(parse(PipeWireChain.wayInConfig(car)), "libpipewire-module-filter-chain")
        val capture = args["capture.props"]!!.jsonObject
        assertEquals(PipeWireChain.SINK, capture.str("node.name"))
        assertEquals("Audio/Sink", capture.str("media.class"))
        val playback = args["playback.props"]!!.jsonObject
        assertEquals(PipeWireChain.PROCESSED, playback.str("node.name"))
        assertEquals("Audio/Source", playback.str("media.class"))
        assertEquals(listOf("FL", "FR", "LFE", "RC"), playback["audio.position"]!!.jsonArray.strings())

        val graph = args["filter.graph"]!!.jsonObject
        val nodes = graph["nodes"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        assertEquals(2 + 20 + 18 + 4 + 1 + 2 + 2 + 4, nodes.size)   // anti-distortion, 2 × 10 bands, 2 × 9 loudness, 2 × 2 low cut, the crossover, the effect and its delay, the final copies
        assertEquals("4.0", nodes.getValue("eqL0")["control"]!!.jsonObject.str("Gain"))
        assertEquals("-2.0", nodes.getValue("eqR9")["control"]!!.jsonObject.str("Gain"))
        assertEquals("60.0", nodes.getValue("hpR2")["control"]!!.jsonObject.str("Freq"))
        assertEquals("80.0", nodes.getValue("lp2")["control"]!!.jsonObject.str("Freq"))
        assertEquals(listOf("oL:Out", "oR:Out", "oLow:Out", "oDiff:Out"), graph["outputs"]!!.jsonArray.strings())
        // No final output may feed anything else, or PipeWire plays silence on it.
        val outs = graph["outputs"]!!.jsonArray.strings().toSet()
        assertTrue(graph["links"]!!.jsonArray.map { it.jsonObject.str("output") }.none { it in outs })
        assertEquals("-0.5", nodes.getValue("diff")["control"]!!.jsonObject.str("Gain 2"))
        assertEquals(0.015f, PipeWireChain.wayInParams(car.copy(surroundDelay = 15)).toMap().getValue("sdelay:Delay (s)"), 1e-6f)
        // Every link joins ports that exist.
        val ports = nodes.keys
        graph["links"]!!.jsonArray.map { it.jsonObject }.forEach { l ->
            assertTrue(l.str("output").substringBefore(':') in ports && l.str("input").substringBefore(':') in ports, "$l")
        }
    }

    @Test fun `anti-distortion takes the biggest boost off first`() {
        val nodes = module(parse(PipeWireChain.wayInConfig(car)), "libpipewire-module-filter-chain")["filter.graph"]!!
            .jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        // +4 dB is the biggest boost: everything down 4 dB first.
        assertEquals(0.631f, nodes.getValue("preL")["control"]!!.jsonObject.str("Gain 1").toFloat(), 0.001f)
        assertEquals(1f, PipeWireChain.wayInParams(car.copy(antiDistortion = false)).toMap().getValue("preR:Gain 1"))
    }

    @Test fun `calls have their own way in, with no equaliser`() {
        val conf = parse(PipeWireChain.wayInConfig(car))
        val calls = conf["context.modules"]!!.jsonArray.map { it.jsonObject }
            .filter { it.str("name") == "libpipewire-module-filter-chain" }.map { it["args"]!!.jsonObject }
            .first { it["capture.props"]!!.jsonObject.str("node.name") == PipeWireChain.CALLS }
        val names = calls["filter.graph"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertTrue(names.none { it.startsWith("eq") || it.startsWith("pre") }, "$names")
        assertEquals(PipeWireChain.CALLS_PROCESSED, calls["playback.props"]!!.jsonObject.str("node.name"))
        val layout = parse(PipeWireChain.layoutConfig(car)!!)["context.modules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, layout.count { it.str("name") == "libpipewire-module-combine-stream" })   // music, calls
    }

    @Test fun `the way in has one shape whatever the settings`() {
        val strip = { s: String -> s.replace(Regex("""-?\d+\.\d+"""), "#") }
        assertEquals(strip(PipeWireChain.wayInConfig(car)), strip(PipeWireChain.wayInConfig(CarSound())))
    }

    @Test fun `the layout mixes each output and sends each device its own`() {
        val conf = parse(PipeWireChain.layoutConfig(car)!!)
        val chain = module(conf, "libpipewire-module-filter-chain")
        val capture = chain["capture.props"]!!.jsonObject
        assertEquals(PipeWireChain.PROCESSED, capture.str("target.object"))
        assertEquals("true", capture.str("node.dont-fallback"))
        val nodes = chain["filter.graph"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        // The card's left plays the front's left and the subwoofer: two inputs to one mixer.
        val out0 = nodes.getValue("out0")["control"]!!.jsonObject
        assertEquals("1.0", out0.str("Gain 1"))
        assertEquals("1.0", out0.str("Gain 2"))
        assertEquals("0.7", nodes.getValue("out2")["control"]!!.jsonObject.str("Gain 1"))

        val combine = module(conf, "libpipewire-module-combine-stream")
        assertEquals(PipeWireChain.SPEAKERS, combine.str("node.name"))
        assertEquals("true", combine.str("combine.latency-compensate"))
        val rules = combine["stream.rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rules.size)                                   // one stream for each device
        val card = rules.first { it["matches"]!!.jsonArray[0].jsonObject.str("node.name") == "alsa_output.card" }
        val stream = card["actions"]!!.jsonObject["create-stream"]!!.jsonObject
        assertEquals(listOf("AUX0", "AUX1"), stream["combine.audio.position"]!!.jsonArray.strings())
        assertEquals(listOf("FL", "FR"), stream["audio.position"]!!.jsonArray.strings())
    }

    @Test fun `only a change of shape changes the first line`() {
        val first = { c: CarSound -> PipeWireChain.layoutConfig(c)!!.lineSequence().first() }
        assertEquals(first(car), first(car.copy(balance = 4, fade = -3, eq = List(10) { 1 })))
        val moved = car.copy(speakers = car.speakers + (SpeakerPosition.REAR to SpeakerAssignment("alsa_output.other", "Other", listOf("FL", "FR"))))
        assertNotEquals(first(car), first(moved))
    }

    @Test fun `nothing chosen, no layout`() {
        assertNull(PipeWireChain.layoutConfig(CarSound(enabled = true)))
    }

    @Test fun `live changes name the controls the files made`() {
        val params = PipeWireChain.layoutParams(car.copy(fade = 5)).toMap()
        assertEquals(0.5f, params.getValue("out0:Gain 1"), 1e-6f)    // front left, faded
        assertEquals(1f, params.getValue("out0:Gain 2"))              // the subwoofer is not
        val way = PipeWireChain.wayInParams(car).toMap()
        assertEquals(4f, way.getValue("eqL0:Gain"))
        assertEquals(60f, way.getValue("hpL1:Freq"))
        assertEquals(80f, way.getValue("lp1:Freq"))
    }

    @Test fun `an output fed by more than eight is mixed in eights`() {
        val crowd = CarSound(enabled = true, speakers = SpeakerPosition.entries.associateWith {
            SpeakerAssignment("one", "One", listOf("MONO"))
        })
        val nodes = module(parse(PipeWireChain.layoutConfig(crowd)!!), "libpipewire-module-filter-chain")["filter.graph"]!!
            .jsonObject["nodes"]!!.jsonArray.map { it.jsonObject.str("name") }
        // Front, rear and centre two each, the shelf three (its effect too), the subwoofer one: ten, so two
        // mixers and their sum.
        assertTrue("out0_0" in nodes && "out0_1" in nodes && "out0" in nodes, "$nodes")
        assertEquals(10 + 1, PipeWireChain.layoutParams(crowd).size)   // and the output's time alignment delay
    }

    private fun graphOf(conf: String, sink: String) = parse(conf)["context.modules"]!!.jsonArray.map { it.jsonObject }
        .filter { it.str("name") == "libpipewire-module-filter-chain" }.map { it["args"]!!.jsonObject }
        .first { it["capture.props"]!!.jsonObject.str("node.name") == sink }["filter.graph"]!!.jsonObject

    @Test fun `every live control names a filter the files made`() {
        val music = graphOf(PipeWireChain.wayInConfig(car), PipeWireChain.SINK)["nodes"]!!.jsonArray.map { it.jsonObject.str("name") }.toSet()
        val calls = graphOf(PipeWireChain.wayInConfig(car), PipeWireChain.CALLS)["nodes"]!!.jsonArray.map { it.jsonObject.str("name") }.toSet()
        val live = PipeWireChain.Live(volume = 0.3f, speedDb = 4f)
        PipeWireChain.wayInParams(car, live).forEach { (k, _) -> assertTrue(k.substringBefore(':') in music, k) }
        PipeWireChain.callsInParams(car, live).forEach { (k, _) -> assertTrue(k.substringBefore(':') in calls, k) }
        val layout = module(parse(PipeWireChain.layoutConfig(car)!!), "libpipewire-module-filter-chain")["filter.graph"]!!
            .jsonObject["nodes"]!!.jsonArray.map { it.jsonObject.str("name") }.toSet()
        PipeWireChain.layoutParams(car).forEach { (k, _) -> assertTrue(k.substringBefore(':') in layout, k) }
    }

    @Test fun `loudness follows the volume below the comfortable one`() {
        val loud = car.copy(loudness = 4, loudnessReference = 0.5f)
        val gains = { v: Float? -> PipeWireChain.liveParams(loud, PipeWireChain.Live(volume = v)).toMap().filterKeys { it.startsWith("ldL") } }
        assertTrue(gains(0.5f).values.all { it == 0f }, "flat at the comfortable volume")
        assertTrue(gains(0.8f).values.all { it == 0f }, "flat above it")
        assertTrue(gains(null).values.all { it == 0f }, "flat when the volume is unknown")
        assertTrue(PipeWireChain.liveParams(loud.copy(loudnessReference = null), PipeWireChain.Live(volume = 0.2f)).toMap()
            .filterKeys { it.startsWith("ld") }.values.all { it == 0f }, "flat until a comfortable volume is set")
        // Half the comfortable volume is 18 dB down (volumes are cubic): the bass shelves lift.
        assertEquals(-18.06f, PipeWireChain.volumeDb(0.25f) - PipeWireChain.volumeDb(0.5f), 0.01f)
        val down = gains(0.25f)
        assertTrue(down.getValue("ldL0:Gain") > 0f && down.getValue("ldL2:Gain") > 0f, "$down")
        assertEquals(gains(0.25f), PipeWireChain.liveParams(loud, PipeWireChain.Live(volume = 0.25f)).toMap()
            .filterKeys { it.startsWith("ldR") }.mapKeys { it.key.replace("ldR", "ldL") })
    }

    @Test fun `speed volume lifts music and calls alike`() {
        val live = PipeWireChain.Live(volume = 0.4f, speedDb = 6f)
        val lift = 1.9953f
        assertEquals(0.631f * lift, PipeWireChain.liveParams(car, live).toMap().getValue("preL:Gain 1"), 0.002f)
        assertEquals(lift, PipeWireChain.liveCallParams(live).toMap().getValue("cR:Gain 1"), 0.001f)
    }

    @Test fun `time alignment delays the nearer speakers, and changes live`() {
        val front = car.speakers.getValue(SpeakerPosition.FRONT).copy(distances = listOf(80, 120))
        val rear = car.speakers.getValue(SpeakerPosition.REAR).copy(distances = listOf(150, 150))
        val aligned = car.copy(timeAlignment = true, speakers = car.speakers + (SpeakerPosition.FRONT to front) + (SpeakerPosition.REAR to rear))
        val first = { c: CarSound -> PipeWireChain.layoutConfig(c)!!.lineSequence().first() }
        assertEquals(first(car), first(aligned), "distances change no shape")
        val params = PipeWireChain.layoutParams(aligned).toMap()
        assertEquals(70f / 34_300f, params.getValue("dl0:Delay (s)"), 1e-7f)       // front left, 70 cm nearer than the rear
        assertEquals(30f / 34_300f, params.getValue("dl1:Delay (s)"), 1e-7f)       // front right
        assertEquals(0f, params.getValue("dl2:Delay (s)"))                          // the rear, furthest
        assertTrue(PipeWireChain.layoutParams(aligned.copy(timeAlignment = false)).filter { it.first.startsWith("dl") }.all { it.second == 0f })
        val graph = module(parse(PipeWireChain.layoutConfig(aligned)!!), "libpipewire-module-filter-chain")["filter.graph"]!!.jsonObject
        val outs = graph["outputs"]!!.jsonArray.strings()
        assertTrue(outs.all { it.startsWith("dl") }, "$outs")
        assertTrue(graph["links"]!!.jsonArray.map { it.jsonObject.str("output") }.none { it in outs })
    }
}
