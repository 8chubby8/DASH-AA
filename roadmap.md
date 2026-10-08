# DASH-AA — Roadmap

DASH-AA's own plan, in upstream's versioning convention (`roadmap.md` upstream: first number the era,
second the feature, third the stage; `.1` is first implementation, later numbers respond to testing).
**Since 2026-10-06 DASH-AA leads** (Roger: DASH native is shelved). This roadmap now plans DASH as a
whole: the bar, the panel, the settings and the SDKs as well as what is DASH-AA's own. All of it is
written to be usable by DASH native when that project resumes (CLAUDE.md, *The Direction*). Native itself
is not touched. Each stage here, and each changelog entry, records how native follows.

**The standing rule (since 2026-10-06):** native is left alone, so there is no syncing with upstream. Every
entry carries a *For native* line instead.

---

## Version 1.x.x — The laptop

**Goal:** DASH-AA on the G14, in the car, daily: modules working exactly as on the tablet, Android Auto
in the viewport, driven by touch and by the steering wheel.

#### 1.0.x — The fork **(complete — 1.0.1 to 1.0.9, 2026-10-05)**
The port, the Linux transports, the Android Auto head unit, the viewport, the bridge, the Android Auto
tab, packaging and bench tools (1.0.1); then, on real hardware the same day, everything first contact was
planned to cover: the Pixel 8 Pro projecting on the G14, taps and trackpad zoom (1.0.2–1.0.3), audio
(1.0.3), phone calls over Bluetooth (1.0.4), sound faults that cannot freeze the phone (1.0.5–1.0.6), echo
cancelling (1.0.7), call volume (1.0.8–1.0.9), and real modules on the laptop. See the changelog.

#### 1.1.x — Settings, reorganised *(in progress — 1.1.1 and 1.1.2 complete, 2026-10-07; 1.1.3 to 1.1.6 complete, 2026-10-08; next 1.1.7)*
The settings panel reorganised around DASH-AA's Linux base. This is the first piece of work under the new
direction (CLAUDE.md, *The Direction*): DASH-AA leads, and the result has to be usable by DASH native. The
minor number goes up stage by stage until the work is done.

**Why it changes (2026-10-07):** native's tree was built around Android. Its System category was mostly deep
links into Android's own settings, and its Audio placeholders were per-app Android audio. On the laptop,
DASH-AA can hand off to GNOME's settings. On the 1.2.x touchscreen PC there is no desktop to hand off to,
so DASH's settings have to *be* the machine's settings. The categories change to reflect what Linux does.

**The tree (agreed with Roger, 2026-10-07):**

```
Appearance     Size & Scale, Transitions, Splash, Colours, Fonts, Presets, Ambient
Layout         System Bar, Module Panel, Elements, Overlays
Android Auto   Connection, Picture, Night & Driver side
Audio          Equaliser, Speakers, Microphone, Volumes, Calls, Saved   (renamed 1.1.3; Saved 1.1.4)
Connections    Wi-Fi, Ethernet, Bluetooth   (1.1.6; Ethernet its own tab, Roger 2026-10-08)
Display        Screens, Rotation, Brightness, Colour, Screen Blanking, Touchscreen   (1.1.5)
Power          Ignition behaviour, Sleep / Shut down / Restart, Leave DASH
Modules        Module Manager, Transport Manager, Serial Monitor, Signal Monitor, Activity Log
Vehicle        (v3)
Notifications  (v2)
System         Location, Date & Time, This Machine, Updates, About, Licence
Developer      Terminal, Logs, Switch to Desktop
```

Roger's rulings in getting here:
- **Lots of top-level categories.** Connections, Display and Power stay separate rather than being folded
  into one "Machine" category.
- **Appearance and Layout stay separate for now.** They belong together, but nesting Layout inside
  Appearance would make a fourth level of navigation, and group headings in the category rail were tried
  and rejected. Roger will find a better way to link them.
- **Rotation is back, in Display.** This reverses the 2026-10-05 decision to drop it. On Linux, rotation is
  part of the display settings.
- **Calls stays in Audio** for now.
- **Developer is its own category, last in the tree**, holding Terminal, DASH's own Logs and Switch to
  Desktop: the machine and DASH itself, while the module tools stay in Modules. It was first placed
  inside System, as a fourth level; on screen it belonged on the main tree (Roger, 2026-10-07).
- **Modules › Activity Log stays in Modules.** It is the record of what modules did and why; DASH's own
  logs are Developer › Logs.
