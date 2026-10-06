package com.dash.android.aa

import com.dash.android.aa.input.TouchTracker
import com.dash.android.aa.media.FrameStore
import com.dash.android.aa.media.VideoDecoder
import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.AaLink
import com.dash.android.aa.protocol.AaMessage
import com.dash.android.aa.protocol.AaMessenger
import com.dash.android.aa.protocol.AaSession
import com.dash.android.aa.protocol.AaSessionHost
import com.dash.android.aa.protocol.AaTls
import com.dash.android.aa.protocol.AaVideoMode
import com.dash.android.aa.protocol.HeadUnitConfig
import com.dash.android.aa.protocol.ProtoMessage
import com.dash.android.aa.protocol.VideoGeometry
import com.dash.android.aa.protocol.proto
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole Android Auto session against a **fake phone** — no hardware needed.
 *
 * The fake plays the phone's side of the real protocol: answers the version request, runs a TLS
 * *server* that demands a client certificate (as a real phone does), asks for service discovery,
 * opens channels, starts a sensor and an input binding, then streams genuine H.264 (made by ffmpeg)
 * through the head unit's real decoder, receives a touch back, and finally shuts the session down.
 *
 * What it proves: framing (including messages split across frames), the TLS tunnel both ways, every
 * message body DASH-AA writes, the media acknowledgements, decode to frames, and touch coordinates
 * landing inside the content area past the margins. What it cannot prove: that a real phone's Android
 * Auto accepts DASH-AA — only a phone can say that.
 */
class FakePhoneTest {

    private class SocketLink(private val s: Socket, override val description: String) : AaLink {
        override fun read(buffer: ByteArray): Int = s.getInputStream().read(buffer)
        override fun write(data: ByteArray) { s.getOutputStream().write(data); s.getOutputStream().flush() }
        override fun close() { runCatching { s.close() } }
    }

