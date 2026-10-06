package com.dash.android

import android.content.Context
import com.dash.android.aa.AndroidAutoHost
import com.dash.android.transport.DashController
import com.dash.android.transport.TransportManager

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

    fun onCreate() {
        transport = TransportManager(this)
        controller = DashController(transport, this)
        androidAuto = AndroidAutoHost(this, controller)
        transport.start()
        controller.start()
        androidAuto.start()
    }
}