- **The Bible is not yet updated.** The tree in `docs/interface.md` (2026-07-20 addendum) is still native's.
  It is changed when Roger says so.

**No desktop, as standard (Roger, 2026-10-07):** "the idea is to have no desktop as standard. the
'desktop' will be dash. so we need to make sure everything we are going to need is going to be
available." So every tab is built to work **with no desktop present**. It talks to the machine's own
services directly, and "open the desktop's settings" is at most an extra when a desktop happens to be
there (the laptop today), never the way a thing gets done.

**The rules every new tab follows:** no root (each uses a service the person at the seat may already use:
PipeWire, NetworkManager, BlueZ, logind, the display program). Capability detection first: a tab whose
service is missing says so in plain words, or does not appear, and nothing else is affected. **Each stage
is tested in a session with no desktop** — DASH alone on the screen — as well as on GNOME.

**What the desktop does today that DASH must take over.** The checklist this work answers to; each item
names the stage that covers it.

| The desktop does this now | With no desktop | Stage |
|---|---|---|
| Joins Wi-Fi networks | DASH talks to NetworkManager itself | **done** — 1.1.6 |
| Pairs Bluetooth devices: answers "does this code match?" | DASH is the pairing helper, or the phone (calls) and Bluetooth modules cannot pair | **done** — 1.1.6 (DASH is BlueZ's agent) |
| Asks for your password when a setting needs more than a normal user may do | Nothing can ask, so the action would quietly fail. Each one DASH needs is granted to the seat user once, at install, like the USB phone rule | each stage, as found |
| Puts the picture on the screen, and turns it | A small display program runs DASH full screen, and Rotation goes through it | 1.1.5 (and 1.2.x) |
| Blanks the screen and sleeps | DASH does it | 1.1.5, 1.1.7 |
| Has a keyboard on screen | DASH needs one, for Wi-Fi passwords and the Terminal | **done** — 1.1.6 (QWERTY, only while stopped); the Terminal's keys at 1.1.9 |
| Picks files (the splash image) | DASH's own file picker | 1.1.8 |
| Opens web links | The QR codes in About already cover a machine with no browser | done |
| Starts the sound system when you log in | The machine logs the user in automatically, so PipeWire runs | 1.2.x |
| Somewhere to go when you leave DASH | "Leave DASH" must lead somewhere. Where is left open (Roger, 2026-10-07: "i feel the answer will become obvious once more of the shell is finished") | 1.1.7 |
| A way back to a full desktop, when one is installed | Developer › **Switch to Desktop** (Roger, 2026-10-07): DASH closes, the desktop starts, logging out of it brings DASH back — no reboot, no root | 1.2.x |
| Shuts down and restarts | DASH, through logind | 1.1.7 |
| Updates the system | DASH updates itself without root; system updates need a decision | 1.1.8 |

**Stages:**

- **1.1.1 — The new tree.** **(complete — 2026-10-07)** Every control moves to its final home, so later stages only add. Android Auto
  leaves Layout for its own category, split into Connection, Picture, and Night & Driver side. Its sound
  controls move into Audio now: where the sound plays (Output), whose microphone (Microphone), volume and
  lowering music under directions (Mixing). The machine-capability report moves out of About DASH into
  System › This Machine. Developer returns as a category. Every tab not yet
  built is an honest "arrives with 1.1.x" placeholder (native's work-in-progress convention). Also, at
  Roger's asking: with no phone projecting, the viewport shows a big analogue clock instead of the
  weather scene, which stays as the settings panel's landing. Settings for the clock's look come later.
- **Audio is the machine's sound settings and the car's sound menu in one** (Roger, 2026-10-07: "a
  machine status and settings tab mixed with dash specific car type controls"). With no desktop, there
  is no other sound panel. Its tabs: **Output** (device, master volume, mute, start-up volume limit),
  **Input** (microphone, input level with a live meter), **Mixer** (a level for each source: Android
  Auto's music, its directions, its system sounds, any other app — calls keep their level in Calls; lowering music under
  directions moves in here, and the separate Mixing tab goes), **Sound** (equaliser, balance, fade,
  loudness, subwoofer, speed-dependent volume) and **Calls** (as now). **Renamed at 1.1.3** (Roger,
  2026-10-08): **Equaliser** (was Sound, moved to the top as the one used most) · **Speakers** (was Output)
  · **Microphone** (was Input) · **Volumes** (was Mixer) · **Calls**. It is built in three stages
  (Roger, 2026-10-07; split into three, 2026-10-08):
- **1.1.2 — Audio: the machine.** **(complete — 2026-10-07)** Output, Input and Mixer, all through PipeWire as the seat user
  (`wpctl`, `pw-dump`), never on the Android Auto session thread. The chosen output and microphone
  become the machine's defaults, so everything follows them. The lists update live as devices are
  plugged in and pulled out. Where PipeWire is missing, the tabs say so and nothing else is affected.
  The Mixer names streams by the identity each already has (the 1.0.x rule). **Volume buttons** (Roger,
  2026-10-07): the user chooses what the steering wheel's volume turns — the machine or Android Auto, and a
  sound module once one exists.
- **1.1.3 — Audio: the car.** **(complete — 2026-10-08)** DASH's own sound chain: one "DASH" output that
  all sound plays through, like a car's DSP between head unit and amplifiers. **PipeWire owns it and DASH
  only adjusts it**: two of the seat user's services (`dash-aa-sound`, the way in, which never restarts;
  `dash-aa-speakers`, the layout, restarted only when the layout's shape changes), with their configs in
  `~/.config/pipewire/`. They start with PipeWire, so the sound keeps its settings when DASH stops.
  Everything else changes live. **Built ready for a sound module**: the tabs edit DASH's sound settings
  (`CarSound`), which a *sound processor* carries out, and the processor says which controls it offers, so
  no control is shown that does nothing.
  - **The speaker layout** (Speakers tab, Roger 2026-10-08, modelled on his XF for his X-Type): **Front**
    (front doors), **Rear** (rear doors), **Surround** (parcel shelf), **Centre** and **Subwoofer**, each
    a device of the user's choosing or None, on the outputs the user picks, with its own level.
  - **Surround's mode** (pulled forward from 1.1.4 at Roger's asking): **Full stereo**, **Surround** (left
    minus right, the same to both shelf speakers) or **Wide surround** (true Hafler: left minus right on
    the left, right minus left on the right), with a **Delay** (Off, 5–30 ms). Not called Dolby or Pro
    Logic, which are Dolby's marks.
  - **The Equaliser tab:** ten-band equaliser, Flat, **Anti-distortion** (on by default: everything
    turned down by the biggest boost), balance, **fade** (front doors against rear doors only, offered only
    when both have a device — the shelf is not part of it), and the crossover (subwoofer cutoff, low cut).
  - **Calls** play through *All speakers*, *Front only* or *Driver's side*, and the subwoofer or not —
    only the choices the layout makes sense of.
  - **Protection:** if PipeWire restarts, the speakers are muted until DASH has its settings back; and
    **`sound_ready`**, DASH's amplifier remote wire, tells modules when to stay quiet (added to
    `system_commands.md` with Roger's say, alongside seat belt warnings for every seat, `screen_on` and
    `engine_running`).
- **1.1.4 — Audio: the rest.** **(complete — 2026-10-08)** All built at once, at Roger's asking.
  - **Loudness** (Roger: "it's got to be good… Hi-Fi level"): correction that follows the volume, from
    ISO 226:2003's equal-loudness contours, worked from a **comfortable volume** the user sets; Off and four
    levels (a quarter to all of the correction). Fitted filters follow the standard to within about half a
    decibel. Measuring the listening level with microphones, or from the music, is left to a sound module
    (Roger) — a processor may offer loudness without asking for a comfortable volume.
  - **Speed volume**, with an effect level (Off, 1–10) for a quiet car or a noisy one (Roger). Appears only
    when a module reports `vehicle_speed` (capability-detected, never faked).
  - **Time alignment**, pulled forward from later: a distance for each speaker, and the nearer ones wait.
  - **Audio › Saved** (Roger, while testing): the car sound in five numbered slots, like a radio's memory
    buttons — hold to save, press to load — with undo of the last load; and changing a speaker's device
    keeps its tuning.
- **1.1.5 — Display.** **(complete — 2026-10-08)** Everything a desktop's display settings have, since with no
  desktop DASH's are the only ones (Roger, 2026-10-08: "what we build has got to replace what is in KDE"),
  all in one stage at his asking. Six tabs: **Screens · Rotation · Brightness · Colour · Screen Blanking ·
  Touchscreen**.
  - **Through the display program, whichever it is** (Roger: the final distribution will probably run
    KWin, the laptop runs GNOME, so "it has to work with both"): GNOME's Mutter, KDE's KWin
    (`kscreen-doctor`) or a wlroots one (labwc, sway, cage — `wlr-randr`), detected at start; what the one
    running cannot do does not appear. With none, **DASH turns its own picture** inside its window.
  - **Screens:** each screen on or off, the main one, mirrored or its own, which side of the main one,
    resolution, refresh rate, adaptive sync, fit-to-edges (overscan) and scale. **A screen plugged in for
    the first time is asked about** (Extend · Mirror · Off) and remembered by its own identity, so it is
    set up the same way whenever it is plugged in.
  - **Rotation:** native's tab and tiles; Auto, on a machine with no tilt sensor, is the screen as DASH
    found it. A touchscreen read directly turns with the screen.
  - **Brightness by day and by night** — night while a module reports `headlights_on`, like a car's dimmer.
    **Colour:** HDR and colour range where a screen has them, and night light (off, on, with the
    headlights) with its warmth. **Blanking:** after 1–30 minutes untouched, dimming first; any touch wakes
    it. **Touchscreen:** which screen each drives, and a place to try it.
  - **Every risky change asks to be kept** (15 s, then it goes back by itself). On the laptop nothing is
    permanent: when DASH closes, the screens go back the way the desktop had them.
- **Several screens, each with a job** — **screen roles**, planned for a later version (Roger, 2026-10-08:
  a friend's Infiniti has one screen for the infotainment and one for the heating; "even a third screen
  for the dash cluster and maybe even a fourth and fifth screen for media entertainment in the rear").
  1.1.5 does the machine side (which screens, where, mirrored); roles decide what each shows. See *Later*.
- **1.1.6 — Connections.** **(complete — 2026-10-08)** Wi-Fi and Ethernet through NetworkManager,
  Bluetooth through BlueZ, all as the seat user with no desktop needed; the desktop's settings stay only
  as a fallback where those are missing. Decided with Roger:
  - **Every car its own way:** each Wi-Fi adapter is given a **job** — Join networks, **DASH network**
    (the car's own Wi-Fi, for modules and later wireless Android Auto) or Off. One adapter does one job;
    two adapters do two. Nothing assumes internet, Wi-Fi modules or wireless Android Auto.
  - **Ethernet is its own tab** — Roger's car takes its internet from a SIM router on a cable, leaving the
    laptop's Wi-Fi free to host 5 GHz for wireless Android Auto (1.4.x, direct, not through the router).
    Every connection shows the address modules connect to, and it can be fixed with one tap.
  - **DASH is the Bluetooth pairing agent** while it runs, with its own pairing prompt; phones can give
    their internet over Bluetooth (tethering) as the backup with no router.
  - **Phones' Bluetooth music is refused** by DASH (on by default) — the 1.0.6 crash, guarded for good.
  - **DASH's on-screen keyboard**, QWERTY, only while the car is stopped (on by default; always allowed
    when no module reports speed or handbrake).
- **1.1.7 — Power.** Sleep, shut down, restart and leave DASH, through logind. Ignition behaviour waits for
  a module that reports ignition.
- **1.1.8 — System.** Date & Time, Updates, and Developer › Logs.
- **1.1.9 — Terminal.** Developer › Terminal. It runs as the user, never as root. It is built
  from JetBrains' open-source terminal rather than from scratch. On the 1.2.x touchscreen PC it needs the
  on-screen keyboard. It is there because the PC has no desktop, so a terminal inside DASH is the only way
  to look at or fix the machine without leaving DASH. No safety gate, as native decided for its tools.

**For native:** the categories are the same on both editions. Only what sits behind the platform-specific
tabs differs. `SettingsTree.kt` and the shared tabs are taken as they are. Native's Android Auto slot is
its **Apps** category (both are settings for whatever runs in the viewport). Connections, Display and
Power are its existing Android deep links (from 1.1.6, Connections' tabs work over an Android
`NetworkSystem` and `BluetoothSystem` instead, showing what Android reports). Display › Rotation is native's `requestedOrientation` code,
and its Brightness and Blanking rules are shared; Screens, Colour and Touchscreen are Linux's.
Audio's device choice and Mixer have no Android equivalent (Android routes sound itself); the
Sound tab's equaliser maps to Android's `Equalizer` effect, while balance and fade mostly have no
non-root Android path, so native's Sound tab is thinner. Loudness can ride on that `Equalizer` (fitted to
its bands); time alignment has no non-root Android path; Saved is taken as it is. Audio's PipeWire controls, Power's logind actions and System's Updates and Logs need Android-side
equivalents or do not apply.

#### 1.2.x — DASH-AA Linux: the head unit as an operating system *(planned 2026-10-05 as 1.1.x; moved back a place by Roger, 2026-10-06)*
**What it is:** a lightweight Linux distribution with DASH-AA built in, installed on Roger's touchscreen
PC — the board era's idea (upstream's version 2: dedicated hardware, DASH as the whole system) reached
from the Linux side. Power on and DASH-AA is the screen: no desktop, no login prompt, no app to start.

