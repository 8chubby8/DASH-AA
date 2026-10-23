package com.dash.android.logs

/**
 * The parts of DASH a log line can come from, in plain words (1.1.9) — what Developer › Logs filters by.
 *
 * Every part of DASH logs under a tag of its own (`DashAaUsb`, `DashSoundChain`…); a person looking for why
 * the phone dropped wants "Android Auto", not thirty-seven tags. A tag nobody has listed yet falls into
 * "DASH", and the viewer shows the tag beside every line anyway.
 */
object LogParts {
    const val ANDROID_AUTO = "Android Auto"
    const val SOUND = "Sound"
    const val MODULES = "Modules"
    const val CONNECTIONS = "Connections"
    const val DISPLAY = "Display"
    const val POWER = "Power"
    const val SYSTEM = "System"
    const val DASH = "DASH"

    private val byTag = mapOf(
        ANDROID_AUTO to listOf("DashAa", "DashAndroidAuto", "DashAaWireless"),
        SOUND to listOf("DashSound", "DashVolume"),
        MODULES to listOf(
            "DashInstall", "DashModuleDb", "DashReconcile", "DashReports", "DashStreams", "DashBroadcast",
            "DashActions", "DashActivePanel", "DashPanel", "DashUsbSerial", "DashWifiTcp", "DashBtSpp",
        ),
        CONNECTIONS to listOf("DashBluetooth", "DashBlueZ", "DashNetwork"),
        DISPLAY to listOf("DashDisplay", "DashScale"),
        POWER to listOf("DashPower", "DashCarPower"),
        SYSTEM to listOf("DashTime", "DashWeather", "DashDesktopSettings", "DashOldNames", "DashLogs"),
    )

    /** In the order the viewer lists them. */
    val all = listOf(ANDROID_AUTO, SOUND, MODULES, CONNECTIONS, DISPLAY, POWER, SYSTEM, DASH)

    fun of(tag: String): String =
        byTag.entries.firstOrNull { (_, prefixes) -> prefixes.any { tag.startsWith(it) } }?.key ?: DASH
}
