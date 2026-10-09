package com.dash.android

import android.content.Context
import com.dash.android.aa.AndroidAutoHost
import com.dash.android.audio.SoundPreferences
import com.dash.android.audio.SoundProcessor
import com.dash.android.audio.SoundReady
import java.io.File
import com.dash.android.audio.SoundMemories
import com.dash.android.audio.VehicleSpeed
import com.dash.android.aa.AaPreferences
import com.dash.android.audio.SoundSettings
import com.dash.android.audio.SoundSystem
import com.dash.android.audio.VolumeButtons
import com.dash.android.audio.limitStartupVolume
import com.dash.android.audio.linux.PipeWireChain
import com.dash.android.audio.linux.PipeWireSound
import com.dash.android.connections.BluetoothSystem
import com.dash.android.connections.ConnectionsPreferences
import com.dash.android.connections.NetworkSystem
import com.dash.android.connections.linux.BlueZBluetooth
import com.dash.android.connections.linux.BluetoothMusic
import com.dash.android.connections.linux.NetworkManagerNetwork
import com.dash.android.display.DisplaySystem
import com.dash.android.display.DisplayPreferences
import com.dash.android.display.DisplayRules
import com.dash.android.display.linux.LinuxDisplay
import com.dash.android.power.CarPower
import com.dash.android.power.PowerPreferences
import com.dash.android.power.PowerRules
import com.dash.android.power.PowerSystem
import com.dash.android.power.linux.LinuxPower
import com.dash.android.power.linux.Relaunch
import kotlin.system.exitProcess
import com.dash.android.prefs.DashPreferences
import com.dash.android.ui.rotation.DashOrientation
import com.dash.android.transport.DashController
import com.dash.android.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The DASH application object — and the owner of the transport stack (roadmap 1.5.11).
 *
 * **DASH-AA:** upstream's reasoning is unchanged and still the point — the module bus must live for
 * the life of the process, never for the life of a screen, because a UI event that tears the bus down
 * is a silent restart of every module mid-drive. On the desktop there is no Android `Application` to
 * hang it on, so this is the [Context] itself: created once in `Main.kt` before any window exists,
 * and never stopped by anything the UI does. Closing DASH-AA ends the process, which takes the
 * sockets with it — exactly the teardown upstream relies on.
 *
 * [home] is where everything is kept — `~/.local/share/dash-aa` normally; tests pass a scratch folder.
 *
 * Upstream's `densityCapable` probe is gone with the App Density feature it served: the apps it
 * scaled are Android apps in an Android viewport, and DASH-AA's viewport is Android Auto, whose own
 * density is negotiated with the phone (see [AndroidAutoHost]).
 */
class DashApplication(home: java.io.File = defaultHome()) : Context(home) {

    /** The pipes. */
    lateinit var transport: TransportManager
        private set

    /** The brain above the pipes: routes inbound messages and holds the discovery / install /
     *  reconciliation desks the settings tabs read. */
    lateinit var controller: DashController
        private set

    /**
     * **The viewport's tenant — the Android Auto head unit** (DASH-AA). It lives beside the bus for
     * the same reason the bus lives here: a phone mid-projection must not be dropped because a screen
     * recomposed. It reads the sourceless core through the controller (night mode, speed, gear,
     * steering-wheel controls) exactly as a desk would, and no module ever learns it exists.
     */
    lateinit var androidAuto: AndroidAutoHost
        private set

    /**
     * **The machine's sound** (DASH-AA 1.1.2) — what the Audio tabs show and change. PipeWire here;
     * watched for the life of the process, so a sound card plugged in mid-drive appears at once.
     */
    private val pipeWire = PipeWireSound()
    val sound: SoundSystem = pipeWire

    /**
     * **The car's sound** (DASH-AA 1.1.3) — the speaker layout, equaliser, balance, fade and crossover,
     * done by PipeWire as the user's own services. Handed every change to the settings, here rather than
     * in a tab, so a setting changed anywhere (one day by a module) takes effect.
     */
    val soundProcessor: SoundProcessor = PipeWireChain(pipeWire)

    /** Audio › Saved (1.1.4): the car sound in numbered slots, one for the whole app so every tab sees the same. */
    val soundMemories by lazy { SoundMemories(File(filesDir, "sound")) }

    /**
     * **The screens** (DASH-AA 1.1.5) — through whichever display program is running. DASH's setup and
     * Rotation's choice are applied here rather than in a tab, so they hold from the moment DASH starts,
     * and the way DASH found the screens is put back when it closes.
     */
    var display: DisplaySystem = LinuxDisplay(File(filesDir, "display"))
        internal set  // tests set a pretend display program before onCreate

    /**
     * **The networks and Bluetooth** (DASH-AA 1.1.6) — NetworkManager and BlueZ. Started here, not in a
     * tab: each Wi-Fi adapter's job and the DASH network hold from the moment DASH starts, and DASH is
     * the Bluetooth pairing agent for as long as it runs, whether or not settings are open.
     */
    var network: NetworkSystem = NetworkManagerNetwork()
        internal set  // tests set pretend ones before onCreate
    var bluetooth: BluetoothSystem = BlueZBluetooth()
        internal set
    /** Phones' Bluetooth music refused or allowed — WirePlumber's own setting, changed here only. */
    internal var bluetoothMusic: (Boolean) -> Unit = BluetoothMusic::set

