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

**Next: 1.1.x — the settings panel, reorganised for the Linux base.** After that, a lightweight Linux
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

Settings › **Audio** has the sound: where Android Auto's sound plays (**Output**), whose microphone it uses
(**Microphone**), and the volume and lowering music under directions (**Mixing**).

**Phone calls go over Bluetooth, not USB.** That's how Android Auto works in every car: the phone uses the
head unit as a hands-free kit and only projects the call screen. So, once, **pair the phone with the
computer** in your desktop's Bluetooth settings. DASH-AA then gives Android Auto the computer's Bluetooth
address. During a call, it joins the phone's voice to the speakers and the microphone to the phone.
Android Auto › Connection › *Calls* says whether it's ready. **Settings › Audio › Calls** has:
- the call volume (up to 300%, independent of music)
- echo cancelling, which runs only for the length of a call. The microphone is never open otherwise.

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
