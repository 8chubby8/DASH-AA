package com.dash.android.audio.linux

import android.util.Log
import com.dash.android.audio.CarSound
import com.dash.android.audio.ProcessorState
import com.dash.android.audio.SoundControl
import com.dash.android.audio.SoundDevice
import com.dash.android.audio.SoundFeed
import com.dash.android.audio.SoundProcessor
import com.dash.android.audio.SoundSource
import com.dash.android.audio.SpeakerPosition
import com.dash.android.audio.callFeeds
import com.dash.android.audio.defaultOutput
import com.dash.android.audio.Loudness
import com.dash.android.audio.feeds
import com.dash.android.audio.outputDelays
import com.dash.android.audio.preGain
import com.dash.android.audio.speedBoostDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * **The car's sound through PipeWire** (DASH-AA 1.1.3) — DASH-AA's [SoundProcessor]: a car's DSP between
 * the head unit and its amplifiers, made of PipeWire's own filters.
 *
 * **PipeWire owns it; DASH only adjusts it.** It runs as two of the seat user's own services, each a
 * small `pipewire -c` process with a config file in `~/.config/pipewire/`. They start with PipeWire,
 * whether or not DASH runs, and if DASH stops the sound keeps playing with the last settings. Nothing
 * needs root.
 *
 * ```
 *  every app ─▶ "DASH" ─ anti-distortion, speed ─ equaliser ─ loudness ─┬─ low cut ─▶ left, right ─┐  dash-aa-sound
 *   (the default)                                                       └─ sub crossover ─▶ low ───┤  (never restarts)
 *  calls ─────▶ "DASH calls" ─ speed ──── low cut and sub crossover ─▶ left, right, low ────────────┤
 *                                                                                                  ▼
 *    the speaker layout: each output's mix of left, right and low, music and calls, then its delay ─▶ each device
 *                                                                                                    dash-aa-speakers
 * ```
 *
 * **What follows the volume and the speed** (1.1.4). Loudness's filters and speed volume's gain change
 * live, from the watch, as DASH's volume and the car's speed move: loudness is fitted to how far DASH's
 * volume is below the comfortable one ([Loudness]); speed volume rises and falls gently, a little each
 * watch, and never takes the sound past what full volume would be. Time alignment is a delay on each of
 * the layout's outputs.
 *
 * **Why two.** Apps play into the first, and its shape never changes. Every control in it (the equaliser,
 * the cutoffs) changes live. The second holds the layout, so choosing which device plays which speakers
 * restarts only it, for a second or so of silence. Restarting the first would be worse than silence:
 * whatever is playing falls back to a raw device, at full volume with no processing, and does not come
 * back (tried on PipeWire 1.6.9 and WirePlumber 0.5.17). So the first is never restarted while it runs.
 * The second takes the first's sound as a recording, which links whenever it starts; the other way round
 * it would never relink after a restart. The levels, balance, fade and where calls play change live too.
 *
 * **Calls have their own way in**, without the music's equaliser, so where they play is the user's choice
 * (Audio › Calls) and changes mid-call. Anything that says it is a call (`media.role` Phone or
 * Communication — DASH-AA's own call audio does) is sent that way while the chain runs.
 *
 * **Volume.** While Car sound is on, DASH's output is the machine's default, and its volume is the
 * volume. The layout's devices play at full, as amplifiers sit at their set gain. Turning Car sound on
 * gives DASH's output the volume the speakers had, and turning it off gives it back, so the loudness never
 * jumps.
 *
 * **If PipeWire restarts** (Roger, 2026-10-08: nothing out of the speakers until DASH has control again):
 * the layout's devices are muted the moment they reappear; DASH waits for its chain, moves anything that
 * landed straight on a speaker back onto its output, puts the volumes back, and only then unmutes.
 * [ProcessorState.ready] — the `sound_ready` signal — is false from the moment PipeWire goes until then,
 * and through every layout restart, so a sound module can keep its amplifiers quiet.
 *
 * Capability-detected: no PipeWire, no filter modules, or no systemd user manager to run the services,
 * and the state says which, in plain words.
 */
class PipeWireChain(
    private val sound: PipeWireSound,
    private val configDir: File = File(configHome(), "pipewire"),
    private val unitDir: File = File(configHome(), "systemd/user"),
) : SoundProcessor {

    private val _state = MutableStateFlow(ProcessorState(available = false, note = "Looking for PipeWire…"))
    override val state: StateFlow<ProcessorState> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dash-sound-chain").apply { isDaemon = true } }
    /** The latest settings not yet applied — a held stepper applies the last press, not every one. */
    private val pending = AtomicReference<Pair<CarSound, Boolean>?>(null)
    /** What was last applied, on the worker thread only; null until the first. */
    @Volatile private var applied: CarSound? = null
    @Volatile private var driverOnRight = false
    @Volatile private var started = false
    private var pipewire: String? = null
    private var pwDump: String? = null

    /** DASH's volume as last seen while all was well — what a PipeWire restart is put back to. */
    @Volatile private var lastVolume: Float? = null
    /** Calls already sent to "DASH calls", by node id. */
    private val routedCalls = HashSet<String>()

    /** The car's speed as last reported, km/h; null when nothing reports it. */
    @Volatile private var speedKmh: Float? = null
    /** Speed volume's rise as it is now, dB — moved toward where the speed says, a little each watch. */
    @Volatile private var speedDb = 0f
    /** Every running control as last set, "node/control" — so the watch sends only what has moved. */
    private val sent = HashMap<String, Float>()

    override fun start() {
        if (started) return
        started = true
        worker.execute { probe() }
        Thread({ watch() }, "dash-sound-chain-watch").apply { isDaemon = true }.start()
    }

    override fun apply(sound: CarSound, driverOnRight: Boolean) {
        pending.set(sound to driverOnRight)
        worker.execute {
            val (next, right) = pending.getAndSet(null) ?: return@execute
            this.driverOnRight = right
            runCatching { applyNow(next) }.onFailure { Log.w(TAG, "car sound not applied: ${it.message}") }
            applied = next
        }
    }

    override fun speed(kmh: Float?) {
        speedKmh = kmh
    }

    /** What the volume and the speed make of the sound now. */
    private fun live() = Live(volume = sound.state.value.outputs.firstOrNull { it.dash }?.volume, speedDb = speedDb)

    private fun probe() {
        pipewire = PipeWireSound.which("pipewire")
        pwDump = PipeWireSound.which("pw-dump")
        val modules = MODULE_DIRS.map { File(it) }.filter { it.isDirectory }
        val missing = when {
            pipewire == null || pwDump == null -> "PipeWire is not installed"
            // A directory found without the modules is an older PipeWire; none found, the distribution
            // keeps them somewhere else, and the services will say if they cannot start.
            modules.isNotEmpty() && modules.none { File(it, "libpipewire-module-combine-stream.so").exists() } ->
                "This PipeWire is too old — Car sound needs PipeWire 1.0 or newer"
            !systemctl("show-environment") -> "Car sound runs as your own services, and systemd's user manager is not running"
            else -> null
        }
        _state.value = if (missing != null) ProcessorState(false, note = missing)
        else ProcessorState(true, running = isActive(SOUND_UNIT), offers = OFFERS)
    }

    private fun publish(running: Boolean, ready: Boolean, note: String = "") {
        _state.value = ProcessorState(true, running = running, note = note, offers = OFFERS, ready = ready)
    }

    private fun applyNow(car: CarSound) {
        if (!_state.value.available) return
        if (!car.enabled) {
            // Only switching it off stops it. Settings that merely start off (a DASH with a fresh data
            // folder, a test) leave a running chain alone, and the sound with it.
            if (applied?.enabled == true) turnOff(applied!!)
            publish(running = isActive(SOUND_UNIT), ready = false)
            return
        }
        val wayIn = wayInConfig(car, live())
        val wayInFile = File(configDir, SOUND_CONF)
        val wayInBefore = wayInFile.takeIf { it.exists() }?.useLines { it.firstOrNull() }
        writeIfChanged(wayInFile, wayIn)
        val layout = layoutConfig(car, driverOnRight)
        val layoutFile = File(configDir, SPEAKERS_CONF)
        val layoutBefore = layoutFile.takeIf { it.exists() }?.useLines { it.firstOrNull() }
        if (layout != null) writeIfChanged(layoutFile, layout)
        if (writeIfChanged(File(unitDir, SOUND_UNIT), soundUnit()) or writeIfChanged(File(unitDir, SPEAKERS_UNIT), speakersUnit())) {
            systemctl("daemon-reload")
        }

        // A way in of an older shape (a DASH upgrade) is swapped as switching off and on would, so nothing blasts.
        if (isActive(SOUND_UNIT) && wayInBefore != wayIn.lineSequence().first()) {
            Log.i(TAG, "the way in has a new shape — swapping it")
            applied?.let { turnOff(it) } ?: turnOff(car)
        }
        val turningOn = !isActive(SOUND_UNIT)
        // What plays now, captured before DASH's output appears: WirePlumber may make it the default at once.
        val before = if (turningOn) sound.state.value.defaultOutput?.takeIf { !it.dash } else null
        if (turningOn) {
            publish(running = false, ready = false, note = "Starting…")
            synchronized(sent) { sent.clear() }
            if (layout != null) systemctl("enable", SPEAKERS_UNIT)
            systemctl("enable", "--now", SOUND_UNIT)   // the speakers follow it, once it is up
            if (awaitNode(SINK) == null) {
                publish(running = false, ready = false, note = "The sound chain did not start — journalctl --user -u $SOUND_UNIT says why")
                return
            }
        } else {
            setParams(SINK, wayInParams(car, live()))
            setParams(CALLS, callsInParams(car, live()))
        }

        val reshaped = layout != null && !turningOn && (!isActive(SPEAKERS_UNIT) || layoutBefore != layout.lineSequence().first())
        when {
            layout == null -> if (isActive(SPEAKERS_UNIT)) systemctl("disable", "--now", SPEAKERS_UNIT)
            reshaped -> {
                publish(running = true, ready = false, note = "Changing the speakers…")
                systemctl("enable", SPEAKERS_UNIT)
                systemctl("restart", SPEAKERS_UNIT)
            }
            turningOn -> Unit
            else -> {
                setParams(LAYOUT, layoutParams(car))
                setParams(LAYOUT_CALLS, callParams(car, driverOnRight))
            }
        }
        if (layout != null && (turningOn || reshaped)) awaitNode(LAYOUT)

        if (turningOn) handOver(car, before) else fullVolume(newDevices(car))
        publish(running = true, ready = layout != null,
            note = if (layout == null) "No speakers are chosen, so nothing plays" else "")
    }

    /**
     * DASH's output takes the volume the speakers had and becomes the default, then the layout's devices
     * go to full — in that order, so nothing is ever louder than it was. Done here, one command after
     * another, rather than queued: the order is the point.
     */
    private fun handOver(car: CarSound, before: SoundDevice?) {
        val dash = sound.nodeId(SINK) ?: return
        val v = before?.volume ?: DEFAULT_VOLUME
        wpctl("set-volume", dash, volume(v))
        lastVolume = v
        wpctl("set-default", dash)
        fullVolume(car.speakers.values.map { it.device }.toSet())
    }

    /** Devices that have joined the layout since it was last applied. None on the first apply after DASH starts. */
    private fun newDevices(car: CarSound): Set<String> {
        val was = applied ?: return emptySet()
        val had = if (was.enabled) was.speakers.values.map { it.device }.toSet() else emptySet()
        return car.speakers.values.map { it.device }.toSet() - had
    }

    /** The layout's devices are its amplifiers: they sit at full, and DASH's volume governs them. */
    private fun fullVolume(keys: Set<String>) {
        sound.state.value.outputs.filter { it.key in keys && !it.dash }.forEach {
            wpctl("set-volume", it.id, volume(1f))
            wpctl("set-mute", it.id, "0")
        }
    }

    /**
     * The reverse: the front speakers (or any in the layout) become the default at DASH's loudness,
     * before the chain stops — so nothing falls back to a device still at full.
     */
    private fun turnOff(car: CarSound) {
        publish(running = true, ready = false)
        val s = sound.state.value
        val dash = s.outputs.firstOrNull { it.dash }
        val keys = listOfNotNull(car.speakers[SpeakerPosition.FRONT]?.device) + car.speakers.values.map { it.device }
        val target = keys.firstNotNullOfOrNull { k -> s.outputs.firstOrNull { it.key == k } }
            ?: s.outputs.firstOrNull { !it.dash }
        if (target != null) {
            wpctl("set-volume", target.id, volume(dash?.volume ?: DEFAULT_VOLUME))
            wpctl("set-default", target.id)
        }
        // Every other device the layout used goes down too, in case something is still playing to it.
        s.outputs.filter { it.key in car.speakers.values.map { a -> a.device } && it.id != target?.id }
            .forEach { wpctl("set-volume", it.id, volume(dash?.volume ?: DEFAULT_VOLUME)) }
        // Calls sent to "DASH calls" go back to following the default.
        synchronized(routedCalls) {
            routedCalls.forEach { run(listOf("pw-metadata", "-n", "default", "-d", it, "target.object")) }
            routedCalls.clear()
        }
        systemctl("disable", "--now", SPEAKERS_UNIT)
        systemctl("disable", "--now", SOUND_UNIT)
    }

    // ---- Watching: calls, and PipeWire going away and coming back ----------------------------------------

    /**
     * Every few tenths of a second while Car sound runs: send new calls the calls' way, remember DASH's
     * volume while all is well, and notice PipeWire going and coming back.
     */
    private fun watch() {
        var wasUp = true
        while (true) {
            Thread.sleep(WATCH_MS)
            val car = applied ?: continue
            if (!car.enabled || !_state.value.available) { wasUp = sound.state.value.available; continue }
            val up = sound.state.value.available
            if (!up && wasUp) {
                Log.w(TAG, "PipeWire went away — sound not ready until it is back and restored")
                publish(running = false, ready = false, note = "The sound system stopped — waiting for it")
            }
            if (up && !wasUp) worker.execute { recover() }
            wasUp = up
            if (!up || !_state.value.ready) continue
            sound.state.value.outputs.firstOrNull { it.dash }?.let { lastVolume = it.volume }
            reclaimDefault()
            routeCalls(car)
            followVolumeAndSpeed(car)
        }
    }

    /**
     * Loudness after DASH's volume, and speed volume after the speed: worked out every watch, and only
     * what has moved is sent. Speed volume moves at most [SPEED_RAMP_DB] a watch, so it rises and falls as
     * smoothly as a hand on the knob, and never past what full volume would give.
     */
    private fun followVolumeAndSpeed(car: CarSound) {
        val volume = live().volume
        val headroom = volume?.let { -volumeDb(it) } ?: 0f
        val target = speedBoostDb(speedKmh, car.speedVolume).coerceAtMost(headroom).coerceAtLeast(0f)
        speedDb = when {
            abs(target - speedDb) <= SPEED_RAMP_DB -> target
            target > speedDb -> speedDb + SPEED_RAMP_DB
            else -> speedDb - SPEED_RAMP_DB
        }
        val now = Live(volume, speedDb)
        setParams(SINK, liveParams(car, now), onlyChanged = true)
        setParams(CALLS, liveCallParams(now), onlyChanged = true)
    }

    /**
     * When DASH's output vanishes for a moment (its process restarting), WirePlumber picks another default
     * and remembers it as the user's choice — and it picks DASH's own internal splitter, so the sound would
     * come back with the equaliser bypassed (seen 2026-10-08). The default left on one of DASH's internal
     * parts is put back on DASH's output. A real device the user chooses is theirs, and left alone.
     */
    private fun reclaimDefault() {
        val current = sound.defaultSinkName() ?: return
        if (current == SINK || !current.startsWith("dash-aa")) return
        val dash = sound.nodeId(SINK) ?: return
        Log.w(TAG, "the default had moved to $current — back onto DASH")
        wpctl("set-default", dash)
    }

    private fun routeCalls(car: CarSound) {
        val into = setOf(SINK) + car.speakers.values.map { it.device }
        for (id in sound.streamsInto(into, calls = true)) {
            synchronized(routedCalls) { if (!routedCalls.add(id)) return@synchronized null else Unit } ?: continue
            run(listOf("pw-metadata", "-n", "default", id, "target.object", CALLS))
            Log.i(TAG, "call audio (node $id) → DASH calls")
        }
    }

    /**
     * PipeWire is back. Nothing plays until DASH has control again: the layout's devices are muted as
     * they reappear, the chain is waited for, strays are moved back onto DASH's output, the volumes are put
     * back, and only then is the sound unmuted and ready.
     */
    private fun recover() {
        val car = applied?.takeIf { it.enabled } ?: return
        publish(running = false, ready = false, note = "The sound system restarted — restoring Car sound…")
        Log.i(TAG, "PipeWire is back — restoring Car sound")
        synchronized(routedCalls) { routedCalls.clear() }      // new nodes, new ids
        synchronized(sent) { sent.clear() }
        val keys = car.speakers.values.map { it.device }.toSet()
        val muted = HashSet<String>()
        val until = System.currentTimeMillis() + RECOVER_MS
        while (System.currentTimeMillis() < until) {
            sound.state.value.outputs.filter { it.key in keys && it.key !in muted }.forEach {
                wpctl("set-mute", it.id, "1"); muted += it.key
            }
            if (muted.containsAll(keys.filter { k -> sound.state.value.outputs.any { it.key == k } })
                && sound.nodeId(SINK) != null && (layoutConfig(car, driverOnRight) == null || sound.nodeId(LAYOUT) != null)) break
            if (sound.nodeId(SINK) == null && System.currentTimeMillis() > until - RECOVER_MS / 2 && !isActive(SOUND_UNIT)) {
                systemctl("start", SOUND_UNIT)                  // it should have come up with PipeWire; nudge it
            }
            Thread.sleep(100)
        }
        val dash = sound.nodeId(SINK)
        if (dash == null) {
            publish(running = false, ready = false, note = "The sound chain did not come back — journalctl --user -u $SOUND_UNIT says why")
            return
        }
        for (id in sound.streamsInto(keys, calls = false)) {
            run(listOf("pw-metadata", "-n", "default", id, "target.object", SINK))
            Log.i(TAG, "node $id had landed straight on a speaker — moved back onto DASH")
        }
        lastVolume?.let { wpctl("set-volume", dash, volume(it)) }
        wpctl("set-default", dash)
        setParams(SINK, wayInParams(car, live()))
        setParams(CALLS, callsInParams(car, live()))
        setParams(LAYOUT, layoutParams(car))
        setParams(LAYOUT_CALLS, callParams(car, driverOnRight))
        sound.state.value.outputs.filter { it.key in keys }.forEach {
            wpctl("set-volume", it.id, volume(1f))
            wpctl("set-mute", it.id, "0")
        }
        publish(running = true, ready = true)
        Log.i(TAG, "Car sound restored")
    }

    private fun awaitNode(name: String): String? = runBlocking {
        withTimeoutOrNull(8_000) {
            sound.state.first { sound.nodeId(name) != null }
            sound.nodeId(name)
        }
    }

    /**
     * Change running controls: `pw-cli set-param <node> Props { params = [ "eqL0:Gain" 3.0 … ] }`. With
     * [onlyChanged], only those that differ from what was last set.
     */
    private fun setParams(node: String, params: List<Pair<String, Float>>, onlyChanged: Boolean = false) {
        val id = sound.nodeId(node) ?: return                // not up yet: it starts with the file's values
        val send = synchronized(sent) {
            params.filter { (k, v) -> !onlyChanged || sent["$node/$k"]?.let { abs(it - v) < 1e-4f } != true }
                .onEach { (k, v) -> sent["$node/$k"] = v }
        }
        if (send.isEmpty()) return
        val body = send.joinToString(" ") { (k, v) -> "\"$k\" ${"%.5f".format(Locale.ROOT, v)}" }
        if (!run(listOf("pw-cli", "set-param", id, "Props", "{ params = [ $body ] }"))) {
            synchronized(sent) { send.forEach { (k, _) -> sent.remove("$node/$k") } }   // tried again next time
        }
    }

    private fun isActive(unit: String) = systemctl("is-active", "--quiet", unit)

    private fun systemctl(vararg args: String) = run(listOf("systemctl", "--user") + args, timeoutSeconds = 20)

    private fun wpctl(vararg args: String) {
        if (!run(listOf("wpctl") + args)) Log.w(TAG, "wpctl ${args.joinToString(" ")} did not succeed")
    }

    private fun volume(v: Float) = "%.3f".format(Locale.ROOT, v.coerceIn(0f, 1f))

    private fun run(cmd: List<String>, timeoutSeconds: Long = 5): Boolean = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        (p.waitFor(timeoutSeconds, TimeUnit.SECONDS).also { if (!it) p.destroyForcibly() } && p.exitValue() == 0)
    }.getOrDefault(false)

    /** Write [text] to [file] if it differs, by renaming over it so PipeWire never reads half a file. */
    private fun writeIfChanged(file: File, text: String): Boolean {
        if (file.exists() && runCatching { file.readText() }.getOrNull() == text) return false
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return true
    }

    internal fun soundUnit() = """
        |# Written by DASH-AA (Audio › Speakers › Car sound). DASH rewrites it; edits are lost.
        |[Unit]
        |Description=DASH-AA car sound: the way in (equaliser, low cut, subwoofer crossover)
        |After=pipewire.service wireplumber.service
        |BindsTo=pipewire.service
        |
        |[Service]
        |Type=simple
        |ExecStart="$pipewire" -c "${File(configDir, SOUND_CONF).absolutePath}"
        |Restart=on-failure
        |Slice=session.slice
        |
        |[Install]
        |WantedBy=pipewire.service
        |""".trimMargin()

    // Waits for the way in's sound (music and calls) before starting: taken before it exists, it would
    // never link. If it never comes, the start fails and systemd tries again.
    internal fun speakersUnit() = """
        |# Written by DASH-AA (Audio › Speakers › Car sound). DASH rewrites it; edits are lost.
        |[Unit]
        |Description=DASH-AA car sound: the speaker layout
        |After=$SOUND_UNIT
        |BindsTo=$SOUND_UNIT
        |
        |[Service]
        |Type=simple
        |ExecStartPre=/bin/sh -c 'for i in $$(seq 80); do d=$$("$pwDump" 2>/dev/null); case "${"$$"}d" in *"\"$PROCESSED\""*"\"$CALLS_PROCESSED\""*|*"\"$CALLS_PROCESSED\""*"\"$PROCESSED\""*) exit 0;; esac; sleep 0.1; done; exit 1'
        |ExecStart="$pipewire" -c "${File(configDir, SPEAKERS_CONF).absolutePath}"
        |Restart=on-failure
        |RestartSec=2
        |Slice=session.slice
        |
        |[Install]
        |WantedBy=$SOUND_UNIT
        |""".trimMargin()

    /** What changes with DASH's volume (0–1, null when unknown) and the car's speed (speed volume's rise, dB). */
    data class Live(val volume: Float? = null, val speedDb: Float = 0f)

    companion object {
        private const val TAG = "DashSoundChain"

        /** DASH's output — what every app plays into. */
        const val SINK = "dash-aa.sound"
        /** The way in's music, offered to the layout. */
        const val PROCESSED = "dash-aa.processed"
        /** Where calls play while the chain runs, and their sound offered on. */
        const val CALLS = "dash-aa.calls"
        const val CALLS_PROCESSED = "dash-aa.calls.processed"
        /** The layout's intakes. */
        const val LAYOUT = "dash-aa.layout"
        const val LAYOUT_CALLS = "dash-aa.layout.calls"
        const val SPEAKERS = "dash-aa.speakers"
        const val SPEAKERS_CALLS = "dash-aa.speakers.calls"

        const val SOUND_UNIT = "dash-aa-sound.service"
        const val SPEAKERS_UNIT = "dash-aa-speakers.service"
        const val SOUND_CONF = "dash-aa-sound.conf"
        const val SPEAKERS_CONF = "dash-aa-speakers.conf"

        /** DASH's own chain can do everything the tabs offer. */
        val OFFERS = SoundControl.entries.toSet()

        private const val WATCH_MS = 300L
        /** Raised whenever the way in's graph changes shape; never otherwise. 1: the first 1.1.3 build; 2: calls and
         *  anti-distortion; 3: the surround effect; 4: copies before the outputs, which 3 lacked and fell silent;
         *  5 (1.1.4): loudness, and speed volume's gain on calls. */
        private const val WAY_IN_SHAPE = 5
        /** The layout's shape, beside its signature. 2 (1.1.4): a delay on every output, for time alignment. */
        private const val LAYOUT_SHAPE = 2
        /** Time alignment's longest delay: 5 m of difference is 14.6 ms. */
        private const val MAX_ALIGN_S = 0.02
        /** How far speed volume moves in one watch: 0.5 dB each 0.3 s, so a full 12 dB takes seven seconds. */
        private const val SPEED_RAMP_DB = 0.5f

        /** DASH's volume as decibels: PipeWire's volumes are cubic, so 0.5 is a gain of 0.125, −18 dB. */
        fun volumeDb(v: Float): Float = 60f * log10(v.coerceAtLeast(0.001f))

        /** Loudness's filter gains for [live]'s volume: flat when off, unset, unknown or at or above the comfortable volume. */
        internal fun loudnessGains(car: CarSound, live: Live): List<Float> {
            val ref = car.loudnessReference
            val v = live.volume
            if (car.loudness == 0 || ref == null || v == null) return List(Loudness.BANK.size) { 0f }
            return Loudness.gains((volumeDb(ref) - volumeDb(v)).toDouble(), car.loudness)
        }

        private fun speedGain(live: Live) = 10f.pow(live.speedDb / 20f)
        private const val RECOVER_MS = 12_000L

        private val MODULE_DIRS = listOf(
            "/usr/lib/pipewire-0.3", "/usr/lib64/pipewire-0.3",
            "/usr/lib/x86_64-linux-gnu/pipewire-0.3", "/usr/lib/aarch64-linux-gnu/pipewire-0.3",
        )

        /** One-octave bands, a Linkwitz-Riley crossover (two Butterworth stages, 24 dB an octave). */
        private const val EQ_Q = 1.41f
        private const val BUTTERWORTH_Q = 0.7071f
        /** PipeWire's mixer takes eight inputs; an output fed by more is mixed in eights, then together. */
        private const val MIXER_INPUTS = 8
        /** DASH's volume when nothing was playing to take it from. */
        private const val DEFAULT_VOLUME = 0.4f

        private val json = Json { prettyPrint = true }

        private fun configHome() = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".config")

        // ---- The way in: fixed in shape, so it never has to restart -------------------------------------

        private val BQ = mapOf(Loudness.Kind.LOW_SHELF to "bq_lowshelf", Loudness.Kind.PEAKING to "bq_peaking", Loudness.Kind.HIGH_SHELF to "bq_highshelf")
        private val LOUD_LAST = Loudness.BANK.size - 1

        internal fun wayInConfig(car: CarSound, live: Live = Live()): String {
            val nodes = buildJsonArray {
                for (side in listOf("L", "R")) {
                    add(filter("pre$side", "mixer", "Gain 1" to car.preGain() * speedGain(live)))
                    CarSound.EQ_BANDS.forEachIndexed { i, hz ->
                        add(filter("eq$side$i", "bq_peaking", "Freq" to hz.toFloat(), "Q" to EQ_Q, "Gain" to car.eq.getOrElse(i) { 0 }.toFloat()))
                    }
                    val loud = loudnessGains(car, live)
                    Loudness.BANK.forEachIndexed { k, band ->
                        add(filter("ld$side$k", BQ.getValue(band.kind), "Freq" to band.freq.toFloat(), "Q" to band.q.toFloat(), "Gain" to loud[k]))
                    }
                }
                addAll(crossoverNodes(car))
                add(diffNode())
                // The surround effect's delay: up to a tenth of a second, set live.
                add(delayNode("sdelay", 0.1, car.surroundDelay / 1000f))
            }
            val last = CarSound.EQ_BANDS.size - 1
            val links = buildJsonArray {
                for (side in listOf("L", "R")) {
                    add(link("pre$side:Out", "eq${side}0:In"))
                    for (i in 0 until last) add(link("eq$side$i:Out", "eq$side${i + 1}:In"))
                    add(link("eq$side$last:Out", "ld${side}0:In"))
                    for (k in 0 until LOUD_LAST) add(link("ld$side$k:Out", "ld$side${k + 1}:In"))
                }
                addAll(crossoverLinks("ldL$LOUD_LAST:Out", "ldR$LOUD_LAST:Out"))
                addAll(diffLinks())
                add(link("diff:Out", "sdelay:In"))
            }
            val music = module("libpipewire-module-filter-chain", buildJsonObject {
                put("node.description", "DASH")
                put("media.name", "DASH")
                put("filter.graph", buildJsonObject {
                    put("nodes", JsonArray(nodes + finalCopies()))
                    put("links", JsonArray(links + finalLinks("hpL2:Out", "hpR2:Out", "lp2:Out", "sdelay:Out")))
                    put("inputs", strings("preL:In 1", "preR:In 1"))
                    put("outputs", strings(*FINAL_OUT))
                })
                put("capture.props", buildJsonObject {
                    put("node.name", SINK)
                    put("node.description", "DASH")
                    put("media.class", "Audio/Sink")
                    put("audio.position", strings("FL", "FR"))
                })
                put("playback.props", offered(PROCESSED))
            })
            // Calls: no equaliser, so voices stay natural; the low cut and crossover still spare the speakers.
            val calls = module("libpipewire-module-filter-chain", buildJsonObject {
                put("node.description", "DASH calls")
                put("media.name", "DASH calls")
                put("filter.graph", buildJsonObject {
                    put("nodes", JsonArray(listOf(filter("cL", "mixer", "Gain 1" to speedGain(live)), filter("cR", "mixer", "Gain 1" to speedGain(live))) +
                        crossoverNodes(car) + diffNode() + finalCopies()))
                    put("links", JsonArray(crossoverLinks("cL:Out", "cR:Out") + diffLinks() + finalLinks("hpL2:Out", "hpR2:Out", "lp2:Out", "diff:Out")))
                    put("inputs", strings("cL:In 1", "cR:In 1"))
                    put("outputs", strings(*FINAL_OUT))
                })
                put("capture.props", buildJsonObject {
                    put("node.name", CALLS)
                    put("node.description", "DASH calls (internal)")
                    put("media.class", "Audio/Sink")
                    put("audio.position", strings("FL", "FR"))
                    put("priority.session", 0)
                    put("priority.driver", 0)
                })
                put("playback.props", offered(CALLS_PROCESSED))
            })
            // The first line names the shape, so a DASH that changes it knows the running one is older.
            return "# DASH-AA car sound: the way in, shape $WAY_IN_SHAPE. Written by DASH-AA (Audio › Equaliser); DASH rewrites it.\n" +
                json.encodeToString(JsonObject.serializer(), process(music, calls)) + "\n"
        }

        /** Each side's low cut, and both sides together through the subwoofer's crossover. */
        private fun crossoverNodes(car: CarSound): List<JsonObject> = buildList {
            for (side in listOf("L", "R")) for (stage in 1..2) add(filter("hp$side$stage", "bq_highpass", "Freq" to car.lowCut.toFloat(), "Q" to BUTTERWORTH_Q))
            add(filter("lowSum", "mixer", "Gain 1" to 0.5f, "Gain 2" to 0.5f))
            for (stage in 1..2) add(filter("lp$stage", "bq_lowpass", "Freq" to car.subCutoff.toFloat(), "Q" to BUTTERWORTH_Q))
        }

        /**
         * The way in's final outputs, each through a copy of its own. A port that is a final output **and**
         * feeds another filter plays silence (PipeWire 1.6.9, found 2026-10-08 when the surround effect
         * tapped left and right): PipeWire's own examples always end in copies, and so does this.
         */
        private val FINAL_OUT = arrayOf("oL:Out", "oR:Out", "oLow:Out", "oDiff:Out")

        private fun finalCopies() = FINAL_OUT.map { copy(it.substringBefore(':')) }

        private fun finalLinks(vararg from: String) = from.zip(FINAL_OUT).map { (f, o) -> link(f, "${o.substringBefore(':')}:In") }

        /** Half of left minus right, after the low cut: the surround effect. */
        private fun diffNode() = filter("diff", "mixer", "Gain 1" to 0.5f, "Gain 2" to -0.5f)

        private fun diffLinks() = listOf(link("hpL2:Out", "diff:In 1"), link("hpR2:Out", "diff:In 2"))

        /** The crossover's links, fed from the ports [left] and [right]. */
        private fun crossoverLinks(left: String, right: String): List<JsonObject> = listOf(
            link(left, "hpL1:In"), link(right, "hpR1:In"),
            link("hpL1:Out", "hpL2:In"), link("hpR1:Out", "hpR2:In"),
            link(left, "lowSum:In 1"), link(right, "lowSum:In 2"),
            link("lowSum:Out", "lp1:In"), link("lp1:Out", "lp2:In"),
        )

        private fun delayNode(name: String, max: Double, seconds: Float) = buildJsonObject {
            put("type", "builtin"); put("name", name); put("label", "delay")
            put("config", buildJsonObject { put("max-delay", max) })
            put("control", buildJsonObject { put("Delay (s)", JsonPrimitive(seconds)) })
        }

        private fun copy(name: String) = buildJsonObject { put("type", "builtin"); put("name", name); put("label", "copy") }

        /** A way in's result, offered as a recording the layout takes. Lowest priority, so nothing picks it as a microphone. */
        private fun offered(name: String) = buildJsonObject {
            put("node.name", name)
            put("node.description", "DASH (internal)")
            put("media.class", "Audio/Source")
            put("audio.position", strings(*WAY_OUT))
            put("node.passive", true)
            put("priority.session", 0)
            put("priority.driver", 0)
        }

        internal fun wayInParams(car: CarSound, live: Live = Live()): List<Pair<String, Float>> = buildList {
            for (side in listOf("L", "R")) {
                CarSound.EQ_BANDS.indices.forEach { i -> add("eq$side$i:Gain" to car.eq.getOrElse(i) { 0 }.toFloat()) }
            }
            add("sdelay:Delay (s)" to car.surroundDelay / 1000f)
            addAll(crossoverParams(car))
            addAll(liveParams(car, live))
        }

        internal fun callsInParams(car: CarSound, live: Live = Live()): List<Pair<String, Float>> = crossoverParams(car) + liveCallParams(live)

        private fun crossoverParams(car: CarSound): List<Pair<String, Float>> = buildList {
            for (side in listOf("L", "R")) for (stage in 1..2) add("hp$side$stage:Freq" to car.lowCut.toFloat())
            for (stage in 1..2) add("lp$stage:Freq" to car.subCutoff.toFloat())
        }

        /** The way in's controls that follow the volume and the speed: anti-distortion with speed volume, and loudness. */
        internal fun liveParams(car: CarSound, live: Live): List<Pair<String, Float>> = buildList {
            val loud = loudnessGains(car, live)
            for (side in listOf("L", "R")) {
                add("pre$side:Gain 1" to car.preGain() * speedGain(live))
                loud.forEachIndexed { k, g -> add("ld$side$k:Gain" to g) }
            }
        }

        internal fun liveCallParams(live: Live): List<Pair<String, Float>> = listOf("cL:Gain 1" to speedGain(live), "cR:Gain 1" to speedGain(live))

        // ---- The layout: restarted when its shape changes ------------------------------------------------

        private val SOURCE_PORT = mapOf(
            SoundSource.LEFT to "inL:Out", SoundSource.RIGHT to "inR:Out", SoundSource.LOW to "inLow:Out", SoundSource.DIFF to "inDiff:Out",
        )
        /** The way in's channels, as the layout takes them: left, right, low, and the surround effect. */
        private val WAY_OUT = arrayOf("FL", "FR", "LFE", "RC")

        /**
         * The layout's process, or null when no speaker has a device. Its first line names the layout's
         * shape, so a change of shape (and only that) is seen by comparing first lines. Music and calls
         * each have their own mix of the same outputs, and PipeWire adds them together at each device.
         */
        internal fun layoutConfig(car: CarSound, driverOnRight: Boolean = false): String? {
            val music = car.feeds().entries.toList()
            if (music.isEmpty()) return null
            val calls = car.callFeeds(driverOnRight).entries.toList()
            val delays = car.outputDelays()
            return "# DASH-AA car sound: the speaker layout, shape $LAYOUT_SHAPE, ${layoutSignature(car)}. Written by DASH-AA (Audio › Speakers); DASH rewrites it.\n" +
                json.encodeToString(JsonObject.serializer(), process(
                    *mix(music, delays, PROCESSED, LAYOUT, SPEAKERS, "DASH speakers (internal)"),
                    *mix(calls, delays, CALLS_PROCESSED, LAYOUT_CALLS, SPEAKERS_CALLS, "DASH call speakers (internal)"),
                )) + "\n"
        }

        /**
         * One mix: [from]'s left, right and low into a mixer for each output, through its time alignment
         * delay (from [delays]), then out to each device.
         */
        private fun mix(
            feeds: List<Map.Entry<Pair<String, String>, List<SoundFeed>>>,
            delays: Map<Pair<String, String>, Float>,
            from: String, intake: String, splitter: String, description: String,
        ): Array<JsonObject> {
            val nodes = mutableListOf<JsonElement>()
            for (n in listOf("inL", "inR", "inLow", "inDiff")) nodes += copy(n)
            val links = mutableListOf<JsonElement>()
            val outputs = mutableListOf<String>()
            feeds.forEachIndexed { k, (key, parts) ->
                val chunks = parts.chunked(MIXER_INPUTS)
                chunks.forEachIndexed { j, chunk ->
                    val name = mixerName(k, j, chunks.size)
                    nodes += filter(name, "mixer", *chunk.mapIndexed { i, f -> "Gain ${i + 1}" to f.gain }.toTypedArray())
                    chunk.forEachIndexed { i, f -> links += link(SOURCE_PORT.getValue(f.source), "$name:In ${i + 1}") }
                }
                if (chunks.size > 1) {
                    nodes += filter("out$k", "mixer", *chunks.indices.map { "Gain ${it + 1}" to 1f }.toTypedArray())
                    chunks.indices.forEach { j -> links += link("${mixerName(k, j, chunks.size)}:Out", "out$k:In ${j + 1}") }
                }
                nodes += delayNode("dl$k", MAX_ALIGN_S, delays[key] ?: 0f)
                links += link("out$k:Out", "dl$k:In")
                outputs += "dl$k:Out"
            }
            val aux = feeds.indices.map { "AUX$it" }
            val chain = module("libpipewire-module-filter-chain", buildJsonObject {
                put("node.description", description)
                put("media.name", description)
                put("filter.graph", buildJsonObject {
                    put("nodes", JsonArray(nodes))
                    put("links", JsonArray(links))
                    put("inputs", strings("inL:In", "inR:In", "inLow:In", "inDiff:In"))
                    put("outputs", strings(*outputs.toTypedArray()))
                })
                put("capture.props", buildJsonObject {
                    put("node.name", intake)
                    put("node.description", description)
                    put("audio.position", strings(*WAY_OUT))
                    put("target.object", from)
                    put("node.dont-fallback", true)
                    put("node.passive", true)
                    put("stream.dont-remix", true)
                    put("application.name", "DASH-AA")
                })
                put("playback.props", buildJsonObject {
                    put("node.name", "$intake.out")
                    put("audio.position", strings(*aux.toTypedArray()))
                    put("target.object", splitter)
                    put("node.dont-fallback", true)
                    put("node.passive", true)
                    put("stream.dont-remix", true)
                    put("application.name", "DASH-AA")
                })
            })
            // Each device gets one stream carrying every output the layout uses on it.
            val byDevice = feeds.mapIndexed { k, e -> Triple(e.key.first, e.key.second, "AUX$k") }.groupBy { it.first }
            val combine = module("libpipewire-module-combine-stream", buildJsonObject {
                put("combine.mode", "sink")
                put("node.name", splitter)
                put("node.description", description)
                // Separate cards have separate delays; PipeWire lines them up.
                put("combine.latency-compensate", true)
                put("combine.props", buildJsonObject {
                    put("audio.position", strings(*aux.toTypedArray()))
                    put("node.passive", true)
                    put("priority.session", 0)
                    put("priority.driver", 0)
                })
                put("stream.props", buildJsonObject {
                    put("stream.dont-remix", true)
                    put("application.name", "DASH-AA")
                })
                put("stream.rules", buildJsonArray {
                    for ((device, outs) in byDevice) add(buildJsonObject {
                        put("matches", buildJsonArray { add(buildJsonObject { put("media.class", "Audio/Sink"); put("node.name", device) }) })
                        put("actions", buildJsonObject {
                            put("create-stream", buildJsonObject {
                                put("combine.audio.position", strings(*outs.map { it.third }.toTypedArray()))
                                put("audio.position", strings(*outs.map { it.second }.toTypedArray()))
                            })
                        })
                    })
                })
            })
            return arrayOf(chain, combine)
        }

        internal fun layoutParams(car: CarSound): List<Pair<String, Float>> = mixParams(car.feeds().values) + delayParams(car)

        internal fun callParams(car: CarSound, driverOnRight: Boolean): List<Pair<String, Float>> =
            mixParams(car.callFeeds(driverOnRight).values) + delayParams(car)

        private fun delayParams(car: CarSound): List<Pair<String, Float>> =
            car.outputDelays().values.mapIndexed { k, d -> "dl$k:Delay (s)" to d }

        private fun mixParams(feeds: Collection<List<SoundFeed>>): List<Pair<String, Float>> = buildList {
            feeds.forEachIndexed { k, parts ->
                val chunks = parts.chunked(MIXER_INPUTS)
                chunks.forEachIndexed { j, chunk ->
                    chunk.forEachIndexed { i, f -> add("${mixerName(k, j, chunks.size)}:Gain ${i + 1}" to f.gain) }
                }
            }
        }

        /** The layout's shape — which outputs, fed from what — without its gains. */
        internal fun layoutSignature(car: CarSound): String {
            val shape = car.feeds().entries.joinToString(";") { (key, parts) -> "${key.first}/${key.second}=" + parts.joinToString(",") { it.source.name } }
            val digest = MessageDigest.getInstance("SHA-256").digest(shape.toByteArray())
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        private fun mixerName(k: Int, j: Int, chunks: Int) = if (chunks == 1) "out$k" else "out${k}_$j"

        // ---- Config pieces ------------------------------------------------------------------------------

        /** A whole `pipewire -c` process: the client essentials, as PipeWire's own filter-chain.conf, then [modules]. */
        private fun process(vararg modules: JsonObject) = buildJsonObject {
            put("context.properties", buildJsonObject { put("log.level", 0) })
            put("context.spa-libs", buildJsonObject {
                put("audio.convert.*", "audioconvert/libspa-audioconvert")
                put("support.*", "support/libspa-support")
            })
            put("context.modules", buildJsonArray {
                add(buildJsonObject {
                    put("name", "libpipewire-module-rt")
                    put("args", buildJsonObject { })
                    put("flags", strings("ifexists", "nofail"))
                })
                for (m in listOf("libpipewire-module-protocol-native", "libpipewire-module-client-node", "libpipewire-module-adapter")) {
                    add(buildJsonObject { put("name", m) })
                }
                modules.forEach { add(it) }
            })
        }

        private fun module(name: String, args: JsonObject) = buildJsonObject { put("name", name); put("args", args) }

        private fun filter(name: String, label: String, vararg controls: Pair<String, Float>) = buildJsonObject {
            put("type", "builtin")
            put("name", name)
            put("label", label)
            put("control", buildJsonObject { controls.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }

        private fun link(output: String, input: String) = buildJsonObject { put("output", output); put("input", input) }

        private fun strings(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }
    }
}