**What it has to be** — upstream's principles, applied to a whole system:
- **DASH-AA is the shell.** Boots straight into it, full screen, as the only thing on the display — the
  way upstream is the Android home screen. A crash restarts it, it never leaves the user at a desktop.
- **Everything DASH-AA proved it needs, and nothing else:** PipeWire + WirePlumber (the versions proven
  on the G14), BlueZ, libusb, ffmpeg, the udev rule, a Java runtime (bundled in the app), Wi-Fi for the
  module network and weather.
- **The touchscreen as the input** — this is where the deferred touchscreen work meets its hardware, for
  as much as the system needs to be usable; the full multi-touch pass stays Roger's to raise.
- **No root at runtime** — DASH-AA runs as an ordinary user, as it does on the laptop.
- **Updatable** without rebuilding the image, so DASH-AA versions keep flowing from this repository.

**To decide before building** (Roger, with the touchscreen PC in hand): the PC's hardware (CPU, graphics,
storage, Wi-Fi/Bluetooth, the touchscreen's connection), the base distribution, how it is installed and
updated, and how it shuts down in a car.

#### 1.3.x — In the car
- **Deferred until Roger raises it:** the full touchscreen pass — multi-touch into Android Auto, the
  module panel by touch, the evdev mapping checked against the real panel.
