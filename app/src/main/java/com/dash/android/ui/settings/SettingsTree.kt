package com.dash.android.ui.settings

/**
 * The DASH settings tree, declared as data (roadmap 1.5.2). The navigation shell renders whatever
 * this describes; later 1.5.x versions fill a subcategory's content by wiring a real control to its
 * id — they never touch the shell or the navigation again. This is the reconciled structure from
 * interface.md's 2026-07-20 addendum: the ten top-level categories with Layout lifted out of
 * Appearance.
 *
 * Every subcategory carries a [SettingsStatus]. In 1.5.2 nothing is wired yet, so the shell shows
 * each one as an honest placeholder; [wipVersion] is the version its feature lights up at (the
 * WIP-placeholder convention). As features are rehomed, [SettingsStatus.LIVE] entries gain real
 * content.
 *
 * **DASH-AA 1.1.1 — the tree reorganised for Linux** (Roger, 2026-10-07; roadmap 1.1.x). Native's tree
 * was built around Android: System was deep links into Android's own settings. On a Linux head unit with
 * no desktop there is nothing to hand off to, so DASH's settings become the machine's settings —
 * Connections, Display and Power join the tree. Android Auto leaves Layout for a category of its own,
 * and its sound controls move to Audio. **For native:** the categories are the same; native's Apps
 * category fills the Android Auto slot, and Connections, Display and Power hold its Android deep links.
 */
enum class SettingsStatus { LIVE, WIP }

data class SettingsSub(
    val id: String,
    val label: String,
    val status: SettingsStatus = SettingsStatus.WIP,
    val wipVersion: String? = null,
    // A tab that manages its own scrolling fills the whole content box instead of being wrapped in the
    // shell's outer vertical scroll — a pinned control region with a scrollable body beneath it (1.5.8:
    // Module Management pins its header + REFRESH and scrolls only the card list; the Developer
    // instruments at 1.5.11 will want the same shape).
    val fillsBox: Boolean = false,
)

data class SettingsCategory(
    val id: String,
    val label: String,
    val subs: List<SettingsSub>,
)

private fun wip(id: String, label: String, version: String) =
    SettingsSub(id, label, SettingsStatus.WIP, version)

/**
 * The reconciled tree. Kept deliberately representative rather than exhaustive at every leaf —
 * enough that the shell navigates the full shape. Version labels track roadmap.md's 1.5.x plan and
 * the v2/v3 eras.
 */
