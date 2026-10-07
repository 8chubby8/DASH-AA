package com.dash.android.aa

import android.util.Log
import com.dash.android.BuildConfig
import com.dash.android.DashApplication
import com.dash.android.aa.input.EvdevTouch
import com.dash.android.aa.input.TouchTracker
import com.dash.android.aa.media.AudioOut
import com.dash.android.aa.media.FrameStore
import com.dash.android.aa.media.Microphone
import com.dash.android.aa.media.VideoDecoder
import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.AaLink
import com.dash.android.aa.protocol.AaSession
import com.dash.android.aa.protocol.AaSessionHost
import com.dash.android.aa.protocol.HeadUnitConfig
import com.dash.android.aa.protocol.VideoGeometry
import com.dash.android.aa.usb.AaUsb
import com.dash.android.audio.ViewportVolume
import com.dash.android.transport.DashController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where Android Auto is, as the viewport and the settings tab show it. */
sealed interface AaStatus {
    data class Unavailable(val reason: String) : AaStatus
    data object Disabled : AaStatus
    data object WaitingForPhone : AaStatus
    data class NoPermission(val device: String) : AaStatus
    data class Switching(val device: String) : AaStatus
    data class Connecting(val device: String, val step: String) : AaStatus
    data class Projecting(val device: String, val geometry: VideoGeometry) : AaStatus
    /** The phone ended Android Auto itself. DASH-AA waits for a replug or a Reconnect — it does not
     *  reopen what the user just closed. */
    data class Ended(val reason: String) : AaStatus
    data class Retrying(val reason: String) : AaStatus
}

/**
 * **The viewport's tenant: the Android Auto head unit** (DASH-AA).
 *
 * Upstream's 1.7.x found that *"an ordinary Android app cannot tell another app where to draw"*, and
 * built a ladder of fallbacks around it. DASH-AA has no such problem: the phone sends DASH-AA a video
 * stream, and DASH-AA draws it into exactly the rectangle it measured — the space the system bar and
 * the resting module-panel assembly leave over, the same rectangle the settings blind rolls into.
 *
 * This class owns the whole of that: waiting for a phone, switching it into accessory mode, running
 * the [AaSession], and wiring its video, audio, microphone, touch and the car's state together. It lives
 * on [DashApplication] beside the module bus, for the same reason the bus does — a phone mid-projection
 * must never be dropped because a screen recomposed.
 *
 * **The viewport is measured at rest.** Upstream's rule (1.6.9): the screen is laid out for the panel's
 * resting state and an expanded panel is drawn *over* the viewport, so the running app never relayouts
 * when a panel opens. Here that rule saves a projection restart every time somebody glances at a module.
 * Only a real layout change — bar height or position, panel size, edge or visibility — changes the
 * viewport's shape, and only then is Android Auto restarted to fill the new shape exactly.
 */
