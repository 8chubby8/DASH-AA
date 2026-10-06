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

#### 1.1.x — Settings, reorganised *(next — Roger, 2026-10-06)*
The settings panel reorganised around DASH-AA's Linux base. This is the first piece of work under the new
direction (CLAUDE.md, *The Direction*): DASH-AA leads, and the result has to be usable by DASH native. The
minor number goes up stage by stage until the work is done.

**Scope:** still to be set out by Roger.

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
- Android Auto's media and navigation status channels — "now playing" and turn-by-turn for upstream's
  planned elements (1.9.x / v2), offered through the same SDK as any element would read them.
- Hardware video decode (VA-API) if the CPU cost ever matters.
- Feed `module_panel_left/right` once upstream defines their behaviour.
