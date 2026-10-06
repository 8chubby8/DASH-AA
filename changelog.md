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