    /**
     * **The machine's power** (DASH-AA 1.1.7) — logind, UPower and the power profiles. Started here, not in
     * a tab: sleeping when left alone, the profile on the charger and on the battery, the lid, and getting
     * Android Auto and the amplifiers ready for sleep all hold whether or not settings are open.
     */
    var power: PowerSystem = LinuxPower()
        internal set  // tests set a pretend one before onCreate

    /** **The car's power** (1.1.7): its stages and switched outputs, from what modules report. */
    lateinit var carPower: CarPower
        private set

    /** Leave DASH, back to the desktop it runs inside — the shutdown hooks put the screens back on the way. */
    fun leave(): Unit = exitProcess(0)

    /** Close DASH and start it again (Power, with no desktop to leave to). False when it cannot be restarted. */
    fun restart(): Boolean = Relaunch.schedule().also { if (it) exitProcess(0) }

    fun onCreate() {
        transport = TransportManager(this)
        controller = DashController(transport, this)
        androidAuto = AndroidAutoHost(this, controller)
        transport.start()
        controller.start()
        androidAuto.start()
        sound.start()
        soundProcessor.start()
        val soundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        display.start()
        soundScope.launch {
            // Native's two preferences, unchanged: Auto (on a machine with no tilt sensor, the screen as
            // DASH found it) or a fixed orientation. Native hands them to requestedOrientation.
            val prefs = DashPreferences(this@DashApplication)
            // Settled briefly: the two are saved one after the other, and the pair between means nothing.
            combine(prefs.autoRotate, prefs.lockedOrientation) { auto, locked -> if (auto) null else DashOrientation.from(locked) }
                .debounce(300)
                .distinctUntilChanged()
                .collect { display.rotate(it) }
        }
        soundScope.launch {
            // A touchscreen read directly turns with the screen.
            display.state.collect { s -> s.main?.let { androidAuto.screenTurn = it.quarterTurns to it.flipped } }
        }
        // Brightness by day and night, night light, and blanking (1.1.5).
        DisplayRules(controller.systemState, display, DisplayPreferences(this).settings, soundScope).start()
        Runtime.getRuntime().addShutdownHook(Thread { display.restore() })
        VolumeButtons(controller.systemState, sound, SoundPreferences(this).settings, androidAuto, soundScope).start()
        val car = SoundPreferences(this).settings.map { it.car }.distinctUntilChanged()
        soundScope.launch {
            // The driver's side is Android Auto's setting, the one place DASH-AA keeps it.
            combine(car, AaPreferences(this@DashApplication).settings.map { !it.leftHandDrive }) { c, right -> c to right }
                .distinctUntilChanged()
                .collect { (c, right) -> soundProcessor.apply(c, right) }
        }
        // Speed volume (1.1.4): the car's speed, while a module reports it.
        soundScope.launch {
            VehicleSpeed.of(controller.systemState.values, controller.database.modules, controller.reconciliation.activity)
                .collect { soundProcessor.speed(it) }
        }
        // Connections (1.1.6): DASH's choices carried out whenever they change.
        network.start()
        bluetooth.start()
        val connections = ConnectionsPreferences(this).settings
        soundScope.launch { connections.distinctUntilChanged().collect { network.apply(it) } }
        soundScope.launch(Dispatchers.IO) {
            connections.map { it.noBluetoothMusic }.distinctUntilChanged().collect { bluetoothMusic(it) }
        }
        val ready = SoundReady(controller.systemState)
        ready.start(soundScope, sound.state, soundProcessor.state, car)
        // Closing DASH: the amplifiers are told first, while the transports are still up.
        Runtime.getRuntime().addShutdownHook(Thread { ready.quiet() })
        // Power (1.1.7): before the machine sleeps, the amplifiers are told and the phone let go, in that
        // order, so nothing pops or hangs; on waking both come back.
        power.start()
        val powerSettings = PowerPreferences(this).settings
        carPower = CarPower(controller.systemState, power, display, powerSettings, soundScope).also { it.start() }
        // Outputs off first (the amplifiers), then the sound, then the phone.
        PowerRules(
            power, display, powerSettings, soundScope,
            busy = { androidAuto.status.value is com.dash.android.aa.AaStatus.Projecting },
            prepare = { carPower.stopping(); ready.quiet(); androidAuto.machineSleeping() },
            woke = { carPower.woke(); ready.wake(); androidAuto.machineWoke() },
        ).start()
        Runtime.getRuntime().addShutdownHook(Thread { carPower.off() })
        Thread({
            runBlocking {
                val limit = runCatching { SoundPreferences(this@DashApplication).settings.first().startupLimit }
                    .getOrDefault(SoundSettings.LIMIT_OFF)
                if (limit > SoundSettings.LIMIT_OFF) sound.limitStartupVolume(limit)
            }
        }, "dash-sound-startup").apply { isDaemon = true }.start()
    }
}
