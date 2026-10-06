package com.dash.android.aa.media

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (-Dsound=1), real PipeWire: two streams labelled like a phone's call (as WirePlumber made them
 * on Roger's recorded call) must be moved through the echo canceller, and it must all go when they do.
 */
class CallEchoProbe {
    private val phone = "AA:BB:CC:00:11:22"

    private fun links(): List<Pair<String, String>> {
        val p = ProcessBuilder("pw-dump").start()
        val d = kotlinx.serialization.json.Json.parseToJsonElement(p.inputStream.bufferedReader().readText()) as kotlinx.serialization.json.JsonArray
        fun s(e: kotlinx.serialization.json.JsonElement?, vararg path: String): String? {
            var x = e; for (k in path) x = (x as? kotlinx.serialization.json.JsonObject)?.get(k)
            return (x as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        val names = d.filter { s(it, "type")?.endsWith("Node") == true }.associate { s(it, "id") to s(it, "info", "props", "node.name") }
        return d.filter { s(it, "type")?.endsWith("Link") == true }.map {
            names[s(it, "info", "output-node-id")].orEmpty() to names[s(it, "info", "input-node-id")].orEmpty()
        }
    }

    @Test fun `a call is moved through the echo canceller and released after`() {
        if (System.getProperty("sound") == null) return
        val calls = CallAudio(callVolume = { 2.5f }).apply { phoneAddress = phone; start() }
        // Its own application id: WirePlumber remembers a volume per application, and a boost on a test
        // stream must never be remembered for anything real (it was once — for every "pw-cat" stream).
        val props = "api.bluez5.address=\"$phone\" api.bluez5.profile=headset-audio-gateway " +
            "application.name=dash-aa-test application.id=dash-aa-test.call"
        val voice = ProcessBuilder("pw-cat", "--playback", "--raw", "--format", "s16", "--rate", "16000", "--channels", "1",
            "--media-role", "Communication", "-P", "node.name=fake-call-voice $props", "-").start()
        Thread { runCatching { val z = ByteArray(640); while (true) { voice.outputStream.write(z); voice.outputStream.flush(); Thread.sleep(20) } } }.start()
        val mic = ProcessBuilder("pw-cat", "--record", "--raw", "--format", "s16", "--rate", "16000", "--channels", "1",
            "--media-role", "Communication", "-P", "node.name=fake-call-mic $props", "-").start()
        Thread { runCatching { mic.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } }.start()

        var during = emptyList<Pair<String, String>>()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500)
            during = links()
            if (("fake-call-voice" to "dash-aa-ec-sink") in during && ("dash-aa-ec-source" to "fake-call-mic") in during) break
        }
        println("during the call:"); during.filter { "fake-call" in it.first + it.second || "dash-aa-ec" in it.first + it.second }.toSet().forEach { println("  $it") }
        assertTrue(("fake-call-voice" to "dash-aa-ec-sink") in during, "voice goes through the canceller")
        assertTrue(("dash-aa-ec-source" to "fake-call-mic") in during, "the phone hears the cleaned microphone")

        // The call volume is on the voice stream itself — before the canceller, so it subtracts the louder sound.
        val voiceId = run {
            val d = kotlinx.serialization.json.Json.parseToJsonElement(ProcessBuilder("pw-dump").start().inputStream.bufferedReader().readText()) as kotlinx.serialization.json.JsonArray
            d.firstOrNull { o ->
                val props = ((o as? kotlinx.serialization.json.JsonObject)?.get("info") as? kotlinx.serialization.json.JsonObject)?.get("props") as? kotlinx.serialization.json.JsonObject
                (props?.get("node.name") as? kotlinx.serialization.json.JsonPrimitive)?.content == "fake-call-voice"
            }?.let { ((it as kotlinx.serialization.json.JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content }
        }
        val vol = ProcessBuilder("wpctl", "get-volume", voiceId ?: "0").start().inputStream.bufferedReader().readText()
        println("voice stream $voiceId: $vol")
        assertTrue(vol.contains("2.50"), "call volume boosted to 250% on the voice stream")

        voice.destroy(); mic.destroy()
        Thread.sleep(3_000)
        val after = links()
        println("after the call: ${after.filter { "dash-aa-ec" in it.first + it.second }.size} canceller links")
        assertTrue(after.none { "dash-aa-ec" in it.first + it.second }, "the canceller (and the microphone) is released")
        calls.stop()
    }
}