val DASH_SETTINGS_TREE: List<SettingsCategory> = listOf(
    SettingsCategory(
        "appearance", "Appearance", listOf(
            SettingsSub("appearance.density", "Size & Scale", SettingsStatus.LIVE),
            SettingsSub("appearance.transitions", "Transitions", SettingsStatus.LIVE),
            SettingsSub("appearance.splash", "Splash Screen", SettingsStatus.LIVE),
            wip("appearance.colours", "Colours", "v2"),
            wip("appearance.fonts", "Fonts", "v2"),
            wip("appearance.presets", "Presets", "v2"),
            wip("appearance.ambient", "Ambient Mode", "v2"),
        )
    ),
    SettingsCategory(
        // Kept apart from Appearance for now (Roger, 2026-10-07): they belong together, and Roger is
        // still deciding how to link them.
        "layout", "Layout", listOf(
            SettingsSub("layout.systembar", "System Bar", SettingsStatus.LIVE),
            SettingsSub("layout.modulepanel", "Module Panel", SettingsStatus.LIVE),
            wip("layout.elements", "Elements", "1.9.x"),
            wip("layout.overlays", "Overlays", "v2"),
        )
    ),
    SettingsCategory(
        // DASH-AA: the viewport's tenant, configured. Native's Apps category fills this slot. Its sound
        // controls live in Audio, with the rest of the sound.
        "androidauto", "Android Auto", listOf(
            SettingsSub("androidauto.connection", "Connection", SettingsStatus.LIVE),
            SettingsSub("androidauto.picture", "Picture", SettingsStatus.LIVE),
            SettingsSub("androidauto.night", "Night & Driver Side", SettingsStatus.LIVE),
        )
    ),
    SettingsCategory(
        "audio", "Audio", listOf(
            // DASH-AA 1.1.2: the machine's sound settings and the car's sound menu in one (Roger,
            // 2026-10-07). Mixing became Mixer, and Microphone became Input.
            // 1.1.3 (Roger, 2026-10-08): renamed for what they hold, and the equaliser first, as the one
            // used most. The ids stay as they were, so nothing anywhere resets.
            SettingsSub("audio.sound", "Equaliser", SettingsStatus.LIVE),
            SettingsSub("audio.output", "Speakers", SettingsStatus.LIVE),
            SettingsSub("audio.input", "Microphone", SettingsStatus.LIVE),
            SettingsSub("audio.mixer", "Volumes", SettingsStatus.LIVE),
            // DASH-AA (Roger, 2026-10-05): calls through the head unit — their volume, independent of
            // music, and echo cancelling. Stays in Audio (Roger, 2026-10-07).
            SettingsSub("audio.calls", "Calls", SettingsStatus.LIVE),
            // DASH-AA 1.1.4 (Roger, 2026-10-08): the car sound in numbered slots, so fine-tuning is never lost.
            SettingsSub("audio.saved", "Saved", SettingsStatus.LIVE),
        )
    ),
    SettingsCategory(
        "connections", "Connections", listOf(
            wip("connections.wifi", "Wi-Fi", "1.1.6"),
            wip("connections.bluetooth", "Bluetooth", "1.1.6"),
        )
    ),
    SettingsCategory(
        // Rotation is back (Roger, 2026-10-07): on Linux it is part of the display settings.
        "display", "Display", listOf(
            wip("display.brightness", "Brightness", "1.1.5"),
            wip("display.blanking", "Screen Blanking", "1.1.5"),
            wip("display.touchscreen", "Touchscreen", "1.1.5"),
            wip("display.rotation", "Rotation", "1.1.5"),
        )
    ),
    SettingsCategory(
        "power", "Power", listOf(
            // Waits for a module that reports ignition — not a version, so it says so.
            wip("power.ignition", "Ignition Behaviour", "a module that reports ignition"),
            wip("power.actions", "Sleep, Shut Down, Restart", "1.1.7"),
            wip("power.leave", "Leave DASH", "1.1.7"),
        )
    ),
    SettingsCategory(
        // Modules is the hub for the whole boards-and-their-plumbing world (roadmap 1.5.10): the boards
        // (Module Manager), the pipes they connect over (Transport Manager), and the tools to watch them
        // talk (Serial + Signal Monitor). The old top-level Developer and Transports categories were
        // folded in here and removed.
        "modules", "Modules", listOf(
            SettingsSub("modules.management", "Module Manager", SettingsStatus.LIVE, fillsBox = true),
            SettingsSub("modules.transport", "Transport Manager", SettingsStatus.LIVE, fillsBox = true),
            SettingsSub("modules.serial", "Serial Monitor", SettingsStatus.LIVE, fillsBox = true),
            SettingsSub("modules.signal", "Signal Monitor", SettingsStatus.LIVE, fillsBox = true),
            // DASH's own decision log — not the wire (that's Serial Monitor) but the reasons behind the
            // refused / dropped / left-dormant outcomes that currently only reach logcat. Roger's call,
            // 2026-07-27: it belongs here beside the boards it explains, but it is v2 work, not v1.
            // Stays here (Roger, 2026-10-07): module logs live with the modules; DASH's own logs are
            // Developer › Logs.
            wip("modules.logs", "Activity Log", "v2"),
        )
    ),
    SettingsCategory(
        "vehicle", "Vehicle", listOf(
            wip("vehicle.patchbay", "CAN Patch Bay", "v3"),
            wip("vehicle.obd2", "OBD2", "v3"),
            wip("vehicle.slots", "Signal Slots", "v3"),
            wip("vehicle.dbc", "DBC Profiles", "v3"),
            wip("vehicle.profile", "Vehicle Profile", "v3"),
        )
    ),
    SettingsCategory(
        "notifications", "Notifications", listOf(
            wip("notifications.overlays", "Overlay Trigger Mapping", "v2"),
            wip("notifications.perapp", "Per-app Management", "v2"),
            wip("notifications.durations", "Durations", "v2"),
            wip("notifications.driving", "Driving-mode Rules", "v2"),
            wip("notifications.history", "History", "v2"),
        )
    ),
    SettingsCategory(
        "system", "System", listOf(
            SettingsSub("system.location", "Location", SettingsStatus.LIVE),
            wip("system.datetime", "Date & Time", "1.1.8"),
            // What DASH found on this machine — moved out of About DASH (1.1.1), because it will grow as
            // each new tab checks for what it needs.
            SettingsSub("system.machine", "This Machine", SettingsStatus.LIVE),
            wip("system.updates", "Updates", "1.1.8"),
            // About and Licence are separate tabs on purpose (roadmap 1.5.14). About is who made
            // DASH and where to find it; Licence is the GPL-3.0 §5(d) notice, the full text and the
            // third-party attributions — a legal surface with enough bulk to bury the other.
            SettingsSub("system.about", "About DASH", SettingsStatus.LIVE),
            // Licence claims the box height only because its full-text view scrolls 674 lines
            // lazily, which needs a finite height to measure against. It pays for that by applying
            // the shell's own content padding itself — see LicenceContent.
            SettingsSub("system.licence", "Licence", SettingsStatus.LIVE, fillsBox = true),
        )
    ),
    // Developer category removed at roadmap 1.5.10 (Serial + Signal Monitor moved under Modules) and
    // back in DASH-AA 1.1.1, last in the tree, for the machine and DASH itself — the module tools stay in
    // Modules. It sat inside System first; on screen it belonged on the main tree (Roger, 2026-10-07).
    // No safety gate — native decided nothing in settings sits behind one.
    SettingsCategory(
        "developer", "Developer", listOf(
            wip("developer.terminal", "Terminal", "1.1.9"),
            wip("developer.logs", "Logs", "1.1.8"),
            // A way out to a full desktop, when one is installed (Roger, 2026-10-07: "reboot into
            // desktop"). Proposed as a switch without a reboot — DASH closes, the desktop starts,
            // logging out brings DASH back. Needs the 1.2.x start-up script, so it waits for it.
            wip("developer.desktop", "Switch to Desktop", "1.2.x"),
        )
    ),
)
