package com.dash.android.connections.linux

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **No Bluetooth music from phones** (DASH-AA 1.1.6) — the guard for the fault found at 1.0.6: a paired
 * phone also sends its music over Bluetooth (A2DP) when the machine offers to be a Bluetooth speaker, and
 * WirePlumber falls over trying to play it beside Android Auto's, taking all sound with it. Until now the
 * only protection was the driver remembering to switch *Media audio* off on the phone.
 *
 * WirePlumber decides which Bluetooth sound roles the machine offers (`bluez5.roles`). With the guard on,
 * DASH gives it the default list less the two that receive music — `a2dp_sink` and `bap_sink` (its
 * Bluetooth LE twin) — in a file of the seat user's own, so the phone never sees a speaker to send music
 * to. Calls (`hfp_hf`), sending sound *to* Bluetooth headphones, and internet over Bluetooth are untouched.
 * With the guard off the file goes and WirePlumber's own defaults return.
 *
 * WirePlumber reads it when it starts, so a change restarts WirePlumber — the sound pauses for about a
 * second while it reconnects everything. It only happens when the setting changes, never at an ordinary
 * start of DASH.
 */
object BluetoothMusic {
    private const val TAG = "DashBluetoothMusic"

    private val file: File by lazy {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        File(config, "wireplumber/wireplumber.conf.d/51-dash-aa-no-bluetooth-music.conf")
    }

    private val CONTENT = """
        |# Written by DASH-AA (Connections › Bluetooth › Music from phones). Phones' Bluetooth music crashes the
        |# sound system beside Android Auto's, so this machine does not offer itself as a Bluetooth speaker.
        |# Calls and Bluetooth headphones are unaffected. Switching the setting back removes this file.
        |monitor.bluez.properties = {
        |  bluez5.roles = [ a2dp_source bap_source hfp_hf hfp_ag ]
        |}
        |""".trimMargin()

    /** Whether the guard is in place now. */
    fun blocked(): Boolean = file.isFile

    /** Put the guard in place or take it away; WirePlumber is restarted only if that changed anything. */
    fun set(block: Boolean) {
        val changed = runCatching {
            if (block) {
                if (file.isFile && file.readText() == CONTENT) false
                else { file.parentFile.mkdirs(); file.writeText(CONTENT); true }
            } else {
                file.isFile && file.delete()
            }
        }.getOrElse { Log.w(TAG, "could not change ${file}: ${it.message}"); false }
        if (!changed) return
        Log.i(TAG, if (block) "phones' Bluetooth music refused" else "phones' Bluetooth music allowed")
        runCatching {
            val p = ProcessBuilder("systemctl", "--user", "try-restart", "wireplumber.service").redirectErrorStream(true).start()
            p.waitFor(15, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "could not restart WirePlumber: ${it.message}") }
    }
}
