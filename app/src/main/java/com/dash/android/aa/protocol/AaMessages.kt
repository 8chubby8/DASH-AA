package com.dash.android.aa.protocol

/**
 * The Android Auto vocabulary DASH-AA speaks — every channel, message id, enum and message body, in
 * one file, so each protocol number is written exactly once.
 *
 * **Source.** Field numbers and ids follow the `.proto` files in aasdk's `aasdk_proto` folder (f1xpl/aasdk, GPL-3.0), the
 * protocol description the open-source head units (openauto, Crankshaft) are built on. Where DASH-AA
 * goes beyond aasdk — portrait and 1440p video modes — the comment says so, because those numbers come
 * from later protocol revisions and have not been hardware-verified here.
 */
object Aa {

    // ---- Channels. Fixed numbering, as aasdk's ChannelId: the phone opens each one we advertise. ----
    const val CH_CONTROL = 0
    const val CH_INPUT = 1
    const val CH_SENSOR = 2
    const val CH_VIDEO = 3
    const val CH_MEDIA_AUDIO = 4
    const val CH_SPEECH_AUDIO = 5
    const val CH_SYSTEM_AUDIO = 6
    const val CH_AV_INPUT = 7
    const val CH_BLUETOOTH = 8

    fun channelName(ch: Int) = when (ch) {
        CH_CONTROL -> "control"; CH_INPUT -> "input"; CH_SENSOR -> "sensor"; CH_VIDEO -> "video"
        CH_MEDIA_AUDIO -> "media-audio"; CH_SPEECH_AUDIO -> "speech-audio"; CH_SYSTEM_AUDIO -> "system-audio"
        CH_AV_INPUT -> "mic"; CH_BLUETOOTH -> "bluetooth"; else -> "ch$ch"
    }

    // ---- Frame header flags (aasdk FrameType / EncryptionType / MessageType). ----
    const val FRAME_FIRST = 0x01
    const val FRAME_LAST = 0x02
    const val FRAME_BULK = 0x03
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08
    /** Largest plaintext payload one frame carries; longer messages are split FIRST/MIDDLE/LAST. */
    const val MAX_FRAME_PAYLOAD = 0x4000

    // ---- Control channel message ids. ----
    const val VERSION_REQUEST = 0x0001
    const val VERSION_RESPONSE = 0x0002
    const val SSL_HANDSHAKE = 0x0003
    const val AUTH_COMPLETE = 0x0004
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000b
    const val PING_RESPONSE = 0x000c
    const val NAVIGATION_FOCUS_REQUEST = 0x000d
    const val NAVIGATION_FOCUS_RESPONSE = 0x000e
    const val SHUTDOWN_REQUEST = 0x000f
    const val SHUTDOWN_RESPONSE = 0x0010
    const val VOICE_SESSION_REQUEST = 0x0011
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013

    // ---- Audio/video channel message ids. ----
    const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    const val AV_MEDIA = 0x0001
    const val AV_SETUP_REQUEST = 0x8000
    const val AV_START = 0x8001
    const val AV_STOP = 0x8002
    const val AV_SETUP_RESPONSE = 0x8003
    const val AV_MEDIA_ACK = 0x8004
    const val AV_INPUT_OPEN_REQUEST = 0x8005
    const val AV_INPUT_OPEN_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008

    // ---- Sensor and input channel message ids. ----
    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT = 0x8003
    const val INPUT_EVENT = 0x8001
    const val BINDING_REQUEST = 0x8002
    const val BINDING_RESPONSE = 0x8003

    // ---- Bluetooth channel message ids. ----
    const val BT_PAIRING_REQUEST = 0x8001
    const val BT_PAIRING_RESPONSE = 0x8002
    const val BT_AUTH_DATA = 0x8003
    const val BT_PAIRING_A2DP = 2
    const val BT_PAIRING_HFP = 4
    const val BT_PAIRING_STATUS_OK = 1

    // ---- Enums. ----
    const val STATUS_OK = 0
    const val AV_SETUP_OK = 2
    const val STREAM_AUDIO = 1
    const val STREAM_VIDEO = 3
    const val AUDIO_SPEECH = 1
    const val AUDIO_SYSTEM = 2
    const val AUDIO_MEDIA = 3
    const val VIDEO_FOCUSED = 1
    const val VIDEO_UNFOCUSED = 2
    const val FPS_30 = 1
    const val FPS_60 = 2

