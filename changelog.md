# DASH-AA — Changelog

## Purpose of This Document

The honest record of DASH-AA, in upstream's format: what was built, what broke, what was fixed, what
is outstanding. **The version DASH-AA reports is read from the topmost entry**, as upstream's is, and
every entry names the upstream DASH version it mirrors on its own line, which the build also reads.

## Entry Format

```
## Version X.x.x

**Mirrors upstream:** DASH X.x.x
**Status:** …

…what was done and why…

**For native:** how DASH native follows: files to take as they are, what needs an Android
equivalent, what does not apply to Android. (Every entry from 1.1.1 on, Roger 2026-10-06.)
```

---

## Version 1.1.6

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-08. Tests pass (104), plus opt-in probes: `-Dconnections=1` reads the G14's
real NetworkManager and BlueZ (read only — nothing joined, paired or applied), and `-Dscreenshots=1` draws
every Connections tab, the keyboard and the pairing prompt against pretend networks (two Wi-Fi adapters, a
cable, a phone and modules). Roger tried it on the G14: "it all seems to work. i think its a bit clunky,
but itll do the job" — polish comes later, with the rest of the settings.

**What and why.** Connections, the fourth 1.1.x category, and two items from the no-desktop checklist that
would otherwise stop the touchscreen PC: **joining Wi-Fi** and **pairing Bluetooth**, both of which only a
desktop did until now. With no desktop nothing answers a phone asking "does this code match?", so calls
and Bluetooth modules could never be set up. Decided with Roger before building:
- **Ethernet is its own tab** (Wi-Fi · Ethernet · Bluetooth). Roger's car: a GL.iNet Mudi V2 SIM router
  (always on, battery-backed) on a cable through a USB hub's Ethernet port for the internet, Wi-Fi modules
  on the router's 2.4 GHz, and the laptop's own Wi-Fi left free to host 5 GHz for wireless Android Auto
  (1.4.x — direct phone-to-DASH, as the protocol is designed, never through the router). The router could
  not share its internet over USB.
