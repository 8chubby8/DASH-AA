package com.dash.android.aa

import com.dash.android.aa.input.EvdevTouch
import com.dash.android.aa.media.VideoDecoder
import com.dash.android.aa.usb.AaUsb
import java.io.File

/**
 * What this machine can do for Android Auto — each answer with the thing that would change it.
 * Probed, never assumed: the Capability Detection Principle, applied to the viewport's new tenant.
 */
object AaCapabilities {

    fun usbSummary(): String = when {
        !AaUsb.available() -> "Unavailable — libusb-1.0 not found"
        udevRuleInstalled() -> "Ready"
        else -> "Needs the udev rule — run packaging/install.sh"
    }

    fun decoderSummary(): String = VideoDecoder.ffmpegPath()?.let { "ffmpeg ($it)" } ?: "Missing — install ffmpeg"

    fun touchSummary(): String = EvdevTouch.findTouchscreen()?.let { d ->
        if (File(d.path).canRead()) "${d.name} — multi-touch" else "${d.name} — single-touch (join the 'input' group for multi-touch)"
    } ?: "None found — mouse and touchpad act as one finger"

    /** Whether DASH-AA's udev rule (or an equivalent the user wrote) is in place. */
    fun udevRuleInstalled(): Boolean =
        listOf("/etc/udev/rules.d", "/usr/lib/udev/rules.d", "/lib/udev/rules.d").any { dir ->
            File(dir).listFiles().orEmpty().any { f -> f.name.contains("dash-aa") || f.name.contains("android-udev") || f.name.contains("51-android") }
        }
}
