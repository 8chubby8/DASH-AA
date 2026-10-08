# DASH-AA

> *"Fine. I'll do it myself."* — DASH on Linux, with Android Auto in the viewport.

**DASH — the Dynamic Automotive System Hub — is an open, modular head unit for your car. DASH-AA is DASH on
Linux, with your phone's Android Auto filling the viewport.** DASH draws the system bar, the module panel
and the settings. Android Auto, projected from the phone over USB, fills the rectangle DASH gives it. The
modules you build — climate controls, gauges, steering-wheel buttons — plug in over USB, WiFi or Bluetooth.

DASH gets out of the way. Your car, your screen, your modules, your layout. There are no locked features,
no hidden menus, and DASH never needs root.

## DASH-AA and DASH

DASH began on Android ([8chubby8/DASH](https://github.com/8chubby8/DASH)), as a launcher that runs
Android apps in the viewport. **DASH-AA is now where DASH is developed.** The Android edition is shelved,
not abandoned, at version 1.7.1. Both editions are meant to finish looking and feeling the same. The
intended differences are only these:

| | DASH (Android) | DASH-AA (Linux) |
|---|---|---|
| Runs on | an Android tablet or board | a Linux machine |
| The viewport shows | Android apps | Android Auto from your phone |

Everything else is shared: the bar, the panel, the settings, and **the module protocol and panel format**.
A module built for either edition works on the other with no change to its firmware. The Element and
Overlay SDKs will be compatible across both. Most of the source files are byte-for-byte the same.
`FORK.md` lists which ones differ and why, and the changelog records how the Android edition can follow
what is built here.

## Where it is

**Version 1.0.9** — working on a Ryzen laptop (GNOME on Wayland) with a Pixel 8 Pro and real modules:
- **Android Auto in the viewport** over USB. It fills the viewport's exact shape and restarts cleanly when
  the layout changes. Taps land where aimed, two-finger trackpad scrolling zooms, and music plays.
- **Phone calls through the head unit** over Bluetooth, with **echo cancelling** and a **call volume**
  independent of music (Settings › Audio › Calls).
- **Modules exactly as on the Android edition.** The Climate module over Bluetooth and a second module,
  installed and driving their panels.
- A sound-system fault never freezes Android Auto, and **Restart sound** appears when one happens.

**Built, not yet tested on real hardware:** multi-touch from a touchscreen, and the steering-wheel module
driving Android Auto.

**Settings, reorganised for the Linux base (1.1.x, in progress):** Audio is the machine's sound settings and
the car's sound system (1.1.2–1.1.4); **Display** sets up the screens through GNOME, KDE's KWin or a
wlroots display program — screens, rotation (Android Auto goes tall with the screen), brightness by day and
night, colour and night light, blanking, touchscreens (1.1.5). Changes to the screens are temporary on a
desktop: closing DASH puts them back.

**Next: the rest of 1.1.x** — Connections, Power, System, Terminal. After that, a lightweight Linux
distribution with DASH-AA built in, so a touchscreen PC boots straight into DASH. See `roadmap.md`.

---

## Setting up (once)

You need `ffmpeg`, `libusb`, BlueZ (`bluez-utils` on Arch, for Bluetooth modules and calls) and PipeWire
with WirePlumber. Java is bundled into the app, and Gradle downloads what the build needs.

```bash
git clone https://github.com/8chubby8/DASH-AA.git
cd DASH-AA
packaging/install.sh               # builds the app, installs the udev rule (asks for sudo once), adds a menu entry
packaging/install.sh --autostart   # …and starts DASH-AA when you log in, like a head unit
```

The udev rule is the only privileged step, and it's asked for once. It lets *the person at the seat* open
their phone over USB for Android Auto, and it keeps ModemManager off module boards. DASH-AA itself never
runs as root.

For USB serial modules your user must be in the `uucp` (Arch) or `dialout` (Debian, Ubuntu) group. For
multi-touch, it must be in the `input` group. `install.sh` warns about the serial group, and About DASH
reports both.

## Running

```bash
app/build/compose/binaries/main/app/dash-aa/bin/dash-aa     # or "DASH-AA" from the app menu
```

| | |
|---|---|
| **F11** | full screen ↔ window |
| **Ctrl+Q** | quit |
| `DASH_WINDOWED=1` | start in a window (for the bench) |
| `DASH_SCALE=1.5` | override the display scale (the in-car screen is yours to size) |
| `DASH_DISPLAY=window` | ignore the desktop's display program: Rotation turns DASH's own picture instead (for trying it) |
| `DASH_HOME=…` | keep data somewhere other than `~/.local/share/dash-aa` |
| `DASH_VERBOSE=1` | debug logging |

**Run one DASH-AA at a time.** Two will fight over the phone, and the log shows `SSLException`. Started
from the app menu, DASH-AA logs to the system journal (`journalctl --user | grep dash-aa`). Started from a
terminal, it logs there (add `2>&1 | tee ~/dash-aa.log` to keep it).

## Android Auto

1. Plug the phone in by USB. DASH-AA asks it to switch into Android Auto's accessory mode. The phone
   drops off, comes back, and Android Auto starts on it.
2. **The first time**, the phone shows its own prompts ("connect to this car?", permissions). Answer them
   on the phone while DASH-AA waits.
3. The viewport fills with Android Auto.

Settings › **Android Auto** has the rest:
- **Connection:** whether the phone is projecting, Android Auto on or off, Reconnect, and **Restart
  sound**, which appears only when the sound system has stopped
- **Picture:** video resolution, frame rate, and **density** (how big the phone draws its interface)
- **Night & Driver Side:** night mode, and which side the driver sits

Settings › **Audio** is the machine's sound settings and the car's sound menu in one, so you don't need
the desktop's:
- **Equaliser:** a ten-band equaliser, **Flat**, **Anti-distortion** (on by default: turns everything down
  by your biggest boost so the sound can't crackle), balance, fade (front doors against rear doors, when
  both have speakers), the crossover (where the subwoofer stops, and a low cut for the other speakers), and
  **Loudness** (below). These work when Car sound is on.
- **Speakers:** **Car sound** at the top (below), then the volume, mute, a **start-up volume limit** so the
  car isn't blasted when DASH starts, and **Volume buttons**: whether the steering wheel's volume turns
  the whole machine or only Android Auto. With Car sound off, which device everything plays through.
  With a module reporting the car's speed, **Speed volume** (Off, 1–10) turns the sound up as the car goes
  faster: higher for a noisy car, lower for a quiet one. It never goes past full volume.
  Below, whether Android Auto's sound plays on the phone or through DASH-AA.
- **Microphone:** which microphone, its volume, and **Test microphone**, a level meter that shows the
  microphone hears you. Nothing is recorded. Below, whether Android Auto uses the phone's microphone or DASH-AA's.
- **Volumes:** Android Auto's volume and separate levels for its music, directions and system sounds,
  lowering music under directions, and a level for anything else that is playing.
- **Calls:** below.
- **Saved:** five slots, like a radio's memory buttons. **Hold** a slot to save the car sound there,
  **press** it to load. **Undo last load** puts back what a load replaced. The slots are files in
  `~/.local/share/dash-aa/files/sound/`, so you can back them up or share them.

**Loudness** keeps music full when it's quiet. As sound gets quieter your ears lose the low notes first, and
a little of the very top; loudness puts back exactly what they lose, from the international standard for
how people hear (ISO 226). Turn it on (1–4; 4 is the full correction), play some music, turn the volume to
where it sounds full and right, and press **Set** under *Comfortable volume*. It adds nothing at that
volume or above, and more the further you turn down. It follows DASH's main volume, so set **Volume
buttons** to *Machine* if you want the steering wheel's buttons to move it.

**Time alignment** (Speakers, with Car sound on): sit in the driver's seat, measure from your head to the
middle of each speaker, and enter each distance. DASH holds back the nearer speakers so the sound from all
of them reaches you at once.

**Car sound** sends everything through DASH, like a car's sound processor between head unit and
amplifiers. You lay out the car's speakers: **Front**, **Rear**, **Surround** (the parcel shelf),
**Centre** and **Subwoofer**. Each plays through a device you choose, or none, with its own level. One card
with several outputs can serve several positions, or each can have its own card. The shelf's **Mode** is
*Full stereo*, *Surround* (the difference between left and right: voices fade, the room remains) or *Wide
surround* (as speakers wired across the two positive terminals), with a **Delay**. With Car sound on,
DASH's own output is the default and its volume is the volume; the speakers' devices sit at full. Only
the controls your speakers make sense of are shown.

It runs as two of your own services, `dash-aa-sound` and `dash-aa-speakers` (`systemctl --user status
dash-aa-sound`), with their settings in `~/.config/pipewire/dash-aa-*.conf`. They start with PipeWire, so
the sound keeps its settings even when DASH isn't running, so switch Car sound off before using the computer
for anything else. Turning it off stops and disables them. To
remove them by hand, run `systemctl --user disable --now dash-aa-speakers dash-aa-sound`, then delete
those files and `~/.config/systemd/user/dash-aa-*.service`.

The choices are the machine's: they need PipeWire and WirePlumber, and if those are missing the tabs say so.
Car sound also needs PipeWire 1.0 or newer and systemd's user manager; without them it doesn't appear.

**Phone calls go over Bluetooth, not USB.** That's how Android Auto works in every car: the phone uses the
head unit as a hands-free kit and only projects the call screen. So, once, **pair the phone with the
computer** in your desktop's Bluetooth settings. DASH-AA then gives Android Auto the computer's Bluetooth
address. During a call, it joins the phone's voice to the speakers and the microphone to the phone.
Android Auto › Connection › *Calls* says whether it's ready. **Settings › Audio › Calls** has:
- the call volume (up to 300%, independent of music)
- echo cancelling, which runs only for the length of a call. The microphone is never open otherwise.
- with Car sound on, **Calls play through** all speakers, the front only or the driver's side, and
  **Subwoofer in calls** — only the choices your speakers make sense of.

**Then, on the phone, give the computer calls only:** Settings › Connected devices › the computer's gear ›
turn **Media audio off** and leave **Phone calls on**. Android Auto's music already comes over USB. If the
phone also sends it over Bluetooth, WirePlumber can crash and all sound stops.

**When the layout changes shape** (bar height or position, panel size, edge or visibility), Android Auto
restarts so it fills the new shape exactly, which takes a few seconds. An *expanded* panel never restarts
it: the panel draws over the viewport, as it does over an app on the Android edition.

**The car drives Android Auto, through DASH.**
- A SYSTEM module that reports `headlights_on` gives Android Auto night mode.
- `vehicle_speed`, `gear_position`, `handbrake_on` and `engine_rpm` become its car sensors. Each is offered
  only when a module actually reports it.
- The steering-wheel controls drive Android Auto with no change to the module: `media_next`, `media_prev`,
  `media_play_pause`, `voice_activate`, `button_home_pressed`, the volume pair and `media_muted`.

## Modules

`docs/module-sdk.md` is the contract, and `docs/module-layout.md` defines the panel. On Linux:

- **USB:** plug in. Native-USB boards appear as `/dev/ttyACM*` and bridge-chip boards (the classic ESP32
  DevKitC) as `/dev/ttyUSB*`. DASH-AA finds both.
- **WiFi:** DASH-AA listens on port **3274**. Set the module's `DASH_HOST` to the computer's address,
  which Settings › Modules › Transport Manager shows. In the car, a Wi-Fi hotspot on the computer makes it
  the module network.
- **Bluetooth:** pair the module once in your desktop's Bluetooth settings (the Transport Manager's link
  opens them). Its name must contain `D.A.S.H`.

## On the bench, with no hardware

```bash
python3 tools/fake_module.py                      # the Climate module over WiFi, with its real artwork
python3 tools/fake_module.py --profile gauge      # the Tank Gauge
./gradlew test                                     # fake phone, SVG transforms, bridge, geometry
./gradlew test --tests '*UiScreenshots*' -Dscreenshots=1 '-Dclicks=1871,1038'   # renders to app/build/screenshots/
```

## When something doesn't work

- **"Phone found, but DASH-AA may not open it"**: the udev rule isn't installed. Run
  `packaging/install.sh`, then unplug and replug the phone.
- **Nothing happens when the phone is plugged in**: run from a terminal and read the log. `DashAaUsb`
  lines say whether the phone agreed to switch. `DashAaSession` lines show how far the conversation got
  (`phone speaks Android Auto`, `TLS complete`, `service discovery`, `phone opened video`).
- **A USB module is refused**: About DASH's report says whether your user may open serial ports.
- **Music plays for about five seconds, then pauses**: the phone is also connected by Bluetooth to another
  audio device (headphones, a car, a speaker) and keeps handing its sound back to it. Disconnect that
  device on the phone.
- **Calls are quiet even at a high Call volume**: the phone's own *in-call* volume (its volume buttons
  during a call) is applied first. Turn it up on the phone, then set Audio › Calls › Call volume to taste.
- **Everything is tiny or huge**: set `DASH_SCALE`.

---

## The documents

| | |
|---|---|
| `CLAUDE.md` | What DASH is, what it stands for, and the rules of the project. |
| `docs/` | The reference documents. `transport.md` (the protocol), `interface.md` (how DASH looks and behaves), `module-sdk.md` and `module-layout.md` (what a module must do and how it describes its panel), `arduino.md` (the SDK's working record), `system_commands.md`, `hardware.md`. |
| `roadmap.md` / `changelog.md` | Where DASH-AA is going, and what actually happened. |
| `FORK.md` | How this code relates to the Android edition. |
| `docs/native/` | The Android edition's brief, roadmap and changelog, as they stood when it was shelved. |

---

Copyright © 2026 Roger Davies. GNU General Public License v3.0 — see `LICENSE`. The Android Auto message
definitions follow aasdk (f1xpl, GPL-3.0).