- **Every car its own way** (Roger: "Maybe they don't want wireless Android auto… Maybe they don't need
  internet. Or maybe they want 2 WiFi adaptors for different things"). So each Wi-Fi adapter is given a
  **job** — Join networks, DASH network, or Off — and DASH assumes nothing else.
- **The on-screen keyboard** is needed, QWERTY, and **only while the car is stopped**, as a setting on
  by default. With no module reporting the speed or the handbrake it is always allowed (Roger: "if it
  doesn't have any of those signals then it will default to being allowed all the time").
- **Phones' Bluetooth music is refused by DASH itself**, on by default (built as recommended; Roger: "just
  build what you think we need").
- **The DASH network's settings show only once an adapter has that job** (Roger, after trying it).
- **One card hosting and joining at once** ("split card"): left for now (Roger) — see Outstanding.

**Done:**
- **The seams** (shared): `connections/NetworkSystem.kt` — adapters and their jobs, networks nearby and
  saved, the internet check, addresses, a phone's internet over Bluetooth; `connections/BluetoothSystem.kt`
  — the radio, name, visibility, devices by kind (`DeviceKind`: a **module** by the `D.A.S.H` marker of
  module-sdk.md §12, otherwise by the device's own class of device), and `PairingRequest` (confirm a code,
  type a PIN or passkey, show a code, allow). `connections/ConnectionsPreferences.kt` — DASH's own choices:
  jobs by hardware address, the DASH network (name, a generated 12-character password, band), the keyboard
  lock, the music guard.
- **NetworkManager** (`connections/linux/NetworkManagerNetwork.kt`) through `nmcli`, as the seat user —
  the requests GNOME's and KDE's network settings make; polkit lets the person at the seat make them with
  no password. Watched with `nmcli monitor`, not polled. Joining, forgetting, order (autoconnect priority,
  tens apart), joins-by-itself, metered, fixed or automatic address (reapplied in place where it can be).
  **The DASH network** is one NetworkManager connection, `DASH network`: an access point with WPA2-AES (the
  security every module radio speaks), its address shared, so devices on it get an address from DASH (and
  DASH's internet if it has any); 2.4 GHz by default (ESP32s hear nothing else), 5 GHz on channel 36 (no
  radar wait). Jobs are carried out by `apply()`, only when they or the adapters change.
- **BlueZ, and DASH as its pairing agent** (`connections/linux/BlueZBluetooth.kt`) over the system message
  bus with **dbus-java** (MIT, pure Java, new): BlueZ has to *call* DASH to ask the pairing questions,
  which the command-line tools cannot do. Registered as `KeyboardDisplay` and made the default agent while
  DASH runs (on GNOME it takes over from GNOME's while DASH is open). Questions wait 30 s for the person,
  then are refused. Devices paired through DASH are **trusted**, so they reconnect by themselves. Changes
  followed live (InterfacesAdded/Removed, PropertiesChanged; re-registered if BlueZ restarts). BlueZ's
  errors in plain words.
- **No Bluetooth music from phones** (`connections/linux/BluetoothMusic.kt`): a WirePlumber drop-in of the
  seat user's own (`~/.config/wireplumber/wireplumber.conf.d/51-dash-aa-no-bluetooth-music.conf`) offering
  WirePlumber's default Bluetooth roles less the two that receive music (`a2dp_sink`, `bap_sink`). The phone
  never sees a speaker, so the 1.0.6 crash cannot happen however its *Media audio* switch is set. Calls,
  Bluetooth headphones and internet over Bluetooth are untouched. WirePlumber restarts only when the
  setting changes (about a second of silence; on the first start of 1.1.6, once).
- **Connections › Wi-Fi** (`ui/connections/WifiContent.kt`): Wi-Fi on/off; each adapter's job; the
  network it is on, with the **address modules connect to** and Automatic/Fixed (one tap on Fixed keeps the
  address it has now); networks nearby (tap to join, a password field for a secured one, "Another
  network…" for a hidden one); saved networks in order (join now, move up and down, joins by itself,
  metered, forget); and, when an adapter hosts it, the DASH network's name, password and band.
- **Connections › Ethernet** (`EthernetContent.kt`): each cable port — status, speed, router, the address
  modules connect to, Automatic/Fixed. A USB hub's port appears when plugged in.
- **Connections › Bluetooth** (`BluetoothContent.kt`): on/off, the name phones see, visible for three
  minutes, *Music from phones*, paired devices grouped as phones, modules, sound, keyboards and controllers
  (connect, disconnect, forget; a phone's **Internet from this phone** — its Bluetooth tethering, the
  backup with no router; a module points to its transport), and search-and-pair.
- **The pairing prompt** (`PairingPrompt.kt`), over everything, since a phone can start pairing at any
  moment. A PIN opens the keyboard on its numbers, and the card moves above the keyboard.
- **DASH's on-screen keyboard** (`ui/keyboard/DashKeyboard.kt`): UK QWERTY, two symbol pages holding every
  printable character (and £, €), shift once for a capital and twice for caps lock, backspace that repeats
  when held, show/hide for secrets, the text shown on the keyboard itself (its box may be underneath). Any
  `KeyboardField` brings it up; a real keyboard keeps working. **`ui/keyboard/CarStill.kt`** decides
  "stopped": a reported speed under 1 km/h, or with no speed the handbrake on; **a speed outranks the
  handbrake** (a car can roll with it on); neither reported → unknown → allowed. Its setting is on
  **Display › Touchscreen**, beside the touchscreen it is for.
- **Transport Manager's Wi-Fi and Bluetooth buttons** open Connections instead of the desktop's settings.
  The desktop's settings stay as a fallback button where NetworkManager or BlueZ is missing.
- **Android Auto is told DASH covers it** while the keyboard or the pairing prompt is up, so a tap on them
  never reaches the phone.
- Licence list: dbus-java and SLF4J (both MIT).

**What broke on the way, and was fixed:**
- **The installed app would have had no Bluetooth at all.** The bundled Java runtime is cut down to the
  parts DASH uses, and dbus-java needs three more: `jdk.net` and `jdk.security.auth` (to tell the system
  bus who DASH is) and `java.xml`. Found by running the D-Bus call against the bundle's exact module list
  before Roger saw it; added to `app/build.gradle.kts`.
- Connection details first read with `nmcli -g`, which leaves a blank line for each empty field and so
  broke the split between connections; read keyed (`-f`) instead.
- The pairing prompt's PIN box and buttons sat under the keyboard.
- **Not DASH's, found while building:** Roger's "connection error" every five minutes since connecting the
  phone was GNOME's old *Rogers Phone Network* (Bluetooth tethering) retrying four times every five minutes
  with tethering off on the phone. Its automatic retry was switched off (`connection.autoconnect no`);
  Connections › Bluetooth › the phone › *Internet from this phone* switches it back.

**Outstanding:**
- **One Wi-Fi card cannot join and host at once** through NetworkManager. The G14's card can in hardware
  (same channel only), given a second, virtual adapter that needs admin rights to create — possible as a
  one-time install rule, like the USB phone rule. Left for now (Roger); a second USB Wi-Fi adapter does
  it today. Limits when built: the hosted network follows the joined one's channel (and drops when it
  moves), the radio's airtime is shared, and many 5 GHz channels (52–144) cannot be hosted on.
- **Not tested with no desktop** (the 1.1.x rule): on GNOME only.
- **Wi-Fi modules cannot be kept off a chosen network**: the Wi-Fi transport listens on every network, and
  limiting it means changing `WifiTcpTransport.kt`, still identical to native's.
- System › This Machine does not yet report NetworkManager and BlueZ.
- **1.2.x:** the automatic login must give DASH an *active seat session*, or NetworkManager's and BlueZ's
  policy will refuse its changes. To check when the PC is built.
- Wi-Fi passwords go to `nmcli` on its command line for the moment it runs (seen only by this machine's own
  processes); NetworkManager keeps the saved ones readable by root only.
- The keyboard opens whenever a field gets focus, so on the laptop it also appears when typing with the
  real keyboard (Close hides it).
- Polish (Roger): the tabs work but are "a bit clunky".
- The music guard refuses Bluetooth music **always**; the rule is only *while Android Auto is active*.
  Following Android Auto (allowed with no phone projecting) is planned with the polish — roadmap,
  *Later*, *Sound sources*.

**Bible (Roger, 2026-10-08, after release):** CLAUDE.md's hard-won rules now read **no Bluetooth music from
the phone while Android Auto is active**, refused by DASH itself, in place of "the phone must have *Media
audio* off". Sound sources (Bluetooth music, line in, USB stick or SD card) added to the roadmap.

**For native:**
- **Take as they are:** `connections/NetworkSystem.kt`, `connections/BluetoothSystem.kt`,
  `connections/ConnectionsPreferences.kt`, `ui/connections/` (all four files), `ui/keyboard/` (the keyboard
  and `CarStill`, for a head unit with no keyboard app), and the keyboard setting in `DisplayTabs.kt`.
- **Needs an Android equivalent:** a `NetworkSystem` from `ConnectivityManager` and `WifiManager` (joining
  through Android's Wi-Fi panel or suggestion API; no jobs — adapters reported as Android's, the DASH network
  as absent, since Android's hotspot is the system's), and a `BluetoothSystem` from `BluetoothAdapter`
  (bonded devices, discovery, `createBond()`; Android shows its own pairing dialog, so no `PairingRequest`
  is raised). The tabs leave out what those report absent.
- **Does not apply:** `connections/linux/` (NetworkManager, BlueZ over D-Bus, the WirePlumber music guard
  — Android does not crash on Bluetooth music beside an app's), and the jlink module list.

---

## Version 1.1.5

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-08. Tests pass (91), plus opt-in probes: `-Ddisplay=1` reads the G14's real
GNOME and has Mutter *check* (never make) DASH's setup and a portrait one; `-Dkwin=1` drives a headless KWin
(written, not yet run — see Outstanding); `-Dscreenshots=1` draws every Display tab against two pretend
screens. Roger tested Rotation on the G14 in the car's way of working: turned to portrait, **Android Auto
came back tall and filled the viewport** ("it looked great"), and the top of the picture landed on the
panel's left edge, as the touchscreen mapping assumes.

**What and why.** Display, the third 1.1.x category. It began as rotation, brightness and blanking. Roger
widened it while it was being built: with no desktop, DASH's display settings are the machine's only ones,
so "what we build has got to replace what is in KDE" — resolution, refresh rate, HDR, everything — all in
1.1.5. The final distribution will probably run KWin, the laptop runs GNOME, so it "has to work with both".
Several screens came up along the way (a friend's Infiniti has one screen for the infotainment and one for
the heating); the machine side is built now, and **screen roles** are planned for later (roadmap, *Later*).

**Done:**
- **The seam** (`display/DisplaySystem.kt`, shared). The Display tabs talk only to `DisplaySystem`: the
  screens (`Screen`: on/off, main, place, size, scale, quarter turns, modes, mirroring, and — null when
  not offered — adaptive sync, overscan, HDR, colour range, brightness), `DisplayFeatures`, touchscreens,
  and a plain-words failure. `transformFor`/`orientationOf` map native's four orientations to a display
  program's turns, for a landscape panel or a portrait one.
- **Three display programs, one DASH** (`display/linux/`). `LinuxDisplay` asks each in turn and keeps the
  first that answers: **Mutter** (`MutterBackend`, GNOME's `org.gnome.Mutter.DisplayConfig` through
  `busctl`, its JSON read with kotlinx; every change *checked* by Mutter first, then made *temporary*),
  **KWin** (`KWinBackend`, `kscreen-doctor` and its JSON) and **wlroots** (`WlrootsBackend`, `wlr-randr`).
  Values are each program's own documented words (Mutter's DisplayConfig XML: colour mode 1 is BT.2100,
  RGB range 1/2/3 auto/full/limited, adaptive sync is a mode's `+vrr` twin). No root anywhere: each is a
  request the seat user may make, the same ones the desktops' own settings make.
- **DASH's memory of the screens.** What the tabs keep is remembered per screen by its own identity (maker,
  model, serial — `display/screens.json`), not the socket, and applied at every start and whenever a
  remembered screen is plugged in. A screen's place is kept as which side of the main screen it is on;
  after any size change the screens are packed edge to edge from the main one, so no display program is
  asked for a gap or overlap. The screens as DASH found them are written to `display/found.json` on the
  first change (a crash cannot lose them) and **put back when DASH closes**. On the laptop GNOME's own saved
  layout is never touched; with no desktop, DASH's memory is the machine's display settings.
- **Display › Screens** (`ui/display/ScreensContent.kt`): a picture of how the screens sit (tap one to set
  it up); per screen: use it (off/on), make it main, its own picture or the main one's (mirror), which side,
  resolution, refresh rate, adaptive sync, fit-to-edges (overscan — on/off on GNOME, a percentage on KWin)
  and scale. **A screen plugged in for the first time is asked about** — Extend · Mirror · Off — once.
- **Display › Rotation** is native's tab (`RotationContent.kt`, its tiles and glyphs, native's two
  preferences, `DashOrientation.kt` identical through an `ActivityInfo` shim). The whole screen turns
  through the display program, so other programs and the touchscreen turn with it. Auto, with no tilt
  sensor, is the screen as DASH found it. With **no display program**, DASH turns **its own picture**
  inside its window (`ui/rotation/TurnedWindow.kt`) — laid out in the turned shape, so Android Auto
  renegotiates a portrait picture there too; `DASH_DISPLAY=window` tries it on GNOME.
- **"Keep this?"** (`ui/display/DisplayCommon.kt`, `DisplayTrial`). Rotation, resolution, refresh, mirroring,
  a screen off, HDR and colour range apply at once and go back by themselves after 15 s unless kept. The
  countdown lives outside the tabs, so the window reflowing or settings closing cannot strand it. A change
  being tried is never remembered. This differs from native's Rotation tab, which applies with no
  confirmation: on Linux a wrong turn can leave the touchscreen pointing elsewhere with nothing to tap.
- **Display › Brightness** — each screen DASH can set (GNOME's own backlight control, KWin's, or logind's
  `SetBrightness` on the built-in panel with no display program to ask), **by day and by night**: night is
  while a module reports `headlights_on`, like a car's dimmer. **Display › Colour** — HDR and colour range
  where a screen has them (the G14's panel offers no HDR, by Mutter's word), and **night light**: off, on,
  or with the headlights, with its warmth (GNOME's own night light, KWin's, or `gammastep` on wlroots;
  GNOME's settings are put back when DASH closes). **Display › Screen Blanking** — never, or 1–30 minutes
  with nobody touching DASH, dimming for the last half-minute; any touch, key or mouse movement wakes it.
  While DASH runs, GNOME's own blanking is held off (`gnome-session-inhibit`). The rules are
  `display/DisplayRules.kt` (shared), settings `display/DisplayPreferences.kt`.
- **Display › Touchscreen** — every touchscreen (`EvdevTouch.touchscreens()`, by USB id), which screen its
  touches land on (GNOME's per-device setting, KWin's `InputDevice.outputName`), and a box to try it.
- **The touchscreen turns with the screen.** DASH reads it directly from the kernel, in the panel's fixed
  corners, so `EvdevTouch.panelToScreen` turns each touch by the main screen's turn, and again by DASH's own
  turn inside the viewport. Confirmed on the G14: Portrait puts the top of the picture on the panel's left
  edge, which is the turn the mapping makes.
- **Android Auto in portrait, proven.** The portrait video modes (720×1280, 1080×1920) go beyond aasdk's
  list; their first real test, on the Pixel 8 Pro, filled the viewport edge to edge.

**What broke on the way, and was fixed:**
- Keep saved Rotation's two preferences one after the other, and the half-saved pair between flicked the
  screen; the app's listener now settles for 300 ms.
- Colour range and night light wrapped their words in the narrow control column; they take the full width.
- The first build asked about a new screen only if it arrived after DASH started; a screen already plugged
  in at first start is now asked about too.

**Outstanding:**
- **KWin is untested on a real KWin.** It could not be installed on the G14 (the CachyOS mirror served
  broken `kwin` and `layer-shell-qt` packages). Roger's home PC runs KDE: run DASH there (it finds KWin when
  GNOME does not answer) or `./gradlew test --tests '*KWinProbe*' -Dkwin=1`. Also untested: wlroots.
- **Not tested by Roger yet:** Screens, Brightness, Colour, Blanking and Touchscreen on the G14 (checked by
  the live probe and headless drawings only), and anything with a second screen.
- **Touchscreen calibration** is not built: only KWin allows it without root, and there is no touchscreen
  here. Nothing touchscreen-side has met a real touchscreen.
- **Scale is in two places**: Screens has the machine's scale, Appearance › Size & Scale DASH's own. DASH
  takes up a new machine scale at its next start. One control or two is Roger's call.
- **A new screen is asked about only in Display › Screens**: a prompt over DASH needs a notification
  surface (v2 Notifications) or the screen-roles design.
- With several screens, DASH stays on the screen it started on when another is made main (screen roles).
- Night light "always on" on GNOME sets GNOME's schedule to the whole day; to watch on the laptop.

**For native:**
- **Take as they are:** `display/DisplaySystem.kt`, `display/DisplayPreferences.kt`,
  `display/DisplayRules.kt` (brightness by day and night, night light, blanking — `UserActivity.touch()`
  from the activity's `onUserInteraction`), `ui/display/DisplayCommon.kt` (the keep countdown), and
  `RotationContent.kt` — native's own tab with the countdown, if Roger wants it on Android too.
- **Needs an Android equivalent:** an Android `DisplaySystem`: `rotate` is native's
  `requestedOrientation` code; brightness is the window's `screenBrightness` (or `Settings.System` with
  permission); blanking is `FLAG_KEEP_SCREEN_ON` off and the system's timeout; one screen, no arrangement,
  no colour range — reported absent, and the tabs leave them out. Night light only where the device
  offers it.
- **Does not apply:** `display/linux/` (the three display programs, logind's backlight),
  `ui/rotation/TurnedWindow.kt`, the Screens and Touchscreen tabs, the `ActivityInfo` shim.

---

## Version 1.1.4

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-08. Tests pass (69), including the opt-in probes on the G14's real PipeWire
(1.6.9), which now **measure** loudness, speed volume and time alignment: tones and clicks in, recorded out,
compared with the design. Roger tested it on the G14 and accepted it ("works well").

**What and why.** The last of the three Audio stages. Roger asked for loudness before anything else: "I
don't want some cheap naf piece of crap. I want it to be Hi-Fi level." We went through how loudness works
(the equal-loudness contours, Fletcher–Munson to ISO 226) and the three schools — a fixed "LOUD" boost,
correction that follows the volume (Audyssey Dynamic EQ, YPAO Volume), and correction that also measures
the music (Dolby Volume). Roger chose the second, from a comfortable volume the user sets: anything more
advanced, microphones included, belongs to a sound module, which DASH's design already allows. He then
asked for everything at once: speed-dependent volume with an effect level, and per-speaker time alignment,
pulled forward from "later". Testing it, he asked for **saved sound setups**, so a slip (a speaker moved to
another device) can never lose hours of tuning.

**Done:**
- **Loudness** (Equaliser, its own section). **Off, 1–4**: a quarter, a half, three quarters or all of the
  full correction. The correction is worked out from **ISO 226:2003**'s equal-loudness contours (formula and
  table 1, `audio/Loudness.kt`): for each frequency, the level it needs to sound as loud, relative to 1 kHz,
  as it did at the comfortable volume — taken as the 80-phon level music is mixed at. Nothing at the
  comfortable volume or above; more the further down. Because the contours bunch together in the bass, the
  correction is always smaller than the turn-down, so **no frequency ever plays louder than it did at the
  comfortable volume**: loudness needs no headroom and asks nothing new of a speaker.
  - It is made by a fixed bank of nine filters (six low shelves an octave apart from 25 Hz, bells at 2 and
    5 kHz, a high shelf at 12 kHz), whose gains are fitted by least squares (Gauss–Newton) to the curve.
    Fitted, it follows the full correction **to within 0.53 dB from 20 Hz to 12.5 kHz at every volume and
    level**. Measured through PipeWire's own filters, the result matches the design to 0.02 dB (40 Hz:
    designed +7.98, measured +7.96; 100 Hz +5.59/+5.59; 1 kHz 0/0; 10 kHz +1.24/+1.24).
  - **Comfortable volume**, a stepper and **Set** (to the volume now). Turning loudness on for the first time
    sets it to the volume then. Loudness follows **DASH's main volume**, read exactly in decibels (PipeWire's
    volumes are cubic, so 60·log₁₀ v). When the volume buttons turn Android Auto's own volume, the tab says
    loudness cannot see that one.
  - A first fit was within 1.5 dB; retuning the bank (the dip at 1–6 kHz needs two bells, and the top shelf
    belongs at 12 kHz) brought it to 0.53. A figure typed from memory into a test was wrong (ISO's 40-phon
    100 Hz is 64.37 dB); the formula was right.
  - Told to Roger as ISO 226:2023, then corrected: what is built is 2003, whose figures could be checked.
    2023 differs slightly; moving to it is a change of table.
