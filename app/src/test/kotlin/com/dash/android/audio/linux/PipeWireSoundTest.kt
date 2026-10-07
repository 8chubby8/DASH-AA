package com.dash.android.audio.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PipeWire graph, fed batches shaped exactly as `pw-dump --monitor` prints them (PipeWire 1.6.9 on
 * the G14, 2026-10-07): whole objects, removals as an id with no type, metadata as the entries changed.
 */
class PipeWireSoundTest {

    private fun node(id: Int, mediaClass: String, props: String, gain: Double = 0.010648, mute: Boolean = false) = """
        {
          "id": $id, "type": "PipeWire:Interface:Node",
          "info": {
            "props": { "media.class": "$mediaClass", $props },
            "params": { "Props": [ { "volume": 1.0, "mute": $mute, "channelVolumes": [ $gain, $gain ] }, { } ] }
          }
        }"""

    private val card = """
        {
          "id": 51, "type": "PipeWire:Interface:Device",
          "info": { "props": { "device.description": "Ryzen HD Audio Controller" },
            "params": { "Route": [
              { "index": 0, "device": 0, "direction": "Input", "description": "Internal Microphone" },
              { "index": 1, "device": 3, "direction": "Output", "description": "Speakers" } ] } }
        }"""

    private fun defaults(sink: String, source: String) = """
        { "id": 40, "type": "PipeWire:Interface:Metadata", "props": { "metadata.name": "default" },
          "metadata": [
            { "subject": 0, "key": "default.audio.sink", "type": "Spa:String:JSON", "value": { "name": "$sink" } },
            { "subject": 0, "key": "default.audio.source", "type": "Spa:String:JSON", "value": { "name": "$source" } } ] }"""

    private val first = """[
        $card,
        ${node(58, "Audio/Sink", """"node.name": "alsa_output.analog", "node.description": "Ryzen HD Audio Controller Analog Stereo", "device.id": 51, "card.profile.device": 3""")},
        ${node(59, "Audio/Source", """"node.name": "alsa_input.analog", "node.description": "Ryzen HD Audio Controller Analog Stereo", "device.id": 51, "card.profile.device": 0""")},
        ${node(70, "Audio/Sink", """"node.name": "alsa_output.usb", "node.description": "USB Audio Device"""", gain = 1.0)},
        ${node(80, "Stream/Output/Audio", """"node.name": "spotify", "application.name": "Spotify", "media.name": "Spotify"""", gain = 0.125)},
        ${node(81, "Stream/Output/Audio", """"node.name": "dash-aa-media-audio", "application.name": "DASH-AA"""")},
        ${node(82, "Audio/Source", """"node.name": "bluez_input.phone", "api.bluez5.profile": "audio-gateway", "api.bluez5.address": "AA:BB"""")},
        ${node(83, "Audio/Sink", """"node.name": "dash-aa-ec-sink", "node.description": "call speaker"""")},
        ${defaults("alsa_output.analog", "alsa_input.analog")}
    ]"""

    @Test fun `devices are named by the port in use, and the default is marked`() {
        val g = PwGraph().apply { apply(first) }
        val s = g.snapshot()
        assertTrue(s.available)
        assertEquals(listOf("Speakers", "USB Audio Device"), s.outputs.map { it.label })
        val speakers = s.outputs.first()
        assertEquals("58", speakers.id)
        assertEquals("Ryzen HD Audio Controller Analog Stereo", speakers.detail)
        assertTrue(speakers.isDefault)
        assertNull(s.outputs[1].detail)
        assertEquals(listOf("Internal Microphone"), s.inputs.map { it.label })
        assertTrue(s.inputs.single().isDefault)
    }

    @Test fun `volumes read as people hear them, the cube root of PipeWire's gain`() {
        val s = PwGraph().apply { apply(first) }.snapshot()
        assertEquals(0.22f, s.outputs.first().volume, 0.005f)
        assertEquals(1.0f, s.outputs[1].volume, 0.005f)
        assertEquals(0.5f, s.streams.single().volume, 0.005f)
    }

    @Test fun `DASH-AA's own nodes and the phone's are left out`() {
        val s = PwGraph().apply { apply(first) }.snapshot()
        assertEquals(listOf("Spotify"), s.streams.map { it.label })
        assertNull(s.streams.single().detail, "a media name that only repeats the app's is not shown")
        assertTrue(s.outputs.none { it.id == "83" }, "the echo canceller's speaker is not a choice")
        assertTrue(s.inputs.none { it.id == "82" }, "the phone's call voice is not a microphone")
    }

    @Test fun `later batches replace, remove and change the default`() {
        val g = PwGraph().apply { apply(first) }
        g.apply("""[ { "id": 80, "info": null } ]""")
        g.apply("""[ { "id": 40, "type": "PipeWire:Interface:Metadata", "props": { "metadata.name": "default" },
            "metadata": [ { "subject": 0, "key": "default.audio.sink", "type": "Spa:String:JSON", "value": { "name": "alsa_output.usb" } } ] } ]""")
        g.apply("[\n" + node(58, "Audio/Sink", """"node.name": "alsa_output.analog", "node.description": "Ryzen", "device.id": 51, "card.profile.device": 3""", mute = true) + "\n]")
        val s = g.snapshot()
        assertTrue(s.streams.isEmpty())
        assertEquals("70", s.outputs.single { it.isDefault }.id)
        assertTrue(s.inputs.single().isDefault, "the source default was not in the change, so it stands")
        assertTrue(s.outputs.first { it.id == "58" }.muted)
    }

    @Test fun `the level meter reads silence as empty and full scale as full`() {
        assertEquals(0f, PipeWireSound.level(ByteArray(800), 800))
        val full = ByteArray(800).also { it[0] = 0xFF.toByte(); it[1] = 0x7F }
        assertEquals(1f, PipeWireSound.level(full, 800), 0.001f)
        val minus30 = ByteArray(800).also { val v = (32768 * 0.0316).toInt(); it[0] = v.toByte(); it[1] = (v shr 8).toByte() }
        assertEquals(0.5f, PipeWireSound.level(minus30, 800), 0.01f)
    }
}