    const val SENSOR_CAR_SPEED = 3
    const val SENSOR_RPM = 4
    const val SENSOR_PARKING_BRAKE = 7
    const val SENSOR_GEAR = 8
    const val SENSOR_NIGHT = 10
    const val SENSOR_DRIVING_STATUS = 13

    const val GEAR_NEUTRAL = 0
    const val GEAR_DRIVE = 100
    const val GEAR_PARK = 101
    const val GEAR_REVERSE = 102

    const val AUDIO_FOCUS_GAIN = 1
    const val AUDIO_FOCUS_GAIN_TRANSIENT = 2
    const val AUDIO_FOCUS_GAIN_NAVI = 3
    const val AUDIO_FOCUS_RELEASE = 4
    const val AUDIO_FOCUS_STATE_GAIN = 1
    const val AUDIO_FOCUS_STATE_GAIN_TRANSIENT = 2
    const val AUDIO_FOCUS_STATE_LOSS = 3
    const val AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY = 7

    /** Android MotionEvent actions, which is what the touch_action field carries. */
    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2
    const val TOUCH_POINTER_DOWN = 5
    const val TOUCH_POINTER_UP = 6

    /** Android KeyEvent codes for the keys DASH-AA can press on the phone's behalf. */
    const val KEY_HOME = 3
    const val KEY_BACK = 4
    const val KEY_SEARCH = 84          // starts the voice assistant (aasdk's MICROPHONE_1)
    const val KEY_MEDIA_PLAY_PAUSE = 85
    const val KEY_MEDIA_NEXT = 87
    const val KEY_MEDIA_PREVIOUS = 88
    const val KEY_MEDIA_PLAY = 126
    const val KEY_MEDIA_PAUSE = 127
    val SUPPORTED_KEYS = listOf(KEY_HOME, KEY_BACK, KEY_SEARCH, KEY_MEDIA_PLAY_PAUSE, KEY_MEDIA_NEXT,
        KEY_MEDIA_PREVIOUS, KEY_MEDIA_PLAY, KEY_MEDIA_PAUSE)
}

/**
 * The video modes Android Auto offers. The phone always renders one of these fixed frame sizes; DASH-AA
 * makes it fit any viewport shape with margins (see [VideoGeometry]).
 */
enum class AaVideoMode(val code: Int, val width: Int, val height: Int, val label: String, val verified: Boolean) {
    P480(1, 800, 480, "800 × 480", true),
    P720(2, 1280, 720, "1280 × 720", true),
    P1080(3, 1920, 1080, "1920 × 1080", true),
    // Later protocol revisions, beyond aasdk's enum — offered, not yet proven on hardware.
    P1440(4, 2560, 1440, "2560 × 1440", false),
    PORTRAIT_720(6, 720, 1280, "720 × 1280 portrait", false),
    PORTRAIT_1080(7, 1080, 1920, "1080 × 1920 portrait", false);

    val portrait: Boolean get() = height > width

    /** The same tier turned the other way — used when the viewport is taller than it is wide. */
    fun forPortrait(): AaVideoMode = when (this) {
        P480, P720 -> PORTRAIT_720
        P1080, P1440 -> PORTRAIT_1080
        else -> this
    }
}

/**
 * How a fixed-size Android Auto frame is fitted to the viewport **without letterboxing** (interface.md:
 * "apps always fill the viewport completely — DASH never letterboxes or pillarboxes").
 *
 * Android Auto lets a head unit declare margins: the phone then draws its interface into the centred
 * `(width - marginW) × (height - marginH)` area and leaves the rest of the frame empty. DASH-AA sets
 * the margins so that inner area has **exactly the viewport's aspect ratio**, crops the margins away,
 * and scales what is left to fill the viewport. The phone lays its interface out for the true shape —
 * nothing is stretched and nothing is cut off.
 */