- **Speed volume** (Speakers › Volume). **Off, 1–10**, for a quiet car or a noisy one (Roger). Nothing below
  30 km/h, then +0.5 dB × level for each doubling of speed, at most +12 dB: at 5, about +5 dB at 70 mph.
  It rises and falls 0.5 dB each 0.3 s (seven seconds for the full 12), never past what full volume would
  give, and applies to calls as well as music. Offered **only while an installed module declares
  `vehicle_speed`**; the speed counts only while such a module is active, so a module unplugged at speed
  does not leave the sound up (`audio/VehicleSpeed.kt`). Measured: +6 dB asked, +6.00 heard.
- **Time alignment** (Speakers, its own section). On, each output the layout uses has a **distance** from
  the listener's head, 0–500 cm in 5 cm steps (100 by default), and shows how long it waits; the furthest
  plays at once and the nearer ones wait for its sound (34 300 cm/s). Each side of a stereo position is set
  on its own — the driver sits off-centre. Measured with clicks through a silent probe sink: 40 cm designed
  as 1.17 ms, heard as 1.15 ms (55 samples: PipeWire takes whole samples, rounding down — 7 mm).
- **Audio › Saved**, a new tab after Calls (Roger chose numbered slots, like a radio's memory buttons):
  five slots, **hold to save, press to load**. A slot keeps the whole car sound — speakers with devices,
  outputs, levels and distances, equaliser, balance, fade, crossover, loudness and its comfortable volume,
  surround, speed volume, calls — but not the volume or whether Car sound is on. Each shows when it was
  saved and what it holds; the one matching what plays now reads **In use**. **Undo last load** puts back
  what a load replaced, and undoing again goes forward. Each slot is a file
  (`~/.local/share/dash-aa/files/sound/slot1.json`…), to copy as a backup or share
  (`audio/SoundMemories.kt`, `ui/audio/SavedContent.kt`).
- **Changing a speaker's device keeps its tuning**: its level and distances, and its outputs when the new
  device has the same ones. Before, it reset all three.
- **How it is built** (`audio/linux/PipeWireChain.kt`). The way in gains loudness's nine filters each side
  after the equaliser, and speed volume rides on anti-distortion's gain (and on a gain at the calls' way in,
  which was a copy). Its shape goes to 5, so the first start after the update swaps it once, as switching off
  and on would. The watch (every 0.3 s) follows DASH's volume and the speed and sends only the controls
  that moved. The layout gains a delay after every output's mixer (shape 2: one restart on first start).
  `SoundControl` gains `LOUDNESS`, `LOUDNESS_REFERENCE` (a processor that judges the level itself offers
  loudness without asking for a comfortable volume), `SPEED_VOLUME` and `TIME_ALIGNMENT`; `SoundProcessor`
  gains `speed(kmh)`.

**Outstanding:**
- **Speed volume is untested in a car**: it needs a module that reports `vehicle_speed`. The curve and the
  gain are tested; the feel on the road is not.
- **In the car**, with real speakers: loudness against road noise, and time alignment measured from the
  driver's seat. With no desktop, with the 1.2.x session.
- **ISO 226:2023** in place of 2003, if wanted: a table change.
- A slot saved with a device that is later unplugged loads it as "Not connected", as any chosen device.
- From 1.1.3 still: GNOME's sound settings list DASH's parts; the moment after a PipeWire restart; a sound
  module going quiet if DASH goes silent.

**For native:**
- **Take as they are:** `audio/Loudness.kt` (ISO 226 and the fit — pure maths), `audio/VehicleSpeed.kt`,
  `audio/SoundMemories.kt` (native's `filesDir`), `audio/CarSound.kt` (the new settings, `outputDelays()`,
  `speedBoostDb()`, the new `SoundControl`s, `SoundProcessor.speed`), `ui/audio/SavedContent.kt`.
