package com.dash.android.aa.protocol

import android.util.Log
import java.io.EOFException
import java.nio.ByteBuffer

/** What a session needs from the head unit around it — the media sinks and the car's state. */
interface AaSessionHost {
    fun onProgress(text: String)
    fun onProjecting()

    fun onVideoStart()
    fun onVideoData(data: ByteArray, offset: Int, length: Int)
    fun onVideoStop()

    fun onAudioStart(channel: Int)
    /**
     * Audio for [channel]. Return true to take responsibility for acknowledging it — by calling
     * [AaSession.ackMedia] once the sound has gone to the device — or false to have it acknowledged now.
     */
    fun onAudioData(channel: Int, data: ByteArray, offset: Int, length: Int): Boolean
    fun onAudioStop(channel: Int)

    fun onMicrophone(open: Boolean)

    /** The phone asked for a sensor; return the first event to send, or null to send nothing yet. */
    fun onSensorRequested(type: Int): ByteArray?

    /** The phone asks whether it is already paired with this head unit over Bluetooth. */
    fun onBluetoothPairing(phoneAddress: String, method: Int): Boolean = false
}

/**
 * One Android Auto projection session, from version exchange to shutdown, over one [AaLink].
 *
 * The conversation, head unit's side (aasdk/openauto's sequence):
 * 1. send `VERSION_REQUEST` (1.1); the phone answers with its version and a match status;
 * 2. run the TLS handshake inside `SSL_HANDSHAKE` messages, then send `AUTH_COMPLETE`;
 * 3. answer `SERVICE_DISCOVERY_REQUEST` with what this head unit is ([HeadUnitConfig]);
 * 4. answer each `CHANNEL_OPEN_REQUEST` — the phone opens every channel that was advertised;
 * 5. on each media channel answer `SETUP`, and acknowledge every packet (the phone stops sending when
 *    acknowledgements stop — the flow control is the ack);
 * 6. answer pings, focus requests and the phone's shutdown.
 *
 * [run] blocks on the calling thread until the session ends; everything else ([sendTouch], [sendKey],
 * [sendSensor], [sendMicrophone], [requestShutdown]) is safe from any thread.
 */