data class VideoGeometry(
    val mode: AaVideoMode,
    val marginWidth: Int,
    val marginHeight: Int,
) {
    val contentWidth: Int get() = mode.width - marginWidth
    val contentHeight: Int get() = mode.height - marginHeight
    val contentLeft: Int get() = marginWidth / 2
    val contentTop: Int get() = marginHeight / 2

    companion object {
        fun fit(mode: AaVideoMode, viewportWidth: Int, viewportHeight: Int): VideoGeometry {
            if (viewportWidth <= 0 || viewportHeight <= 0) return VideoGeometry(mode, 0, 0)
            val viewportAspect = viewportWidth.toDouble() / viewportHeight
            val frameAspect = mode.width.toDouble() / mode.height
            // Margins are kept even, so the content area centres on whole pixels.
            return if (viewportAspect >= frameAspect) {
                val contentH = (mode.width / viewportAspect).toInt().coerceIn(1, mode.height)
                VideoGeometry(mode, 0, (mode.height - contentH) and 1.inv())
            } else {
                val contentW = (mode.height * viewportAspect).toInt().coerceIn(1, mode.width)
                VideoGeometry(mode, (mode.width - contentW) and 1.inv(), 0)
            }
        }
    }
}

/** Everything the service-discovery response declares about this head unit. */
data class HeadUnitConfig(
    val geometry: VideoGeometry,
    val fps: Int,                       // Aa.FPS_30 / FPS_60
    val dpi: Int,
    val leftHandDrive: Boolean,
    val sensors: List<Int>,
    val micEnabled: Boolean,
    val audioEnabled: Boolean,
    val softwareVersion: String,
    /** This machine's Bluetooth address, or null to offer no Bluetooth — then calls stay on the phone. */
    val bluetoothAddress: String? = null,
)

/** Encoders for every message DASH-AA sends, and readers for the ones it receives. */
object AaMessages {

    fun versionRequest(): ByteArray = byteArrayOf(0, 1, 0, 1)    // protocol 1.1, as aasdk

    fun authComplete(): ByteArray = proto { int(1, Aa.STATUS_OK) }

    fun serviceDiscoveryResponse(c: HeadUnitConfig): ByteArray = proto {
        // Input — the touchscreen is declared the size of the phone's *interface* (the content area,
        // margins excluded), and touches are given in that area's own coordinates. Correct whether the
        // phone reads a touch as interface pixels or scales it from the declared size; the 1.0.1 frame
        // mapping (margin offset added) was ~2% off in real use (Roger, 2026-10-05).
        message(1) {
            uint(1, Aa.CH_INPUT)
            message(4) {
                Aa.SUPPORTED_KEYS.forEach { uint(1, it) }
                message(2) { uint(1, c.geometry.contentWidth); uint(2, c.geometry.contentHeight) }
            }
        }
        // Sensors — only the ones DASH can honestly feed (see AaBridge).
        message(1) {
            uint(1, Aa.CH_SENSOR)
            message(2) { c.sensors.forEach { type -> message(1) { int(1, type) } } }
        }
        // Video.
        message(1) {
            uint(1, Aa.CH_VIDEO)
            message(3) {
                int(1, Aa.STREAM_VIDEO)
                bool(5, true)
                message(4) {
                    int(1, c.geometry.mode.code)
                    int(2, c.fps)
                    uint(3, c.geometry.marginWidth)
                    uint(4, c.geometry.marginHeight)
                    uint(5, c.dpi)
                }
            }
        }
        if (c.audioEnabled) {
            audioChannel(Aa.CH_MEDIA_AUDIO, Aa.AUDIO_MEDIA, 48000, 2)
            audioChannel(Aa.CH_SPEECH_AUDIO, Aa.AUDIO_SPEECH, 16000, 1)
            audioChannel(Aa.CH_SYSTEM_AUDIO, Aa.AUDIO_SYSTEM, 16000, 1)
        }
        if (c.micEnabled) {
            message(1) {
                uint(1, Aa.CH_AV_INPUT)
                message(5) {
                    int(1, Aa.STREAM_AUDIO)
                    message(2) { uint(1, 16000); uint(2, 16); uint(3, 1) }
                }
            }
        }
        // Bluetooth — how calls reach the car. Android Auto carries no call audio over USB: the phone
        // connects to the head unit as a Bluetooth hands-free kit, and only the call *screen* is
        // projected. Hands-free only: offering music over Bluetooth too would let the phone play twice.
        c.bluetoothAddress?.let { address ->
            message(1) {
                uint(1, Aa.CH_BLUETOOTH)
                message(6) {
                    string(1, address)
                    int(2, Aa.BT_PAIRING_HFP)
                }
            }
        }
        string(2, "DASH-AA")              // head_unit_name
        string(3, "DASH")                 // car_model
        string(4, "2026")                 // car_year
        string(5, "DASH-AA-0001")         // car_serial
        bool(6, c.leftHandDrive)          // left_hand_drive_vehicle
        string(7, "DASH")                 // headunit_manufacturer
        string(8, "DASH-AA")              // headunit_model
        string(9, c.softwareVersion)      // sw_build
        string(10, c.softwareVersion)     // sw_version
        bool(11, false)                   // can_play_native_media_during_vr
        bool(12, false)                   // hide_clock
    }