- **Take, removing the Android Auto parts:** `ui/audio/AudioContent.kt` (loudness on the Equaliser tab;
  speed volume and time alignment on Speakers; a device change keeping its tuning);
  `ui/settings/SettingsTree.kt` and `content/SettingsContent.kt` (Audio › Saved); `DashApplication`'s
  `soundMemories` and the speed feed (native's Application).
- **Needs an Android equivalent:** native's `SoundProcessor` can offer loudness only if its equaliser can
  carry the curve — Android's `Equalizer` effect has fixed bands, so it would fit `Loudness.target()` to
  those bands, following `AudioManager`'s volume in decibels — and speed volume as a gain on its own output
  (or the `Equalizer`'s overall level). Time alignment has no non-root Android path; native offers none,
  and the tab hides it by the processor's word.
- **Does not apply:** the PipeWire graph and its shapes.

---

## Version 1.1.3

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-08. Tests pass (52), including the opt-in probes on the G14's real PipeWire
(1.6.9, WirePlumber 0.5.17), which now play a tone through the chain and measure it at the far end. Roger
tested every setting on the G14 and accepted it ("I really like it… I like the way it works").

**What and why.** The second of the three Audio stages (roadmap 1.1.x, split into three by Roger on
2026-10-08). Roger asked for each part of the car to be played by a device of his choosing, as in his XF
(front and rear doors, surround speakers on the parcel shelf, a centre and a subwoofer in the dashboard),
planned for his X-Type. Then, going through the problems found one by one, he settled anti-distortion, the
restart protection, where calls play, `sound_ready`, controls that follow the speakers and the processor,
the tab names, and the surround effect — pulled forward from 1.1.4.

**The tabs** are now **Equaliser · Speakers · Microphone · Volumes · Calls** (Roger: Sound → Equaliser,
moved to the top as the one used most; Output → Speakers; Input → Microphone; Mixer → Volumes). Their ids
are unchanged, so no setting was reset.

**Done:**
- **Car sound** (Speakers, at the top). On, all sound goes through DASH's own output and on to the
  **speaker layout**: **Front** (front doors), **Rear** (rear doors), **Surround** (parcel shelf),
  **Centre** and **Subwoofer**, each a device or **None**, each with its own **level** (its amplifier
  gain). A device with more than one way to be used shows **Outputs**: on a stereo card the subwoofer or
  centre can take both sides, the left or the right; on a 7.1 card each position picks its pair or output,
  and starts on the one named for it. A device chosen but unplugged stays chosen and says "Not connected".
  An output given to two positions plays both. Turning Car sound on for the first time puts Front on the
  device playing now. Off, Speakers is as Output was at 1.1.2.
- **Surround's Mode** (Roger: "full stereo, surround, wide surround"): **Full stereo**; **Surround**, half
  of left minus right, the same to both shelf speakers (Pro Logic's single surround channel); **Wide
  surround**, true Hafler — left minus right on the left, right minus left on the right, as speakers wired
  across the two positive terminals. Voices, recorded the same in both channels, cancel; the room and
  anything panned wide remain. A **Delay** (Off, 5–30 ms) makes the effect sound like the room rather than
  another speaker. Not called Dolby or Pro Logic (Dolby's marks). Mode and delay change live; balance still
  moves the effect; calls never play it.
- **Equaliser**: a **ten-band equaliser** (31 Hz–16 kHz, an octave apart, ±12 dB in 1 dB steps), drawn as
  one control with a button above and below each band (the design language has no slider), and **Flat**;
  **Anti-distortion** (Roger: on by default) — everything is turned down by the biggest boost before the
  equaliser, so the boosted band ends at 0 dB and nothing can crackle; **balance** (Left 10 to Right 10);
  **fade**, the front doors against the rear doors only, offered only when both have a device (Roger: the
  shelf is not part of fade); and **Crossover**: where the subwoofer stops (40–200 Hz, 80 by default, only
  with a subwoofer) and a **low cut** for the other speakers (Off, 40–200 Hz). Linkwitz-Riley, 24 dB an
  octave. Balance moves the centre only as far as it moves the middle; neither balance nor fade touches
  the subwoofer.
- **Calls** (Calls tab, with Car sound on): **Calls play through** *All speakers* / *Front only* /
  *Driver's side* (the driver's front speaker, from Android Auto's driver side), and **Subwoofer in calls**.
  Only the choices the layout makes sense of are offered (Roger: with only front speakers, *Front only*
  is pointless): *Front only* when Rear or Surround has a device, *Driver's side* when Front does, the
  subwoofer when there is one. Calls have their own way in, "DASH calls", with the low cut and crossover
  but not the music's equaliser, balance, fade or surround effect, so the choice changes mid-call with no
  gap. Anything marked as a call (`media.role` Phone or Communication; DASH-AA's own call audio now is)
  is sent that way.
- **Controls follow the processor and the speakers.** The processor declares what it offers
  (`ProcessorState.offers`: layout, equaliser, anti-distortion, balance, fade, crossover, low cut, call
  routing, surround effect), and the tabs show only those. DASH's PipeWire chain offers them all; a sound
  module or Android will offer fewer, and nothing that does nothing is ever shown.
- **Volume.** With Car sound on, DASH's output is the default and its volume is the volume (so the
  start-up limit and the volume buttons' *Machine* turn it). The layout's devices sit at full, like
  amplifiers. Turning on gives DASH's output the volume the speakers had before they go to full; turning
  off gives it back first. The loudness never jumps.
- **PipeWire restart protection** (Roger: nothing out of the speakers until DASH has control again). DASH
  notices PipeWire going; when it returns, the layout's devices are muted as they reappear, DASH waits for
  its chain, moves anything that landed straight on a speaker back onto its output, restores the volumes
  and settings, and only then unmutes. The WirePlumber rule mentioned on the way was not needed: muting
  covers it.
- **`sound_ready`** (Roger: the amplifier remote wire, sent to modules). DASH raises it into the sourceless
  core itself, so any subscribed module hears it like any other system signal — no change to the wire
  (transport.md already lets DASH relay what it "internally generates"). **false** before the sound stops,
  while it starts, restarts, changes layout or is restored, and as DASH closes (a shutdown hook, before the
  transports go); **true** once it is back as the user left it. With Car sound off, it follows PipeWire.
- **How it is built** (`audio/linux/PipeWireChain.kt`). **PipeWire owns it and DASH only adjusts it**: two
  of the seat user's systemd services, each a small `pipewire -c` process with its config in
  `~/.config/pipewire/`. They start with PipeWire whether DASH runs or not. No root.
  - `dash-aa-sound`, **the way in**: DASH's output → anti-distortion → equaliser → low cut and subwoofer
    crossover → the surround difference and its delay, offered on as left, right, low and surround; and
    the calls' own way in beside it. Its shape never changes in use, so it never restarts while it runs;
    every control changes live (`pw-cli set-param`, about 16 ms). Its first line names its shape: a DASH
    that changes the shape (an upgrade) swaps it as switching off and on would, so nothing blasts.
  - `dash-aa-speakers`, **the layout**: for music and for calls, a mixer for each output the layout uses
    (levels, balance, fade, surround mode and call routing, all live), then PipeWire's combine-stream,
    which sends each device its outputs and lines up separate cards' delays. Changing which device plays
    what restarts only this, for about a second of silence.
- **What broke on the way, and what it forced** (each found on the G14):
  - Restarting the part apps play into sends whatever is playing to a raw device, at full volume with no
    processing, and **it does not come back**. Hence two services, and the way in never restarting.
  - A stream told to play into the layout does not relink when the layout restarts (`node.dont-reconnect`
    or `node.dont-fallback`). So the way in **offers its sound as a recording** and the layout takes it as
    it starts, which always links; the layout's service waits for the way in (and fails, to be retried, if
    it never comes).
  - **When DASH's output vanishes, WirePlumber makes DASH's internal splitter the default and remembers it
    as the user's choice**, so the sound returns with the equaliser bypassed. While Car sound runs, a
    default left on one of DASH's internal parts is put back on DASH's output; a real device the user picks
    is left alone. Found because a probe started and stopped a copy of the chain under the real names
    beside Roger's live one and moved his default; the probes now use names of their own.
  - **A filter output that is both a final output of the chain and feeds another filter plays silence.**
    The surround effect's tap on left and right silenced the whole chain in Roger's first test of it. Every
    final output now goes through a copy of its own, as PipeWire's examples do — and the probes now play a
    tone and measure it at the far end, since checking links alone had passed while nothing played.
  - A high-pass filter at 0 Hz passes everything, so the low cut's *Off* is a setting, not a restart.
- **DASH stopping or starting never changes the sound.** Only switching Car sound off stops the services.
  A DASH that merely starts with it off (a fresh data folder, a test) leaves a running chain alone.
- **Capability detection:** with no PipeWire, a PipeWire older than 1.0 (no combine-stream), or no systemd
  user manager, Car sound doesn't appear, Speakers says why, and the rest of Audio works as at 1.1.2.
- **The Bible** (`docs/system_commands.md`, changed on Roger's word): `sound_ready` and `screen_on` in a
  new *Head Unit* section; a seat belt **warning** for every seat (driver, passenger, rear left / centre /
  right, third row left / centre / right) — the module decides when, DASH does not judge who sits where;
  and `engine_running`, which `ignition_state` cannot say. All registered in `SystemCommands.kt`. The
  existing `seatbelt_driver_fastened` / `seatbelt_passenger_fastened` stay, for Roger to tidy.
- **Tests:** `CarSoundTest` (what each output plays; balance, fade between the doors only, shared outputs;
  the three surround modes and their balance; calls' routing and choices; anti-distortion; output choices
  and names; storage). `PipeWireChainTest` (both configs as PipeWire reads them: ports that exist, no final
  output feeding another filter, one way-in shape whatever the settings, the calls' way in, one stream per
  device for music and for calls, the first line changing only with the layout's shape, mixers of more than
  eight inputs, the live controls). Opt-in (`-Dsound=1`), under names of their own so they can run beside a
  live Car sound: `PipeWireChainProbe` runs the configs on the real PipeWire, checks the whole route for
  music and calls, **plays a tone and measures it out of the way in and into the speakers**, moves a
  playing call onto "DASH calls" and a stray off a speaker back onto DASH, changes controls live, and
  restarts the layout while the app stays on DASH; `PipeWireChainUnitsProbe` has `systemd-analyze` check
  both units and times the layout's wait. All 52 pass.
- The roadmap: 1.1.4 is now *Audio: the rest* (loudness, speed-dependent volume), and Display, Connections,
  Power, System and Terminal each moved up one stage (roadmap and settings-tree placeholders).
- The README's Audio section describes the renamed tabs, Car sound, its services and how to remove them.

**Outstanding:**
- **In the car**, with real speakers and a second card; and **with no desktop**, with the 1.2.x session.
- **GNOME's sound settings** list "DASH" and DASH's internal parts. Choosing a real device there sends the
  sound around DASH. Left until the 1.2.x PC, which has no GNOME (Roger, 2026-10-08: he switches Car sound
  off before using the laptop for anything else).
- If PipeWire restarts, there is a moment between it returning and DASH's mute landing in which a stray
  could sound briefly. Short, and covered by `sound_ready` for a sound module's amplifiers.
- A sound module should go quiet if it stops hearing from DASH at all (DASH cannot send `sound_ready` false
  if it crashes or the cable comes out) — advice for the module documents when they come.
- Per-speaker time alignment, loudness, speed-dependent volume: 1.1.4 and later.

**For native:**
- **Take as they are:** `audio/CarSound.kt` (the settings, `feeds()`/`callFeeds()`, `SoundProcessor` and
  its `offers`), `audio/SoundReady.kt`, `audio/SoundPreferences.kt`, `audio/SoundSystem.kt`,
  `core/SystemCommands.kt` (the new signals). Native's `docs/system_commands.md` is not touched; the
  additions are recorded here and in DASH-AA's copy.
- **Take, removing the Android Auto parts:** `ui/audio/AudioContent.kt` (the Equaliser, Speakers,
  Microphone and Volumes tabs); `ui/androidauto/CallsContent.kt`'s *Calls play through* and *Subwoofer in
  calls* (native's Calls tab, if it gains one); `SettingsTree.kt` and `SettingsContent.kt` (names, order,
  the later stages renumbered).
- **Needs an Android equivalent:** a `SoundProcessor` that offers what Android can do — the equaliser
  (Android's `Equalizer` effect), perhaps anti-distortion — and nothing else: Android plays through one
  output and gives no non-root way to shape other apps' balance or fade. The tabs then show no layout,
  fade, crossover or surround effect, by the processor's own word. `sound_ready` on native follows its
  audio being up.
- **Does not apply:** `audio/linux/PipeWireChain.kt`, its services, and `aa/echo-cancel.conf`'s call role.

---

## Version 1.1.2

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-07. Tests pass, and the PipeWire side was checked against the G14's real
PipeWire (1.6.9). Roger accepted it ("im happy with that").

**What and why.** The first of the two Audio stages (roadmap 1.1.x). Roger: Audio is "a machine status and
settings tab mixed with dash specific car type controls". With no desktop there is no other sound panel,
so this stage makes DASH's Audio tabs the machine's sound settings: which speakers, which microphone, how
loud, and what else is playing. 1.1.3 adds the car side (equaliser, balance, fade, loudness), built so a
future sound module (Roger's 5-way active crossover) can take that processing over.

**The tabs** are now Output · Input · Mixer · Sound · Calls. Microphone became **Input**, Mixing became
**Mixer**, and **Sound** is a placeholder for 1.1.3. No setting was reset.

**Done:**
- **Output:** the machine's speakers as a list, the one in use filled. Choosing one makes it PipeWire's
  default, so every app follows it and WirePlumber remembers it. Each is named by the port in use
  ("Speakers", "Headphones") over the card's name. A USB sound card or headphones appear the moment they
  are plugged in, and go when pulled out. Then the volume (5% steps), mute, and a **start-up volume
  limit**: when DASH starts, the volume comes down to it if it was left higher, and is never turned up
  (off by default). Android Auto's Phone / DASH-AA choice sits beneath, in its own section.
- **Input:** the microphones, chosen the same way; input volume and mute; and **Test microphone**, a
  live level meter. It listens only while switched on and the tab is open, and records nothing. The rule
  that DASH-AA listens only when the phone opens the microphone still stands, and the user asks for this
  one. Android Auto's microphone choice beneath.
- **Mixer:** Android Auto's overall volume (still what the steering wheel moves), and a level each for its
  **music**, **directions and assistant**, and **system sounds**, beneath it. Lowering music under
  directions moved here. Under **Everything else**, a level for each other app while it plays. DASH-AA's
  own streams and the phone's Bluetooth nodes are left out: calls have their level in Calls, and the
  phone's Bluetooth music would only double Android Auto's.
- **Volume buttons, the user's choice** (Roger: "let it be decided by the user which it controls").
  The steering wheel's volume arrives as system messages from a SYSTEM module (`media_volume_up`,
  `media_volume_down`, `media_muted` — system_commands.md). Audio › Output › **Volume buttons** sends them
  to the **Machine** (the default output, in 5% steps) or to **Android Auto** (its own volume, as before,
  and still the default). They moved out of Android Auto's bridge into DASH (`audio/VolumeButtons.kt`).
  Before, they were heard only while a phone projected; now they work with no phone at all. Mute follows
  the choice: change it while muted and the old target is unmuted and the new one muted. A sound module
  becomes a third choice once one is designed. (interface.md's tree already has a "Volume behaviour"
  entry under Audio; this is it.)
- **How it talks to PipeWire** (`audio/linux/PipeWireSound.kt`): one `pw-dump --monitor` for the life of
  DASH reports every change as it happens, and changes go out as short `wpctl` commands. These run one at a
  time on their own thread and are killed after 3 seconds, so a hung sound system cannot stall DASH or
  Android Auto (the 1.0.5 rule). A stepper pressed quickly sends one command with the latest value, not
  ten. Volumes are shown as `wpctl` and every desktop shows them: the cube root of PipeWire's gain, which
  sounds even. If PipeWire stops, the tabs say so and the monitor picks it up again when it returns.
- **The seam** (`audio/SoundSystem.kt`): the tabs only talk to a `SoundSystem` interface, never to
  PipeWire, so they are shared code. PipeWire is DASH-AA's implementation.
- **Capability detection:** with no `pw-dump` or `wpctl`, every Audio tab says "PipeWire's tools are not
  installed — install pipewire and wireplumber" and nothing else is affected. **System › This Machine**
  gains a *Sound* line (PipeWire and its version, or what is missing).
- The README's Audio section describes the new tabs.
- **Tests:** `PipeWireSoundTest` feeds the graph batches shaped exactly as PipeWire 1.6.9 prints them
  (devices and their ports, defaults, volumes, removals, DASH-AA's and the phone's nodes left out, the
  level meter's scale). `PipeWireSoundProbe` (opt-in, `-Dsound=1`) runs against the real PipeWire. It
  plays a silent stream from a test app, sees it appear, sets its level twice in quick succession and
  reads 45% back, sees it go, and reads the microphone about 35 times a second. It never touches the
  machine's default devices or their volume. `VolumeButtonsTest` sends the wheel's messages through the
  real system state and checks each lands where it is pointed, an old press is never replayed, and mute
  moves with the choice.

**Outstanding:**
- **Tried in a session with no desktop** once the 1.2.x session exists, and with a real steering-wheel
  module sending the volume buttons.
- A sound card whose profile is switched off (an HDMI output with no screen plugged in) does not appear.
  WirePlumber switches it on when something is connected; choosing profiles by hand is not offered.
- WirePlumber remembers each app's level (by its application id), so a level set in the Mixer comes back
  the next time that app plays.

**For native:**
- **Take as they are:** `audio/SoundSystem.kt`, `audio/SoundPreferences.kt` and `audio/VolumeButtons.kt`
  (native's viewport tenant implements `ViewportVolume`, or the choice offers only the machine).
- **Take, removing the Android Auto parts:** `ui/audio/AudioContent.kt` (the Android Auto sections, and the
  Mixer's Android Auto levels, are DASH-AA's). `SettingsTree.kt`: Audio's tabs, Output · Input · Mixer ·
  Sound · Calls.
- **Needs an Android equivalent:** a `SoundSystem` on `AudioManager`: the media volume as the output's
  volume, and the start-up limit applied to it. Android chooses its own speakers, does not let one app set
  another's level, and keeps the microphone level to itself, so those lists come back empty and the tabs
  leave them out. The level meter needs the microphone permission.
- **Does not apply:** `audio/linux/PipeWireSound.kt`.

---

## Version 1.1.1

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-07. Tests pass; Roger looked it over on the G14 (Developer moved to the main
tree and the clock added on his look).

**What and why.** The first stage of 1.1.x, the settings panel reorganised for Linux (roadmap 1.1.x).
Native's tree was built around Android: System was mostly deep links into Android's settings. DASH-AA is
heading for a machine with **no desktop** (Roger: "the 'desktop' will be dash"), where there is nothing to
hand off to, so DASH's settings become the machine's settings. This stage moves every existing control to
its final home, so the later stages only add.

**The tree** (agreed with Roger, 2026-10-07): Appearance · Layout · Android Auto · Audio · Connections ·
Display · Power · Modules · Vehicle · Notifications · System · Developer.

**Done:**
- **Android Auto is its own category**, no longer under Layout, split into three tabs: **Connection**
  (status, on/off, Reconnect, Restart sound), **Picture** (video, frame rate, density) and **Night &
  Driver Side**.
- **Its sound controls moved to Audio:** **Output** (where Android Auto's sound plays), **Microphone**
  (whose microphone), **Mixing** (volume, lower music under directions). **Calls** stays in Audio. No
  setting is reset: they are saved by name, not by where they are shown.
- **System › This Machine:** the report of what DASH found on the machine, and its Copy button, moved
  out of About DASH. About keeps who made DASH and where to find it.
- **Developer is back as a category**, last in the tree, for the machine and DASH itself (the module
  tools stay in Modules, where 1.5.10 put them). It first went inside System as a fourth level — the
  panel was taught tabs inside tabs for it — but on screen it belonged on the main tree (Roger: "you were
  right before"). With nothing else using it, tabs inside tabs was taken out again, and the settings
  panel's navigation (`SettingsShell.kt`) is native's, unchanged. It was never committed; if Appearance ›
  Layout ever wants it, it is a small change to build again.
- **New categories and tabs** appear as honest placeholders saying when they arrive: Connections (Wi-Fi,
  Bluetooth — 1.1.4), Display (Brightness, Screen Blanking, Touchscreen, **Rotation** — 1.1.3; Rotation is
  back, reversing the 2026-10-05 drop), Power (Sleep / Shut Down / Restart, Leave DASH — 1.1.5; Ignition
  Behaviour waits for a module that reports ignition), System (Date & Time, Updates — 1.1.6), Developer
  (Logs — 1.1.6, Terminal — 1.1.7, **Switch to Desktop** — 1.2.x: Roger's way back to a full desktop
  when one is installed).
- **Modules › Activity Log stays in Modules** (Roger: module logs live with the modules).
- **With no phone projecting, the viewport shows a big analogue clock** instead of the weather scene
  (Roger: "a simple modern style analogue clock"). Plain and modern: no numerals, sixty fine marks with
  the hours heavier, three hands. Coloured from the theme like all DASH chrome, so a future theme
  changes it too; the settings for how it looks come later. The status line ("Connect your phone…") is
  unchanged beneath it. **The weather scene stays as the settings panel's landing.**
- The README's Android Auto section follows the new places.
- The roadmap gains the **no-desktop checklist**: everything the desktop does today that DASH must take
  over, each with its stage.

**Outstanding:**
- **The Bible is not updated.** The settings tree in `docs/interface.md` is still native's (2026-07-20
  addendum). It changes when Roger says so.
- **Where Power › Leave DASH goes** is left open (Roger: "the answer will become obvious once more of
  the shell is finished").
- **Appearance and Layout** stay separate until Roger decides how to link them.
- The README still tells the user to pair the phone in the desktop's Bluetooth settings. That stays true
  until 1.1.4 makes DASH the pairing helper.

**For native:**
- **Take as they are:** `ui/clock/AnalogueClock.kt` (plain Compose, nothing of Linux — native has no
  empty viewport to show it in today, but it is there for when it wants a clock),
  `ui/settings/content/ThisMachineContent.kt`, and the cut from
  `AboutContent.kt` (the report section removed; the rest of native's About is unchanged).
- **`SettingsTree.kt`:** take the categories and their order, Developer included. Native's **Apps** category fills the Android
  Auto slot. Native's tabs for Connections, Display and Power are its Android deep links
  (`SystemLinksContent`, `RotationContent`, `PowerContent`), split across those categories.
- **Does not apply:** the Android Auto tabs and the Audio tabs' contents (Android Auto settings).

---

## Version 1.0.9

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-05. Roger: music at its normal level, call volume independent of it.
**Call loudness settled:** 300% was only just enough because the phone's own in-call volume (sent over
Bluetooth, applied before DASH-AA's boost — WirePlumber's record showed `volume 0.216`) was at about 60%.
Turned up on the phone, it works. The 300% ceiling stays; DASH-AA does not override the phone's volume,
which stays the driver's control. Recorded in the README's troubleshooting.

**Found** *(Roger: "the music from spotify was also running louder. I think there was a mistake")*: it was
— mine, on the bench. DASH-AA's Spotify stream was playing at **15.625**, WirePlumber's internal figure for
250%. WirePlumber **remembers a stream's volume per application** and restores it to the next stream
from the same one. DASH-AA's streams were plain `pw-cat` streams, so they shared the identity "pw-cat"
with anything else using the tool — including the fake call stream in `CallEchoProbe`, which 1.0.8's
test boosted to 250%. WirePlumber remembered "pw-cat → 250%" and gave it to Spotify. DASH-AA's call code
behaved correctly; the shared identity was the fault, and my test is what tripped it.

**Fixed:**
- The polluted memory reset — "pw-cat" back to 100% (done on the G14, verified in WirePlumber's state).
- **Every DASH-AA stream has its own application identity** — `dash-aa.media-audio`, `dash-aa.speech-audio`,
  `dash-aa.system-audio`, `dash-aa.mic` — so their volumes are their own and nothing else on the desktop
  can leak into them. A fresh stream with its own id starts at 100%, confirmed.
- The call test's fake streams carry their own test identity, so a test boost can never be remembered for
  anything real.

**Noted:** the eighth run's log was a second DASH-AA started while one from the app menu still held the
phone (`SSLException` — two head units on one phone). Only one DASH-AA should run at a time; the
menu-launched one logs to the system journal (`journalctl --user | grep dash-aa`).

---

## Version 1.0.8

**Mirrors upstream:** DASH 1.7.1

**Status:** Built 2026-10-05, awaiting Roger's call test.

**Call volume** *(Roger: "the call volume is quite low… a slider to increase the volume of call playback
independent of the music… in the settings panel of dash in audio… it might need to be overdriven")*:
- **Settings › Audio › Calls** — the first live tab in upstream's Audio category — with **Call volume**,
  50%–300% in 10% steps. Above 100% the stepper says *boosted*: the phone sends the friend's voice at a
  fixed level, so the laptop adds the gain, and past full scale loud peaks may clip — the overdrive asked
  for. A stepper rather than a slider, as every level in DASH's design language is.
- **Set on the friend's voice stream itself, before the echo canceller** (`wpctl set-volume` on that
  stream), so the canceller subtracts the louder sound it is about to play; boosting after it would bring
  the echo back. Applied at once, mid-call included. Music is untouched.
- **Echo cancelling moved here** from Layout › Android Auto, so everything about calls is in one place.
- Verified against real PipeWire (`CallEchoProbe`): the voice stream reads 250% with the canceller still
  in line.

**Found and fixed — the microphone could outlive DASH-AA.** A test that died mid-call left its echo
canceller running, *still holding the microphone*: a DASH-AA killed outright, or crashing, during a call
would do the same. Now a shutdown hook stops the canceller on any normal exit, and on start DASH-AA stops
any canceller orphaned by an earlier one. The first version of that reaper matched *any* process whose
command line mentioned the canceller's name — it stopped the test's own shell — so it is now restricted
to the `pipewire` program running a DASH-AA canceller config, and verified to stop a deliberate orphan
and nothing else.

---

## Version 1.0.7

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-05. **Confirmed on a real call: "he says no echo now."**

**Echo cancelling on calls** *(Roger, 1.0.4/1.0.6: "he could hear himself")*. The friend's voice came out
of the laptop speakers, the laptop microphone heard it, and it went back to him. Built from the plan
recorded in 1.0.6:

- **For the length of a call only**, DASH-AA runs PipeWire's own echo canceller (WebRTC's), configured
  by `aa/echo-cancel.conf`: a *call speaker* that plays to the real speakers and remembers what it played,
  and a *call microphone* — the real microphone with that removed.
- **The phone's two call streams are moved through it** — the voice onto the call speaker, the phone's
  microphone stream onto the call microphone — with PipeWire's `target.object` metadata, which
  WirePlumber honours by relinking. Nothing else on the desktop is moved; default devices never change.
- **The call ends, the streams go, the canceller stops** — and the microphone with it. Metadata is
  cleared behind it.
- **Layout › Android Auto › Echo cancelling on calls**, on by default.
- **Verified on the G14 against real PipeWire** (`CallEchoProbe`, opt-in): two streams labelled exactly as
  WirePlumber labelled Roger's recorded call are moved through the canceller (mic → canceller → phone,
  voice → canceller → speakers) and everything is released when they end. Whether the friend still hears
  himself is the one thing only a call can say.

**Found while testing:** a Bluetooth address in PipeWire properties must be quoted — a bare colon is a
key separator. (Only the test needed it; DASH-AA never writes one.)

---

## Version 1.0.6

**Mirrors upstream:** DASH 1.7.1

**Status:** Built 2026-10-05.

**Found** *(Roger: "the connection seemed stable, but there was no audio… the track was playing, but no
volume")*: **WirePlumber crashed again (21:01:54), and the trigger is Bluetooth *music*, not the call.**
The journal shows an A2DP transport (`fd0 ready`, `set_delay_report`) opening from the Pixel the moment
before the crash: with the phone paired, the laptop also offers itself as a Bluetooth *speaker*, the phone
starts sending its music there as well as over Android Auto's USB, WirePlumber fails to create that
node ("can't get format") and its session stops joining any sound to the speakers. Pipe accepted 8 chunks
of Spotify, then nothing.

**Confirmed by Roger's sixth run (21:09–21:12):** with Media audio off on the phone there were no more
crashes; "Restart sound" recovered the one left from before; the call came through. The two drops at
21:10:00 and 21:10:10 were the **USB cable** (kernel: *"Cannot enable. Maybe the USB cable is bad?"*).

**Echo — next, ready to build (not yet in the app).** The recorded call shows WirePlumber wiring it as two
*streams*: `bluez_input.<phone>.0` (the friend's voice, role Communication) → the speakers, and the mic →
`bluez_output.<phone>.1`. Being streams, both can be retargeted. Prototyped on the G14: PipeWire's own
echo canceller (`aec/libspa-aec-webrtc`) run as `pipewire -c` with the config now bundled as `aa/echo-cancel.conf` creates
`dash-aa-ec-sink` and `dash-aa-ec-source`, wires itself to the speakers and mic, and leaves the default
devices alone. **Built at 1.0.7.** *(The plan as written:)* in `CallAudio`, when the phone's Communication streams appear, start that
process, then `pw-metadata -n default <voice id> target.object dash-aa-ec-sink` and
`pw-metadata -n default <mic stream id> target.object dash-aa-ec-source`; stop it when they go — so the
microphone is only open during a call.

**The remedy is the one every car uses:** on the phone, the laptop's Bluetooth entry carries **Phone
calls** only, with **Media audio** off. Android Auto's music already arrives over USB.

**Fixed in DASH-AA:**
- **A stalled sound system can no longer stall the phone.** The phone sends each audio chunk only after
  the last is acknowledged, so 1.0.5's "drop when the queue overflows" never fired — the queue never
  grows; the phone simply waited, then dropped Android Auto three times. Now a write held over 400 ms is
  acknowledged anyway, and everything after it until sound moves again. Tested with an output that never
  returns (`AudioStallTest`), and against real PipeWire that ordinary playback never trips it.

---

## Version 1.0.5

**Mirrors upstream:** DASH 1.7.1

**Status:** Built 2026-10-05.

**Found** *(Roger: "android auto is no longer connecting… it starts the splash screen for AA and then
hangs there")*: after the first Bluetooth call, **WirePlumber's own script crashed** setting up a
Bluetooth audio node (`create-node.lua:43: attempt to index a nil value`, 20:53:46) and from then on
joined no new sound stream to the speakers. DASH-AA opened the speakers **on the thread that listens to
the phone**; the open hung for 30 s, DASH-AA stopped answering, and the phone froze on its splash and
dropped. Two faults: WirePlumber's (cleared by restarting it, done with Roger's session running) and
DASH-AA's — no sound fault may ever stall the phone.

**Fixed:**
- **Audio goes straight to PipeWire with `pw-cat`**, one process per stream with a proper role (Music,
  Navigation, Notification); the microphone likewise (`Communication`). Java's sound API — which probes
  every sound device on the machine, the Nvidia HDMI audio included, before opening one — is now only a
  fallback where `pw-cat` is absent. "Can we offer sound?" is answered without touching a device.
- **Outputs open on their own threads**; the session only queues. If sound never opens, or the sound
  system stops taking it, chunks are dropped *and acknowledged*, so Android Auto carries on in silence.
- **Layout › Android Auto offers "Restart sound"** when, and only when, DASH-AA sees the sound system has
  stopped — the in-car fix for WirePlumber wedging, without a terminal.
- Verified on the G14: a real tone through the new path, 25 of 25 chunks acknowledged at real-time pace.

**Outstanding:** the echo on calls (1.0.4) — waiting on a recorded call to see how WirePlumber wires it.

---

## Version 1.0.4

**Mirrors upstream:** DASH 1.7.1

**Status:** Built 2026-10-05, awaiting Roger's call test.

**Found** *(Roger: "I tried to call my mate… it showed up on the Android Auto screen that I was calling
him, but the audio was still on the phone")*: **Android Auto never carries call audio over USB.** In a car
the phone connects to the head unit as a Bluetooth hands-free kit and only the call *screen* is projected;
the head unit tells the phone its Bluetooth address on a dedicated channel. DASH-AA offered no such
channel, so the phone concluded the car had no Bluetooth and kept the call on the handset.

**Implemented:**
- **Android Auto's Bluetooth channel** (aasdk's definitions): the laptop's address, **hands-free only** —
  offering music over Bluetooth too would let the phone send Spotify twice, over USB and over Bluetooth.
  Offered only when the machine has a powered Bluetooth radio. The phone's "are we paired?" is answered
  honestly from BlueZ's bonded list, and logged.
- **Call audio routing** (`CallAudio`): PipeWire presents a phone on a call as two Bluetooth *devices*
  and links neither, so a desktop call connects in silence. As the car's mixer, DASH-AA joins them — the
  phone's voice to the speakers, the microphone to the phone — with one `pw-loopback` each, started when
  the call's nodes appear and ended when they go. A node someone else has already connected is left
  alone; a Bluetooth *music* node is never touched.
- **Layout › Android Auto › Calls** says whether the phone is paired and calls will come through.
- The fake phone now checks the Bluetooth offer and the pairing answer.

**Roger paired the Pixel with the G14** the same evening ("Rogers Phone" — trusted, connected,
Handsfree Audio Gateway).

**Outstanding:**
- **Not yet heard.** Whether the phone takes the hands-free route, and what PipeWire names the call's
  nodes, is only answered by a call. The log says each step (`asks about Bluetooth pairing`,
  `call audio: phone → speakers`).
- **Echo.** Laptop speakers into the laptop microphone may let the other person hear themselves; the
  in-car setup (or PipeWire's echo cancellation) is the answer if it does.
- The phone also offers Bluetooth music (A2DP). If the laptop's sound system starts playing it alongside
  Android Auto's USB audio, music will double; not seen yet.

---

## Version 1.0.3

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-05, tested by Roger on the Pixel 8 Pro and the G14 trackpad.

**Outcome of the third run — the audio was never DASH-AA's.** Roger found it: *"my phone was still
connected by bluetooth to another device. I've disconnected that and it seems to work now."* The
diagnostic log agrees: the phone sent nothing DASH-AA left unanswered; each stream delivered sound in
real time (5.5 s of sound in 5.5 s) with every packet acknowledged; the phone stopped it three times at
~5.5 s, then played on once the other device was disconnected. The phone was handing its audio back to the
other Bluetooth device. Recorded in the README's troubleshooting. Acknowledging audio after playback
(1.0.2) stays — it is still what a real head unit does — and the diagnostics stay, throttled, because they
answered the question in one run.

**From the second run's log** *(Roger: "the audio is still a problem… I have to line it up right in the
middle of the icon")*:
- **Taps were about 2% off, and that is fixed** — *confirmed by Roger on the Pixel: "the taps are much
  better now."* This settles 1.0.1's open question: **Android Auto reads touches in its interface's own
  coordinates**, against a touchscreen declared the interface's size. Every tap arrived (held ~180 ms), so 1.0.2's tap fix did
  its job; the misses were aim. The viewport had 36 px margins either side and DASH-AA sent touches in
  whole-frame coordinates, margin added. **The touchscreen is now declared the size of the phone's
  interface and touches are sent in its own coordinates** — right whichever way the phone reads them.
- **Audio is not fixed, and 1.0.2's theory was wrong.** Spotify started at 15.87 s and the phone stopped
  it at 21.25 s, with no change of audio focus between. Acknowledging after playback (kept — it is still
  what a real head unit does) did not change it. A clean ~5 s cut-off is the shape of a phone waiting on an
  answer it never gets, and unanswered messages were logged only at debug level, so the log could not say.
  **1.0.3 is a diagnostic build for audio**: every unanswered message is logged with its contents, and
  each audio stream reports at its end how many packets came, how much sound that was, and how many were
  acknowledged.

---

## Version 1.0.2

**Mirrors upstream:** DASH 1.7.1

**Status:** Built 2026-10-05, awaiting Roger's second test. A response to the first real-hardware run.

**The first real run** *(Roger, 2026-10-05)*: the Pixel 8 Pro projected on the G14, restarting cleanly on
every layout change; **the real Climate module worked over Bluetooth through BlueZ** — the first module
on DASH-AA's Linux transport. Two faults, both in Android Auto, both on the trackpad bench:

**Fixed:**
- **Spotify played a few seconds and paused.** DASH-AA acknowledged each audio packet the moment it
  arrived. The acknowledgement is the phone's only clock, so the phone believed the car was playing far
  faster than real time, ran its player dry, and the player paused. **Audio is now acknowledged only once
  it has gone to the sound device** — real-time back-pressure, as a real head unit gives. Tested against
  the fake phone (no acknowledgement before the sound is played). Audio focus is now granted in kind
  (transient for transient) and logged, with every audio start and stop, so if the pause survives the
  next log shows why.
- **Zoom out and pinch did nothing.** A trackpad's two-finger swipe reaches DASH-AA as scrolling, which
  was ignored, and GNOME does not pass trackpad pinches to XWayland windows at all. **Scrolling is now a
  pinch**: two fingers placed either side of the pointer, spreading to zoom in or closing to zoom out,
  lifted when the scrolling stops. The mouse wheel does the same.
- **Some taps were missed** (Spotify's icon). A trackpad tap-to-click is a press and release in the same
  instant; **a tap is now held for at least 80 ms**, as a finger's would be. Only the main button acts as a
  finger now, so a right-click or two-finger tap no longer arrives as a stray touch.
- **Every tap is logged** (`DashAaTouch: down at x,y`) with the coordinates sent to the phone.

**Outstanding:**
- **If taps still miss, the margin mapping is the suspect** (1.0.1 *Outstanding*): the viewport here has
  side margins (60–114 px of the 1920 frame), and whether the phone wants touches in frame or content
  coordinates is unproven. The new tap log settles it.
- The zoom direction may feel reversed with natural scrolling.

---

## Version 1.0.1

**Mirrors upstream:** DASH 1.7.1

**Status:** Complete — 2026-10-05. The first build. Verified on the G14 without a phone, a module board
or a touchscreen (see *Achieved* for exactly what that covers, and *Outstanding* for what it does not).

**Scope:** *(Roger: "I want it to function exactly the same as the android app with one exception. The
viewport will run android auto… I want to be able to plug my dash modules into the laptop and them also
work with this fork.")* Fork DASH onto the laptop as a Linux application; keep the module system, panel
and interface exactly; make the viewport Android Auto, projected from the phone over USB.

**Decided** *(Roger, 2026-10-05, asked before building)*:
- **Wired USB Android Auto first**; wireless later.
- **Compose for Desktop**, porting DASH's own Kotlin/Compose so the module brain, panel renderer and
  settings are literally the same code.
- **Mirror upstream**: same package, paths and contents wherever possible, thin shims for Android bits.
- **The in-car screen is an external touchscreen** — so multi-touch matters.
- **The sourceless core feeds Android Auto** — night, speed, gear, parking brake, steering-wheel controls.
- **Idle viewport is the weather scene.**
- **Restart projection on a layout change, so Android Auto always fills its exact shape** (never letterbox).
- **Drop the Android-only tabs**; add one Android Auto tab.
- **Name: DASH-AA.** **Own changelog and roadmap from 1.0.1**, each naming the upstream version mirrored;
  reference documents copied verbatim.
- **Audio and microphone in the first build.** **No git yet.**

**Implemented:**

- **The port.** Upstream's tree copied as it stands at `8638341` (1.7.1) and compiled for the desktop:
  **74 of 97 Kotlin files byte-identical**, 15 changed, 8 dropped — every one accounted for in `FORK.md`.
  Platform shims (`android.util.Log`, `Context`, `BitmapFactory`, `Color`, `Xml`, `LocalContext`, `R`,
  DataStore's delegate) let upstream files compile unchanged instead of being re-ported.
- **Linux transports under upstream's class names**, so `TransportManager` is unchanged: USB serial on
  `/dev/ttyACM*`/`ttyUSB*` (115200 8N1, DTR/RTS raised, one frame assembler per device, 1.5 s re-sweep);
  Bluetooth SPP through BlueZ — bonded devices whose name carries `D.A.S.H`, SDP for the channel (falling
  back to the ESP32's channel 1), RFCOMM sockets through libc. WiFi TCP is upstream's file, unchanged.
- **The Android Auto head unit** (`aa/`): the accessory switch over the system libusb; AA framing with
  per-channel reassembly; TLS 1.2 inside `SSL_HANDSHAKE` with the head-unit certificate open head units
  use (replaceable by dropping one into the data folder); service discovery, channel opens, video,
  three audio streams, microphone, input and sensors; pings, focus and shutdown; H.264 decoded by
  `ffmpeg`; audio and microphone through PipeWire; touch from the window and, when a touchscreen is
  present, genuine multi-touch read from evdev.
- **The viewport**, in the rectangle the settings blind rolls into (the one upstream 1.7.1 measured).
  Android Auto is told **margins that make its interface exactly the viewport's shape**, which are cropped
  away — interface.md's *"never letterboxes"*, delivered. The panel and the settings blind draw over it
  and touches on them never reach the phone. Idle, it shows the weather scene and one quiet status line.
- **The bridge from the sourceless core to Android Auto** (`AaBridge`): sensors offered only when a module
  actually reports them; steering-wheel controls become phone keys; volume and mute drive DASH-AA's own
  mixer. The event bus's replay is guarded, so a press from before the phone connected is never sent.
- **Layout › Android Auto**: status, on/off, reconnect, video, frame rate, density, night source, driver
  side, sound, volume, lowering music under directions, microphone.
- **Desktop glue**: full screen by default (F11, Ctrl+Q), the GNOME display scale applied as density
  (`DASH_SCALE` overrides), Transport Manager's links open GNOME's Wi-Fi and Bluetooth panels, About
  reports the Linux capabilities, the Licence tab names what this build bundles.
- **Packaging**: a udev rule (phone access for the person at the seat; ModemManager kept off module
  chips), `install.sh`, a menu entry and optional autostart; a self-contained app with its own Java.
- **Bench tools**: `tools/fake_module.py` (the Climate or Gauge module over WiFi, its real artwork, the
  library's exact wire behaviour), `tools/upstream-sync.sh`, and tests including a **fake phone**.

**Achieved** *(on the G14, 2026-10-05)*:
- **A full Android Auto session against the fake phone passes** — the fake checks it was handed the
  head-unit certificate, as a phone does — through to real H.264 decoded (first frame ~180 ms after the
  stream starts), a touch landing in the centre of the content area, and the phone ending the session.
- **The Climate module installs over WiFi and draws its real `v_medium` panel**; a tap on fan `+` steps
  along the module's declared list, sends `ACTION|0000DA58AC04|fan|3`, and the module's `REPORT` brings
  the panel to 3.
- **A projection crops to a 1680 × 996 viewport with no margin visible and no stretch.**
- **The real app starts on the GNOME/Wayland desktop**, detects the 1.67× scale and finds live weather.

**Found along the way:**
- **Upstream's SVG parser composes compound transforms in reverse** — any `translate(…) scale(…)`, or a
  scaled element inside a translated group, lands off the panel. Every Climate layout after `h_large`
  places its glyphs that way, so on a tablet they would draw with no arrows, AUTO, recirculation, A/C,
  screen or seat icons — upstream's own record says they were never seen on the tablet. Proved by running
  Compose's `Matrix.timesAssign` from both upstream's Compose and this one. **Fixed here in the shared
  file, the way upstream should take it, with a test** — `FORK.md`, *Upstream fix candidates* #1. The
  Gauge and `climate_h_large`, the hardware-verified ones, use only single-step transforms and are
  unaffected.
- **`-fflags nobuffer` makes ffmpeg 9.0 discard every frame of raw H.264**, and without a probe limit it
  shows nothing until the stream *ends*. Measured, then settled on four flags that give the first frame
  in 0.1 s with ~66 ms of decoder lookahead.
- **The first connection must not be timed out.** A phone meeting a new car waits on its own prompts; only
  the phone's first reply is timed now (10 s), never the time the owner takes on the phone.
- **XWayland hands Java raw pixels under fractional scaling**, so DASH's `dp` would be a third of their
  size on the G14's panel. DASH-AA reads GNOME's scale and applies it as density.
- **Foxconn builds phones and laptop radios** — this G14's Bluetooth is `0489:e11e` — so it is not on the
  phone-maker list; a Foxconn-built phone is still found by its MTP or adb interface.
- **The packaged launcher prints "pure virtual method called" at start-up** and the app runs normally
  regardless. Not investigated further.

**Outstanding:**
- **No real phone yet.** The first hardware test is the Pixel 8 Pro on the G14. If its Android Auto
  rejects the head unit, the session log names the step.
- **No real module on this laptop yet** — USB serial and Bluetooth on Linux are new code; WiFi is
  upstream's own.
- **No touchscreen yet** — the evdev path assumes the touchscreen covers the monitor DASH-AA is on.
- ~~**Touch coordinates are mapped into the content area of the full frame.**~~ *Settled at 1.0.3 — the
  phone wants them in the interface's own coordinates; confirmed on the Pixel.*
- **Portrait and 1440p video modes** come from later protocol revisions than aasdk's and are unproven.
- **Upstream's splash-on-wake** (Android's screen-on) has no laptop equivalent yet; the splash plays at
  launch.
- **Manual location needs a keyboard** — XWayland apps do not raise GNOME's on-screen keyboard.
- **The udev rule is not installed on the G14** — it needs Roger's `sudo`, deliberately.

**Notes:**
- **Upstream gaps mirrored, not invented:** `module_panel_left/right` are specified but unused upstream.
- **Weather, landing and idle viewport** use upstream's IP cascade (manual pin → IP → clock).