class AaSession(
    private val link: AaLink,
    private val config: HeadUnitConfig,
    private val host: AaSessionHost,
    tlsDataDir: java.io.File?,
) {
    private val tls = AaTls.headUnit(tlsDataDir)
    private val messenger = AaMessenger(link, tls)

    @Volatile private var authenticated = false
    /** The phone has replied at all — Android Auto is running on it. */
    @Volatile var answered = false
        private set
    @Volatile private var ended = false
    @Volatile var projecting = false
        private set

    /** Per media channel: the session id from START, which every ACK must echo. */
    private val avSessions = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val activeSensors = mutableSetOf<Int>()
    @Volatile private var micOpen = false

    /** Why the session ended — read after [run] returns. */
    @Volatile var endReason: String = "ended"
        private set

    fun run() {
        val pinger = Thread({ pingLoop() }, "dash-aa-ping").apply { isDaemon = true }
        try {
            host.onProgress("Negotiating with the phone…")
            messenger.send(Aa.CH_CONTROL, Aa.VERSION_REQUEST, AaMessages.versionRequest(), encrypted = false)
            pinger.start()
            while (!ended) dispatch(messenger.receive())
        } catch (e: EOFException) {
            if (!ended) endReason = "the phone disconnected"
        } catch (e: Exception) {
            if (!ended) {
                endReason = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "session failed", e)
            }
        } finally {
            ended = true
            pinger.interrupt()
            if (micOpen) host.onMicrophone(false)
            avSessions.keys.toList().forEach { stopMedia(it) }
            runCatching { link.close() }
        }
    }

    private fun dispatch(m: AaMessage) {
        if (m.channel == Aa.CH_CONTROL) return control(m)
        if (m.control && m.id == Aa.CHANNEL_OPEN_REQUEST) {
            Log.i(TAG, "phone opened ${Aa.channelName(m.channel)}")
            messenger.send(m.channel, Aa.CHANNEL_OPEN_RESPONSE, AaMessages.channelOpenResponse(), control = true)
            return
        }
        when (m.channel) {
            Aa.CH_VIDEO, Aa.CH_MEDIA_AUDIO, Aa.CH_SPEECH_AUDIO, Aa.CH_SYSTEM_AUDIO -> media(m)
            Aa.CH_AV_INPUT -> microphone(m)
            Aa.CH_SENSOR -> sensor(m)
            Aa.CH_INPUT -> input(m)
            Aa.CH_BLUETOOTH -> bluetooth(m)
            else -> unhandled(m)
        }
    }

    // ---- Control channel ----

    private fun control(m: AaMessage) {
        when (m.id) {
            Aa.VERSION_RESPONSE -> {
                answered = true
                val b = ByteBuffer.wrap(m.payload)
                val major = b.short.toInt(); val minor = b.short.toInt()
                val status = if (b.remaining() >= 2) b.short.toInt() and 0xFFFF else 0
                Log.i(TAG, "phone speaks Android Auto $major.$minor (status $status)")
                if (status == 0xFFFF) fail("the phone refused this protocol version")
                host.onProgress("Securing the connection…")
                sendHandshake(tls.handshake(null))
            }
            Aa.SSL_HANDSHAKE -> {
                sendHandshake(tls.handshake(m.payload))
                if (tls.handshakeComplete && !authenticated) {
                    authenticated = true
                    messenger.send(Aa.CH_CONTROL, Aa.AUTH_COMPLETE, AaMessages.authComplete(), encrypted = false)
                    Log.i(TAG, "TLS complete — authenticated")
                }
            }
            Aa.SERVICE_DISCOVERY_REQUEST -> {
                Log.i(TAG, "service discovery: ${config.geometry.mode.label}, margins " +
                    "${config.geometry.marginWidth}×${config.geometry.marginHeight}, ${config.dpi} dpi")
                host.onProgress("Starting Android Auto…")
                messenger.send(Aa.CH_CONTROL, Aa.SERVICE_DISCOVERY_RESPONSE, AaMessages.serviceDiscoveryResponse(config))
            }
            Aa.PING_REQUEST -> {
                val ts = ProtoMessage.parse(m.payload).long(1) ?: 0L
                messenger.send(Aa.CH_CONTROL, Aa.PING_RESPONSE, AaMessages.pingResponse(ts))
            }
            Aa.PING_RESPONSE -> Unit
            Aa.NAVIGATION_FOCUS_REQUEST ->
                messenger.send(Aa.CH_CONTROL, Aa.NAVIGATION_FOCUS_RESPONSE, AaMessages.navigationFocusResponse())
            Aa.AUDIO_FOCUS_REQUEST -> {
                val type = ProtoMessage.parse(m.payload).int(1) ?: Aa.AUDIO_FOCUS_GAIN
                // The head unit grants what the phone asks for, in kind: a transient request gets a
                // transient grant, not a permanent one, so the phone's own focus logic stays honest.
                // A release is acknowledged as a loss — the phone gave the sound back.
                val state = when (type) {
                    Aa.AUDIO_FOCUS_RELEASE -> Aa.AUDIO_FOCUS_STATE_LOSS
                    Aa.AUDIO_FOCUS_GAIN_TRANSIENT, Aa.AUDIO_FOCUS_GAIN_NAVI -> Aa.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
                    else -> Aa.AUDIO_FOCUS_STATE_GAIN
                }
                Log.i(TAG, "audio focus: phone asks ${focusName(type)} → ${if (state == Aa.AUDIO_FOCUS_STATE_LOSS) "loss" else if (state == Aa.AUDIO_FOCUS_STATE_GAIN) "gain" else "transient gain"}")
                messenger.send(Aa.CH_CONTROL, Aa.AUDIO_FOCUS_RESPONSE, AaMessages.audioFocusResponse(state))
            }
            Aa.VOICE_SESSION_REQUEST -> Log.i(TAG, "voice session ${ProtoMessage.parse(m.payload)}")
            Aa.SHUTDOWN_REQUEST -> {
                messenger.send(Aa.CH_CONTROL, Aa.SHUTDOWN_RESPONSE, AaMessages.shutdownResponse())
                endReason = "the phone ended Android Auto"
                ended = true
            }
            Aa.SHUTDOWN_RESPONSE -> { endReason = "restarting"; ended = true }
            else -> unhandled(m)
        }
    }

    private fun sendHandshake(bytes: ByteArray) {
        if (bytes.isNotEmpty()) messenger.send(Aa.CH_CONTROL, Aa.SSL_HANDSHAKE, bytes, encrypted = false)
    }

    // ---- Audio and video ----

    private fun media(m: AaMessage) {
        when (m.id) {
            Aa.AV_SETUP_REQUEST -> {
                messenger.send(m.channel, Aa.AV_SETUP_RESPONSE, AaMessages.avSetupResponse(maxUnacked = 1))
                if (m.channel == Aa.CH_VIDEO) {
                    messenger.send(m.channel, Aa.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(true, false))
                }
            }
            Aa.AV_START -> {
                val session = ProtoMessage.parse(m.payload).int(1) ?: 0
                avSessions[m.channel] = session
                if (m.channel == Aa.CH_VIDEO) {
                    host.onVideoStart()
                    if (!projecting) { projecting = true; host.onProjecting() }
                } else {
                    Log.i(TAG, "${Aa.channelName(m.channel)} started (session $session)")
                    audioStats[m.channel] = AudioStats()
                    host.onAudioStart(m.channel)
                }
            }
            Aa.AV_STOP -> stopMedia(m.channel)
            Aa.AV_MEDIA_WITH_TIMESTAMP, Aa.AV_MEDIA -> {
                val skip = if (m.id == Aa.AV_MEDIA_WITH_TIMESTAMP) 8 else 0
                if (m.payload.size > skip) {
                    if (m.channel == Aa.CH_VIDEO) host.onVideoData(m.payload, skip, m.payload.size - skip)
                    else {
                        audioStats[m.channel]?.let { it.packets++; it.bytes += m.payload.size - skip }
                        if (host.onAudioData(m.channel, m.payload, skip, m.payload.size - skip)) return
                    }
                }
                ackMedia(m.channel)
            }
            Aa.VIDEO_FOCUS_REQUEST ->
                messenger.send(m.channel, Aa.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(true, false))
            else -> unhandled(m)
        }
    }

    private fun stopMedia(channel: Int) {
        if (avSessions.remove(channel) == null) return
        if (channel != Aa.CH_VIDEO) {
            val st = audioStats.remove(channel)
            val secs = (System.currentTimeMillis() - (st?.since ?: 0L)) / 1000.0
            val rate = if (channel == Aa.CH_MEDIA_AUDIO) 192_000.0 else 32_000.0     // bytes per second of sound
            Log.i(TAG, "${Aa.channelName(channel)} stopped after %.1fs: %d packets, %.1fs of sound, %d acknowledged"
                .format(secs, st?.packets ?: 0, (st?.bytes ?: 0L) / rate, st?.acks ?: 0))
        }
        if (channel == Aa.CH_VIDEO) host.onVideoStop() else host.onAudioStop(channel)
    }

    // ---- Microphone ----

    private fun microphone(m: AaMessage) {
        when (m.id) {
            Aa.AV_INPUT_OPEN_REQUEST -> {
                val open = ProtoMessage.parse(m.payload).bool(1) ?: false
                messenger.send(m.channel, Aa.AV_INPUT_OPEN_RESPONSE, AaMessages.avInputOpenResponse())
                if (open != micOpen) { micOpen = open; host.onMicrophone(open) }
            }
            Aa.AV_MEDIA_ACK -> Unit              // the phone acknowledging our microphone audio
            Aa.AV_SETUP_REQUEST -> messenger.send(m.channel, Aa.AV_SETUP_RESPONSE, AaMessages.avSetupResponse(1))
            else -> unhandled(m)
        }
    }

    // ---- Sensors and input ----

    private fun sensor(m: AaMessage) {
        if (m.id != Aa.SENSOR_START_REQUEST) { unhandled(m); return }
        val type = ProtoMessage.parse(m.payload).int(1) ?: return
        messenger.send(Aa.CH_SENSOR, Aa.SENSOR_START_RESPONSE, AaMessages.sensorStartResponse())
        synchronized(activeSensors) { activeSensors += type }
        host.onSensorRequested(type)?.let { messenger.send(Aa.CH_SENSOR, Aa.SENSOR_EVENT, it) }
    }

    private fun bluetooth(m: AaMessage) {
        if (m.id != Aa.BT_PAIRING_REQUEST) { unhandled(m); return }
        val req = ProtoMessage.parse(m.payload)
        val address = req.string(1).orEmpty()
        val paired = host.onBluetoothPairing(address, req.int(2) ?: 0)
        Log.i(TAG, "phone $address asks about Bluetooth pairing (method ${req.int(2)}) → ${if (paired) "already paired" else "not paired"}")
        messenger.send(Aa.CH_BLUETOOTH, Aa.BT_PAIRING_RESPONSE, AaMessages.bluetoothPairingResponse(paired))
    }

    private fun input(m: AaMessage) {
        if (m.id == Aa.BINDING_REQUEST) {
            messenger.send(Aa.CH_INPUT, Aa.BINDING_RESPONSE, AaMessages.bindingResponse())
        } else unhandled(m)
    }

    // ---- Diagnostics ----

    private val unhandledSeen = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * A message DASH-AA does not answer — logged at INFO, with its payload, the first few times each
     * kind arrives. A phone that waits on an answer it never gets typically gives up after a few seconds,
     * so these lines are where a "stops after N seconds" fault shows itself.
     */
    private fun unhandled(m: AaMessage) {
        val key = "${m.channel}/${m.id}/${m.control}"
        val n = unhandledSeen.merge(key, 1, Int::plus) ?: 1
        if (n > 5) return
        val hex = m.payload.take(48).joinToString("") { "%02x".format(it) }
        Log.i(TAG, "unhandled $m payload=$hex${if (m.payload.size > 48) "…" else ""} ${ProtoMessage.parse(m.payload)}")
    }

    private class AudioStats { var packets = 0; var bytes = 0L; var acks = 0; val since = System.currentTimeMillis() }
    private val audioStats = java.util.concurrent.ConcurrentHashMap<Int, AudioStats>()

    // ---- From the head unit ----

    /**
     * Acknowledge one media packet. Video is acknowledged on arrival; **audio only once it has gone to
     * the sound device**, by the host. The acknowledgement is the phone's only clock: acknowledge audio
     * on arrival and the phone believes the car is playing far faster than real time, runs its player's
     * buffer dry, and the player pauses — which is what Spotify did on the first real test.
     */
    fun ackMedia(channel: Int) {
        if (ended) return
        audioStats[channel]?.let { it.acks++ }
        runCatching { messenger.send(channel, Aa.AV_MEDIA_ACK, AaMessages.mediaAck(avSessions[channel] ?: 0)) }
    }

    private fun focusName(type: Int) = when (type) {
        Aa.AUDIO_FOCUS_GAIN -> "gain"; Aa.AUDIO_FOCUS_GAIN_TRANSIENT -> "transient"
        Aa.AUDIO_FOCUS_GAIN_NAVI -> "transient (may duck)"; Aa.AUDIO_FOCUS_RELEASE -> "release"; else -> "type $type"
    }

    fun sendTouch(pointers: List<AaMessages.Pointer>, actionIndex: Int, action: Int) {
        if (!projecting || ended) return
        runCatching {
            messenger.send(Aa.CH_INPUT, Aa.INPUT_EVENT, AaMessages.touchEvent(micros(), pointers, actionIndex, action))
        }
    }

    fun sendKey(keyCode: Int) {
        if (!projecting || ended) return
        runCatching {
            messenger.send(Aa.CH_INPUT, Aa.INPUT_EVENT, AaMessages.keyEvent(micros(), keyCode, true))
            messenger.send(Aa.CH_INPUT, Aa.INPUT_EVENT, AaMessages.keyEvent(micros(), keyCode, false))
        }
    }

    /** Send a sensor event, but only for a sensor the phone has started — never unsolicited. */
    fun sendSensor(type: Int, event: ByteArray) {
        if (ended || synchronized(activeSensors) { type !in activeSensors }) return
        runCatching { messenger.send(Aa.CH_SENSOR, Aa.SENSOR_EVENT, event) }
    }

    fun sendMicrophone(pcm: ByteArray, length: Int) {
        if (!micOpen || ended) return
        val payload = ByteArray(8 + length)
        ByteBuffer.wrap(payload).putLong(micros())
        System.arraycopy(pcm, 0, payload, 8, length)
        runCatching { messenger.send(Aa.CH_AV_INPUT, Aa.AV_MEDIA_WITH_TIMESTAMP, payload) }
    }

    /** Ask the phone to end projection cleanly (used when the viewport changes shape). */
    fun requestShutdown() {
        if (ended) return
        if (!authenticated) { close("restarting"); return }
        runCatching { messenger.send(Aa.CH_CONTROL, Aa.SHUTDOWN_REQUEST, AaMessages.shutdownRequest()) }
            .onFailure { close("restarting") }
    }

    /** End the session now, from any thread; [run] returns. */
    fun close(reason: String) {
        if (ended) return
        endReason = reason
        ended = true
        runCatching { link.close() }
    }

    private fun pingLoop() {
        try {
            while (!ended) {
                Thread.sleep(PING_MS)
                if (authenticated && !ended) {
                    runCatching { messenger.send(Aa.CH_CONTROL, Aa.PING_REQUEST, AaMessages.pingRequest(micros())) }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun fail(reason: String): Nothing {
        endReason = reason
        throw IllegalStateException(reason)
    }

    private fun micros(): Long = System.nanoTime() / 1000

    private companion object {
        const val TAG = "DashAaSession"
        const val PING_MS = 1500L
    }
}