class AndroidAutoHost(
    private val app: DashApplication,
    private val controller: DashController,
) : ViewportVolume {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = AaPreferences(app)

    @Volatile var settings: AaSettings = AaSettings()
        private set

    private val _status = MutableStateFlow<AaStatus>(AaStatus.WaitingForPhone)
    val status: StateFlow<AaStatus> = _status.asStateFlow()

    /** The decoded picture, drawn by the viewport. */
    val frames = FrameStore()

    private val audio = AudioOut()
    private val calls = com.dash.android.aa.media.CallAudio(echoCancel = { settings.echoCancel }, callVolume = { settings.callVolume })

    /** What the Android Auto tab says about calls — whether the phone can reach the laptop's Bluetooth. */
    private val _callsNote = MutableStateFlow("Not connected")
    val callsNote: StateFlow<String> = _callsNote.asStateFlow()

    /**
     * The desktop's sound system has stopped taking audio. Seen on 2026-10-05: WirePlumber's own script
     * crashed as a Bluetooth call ended and joined no new sound to the speakers until restarted.
     * Android Auto carries on in silence (see AudioOut); this lets the Android Auto tab offer the fix.
     */
    private val _soundStuck = MutableStateFlow(false)
    val soundStuck: StateFlow<Boolean> = _soundStuck.asStateFlow()

    /** Restart the desktop's audio session manager — only ever on the user's press. */
    fun restartSoundSystem() {
        _soundStuck.value = false
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "restarting WirePlumber at the user's request")
            runCatching { ProcessBuilder("systemctl", "--user", "restart", "wireplumber").start().waitFor() }
            kotlinx.coroutines.delay(2000)
            reconnect()       // reopen the streams against the fresh session
        }
    }
    @Volatile private var session: AaSession? = null
    private val mic = Microphone { pcm, n -> session?.sendMicrophone(pcm, n) }
    private val touch = TouchTracker { pointers, index, action -> session?.sendTouch(pointers, index, action) }
    private var decoder: VideoDecoder? = null

    private val bridge = AaBridge(state = controller.systemState, scope = scope)

    /** Android Auto's own volume, for the volume buttons when the user points them here (Audio › Output). */
    override fun stepVolume(direction: Int) {
        scope.launch { prefs.update { s -> s.copy(volume = (s.volume + direction * VOLUME_STEP).coerceIn(0f, 1f)) } }
    }

    override fun setMuted(muted: Boolean) { audio.muted = muted }

    // ---- The viewport, as the UI reports it ----

    /** The viewport in window pixels, as last laid out — the shape Android Auto is fitted to. */
    @Volatile private var viewportPx: Pair<Int, Int> = 0 to 0
    @Volatile private var viewportChangedAt = 0L

    /** The viewport and the monitor in screen coordinates, for mapping the touchscreen. */
    @Volatile private var viewportOnScreen: java.awt.Rectangle? = null
    @Volatile private var monitorOnScreen: java.awt.Rectangle? = null
    /** Areas of the viewport currently covered by DASH chrome (settings, an expanded panel), as
     *  fractions of the viewport — the touchscreen reader must not reach through them. */
    @Volatile private var covered: List<java.awt.geom.Rectangle2D.Float> = emptyList()
    @Volatile var coveredFully: Boolean = false
        private set

    private val evdev = EvdevTouch { kind, slot, sx, sy -> onScreenTouch(kind, slot, sx, sy) }
    @Volatile var multiTouch: Boolean = false
        private set
    private val evdevGestures = mutableSetOf<Long>()

    @Volatile private var restartRequested = false
    @Volatile private var debugHold = false
    @Volatile private var parked = false
    @Volatile private var reconnectNow = false
    @Volatile private var activeGeometry: VideoGeometry? = null
    @Volatile private var activeConnectionSettings: AaSettings? = null

    fun start() {
        audio.onStuck = { _soundStuck.value = true }
        scope.launch {
            prefs.settings.collect { s ->
                settings = s
                audio.volume = s.volume
                audio.duckMedia = s.duckMedia
                audio.musicLevel = s.musicLevel
                audio.directionsLevel = s.directionsLevel
                audio.systemLevel = s.systemLevel
                val active = activeConnectionSettings
                if (active != null && connectionFieldsDiffer(active, s)) requestRestart("settings changed")
                if (!s.enabled) session?.requestShutdown()
            }
        }
        multiTouch = runCatching { evdev.start() }.getOrDefault(false)
        Thread({ connectionLoop() }, "dash-aa-host").apply { isDaemon = true }.start()
    }

    /** The viewport's size in pixels, from its layout. Debounced: Android Auto restarts only once a new
     *  shape has held for [SETTLE_MS], so a panel animating to a new edge costs one restart, not forty. */
    fun updateViewport(widthPx: Int, heightPx: Int) {
        if (widthPx to heightPx == viewportPx) return
        viewportPx = widthPx to heightPx
        viewportChangedAt = System.currentTimeMillis()
    }

    fun updateScreenGeometry(viewport: java.awt.Rectangle?, monitor: java.awt.Rectangle?) {
        viewportOnScreen = viewport
        monitorOnScreen = monitor
    }

    fun updateCovered(fully: Boolean, areas: List<java.awt.geom.Rectangle2D.Float>) {
        coveredFully = fully
        covered = areas
        if (fully) touch.cancelAll()
    }

    private val pressedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** A touch from the window (mouse, touchpad, or a touchscreen's single emulated finger). */
    fun windowTouch(kind: EvdevTouch.Kind, pointer: Long, x: Float, y: Float) {
        // A touchscreen read directly also reaches the window as an emulated mouse; drop the echo.
        if (multiTouch && System.nanoTime() - evdev.lastActivityNanos < ECHO_WINDOW_NS) return
        when (kind) {
            EvdevTouch.Kind.DOWN -> { pressedAt[pointer] = System.currentTimeMillis(); touch.down(pointer, x, y) }
            EvdevTouch.Kind.MOVE -> touch.move(pointer, x, y)
            EvdevTouch.Kind.UP -> {
                // A trackpad's tap-to-click is a press and release in the same instant, which a phone's
                // gesture detector can reject as no touch at all. A finger is never that quick, so a
                // tap is held to at least [MIN_TAP_MS] before it lifts.
                val held = System.currentTimeMillis() - (pressedAt.remove(pointer) ?: 0L)
                if (held >= MIN_TAP_MS) touch.up(pointer)
                else scope.launch { kotlinx.coroutines.delay(MIN_TAP_MS - held); touch.up(pointer) }
            }
        }
    }

    // ---- Pinch from a mouse wheel or a trackpad's two-finger swipe ----

    private val pinchLock = Any()
    private var pinchSpread = 0f
    private var pinchCentre = 0.5f to 0.5f
    private var pinchEnd: Job? = null

    /**
     * Zoom by [steps] (positive in, negative out) about a point of the viewport. A laptop cannot hand a
     * real pinch to an XWayland window — GNOME keeps trackpad pinches for itself — but it does deliver
     * the two-finger swipe as scrolling. So DASH-AA turns scrolling into what a pinch *is* to the phone:
     * two fingers placed either side of the pointer, moving apart to zoom in or together to zoom out,
     * lifted a moment after the scrolling stops.
     */
    fun windowZoom(x: Float, y: Float, steps: Float) {
        if (steps == 0f) return
        synchronized(pinchLock) {
            if (pinchEnd == null) {
                pinchSpread = PINCH_START
                pinchCentre = x.coerceIn(PINCH_MAX, 1f - PINCH_MAX) to y
                touch.down(PINCH_A, pinchCentre.first - pinchSpread, y)
                touch.down(PINCH_B, pinchCentre.first + pinchSpread, y)
            }
            pinchSpread = (pinchSpread * kotlin.math.exp(steps * PINCH_RATE)).coerceIn(PINCH_MIN, PINCH_MAX)
            touch.move(PINCH_A, pinchCentre.first - pinchSpread, pinchCentre.second)
            touch.move(PINCH_B, pinchCentre.first + pinchSpread, pinchCentre.second)
            pinchEnd?.cancel()
            pinchEnd = scope.launch {
                kotlinx.coroutines.delay(PINCH_IDLE_MS)
                synchronized(pinchLock) {
                    touch.up(PINCH_B); touch.up(PINCH_A)
                    pinchEnd = null
                }
            }
        }
    }

    private fun onScreenTouch(kind: EvdevTouch.Kind, slot: Long, sx: Float, sy: Float) {
        val monitor = monitorOnScreen ?: return
        val vp = viewportOnScreen ?: return
        val px = monitor.x + sx * monitor.width
        val py = monitor.y + sy * monitor.height
        val vx = (px - vp.x) / vp.width
        val vy = (py - vp.y) / vp.height
        val key = EVDEV_KEY + slot
        when (kind) {
            EvdevTouch.Kind.DOWN -> {
                // Only a gesture that *starts* on uncovered viewport belongs to Android Auto.
                val inside = vx in 0f..1f && vy in 0f..1f && !coveredFully && covered.none { it.contains(vx.toDouble(), vy.toDouble()) }
                if (inside) { synchronized(evdevGestures) { evdevGestures += key }; touch.down(key, vx, vy) }
            }
            EvdevTouch.Kind.MOVE -> if (synchronized(evdevGestures) { key in evdevGestures }) touch.move(key, vx, vy)
            EvdevTouch.Kind.UP -> if (synchronized(evdevGestures) { evdevGestures.remove(key) }) touch.up(key)
        }
    }

    /** The geometry Android Auto would be told for the viewport as it stands — what a connection now
     *  would negotiate. */
    fun geometryForViewport(): VideoGeometry {
        val (w, h) = viewportPx
        return VideoGeometry.fit(if (h > w) settings.videoMode.forPortrait() else settings.videoMode, w, h)
    }

    /** Test hook: show [image] as if a phone were projecting with [geometry]. Never called by DASH-AA. */
    internal fun debugShowFrame(geometry: VideoGeometry, image: org.jetbrains.skia.Image) {
        debugHold = true
        _status.value = AaStatus.Projecting("debug", geometry)
        frames.publish(image)
    }

    /** Press a key on the phone — the settings tab's Home / Back, and the bridge's controls. */
    fun sendKey(code: Int) { session?.sendKey(code) }

    /** End a parked wait (or a running session) and connect afresh. */
    fun reconnect() {
        parked = false
        reconnectNow = true
        session?.requestShutdown()
    }

    fun requestRestart(why: String) {
        val s = session ?: return
        if (restartRequested) return
        Log.i(TAG, "restarting projection — $why")
        restartRequested = true
        s.requestShutdown()
        scope.launch {
            kotlinx.coroutines.delay(SHUTDOWN_GRACE_MS)
            if (session === s) s.close("restarting")
        }
    }

    // ---- The connection loop ----

    private fun connectionLoop() {
        if (!AaUsb.available()) {
            _status.value = AaStatus.Unavailable("libusb-1.0 is not installed, so DASH-AA cannot reach a phone on USB.")
            return
        }
        if (VideoDecoder.ffmpegPath() == null) {
            _status.value = AaStatus.Unavailable("ffmpeg is not installed, so Android Auto's video cannot be shown.")
            return
        }
        var backoff = 0L
        var lastSwitch = 0L
        while (true) {
            try {
                if (backoff > 0) { sleepUntilReconnect(backoff); backoff = 0 }
                reconnectNow = false
                if (debugHold) { Thread.sleep(POLL_MS); continue }
                if (!settings.enabled) { _status.value = AaStatus.Disabled; Thread.sleep(POLL_MS); continue }
                if (!viewportSettled()) { Thread.sleep(200); continue }

                val found = runCatching { AaUsb.scan() }.getOrNull()
                if (parked) {
                    if (found == null) parked = false               // unplugged — the next plug is a fresh start
                    Thread.sleep(POLL_MS)
                    continue
                }
                when (found) {
                    null -> { if (_status.value !is AaStatus.Retrying) _status.value = AaStatus.WaitingForPhone; Thread.sleep(POLL_MS) }
                    is AaUsb.Found.NoPermission -> { _status.value = AaStatus.NoPermission(found.id); Thread.sleep(2000) }
                    is AaUsb.Found.Phone -> {
                        if (System.currentTimeMillis() - lastSwitch < SWITCH_COOLDOWN_MS) { Thread.sleep(POLL_MS); continue }
                        _status.value = AaStatus.Switching(found.id)
                        lastSwitch = System.currentTimeMillis()
                        if (!AaUsb.switchToAccessory(found.key)) backoff = 5000
                        Thread.sleep(SWITCH_SETTLE_MS)
                    }
                    is AaUsb.Found.Accessory -> {
                        val link = AaUsb.openAccessory(found.key)
                        if (link == null) { backoff = 3000; continue }
                        val outcome = runSession(link, found.id)
                        link.release(reset = outcome != Outcome.PHONE_ENDED)
                        when (outcome) {
                            Outcome.PHONE_ENDED -> { parked = !reconnectNow }
                            Outcome.RESTART -> Thread.sleep(SWITCH_SETTLE_MS)
                            Outcome.FAILED -> backoff = 4000
                        }
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                Log.e(TAG, "connection loop", e)
                backoff = 5000
            }
        }
    }

    private enum class Outcome { RESTART, PHONE_ENDED, FAILED }

    private fun runSession(link: AaLink, deviceId: String): Outcome {
        val s = settings
        val (vw, vh) = viewportPx
        val mode = if (vh > vw) s.videoMode.forPortrait() else s.videoMode
        val geometry = VideoGeometry.fit(mode, vw, vh)
        val config = HeadUnitConfig(
            geometry = geometry,
            fps = if (s.fps60) Aa.FPS_60 else Aa.FPS_30,
            dpi = s.dpi,
            leftHandDrive = s.leftHandDrive,
            sensors = bridge.sensorsFor(s),
            micEnabled = s.microphone && Microphone.inputAvailable(),
            audioEnabled = s.audio && AudioOut.outputAvailable(),
            softwareVersion = BuildConfig.VERSION_NAME,
            // Offered only when this machine has a working Bluetooth radio — capability detection.
            bluetoothAddress = if (runCatching { com.dash.android.transport.bluetooth.linux.BlueZ.adapter() }.getOrNull() ==
                com.dash.android.transport.bluetooth.linux.BlueZ.Adapter.POWERED) com.dash.android.transport.bluetooth.linux.BlueZ.adapterAddress() else null,
        )
        _callsNote.value = if (config.bluetoothAddress == null) "No Bluetooth on this machine — calls stay on the phone"
            else "Waiting for the phone"
        restartRequested = false
        activeGeometry = geometry
        activeConnectionSettings = s
        touch.geometry = geometry
        _status.value = AaStatus.Connecting(deviceId, "Connecting…")

        val host = object : AaSessionHost {
            override fun onProgress(text: String) { _status.value = AaStatus.Connecting(deviceId, text) }
            override fun onProjecting() {
                _status.value = AaStatus.Projecting(deviceId, geometry)
                Log.i(TAG, "projecting ${geometry.mode.label} into ${vw}x$vh (content ${geometry.contentWidth}x${geometry.contentHeight})")
            }
            override fun onVideoStart() {
                decoder?.stop()
                decoder = VideoDecoder(geometry.mode.width, geometry.mode.height, frames).also { it.start() }
            }
            override fun onVideoData(data: ByteArray, offset: Int, length: Int) { decoder?.feed(data, offset, length) }
            override fun onVideoStop() { decoder?.stop(); decoder = null }
            override fun onAudioStart(channel: Int) = audio.start(channel)
            override fun onAudioData(channel: Int, data: ByteArray, offset: Int, length: Int): Boolean =
                audio.write(channel, data, offset, length) { session?.ackMedia(channel) }
            override fun onAudioStop(channel: Int) = audio.stop(channel)
            override fun onMicrophone(open: Boolean) { if (open) mic.open() else mic.close() }
            override fun onSensorRequested(type: Int): ByteArray? = bridge.eventFor(type, settings)
            override fun onBluetoothPairing(phoneAddress: String, method: Int): Boolean {
                val paired = com.dash.android.transport.bluetooth.linux.BlueZ.bondedDevices()
                    .any { it.address.equals(phoneAddress, ignoreCase = true) }
                calls.phoneAddress = phoneAddress
                calls.start()
                _callsNote.value = if (paired) "Phone paired — calls play through DASH-AA"
                    else "Phone not paired with this laptop — pair it once in GNOME Settings › Bluetooth"
                if (!paired) Log.i(TAG, "calls: phone $phoneAddress is not paired with this laptop — pair it in GNOME Settings › Bluetooth")
                return paired
            }
        }
        val sess = AaSession(link, config, host, app.filesDir.parentFile)
        session = sess
        val watchdog = scope.launch {
            // A phone that never answers at all (Android Auto not running on it) must not hold the loop
            // forever. Only the *first reply* is timed: once the phone has answered, it may legitimately
            // sit for minutes on its own "connect to this car?" prompts, waiting for its owner.
            kotlinx.coroutines.delay(ANSWER_TIMEOUT_MS)
            if (session === sess && !sess.answered) sess.close("the phone did not answer")
        }
        val viewportWatch = scope.launch { watchViewport(geometry, mode) }
        val bridgeJob: Job = bridge.attach({ settings }, { type, ev -> sess.sendSensor(type, ev) }, { sess.sendKey(it) })

        sess.run()

        watchdog.cancel(); viewportWatch.cancel(); bridgeJob.cancel()
        session = null
        activeConnectionSettings = null
        touch.cancelAll()
        mic.close()
        calls.stop()
        _callsNote.value = "Not connected"
        audio.stopAll()
        decoder?.stop(); decoder = null
        frames.clear()
        Log.i(TAG, "session ended: ${sess.endReason}")

        return when {
            restartRequested || reconnectNow -> Outcome.RESTART
            sess.endReason == "the phone ended Android Auto" -> {
                _status.value = AaStatus.Ended("Android Auto was closed on the phone.")
                Outcome.PHONE_ENDED
            }
            else -> {
                _status.value = AaStatus.Retrying(sess.endReason)
                Outcome.FAILED
            }
        }
    }

    /** Restart when the viewport settles into a shape the running projection does not fit. */
    private suspend fun watchViewport(active: VideoGeometry, mode: com.dash.android.aa.protocol.AaVideoMode) {
        while (true) {
            kotlinx.coroutines.delay(250)
            if (!viewportSettled()) continue
            val (w, h) = viewportPx
            val wanted = VideoGeometry.fit(if (h > w) settings.videoMode.forPortrait() else settings.videoMode, w, h)
            if (wanted != active) { requestRestart("viewport is now ${w}x$h"); return }
        }
    }

    private fun viewportSettled(): Boolean {
        val (w, h) = viewportPx
        return w > 0 && h > 0 && System.currentTimeMillis() - viewportChangedAt >= SETTLE_MS
    }

    private fun connectionFieldsDiffer(a: AaSettings, b: AaSettings) =
        a.enabled != b.enabled || a.videoMode != b.videoMode || a.fps60 != b.fps60 || a.dpi != b.dpi ||
            a.leftHandDrive != b.leftHandDrive || a.microphone != b.microphone || a.audio != b.audio ||
            a.nightSource != b.nightSource

    private fun sleepUntilReconnect(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !reconnectNow) Thread.sleep(100)
    }

    private companion object {
        const val TAG = "DashAndroidAuto"
        const val POLL_MS = 1000L
        const val SETTLE_MS = 1200L
        const val SWITCH_SETTLE_MS = 1500L
        const val SWITCH_COOLDOWN_MS = 4000L
        const val SHUTDOWN_GRACE_MS = 2500L
        const val ANSWER_TIMEOUT_MS = 10000L
        const val ECHO_WINDOW_NS = 400_000_000L
        const val EVDEV_KEY = 1_000_000L
        const val VOLUME_STEP = 0.1f
        const val MIN_TAP_MS = 80L
        const val PINCH_A = 2_000_001L
        const val PINCH_B = 2_000_002L
        const val PINCH_START = 0.08f
        const val PINCH_MIN = 0.01f
        const val PINCH_MAX = 0.45f
        const val PINCH_RATE = 0.25f
        const val PINCH_IDLE_MS = 250L
    }
}