    @Test
    fun `a full session against a fake phone`() {
        val tmp = createTempDirectory("dash-aa-test").toFile()
        val h264 = makeTestVideo(tmp)
        val server = ServerSocket(0)
        val phoneSocket = arrayOfNulls<Socket>(1)
        val accept = Thread { phoneSocket[0] = server.accept() }.apply { start() }
        val huSocket = Socket("127.0.0.1", server.localPort)
        accept.join()

        // A 1700 × 900 viewport — wider than 16:9, so the phone is told to leave margins top and bottom.
        val geometry = VideoGeometry.fit(AaVideoMode.P720, 1700, 900)
        assertEquals(0, geometry.marginWidth)
        assertTrue(geometry.marginHeight > 0, "a wide viewport must produce a vertical margin")
        val config = HeadUnitConfig(geometry, Aa.FPS_30, 160, false,
            listOf(Aa.SENSOR_DRIVING_STATUS, Aa.SENSOR_NIGHT), micEnabled = true, audioEnabled = true, softwareVersion = "test",
            bluetoothAddress = "84:9E:56:E4:7C:96")

        val frames = FrameStore()
        val audioReceived = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val sessionRef = arrayOfNulls<AaSession>(1)
        val decoder = VideoDecoder(geometry.mode.width, geometry.mode.height, frames)
        val projecting = CountDownLatch(1)
        val host = object : AaSessionHost {
            override fun onProgress(text: String) = Unit
            override fun onProjecting() = projecting.countDown()
            override fun onVideoStart() = decoder.start()
            override fun onVideoData(data: ByteArray, offset: Int, length: Int) = decoder.feed(data, offset, length)
            override fun onVideoStop() = decoder.stop()
            override fun onAudioStart(channel: Int) = Unit
            override fun onAudioData(channel: Int, data: ByteArray, offset: Int, length: Int): Boolean {
                audioReceived.add(length)
                // Play it "later", as the real sink does, and acknowledge only then.
                Thread { Thread.sleep(150); sessionRef[0]!!.ackMedia(channel) }.start()
                return true
            }
            override fun onAudioStop(channel: Int) = Unit
            override fun onMicrophone(open: Boolean) = Unit
            override fun onBluetoothPairing(phoneAddress: String, method: Int) = phoneAddress == "94:45:60:56:B3:CA"
            override fun onSensorRequested(type: Int): ByteArray? =
                if (type == Aa.SENSOR_NIGHT) proto { message(10) { bool(1, true) } } else proto { message(13) { int(1, 0) } }
        }
        val session = AaSession(SocketLink(huSocket, "test"), config, host, null)
        sessionRef[0] = session
        val sessionThread = Thread { session.run() }.apply { start() }

        // ---- The phone ----
        val phoneTls = phoneTls(tmp)
        val phone = AaMessenger(SocketLink(phoneSocket[0]!!, "phone"), phoneTls)

        val version = phone.receive()
        assertEquals(Aa.CH_CONTROL to Aa.VERSION_REQUEST, version.channel to version.id)
        assertEquals(listOf<Byte>(0, 1, 0, 1), version.payload.toList())
        phone.send(Aa.CH_CONTROL, Aa.VERSION_RESPONSE, byteArrayOf(0, 1, 0, 1, 0, 0), encrypted = false)

        // TLS: the head unit speaks first; keep answering until it says AUTH_COMPLETE.
        while (true) {
            val m = phone.receive()
            if (m.id == Aa.AUTH_COMPLETE) { assertEquals(0, ProtoMessage.parse(m.payload).int(1)); break }
            assertEquals(Aa.SSL_HANDSHAKE, m.id)
            val reply = phoneTls.handshake(m.payload)
            if (reply.isNotEmpty()) phone.send(Aa.CH_CONTROL, Aa.SSL_HANDSHAKE, reply, encrypted = false)
        }
        assertTrue(phoneTls.handshakeComplete)

        phone.send(Aa.CH_CONTROL, Aa.SERVICE_DISCOVERY_REQUEST, ByteArray(0))
        val discovery = phone.expect(Aa.CH_CONTROL, Aa.SERVICE_DISCOVERY_RESPONSE)
        val sd = ProtoMessage.parse(discovery.payload)
        assertEquals("DASH-AA", sd.string(2))
        val channels = sd.repeatedMessages(1)
        val video = channels.first { it.int(1) == Aa.CH_VIDEO }.message(3)!!.message(4)!!
        assertEquals(AaVideoMode.P720.code, video.int(1))
        assertEquals(geometry.marginHeight, video.int(4))
        assertTrue(channels.any { it.int(1) == Aa.CH_AV_INPUT }, "microphone channel advertised")
        val touchConfig = channels.first { it.int(1) == Aa.CH_INPUT }.message(4)!!.message(2)!!
        assertEquals(geometry.contentWidth, touchConfig.int(1))
        assertEquals(geometry.contentHeight, touchConfig.int(2))

        // Bluetooth: the head unit offers its address, hands-free only, and answers the pairing question.
        val bt = channels.first { it.int(1) == Aa.CH_BLUETOOTH }.message(6)!!
        assertEquals("84:9E:56:E4:7C:96", bt.string(1))
        assertEquals(listOf(Aa.BT_PAIRING_HFP.toLong()), bt.repeatedLong(2))
        phone.send(Aa.CH_BLUETOOTH, Aa.CHANNEL_OPEN_REQUEST, proto { int(1, 0); int(2, Aa.CH_BLUETOOTH) }, control = true)
        phone.expect(Aa.CH_BLUETOOTH, Aa.CHANNEL_OPEN_RESPONSE)
        phone.send(Aa.CH_BLUETOOTH, Aa.BT_PAIRING_REQUEST, proto { string(1, "94:45:60:56:B3:CA"); int(2, Aa.BT_PAIRING_HFP) })
        val pairing = ProtoMessage.parse(phone.expect(Aa.CH_BLUETOOTH, Aa.BT_PAIRING_RESPONSE).payload)
        assertEquals(true, pairing.bool(1))
        assertEquals(Aa.BT_PAIRING_STATUS_OK, pairing.int(2))

        for (ch in listOf(Aa.CH_INPUT, Aa.CH_SENSOR, Aa.CH_VIDEO)) {
            phone.send(ch, Aa.CHANNEL_OPEN_REQUEST, proto { int(1, 0); int(2, ch) }, control = true)
            val r = phone.expect(ch, Aa.CHANNEL_OPEN_RESPONSE)
            assertTrue(r.control)
        }

        phone.send(Aa.CH_SENSOR, Aa.SENSOR_START_REQUEST, proto { int(1, Aa.SENSOR_NIGHT) })
        phone.expect(Aa.CH_SENSOR, Aa.SENSOR_START_RESPONSE)
        val night = ProtoMessage.parse(phone.expect(Aa.CH_SENSOR, Aa.SENSOR_EVENT).payload)
        assertEquals(true, night.message(10)!!.bool(1))

        phone.send(Aa.CH_INPUT, Aa.BINDING_REQUEST, proto { int(1, Aa.KEY_HOME) })
        phone.expect(Aa.CH_INPUT, Aa.BINDING_RESPONSE)

        phone.send(Aa.CH_VIDEO, Aa.AV_SETUP_REQUEST, proto { uint(1, 0) })
        phone.expect(Aa.CH_VIDEO, Aa.AV_SETUP_RESPONSE)
        phone.expect(Aa.CH_VIDEO, Aa.VIDEO_FOCUS_INDICATION)
        phone.send(Aa.CH_VIDEO, Aa.AV_START, proto { int(1, 7); uint(2, 0) })
        assertTrue(projecting.await(5, TimeUnit.SECONDS))

        // Real H.264, in chunks up to 40 KB so some messages are split across frames.
        var acks = 0
        var offset = 0
        var sent = 0
        while (offset < h264.size) {
            val n = minOf(h264.size - offset, if (sent % 3 == 0) 40_000 else 6_000)
            val payload = ByteArray(8) + h264.copyOfRange(offset, offset + n)
            phone.send(Aa.CH_VIDEO, Aa.AV_MEDIA_WITH_TIMESTAMP, payload)
            val ack = ProtoMessage.parse(phone.expect(Aa.CH_VIDEO, Aa.AV_MEDIA_ACK).payload)
            assertEquals(7, ack.int(1), "acks echo the session id from START")
            acks++; offset += n; sent++
        }
        assertEquals(sent, acks)

        // Frames come out of the decoder at the full video size.
        val deadline = System.currentTimeMillis() + 10_000
        while (frames.tick.value < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(frames.tick.value >= 5, "decoded frames: ${frames.tick.value}")
        frames.withFrame { img -> assertEquals(1280, img!!.width); assertEquals(720, img.height) }

        // Audio is acknowledged only once played — the phone's clock (Spotify paused without it).
        phone.send(Aa.CH_MEDIA_AUDIO, Aa.CHANNEL_OPEN_REQUEST, proto { int(1, 0); int(2, Aa.CH_MEDIA_AUDIO) }, control = true)
        phone.expect(Aa.CH_MEDIA_AUDIO, Aa.CHANNEL_OPEN_RESPONSE)
        phone.send(Aa.CH_MEDIA_AUDIO, Aa.AV_SETUP_REQUEST, proto { uint(1, 0) })
        phone.expect(Aa.CH_MEDIA_AUDIO, Aa.AV_SETUP_RESPONSE)
        phone.send(Aa.CH_MEDIA_AUDIO, Aa.AV_START, proto { int(1, 9); uint(2, 0) })
        val sentAt = System.currentTimeMillis()
        phone.send(Aa.CH_MEDIA_AUDIO, Aa.AV_MEDIA_WITH_TIMESTAMP, ByteArray(8) + ByteArray(3840))
        val audioAck = ProtoMessage.parse(phone.expect(Aa.CH_MEDIA_AUDIO, Aa.AV_MEDIA_ACK).payload)
        assertEquals(9, audioAck.int(1))
        assertTrue(System.currentTimeMillis() - sentAt >= 140, "audio must not be acknowledged before it is played")
        assertEquals(listOf(3840), audioReceived.toList())

        // A tap in the middle of the viewport lands in the middle of the content area.
        val tracker = TouchTracker { p, i, a -> session.sendTouch(p, i, a) }.apply { this.geometry = geometry }
        tracker.down(1, 0.5f, 0.5f)
        val touch = ProtoMessage.parse(phone.expect(Aa.CH_INPUT, Aa.INPUT_EVENT).payload).message(3)!!
        val loc = touch.message(1)!!
        assertEquals(Aa.TOUCH_DOWN, touch.int(3))
        assertEquals(639, loc.int(1))
        assertEquals((geometry.contentHeight - 1) / 2, loc.int(2))
        tracker.up(1)
        assertEquals(Aa.TOUCH_UP, ProtoMessage.parse(phone.expect(Aa.CH_INPUT, Aa.INPUT_EVENT).payload).message(3)!!.int(3))

        // The phone ends Android Auto; the head unit answers and the session ends with that reason.
        phone.send(Aa.CH_CONTROL, Aa.SHUTDOWN_REQUEST, proto { int(1, 1) })
        phone.expect(Aa.CH_CONTROL, Aa.SHUTDOWN_RESPONSE)
        sessionThread.join(5000)
        assertEquals("the phone ended Android Auto", session.endReason)
        decoder.stop()
        server.close()
    }

    /** Waits for one specific message, answering pings on the way as a phone would. */
    private fun AaMessenger.expect(channel: Int, id: Int): AaMessage {
        while (true) {
            val m = receive()
            if (m.channel == Aa.CH_CONTROL && m.id == Aa.PING_REQUEST) continue
            if (m.channel == channel && m.id == id) return m
            error("expected ${Aa.channelName(channel)} 0x%04x, got $m".format(id))
        }
    }

    /** A TLS *server* with a throwaway certificate that, like a real phone, requires a client certificate. */
    private fun phoneTls(dir: File): AaTls {
        val store = File(dir, "phone.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "phone", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", store.absolutePath, "-storepass", "secret",
            "-dname", "CN=fake-phone", "-validity", "2").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); check(p.waitFor() == 0)
        val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "secret".toCharArray()) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                check(chain?.first()?.subjectX500Principal?.name?.contains("JVC Kenwood") == true) { "not the head-unit certificate" }
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, arrayOf(trustAll), null) }
        val engine = ctx.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        return AaTls(engine)
    }

    private fun makeTestVideo(dir: File): ByteArray {
        val out = File(dir, "test.h264")
        val p = ProcessBuilder(VideoDecoder.ffmpegPath()!!, "-hide_banner", "-loglevel", "error", "-f", "lavfi",
            "-i", "testsrc=size=1280x720:rate=30", "-frames:v", "20", "-c:v", "libx264", "-profile:v", "baseline",
            "-pix_fmt", "yuv420p", "-f", "h264", out.absolutePath).redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); check(p.waitFor() == 0) { "ffmpeg could not make test video" }
        return out.readBytes()
    }
}