    private fun ProtoWriter.audioChannel(channel: Int, audioType: Int, rate: Int, channels: Int) = message(1) {
        uint(1, channel)
        message(3) {
            int(1, Aa.STREAM_AUDIO)
            int(2, audioType)
            message(3) { uint(1, rate); uint(2, 16); uint(3, channels) }
            bool(5, false)
        }
    }

    fun bluetoothPairingResponse(alreadyPaired: Boolean): ByteArray = proto {
        bool(1, alreadyPaired); int(2, Aa.BT_PAIRING_STATUS_OK)
    }

    fun channelOpenResponse(): ByteArray = proto { int(1, Aa.STATUS_OK) }

    fun avSetupResponse(maxUnacked: Int): ByteArray = proto {
        int(1, Aa.AV_SETUP_OK); uint(2, maxUnacked); uint(3, 0)
    }

    fun videoFocusIndication(focused: Boolean, unrequested: Boolean): ByteArray = proto {
        int(1, if (focused) Aa.VIDEO_FOCUSED else Aa.VIDEO_UNFOCUSED); bool(2, unrequested)
    }

    fun mediaAck(session: Int): ByteArray = proto { int(1, session); uint(2, 1) }

    fun sensorStartResponse(): ByteArray = proto { int(1, Aa.STATUS_OK) }

    fun bindingResponse(): ByteArray = proto { int(1, Aa.STATUS_OK) }

    fun pingRequest(timestamp: Long): ByteArray = proto { uint(1, timestamp) }
    fun pingResponse(timestamp: Long): ByteArray = proto { uint(1, timestamp) }

    fun navigationFocusResponse(): ByteArray = proto { uint(1, 2) }   // projected, as aasdk/openauto

    fun audioFocusResponse(state: Int): ByteArray = proto { int(1, state) }

    fun shutdownRequest(): ByteArray = proto { int(1, 1) }             // QUIT
    fun shutdownResponse(): ByteArray = ByteArray(0)

    fun avInputOpenResponse(): ByteArray = proto { int(1, 0); uint(2, 0) }

    // ---- Sensor events (SensorEventIndication: one repeated field per sensor type). ----
    fun drivingStatus(status: Int = 0): ByteArray = proto { message(13) { int(1, status) } }
    fun nightMode(night: Boolean): ByteArray = proto { message(10) { bool(1, night) } }
    /** Speed in metres per second × 1000 — the unit Android Auto expects. */
    fun speed(speedE3: Int): ByteArray = proto { message(3) { int(1, speedE3) } }
    fun gear(gear: Int): ByteArray = proto { message(8) { int(1, gear) } }
    fun parkingBrake(on: Boolean): ByteArray = proto { message(7) { bool(1, on) } }
    fun rpm(rpmE3: Int): ByteArray = proto { message(4) { int(1, rpmE3) } }

    // ---- Input events. ----
    data class Pointer(val id: Int, val x: Int, val y: Int)

    fun touchEvent(timestampMicros: Long, pointers: List<Pointer>, actionIndex: Int, action: Int): ByteArray = proto {
        uint(1, timestampMicros)
        message(3) {
            pointers.forEach { p -> message(1) { uint(1, p.x); uint(2, p.y); uint(3, p.id) } }
            uint(2, actionIndex)
            int(3, action)
        }
    }

    fun keyEvent(timestampMicros: Long, keyCode: Int, pressed: Boolean): ByteArray = proto {
        uint(1, timestampMicros)
        message(4) {
            message(1) { uint(1, keyCode); bool(2, pressed); uint(3, 0); bool(4, false) }
        }
    }
}
