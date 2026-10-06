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
        "layout", "Layout", listOf(
            // First in Layout (roadmap 1.5.15, Roger's call). Layout owns the structural decisions —
            // where the bar sits, how zones divide, where panels dock — and which way the whole
            // screen faces is the most structural of them, so it comes before the rest.
            // DASH-AA (Roger, 2026-10-05): Rotation is dropped with the other Android tabs — the
            // desktop compositor owns which way the screen faces, not the app.
            SettingsSub("layout.systembar", "System Bar", SettingsStatus.LIVE),
            SettingsSub("layout.modulepanel", "Module Panel", SettingsStatus.LIVE),
            // DASH-AA: the viewport's tenant. Android Auto *is* the viewport surface here, so its
            // settings sit with the other surfaces rather than under a category of their own. The App
            // Launcher placeholder is gone with it — the launcher in DASH-AA is Android Auto's own.
            SettingsSub("layout.androidauto", "Android Auto", SettingsStatus.LIVE),
            wip("layout.elements", "Elements", "1.9.x"),
            wip("layout.overlays", "Overlays", "v2"),
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
        "audio", "Audio", listOf(
            // DASH-AA (Roger, 2026-10-05): calls through the head unit — their volume, independent of
            // music, and echo cancelling. First in Audio because it is the one live tab here.
            SettingsSub("audio.calls", "Calls", SettingsStatus.LIVE),
            wip("audio.output", "Output Selection", "v2"),
            wip("audio.routing", "Audio Routing", "v2"),
            wip("audio.volume", "Volume Behaviour", "v2"),
            wip("audio.perapp", "Per-app Audio", "v2"),
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
    // DASH-AA: the Apps category is dropped — it managed Android apps installed on the head unit, and
    // DASH-AA has none; Android Auto's apps live on the phone.
    SettingsCategory(
        "system", "System", listOf(
            SettingsSub("system.location", "Location", SettingsStatus.LIVE),
            // About and Licence are separate tabs on purpose (roadmap 1.5.14). About is who made
            // DASH and where to find it; Licence is the GPL-3.0 §5(d) notice, the full text and the
            // third-party attributions — a legal surface with enough bulk to bury the other.
            SettingsSub("system.about", "About DASH", SettingsStatus.LIVE),
            // Licence claims the box height only because its full-text view scrolls 674 lines
            // lazily, which needs a finite height to measure against. It pays for that by applying
            // the shell's own content padding itself — see LicenceContent.
            SettingsSub("system.licence", "Licence", SettingsStatus.LIVE, fillsBox = true),
            // DASH-AA: Android Settings Links and Power are dropped with the Android tabs (Roger,
            // 2026-10-05). Leaving DASH-AA is Ctrl+Q; the window's own keys are listed in the README.
        )
    ),
    // Developer category removed (roadmap 1.5.10): its Serial + Signal Monitor moved under Modules;
    // Transport Diagnostics is absorbed by Transport Manager; the Log Viewer becomes Modules › Activity
    // Log, deferred to v2. Nothing is left behind a safety gate — every instrument is a normal tab.
)
