package com.dash.android.system

import android.util.Log
import java.io.File

/**
 * DASH-AA: opening the desktop's own settings panels — the equivalent of upstream's deep links into
 * Android's Wi-Fi and Bluetooth pages, which Transport Manager offers because pairing a Bluetooth
 * module, or joining the network a WiFi module is on, is the operating system's job, not DASH's.
 *
 * Capability-detected: GNOME's Settings first (this machine's desktop), then the common standalone
 * tools. Nothing installed, nothing happens — and nothing breaks.
 */
object DesktopSettings {
    private const val TAG = "DashDesktopSettings"

    fun open(panel: String) {
        val candidates = when (panel) {
            "bluetooth" -> listOf(listOf("gnome-control-center", "bluetooth"), listOf("blueman-manager"), listOf("systemsettings", "kcm_bluetooth"))
            "wifi" -> listOf(listOf("gnome-control-center", "wifi"), listOf("nm-connection-editor"), listOf("systemsettings", "kcm_networkmanagement"))
            else -> listOf(listOf("gnome-control-center"))
        }
        val cmd = candidates.firstOrNull { onPath(it.first()) } ?: run {
            Log.i(TAG, "no settings tool for $panel on this desktop")
            return
        }
        runCatching { ProcessBuilder(cmd).start() }.onFailure { Log.w(TAG, "could not open $cmd: ${it.message}") }
    }

    private fun onPath(name: String): Boolean =
        (System.getenv("PATH") ?: "/usr/bin").split(':').any { File(it, name).canExecute() }
}