- **Deferred until Roger raises it:** the steering-wheel module driving Android Auto through the bridge
  (built at 1.0.1, unit-tested, not yet met the real module).
- Power: sleep on `ignition_state` off, wake on on (upstream's power and wake behaviour), the screen-on
  splash.

#### 1.4.x — Wireless Android Auto
The machine as the phone's Wi-Fi hotspot plus the Bluetooth start-up handshake, so the phone projects
without a cable. Deferred by Roger's first ruling (wired first).

#### Later, unordered
- **Screen roles** (Roger, 2026-10-08; to be designed with him, and interface.md updated, before building).
  Each screen given a job rather than just desktop space: **DASH** (bar, panel, viewport, settings),
  **Mirror**, **a module's screen** (one module's panel full screen — a climate module on its own screen,
  as in the Infiniti), **Cluster** (speed, revs, directions), **Terminal** (a monitor plugged in at the
  bench), **Rear** (media for the back seats; one phone gives one Android Auto picture), **Off**. DASH
  becomes one window per screen from the one app. A newly plugged screen is asked which role it takes and
  remembered (1.1.5 already asks Extend · Mirror · Off). Each touchscreen follows its own screen.
- **One Wi-Fi card that joins and hosts at once** ("split card", raised 2026-10-08; left for now by Roger).
  The G14's card can do both on one channel, given a virtual second adapter made once at install (it needs
  admin rights, like the USB phone rule). Limits: the hosted network follows the joined one's channel and
  drops when it moves; airtime is shared; channels 52–144 cannot be hosted on. A second USB adapter does it
  today.
