package com.dash.android.system.linux

import android.util.Log
import com.dash.android.connections.linux.BluetoothMusic
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **From dash-aa to dash** (1.1.9) — the one-time move for a machine that ran DASH before it was renamed.
 *
 * Roger, 2026-10-21: "the project is called dash-aa, but the product is called dash. so in the code its
 * called dash". Everything DASH left on the machine under the old name is moved across before anything
 * starts, so the settings come with it and nothing runs twice:
 * - the data folder, `~/.local/share/dash-aa` → `~/.local/share/dash`;
 * - Car sound's two services and their files — stopped and removed; Car sound then starts them again
 *   under the new names, as it would after any switch off and on;
 * - Connections › Bluetooth's WirePlumber file, moved with WirePlumber left running (it says the same).
 *
 * Each step happens only when the old one is there and the new one is not, so it does nothing on every
 * later start, and never on a machine that only ever had the new name. Not root: all of it is the user's own.
 */
object OldNames {
    private const val TAG = "DashOldNames"
    private const val OLD = "dash-aa"

    fun move() {
        if (System.getenv("DASH_HOME") == null) runCatching { data() }.onFailure { Log.w(TAG, "data folder not moved: ${it.message}") }
        runCatching { sound() }.onFailure { Log.w(TAG, "old sound services not removed: ${it.message}") }
        runCatching { bluetoothMusic() }.onFailure { Log.w(TAG, "old WirePlumber file not moved: ${it.message}") }
    }

    private fun dataHome() = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"))
    private fun configHome() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"))

    private fun data() {
        val old = File(dataHome(), OLD)
        val new = File(dataHome(), "dash")
        if (!old.isDirectory) return
        if (new.exists()) { Log.w(TAG, "both $old and $new exist — $new is used, $old is left as it is"); return }
        if (old.renameTo(new)) Log.i(TAG, "data folder moved: $old → $new")
        else Log.w(TAG, "could not move $old to $new")
    }

    private fun sound() {
        val units = File(configHome(), "systemd/user")
        val sound = File(units, "$OLD-sound.service")
        val speakers = File(units, "$OLD-speakers.service")
        if (!sound.exists() && !speakers.exists()) return
        // The speakers hang off the way in, so they go first.
        systemctl("disable", "--now", speakers.name)
        systemctl("disable", "--now", sound.name)
        listOf(sound, speakers, File(configHome(), "pipewire/$OLD-sound.conf"), File(configHome(), "pipewire/$OLD-speakers.conf"))
            .forEach { it.delete() }
        systemctl("daemon-reload")
        Log.i(TAG, "Car sound's old services removed; it starts again under the new names")
    }

    private fun bluetoothMusic() {
        val old = File(configHome(), "wireplumber/wireplumber.conf.d/51-$OLD-no-bluetooth-music.conf")
        if (!old.isFile) return
        BluetoothMusic.adopt(old)
        Log.i(TAG, "phones' Bluetooth music guard moved to its new name")
    }

    private fun systemctl(vararg args: String) = runCatching {
        val p = ProcessBuilder(listOf("systemctl", "--user") + args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
    }
}