- **Sound sources** (Roger, 2026-10-08): DASH as the car's source selector, like a head unit's — Android
  Auto, **Bluetooth music**, **line in** (analogue, and digital such as optical), and **a USB stick or SD
  card** played by DASH itself. Each one a PipeWire input into DASH's sound chain, chosen from Audio. The
  rule stands (CLAUDE.md): **no Bluetooth music while Android Auto is active**. The first step, small and
  due with the 1.1.x polish: the 1.1.6 music guard follows Android Auto — Bluetooth music allowed while
  no phone is projecting, refused the moment one starts — where today it refuses always.
- **Sound modules** (Roger, 2026-10-07; not yet designed). Roger's own: a 5-way active crossover that
  also does fade, balance, speed-dependent volume and loudness. A sound module tells DASH what it can
  do; each function it offers is taken off DASH's PipeWire chain (never done twice), and what it does not
  offer stays in DASH. Every option is still controlled from DASH's Sound tab, and the settings are
  DASH's, so they carry across with the module present or not. To design: the messages (likely a SYSTEM
  module, system_commands.md vocabulary, transport.md unchanged), how the sound reaches the module, and
  who supplies the speed.
- Android Auto's media and navigation status channels — "now playing" and turn-by-turn for upstream's
  planned elements (1.9.x / v2), offered through the same SDK as any element would read them.
- Hardware video decode (VA-API) if the CPU cost ever matters.
- Feed `module_panel_left/right` once upstream defines their behaviour.
