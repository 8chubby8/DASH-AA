# DASH — Changelog

---

## Purpose of This Document

This document records the development history of DASH version by version. For every version increment it captures what was implemented, what broke as a result, what was done to fix it, and what remains outstanding. It is the honest record of how DASH was actually built — including the mistakes, regressions, and lessons learned.

This document is read alongside roadmap.md. Roadmap.md is the plan. Changelog.md is the reality.

**A version number is not complete until a changelog entry exists for it.**

---

## Entry Format

Each version entry follows this structure:

```
## Version X.x.x

**Status:** In Progress / Complete

**Implemented:**
- What was built in this version

**Regressions:**
- What broke as a result of this version's changes
- Which previously working feature was affected and how

**Fixes:**
- What was done to resolve each regression
- Whether the fix is complete or still being worked on

**Outstanding:**
- Known issues not yet resolved
- Carries forward to next version if unresolved

**Notes:**
- Observations, lessons learned, decisions made during this version
- Anything useful for future development sessions to know
```

---

## Version History

---

## Version 1.7.1

**Status:** Complete — 2026-10-01. **A test build only.** Nothing from it is on `main`: the code
is kept on the branch `viewport-test-1.7.1` (pushed to GitHub), and `main` is back to 1.6.12's code.
*(Roger: "let's write code that we know will work with the Samsung tablet… see what it looks like and
then make decisions. After that we can call it 1.7.1 and in the road map it will be marked as a test
build only.")*

**Scope:** Find out whether DASH can put another app inside the viewport at all — and if so, how, on
which hardware.

**The problem 1.7.x turns on.** The roadmap's 1.7.x list assumed the viewport was a layout job: work
out the rectangle, tell the app to live in it. Working out the rectangle is the easy half — DASH has
always known it, since it is exactly the space the settings blind rolls into. The hard half is that
**an ordinary Android app cannot tell another app where to draw.** A launcher opens Maps and Maps
takes the whole screen. So 1.7.1 set out to test every way round that, by hand first and then in DASH.

**Implemented — the hand tests** (adb, a throwaway probe app, the Tab S9 Ultra on Android 16 and the
Pixel 8 Pro on Android 17):

| Way of doing it | Tab S9 Ultra (Samsung) | Pixel 8 Pro |
|---|---|---|
| **Windowed** — an ordinary app asks Android to open the app at a given rectangle | ✅ placed exactly | ❌ ignored, opens full screen — even with freeform switched on and the phone restarted |
| **Shizuku** — the same, using shell powers; also moves a window that is already open | ✅ | ✅ |
| **Draw on top** — the app runs full screen, DASH draws its bar and panel over it | ✅ but the app is partly covered | ✅ but the app is partly covered, and Android's status bar sits above DASH |
| **System app** | not testable | not testable |
| **Full screen** — none of the above; the app takes the screen | always | always |

That gave **the ladder**, best first: **System app → Shizuku → Windowed → Draw on top → Full screen.**
DASH uses the highest rung that actually works on the device it is running on — the CLAUDE.md
capability-detection pattern, applied to the viewport.

**Implemented — the test build** (on the branch):
- **The viewport rectangle**, measured from the resting layout in screen pixels.
- **The windowed check.** DASH opens a blank window of its own at a known rectangle, reads back where
  Android actually put it, and closes it. The device's own "I support freeform windows" flag cannot be
  trusted — the Pixel says yes and then ignores every request — so the only honest test is to ask and
  look.
- **Windowed and Draw on top, both driven**, plus a stand-in grid of apps drawn in the viewport (the
  real launcher is 1.8.x), and a **Layout › Viewport** page reporting every rung, the rung in use, and
  a test-only method picker.

**Achieved:**
- **On the Tab S9 Ultra, Maps opened in the viewport to the pixel** — 0,119 → 1848,2190, the
  rectangle DASH measured. Verified on the device.
- **On the Pixel, the same build tested Windowed, found it did not work, and fell back to full screen
  on its own.** Verified on the device. One build, two devices, two different answers, nothing told.
- **Draw on top was built and installed but never seen running** — the tablet dropped off wireless adb
  partway through the test.

**Found along the way:**
- **Samsung keeps windowed apps above everything an ordinary app can do.** DASH bringing itself
  forward, a "go home" request, even launching a full-screen app — all leave Maps on top. Only the
  user's own Home key tucks windowed apps away. So **settings opened while an app is in the viewport
  rolls out underneath it.** Shizuku or a system app can solve this; Draw on top sidesteps it.
- **An ordinary app cannot move a window once it is open.** Opening the same app again with a new
  rectangle is ignored. So a rotation or a layout change leaves the app where it was first put. Only
  Shizuku and the system-app rung can keep an app in the box when things change.
- **Samsung will not let a window cover the top 45px** — its status bar's strip — even with the bar
  hidden. Not a problem with the bar at the top; it would be with the bar at the bottom.
- **Samsung puts a thin drag handle on each window**, so the user can drag or resize the app out of
  its box. The Pixel, through Shizuku, shows none.
- **DASH's first measurement of the viewport is wrong** — before its saved layout loads it briefly
  thinks the whole screen is free. Anything that acts on the rectangle must wait for it to settle.

**Decided** *(Roger, 2026-10-01)*:
- **System app is the ideal; everything below it is a fallback**, and DASH falls back on its own.
- **1.7.x is rebuilt rung by rung, from the bottom up** — one version per rung, each made properly
  usable before the next. Full screen needs no version of its own. **1.7.2 is Draw on top.** See
  roadmap.md.
- **The test code stays off `main`.** Each rung is built properly in its own version; the branch is
  there to borrow from, not to merge.

**Outstanding:**
- **A conflict with interface.md, not resolved here.** The Viewport section says *"apps always fill
  the viewport completely"* and *"apps receive correct inset information so interactive content
  respects bar areas even in Passive mode."* Draw on top cannot do either — the app fills the whole
  screen and is never told about DASH's chrome. **Draw on top is, by nature, Passive mode** ("the
  viewport extends underneath floating bars"), and it can honestly offer nothing else. Whether
  interface.md is amended, or Flush and Dominant are offered only on the rungs that can deliver them,
  is a Bible conversation still to have.
- **Each rung version needs a way to open an app.** *(Settled the same day, Roger:)* a **throwaway
  stand-in** for the whole of 1.7.x, not the launcher. Bringing the launcher's core forward into 1.7.x
  was offered and declined — the viewport is finished first, then the launcher is built properly at
  1.8.x, and the stand-in goes when it arrives.
- **The System app rung cannot be checked on any hardware DASH has.** It waits for the Orange Pi.
- The throwaway probe app (`dash.probe`) is still installed on the Tab S9 Ultra and the Pixel.
- DASH is set as the home app on the Tab S9 Ultra (it was the Samsung launcher before testing).

**Notes:**
- `versionName` is derived from this file, so a `main` build now reports 1.7.1 while carrying 1.6.12's
  code. That is accurate — 1.7.1 changed no code on `main`.
- **Testing on a phone mid-call is fine.** Roger took a call during the Pixel tests and saw two Maps
  windows; both were the tests' own. The clean re-run after a restart gave identical answers.

**Bug fix — after 1.7.1, not a version of its own** *(2026-10-05; to be committed with the next feature
version, Roger's call)*: **SVG transforms composed in reverse** (`SvgParser.kt`). Compose's
`a.timesAssign(b)` computes b · a, the opposite of what the parser assumed, so any vector element under
a *compound* transform — `translate(…) scale(…)`, or a scaled element inside a translated group — was
drawn in the wrong place, usually off the panel. The Climate layouts after `h_large` place their glyphs
exactly that way, so on the tablet they would draw their buttons with no icons; the browser previewer
never showed it. Single-step transforms are unaffected, which is why the Tank Gauge and `climate_h_large`
always drew correctly. Found and tested by DASH-AA, the Linux fork. Nothing else changes.

---

## Version 1.6.12

**Status:** Complete — 2026-10-01. Hardware-verified on the Tab S9 Ultra: 20 installs from a classic
ESP32 over USB, all succeeding, 9 of them repaired by `RESEND`; 22 from an Uno R4 WiFi over USB,
none damaged.

**Scope:** Finishing off and cleaning up 1.6.x. First item: the USB repair round.

**Implemented — `RESEND`, the per-block repair** *(Roger, 2026-10-01: "let's add the message as
you've described, the procedure as you've described")*

- **The problem, plainly:** over a USB serial cable a large panel install arrived damaged about two
  times in five — the right number of bytes, some of them wrong. DASH's checksum caught it every
  time and refused the install, so nothing broken was ever drawn, but the user had to press
  install again. WiFi and Bluetooth repair this underneath; a bare serial line does not.
- **DASH (`Install.kt`):** a damaged block no longer ends the install. It is noted and the install
  runs to its end. After `INSTALL_END`, DASH sends `RESEND|id|name` for each damaged block in
  turn, up to **five** times each *(three as first built; raised after the test below)*. Repaired, the install commits exactly as a clean one would.
  Still damaged after three, or no answer within the existing 10 s idle watchdog, and it fails as
  **CORRUPT** — the same failure as before, never mislabelled as a stall. The progress bar gives
  back a damaged block's bytes and moves again as the repair arrives.
- **Module library (`DashAccessory`, 1.2.0):** answers `RESEND` by sending that one block again
  from flash. Ignores a name it never shipped. Not gated on ACTIVE, since an install happens first.
  The builder writes nothing.
- **Sketches:** every panel sketch picks it up through the library. Versions bumped so DASH offers
  the update — Climate v2.0 (all three), Gauge WiFi v1.5, Gauge BT / USB v1.3. All eight board
  builds compile (Gauge USB and WiFi for both the R4 and the ESP32). Stale "retry deferred" notes in
  the USB sketches corrected.
- **Documents:** `module-sdk.md` — `RESEND` added to the cheat sheet and §4, and §8 amended with a
  dated note (the old abort rule kept and marked superseded in one respect). `arduino.md` — dated
  note reversing the 1.6.10 deferral. Roadmap — 1.6.12 added; 1.6.10's retry paragraph marked built.
  `transport.md` carries no install detail and needed nothing.
- `versionCode` 45 → 46.

**Tested — 2026-10-01, Tab S9 Ultra, driven by Claude over wireless adb**

- **Uno R4 WiFi over USB, 22 installs:** all clean. Not one damaged piece, so no repair ran.
- **Classic ESP32 over USB, 20 installs:** all succeeded. **9 arrived damaged** — always the
  78 KB `dial.png`, always the right length with altered bytes — and **all 9 were repaired**:
  three needed one resend, five needed two, one needed three. The damaged copy is about half of
  all sends, so three attempts would still fail roughly 1 install in 25; **raised to five**
  (Roger), roughly 1 in 150.
- **The finding:** the damage is the classic ESP32's USB-to-serial converter chip, not USB itself.
  `module-sdk.md` §12 (USB serial) now **recommends a native-USB board** for USB modules, and
  corrects its claim that every ESP32 has native USB; `hardware.md` gains a *Module Boards — USB
  Compatibility* section; both USB sketch headers point there. *Roger:* installs happen once per
  firmware version, so an occasional failed install on a less compatible board is acceptable —
  a recommendation, not a rule.

**Outstanding:**

- None from this version. 1.6.12 stays open as the home for any further 1.6.x clean-up Roger
  brings to it; this entry closes the `RESEND` work.
- *(Later the same day — Roger: "we're now done with 1.6".)* **1.6.12 closes, and with it the
  1.6.x era — the Module Panel.** Development moves to **1.7.1**, the first version of the
  Viewport (roadmap 1.7.x). Carried out of 1.6.x unbuilt: `TRIGGER` → the system bar's alerts
  area, night slots, panel warnings beyond logcat, Bronze measurement, `Locale.ROOT` formatting,
  the step-down resize ordering, and `GaugeWifi` on the R4 untested.

---

## Version 1.6.11

**Status:** Complete — 2026-10-01. Hardware-verified by Roger on the Pixel 8 Pro with the Tank
Gauge (Uno R4, USB) and Climate (ESP32, Bluetooth). **This closes the 1.6.x era — the Module
Panel.**

**Scope:** The panel questions deferred across 1.6.8–1.6.10, answered together in the Module
Manager: the order, a main module, handing the panel back, and what each module shipped.

**Decisions** *(Roger, 2026-10-01)*

- **The main module is optional and off by default.** With none, DASH reopens on the last module
  shown — 1.6.8's rule, unchanged. With one set, DASH starts on it every time. That settles the
  roadmap's question of whether the two rules collide: they don't, because one only applies when
  the other is switched off.
- **Return to main defaults to Never.** DASH does nothing until asked.
- **Order by up / down arrows**, not drag — simpler, and dependable with a gloved finger.
- **`TRIGGER` never takes the module panel.** *"There will never be a trigger that takes control
  of a module panel. The triggers will go in the alerts area on the system bar only."* This
  retires the roadmap's speculation that a module raising its hand and the dwell handing the panel
  back were the same mechanism, and the reversing-camera case that joined them.

**Implemented**

- **Modules › Module Management lists installed modules in tab order.** Select a panel module and
  the top bar offers **▲ / ▼** and **SET MAIN / CLEAR MAIN** beside UPDATE / UNINSTALL. The main
  module wears a neutral **MAIN** chip. Order and main appear only for modules that ship a layout.
  The first move writes the whole visible order, so modules never placed get a position at once.
- **The layouts a module shipped, on its card** — the count and the slot names
  (`2 layouts — h_large_day · h_small_day`), in the canonical slot order. A plain fact about the
  module, stated against its record; never on the size tiles, per 1.6.8's ruling.
- **The tab bar follows the order.** `ModulePanelConfig.order` holds the ids; anything unlisted
  follows in name order, so a newly installed module simply joins the end.
- **Layout › Module Panel › Main module › Return to main after** — Never, 10 s … 5 min. A touch
  inside the panel restarts the count, as the fold-back dwell already does. It sits with the
  panel's other timer because it is panel behaviour; the main module itself is chosen in the
  Module Manager, against the module's record, as the roadmap placed it.
- **The switch transition** (`MODULE_PANEL_SWITCH`, 1.6.8) was already surfacing in Transitions
  on its own, as the self-growing registry intended — checked, nothing built.
- `versionCode` 44 → 45.

**Regressions:**

- None found.

**Fixes:**

- None required.

**Outstanding (carried out of 1.6.x):**

- **`TRIGGER` → the system bar's alert area** — still unbuilt; it reaches only the Serial Monitor.
- **USB large-payload corruption** — the per-block retry stays deferred (1.6.10).
- Night slots unreachable, panel warnings only reaching logcat, Bronze unmeasured,
  `Locale.ROOT` number formatting, the step-down resizing before the new layout arrives, and
  `GaugeWifi` on the R4 untested — all carry forward.
- Stacking against the app launcher goes with the launcher (1.8.x), as decided 2026-07-30.

---

## Version 1.6.10

**Status:** Complete — 2026-10-01. Hardware-verified by Roger on the Tab S9 Ultra (SM-X910): the
Tank Gauge on an Uno R4 WiFi over USB and Climate on an ESP32 over Bluetooth, both reinstalled
through the new library, both panels drawing and answering presses, switching between them on
the tab bar.

**Scope:** The module SDK for panels — how to write an ACCESSORY — and the lock of
`module-layout.md`.

**Decisions taken before building** *(Roger, 2026-10-01)*

- **Wall, not island.** The panel spans its edge. Compacting stays what 1.6.9 built it as — a
  mitigation for a panel that asked for more screen than exists — and does not become a general
  shape option. Small / Medium / Large stay as they are.
- **Module icons on tabs wait for version 3.** `MANIFEST` is unchanged; tabs carry the `HELLO`
  name and/or a pip (1.6.9).
- **The per-block USB retry (`RESEND`) is deferred.** It was designed in the roadmap for this
  version and would have been an amendment to the locked `module-sdk.md`; Roger chose not to make
  it now. USB stays sound for line traffic and a coin-toss for large panel payloads, exactly as
  before — a failed install is simply tried again.
- **`translate` stays** — settled 2026-08-14 and not reopened.

**Implemented**

- **`DashAccessory` joins the module library** (`arduino/DashModule/src/DashAccessory.*`, library
  1.1.0), beside `DashSystem` and `DashListener` — extracted from the draft class the three Gauge
  sketches carried since 1.6.5. The builder writes three things: `setAssets()` (the generated
  payload), `onReport()` (say every current value) and `onAction()` (a control was pressed). The
  library sends the `MANIFEST`, streams every `BLOCK` from flash a chunk at a time, reports on
  activation and on a heartbeat, routes `ACTION`, and offers `trigger()` for `TRIGGER`. Every send
  is gated on ACTIVE. A module with more state subclasses it and overrides `handleAction()` /
  `reportAll()` instead — Climate does.
- **`DashAsset` moved into the library.** The six `make_assets.py` generators and their generated
  headers include `<Dash.h>` instead of each defining the struct.
- **All six panel sketches ported** — Gauge and Climate, over WiFi, Bluetooth and USB — each
  losing its copy of the install and heartbeat code. Firmware versions bumped (Climate v1.9, Gauge
  WiFi v1.4, Gauge BT / USB v1.2) so DASH re-runs the handshake through the library.
- **The Tank Gauge runs on an Uno R4 WiFi.** `GaugeUsb` builds for it unchanged; `GaugeWifi` now
  builds for it too (`WiFiS3` on the R4, `WiFi` on the ESP32) — compiled, not hardware-tested.
- **`module-layout.md` is locked**, with Bible weight from here. Its Status section records the
  lock and keeps the provisional text struck through; Open Items closes `translate` and records
  the icon deferral. §8's control/variable rule and §9's `touch` table were given their last read
  and stand as amended at 1.6.7 — the library speaks them exactly (a momentary control arrives
  with an empty value).
- **Dated notes, nothing rewritten:** `module-sdk.md` (the layout lock, the library, the
  deferred `RESEND` — its own rules untouched), `arduino.md` Open Items (where each sub-decision
  landed), and CLAUDE.md's `module-layout.md` entry.
- No DASH app code changed. `versionCode` 43 → 44; `versionName` follows this heading.

**Regressions:**

- None found.

**Fixes:**

- None required.

**Outstanding:**

- **USB large-payload corruption** carries forward, now by decision rather than by schedule.
- **`TRIGGER` reaches DASH but goes nowhere** — the library can raise one; DASH shows it only in
  the Serial Monitor. The system-bar alert area is not built.
- `GaugeWifi` on the R4 is untested on hardware.
- Night slots unreachable, panel warnings only reaching logcat, Bronze unmeasured and
  `Locale.ROOT` number formatting carry forward from 1.6.8 unchanged.

**Notes:**

- The draft-in-the-sketch-first discipline paid off again, as it did for SYSTEM and LISTENER at
  1.4.15: three transports carrying one unchanged class was the evidence it belonged in the
  library, and the extraction changed no behaviour.

---

## Version 1.6.9

**Status:** Complete — 2026-10-01. The reachability rule, the whole visibility / expansion model,
compacting, and the tab bar's own customisation are all built and hardware-verified.
*(Was "In Progress — the tab bar's own customisation is all that remains" until the last piece
landed on 2026-10-01.)*

**Scope:** The trap this version exists for. **Whatever the user configures, the settings panel must
stay usable** — because the module panel's own setting lives inside it, and a panel large enough to
crush the settings panel is a configuration you can enter and cannot leave.

**Implemented — rule 2, the panel yields so settings can open**

- **The 1.6.2 rule that settings never covers the module panel is superseded** *(Roger, 2026-08-27 —
  "ive previously said that the settings panel does not overlap the module panel ever. i think this
  is wrong")*. That rule was right about the Module Mantra and wrong about what happens when
  honouring it makes DASH unusable. A large vertical slot on a 412dp-wide phone takes 358dp of it,
  leaving the settings blind a **54dp band** — so the module panel's own setting became unreachable
  *by the settings panel*, with no way out, because DASH sets its own `requestedOrientation` and no
  Android rotation control gets round it. **The courtesy loses to the trap.** A user-initiated blind
  temporarily occluding the panel is not DASH reaching into the module's box; the panel is untouched
  and returns exactly as it was.
- **The measure is the shape of what is left over, not the size of what was taken** *(Roger)*. DASH
  already knows the screen, the bar and the assembly, so it knows the rectangle the settings blind
  would get. If that rectangle is too far from a usable shape, the panel yields.
- **The aspect is signed — width ÷ height — and that distinction is the finding.** Measured
  unsigned the rule collapses immediately: a leftover space 2.41 : 1 **wide** is perfectly usable
  and one 2.29 : 1 **tall** is not. Same ratio, opposite verdicts. A single unsigned bound cannot
  separate them; a pair of signed bounds separates all 24 test cases cleanly.
- **The two bounds are asymmetric — about 4 : 1 wide against about 2 : 1 tall — and that asymmetry
  is structural rather than a taste.** A setting's control is a fixed `CONTROL_WIDTH` (190dp) and
  cannot shrink, so **width is structural** and a narrow band simply breaks; the content box
  scrolls, so **height is elastic** and a short band merely degrades. The same ~2 : 1 preference for
  spending height over width arrived independently from Roger's device-by-device verdicts and from
  the shell's own construction. Settled at **0.48** and **4.40** after testing on three devices.
- **Two behaviours, and the distinction matters** *(Roger, 2026-08-27)*. **Retract is the guarantee;
  step-down is the courtesy.** The guarantee is unconditional — with nothing to step down to, the
  panel slides off its edge and settings gets a usable shape regardless of what a module shipped.
  The step-down is the nicer version and is only available when the *author* drew more than one
  slot. Climate fills all six day slots and shrinks gracefully; the Tank Gauge ships only the 8 × 3
  and retracts. Both end with a working settings panel, which is the whole point.
- **Step-down goes to the largest smaller slot the module on screen actually ships**, Large → Medium
  → Small, **never crossing orientation** — orientation has been a manual setting independent of the
  docked edge since 2026-07-30, so a vertical Large must not become a horizontal Small.
- **Tab membership is judged on the user's chosen slot, never the yielded one.** This was found on
  paper before it was built and is the version's one real structural trap: a module shipping only
  Large would lose its tab mid-yield, and DASH would then have to put somebody else's module on
  screen. **DASH never chooses a module on the user's behalf**, so the step-down is judged against
  the module already on screen and changes only what that module draws.
- **Retracting rather than covering** *(Claude's suggestion, Roger's call)*. Covering means the blind
  rolls out on top of the king's castle; retracting means the castle visibly steps aside and visibly
  comes back, which the user can read. It also costs no new machinery — floating mode needs the same
  displacement — and it collapses the assembly to the tab bar alone, so settings gets nearly the
  whole screen and **the bar stays exactly where it is**. The bar is DASH's own chrome, it is the
  peek strip, and it is the one thing that must never become unreachable.
- **The stored preference is never rewritten**, exactly as `effectiveEdge` does not rewrite the
  user's edge when the system bar displaces the panel. **This is that decision applied to size
  instead of position** — displace for as long as the collision lasts, and not one frame longer.
  That precedent is why the shape of the rule was recognisable before it was written.
- **The 302dp control-width floor is kept as a hard backstop** alongside the ratio. The aspect rule
  fires first in practice, but a settings control is 190dp plus the shell's own `PANEL_GAP` and
  `BOX_PAD` on both sides, and no ratio rescues a band narrower than that. It is a fact about how
  the shell is built rather than a preference, so it is checked as a fact — and it moves with the
  text scale, because `controlWidth()` does.

**Extended 2026-08-27 — visibility, expansion and the dwell**

*Designed and built the same day the reachability rule landed. The two are the same two motions —
step down to a smaller slot, or retract off the edge — reached from different directions: rule 2 is
DASH forcing them, and this is the user driving them. That they kept arriving independently is the
main reason to believe the model is right.*

- **The module panel now has four states, chosen as one setting** *(Roger)*: **Off**, **Full**,
  **Retracted** (rests off screen, expands on a tap), **Shrunk** (rests at a smaller layout the
  author drew, expands on a tap). The settings page is *built from* that choice rather than showing
  four controls of which two are usually meaningless.
- **The default is Off, and that is the point** *(Roger)*. DASH ships with no module panel until the
  user asks for one. It has no opinion about how much of somebody's screen a head unit should spend,
  so it spends none — which also retires the question of what a sensible default *size* would be,
  since there is no default panel to size. **It also gives the user something they could not do
  before: turn the panel off.** The size tiles had no off state, so installing an ACCESSORY module
  got you a panel whether you wanted one or not.
  *(Migration: existing configs have no `visibility` field, so they adopt the default and the panel
  is absent until set once. `ignoreUnknownKeys` was already on, so the removed test fields are
  ignored rather than throwing.)*
- **The resting-size constraint is structural rather than validated.** Rest size is chosen first and
  the full-size list is built from it, so a resting panel thicker than its full panel cannot be
  expressed — and therefore never has to be detected, warned about, or corrected. Choosing a thicker
  rest pulls the full size back to the thickest still legal in the same write, because there is no
  error there, only a stale pairing.
- **A one-item full-size list reads as a statement, not a control** *(Roger)*, per 1.5.15's
  no-dead-controls rule. Hidden entirely, the user could not see what "full" actually is.
- **Neither size list is filtered by what the installed modules ship** *(Roger)*. These are DASH's
  own surfaces describing DASH's own capabilities, not a report on somebody's modules — the same
  reasoning that rejected module counts on the size tiles at 1.6.8. A missing layout degrades at
  runtime instead.
- **The viewport is laid out for the resting state and never for the expanded one** *(Roger)*. The
  expanded panel is drawn **over** the viewport rather than pushing it, so the running app never
  relayouts — Maps and Spotify reflowing every time you glance at a climate module would be
  intolerable, and it would get worse the more you used it. **The consequence is the whole point of
  the feature: the user pays for the state the panel is usually in, not the state it is briefly in.**
  A Large panel resting off screen costs the viewport nothing but the tab bar and covers the screen
  only while you are looking at it — which is what makes a big panel reasonable on a device where a
  permanent one never was. It also takes the pressure off the morning's sizing problem: *"Large is
  too big everywhere"* was never about Large, it was about Large being permanent.
- **The bar lists the modules that can fill *any* slot the panel can reach — resting or full.** This
  is the version's third statement of one invariant and the reason to write it once: **the bar lists
  what you can go to, and is never filtered by what happens to be on screen.** Judged on the drawn
  slot, a module shipping only Large loses the very tab that is the only way to reach it; judged on
  the full slot alone, a module shipping only Small sits on screen at rest with no tab, so the bar
  names something other than what you are looking at.
- **A tap means: show me this module, at the largest slot it can fill.** Tapping the module already
  on screen toggles it, which is how the panel is folded away by hand rather than waiting out the
  timer — and it means a module with no full layout is reached by a tap that returns the panel to
  rest, so **no tab is ever a dead control.**
- **The bar rides inward with the panel when it expands** *(Roger — "keep it simple and make it ride
  with the motion")*, staying on the panel's inboard face per 1.6.8. The known cost is that the
  control you just pressed slides away from your finger. Accepted knowingly; making the bar's
  position its own setting was raised and deliberately deferred.
- **Any touch inside the panel restarts the dwell — and observing a touch is not taking it.** A panel
  that folded shut under a finger mid-adjustment would be a fault, so the timer resets on every
  press. 1.6.8 gave every gesture inside the boundary to the module and that is untouched: the hook
  sits in the existing press loop after `awaitFirstDown`, which already ran with
  `requireUnconsumed = false`, and consumes nothing. **DASH learns that a finger landed and does not
  take the finger.**
- **At rest, a module with no resting layout is substituted, and the substitution is never stored.**
  Tank ships only Large, so when the dwell folds the panel back Tank cannot be what rests there and
  the first module that *can* is drawn. With one small-capable module installed this is arithmetic
  rather than judgement — there is exactly one valid answer. **The user chooses the resting module by
  choosing which module ships the resting layout** *(Roger)*: 1362 modules installed, one shipping
  Small, and that one is the resting panel, with no "which module rests here" setting needed
  anywhere. What DASH remembers you chose is written only on an actual tap, so it survives the rest
  and survives a restart — the same discipline `effectiveEdge` applies to position and rule 2 applies
  to size. **That is now three places where DASH displaces without ever rewriting.**
- **Opening the settings panel puts the module panel at rest before anything else happens**, which is
  what keeps rule 2 and this feature genuinely independent: the yield only ever measures a *resting*
  assembly and never has to reason about an expanded one. Reversed, settings would measure an
  expanded panel and retract things for no reason. It also means rule 2 fires far less often than in
  the build it was tested in — a panel resting off screen never trips it at all.
- **Naming settled** *(Roger)*: **shrinking** is the feature — a panel resting at a smaller layout its
  author drew on purpose. **Compacting** is the mitigation — DASH scaling a panel that asked for more
  screen than exists. *"The byproduct of a stupid user selecting the wrong panel layout size is
  mitigation of that stupidity, not a feature."* Separately, the panel's docking control stays
  **Position**, and the tab bar's alignment along its edge becomes **Spread**, so three different
  things stop sharing one word.
- **The tolerance steppers are gone and 0.48 / 4.40 are pinned** as constants in `SettingsFitSpec`,
  after verification on the SM-X910, the SM-T710 and a Pixel. They were scaffolding for finding the
  numbers; the numbers are found.
- **The dwell moved from 1.6.11 to here** *(Roger — "i only decided it as ive started to work on it,
  and its become apparant it needs to be added now")*. The roadmap's 1.6.8 note assigning the dwell
  to 1.6.11 with the panel order and the dominant module is amended accordingly. The panel order and
  the dominant module are unaffected and still land at 1.6.11.

**Corrected 2026-09-11 — the version number on disk had gone four versions stale**

*Found on a catch-up read rather than by anything failing, which is the point: nothing failed, it
just quietly said the wrong thing everywhere it was read.*

- **`versionName` was still `1.6.5` and `versionCode` still `39`.** Corrected to **1.6.9 / 43**
  *(Roger — "it needs to not happen again")*. The bump had been unbroken for twenty consecutive
  versions, from code 20 at 1.4.16 to 39 at 1.6.5. It broke at **1.6.6**, in a commit that *did*
  edit `app/build.gradle.kts` — to add the `copySvgSubset` task — and missed the line sixty above
  it. 1.6.7, 1.6.8 and 1.6.9 never opened the file at all, so there was nothing to notice.
- **Three user-facing surfaces read it, and all three were wrong for a fortnight:** Settings ›
  About DASH (`AboutContent.kt`), the copyable `DeviceReport`, and — the sharp one — **the nightly
  GitHub release title**, which `.github/workflows/nightly.yml` greps straight out of the
  `versionName` line. Every nightly published since 13 August was titled *DASH Nightly (v1.6.5)* on
  the public repository.
- **The rule is that the bump lands in the commit that ships the version**, which is what the
  twenty-version run was already doing, so the number on disk always matches the newest committed
  work. 1.6.9 is In Progress, and the disk carries 1.6.9 because 1.6.9 work is committed and
  hardware-verified — the number describes what is built, not what is finished.
- **The cause is that the discipline was never written down.** CLAUDE.md requires a changelog entry
  before a version is complete and says nothing about the build file, so the bump survived twenty
  versions on habit — and habit is exactly what fails when the file is opened for an unrelated
  reason. It is now stated in a comment on the line itself.
- **The number is no longer written in the build file at all — it is read from this document**
  *(Roger — "do it now")*. `app/build.gradle.kts` already contained two tasks, `copyLicence` and
  `copySvgSubset`, whose stated reason for existing is that *"a subset kept in two places diverges
  silently, and in the direction that hurts most."* **The version number was a third such list,
  kept in two places, and it diverged in exactly that direction** — the copy nobody edits going
  quietly wrong while the copy everybody edits stayed right. `dashVersionName` now finds the
  topmost `## Version n.n.n` heading in changelog.md and `versionName` is that. The project rule
  that *"a version number is not complete until a changelog entry exists for it"* stops being a
  convention and becomes the build's own precondition.
- **The digits in the pattern are load-bearing, and this document is why.** changelog.md documents
  its own entry format with a literal `## Version X.x.x` template inside a fenced block, twenty-six
  lines above the first real entry. A pattern matching the words alone finds the template and ships
  a build called *X.x.x*. Requiring `\d+\.\d+\.\d+` walks past it — **the file that is now the
  source of truth contains a decoy, and it is the first line a careless reader would have hit.**
- **A missing heading is a hard build failure, deliberately.** A fallback value would restore
  exactly the silent-wrong-number behaviour the change exists to end. A build that stops is a
  problem that gets fixed; a build that quietly says 1.6.5 is not.
- **The nightly workflow reads the same source rather than the build file.** `nightly.yml` used to
  grep `versionName` out of `app/build.gradle.kts`, which is *how* the wrong title got published —
  and that grep took the **first** line containing the word, so even a comment mentioning it above
  the real line would have hijacked the release title. It now reads changelog.md with the same
  digit-anchored pattern and fails the job if it finds nothing. **One source, two readers, and the
  release title cannot disagree with the APK inside it.**
- **`versionCode` stays by hand, and that is not an oversight.** Nothing in the changelog records
  it — it is Android's install-ordering counter, one per *built* version, and 1.6.1 was
  documentation-only and never got one. It is also far less dangerous stale: a wrong code blocks an
  in-place nightly update and is noticed at once, where a wrong name says the wrong thing quietly
  and forever.
- **Verified three ways, 2026-09-11:** a clean `generateDebugBuildConfig` produces
  `VERSION_NAME = "1.6.9"` / `VERSION_CODE = 43`; temporarily retitling the top entry `9.9.9` moves
  the generated constant to `9.9.9`, proving the changelog is genuinely tracked as a build input
  rather than read once; and removing every version heading fails the build with the intended
  message instead of falling back.

**Built 2026-09-14 — compacting, the overflow mitigation**

*The last of the three motions. Rule 2 forces a step-down or a retract; the visibility model lets the
user drive them; compacting is what happens when neither applies and the shape simply does not fit.*

- **A panel that cannot fit at its true ratio scales down uniformly, keeps its aspect exactly, and
  centres on its edge** *(Roger, agreed 2026-08-27)*, with `backgroundColourPrimary` either side.
  `ModulePanelSpec.boxFor(size, longEdge, availableThickness)` caps thickness at what exists and lets
  the ratio pull the long edge in with it. **The author's shape is never distorted** — a compacted
  panel is the drawing the module shipped, smaller.
- **The panel stops being a wall and becomes an island, and this is the first place that happens.**
  Recorded deliberately, because the open question below — *does the panel have to span its edge?* —
  now has one built answer in the overflow case. That is evidence for the general case, not a
  decision on it; the question still wants settling before 1.6.10.
- **Compacting never trades the long edge away to keep thickness**, which was the other way to solve
  it. Thickness is what a user chooses a *size* for, so capping thickness and letting the ratio pull
  the length in is the same decision `PanelYield` makes: give the user as much of what they asked for
  as the screen allows, and never silently rewrite the request.
- **The band either side is DASH's own surface, not the module's box.** It is a sibling of the panel
  rather than padding inside it — the castle walls are the panel rectangle, and what DASH paints
  outside them is its own floor. It takes the panel's own offsets so it retracts with it, and is
  composed only when the panel actually compacted, so a device that never needs it pays no overdraw.
- **`PanelYield` now measures through the same function.** One place knows a panel can be capped
  rather than two that might drift. It changes no verdict — an overflowing panel used to leave
  settings a *negative* band and now leaves it a *zero* one, and both fail — but rule 2 now reads the
  geometry that will actually be on screen.
- **The resting thickness compacts too**, since that is what the viewport is laid out for. Reserving
  space nothing occupies would have been a separate bug hiding behind a correct-looking panel.

**The finding: compacting is not a head-unit curiosity.** It was scoped as arithmetic for one honest
bad pairing — a Large panel along the long edge of a 1280 × 480 head unit, which at the default 40dp
bar and 36dp tab asks for **480dp of a 404dp band, 119%**, and compacts to 1077 × 404 with 101dp of
background each side. **But a Pixel 8 Pro held in landscape is 997 × 448dp, which is head-unit
shaped, and it overflows at default settings too** — asking 374dp of 372. The first estimate said one
combination in twenty-four could ever trip it, and that was portrait-only for the phone. **The
mitigation is reachable on ordinary hardware turned sideways**, which is a far better reason for it
to exist than a board nobody has yet.

**Hardware-verified 2026-09-14, Pixel 8 Pro, both docks, with a negative control.**

- **Horizontal dock, landscape** (997.33 × 448dp, bar 40, tab 36). Large asks 374.0dp of 372.0dp
  available. Predicted box 992.00 × 372.00dp, band 2.67dp each side; **measured 992.00 × 372.00dp,
  band 2.67dp each side.** Drawn ratio 2.66667 against a target of 2.66667 — **error 0.00000**.
- **Vertical dock, portrait, two tab-bar settings.** Panel long edge 957.33dp, so vertical Large asks
  359.0dp in both. At a **96dp** bar, available is 352dp → compacts to 938.67 × 352. At a **32dp**
  bar, available is 416dp → fits untouched at 957.33 × 359. Measured panel thickness **352.00dp** and
  **359.00dp** respectively: exactly the cap in one case and exactly the request in the other.
- **The differential is the real proof** *(Roger's suggestion — "how about i make the tab bar smaller,
  then you can compare both screenshots")*. Two shots of one screen cancel out the display insets and
  the density, leaving only what changed. The module artwork's height ratio between them measured
  **0.98094** against a predicted long-edge ratio of **0.98050** — 0.045%, about one pixel in 2,700.
  **The panel did not merely get thinner when the bar grew; it got shorter in proportion**, which is
  the ratio being held rather than the box being squashed. The 32dp case is the negative control: the
  rule does not fire when it should not.
- **A module dressed in DASH's tokens compacts invisibly.** Climate paints its own background with
  `backgroundColourPrimary`, the same token the band uses, so its 9.33dp bands are genuinely present
  and genuinely correct and cannot be seen. Not a fault — the system working — but it means the band
  can only be judged against a module that brings its own colours, such as the Tank Gauge.
- **A measurement note worth keeping:** `wm density` reported an override of 408 while DASH was
  laying out at **3 px/dp (density 480)**. Absolute dp read off a screenshot are not trustworthy
  without deriving the scale from a known quantity first — here, a tab pill plus its padding coming
  to exactly 96px at a 32dp setting.

**Built 2026-10-01 — the tab bar's own settings, the last of 1.6.9**

- **Settings › Layout › Module Panel › Selector now holds the whole bar.** In page order: **Shown /
  Hidden**, then Selector size (1.6.8), **Tab style**, **Spread** and **Colour**. Built from the
  first choice, as Visibility is above it — hide the bar and the other four controls are not drawn.
- **Shown / Hidden** — `ModulePanelConfig.tabShown`. A hidden bar is not drawn and **gives its
  thickness back to the viewport**: every measurement of the assembly (layout, compacting, rule 2's
  yield, the settings inset) now goes through `tabBarDp`, which is zero when hidden, rather than
  through `tabThicknessDp`. Built as a plain preference with no guard, exactly as the Outstanding
  note below argued it could be. **Known and left as the user's choice:** hidden *and* Retracted
  leaves nothing on screen to draw the panel out with. Settings still reaches the switch, so nobody
  is stranded — the combination is simply one that shows no panel.
- **Tab style** — `TabStyle`: **Names** (the 1.6.8 bar, and the default), **Pips**, or **Both**. A pip
  is an 8dp dot in the tab's own ink. On a vertical bar the pip and the name turn together, so Both
  reads the same way round on either bar.
- **Spread** — `TabSpread`: **Fill** (the 1.6.8 behaviour, default) shares the edge between the tabs;
  **Start / Centre / End** size each tab to what it carries, never shorter than the bar is thick, and
  gather them. The settings labels follow the bar actually drawn — Left / Right on a horizontal bar,
  Top / Bottom on a vertical one — so the word names what moves.
- **Colour** — `TabColour`: **Light** (the 2026-08-26 pairing, default) or **Dark**, which swaps the
  two theme surfaces. Still only theme tokens, never a free colour — the tokens themselves stay the
  user's in Appearance, and both pairings keep `DashTheme`'s ink rule.
- **Defaults reproduce the 1.6.8 bar exactly**, so an existing install looks unchanged until the user
  touches something. The new fields are serialised with literal defaults, the same discipline as
  `tabThicknessDp`.
- **Hardware-verified by Roger on the Pixel 8 Pro, 2026-10-01** — "all working".

**Regressions:**

- **None found.** Tab membership, the cross-fade, the press predictions and the module selection all
  behave as they did at 1.6.8; the yield changes only which slot the module on screen draws at.

**Fixes:**

- None required.

**Outstanding:**

- ~~**The tab bar's own customisation is still to come** — Style (pips / name / both), Spread (fill the
  edge or gather to one end), the chosen colour pairing, and whether it shows at all.~~ **Done
  2026-10-01** — see "Built 2026-10-01" above.
- **"Whether the bar shows at all" no longer carries this version's trap, and the reason should not
  have to be re-derived.** The roadmap warns that hiding the only switch a user has strands them on
  one module with no way off it. **Rule 2 already answers it.** The system bar can never be hidden,
  the settings button lives on it, and rule 2 now guarantees the settings panel a usable shape
  whatever the user has configured — so **Layout › Module Panel is reachable from any configuration,
  and the way back always exists.** Hiding the bar, resting a panel off screen, a 96dp bar on a
  phone: none of them can strand anybody. That is the whole reason the reachability rule was built
  first, and it means the remaining bar settings can be built as plain preferences with no special
  guarding. *(The same argument covers the app launcher's version of this at 1.8.x.)*
- **The bar's position when the panel expands may want to become a setting** *(Roger)*. It rides with
  the motion for now, which is simple and consistent with 1.6.8, at the cost of the tap target moving.
- **The step-down resizes the box before the new layout arrives.** The document reload takes 20–40ms,
  during which the old artwork is drawn into the new shape — visible on a CINEMATIC transition
  setting. Cosmetic, and shipped knowingly — it was left out of the build whose job was to test the
  principle, and then the principle held. Holding the resize until the document lands is the fix.
- **Module icons on tabs remain time-sensitive**, unchanged from 1.6.8: `MANIFEST` carries `blocks`
  and `bytes` and nothing else, so an icon is an SDK change that must be agreed before
  `module-sdk.md`'s neighbours lock at 1.6.10, or it waits for version 3.
- **USB serial's large-payload corruption**, **night slots unreachable**, **panel warnings only
  reaching logcat**, **Bronze unmeasured** and **`Locale.ROOT` number formatting** all carry forward
  from 1.6.8 unchanged.

**Notes:**

- **Verified on three devices, 2026-08-27** — Samsung SM-X910 (Tab S9 Ultra, 1242 × 1990dp at the
  238 density override, R = 1.602), Samsung SM-T710 (Tab S2 8.0, 1024 × 768dp, **R = 1.333**) and a
  Pixel phone. The T710 matters most as a check that the rule *does not over-fire*: on a 4 : 3 screen
  a square-ish panel leaves a square-ish remainder, and only portrait-vertical-Large trips the
  tolerance at all. A rule that yielded constantly on a well-proportioned screen would have been
  wrong even if it never trapped anybody.
- **The screen's own aspect ratio is the mechanism, and it was Roger's hunch before it was arithmetic.**
  The fraction of screen a panel eats is `screen aspect ÷ slot aspect`. A tall narrow device has a
  large R and R is the numerator, which is the entire reason a Pixel is punished where a tablet is
  not — and why the same module, same slot, costs **R² more** lying along the screen's long edge than
  across its short one. 4.9× on a Pixel, 2.6× on the Tab S9 Ultra.
- **The thing people instinctively want is the expensive orientation.** A wide panel along the bottom
  of a wide screen is the case the arithmetic punishes hardest. Worth knowing before the slot set
  locks at 1.6.10 — though **adding slots does not fix it**: to clear the horizontal tolerance on a
  Pixel you would need a slot aspect of 6.5 or thinner, and on a 1280 × 480 head unit 7.8 or thinner.
  `% = R ÷ slot aspect` is arithmetic, so on an elongated screen a panel lying along the long edge
  can only ever be thin. The six slots are not wrong and cannot be made right by addition.
- **An open question was raised and deliberately not answered: does the panel have to span its edge?**
  Rule 1's overflow behaviour — scale to fit, keep the ratio, centre it, fill the remainder with
  `backgroundColourPrimary` — is already a panel that does not span. Making that the general case
  would dissolve the sizing problem entirely and turn Small/Medium/Large into what they already are,
  *shapes* rather than sizes, with thickness becoming the user's on a stepper. The cost is that the
  panel stops being a **wall** and becomes an **island** on its edge. **Not decided.** Recorded here
  because it is the real question under the whole sizing discussion and it wants settling before
  1.6.10.
- **Rule 1 (overflow) is built — 2026-09-14**, as *compacting*, above. Agreed 2026-08-27 and
  unchanged in the building: scale down uniformly, keep the aspect exactly, centre on the edge
  *(Roger)*, `backgroundColourPrimary` either side, never mis-drawn. **The 108% figure quoted here
  for a 1280 × 480 head unit was understated** — it counted the tab bar but not the system bar. At
  the default 40dp bar and 36dp tab the true figure is **119%**.
- **The controls came before the constraints, on purpose.** Roger's four device verdicts turned out
  to be a *taste* judgement about which slots look right on which screen — not a statement about
  whether settings could open — and taste is the one thing DASH does not encode. What the exercise
  produced instead was a mechanical rule about when the interface actually stops working, which is
  DASH's business, and left the looks-right question where it belongs.

---

## Version 1.6.8

**Status:** Complete — two modules, two transports, two tabs, and a cross-fade between them. Hardware-verified by Roger on the Tab S9 Ultra, 2026-08-26.

**Scope:** More than one accessory module installed, and a way to get from one to the other. 1.6.7 made a panel a thing you can operate; this version makes it one of several.

**Implemented:**

- **The swipe was dropped before it was built, and the tab bar took its place** *(Roger, 2026-08-19)*. The inventory and interface.md had modules cycled by swiping *inside* the panel. **They are not: the swipe belongs to the module.** The panel boundary is the module's box under the Module Mantra, and a single-finger gesture inside it is the module's input to claim — hold-to-repeat on a stepper, a drag slider, a swipe through presets. **Once DASH takes that gesture it can never give it back** without breaking every module built in the meantime, so it is not taken at all. The concrete problem was already sitting in the code: `PanelContent`'s press fires on touch-*down* and consumes the event, so a swipe starting on a control would have sent an `ACTION` before the finger moved and no drag detector would ever have seen the gesture.
- **So DASH's control lives outside the box** — a tab bar on the panel's inboard edge, the side facing the content area. DASH owns the walls; this makes one wall thick enough to touch. A tab per module that can fill the current slot, **tapped directly rather than cycled**, so six modules are one tap apart rather than five. Labels come from the module's `HELLO` name and needed no SDK change.
- **The bar cuts into the viewport, not into the panel** *(Roger)*. Not out of the panel's own footprint: the module's box would then be thinner than the slot ratio it was authored to, and a module drawn for 8 × 3 must be drawn into 8 × 3. Not overlaid on it either, which would be DASH drawing on the module's box. The panel keeps its exact shape and the cost lands on content area.
- **The bar shows whenever the panel shows, including with one module installed** *(Roger)* — so the viewport is the same size on Monday and Friday. Installing a module changes what is *in* the panel, not how much content area the screen has.
- **Membership: installed ACCESSORY, has a layout for the selected slot, gets a tab.** SYSTEM and LISTENER modules never appear — they have no panel to draw. **A silent module keeps its tab** *(Roger)*: the install record is the tenancy, §6's rule is about layouts rather than liveness, and DASH does not annotate a tab with the state of its board. It also stops tabs appearing and vanishing every time a board brownouts. **Different id, different module, no exceptions** — `GaugeWifi` and `GaugeBt` are the same panel on two ids and get two identical tabs, and DASH neither disambiguates them nor comments on it.
- **Load on demand, and no cache** — measured rather than assumed, and the measurement reversed the design. The Tank Gauge is a 1.4 KB layout, a 10.6 KB SVG and a 1600 × 600 PNG decoding to 3.7 MB: roughly 20–40 ms, one to three frames. Climate is a 14 KB layout and one 10 KB vector with no raster at all — under 10 ms. **There is no load to hide**, so only the panel on screen is held decoded, memory is bounded at one panel however many modules are installed, and the LRU cache first proposed here was machinery for a cost that does not exist.
- **The switch is a cross-fade**, with the outgoing panel held until the incoming one is ready so there is never an empty box. Not a slide: sliding says Climate lives to the left of the Gauge, and a tap can jump from the first tab to the fourth, so there is nothing meaningful to slide past. **It is DASH's transition, so it takes the user's INSTANT–CINEMATIC setting** — the opposite of anything inside a panel, where §5 gives the module's own durations and DASH's never enters. Registered as `MODULE_PANEL_SWITCH`, and because the Motion page builds itself from the registry it appeared in Settings the moment it was added.
- **`rememberPanelPresses` became per-panel rather than per-screen.** This is the real structural change of the version: a cross-fade composes two panels at once, and one call at the screen could only ever describe one of them.
- **Which module was last shown survives a restart** — DASH restarts every ignition cycle, so losing the choice every time would be losing it daily.
- **No ordering at all in this version** *(Roger)*. Tabs sit in database order. Panel order, the dominant module and the dwell are one story and are told once, at 1.6.11, rather than a small version of it here and a real one later.

**Extended 2026-08-26 — the bar's colours and its size**

*Both were 1.6.9 scope. They landed here because the version's own hardware test put the bar in front of Roger, and looking at a thing is what generates the notes about it.*

- **The colour pairing was inverted** *(Roger)*. The bar took the *secondary* (dark) surface when first drawn, on the reasoning that the panel's floor is the primary surface and a bar in the same colour would read as part of the module's box. It now takes the **primary** (light) surface, with the selected tab a dark `backgroundColourSecondary` pill. Ink follows `DashTheme`'s own stated rule rather than eye — the primary surface carries black, the secondary carries light grey — which is 13.9:1 unselected and 11.1:1 selected on the default palette. **The mid-grey accent was dropped from both roles it held**: on a light bar it sits too close to the background to be read at a glance, which is precisely the trap 1.5.12 dug itself out of.
- **The concern raised against the swap was real and turned out to be overstated.** The bar does now share a token with the panel floor — but that floor is only visible where a module leaves it bare, and against the dark viewport the light bar reads clearly as a bar. Recorded because the flag was worth raising and the answer was worth checking rather than assuming.
- **Selector size became the user's** — Settings → Layout → Module Panel → **Selector**, 24–96dp in steps of 4, default 36. **The reasoning is Roger's and is the point of the entry:** asked how thick the bar should be, the answer is that it is not DASH's to choose. The cost lands on the content area, so the same 36dp is nearly free beside a large 8 × 3 panel and a real bite out of a 16 × 1 one — no single number serves both, and the person looking at the screen is the one who can see which case they are in. *"We don't get to choose that, the user chooses that. We just give them the tools."*
- **Range chosen from numbers the system had already agreed to**, not invented here: 24dp is `SystemBarConfig.MIN_ELEMENT_HEIGHT_DP`, already the smallest DASH lets anything be; the step of 4 matches every other size stepper in DASH, because a different feel under the thumb on one control would read as a fault rather than a choice.
- **No hard floor** — deliberately. 24dp is small for a gloved hand, and that is the user's call: interface.md reserves hard floors for safety-critical targets, and the reachability question the bar really raises — what happens when the only way off a module is made too small to hit, or hidden altogether — is 1.6.9's to answer rather than something to pre-empt with a floor here.
- **The boundary is shown in the control itself**, per interface.md's v1.3.4 decision: at either end the caption under the number reads `MIN` or `MAX`. Carried in the stepper's existing caption slot so the shared component is untouched and no other setting in DASH changes behaviour.
- **Stored in `ModulePanelConfig`** with the panel's edge and size — it belongs to that assembly, so it travels with it and needs no key of its own. **The serialised default is the literal `36`, not `ModuleTabsSpec.DEFAULT_DP`**: a default that moved when somebody edited a constant would silently reinterpret every config already written to disk without that field.
- **The control sits at the bottom of the Module Panel page, and briefly did not.** It was first sited on Appearance → Size & Scale, where every other DASH surface size lives — Roger's suggestion, and a good one. He then went and looked at the menus and reversed it: the Module Panel page reads top to bottom as one continuous answer, and Size → Position → Selector is that answer. Recorded because the second look was the better one.

**Regressions:**

- **None.** The one behaviour that looked like a regression on paper was not: *switching away retires the outgoing module's outstanding presses.* `PanelPress.kt` already documented that as deliberate, and it is right — a prediction exists so the panel does not look dead under a finger, so once the panel is off screen there is nothing to draw ahead of. What 1.6.8 changed is only that the case now actually happens.

**Fixes:**

- None required. Nothing broke.

**Outstanding:**

- **The bar is not yet customisable beyond its size.** Tab style (pips / name / both), alignment along the edge, chosen colours, and whether it shows at all are 1.6.9. **Whether it shows at all carries that version's trap**: hiding the only switch a user has strands them on one module with no way off it.
- **Module icons on tabs remain time-sensitive.** `MANIFEST` carries `blocks` and `bytes` and nothing else, so an icon is an SDK change that must be agreed before `module-sdk.md`'s neighbours lock at 1.6.10, or it waits for version 3.
- **USB serial's large-payload corruption is unchanged** and still deferred to 1.6.10 with the per-block retry. It bit again during this version's setup, and the workaround is still to run the install a second time.
- **Night slots are still unreachable**, the **panel warnings still only reach logcat**, **Bronze tier is still unmeasured**, and **number formatting is still `Locale.ROOT`** — all carried forward from 1.6.6 and 1.6.7 unchanged.

**Notes:**

- **The Tank Gauge sketch ran on an Arduino Uno R4 WiFi with no changes at all.** It was written for and commented for an ESP32 DevKitC; the second board was an R4 only because there was one spare power lead in the house. It compiled first time — 57% of flash, 20% of RAM, with 90 KB of that being panel artwork in `PROGMEM` — and answered `DISCOVER` with the right `HELLO` on the first attempt. **Nothing in the sketch is board-specific**: `Serial`, `millis()`, `strcmp`, and assets streamed from flash. That is a stronger statement about the SDK than the tab it enabled, and it was got for free.
- **A Bluetooth accessory needs power, not a cable to the tablet.** Obvious in hindsight and not obvious at the bench, where the instinct is to plug everything into the head unit. The ESP32 running Climate over SPP will do its whole job from a phone charger. Worth knowing for anyone laying out a real installation.
- **A board's identity can be read straight off the wire before deciding anything.** Resetting the ESP32 over DTR and listening at 115200 printed `DASH Climate (ACCESSORY over Bluetooth SPP)`, its payload size and its pairing name in one line — which settled what was on it, that it was a panel module, and that it would never answer `DISCOVER` down that cable, because the cable is only its debug console. Cheaper than guessing and much cheaper than reflashing to find out.
- **`ModuleTabsSpec` now holds the range rather than the value.** The bar's dimensional rules and their reasoning stay with the bar; the number the user chose lives with the panel. Worth keeping straight when the rest of the bar's settings arrive at 1.6.9.

---

## Version 1.6.7

**Status:** Complete — a module's panel pressed, and both ends answering. Hardware-verified by Roger on the Tab S9 Ultra over WiFi and Bluetooth, 2026-08-14.

**Scope:** Data between the two devices. 1.6.6 drew what the module sent; this version makes the panel a thing you can operate, and closes the loop back to the board.

**Implemented:**

- **`touch` bindings are honoured** — parsed since 1.6.6 and doing nothing until now. All three target routes are pressable (`module-layout.md` §3), and the third is the one that earned its own file (`PanelTouch.kt`): `layer#element` points at a named path *inside* parsed artwork, so finding it means walking the renderer's own coordinate chain backwards — the node's accumulated group transform, the artwork's uniform fit into its layer, the layer's box within the panel. Getting that wrong would have been invisible rather than obvious: the button draws in the right place and answers somewhere else.
- **The press area is the element's bounding box at its authored position.** Not its outline — a `+` drawn as two thin bars is a button, not two bars, and hit-testing the path would make the gaps in the author's own glyph into dead spots. And not where a binding has *moved* it to: a control that slid away from your finger as you reached would be indefensible, so the drawn position is deliberately not consulted.
- **DASH imposes no minimum touch size.** A control too small to hit is a mistake the author can see and fix in their own artwork; DASH silently inflating it would be reaching inside the panel boundary to correct a design decision. The previewer is where that should be said out loud.
- **Press feedback** (§4.2) — momentary, local, and about the interaction rather than the state, which is what separates it from `style`. The effect is built whether or not the control is pressed, with the press choosing only its target; building it on press would make a 120 ms fade light gently and go out like a switch.
- **The optimistic update, and the timeout that keeps it honest** (§8). Predictions live in a layer *in front of* `ModuleData`, never inside it — that store holds what modules have said, and a prediction is what DASH expects them to say next. Writing one into the store would have made the State Inspector report a reading no module ever sent. The two are merged only in the map the panel draws from.
- **§8's three outcomes need only two mechanisms.** Any report of the variable after the press retires the prediction, so "it worked" and "it heard and could not comply" both simply end with the panel drawing what the module actually said. Only silence is a fault.
- **The Tank Gauge became a thing you drive.** The sweep is gone — it proved the needle moved and could never prove *why* it moved. Now nothing moves unless a button is pressed, so every movement on screen is attributable to the press that caused it. Three controls, chosen to cover both halves of §8: **PLUS/MINUS** are momentary, because "one more than it is now" is not a value anyone can name in advance; **ZERO** names the variable and carries a literal, which is the only control on the panel that exercises optimism at all.
- **The clamp is not a refusal.** Pressing PLUS at 11 bar reports 11 again, and the module reports it unconditionally because the `REPORT` is the acknowledgement as well as the value. That gives §8's middle row a test without building anything for it.
- **`DashAccessoryDraft` gained `onAction` and `onActivate` hooks**, in the library's own function-pointer idiom, across all three sketches — the draft the 1.6.10 helper is extracted from, now with the inbound half written. A control the firmware does not recognise is ignored in silence: the layout on the tablet may be newer than the firmware on the board, and refusing a press with an error would turn ordinary version skew into a fault.
- **The state dump on `ACTIVATE` is now load-bearing** (§8). With the value held rather than generated, this is the first module that would show a blank panel without it.

**Documentation:**

- **`module-layout.md` §8 gained the rule it had always assumed** *(amended 2026-08-14)*: **a touch binding's `control` is the variable it predicts, and its `value` is the prediction.** The whole reconcile depends on the two carrying the same name, and the spec stated it nowhere — the kind of gap that only surfaces when somebody implements it. **A control with no value predicts nothing**, which is where §8's momentary case comes from: it is not a special case in DASH, it falls out of the rule.
- **§9's `touch` table carried an actual error**, corrected and flagged rather than quietly overwritten: `value` read *"omitted sends an empty value field"*. It sends **no** field — `ACTION|id|control`, no trailing separator, the same shape an event-only broadcast takes. The old text would have had a firmware author parsing for a field that never arrives.

**Regressions:**

- **None shipped.** One defect was introduced and fixed within the version — below.

**Fixes:**

- **The optimistic update had no clock, and the needle stuck.** Found by Roger on hardware, doing exactly the test that was asked for: up to 7 bar, board powered off, press ZERO. The needle went to zero and stayed there indefinitely. **Cause: the coroutine that expires predictions was keyed only on the module's report map.** It ran once at composition, found nothing outstanding, fell straight out of its loop and *completed* — and a press arriving afterwards changed neither key, so nothing restarted it. The optimistic write worked perfectly; the timeout that was supposed to make it honest was never running. **The same defect killed the escalation counter**, which could never increment because nothing ever timed out. Both return with one fix: the predictions are now part of the key, so a press restarts the loop.
- **The general lesson, recorded because it is cheap here:** a `LaunchedEffect` that polls state must be keyed on the state that decides whether it should be polling, or it only ever answers the question it was asked at composition.
- **Verified on hardware afterwards, on both transports**, and the escalation counter with it — the module's own log is the evidence rather than anybody's recollection: `1 action(s) unanswered within 1500ms — reverted (1 in a row)` on the WiFi build, then `(1 in a row)` and `(2 in a row)` on the Bluetooth one. The second of those is the half that could not previously have been reached at all.

**Outstanding:**

- **`translate` is not a removal candidate, and should not be raised as one at the lock.** The 1.6.7 redesign dropped the arrow head, so nothing in the current build uses it — but §4.6's test is whether it was *ever* proven, and it was, at 1.6.6, against real artwork. Removing a proven binding because today's panel stopped needing it would decide on behalf of every author who has not built theirs yet. Recorded here so the question is not re-opened at 1.6.10 on a reading of "unused" that was never meant.
- **How many repeated timeouts constitute a fault, and how it surfaces**, remains §8's open item. The count is kept and logged; no threshold and no badge have been invented for it, because that wants tuning against real hardware rather than guessing.
- **USB serial's large-payload corruption** is unchanged and still deferred to 1.6.10 with the per-block retry. Nothing in this version touches it — an `ACTION` is a short line, not a block.
- **Night slots are still unreachable**, the **panel warnings still only reach logcat**, **Bronze tier is still unmeasured**, and **number formatting is still `Locale.ROOT`** — all carried forward from 1.6.6 unchanged.

**Notes:**

- **A panel can be tested against DASH's own parser with no board at all.** Staging a module record and its assets into `files/modules/<id>/` with `run-as` and restarting DASH makes `PanelLoader` read the layout for real and log every warning — which caught that the new artwork parsed cleanly (only the three deliberate out-of-subset warnings) hours before any hardware was connected. A rendered mock-up cannot prove that; it only proves the design. **The staged record is a forgery and must be uninstalled before a real board installs**, or the board will match its version and skip the handshake.
- **The two boards are the same module twice, and DASH treats them as two.** `GaugeWifi` is `0000DA58AC01` and `GaugeBt` is `0000DA58AC02`, both claiming the large horizontal slot — and 1.6.6 draws the first that can fill it, in database order. So the second installs correctly, sits on disk and never appears, which looks like a failed install and is not one. Swiping between them is 1.6.8, and this is the concrete case that version exists for.
- **A locked, dozing tablet does not accept incoming TCP**, however healthy the transport is. The board dialled correctly for the better part of an hour against a DASH whose accept loop had bound its port without complaint. Ping answered in 60–110 ms — the WiFi power-save signature — while port 3274 timed out. Worth knowing before diagnosing a WiFi module that "will not connect".

---

### Extended 2026-08-15 — the module's own message board, and the Climate panel

**Not a new version** *(Roger's call)*. This was written as firmware for a climate panel, and it is an
extension of the press work above rather than something of its own — **1.6.8 remains multi-module.**

**It was not only firmware, which is why it is recorded here at all.** It changed `PanelVariable`, the
layout reader, `TouchBinding.step` and `PanelPress`, and added §8a to `module-layout.md`. A reader who
found `step` in the format with no entry explaining it would have no way back to the reasoning.

**Why the panel forced it.** The Tank Gauge had one variable and three buttons. Climate has nine variables and thirteen controls, and almost all of them are steppers and toggles — and **neither could be drawn before the module answered**, because "one more than whatever it is now" is not a value anybody can name in advance. That is what this work fixes, and it is why designing the panel changed the platform rather than just adding a sketch.

**Implemented:**

- **§8a — the module's own message board.** A module may declare, in its layout, the values each variable takes and in what order. **This is the private twin of `system_commands.md`**: DASH's shared board has a declared vocabulary because it is shared, and a module's board is written by the module because it is its own. That single difference is the whole idea, and it is why this does not breach the mantra — nobody reaches into anybody's box. *(Roger's design, and his framing; I had been circling a worse version of it.)*
- **A list, and no type system.** A toggle is a two-element list that wraps; a fan is an eight-element list that stops. Neither needed a rule of its own, which is the sign the shape was right — **the toggle problem disappeared into the stepper solution rather than needing its own mechanism.**
- **The stepper stopped being a special case.** DASH finds the current value, moves one place, and sends **the value it landed on** — `ACTION|id|fan|4`, never `fan_up`. So a `+` is an ordinary set-this-to-this, it predicts and draws under the finger like any other control, and §8's rule (the control names the variable, the value is the prediction) stops being a case and becomes universal. **Everything on the wire is now the same message.**
- **DASH does no arithmetic on a module's data.** It never adds one to `3`; it moves along a list the module wrote, with no idea what the values mean or that `4` is larger than `3`. Delegation, exactly as a theme token is — not DASH forming an opinion.
- **A press that lands nowhere sends nothing** — and this reversed a decision on contact with the code, which is the honest part. The intent was to send regardless, because DASH's index is a *belief* and a stale belief should not swallow a real press. But once a stepper works out its value first there is no direction left on the wire, so the only thing an end-stop press could carry is DASH's current value — which would **command** the module to whatever DASH last believed. A stale belief swallowing a press is a nuisance; a stale belief issuing a command is the second-source-of-truth failure the whole design exists to prevent. The heartbeat corrects a stale belief in a second or two anyway.
- **The Climate module** (`ClimateWifi` / `ClimateBt` / `ClimateUsb`, ids `0000DA58AC04/05/06`) — one vector, no raster, nine variables, thirteen controls. The three sketches share `assets/` **and** `ClimateModule.h`, so the firmware behind the three panels cannot drift any more than the artwork can.
- **The firmware holds strings and never steps anything.** Because DASH sends the destination, the board needs no copy of the value lists at all — it is told a string, keeps it, reports it. The lists live once, in the layout, and cannot drift out of step with the panel that draws them.
- **The module's logic is genuinely its own.** Touching the fan or the vents drops AUTO; AUTO takes the vents by setting `mode` to a value no zone matches, so all three go dark together with no "nothing selected" concept anywhere in the format; leaving AUTO restores the last hand-picked vent from a slot deliberately placed *after* the reported ones so it never reaches the wire. **DASH is told none of it.** One `ACTION` goes out, the module changes two variables, reports both, and the panel redraws both.

**Documentation:**

- **`module-layout.md` gained §8a**, and §9's `touch` table gained `step`. Provisional with the rest until 1.6.10.
- **A `range` shorthand was designed and rejected** (Roger's ruling). The deciding case was his own temperature scale, whose ends are `min` and `max` rather than numbers — so the shorthand could not express the very thing it was invented for. Twice now a real control has had words at the ends and numbers in the middle, a fan and a temperature, which suggests that is the norm. Writing the values out longhand also removes a whole class of trouble: the strings are the contract, and a generated `17.0` would never match a reported `17`.

**Regressions:**

- **None shipped.** Two defects were found and fixed within the version — below.

**Fixes:**

- **Every press stepped from the value the panel had at install.** Found by Roger in about ten seconds: fan on 3, press down → 1, press up → 3, and never higher. `PanelContent`'s gesture handler is `pointerInput(document)`, so its coroutine is built once per panel and had captured that composition's `onPress` — a lambda closing over the values map as it was then. The module boots at `2`, so every press stepped from a frozen `2` for ever. **Fixed with `rememberUpdatedState`**, so the long-lived coroutine always calls the current handler; keying the input on the handler instead would have torn down and rebuilt the gesture loop on every reported value, a far worse cure.
- **The ESP32 WiFi retry loop could never associate on a slow AP.** `WiFi.begin()` was being called every 3 s while the radio was still associating, which the core refuses *and restarts* — so association never completed, and the sketch printed `sta is connecting, cannot set config` for ever. Invisible on a fast AP, total on a slow one. Fixed in `ClimateWifi`; **`GaugeWifi` still carries it.**

**Outstanding:**

- **Multi-module and swiping is untouched and remains 1.6.8.** The Tank Gauge is being retired, so there is one module at a time on the bench and nothing to swipe between yet.
- **`GaugeWifi` still has the association defect.** Left alone deliberately — the Tank Gauge is on its way out, and a fix nobody will run is not worth the reflash.
- **`style` still has no separate `fill` and `stroke`** (§4.3's open item), which is why every surface in the Climate artwork is a flat fill with no border: a bordered button delegated to a token loses its border into its own fill. Worth settling before the 1.6.10 lock rather than after.
- **The WiFi sketches are untested.** They only ever run on the mobile router, never the home AP, so `ClimateWifi` has been compiled and never connected. Bluetooth is the bench transport.
- **Night slots, panel warnings reaching only logcat, Bronze unmeasured, `Locale.ROOT` number formatting** — all carried forward unchanged.

**Notes:**

- **Two long-lived coroutines holding stale state, on consecutive days.** 1.6.7's revert bug was a `LaunchedEffect` keyed only on the report map, so the expiry loop completed at composition and no press restarted it. This one was a `pointerInput` keyed only on the document, holding a lambda that had closed over stale values. **Same disease: a coroutine keyed on one thing while quietly holding a copy of another, invisible until somebody drives the panel properly.** Recorded as a pattern rather than two incidents, because there will be a third.
- **Designing the panel changed the platform, which is the third time that has happened.** The climate concept forced the message board exactly as the pressure monitor forced number formatting and the air-ride panel proved mutual exclusion never needs to exist. A specification written from first principles would have shipped a stepper that could not be drawn.
- **Hand-drawing letterforms as SVG paths does not work.** The first attempt rendered "AU" and "TEMF". They were set in a real typeface and converted with Inkscape's Path > Object to Path instead — which is precisely what the spec already tells authors to do, and now there is a reason in the record for why it says so.
- **A module change is a slower loop than a DASH change**, and it is worth knowing before iterating on a look: rebake assets → reflash the board → bump the version → UPDATE in Module Management. Roger spent part of an afternoon looking at artwork two versions old while I described something else, because I had held the reflash back waiting on a decision. **Bump and flash first, discuss after.**

---

### Extended 2026-08-16 — the Climate module fills all six day slots

**Not a new version, and not a platform change** *(Roger's call — "I just wanted to actually have all
the sizes")*. A continuation of the tangent above: the Climate module shipped one slot, and now ships
six. **1.6.8 remains multi-module.**

**Not one line of DASH changed, and that is the entry.** Every slot name, every ratio, the slot
selector and the *no layout, no panel* rule were already built at 1.6.4 and 1.6.6. Adding five shapes
to a module needed five drawings and five layout documents and nothing else — no new message, no new
field, no capability. **That is `module-layout.md` §6 working as designed**, and it is the first time
the six-shape set has been exercised by a module rather than described in a document.

**Implemented:**

- **Five more slots on the Climate module**, all `day`: `h_medium` (16 × 3), `h_small` (16 × 1),
  `v_large` (3 × 8), `v_medium` (3 × 16), `v_small` (1 × 16). Twelve blocks, 115,972 bytes.
- **The two large shapes carry everything; the two small ones carry four controls.** `h_small` and
  `v_small` show temperature, AUTO, A/C and demist — **§6's progressive disclosure, decided rather
  than described.** The fan has no control because AUTO is running it, which is the honest reading of
  a strip a sixteenth of its own length thick.
- **Each shape got its own answer to the same problem, which is the argument for the set.** A stepper
  is horizontal in `h_large` and `v_large`, and **stands up in `v_medium`** — `[−] 21.5 [+]` across a
  320 px column leaves the number about 100 px, and "21.5" is four characters, so `[+]` above, the
  value across the full width, `[−]` below. Two shapes, two designs. Anything DASH did to reflow one
  into the other's slot would be DASH forming an opinion about someone's artwork.
- **The vertical slots are re-laid out, never rotated.** §6's *"every vertical slot is its horizontal
  twin stood on its end"* is a statement about the **ratio** — it is what makes three shapes to learn
  rather than six. Rotating the artwork would give sideways lettering and sideways glyphs. Left-to-right
  becomes top-to-bottom instead, so the reading order survives the quarter-turn.
- **The layouts are generated from `h_large_day.json`, not retyped.** Each new layout takes that
  document's bindings verbatim and moves only the artwork name and the two text layers; the two small
  slots are the same list *filtered* to the ids their artwork actually draws, which is where their
  four variables and eighteen bindings come from. **A binding cannot pick up a different meaning on
  the way into another slot** if nobody ever retypes it.
- **The artwork was renamed to a matrix** — `climate_h_large/medium/small.svg` and `climate_v_*.svg`,
  replacing `climate.svg` / `climate_medium.svg` / `climate_small.svg`. Six files that read as
  three shapes × two orientations.
- **Shared glyphs are lifted verbatim and placed with a group transform**, so the A/C asterisk in
  `v_small` is literally the same path as the one in `h_large` rather than six redrawings. Confirmed
  safe first: `VectorArtLayer` applies `node.transform` to the *draw scope*, so a scaled stroke scales
  with its geometry and the artwork cannot diverge from what the parser does with it.

**Fixes:**

- **AUTO was overflowing its button, because the label is 47 units wide and I had assumed 38.** Placing
  a lifted glyph means centring it, and centring it means knowing its bounding box — which I had been
  estimating by reading path data. Every shared glyph is now measured with `inkscape --query-id` and
  every transform computed from the real numbers.
- **Fitting each glyph to its own button independently broke the family.** The three vent arrows came
  out at scale 1.52, 1.02 and 1.75 — each the same *fraction of a button*, and no longer one drawing.
  **A family now takes a single scale, the smallest that fits every member.** In the source artwork
  they sit at scale 1 in identical buttons, so their relative sizes are already right; sharing one
  scale is what carries that relationship into a new shape. This is the fix that made the six panels
  look like one design rather than six variations.

**Outstanding:**

- **Not yet seen on the tablet.** The Bluetooth board is flashed at `v1.8` and connected, and the
  panels were checked against a throwaway renderer in the scratchpad — **which proves the design and
  not the parser.** Cycling the panel size and edge through all six on the Samsung is still to do.
- **`ClimateWifi` and `ClimateUsb` are updated in the repository and not flashed.** One board on the
  bench; Bluetooth is the bench transport.
- **The sketch is at 89% of program storage** (1,174,924 of 1,310,720 bytes). **The six `night` slots
  would roughly double the payload again and will not fit on the default partition** — that is a
  partition-scheme change to `huge_app` when Ambient lands in version 2, not a surprise on the day.
  Worth knowing now because it is the first hint that a module shipping twelve fully-authored slots
  has a real flash budget to think about.
- **Night slots, panel warnings reaching only logcat, Bronze unmeasured, `Locale.ROOT` number
  formatting, `style` still having no separate `fill` and `stroke`** — all carried forward unchanged.

**Notes:**

- **A previewer that guesses is worse than no previewer.** The throwaway renderer used to look at
  these put its text layers in *pixel* space when the artwork carries its own viewBox, and drew the
  fan value off the bottom of the panel. Caught in seconds because it was obviously wrong — but it is
  exactly the failure `panel-preview/CLAUDE.md` names as the hazard, arrived at independently and
  within an hour of starting. **The scratchpad tool is not panel-preview and must not become it**; it
  resolves theme tokens and static bindings well enough to judge a layout and knows nothing about the
  subset.
- **The measured-and-shared-scale rule is the reusable part.** Any module author lifting one glyph
  into several slots hits both defects in the same order: first the bounding box they guessed, then
  the family that stopped matching. Worth saying out loud in the SDK material at 1.6.10.
- **Six fully-authored slots is roughly 116 KB and about fifteen minutes of drawing per shape.** A
  useful number for the "is twelve a burden on community builders" question, which the answer to
  remains no — every slot is optional and a one-shape module is a legitimate module. But the cost of
  choosing *all* of them is now measured rather than guessed.

---

## Version 1.6.6

**Status:** Complete — a module's layout drawn on the panel, verified by Roger on the Tab S9 Ultra over **all three transports**, 2026-08-13.

**Scope:** What the module sends is what the panel draws. 1.6.5 got a panel's artwork onto the tablet's disk and deliberately stopped there; this version reads it back and renders it, live, from the module's own data.

**Implemented:**

- **The twelve slot names became a type** (`panel/LayoutSlot.kt`) — derived from `PanelSize` plus orientation and ambient, never listed. `module-layout.md` §9 rules that a block named for a slot *is* a layout, and until now the names existed nowhere machine-readable, which is why 1.6.5's `h_large_day` landed as an ordinary asset. **`InstalledModule.slots` derives that classification from the block names already recorded**, so the 1.6.5 install was understood without a reinstall.
- **The SVG parser shipped** — `SvgParser` / `SvgArt` / `SvgSubset` promoted out of the throwaway spike branch into `com.dash.android.panel`, with the harness and `SpikeConfig` binned. Artwork parses into DASH's own flat IR (named nodes, each holding a path built once at parse time), not an `ImageVector`, so a bound element is addressable by construction and a frame costs a matrix and a paint.
- **`gradientTransform` implemented** — the one Open Item marked *"before 1.6.6 ships"*. Control points are taken through the gradient's own transform before being resolved against the shape, which is exact for translation, rotation and uniform scale — the rotation case Inkscape actually writes. A non-uniform scale or skew is approximated (a radius is one number) and **says so in a warning** rather than being discovered later.
- **The layout engine** (`PanelLayout`, `PanelLayoutReader`, `PanelLoader`, `PanelContent`, `PanelBindings`) — layers and bindings, and nothing else. Raster, vector and text layers; `rotate`, `reveal`, `style` and `translate` driven live off `ModuleData`; theme tokens resolved **in the frame being drawn**, never flattened at install. Raster decoding halves on `OutOfMemoryError` rather than refusing (§2's degrade-don't-cap rule).
- **The layout reader never throws.** Every field is read from the JSON tree by hand rather than decoded into data classes, so one mistyped value costs the author that value and not the panel. Decoding into `@Serializable` classes would have been tidier code and the wrong behaviour: `"opacity": "loud"` would blank the whole panel, and an unknown layer `type` would throw where the browser rule says skip.
- **No layout, no panel** (§6, ruled 2026-08-12, previously unbuilt) — with no installed module able to fill the selected slot the panel is **absent from the screen arithmetic**, not merely skipped at draw time, so the settings blind reclaims the band. This supersedes 1.6.2's empty-box tenancy: reserving a strip of screen to display nothing is DASH taking space it has no content for.
- **The Tank Gauge got real bindings** — `rotate` on `gauge#needle` about a pivot given as a fraction of *the element's* box, `reveal` on the bar, `translate` on the arrow head tracking it, a static `style` delegating the logo to `@accentColourPrimary`, a ranged `style` pulsing the lamp past 9 bar, and a text layer with one decimal and a ` bar` suffix that goes red at the same threshold.
- **Two more transports, same panel.** `GaugeBt` (Bluetooth SPP) and `GaugeUsb` (USB serial) join `GaugeWifi`, all three reading **one shared `assets/` folder** so their payloads are byte-identical — the CRCs match across all three builds, which is what makes it a fair test rather than three similar tests.

**What it proved:** A layout is installed once and read from disk from then on; nothing in a layout names a transport and nothing in the render path knows one exists. The panel being indistinguishable across WiFi, Bluetooth and USB is that separation holding up under test rather than being asserted in a document. It also means the `DashAccessory` helper due at 1.6.10 has had its draft written three times against three transports, which is good evidence about what belongs in the library.

**Regressions:**

- **None shipped.** One was introduced and removed during the session — see the progress-bar note under Outstanding.

**Fixes:**

- **`svg-subset.json` was never in the APK.** The Gradle task that copies it existed only on the spike branch and was never merged, so `SvgSubset.load()` threw on every boot and **no vector layer could draw on any build of main**. The first run showed the raster and the text and nothing else.
- **And the failure was silent, which was the worse half.** The loader collected a perfectly good explanation and dropped it. Warnings now reach logcat, and the missing-subset case is reported **in DASH's own name** — an author told *"this is not an SVG DASH could read"* would go and stare at artwork that was fine.
- **The Bluetooth board name went stale** (a 1.4.12 defect). `DeviceConnection.label` was read once at connect and frozen for the life of the link, so a reflashed board kept its previous name — DASH reconnects within seconds of a board rebooting, which is exactly the window before Android has learned the new one. A restart cleared it, which made it look like a cache; it was a race with a permanent memory. The sweep already read the current name every pass for the `D.A.S.H` marker test and threw it away; it now writes it down.

**Outstanding:**

- **USB serial delivers large payloads unreliably — deferred to 1.6.10.** An 88 KB panel arrives corrupt roughly two installs in five, always `lengthOk=true crcOk=false`: the right number of bytes and the wrong content. The identical bytes cross WiFi and Bluetooth perfectly, and both have flow control and retransmission underneath where a bare UART has neither — with XON/XOFF ruled out for good, because a PNG contains `0x11` and `0x13` freely. **57600 baud was tried and put back**: one failure in four against two in five is the same coin, so the cause is neither throughput nor bit time. The remedy agreed is a **per-block retry**; see the roadmap. *The per-block CRC already exists and is what catches this every time — nothing corrupt has ever reached the renderer.*
- **The install progress bar advanced only at block boundaries** — a three-block payload gave three samples, and since the artwork is ~90% of it the bar sat at 1%, flicked to 90%, and finished. Over USB that is eight seconds of a motionless bar, which reads as a hung install. **Fixed in this version** (`Inbound.BlockProgress`, throttled to one report per 4 KB) but **not hardware-verified**: it is only visible on USB, USB is the transport that fails for the reasons above, and the bench board is currently carrying the 57600 build. It builds clean and is recorded here honestly rather than claimed.
- **`touch` bindings are parsed and recorded in `PanelLayout.controls` but do nothing.** That is 1.6.7 by design, along with the optimistic update and the timeout that keeps optimism honest (§8).
- **Panel warnings only reach logcat.** Their real home is the previewer, and later the Module Manager.
- **Night slots are unreachable.** The Ambient switch is a version 2 setting, so DASH resolves `*_day` for the whole 1.6.x era. Night artwork a module ships today is accepted, stored and never selected.
- **Bronze tier is still unmeasured.** This panel draws 22 elements at the Tab S9's full 120 Hz; nothing here says what an Android 7 device will do.
- **Number formatting is `Locale.ROOT`**, so a module sending `10.5` never prints `10,5`. Whether a module should be able to *ask* for a localised number is a question for the 1.6.10 lock, not a default that should be chosen by accident.

**Notes:**

- **A diagnosis was called too early and was wrong.** The progress-bar change was blamed for the USB corruption on two failures against one success, and reverted; with the code fully reverted the next install failed too, on a different asset. The corruption was there from the start and simply hid behind a first-attempt success. Recorded because the lesson is cheap here and expensive later: **an intermittent fault will confirm whatever you tested last.**
- **DASH now says *how* a block is corrupt, not just that it is.** It holds no reference copy to diff against, but the assembler always takes exactly the declared byte count — so if bytes were *lost*, the shortfall is made up from whatever arrived next and the payload's tail contains the start of the following message. Finding `BLOCK|` in the tail of a PNG is proof the stream slid; not finding it means bytes were altered in place. That distinction decides the remedy, and we had been guessing at it.
- **The order of the three transforms in the vector renderer is load-bearing**, because each belongs in a different coordinate space: a `translate` is a fraction of the layer and must act in artwork space, a `rotate` is about the element's own box and must act inside it, and the artwork's own group transform sits between them. Getting it wrong is quiet rather than loud — a needle inside a translated group turns about a point off in the artwork, which reads as a wrong pivot rather than a wrong space.
- **Bumping a module's firmware version is not bookkeeping.** DASH compares it against every `HELLO` (1.4.13), so a module whose layout has moved on while its version stands still keeps being drawn from the copy already on disk. The quarantine and its UPDATE button are what make an edited layout reach the panel.

---

## Version 1.6.5

**Status:** Complete — an ESP32 ACCESSORY module installed over WiFi and reporting. Hardware-verified by Roger, 2026-08-12.

**Scope:** The first module with a face. Every module before this one was invisible — a SYSTEM module publishes signals, a LISTENER consumes them, and neither puts anything on screen. This one ships the artwork DASH will draw, and gets it onto the tablet's disk intact.

**Implemented:**

- **`GaugeWifi`** (`arduino/current_sketches/GaugeWifi/`) — a **Tank Gauge** ACCESSORY on an ESP32 DevKitC, connecting to DASH as a TCP client on port 3274. Three blocks stream out of flash on `INSTALL`: `h_large_day` (the layout document), `dial.png` and `gauge.svg` (the artwork the layout refers to). Then SILENT until `ACTIVATE`, then `REPORT|id|tank_pressure|value` four times a second on a simulated sweep — no sensor, because this version is about the path rather than the plumbing.
- **The layout is the declaration** (`module-layout.md` §9) made real: the sketch's `onInstall()` is a `MANIFEST` and three blocks and nothing else. There is no separate variable or control declaration to write, because the layout already names everything.
- **`make_assets.py`** bakes `assets/` into a PROGMEM header with each **CRC32 computed at build time** (§8 permits it, and it saves reading every asset twice on a microcontroller). The files in `assets/` are the source of truth, so the artwork in the repository and the bytes on the board cannot drift apart.
- **Blocks are streamed, never held whole in RAM** — 512 bytes at a time out of PROGMEM, with partial writes pushed to completion rather than assumed. **Deliberately not `flush()`**: on the ESP32 core that drains the *receive* buffer and would quietly eat inbound DASH messages mid-install.
- **A hand-built ACCESSORY class in the sketch**, not in the library. `DashAccessory` stays deferred to 1.6.10 so that it is extracted from something proven on hardware rather than written from the specification — the same order 1.4.15 used for SYSTEM and LISTENER. This sketch is that draft.

**What it proved:** 87,262 bytes crossed from an ESP32's flash to the tablet's disk **byte-for-byte identical** (MD5 matched on both assets), every CRC32 passing, the record committed with `crcOk: true` throughout. The open question of whether a real board could ship panel-sized artwork over a real transport is answered: it can, and it is not slow.

**Regressions:**

- **None** — but one long-standing defect was found and fixed before it could bite, see below.

**Fixes:**

- **The 64 KB asset block limit** (`FrameAssembler.maxBlockBytes`) — a **stale guard contradicting a same-day ruling**. Its own comment deferred to an open item — *"the real asset-size caps are an open item; this is a safe default"* — that had been closed hours earlier the other way: `module-layout.md` §2 rules there are **no** asset caps, DASH advises and degrades instead. The first real ACCESSORY payload was 78 KB and would have tripped it.
- **And the failure mode was worse than the limit.** Over-size blocks fell through to `onInbound(Inbound.Line(line))`, so DASH never switched to counted reading and the PNG would have been shredded into nonsense lines at every `0x0A` byte in the compressed data — a baffling failure rather than an honest one. Now: the bound is an **8 MB sanity guard** against a corrupt header claiming gigabytes, and an over-size block is **read and discarded** so the stream stays framed, then reported as a new `FailReason.OVERSIZE` with a reason on the card. Whatever the number, exceeding it can no longer corrupt the stream.

**Outstanding:**

- **The panel still draws nothing.** The layout and artwork sit in `files/modules/0000DA58AC01/` unread — that wiring is 1.6.6, and this version deliberately stops at the disk.
- **`h_large_day` was stored as an ordinary asset, not recognised as a layout.** §9 says a block whose name is a slot name *is* a layout, but **the twelve slot names are not enumerated anywhere machine-readable** — only `h_large_day` exists, as an example in the prose. That list is the first job of 1.6.6.
- **`gradientTransform` is promised by the spec and not implemented** (carried from the same-day `module-layout.md` amendments). Not blocking here — nothing is drawn yet.
- **A block is still buffered whole before its CRC can be checked**, so the 8 MB bound is also a heap bound. Streaming a large block to disk as it arrives is the eventual answer and is not needed at the sizes real panels use.

**Notes:**

- **A cold WiFi radio looks exactly like a broken one.** The first connections to the tablet timed out at four seconds and then succeeded at fifteen; once warm, every attempt landed in under 0.1 s. Nothing was wrong — the tablet's radio was dozing and the first SYN was dropped. Worth remembering before diagnosing anything else on a wireless bench.
- **Opening a serial port resets an ESP32.** A run of reconnects and ACTIVE→SILENT→ACTIVE cycling looked like a link fault and was entirely self-inflicted by watching the board's own debug output; DTR pulses on open. `stty -hupcl` reads without resetting. The proof it was harmless came from DASH's own log: the board connected **once** and was never closed, while every churning connect/close carried the *laptop's* address.
- **The wire log tells you who is talking by address, not by assumption.** That one detail turned a suspected firmware bug into a five-second diagnosis.
- **Verified live on the development machine as well as on hardware** — Waydroid was installed this session, so DASH can now be seen running on the desktop, which is what made the day's earlier parser work checkable without a flash cycle. It cannot answer performance questions; it renders on the desktop's GPU.

---

## Version 1.6.4

**Status:** Complete — all three panel sizes, and the six slot ratios given real numbers. Hardware-verified by Roger, 2026-08-01.

**Scope:** The panel had one size. Now it has three, chosen and looked at on hardware, and the six layout slots that have been referred to since transport.md was written finally have values.

**Implemented:**

- **`PanelSize`** — SMALL / MEDIUM / LARGE, persisted in `ModulePanelConfig` (which was shaped at 1.6.3 to take it, so the field simply arrived). `largeThicknessFor(longEdge)` became `thicknessFor(size, longEdge)`; nothing else in the layout maths changed.
- **The six slot ratios**, Roger's call, recorded in whole numbers:

  | | Horizontal | Vertical |
  |---|---|---|
  | **Large** | **8 × 3** | **3 × 8** |
  | **Medium** | **16 × 3** | **3 × 16** |
  | **Small** | **16 × 1** | **1 × 16** |

  Long edge first, thickness second. **Every vertical slot is its horizontal twin stood on its end**, so there are three shapes to learn rather than six and DASH holds one pair of numbers per size.
- **Stored as integers, with the aspect derived.** `PanelSize.longUnits` / `thickUnits` are the published ratio; `aspect` is computed from them and never stored. The ratio a module author reads and the number the layout arithmetic uses are one fact and cannot drift apart. `horizontalRatio` / `verticalRatio` render the pair for display.
- **Layout › Module Panel became two sections — Size first, Position second** (Roger). Size is the larger decision, and the position tiles draw the panel at whatever size is chosen above them, so the page reads top to bottom as one continuous answer rather than two unrelated controls. Both rows use `SettingsSectionHeader` on the 1.5.15 design language.
- **Every tile is a picture of the real result.** Size tiles draw the panel on the edge it is *actually* on, so changing position updates them; position tiles draw it at the *selected size*. Glyph thickness comes from the same ratio the real panel uses, so the three sizes are honestly to scale against each other rather than three arbitrary bands.
- **`EdgeTile` became `PanelTile`**, since it now serves both rows.

**Regressions:**

- None found. Converting 4×1.5 / 4×0.75 / 4×0.25 to 8×3 / 16×3 / 16×1 is arithmetically identical, so nothing moved on screen.

**Documentation:**

- **arduino.md §11 — an open item closed.** That section has said since it was written that *"the actual ratio values for each of the six layout slots are not yet defined — this will be decided once real panels are being designed."* This is that decision, drafted in full: the table, how the numbers were arrived at, what they come to as screen shares, and the long-edge rule. **Still explicitly a draft** — nothing has yet been drawn *to* these shapes, and a spec is not locked before it has been built against. They lock at 1.6.10.
- **interface.md** — the same table, and the Panel Sizes section's "1× / 2× / 4× system bar height" marked superseded. Worth recording that the impression was right: small does land at roughly bar height on a wide screen. It simply could not survive being tied to a measurement the user can move.

**Notes:**

- **Whole numbers over fractions** (Roger). The sizes were sketched as 4×1.5, 4×0.75 and 4×0.25 — fine as working notation while picking them by eye, wrong for publication. A module author should not have to picture a shape described in quarters.
- **How the numbers were actually chosen.** By eye on the Tab S9 Ultra, not by reasoning. Large alone took four builds — 3:1, then 4×3 ("much bigger than I was expecting"), then 4×2, then 4×1.5 — with medium and small set against it once it looked right. This is the only honest way to choose a shape, and it is why the ratios could not have been drafted at 1.6.1 as originally planned.
- **A cheat in the glyphs, documented as one.** A true sixteenth of a 52dp glyph is thinner than a hairline, so Small is floored at 3dp in the tiles. The real panel is not floored.
- **Deliberately not shown on the tiles:** the ratio itself under each label. Real information a module author will need — a module is drawn for a specific slot — but 1.5.15's no-help-text-under-settings rule holds, and there are no modules to match against yet. `horizontalRatio` / `verticalRatio` exist for when it starts to matter.

**Outstanding:**

- **The ratios are provisional** until they lock into `module-sdk.md` at 1.6.10, once two real modules have been rendered through them.
- **Carried from 1.6.1:** transport.md still carries pre-1.4.x text — the retired HYBRID module type in three places, and the `SYSTEM_SIGNALS:` / `ICON:` colon syntax that `MANIFEST` and `BLOCK` replaced. The §11 layout-format draft is now partly discharged (the ratios), but the **wire grammar** — how a module actually declares a layout — is still undrafted and still best sited just ahead of 1.6.5.
- **Carried from 1.6.2:** `MainActivity.openSetDefaultLauncher()` is unused since the banner went — a candidate for the 1.6.11 cleanup.

---

## Version 1.6.3

**Status:** Complete — the panel docks to all four edges and has its own settings tab. Hardware-verified by Roger, 2026-07-30.

**Scope:** Give the panel somewhere to go and the user a way to send it there. All four edges, a persisted config, the Layout › Module Panel tab, and the collision rule from 1.6.2 given real teeth now that the user can actually express a preference.

**Implemented:**

- **`ModulePanelModel.kt`** — `PanelEdge` (TOP / BOTTOM / LEFT / RIGHT) and `ModulePanelConfig`, persisted through `DashPreferences.modulePanelConfig`. Shaped deliberately to grow: panel size arrives at 1.6.4 and persistent/floating mode at 1.6.9, and both belong in this record rather than as loose preference keys.
- **The long edge is the docked edge minus what the bar has taken from it** (Roger). Docked top or bottom the bar sits opposite and takes nothing, so the panel runs full screen width. Docked left or right the bar spans the full width and eats the vertical run, so the panel runs **screen height less bar height** and butts against the far end, clearing the bar rather than running under it. Changing the bar height therefore resizes a vertical panel — correct, and now explicit.
- **The vertical slot is the horizontal one stood on its end — 1.5 × 4.** One constant serves both once the ratio is expressed against the *long* edge rather than against width, which is why `largeHeightFor` became `largeThicknessFor(longEdge)`. Vertical is the shape that suits a landscape screen, where the horizontal slot is deepest; the two together give a usable option in either orientation for the first time.
- **Preference and effective edge separated.** `effectiveEdge(preferred, bar)` resolves where the panel actually draws. A collision displaces it to the opposite edge for as long as the collision lasts, and **the stored preference is never rewritten** — move the bar away and the panel returns to the user's choice on its own. Storing the displacement would let DASH quietly overwrite a setting and never give it back.
- **Layout › Module Panel is live**, ending the WIP placeholder open since 1.5.2. Four tiles built on the Rotation tab's glyph precedent: the device at its true proportions, the user's own bar on the edge it actually occupies, and the panel drawn at roughly its real share of the screen on the edge that would result.
- **The bar's edge cannot be newly chosen** (Roger). Its tile is greyed to a third opacity, unclickable, and labelled with the reason rather than left silently dead; its glyph drops the panel and shows the bar alone. All four tiles stay in place so the row does not reshuffle when the bar moves.
- **`MODULE_PANEL_MOVE` transition.** An edge change moves the panel in two dimensions *and* resizes it, so placement moved from `Alignment` to four animated values offset from the top-left — an Alignment switch cannot express that motion at all. The self-growing transition registry picked it up with no settings rework, giving it a user-controllable speed like every other DASH motion.

**Regressions:**

- None found.

**Notes:**

- **A deliberate exception to the no-dead-controls principle** recorded at Layout › Rotation in 1.5.15 ("picking a fixed orientation turns Auto off by implication rather than greying the others out, so there is never a dead control on screen"). The distinction: Rotation's options were all genuinely valid, so greying would have obstructed a real choice. Here the edge is genuinely occupied by the bar, and a disabled control is the honest state.
- **Greying prevents only *new* selection.** The displacement logic is still needed and still reachable — choose an edge, then move the bar onto it. The tile then reads as both selected and unavailable, which says the user's choice is being held rather than lost. Both mechanisms are live and they are not alternatives to each other.
- **Settings insets went from two edges to four.** The blind previously padded top and bottom only; a left- or right-docked panel needs start/end insets too, or settings covers it. Vertical insets stay clamped to the window for the reason recorded in 1.6.2.

**Outstanding:**

- **Both ratios are provisional** until the slot set locks into `module-sdk.md` at 1.6.10.
- **Carried from 1.6.1:** the ACCESSORY panel/layout format (arduino.md §11) is still undrafted, and transport.md still carries pre-1.4.x text — the retired HYBRID module type in three places, and the `SYSTEM_SIGNALS:` / `ICON:` colon syntax that `MANIFEST` and `BLOCK` replaced. Best sited just ahead of 1.6.5, where a real module gives the draft something to be written against.
- **Carried from 1.6.2:** `MainActivity.openSetDefaultLauncher()` is unused since the banner went — a candidate for the 1.6.11 cleanup.

---

## Version 1.6.2

**Status:** Complete — the module panel is on screen. Hardware-verified by Roger on the Galaxy Tab S9 Ultra, 2026-07-30.

**Scope:** The first version of the Module Panel era to put anything on screen. DASH's defining surface — the box an ACCESSORY module will own — drawn for the first time, at the large slot only, one panel, persistent, with nothing inside it. Docking is 1.6.3, the other sizes 1.6.4, a real module 1.6.5.

**Implemented:**

- **`ModulePanel.kt`** — the container. A plain box filled with `backgroundColourPrimary`: no border, no corner radius, no content. **DASH draws the container and stops at its boundary.** The fill is a default to be drawn over, not a designed frame — when a module owns the box it is never seen. With nothing installed there is no king in the castle, so DASH occupying the empty box is its one permitted tenancy, and it ends the moment a module claims the space.
- **Shape, not size.** The panel is a fixed aspect ratio rather than a measurement — the property that lets a module drawn once render correctly on a phone, a tablet and a head unit alike. The long edge is the screen edge it docks to; thickness follows from the ratio. The large slot is **4 × 1.5** (`ModulePanelSpec.LARGE_ASPECT`), the first of the twelve layout slots to be given a real number.
- **The panel and the system bar never share an edge** (Roger). Where interface.md previously had the panel beginning where the bar ends, the two now cannot meet at all. Move the bar onto the panel's edge and **DASH moves the panel out of the user's way** rather than leaving them to resolve it. At 1.6.2, with docking a version away and two live bar positions, this resolves to *the panel takes the edge opposite the bar*.
- **Settings can never cover the panel.** The blind insets around it on both edges — bar on one, panel on the other — and rolls out into the band between them. The conform rule that the 1.5.2 placeholder existed to demonstrate is now enforced against a real surface.
- **Hidden during bar edit mode**, carrying forward the placeholder's rule: edit mode is a focused workspace holding nothing but the bar, its ruler and Save/Cancel.

**Removed:**

- **`ModulePanelPlaceholder.kt`** (104 lines) — the throwaway dark strip with its EXPAND/MINIMISE toggle, open since 1.5.2 for one purpose: giving the settings shell a real foreign surface to conform to while the real panel was still versions away. Deleted exactly as planned, along with its two height constants, three imports and the `modulePanelExpanded` state in `MainScreen`.
- **The not-default-launcher banner** (Roger's call — "if it is deleted, I shall not miss it"). A purple full-width strip anchored opposite the bar, it wanted precisely the edge the module panel now takes. Verified before deleting that nobody is stranded: **System › Android Settings Links** already offers Android's own home-settings and default-apps screens, which is where a "make me the launcher" action belongs. Its orphaned `isDefaultLauncher` state, the `ON_RESUME` refresh of it, and two now-unused imports went with it.

**Regressions:**

- None found. The only behaviour change is intentional: with the placeholder gone the settings blind briefly ran full height to the bar, restored the moment the real panel landed.

**Notes:**

- **How the ratio was chosen.** Four attempts on real hardware, not reasoning: 3:1 (Roger's first sketch, "height should be ⅓ the width"), then 4×3 — "much bigger than I was expecting" — then 4×2, then **4×1.5**. Held as one constant precisely so it stayed cheap to iterate. The `4f / 1.5f` form is kept unreduced in the code rather than collapsed to `2.667f`, because the two numbers are how the shape is pictured and how a module author will read it.
- **A panel's height share of the screen is screen aspect ÷ panel aspect.** So a full-width panel is shallow on a portrait screen and deep on a landscape one — 4×1.5 is 23% of the Tab S9 Ultra portrait and 60% of it landscape. **This is not a defect.** A head unit does not rotate: a user picks the slot that suits the one orientation their screen is mounted in, which is what having twelve is *for*. It only reads as a problem on a dev tablet that spins.
- **The twelve slots are a menu for the user, not a workload for the author.** Recorded because it was initially read backwards during 1.6.1 discussion. An author supports the shapes they choose; the user picks what fits their car. Ratios are designed from real cases starting with Roger's own, which is why 4×1.5 exists before the other eleven.
- **Defensive clamp.** A fixed-ratio panel derives its height from screen *width*, so on a wide landscape screen it can ask for more height than the screen has. The settings inset arithmetic is clamped to the window so the blind is never handed negative space. This guards the maths only — the panel still draws at its true ratio, so an overflowing shape stays visibly wrong rather than being silently trimmed.

**Outstanding:**

- **The ratio is provisional.** The remaining eleven slots are designed at 1.6.4; the set locks into `module-sdk.md` at 1.6.10, once two real modules have proven the format.
- **`MainActivity.openSetDefaultLauncher()` is now unused** — the banner was its only caller. Left in place rather than swept mid-version; a candidate for the 1.6.11 cleanup, or for reuse if a launcher prompt finds a better home.
- **Carried from 1.6.1:** the ACCESSORY panel/layout format (arduino.md §11) is still undrafted, and transport.md still carries pre-1.4.x text — the retired HYBRID module type in three places, and the `SYSTEM_SIGNALS:` / `ICON:` colon declaration syntax that `MANIFEST` and `BLOCK` replaced. Neither blocks the container work: 1.6.2–1.6.4 build the walls, and §11 governs what fills them. The draft is best sited just ahead of 1.6.5, where a real module gives it something to be drafted against.

---

## Version 1.5.15

**Status:** Complete — the legacy panel gone, and a design language established and applied to every settings page. Hardware-verified by Roger throughout on the Galaxy Tab S9 Ultra and the Pixel 8 Pro. 2026-07-27 → 2026-07-29.

**Scope:** The roadmap asked for "cleanup & polish: remove the old flat settings scaffold, confirm every 1.1.x–1.4.x feature has a home, confirm every WIP placeholder is honestly labelled." That took an afternoon. What it turned into is the version where **DASH stopped being a collection of settings pages and became one system** — because with the legacy panel gone, Roger went through every page in order and the same problems kept surfacing: no two pages agreed on spacing, on type size, on where a control lived or what it looked like. Each was individually defensible and collectively incoherent.

### Part one — the cleanup the roadmap asked for

- **Layout › Rotation** — a new tab, first in Layout, rehoming the legacy panel's last live control. **Its glyph is a miniature of the user's real layout**: the screen at its true aspect ratio with *their* system bar on the edge it will actually occupy, read live from `SystemBarConfig`. A plain rectangle cannot show a 180° rotation — portrait and portrait-reversed are the same shape — so the reversed options put that bar on the opposite edge, which is exactly what will happen. Move the bar to the top in Layout › System Bar and every tile follows. All four fixed orientations are offered, up from two.
- **Auto became `FULL_SENSOR`.** Plain `SENSOR` refuses to rotate into reverse portrait on most devices, so with four fixed orientations on offer Auto could not reach one of them — a tab that will *hold* an orientation but never rotate into it contradicts itself.
- **The legacy settings panel is deleted.** `SettingsPanel.kt`, both `LEGACY SETTINGS →` nav rows, the `onOpenLegacy` parameter threaded through three levels, and `MainScreen`'s `showLegacySettings` state. That bridge had been open since 1.5.2 — thirteen versions of rehoming, ending here.
- **The audit came back clean.** Every persisted preference was checked against the tree; all have a home.
- **The `DiagnosticOverlay` is gone** — four lines of grey 10sp pinned over the top-left of the user's home screen since 1.1.x, when reading those numbers *was* the job. It never got a gate. About DASH's device report supersedes it.
- **Dead code swept:** the whole `dashScale` token (pref, key, writer, `LocalDashScale`, `DASH_SCALE_*` bounds) — superseded at 1.5.3 and kept alive only by the overlay printing its value; `TransportManager.sendTo`, built for the SEND TO selector 1.5.13 dropped; `DensityManager.formatDpi`; `TransitionId.hint` and its seven strings; `SettingToggle`. **A comment was left where each went**, so the next person to wonder "was there a way to send to one board?" finds the answer rather than silence.

### Part two — the design language

Seven shared pieces in `ui/common/`, each replacing numbers or components that had been copied around:

- **`Typography.kt`** — five tiers with matching line heights: **HEADING 28 · SUBHEADING 22 · MAINBODY 18 · BODY 14 · TINY 11**. There had been **seventeen distinct font sizes across ninety-eight call sites**, including 12, 12.5, 13 and 13.5 all at once. Nobody can see half a point; those were the residue of iterating one screen at a time. The tiers are `sp` and never `dp`, because DASH overrides `fontScale` at the composition root — a size in `dp` would silently opt out of the user's text-size control.
- **`Spacing.kt`** — `PANEL_GAP` (all four panel margins, formerly 24 top / 28 bottom / 16 at the edges), `BOX_PAD`, `TREE_GUTTER`, `NAV_ROW_INSET`, `SETTING_SPACING` (formerly 34 / 28 / 28 / ad-hoc 24 depending on the page), `SECTION_HEADER_GAP` and `CONTROL_WIDTH`.
- **`DashButton`** promoted out of `ModulesContent`, and **rebuilt on the same container as the segments and steppers** — an 8% wash inside a hairline border on an 11dp radius. It had carried its own shape, a 0.5-alpha border and a 10dp radius, which is why it read as a different family sitting beside them.
- **`DashStatus`** — one status palette, ok / wait / fail / alert / idle / info. Three hex values had been defined twice in two files.
- **`HeadingRule`** — the art-deco rule, shared so the tree's heading and the box's headings draw the same one.

**The rules, now recorded in the code rather than in anyone's head:**

1. **A page is titled once**, with the rule. Sections sit a rank below as `SettingsSectionHeader` — **no rule**, because the rule marks the top of a page and spending it on every section makes the hierarchy invisible.
2. **No help text under settings.** `SettingsContentHeader` lost its `description` **parameter**, not just its arguments — emptying the call sites would have left it one careless tab away from returning.
3. **Every clickable control sits on the right, at one shared width, and grows downwards.** Labels wrap; nothing truncates, because an action you cannot read is worse than a tall box.
4. **The one exception** is a control unusable at that width — Transitions' six speeds would get 31dp a cell. Those stack via `fullWidthControl`, which is now documented as an escape hatch and nothing else.

### Part three — the page-by-page pass

- **Size & Scale** — titled once (it had two page-rank headings and nothing said what the page was about); DASH Scale and Android became sections; "App density" became **Viewport App Density**; the stepper readout scales with the text (a fixed 66dp box wrapped "72 dp" onto two lines at 1.4×).
- **Transitions** — the master control renamed to *Every transition at once*, since "Master pace" under a heading reading "Master pace" says the word twice with nothing explaining it.
- **Splash Screen** — the custom colour picker collapsed behind a **Custom swatch** filled with the colour in force (it had sat ~200dp tall permanently); a full **`ColourEditor`** with hex, R/G/B and H/S/V, all live-synced, built in `ColourPicker.kt` because v2's theming wants exactly this; the chooser moved **into the preview** under the orientation selector; and a **None** type that genuinely skips the splash rather than showing an empty one for zero milliseconds.
- **System Bar** — three separate Columns merged into one flow, which is why its gaps had been uneven.
- **Location** — the permission **toggle replaced by a DASH selector reading Off | On, or Settings | On when granted**. A toggle promises it can be flipped both ways and this one cannot: DASH can *ask* for the permission, only Android can revoke it. The left cell now says what the tap will actually do. `SettingToggle` died with it.
- **Android Settings Links** — fifteen links became full-width left-aligned boxes, using `DashButton`'s new `alignStart` rather than a lookalike. **Deliberately exempt from the controls-on-the-right rule**: 1.5.14 settled that the row *is* the control, and fifteen boxes reading "Open" would undo that.
- **Power** — "Exit DASH" became **Return to homescreen**; the launcher warning moved from help text into the **tag**, because deleting it would leave a control that appears to do nothing.
- **Modules** — audited rather than restyled. About DASH and Licence left alone at Roger's request.

### Part four — the text scale

**0.8–1.4 became 0.4–2.0.** DASH runs on everything from a phone to a head unit read across a cabin, and it is not DASH's place to decide someone wants a narrower range than that.

**Regressions:**

- **The type sweep mis-ranked things, three times, and each was found by Roger looking at a page.** The automated mapping put anything 15–23sp into `SUBHEADING`, which quietly promoted **the page title** (so titles and section headings rendered the same size, undercutting the hierarchy that had just been asked for), **module and pipe card names** (every card shouting at section volume), and **the Serial Monitor's column headers** (labels a tier *above* the data they describe). The lesson: a value-based mapping cannot see a thing's *rank*, and rank is the whole point of a type scale.
- **Controls vanished their own content below 0.5× text scale.** `CONTROL_WIDTH` scaled linearly in both directions, but a stepper's − and + are fixed 38dp touch targets: at 0.5× a 95dp control held 76dp of buttons and squeezed the readout out. Now `controlWidth(fontScale)` clamps the scale at 1.0 — **controls grow with the text but never shrink below their base width**, because smaller text does not need a narrower control and a touch target does not shrink at all.
- **`fillsBox` tabs had no margins.** About and Licence claimed the box for themselves and were handed it bare. About went back to an ordinary tab; Licence keeps `fillsBox` (its full-text `LazyColumn` needs a finite height) and applies `BOX_PAD` itself.
- **Heading rules did not line up** because the tree's heading is 13sp and a box's is 28sp, so the taller one pushed its rule further down. Both now share `HEADING_LINE`.
- Roger asked to right-align the tree, then reverted it the same session. `HeadingRule`'s `mirrored` flag went with it.

**Outstanding:**

- **`LinkButton` survives in six files** but is being displaced by `DashButton`. About and Licence keep it, and those two pages were deliberately left as they are.
- **`DashButton`'s filled variant loses its border**, so Module Manager's coloured actions differ slightly in construction from the neutral ones.
- **The 0.1 step across a 0.4–2.0 range is sixteen taps end to end.**
- Some removed help carried facts that now appear nowhere: that Bluetooth pairing is Android's job, that a link opens the exact page rather than the top of the menu, and what "use device location" being *off* actually falls back to.
- `TINY` at 11sp is DASH's floor; below it the guidance is to cut words rather than shrink further.

**Notes:**

- **The idiom completed itself.** 1.5.13 gave *the readout is the control*; 1.5.14 gave *the item is the control*; 1.5.15 gives **the page is one system** — a control's size, position, container and type all come from one place, and a page that wants to differ has to say why.
- **Deleting the parameter, not just the argument.** Twice — `SettingsContentHeader`'s description and `TransitionId.hint` — the durable fix was removing the *ability* rather than the current usage. A rule that lives in a type cannot be forgotten by whoever writes the next tab.
- The wireless-debugging pairing to the tablet broke when it changed networks; a full 65535-port scan found the one open port, and re-pairing restored mDNS discovery too. The hotspot does not isolate its clients.

---

## Version 1.5.14

**Status:** Complete — System › Android Settings Links, About DASH and Licence built; a new **Power** category opened; two more controls rehomed out of the legacy panel. Hardware-verified by Roger on the Galaxy Tab S9 Ultra and installed on the Pixel 8 Pro. 2026-07-27.

**Scope:** The roadmap's 1.5.14 was "Android deep-links + About DASH (version, licence)". It grew three ways during the build, each on Roger's call: **About and Licence split into two tabs** (About answers *who and where*; the licence is a legal text with enough bulk to bury it), the **device report** joined About, and a **Power** category was opened to rehome EXIT DASH — which then took restart/shut down with it.

**Implemented:**
- **System › Android Settings Links** — fourteen links across four sections (Connections / Display and sound / Device / DASH on this device). DASH is the home screen, so Android's own Settings app is installed, running and unreachable; without this tab a dropped WiFi connection cannot be fixed from inside DASH. **DASH links out and does not reimplement** — changing a network or pairing a device needs privileges a Bronze sideload has not got, and rebuilding Android's screens would be a worse copy of something already on the device.
- **Every row is capability-detected**, and three carry fallback actions (Mobile network tries roaming then wireless; Apps tries two forms; Default home app tries `ACTION_HOME_SETTINGS` then the default-apps page). A link nothing can handle is **not listed**, and a section left empty by that disappears. All fourteen probed present on the Tab S9 Ultra via `cmd package resolve-activity`; a bare board image is where the detection earns its keep.
- **System › About DASH** — identity (wordmark, version and `BUILD_DATE` from `BuildConfig`), the author, links, and the device report.
- **System › Licence** — the GPL-3.0 §5(d) notice, a plain-language section on what the licence means for a user, and nine open-source components with their licences. **VIEW FULL LICENCE** swaps the tab for the full text, read from an asset the build copies from the repo root, so what DASH shows and what the project ships cannot drift.
- **The device report** — twelve lines of facts and capabilities (version, Android, model, board, screen in dp and px, both font scales, default-launcher, density control, USB host / Bluetooth / WiFi), with **COPY REPORT** putting it on the clipboard as aligned plain text. Facts and capabilities, never identity; nothing on it needs a permission to read.
- **System › Power**, last under System, holding **Exit DASH** — tap-to-confirm on the 1.5.7 Reset idiom. Named *Power* rather than *Exit* because on Silver/Gold it holds restart and shut down too. *(Built first as a **top-level Power category** and moved into System the same day, 2026-07-27, on Roger's call: a surface that restarts and shuts down the **device** is a system concern, not a peer of Appearance and Layout. `ExitContent` became `PowerContent` with it. The original top-level form shipped in commit `a50b915` and never reached hardware in Roger's hands as a category; recorded here rather than erased.)*
- **Restart / Shut down device**, privileged-only. `REBOOT` and `SHUTDOWN` are `signature|privileged`: granted to a system app, never grantable to a sideload, and with no public intent for either — and root is forbidden by the No-Root Constraint. So on Bronze the whole **Device power** section is **absent**, not disabled and not erroring. Declaring the permissions is harmless where they are refused; `dumpsys` confirms both requested and neither granted on the Tab S9 Ultra and the Pixel 8 Pro.
- **`qrencode`-generated QR assets** (618 and 641 bytes) rather than a QR library — the URLs are constants known at build time, so a runtime encoder would have bought nothing and added a tenth entry to the licence page being built in the same version. Both decoded back to confirm they resolve correctly.
- **New scaffold components:** `QrPanel` (white matte, `FilterQuality.None`), `LinkRow`, `InfoRows` and a shared `Chevron`.
- **`buildConfig = true`** and a `BUILD_DATE` field — a sideloaded head unit has no store listing to read a date from.

**The two rehomes:**
- **`CHANGE LAUNCHER →` → System › Android Settings Links, as "Default home app."** It was always a deep link to `ACTION_HOME_SETTINGS`; it now sits with the rest of them and carries an explanation.
- **`EXIT DASH` → Power › Exit DASH**, with its `onExit` plumbing (and `MainScreen`'s now-unused `DensityManager`) removed. The exit itself moved to `MainActivity.exitDash()`, so one implementation serves any caller. **The tab is honest about doing nothing useful when DASH is the launcher**: a home app that finishes is relaunched immediately, so the help text says so and points at the tab that can change it, rather than leaving a control that appears broken.
- `ROTATION` is now the only thing stranded in the legacy panel. It waits for 1.5.16.

**The density probe fix (unplanned, and the significant one):**
- `DensityManager.checkCapability()` probed by calling `setForcedDisplayDensityForUser(DENSITY_DEVICE_STABLE)` — a no-op only on a device sitting at stock density. On **Silver/Gold, where the call succeeds**, probing a device whose user had set a DASH density would have **reset their screen**. It was safe only because the density tab was its single caller; About asking the same question in passing would have exposed it.
- Now it passes the **current** density, which is a true no-op at every tier, and `DashApplication.densityCapable` asks once for the life of the process.
- **Restart and shut down deliberately probe differently** — they check the *permission grant* rather than attempting the action. Density had no choice, since there is no way to ask. Here there is, and attempting a reboot to learn whether you may reboot is not a probe, it is a reboot.

**Regressions:**
- **Three, all caught by Roger driving it on the tablet, none surviving the session.**
- **No margins on About or Licence.** `fillsBox = true` hands a tab the bare box on purpose (1.5.8) — right for Module Manager, which pins its own controls, wrong for two tabs that only read. About went back to an ordinary tab and takes the shell's 28dp and scroll; Licence must keep `fillsBox`, because its full-text `LazyColumn` needs a finite height to measure against, so it applies the same 28dp itself.
- **The report's longer labels wrapped onto second lines.** `InfoRow`'s label column was a fixed 140dp. Replaced by `InfoRows`, which takes the whole block and measures the widest label at the size it is actually drawn — **the 1.5.13 lesson, repeated**: DASH lets the user change its text size, so any constant wraps for somebody. A single row cannot do this; it has no way to know how wide its siblings' labels are.
- **The About links were not clickable.** `resolveActivity` returned null for the browser intent, so `onOpen` was null and the rows were inert — with Chrome installed and visible in `cmd package query-activities`. The cause was **Android 11 package visibility**: an app targeting SDK 30+ cannot see which apps handle an intent without declaring the query, and DASH declared none. A narrow `<queries>` for `VIEW`/`https` fixed it. **The capability check was right; the answer Android gave it was wrong** — and the settings links were never affected, because the platform's own settings app is visible to everyone, which is exactly what made it look like a bug in About alone.
- Two compile-time only: `Image`'s `Painter` overload has no `filterQuality` parameter (switched to the `ImageBitmap` overload), and `java.time` does not resolve inside a Gradle `android { }` block without a script-level import.

**Outstanding:**
- **Restart and shut down cannot be verified on any hardware DASH currently runs on.** They are correct by construction and invisible on Bronze; first real test is the Orange Pi.
- **Donations are deliberately absent** — no card, no placeholder, no dead constant. GitHub Sponsors is not enabled on the account, and enrolment cannot be automated. Lands as its own piece just before v1 sign-off.
- The open-source component list is **hand-maintained** — update it whenever `gradle/libs.versions.toml` changes.
- `ROTATION` still in the legacy panel; the whole panel dies at 1.5.16.
- **Still no `MaterialTheme`** (carried from 1.5.13): `LinearProgressIndicator`, the settings gear `Icon` and the legacy panel's `Button`s remain unthemed. Most of it dies with the panel.
- **The settings tree gained a subcategory (System › Power)**, which interface.md's Settings Panel section may want a dated addendum for. Not edited — that is a Bible decision, not a build one. *(Smaller than it was: the top-level category this started as would have changed the shape of the tree itself; a fifth sub under System does not.)*

**Notes:**
- **The item is the control.** Roger's call on both surfaces: no OPEN button at the end of a row that has exactly one thing it can do — the row *is* the control, full width of the box. It reads better and it suits the car, where a short word at the far edge is a poor target on a moving screen and a full-width row is a reliable one. The natural successor to 1.5.13's *the readout is the control*.
- **The QR code is the primary affordance; the tap is the convenience.** A head unit may have no browser, no keyboard, and a user stood beside the car holding the phone that should receive the link. So the code is always drawn and the tap is offered only where something can honour it.
- **The Licence tab is an obligation, not a nicety.** GPL-3.0 §5(d) requires an interactive program to display Appropriate Legal Notices, and DASH displayed none anywhere before this version. The Apache 2.0 components it is built on require their attribution to travel with the binary, and it was not travelling. `usb-serial-for-android` was confirmed **MIT** by reading its POM rather than trusting memory.
- `qrencode -s 12 -m 2 -l M` generated the assets; the command is recorded beside them so they are reproducible.

---

## Version 1.5.13

**Status:** Complete — instrument polish across the two new tabs, out of driving them on real hardware. 2026-07-27.

**Scope:** This number was **cut** earlier the same day: every part of the planned 1.5.13 had already been rehomed or dropped by 1.5.10 and 1.5.12. It was then **reclaimed the same day** rather than skipping to 1.5.14, which is spoken for — the sequence stays continuous and the roadmap records both the cut and the reclaim.

**Implemented:**
- **`DashMenu`** — a DASH dropdown built on `Popup` from compose-ui, in `SettingsScaffold.kt` beside `LinkButton` so any tab gets it. Replaces Material's `DropdownMenu` in all six places on the Serial Monitor. Rows at 13.5sp rather than Material's 12sp, the current selection marked, capped at 300dp with scrolling.
- **A position provider (`BelowAnchor`)** so the menu drops *below* its anchor, nudges left when it would overflow the right edge, and flips above only when there genuinely is not room below.
- **Column widths are measured, not constants.** Each column takes the greater of its own 13sp header-plus-chevron and a representative 12sp sample, and re-measures when the font, the display density or the text scale changes.
- **The Serial Monitor's line buffer is the user's** — 50 · 200 · 500 · 1000 · 5000, default 500, persisted as `serial_buffer_lines`. The count line is the control. Shrinking trims immediately rather than only capping future growth.
- **`TRANSPORT` reads `Bluetooth` / `WiFi` / `USB`** instead of the internal tags, in the column and in its filter. An unknown transport falls back to its own tag uppercased.
- **Top bar reworked:** PAUSE green, CLEAR red, COMMANDS in the text colour, actions grouped left with an 18dp gap, readout moved right. `LinkButton` gained an optional colour for actions that carry a meaning of their own.
- **The send box returned as a COMMANDS drawer** — opens on request, closes on send or on pressing COMMANDS again, and pushes the grid down rather than floating over it.
- **The settings content box aligns with the heading** rather than the top of the tree, gaining roughly 60dp it was missing. The breadcrumb now heads the *tree column* in the wide layout; the narrow layout is untouched, so `Breadcrumb` takes a modifier with its old padding as the default.
- **The Signal Monitor's count toggles live-only**, with an honest empty state for the case where nothing has been heard.

**Removed:**
- **`SEND TO`**, then **the send box itself**, then the send box came back behind COMMANDS. The selector did not return: DASH messages carry their own addressing in field 1 of the grammar, so a line finds the right module down whichever pipe it travels — targeting at the transport layer duplicated that a layer down. `TransportManager.sendTo` is untouched and has no caller.
- **The Serial Monitor's per-pipe status lights.** Transport Manager owns "what is connected and is it healthy"; two tabs answering the same question is two tabs that can disagree.

**Regressions:**
- **Two, both mine, both caught immediately by Roger on the device.** The first `DashMenu` filled the screen: its rows called `fillMaxWidth()` inside a `Popup`, and a Popup is handed the whole window as its maximum. `IntrinsicSize.Max` — the normal answer — cannot be measured through a scrolling container, and the menu needs to scroll, so the fix was to measure the widest label directly. The second dropped *upwards*: `Popup`'s `alignment` positions the popup **within** the anchor's bounds, so `BottomStart` aligns its bottom edge with the anchor's bottom. Only a position provider can offset by the anchor's height.
- `LinkButton` gaining a colour parameter broke two positional call sites in `TransportManagerContent`, which passed `onClick` as the second argument. Compile-time, fixed at once.

**Outstanding:**
- **Still no `MaterialTheme`.** The Serial Monitor is clean, but `LinearProgressIndicator` (Module Manager), the settings gear `Icon`, and the legacy panel's `Button`/`TextButton` remain Material components with no theme to read. Most of that dies with the legacy panel at 1.5.16; the progress bar and the icon will want converting or bridging.
- Transport Manager's lights and Module Manager's chips are still the mid-tone set and look muted beside the Serial Monitor's brights.
- `ROTATION`, `CHANGE LAUNCHER →` and `EXIT DASH` are still stranded in the legacy panel.
- Grid cells remain single-line with ellipsis; tap-to-expand is unbuilt.

**Notes:**
- **The readout is the control** has become the idiom for these instruments: the line count opens the buffer menu, the signal count toggles live-only, and each column filter lives inside the header it filters. In each case the thing you are already reading is the thing you click.
- **Roger's naming rule, again:** DASH spells it **colour**. `Color` appears only where Compose's API forces it, and the audit confirmed every DASH-chosen identifier already follows the rule.
- The buffer options and default live in `DashPreferences.kt` as `SERIAL_BUFFER_OPTIONS` and `SERIAL_BUFFER_DEFAULT`. A higher ceiling is one list entry, for when there is hardware busy enough to justify it.

---

## Version 1.5.12

**Status:** Complete — Serial Monitor and Signal Monitor rehomed under Modules, the Serial Monitor rebuilt as a filterable grid, the Developer category confirmed dead, and the settings surface taken dark. 2026-07-27.

**Scope:** Planned as a migration of the two 1.4.x dev instruments into a **Developer** tab. Roger settled the question that had been tabled since 1.5.10 — **the Developer category is dead** — so they landed under **Modules**, beside the boards they watch. Nothing is behind a safety gate; every instrument is an ordinary tab, per CLAUDE.md's *no locked features, no hidden menus, no barriers between the user and full control*.

**Implemented:**
- **Modules › Serial Monitor** and **Modules › Signal Monitor**, both `fillsBox` tabs in the settings shell.
- **Rebuilt, not ported.** The old screens were near-black wells carrying their own `SERIAL MONITOR` headers and `CLOSE ✕` buttons — right for a standalone route, wrong for a tab where the nav names it and the shell frames it. Same decision 1.5.8 took for Module Manager.
- **The Serial Monitor is now a grid:** TIME · DIR · TRANSPORT · BOARD · MESSAGE · MODULE ID · PAYLOAD. Every line on the wire is already a structured record (`TYPE|id|…`), so rendering it as one string made the eye do the parsing.
- **A filter in each column header**, for DIR / TRANSPORT / BOARD / MESSAGE / MODULE ID. They combine, they filter the *view* only (nothing leaves the buffer), and a set column shows its value in full ink so a filtered grid is never mistaken for a quiet bus — helped by a "42 of 318 lines" count.
- **Filter options are built from what has been seen**, not from a hardcoded vocabulary, so a message type invented by a community module appears the first time it is sent. Same principle as the transport cards rendering exactly the pipes that exist.
- **Send box in the modern DASH idiom** — a token-bordered well with `BasicTextField`, replacing a Material `TextField` in navy and green. "All devices" became **"All boards"**, since that selector addresses a thing on a pipe.
- **Desk plumbing:** `TransportDesk` gained `status`/`wire`/`send`/`sendTo` (the Serial Monitor is a view onto that same transport layer, so it shares the desk); `ModuleDesk` gained `systemState` (modules are what fill the sourceless core).
- **Legacy panel cleanup:** both standalone full-screen routes and their `MainScreen` state deleted, the old `SerialMonitorScreen.kt` and `SignalMonitorScreen.kt` removed, and the stale **APP DENSITY** block — including "Open Display Size Settings →", a duplicate of the Appearance › Size & Scale deep-link since 1.5.3 — removed. Roger spotted that one.

**The theme change (the significant part):**
- `backgroundColourSecondary` **848482 → 2C2C2E**, and `backgroundColourPrimary` **E5E5EA → D2D2D7**, with `accentColourPrimary` **D1D1D6 → C2C2C7** following it.
- **Why.** The Serial Monitor's colour-coded message words were unreadable, and measurement showed why: against the mid-grey surface they ran **1.06–1.28:1**. The cause was not the palette but the *background* — mid-grey sits in the middle of the luminance range, so nothing can get far from it. **Even pure black reaches only 5.60:1 on it, and pure white 3.75:1.** No hue can be readable there, which is why three attempts to fix it with colour all failed.
- On `2C2C2E` the paired ink runs **11.10:1** (was 2.98:1) and the restored bright palette runs **4.7–8.1:1**.
- The primary surface was then eased down so the near-white system bar sat less harshly against the now-dark panel. **`accentColourPrimary` had to move with it:** the old accent was a subtly *darker* divider, and on `D2D2D7` it measured 1.01:1 — invisible, and inverting to lighter-than-surface if the background went further. Recorded in `DashTheme.kt` as a rule: **when a surface token moves, the accent paired with it must be re-checked.**

**Tried and reverted (kept for the record — three ways not to solve this):**
- **A `categoryColours` theme token** with colours hashed from the message word, so a community message type would colour itself. Designed in full, then **rejected by Roger**: a message's colour distinguishes a *kind of traffic*, it is not part of DASH's visual identity, and it should not shift when a user picks a preset.
- **A light grid well on `backgroundColourPrimary` plus a paired light/dark palette** selected automatically by `Color.luminance()`. Measured well (6.8–9.7:1) and looked wrong — reverted on sight.
- **A white glyph outline behind the coloured text** (`drawStyle = Stroke`), Roger's own suggestion, on the theory that an outline sidesteps contrast entirely by putting a hard edge between glyph and background. Reverted on sight — "ugly".
- The lesson is in the order: three attempts to work *around* a bad surface, then one change *to* the surface, which fixed it in one line.

**Regressions:**
- None found. The two instruments keep their behaviour; the transport stack was not touched.

**Outstanding:**
- **Material components ignore DASH theming entirely.** There is no `MaterialTheme` anywhere in the app, so `DropdownMenu` and `DropdownMenuItem` — the four filter dropdowns and the SEND TO selector — render in Material3's baseline scheme, and no token work on DASH's side reaches them. The same is true of the Material `Button`s left in the legacy panel. Fix is either a `MaterialTheme` mapped from the DASH tokens at the root, or DASH-native popups. **Not done, and it is visible.**
- **Status colours elsewhere are now inconsistent with the Serial Monitor.** Transport Manager's DATA/DASH lights and Module Manager's chips are still the mid-tone set (3DA35D / C98A2B / D9534F). They improved to 3.5–4.8:1 on the dark surface, so they are readable, but they read more muted than the Serial Monitor's brights. Worth lifting to match.
- **The legacy panel still holds ROTATION, CHANGE LAUNCHER → and EXIT DASH.** None has a home in the tree, so removing them would strand live features. Rotation arguably belongs in System; Exit and Change Launcher probably belong with About DASH at 1.5.14.
- Grid cells are single-line with ellipsis — a long `MANIFEST` payload is clipped rather than wrapped, because wrapping breaks the alignment that makes a grid scannable. Tap-to-expand is the obvious answer if the full text is ever needed.

**Notes:**
- **1.5.13 was cut as a consequence** — transport diagnostics are what Transport Manager *is*, the log viewer became Modules › Activity Log deferred to v2, and the safety gate went with the Developer category. Nothing was left to build. The number stays in place rather than renumbering; the sequence is a record, not a tidy list.
- Roger's naming correction, worth keeping: DASH spells it **colour**. `Color` appears only where Compose's API forces it.

---

## Version 1.5.11

**Status:** Complete — the transport stack now lives for the life of the process, not the life of a composable. Verified 2026-07-27.

**Scope:** Not a planned version. Found mid-bench while verifying 1.5.10, and inserted ahead of the remaining 1.5.x work because it is a **foundation** fix — every version after it would otherwise have been built on a bus that silently restarts itself.

**Implemented:**
- **`DashApplication`** — a new `Application` subclass owning `TransportManager` and `DashController`, constructed and started in `onCreate()`, registered via `android:name=".DashApplication"`.
- **`MainScreen` reaches them rather than creating them.** The `DisposableEffect` that started and — critically — *stopped* the stack is gone; this screen no longer has the power to kill the bus.
- Both are now given the **application context** rather than an activity context, which the previous arrangement was also quietly getting wrong for something outliving any one activity.

**The fault:**
- The bus was created with `remember { }` inside a composable, so its lifetime was tied to a *screen*. Any Android activity recreation tore the whole stack down and rebuilt it: every socket dropped, every module forced to reconnect, `lastSeen` wiped so the installed list fell back to DORMANT, and any install handshake in flight died mid-declaration.
- `MainActivity` handles `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden` itself but **not** `density`, `uiMode`, `fontScale` or `locale`. So a dark-mode switch at dusk, a font-scale change, DASH's own App Density feature, or the system reclaiming the activity while the user is in Maps each triggered a full, silent, unannounced restart of the module bus — in a car, mid-drive.

**How it was caught:**
- A duplicate `client c1` in the WiFi log. The client id comes from an `AtomicLong(1)` that only ever increments, so one process can never hand out `c1` twice — a second one means a second `TransportManager`, which means the stack had been rebuilt. Worth remembering as a diagnostic: a monotonic counter restarting is proof of an object's lifetime being wrong.

**Why the Application and not a ViewModel:**
- A `ViewModel` survives a configuration-change recreation but is cleared when the activity genuinely finishes — and DASH is a **launcher**, so the user opening an app in the viewport backgrounds it by design. The bus must outlive the screen. The process is the honest scope: one device, one bus, alive as long as DASH is.
- There is deliberately **no teardown path**, because the old `onDispose` is precisely what let a transient UI event kill the bus.

**Regressions:**
- None found. Verified by forcing two genuine activity recreations (`cmd uimode night yes` / `no`, since `uiMode` is not in `configChanges`): same PID throughout, no transport teardown, no client reconnect, no crash. Before the fix each recreation produced a fresh `TransportManager` and a reconnect.

**Outstanding:**
- Process death is still fatal to the bus — an `Application` lives exactly as long as the process. If real hardware ever shows the **process** being killed mid-drive, the next escalation is a foreground service. Deliberately not built on speculation; a launcher process is high-priority and rarely reclaimed.

**Notes:**
- The comment sitting above the old `remember { }` had claimed "Both live for the app's life" since 1.4.1. It was an accurate statement of *intent* and an inaccurate statement of *fact* for ten versions. A comment describing what code is supposed to do is not a substitute for the code doing it.

---

## Version 1.5.10

**Status:** Complete — Modules › Transport Manager. Hardware-verified by Roger on the Tab S9 Ultra with a WiFi Uno R4, a USB Uno R4 and a Bluetooth ESP32, 2026-07-27.

**Scope:** Planned as a generic transport *list* — enable/disable per transport, WiFi port/host, Bluetooth paired devices. On building it the list had nothing worth listing: **enable/disable** is the speculative user-intent layer 1.5.9 already cut, **port** is fixed at 3274 by transport.md, and **pairing** is Android's own screen DASH deep-links out to rather than reimplements. So the version became what a user actually needs from a transports screen: a **board-connection diagnostic** — help me connect a board, and tell me why one won't show up.

**Implemented:**
- **Modules › Transport Manager**, a card per pipe, reached through a `LocalTransportDesk` CompositionLocal (the same pattern 1.5.8 established for Module Manager — the stateful managers are *reached*, never rebuilt).
- **The card lists boards, not modules.** A board is a physical thing on a pipe and may host many modules, so this list and Module Manager's are different things by design.
- **Per-board DATA and DASH lights.** Green when good; amber when a board is connected but silent; red on DASH when a board is sending noise that is not DASH grammar. Amber vs red is deliberate — a silent board has not *failed* the DASH test, it has not sat it; a board pumping out gibberish has.
- **A width-gated explanation** beside the lights (`BoxWithConstraints`, 560dp): "Board fully functional." / "Board connected but sending nothing — check it is powered and its sketch is running." / "Board connected but not speaking DASH — check it is running the DASH module SDK." Narrow screens drop the sentence and keep the lights, because the words would otherwise crush the board's name and the name is the point.
- **An address panel** — "POINT YOUR BOARD AT 192.168.50.4:3274", copyable. Driven off a pipe *declaring* an address rather than off its kind, so it is not a WiFi special case.
- **One honest pipe-condition line** when no boards are present: normally "No boards connected", but "Waiting for USB permission…" or a bind error when that is the truth.
- **Deep-links out** to Android's own Wi-Fi and Bluetooth settings for radio-level and pairing controls.
- **`TransportStatus.address`** — a structured field for the above.
- **The WiFi idle horizon** — `soTimeout = DASH_ABSENT_MS` on each accepted client, closing it with the reason `silent for 75s`.
- **`DASH_ABSENT_MS` promoted** out of `Reconciliation`'s private companion to a top-level const, so the board view and the module view age on one clock.
- **Tree restructure:** the **Developer** and **Transports** top-level categories folded into **Modules** (Module Manager, Transport Manager, Serial Monitor, Signal Monitor, Activity Log).

**Regressions:**
- None found. No previously working feature was touched — the tab is new, and the two transport-layer changes (`address`, `soTimeout`) are additive.

**Fixes (found and fixed inside this version, on the bench):**
- **The card lied about a board that was gone.** A powered-off WiFi board sends no FIN and no RST, so `input.read()` blocked for ever, the client was never closed, and the card kept showing a link, a device count and an IP for a board lying unplugged on the bench. Pressing CHECK NOW could not help either — writing `DISCOVER` into a dead socket succeeds locally. Fixed with the idle horizon above. Bluetooth needs no equivalent; its radio link supervision throws within seconds on its own.
- **The lights forgot everything on leaving the tab.** The first cut accumulated "data seen" / "HELLO seen" flags inside the composable from the wire tap, so navigating away and back emptied them, and they could only refill from a 200-event buffer shared across all pipes. A healthy but quiet board therefore read as silent for up to 30 s — exactly when a worried user was looking at it. Replaced with two timestamp maps held for the app's life: `TransportManager.lastInboundAt` (bytes) and `DashController.lastDashAt` (grammar), each recorded by the layer that knows the fact.
- **Those maps were keyed per pipe, which is the wrong thing.** "Data arrived on the WiFi pipe" is meaningless with two boards on it — one can be streaming while the other is dead, and a per-pipe answer averages them into a lie. Re-keyed by `DeviceRef` (tag + device key); every transport already stamped a device key on its frames, so USB, WiFi and Bluetooth all got it for nothing.
- **The address was regex-scraped** out of `status.detail`, so rewording a status string would have silently broken the copy button. Replaced by the structured field.

**Cut:**
- **CHECK NOW.** It was Module Manager's REFRESH borrowed wholesale, and it fitted badly: half its work (pruning the discovered list) is invisible on this tab, and it could confirm presence but never absence. The pipes already greet an arriving board in ~100 ms, sweep every 30 s and close a dead client on the horizon — a manual refresh on a self-refreshing surface only teaches the user to press something before believing the screen. If a screen is stale, fix the staleness, do not add a button.
- **The `1 DEV` chip and the WIRED/WIRELESS tags.** The board list states the count literally, and — Roger — "if you don't know bluetooth and wifi are wireless, then you got other problems". The wired/wireless *fact* still earns its keep in `Reconciliation.isWired()`, where it words a NOT_RESPONDING module honestly on Module Manager.
- **The LINK light.** On a board row it is green by definition — a board that is not linked is not listed — so it carried no information.

**Outstanding:**
- Detection of a dead WiFi board is bounded at 45–75 s (the clock starts at the last byte received, not at the unplug). That is inherent to a passive TCP pipe: a powered-off peer is genuinely indistinguishable from a quiet one until you have waited long enough to be sure. Measured and accepted, not a defect.
- The Developer category's fate is **tabled to 1.5.12/1.5.13**. This version's tree already folds its instruments into Modules and drops the safety-acknowledgement gate as contrary to CLAUDE.md's *no hidden menus, no barriers*, but that decision is not formally taken.

**Notes:**
- **Bench measurements worth keeping.** Greet-on-arrival fires ~100 ms after a socket is accepted; a healthy R4 answers `DISCOVER` in ~0.3 s; the slow sweep is 30 s and a greet correctly *resets* that timer rather than firing an extra sweep. All measured with a bare TCP probe on port 3274, which is a genuinely useful debugging trick — a second client sees every broadcast DASH sends, so it can observe a *different* board's arrival being greeted.
- **A reported "30 s to receive data" on replug did not reproduce** and no cause was found. Measured at 0.3 s on the same hardware afterwards. Recorded here so that if it returns there is a note that it was seen once.
- **A board is not a module.** Two boards could reveal a whole catalogue of modules. This is the line between Transport Manager and Module Manager and it should not blur.

---

## Version 1.5.9

**Status:** Complete — Modules › Enable/Disable cut before any code; single-subcategory categories now open straight from the main tree. 2026-07-24, Roger's call.

**Scope:** 1.5.9 was planned as a per-module user-facing **disable** (a stored intent withholding ACTIVATE, a persistent sibling of the 1.4.13 quarantine) plus **transport assignment** for a module reachable over more than one pipe. Pinning the disable semantics was the gate before building it — and pinning it surfaced the real question: does DASH need this at all? On interrogation, neither half earned its place in v1, so the version became the **cut** and its natural consequence rather than the feature.

**Implemented:**

- **Enable/Disable cut.** The `modules.enable` subcategory was removed from the tree; no disable mechanism was built. Reasoning, on the record: a board can already be silenced by unplugging or uninstalling it, so disable uniquely serves only the thin case of a board physically connected but software-muted with its config kept — not enough to justify a persistent user-intent layer, which the DASH ethos says not to build ahead of a felt need.
- **Transport assignment cut.** A make-it-work-correctly concern that only bites once boards actually appear on two pipes at once — which hasn't happened. Deferred until it does (likely v2).
- **Single-sub categories open straight from the main tree.** With Enable/Disable gone, the Modules category has one subcategory (Module Management), and a one-item submenu is pointless. `SettingsShell` now treats a single-sub category as a **leaf**: tapping it selects its one sub's content directly, skipping the intermediate submenu. Made **generic** — any single-sub category collapses this way, so **Transports** (also one sub, `transports.list`) behaves the same.
  - **Wide (two-pane):** the main tree stays in the left margin and the leaf's content fills the right pane; the leaf category's row reads as selected, the breadcrumb names it, and BACK deselects to the weather landing (rather than closing outright).
  - **Narrow (drill-down):** tapping the leaf goes straight to its content (depth 0 → 2); BACK returns to the main tree.
  - The content box now resolves its subcategory **tree-wide by id** (`findSubAcrossTree`) rather than only within a selected category, which is what lets a leaf's content show with no owning category selected.

**Regressions:**

- None observed. Multi-sub categories navigate exactly as before; only single-sub categories changed, and they had no reason to show a one-item list.

**Fixes:**

- Wide-layout BACK/CLOSE label and back handler updated for the new "leaf content open with the main tree still showing" state — BACK deselects the content; CLOSE only shows at the true top.

**Outstanding:**

- The disable idea is documented (roadmap 1.5.9, and the design worked through in this session's discussion) should a real need ever surface; it would return as its own version, likely v2. The 1.4.13 quarantine is the mechanism to reuse if so.
- `SettingsTree.kt` still carries pre-renumber WIP version labels on not-yet-live subs — the 1.5.15 sweep's job, untouched here.

**Notes:**

- **Bible updated** — roadmap.md 1.5.9 rewritten from the planned feature to the cut + nav-collapse (kept, not renumbered — 1.5.9 is a real version, not a gap); this entry.
- This is the DASH ethos doing its job: a feature that looked tidy in a settings tree, interrogated, didn't survive "do you actually need it". Roger caught that it had been planned into the tree by an earlier design pass, not asked for — and cutting it was the right call.

---

## Version 1.5.8

**Status:** Complete — Modules › Module Management is LIVE in the settings shell; the DETAILS dialog is gone and the card is now a tap-to-select row driving its action from the top bar. Hardware-verified by Roger, 2026-07-24.

**Scope:** Rehome the 1.4.x Module Management instrument into the Modules tab. Planned as a straight "no rebuild" migration; in the doing it became a card-interaction redesign (Roger's direction), because once the instrument was in the settings box the standalone screen's chrome, its floating DETAILS dialog and its per-card action row all read as heavy and out of place.

**Implemented:**

- **Modules › Module Management is LIVE** (`ui/modules/ModulesContent.kt`, the renamed old `ModuleManagementScreen.kt`), rendered inside the shell. One line in the content router; the tree entry flipped WIP→LIVE.
- **Reached through `LocalModuleDesk`.** The four managers it needs — `discovery`, `install`, `database`, `reconciliation` (plus the update callback) — are *stateful* and live on `DashController` for the app's life, so unlike the stateless-prefs tabs that rebuild from context, the tab reaches them through a new CompositionLocal provided in `MainScreen` (mirroring `LocalEnterBarEdit`). Rebuilding them would have given a dead, empty screen.
- **`fillsBox` tree flag.** A new `SettingsSub.fillsBox` tells `SettingsContentBox` to hand the tab the whole box height with no outer scroll, so its controls pin at the top and only the card list scrolls beneath. (Sets up the Developer instruments at 1.5.11, which want the same shape.)
- **The DETAILS dialog was removed entirely** — both the floating card and the DETAILS button. It carried no detail of real use; its one unique action, UPDATE on a firmware mismatch, was preserved (see below).
- **The card became a tap-to-select row.** No per-card button. Tap anywhere to select (2dp border + a faint fill-lift mark it); tap again to deselect. The card shrank back accordingly.
- **Actions moved to the pinned top bar — REFRESH left, the selected module's action right.** Discovered → INSTALL (→ progress + CANCEL → installed); installed → UNINSTALL, plus UPDATE when the firmware version no longer matches (1.4.13); a failed install → its reason with RETRY / DISMISS. The whole install-state machine simply relocated from the card to the top bar, keyed to the selection.
- **Transport tag added** to the chip row — USB / WIFI / BT, a neutral blue-grey tag beside the type and activity chips, from a new `Reconciliation.transportTag(id)` accessor.
- **Identity lines never wrap.** Name, description and id/version each scroll sideways if they outrun the card, so nothing reflows and grows the card.
- **Cards moved onto the settings surface** (`backgroundColourSecondary`), defined by a border rather than a dark fill; the near-black list "well" was dropped so the whole tab reads as one surface. Neutral card text moved onto the secondary-text token at graded alphas (the old dark-surface greys would have been invisible on grey); semantic colours — status chips, accent buttons, the fail-red — were left alone.
- **Every button moved to the modern DASH idiom** (`DashButton` — a rounded, token-styled tap, not a Material button): filled semantic accents for INSTALL/UNINSTALL/UPDATE, the quiet outline style for neutral actions, and REFRESH as the scaffold's `LinkButton` text action.
- **The standalone route was deleted in full** (Roger's call): the old full-screen `ModuleManagementScreen` invocation, `showModules` state, and the legacy panel's "MANAGE MODULES →" section are gone.
- **The unconfirmed-deactivation safety warning stayed** — a distinct §6 warning, not the details dialog.

**Regressions:**

- None observed. The migration is behaviour-preserving for install/uninstall/discovery; the interaction around them changed by design.

**Fixes:**

- The scaffold's outer `verticalScroll` would have fought the card list's own scroll (nested scroll = unbounded-height crash); the `fillsBox` path avoids it by giving the tab the box height directly and letting its weighted list scroll on its own.

**Outstanding:**

- What the DETAILS dialog *uniquely* showed is gone for good: the module's declared signals/subscriptions/assets, and the worded wired-vs-wireless explanation for a NOT RESPONDING module. The chips still convey the states; only the verbose readout went. Roger judged it not useful.
- During an install the top bar packs REFRESH + a 150dp progress bar + CANCEL; comfortable on the Pixel/Tab widths, could crowd on a very narrow screen. Easy to shrink if it ever shows.
- `SettingsTree.kt` still carries pre-renumber WIP version labels on the not-yet-live subs (`modules.enable` reads "1.5.7", etc.) — the 1.5.14 sweep's job, untouched here.

**Notes:**

- **Bible updated** — roadmap.md 1.5.8 ticked off with a dated note recording the redesign; this entry. interface.md was **not** touched: its settings tree lists the *future* per-module actions (enable/disable, transport assignment, relay subscriptions, remove/uninstall — 1.5.9+) and does not describe the DETAILS dialog interaction, so nothing in it contradicts what shipped.
- Designed live with Roger across the version, in four passes: the migration itself; REFRESH onto the correct token; the button-modernise + card-fill-to-token pass; then the header removal and the DETAILS-dropping tap-to-select redesign.

---

## Version 1.5.7

**Status:** Complete — Layout › System Bar: Position, Zones and Reset in the box; edit mode stripped to the ruler plus Save/Cancel; and edit mode returns you to the tab you left. Hardware-verified by Roger, 2026-07-23.

**Scope:** Rehome the System Bar controls into Layout › System Bar — and, in the doing, resolve two things the design conversation surfaced: edit mode still duplicated the height/element sliders that moved to Size & Scale in 1.5.3, and "Edit bar layout" dropped you on the home screen. The result reworks what edit mode is *for*.

**Implemented:**

- **Layout › System Bar is LIVE** (`ui/settings/content/SystemBarContent.kt`), in the house scaffold. Box order **Position → Zones → Edit bar layout → Reset**:
  - **Position** — a live Bottom/Top segment, with a small **"screen" preview** in which the bar slides to its chosen edge.
  - **Zones** — a live 1/2/3 segment (zone *count*; boundary *positions* are still set on the bar in edit mode).
  - **Edit bar layout →** — the entry into edit mode, via a new `LocalEnterBarEdit` CompositionLocal (mirroring `LocalSplashPreview`, so no callback threads through the settings shell).
  - **Reset** — tap-again-to-confirm, restores the full default.
- **Edit mode reduced to its irreducible job.** The centred control column (Position, Zones, Bar Height, Element Size, Reset) is gone; edit mode is now **the ruler beside the bar plus Save/Cancel**, nothing else. **Bar Height and Element Size were deleted, not moved** — they have lived in Appearance › Size & Scale since 1.5.3, so this finally clears the "ruler coexists with the steppers" duplication parked then.
- **Edit-mode colours read the theme tokens.** Save/Cancel dropped their hardcoded green/red for the token set (Save = `backgroundColourPrimary`/`textColourPrimary`, Cancel = `accentColourSecondary`/`textColourSecondary`), so they follow v2 theming and match DASH's neutral default. The ruler was already token-driven; the only literal colour left is the interface.md-mandated **snap red**.
- **A clean workspace.** The module-panel placeholder and the "not your default launcher" banner both **hide while editing** — nothing competes with the bar and its ruler.
- **Save/Cancel return to the System Bar tab.** Entering edit remembers the tab (`layout.systembar`); Save and Cancel reopen the settings blind seeded to it, so you land back where you were instead of on the home screen. `SettingsShell` gained an `initialSubId` seed; `MainScreen` holds the return target and clears it on close.
- **System Bar section removed from the legacy flat panel** — its rehome.

**Regressions:**

- **Save/Cancel dropped to the home screen.** Entering edit mode closed the settings panel and never reopened it.

**Fixes:**

- **Return target:** edit-entry records the originating tab and Save/Cancel reopen settings on it (seeded via `SettingsShell.initialSubId`); the target clears when settings is next closed, so a normal open still starts at the top of the tree.

**Outstanding:**

- Edit is only ever entered from the System Bar tab, so the return target is set literally (`"layout.systembar"`); a future surface entering edit from elsewhere would set its own.
- interface.md item 4's earlier "not flat height/zone/element sliders" wording is now qualified by the 2026-07-23 addendum recording the Position **+ Zones** split.

**Notes:**

- **interface.md updated** — a dated addendum under item 4 of the 2026-07-20 Layout reconciliation: System Bar settings = Position + Zones + Edit-bar-layout entry + Reset; height/element size live in Size & Scale; edit mode = ruler + Save/Cancel. A deliberate, discussed Bible edit.
- This was designed live with Roger across the version — the ruler-must-stay-beside-the-bar call (Option A over pulling it into the box) preserved the 1.3.x edit model's core structural departure; the box took the discrete config, the bar kept the spatial task.

---

## Version 1.5.6

**Status:** Complete — Appearance › Splash Screen: colour / image / animation, an independent background colour with a custom picker, and a per-orientation image crop. Hardware-verified by Roger on the Pixel 8 Pro and the Galaxy Tab S9 Ultra, 2026-07-23.

**Scope:** Exposes the 1.2.x splash feature — and grew well past "image-or-colour + duration" in design with Roger. Three source types, the splash's own colour, animated images, and a "Model B" crop editor with per-orientation crops. Designed in full before code and built in two verified phases (structure, then crop).

**Implemented:**

- **Three-way type — Colour / Image / Animation** (`ui/settings/content/SplashContent.kt`). Image is a *still* (a GIF shows a frozen first frame); Animation is a GIF / animated WebP that **plays through once and then fades** — no dwell, the animation's own length is its duration. Still and animation keep **separate file slots**, so switching type remembers each pick.
- **BackgroundColourSplash — the splash's own independent colour** (raw ARGB, deliberately *not* a theme reference). Theme tokens plus black/white are offered as **preset swatches** to seed it, and a compact **HSV custom picker** (a saturation/value square over a hue bar; `ui/settings/content/ColourPicker.kt`) takes it anywhere. It **persists across all three types**: for Colour it is the whole screen, for Image / Animation it is the backdrop and the **matte behind any letterboxing**. The Background control sits **between Type and Preview** so it applies to everything — which is also what settled the matte question, by reuse rather than a second control.
- **Display time** — a ± stepper (0–10s, half-second steps; floor is 0 because DASH has no opinion on whether you want a splash at all), shown for Colour and Image, **hidden for Animation**.
- **Animation playback** (`ui/splash/SplashScreen.kt`) — API 28+ decodes an `AnimatedImageDrawable` via `ImageDecoder` and plays it once; the end callback fades the splash, with a 20s safety cap. API 24–27 degrades to a static first frame under the dwell timer — the no-special-cases graceful path. The two fades either side remain Transitions' concern (1.5.5).
- **Real-shape preview + orientation toggle** — the preview draws in the **actual screen aspect**, taken from the screen's long/short dimensions (right regardless of how the device is held), with a **Landscape / Portrait toggle** deciding which way round (default landscape). This replaced the fixed 16:9 preview and fixed a bug where a landscape device showed a portrait preview.
- **Per-orientation image crop — "Model B"** (`ui/splash/SplashCrop.kt`, `ui/settings/content/SplashCropEditor.kt`). Tap the preview to edit: a fixed dashed frame **is** the screen, and the photo **pans and pinches behind it**. Zoom runs from the **contain floor** (whole image visible, background colour showing as matte in the gaps) up through the **standard-crop detent at 1.0** — where it clicks and holds; release and re-pinch to cross it, symmetric in both directions — and on into tight zoom. **Two crops are stored, portrait and landscape** (a tall slice and a wide slice of one picture can't come from one rectangle). The same crop drives the **real boot splash**, chosen by the actual screen shape at boot. Colour has nothing to crop; **animation centre-crops for now** (stills-first).
- **Splash section removed from the legacy settings bridge** — this version is its rehome. Appearance › Splash Screen is now LIVE in the settings tree.

**Data model** (`DashPreferences`): `splash_mode` (COLOUR/IMAGE/ANIMATION), `splash_bg_colour` (Long ARGB — BackgroundColourSplash), `splash_image_uri` + `splash_animation_uri` (separate slots), `splash_dwell_millis`, and `splash_crop_portrait` + `splash_crop_landscape` (each encoded `"zoom,panX,panY"`). The colour is theme-independent; the crop is **resolution-independent** — zoom is relative to the cover fit, pan is normalised — so the small preview and the full boot splash render an identical crop.

**Regressions:**

- **Landscape device showed a portrait preview** — the preview read the device's live orientation.
- **Colour picker snapped the hue back.** A `pointerInput(Unit)` block captured its first `hsv` and callbacks once, so adjusting hue and then saturation/value reset the hue, and a drag's commit persisted a stale colour.

**Fixes:**

- **Preview orientation:** derived from the screen's long/short dimensions and the new toggle rather than the live orientation — deterministic, and the bug is gone.
- **Picker stale-capture:** the current `hsv` and callbacks are routed through `rememberUpdatedState`, so a gesture always acts on the latest and commit saves the true colour.

**Outstanding:**

- **Zoom pivots about the frame centre**, not the pinch centroid — accepted; a maths tweak if wanted.
- **Drag vs scroll:** the crop editor sits inside the scrollable settings box; the gesture consumes to avoid scrolling the page — to harden if it ever fights.
- **The background HSV picker is full-height above the preview**; a "Custom" disclosure to collapse it was offered and not taken.
- **Animation crop deferred** (stills-first); **true video is out of scope** — it needs a media player, not a drawable.
- **Stale WIP tree labels** still await the 1.5.14 sweep (carried from 1.5.5).

**Notes:**

- Built in two phases with Roger, each hardware-verified: **Phase 1** — three types, the reorder, the real-aspect preview and the colour model; **Phase 2** — the crop, per-orientation, and the orientation-bug fix.
- **New shared building blocks:** `ColourPicker` / `Hsv` (HSV picker + conversions), `SplashCrop` with `CroppedImage` / `rememberSplashBitmap` / `placeImage` (resolution-independent crop maths and rendering, shared by preview, editor and boot splash), and `LocalSplashPreview` (a CompositionLocal that lets the Splash tab play the real full-screen splash without threading a callback through the settings shell).

---

## Version 1.5.5

**Status:** Complete — Appearance › Transitions: a master pace over a per-transition breakout, six speed presets, and the settings-landing weather pre-loaded so it opens on real weather. Hardware-verified by Roger, 2026-07-23.

**Scope:** Rehome the transition-length control (parked in the legacy settings bridge since 1.5.2) into its proper settings home, and build DASH's motion model out from a single global knob into a per-transition system. Designed in full with Roger before code; the governing rule and the tree edit are recorded in interface.md (2026-07-23 addendum).

**Implemented:**

- **The governing rule — "if it's a transition, it goes in Transitions."** A transition is a surface revealing, hiding, or moving between states; every one is user-controllable and breaks out to its own control. Control-feedback micro-animation intrinsic to a widget (a toggle thumb, an edit-ruler handle) is *not* a transition and stays fixed. This line is deliberately simple so it needs no case-by-case adjudication.
- **Six speed presets** (`ui/motion/DashMotion.kt`, `TransitionSpeed`): INSTANT (0ms, a true hard cut) · FAST (250) · NORMAL (450, default) · SLOW (750) · CINEMATIC (1100) · **LABORIOUS (3000)** — the last a deliberately, gloriously slow option, the ethos made literal.
- **A transition registry** (`TransitionId`): every transition in the code is one entry (stable key, label, hint, default). The Transitions page renders one control per entry automatically, so a new surface's transition gains a control the moment it registers — the same self-growing pattern the transport list uses. The seven at 1.5.5: settings panel **open** / **close**, settings nav **drill in** / **back out**, settings content **swap**, splash **fade in** / **fade out**.
- **Master pace + per-transition breakout** (`ui/settings/content/MotionContent.kt`): a master control sets every transition at once; each transition then has its own six-preset control. The master is **derived, never stored** — it shows the shared speed when all agree and a **"Custom"** tag (nothing selected) the moment one diverges; re-tapping a master preset re-syncs the lot. This is the games-menu pattern, and deriving rather than storing means the master can never fall out of step with the rows.
- **Open and close split** (`MainScreen.kt`): the settings roll-out reads its duration from OPEN when expanding and CLOSE when collapsing — the two are genuinely independent (roll out slow, snap shut fast, or any pairing).
- **Splash gained a real fade-in** (`SplashScreen.kt`): it only faded out before. Both fades now read their durations from the registry; the splash *dwell* and image/colour selection remain a Splash-tab concern (1.5.6).
- **Under the hood:** the single global `LocalTransitionMillis` is replaced by a `LocalDashTransitions` holder provided at the composition root, resolving each `TransitionId` to its stored speed (`DashPreferences.transitions` / `setTransition` / `setAllTransitions`, stored by name). The parked control in the legacy settings bridge is removed — this version is its rehome. Appearance › Transitions is now LIVE in the settings tree.
- **Weather pre-load** (`ui/weather/WeatherLandingState.kt`, `MainScreen.kt`): the settings-landing weather is warmed at app start and cached at the root (`LocalWeatherSnapshot`), refreshed in the background on each settings open. The landing opens from the cache instead of the clock-only floor.

**Regressions:**

- **Re-entering Transitions flashed the defaults.** `MotionContent` originally started its own `collectAsState(initial = emptyMap())`, so navigating away and back painted NORMAL for one frame before DataStore delivered the stored speeds — very visible at LABORIOUS.
- **Rapid tab-swap snapped instead of crossfading.** The wide content pane used `AnimatedContent`; re-selecting a tab whose fade-out was still in flight cancelled its exit and snapped it back — a fast "not instant, but too quick" pop, again exposed by LABORIOUS.
- **Weather flashed "clear" on open.** The landing self-fetched from the clock-only floor each time it composed, so it showed clear for a fraction of a second before the live reading arrived.

**Fixes:**

- **Flash on re-entry:** `MotionContent` now reads the always-alive `LocalDashTransitions` from the root instead of starting a fresh collector, so the stored speeds are present on the first frame.
- **Snap on tab-swap:** the content pane swapped from `AnimatedContent` to `Crossfade`, which re-targets each state's alpha over the full duration when interrupted — tapping back animates home at the chosen speed rather than snapping. The swap is now symmetric (both directions one duration).
- **Weather flash:** fixed by the pre-load above — warmed, cached, and opened from the cache.

**Outstanding:**

- **Copy/text polish deferred** (Roger, 2026-07-23) — the page's labels, hints and header wording want a cleanup pass; parked deliberately, the functions are agreed correct.
- **Shrink-to-fit trade-off:** at six presets the longest labels (CINEMATIC, LABORIOUS) shrink to fit a phone in portrait, so all chips share one smaller size. Accepted; a two-rows-of-three fallback was offered and not needed for now.
- **Cold-boot corner:** opening settings within a second or two of a cold boot, before the warm-up fetch returns, still shows the clock-only floor briefly (there is genuinely no data yet). Not gated on purpose — the fetch is a blocking call on 5s socket timeouts, and gating the open on it could freeze the button for seconds.
- **Stale WIP version labels** in the settings tree (Splash still says "1.5.4", System Bar "1.5.5", etc. — predating the 2026-07-21 renumber) left untouched; a sweep for the 1.5.14 cleanup.

**Notes:**

- **New scaffold vocabulary:** `FitPresetSegment` (a segmented selector that measures its label font down until all cells fit the width, instead of side-scrolling) and a `fullWidthControl` option on `SettingBlock` (so a wide six-cell control stacks under its label rather than crushing into the right-hand nook). Both live in `ui/settings/content/SettingsScaffold.kt` for reuse. The per-row live "demo" preview built first was removed at Roger's request — it took too much room.
- **The naming.** The subcategory was weighed as "Motion" and settled as **Transitions**, matching the noun the governing rule uses; "Motion" would imply it also owns micro-animation, which it explicitly does not.

---

## Version 1.5.4

**Status:** Complete — the settings-landing weather scene, live weather over a location cascade, System › Location settings, and the user-replaceable art scheme. Hardware-tested by Roger on the Pixel 8 Pro (and the Galaxy Tab S9 Ultra), 2026-07-23.

**Scope:** Fill the bare settings-landing box with a designed, offline-capable, good-looking interim visual — the layered weather scene brought forward from the version-2 hero, *minus* the vehicle silhouette and live-car interaction (those stay v2). Designed in full with Roger before any keeper code, then reconciled to the finalised layer model once the look was agreed.

**Implemented:**

- **The layered weather scene** (`ui/weather/WeatherScene.kt`) — four layers, back to front, each drawn from user/bundled art if present and procedurally if not, so the scene is complete with zero art shipped:
  1. **Skybox — nine time-of-day states** (dawn, sunrise, morning, midday, afternoon, evening, sunset, dusk, night). Carries *time only* — sky colour and the sun. The **sun rides the right third and moves vertically** (low at dawn/dusk, high at midday), so clouds light from a consistent side.
  2. **Clouds — seven levels**, driven by the real `cloud_cover` reading; painted neutral and code-tinted to the hour so one set serves every sky. Level 7 reads as full overcast.
  3. **Background — three graded states** (day / night-with-lit-windows / snow). Each exists only for content a grade can't add; everything between is one of these graded.
  4. **Foreground — procedural** rain / snow (and fog), never art; particles scale to precipitation and rake with the wind.
- **"Frozen clock, living air."** The snapshot is fixed for the session (taken when the landing opens, re-fetched on reopen), but the clouds still drift at the real wind speed and the particles still fall while it is on screen. The frame loop runs only while composed.
- **The readout** — bottom-left, left-aligned, over a soft bottom fade (a gradient, not a box — the picture is never framed): time, temperature, condition, location, and a **"Weather data by Open-Meteo →" attribution link** (Open-Meteo's free tier is CC-BY 4.0, so attribution is required). It wears DASH's typography — the `font` token, sized in `sp` so it rides the `dashTextScale` stepper.
- **Live weather** (`weather/WeatherProvider.kt`) — Open-Meteo (`api.open-meteo.com`), keyless, over `HttpURLConnection` + kotlinx.serialization (no new dependency). Maps `weather_code`, `is_day`, `temperature_2m`, `cloud_cover`, `wind_speed_10m`, `wind_direction_10m`.
- **The location cascade**, most-owned first: **manual pin → GPS → IP geolocation → clock-only floor.** GPS is a last-known coarse fix, used *only if* `ACCESS_COARSE_LOCATION` is already granted (DASH never prompts — the no-nag rule); IP geolocation is keyless across two providers (`ipwho.is`, then `ipapi.co`) with a browser-like User-Agent; offline it renders a correct time-of-day sky from the device clock alone.
- **System › Location** (`ui/settings/content/LocationContent.kt`, live tree entry) — the two controls that make the cascade reachable without adb:
  - **Use device location** — a toggle that *is* the permission ask, raised only when the user reaches for it (user-initiated, not a nag). Off, the scene uses IP geolocation; a granted permission lights up the GPS rung.
  - **Manual location** — type a town; resolved once via Open-Meteo's geocoder and pinned at the top of the cascade, overriding GPS and IP both. "Use automatic" clears it.
  - New reusable scaffold control `SettingToggle`; manual pin persisted via `DashPreferences` (`manualLocation`).
- **User-replaceable art — the SDKable principle made concrete.** Three tiers per layer (user → bundled → procedural). Drop image files into **`Android/data/com.dash.android/files/weather/`** (DASH's own external files dir — no permission, no root; on Android 11+ it is hidden from file managers, so use `adb push <file> /sdcard/Android/data/com.dash.android/files/weather/`). Extensions `.webp` / `.png` / `.jpg`. Any file overrides that one layer; any absent file stays procedural. **Filenames:**
  - Skybox (time only): `sky-dawn`, `sky-sunrise`, `sky-morning`, `sky-midday`, `sky-afternoon`, `sky-evening`, `sky-sunset`, `sky-dusk`, `sky-night`
  - Clouds: `clouds-1` (lightest) … `clouds-7` (full overcast) — paint neutral, lit from the upper right
  - Background: `background-day`, `background-night`, `background-snow`
  - Foreground weather (rain/snow/fog) takes no art — it is procedural.
- **The content-box crossfade** — picking a subcategory now crossfades from the scene to the content (and between subcategories), timed to `LocalTransitionMillis`, instead of a hard cut.

**Regressions / dead ends (the honest bits):**

- **An APK was built mid-design, before the scene was agreed** — Roger (rightly) called it out; the code was parked and not built on until he gave the word. Recorded because the lesson — design sign-off is not a build order — is worth keeping.
- **IP geolocation first showed "offline" forever** — `ipapi.co` was silently rejecting the default Java user-agent. Fixed with a browser-like User-Agent and a second provider (`ipwho.is`) as fallback, plus `DashWeather` logging to diagnose it. Confirmed on-device: a granted-permission fix pinned Barleythorpe (Rutland) exactly; without permission, IP wandered between London and Northampton.
- **The first naming scheme predated the finalised design** — the parked `WeatherArt` baked condition into the skybox and had a single background. Reconciled to the 9/7/3 model (skybox time-only, clouds carry condition, three backgrounds) so the artist's set drops straight in. The old `overlay-snow` / `overlay-wet` (snow-cover / wet-sheen) were removed — they belong to v2 with the ground and vehicle.

**Outstanding:**

- **The offline→live swap is a hard snapshot change**, not a crossfade — the scene can visibly "pop" from clear to the real condition a beat after opening. A within-scene crossfade is an easy follow-up if it grates.
- **Painted art is optional, not pending.** Roger likes the procedural look as a default in its own right; the 9/7/3 art slots are an enhancement that overrides it, not a prerequisite. Don't gold-plate the art pipeline.
- **Manual-city geocoding takes the first match** — no disambiguation UI for same-named towns.
- **GPS reads last-known network location** — instant and battery-free, but can be null on a device that hasn't fixed recently (it then falls through to IP); there is no active single-shot fetch.

**Notes:**

- **Version bump:** `versionName` 1.5.3 → 1.5.4, `versionCode` 23 → 24.
- **New manifest permission:** `ACCESS_COARSE_LOCATION`, declared for the GPS rung — never prompted by DASH itself; it lights up only if the user grants it (capability detection), and the scene works from IP until then.
- **The whole scene was designed with Roger in one session before any keeper code** — the four-layer split, frozen-clock/living-air, the right-third sun, the bottom-left readout, the attribution link, and the location cascade were all agreed first.

---

## Version 1.5.3

**Status:** Complete — Appearance › Size & Scale, the reusable settings content scaffold, and the adaptive settings layout. Hardware-tested by Roger on both the phone and the tablet, 2026-07-22.

**Scope:** The first settings tab with real controls — and, because building it well demanded it, the scaffold every later tab is built from and the adaptive shell that houses them all. What began as "Density & Scale" became a DASH sizing hub, and the panel learned to reshape itself to the screen it is on.

**Implemented:**

- **Appearance › Size & Scale** (renamed from "Density & Scale"), two headed sections:
  - **DASH Scale** — DASH's own chrome, each surface on its own ± stepper: system bar size and element size (writing `SystemBarConfig`; the live bar resizes on the tap), an app-favourites-bar-size placeholder (disabled until the App Launcher, 1.8.x), and DASH text size.
  - **Android** — Android's settings for the viewport apps: app density and font size, capability-gated.
- **DASH owns its text sizing.** A new `dashTextScale` preference is applied at the composition root (`MainScreen` overrides the composition `fontScale`), so *all* DASH chrome text follows the DASH text-size stepper and ignores Android's font setting. Android's font size is left for the viewport apps. One override at the root does it — no per-`sp` sweep. This retires the vague global "DASH UI scale" in favour of **per-surface sizing + a DASH-owned text size** (interface.md reconciled).
- **Capability-gated Android controls.** App density (and the privileged font-size control) follow the Capability Detection Principle: on a privileged install the native preset controls appear — mirroring Android's own display-size page, with a live preview; on Bronze they are absent and a single honest link, "Android text & display size →", points out to Android's own page. That link uses the public `android.settings.TEXT_READING_SETTINGS` action — discovered on-device to be the one page both stock Pixel and Samsung One UI leave open to a third-party app (their dedicated font/zoom pages are `exported=false`) — falling back to the Display parent.
- **The settings content scaffold** (`ui/settings/content/`) — the reusable vocabulary every future tab is built from: `SettingsContentHeader` (title + art-deco rule, optional description), `SettingBlock` (label + help + optional live preview, responsive), and the controls `PresetSegment`, `Stepper`, `LinkButton`, `LivePreviewCard`. A one-line-per-tab router (`SettingsContent`) claims each subcategory id; the navigation shell never changes as tabs are added.
- **Adaptive settings layout.** The shell lays itself out from the space actually available (measured, not a window-size class): **wide** (≥600dp — tablet, landscape) is two-pane, tree in the left margin, content beside it, holding on an empty landing box until a *subcategory* is chosen (category → sub → content); **narrow** (<600dp — phone portrait) is the progressive drill-down from interface.md's original three-level model — tree fills the screen, a category replaces it with its subtree, a subcategory replaces that with the content, a back control pinned to the bottom walks down and closes at the top. Both share one navigation state, so rotating reflows between them.
- **Rotation persistence.** `MainActivity` now declares `configChanges` (orientation / screen size / layout), so it is no longer recreated on rotation — whatever is open (settings, a monitor) survives and reflows instead of resetting to the home screen.
- **Polish** — more vertical breathing room, 34dp between settings, a nearly-imperceptible separation line at the bar/panel boundary (secondary colour, tracking the bar as its size changes), crisp tween slides (springs removed), the empty wide-landing box drawn straight away, and the trailing nav arrows removed at Roger's call.

**Regressions / dead ends (the honest bits):**

- Density presets first showed on Bronze where they can't work — corrected to the capability gate.
- The tree's trailing arrows did an awkward half-slide-then-fade; a per-arrow independent fade fixed it — then the arrows were removed entirely anyway.
- Slides felt laggy on the phone in the *debug* build — diagnosed (with Roger's own "beautiful on the tablet" observation) as debug-build overhead plus the narrow path animating the heavier full-screen content, not an over-heavy design. Springs swapped for tweens to crisp the feel; the first-open cold-composition hitch is a release-build / baseline-profile matter, not something to chase in debug.

**Outstanding:**

- **Only text is off Android.** DASH's `dp` dimensions still follow the system density, so app density still moves DASH chrome. Rendering DASH against native density (full immunity to app-density) is the parked dp-renormalisation step.
- **The privileged Android font-size control is visual only** — it moves its preview but does not yet write Android's real font scale (the font analogue of `DensityManager` is unbuilt). Absent on Bronze anyway.
- **The EDIT BAR LAYOUT ruler still exists** alongside the new bar/element steppers (one source of truth, two editors) — remove once the steppers are trusted.
- **The nav-tree label** still reads "Size & Scale" while the in-box headings are the "DASH Scale" / "Android" split — a cosmetic reconcile.
- The narrow content transition can be lightened (fade over full-width slide) if the phone still feels heavy on a release build.

**Notes:**

- **Version bump:** `versionName` 1.5.2 → 1.5.3, `versionCode` 22 → 23.
- **interface.md** gained two dated 2026-07-22 addenda (additive, originals kept): the Density/Scale section reconciled to per-surface sizing + DASH-owned text (retiring the fluid global "DASH UI scale"), and the Settings Panel section extended with the adaptive layout and the **content-scaffold pattern** so future tabs are built the same way.
- The adaptive layout and rotation persistence were built in this pass rather than as a separate numbered version — they emerged from making the first real tab feel right across the phone and the tablet, and shipped and were verified together.

---

## Version 1.5.2

**Status:** Complete — the first *built* version of the 1.5.x Settings Panel era. Hardware-tested by Roger on the tablet, 2026-07-20.

**Scope:** The settings shell and the full navigation tree — the chrome every later 1.5.x version drops real controls into. Built visuals-first and deliberately empty of real settings (Option B): the shell is what is being tested, the tree is honest placeholders, and the pre-1.5.2 flat panel stays reachable underneath so nothing is lost.

**Implemented:**

- **The shell** — a two-pane settings panel (not the three-column model interface.md originally described; the cleaner two-pane one was agreed during the build). Left margin shows the main tree of ten categories; tapping one makes its subtree grow in and the main tree drop out; a content box (soft radius, `backgroundColourSecondary`) appears on the right, auto-opening the first subcategory. A back button pinned bottom-left walks up one level and closes at the top. The settings tree is declared as data (`SettingsTree.kt`) so later versions fill one content slot without touching navigation.
- **Grow-from-bar animation** — the panel rolls out from the bar's edge like a blind, on the shared `backgroundColourPrimary` fill so there is no seam, with the bar floating above and staying reachable.
- **User-configurable transition length** — a new Appearance setting (`LocalTransitionMillis`; presets INSTANT / FAST / NORMAL / SLOW / CINEMATIC, default 450 ms), driving the panel open/close and the subtree slide. In the legacy panel for now; gains its shell home at 1.5.3.
- **Theme token reconciliation** — `DashColors` → `DashTheme` (colours **and** font). Nine tokens: background/text/icon/accent × primary/secondary, plus `font`. The old four `bar*` tokens retired and every call site swept over. A documented primary/secondary pairing rule.
- **Font tied together** — every DASH chrome string moved onto the `font` token (the module-panel placeholder and the token default excepted, both deliberately), so a single v2 font setting will change all typography with no per-screen work.
- **Settings button rewrite** — a real vector gear (`ic_settings_gear.xml`) filling its cell, tinted `iconColourPrimary`, replacing the small text glyph; it now toggles the panel open/closed.
- **Module-panel placeholder** — a throwaway dark box (`ModulePanelPlaceholder.kt`) with an expand/minimise toggle, so the panel can demonstrate conforming to the module panel: it covers a minimised panel and yields to an expanded one. Deleted wholesale at 1.6.x. Its own font is intentionally *not* DASH-themed — the module is king in its domain.
- **Legacy bridge** — a temporary LEGACY SETTINGS button in the shell opens the old flat panel, keeping the 1.1.x–1.4.x controls reachable until rehomed. Removed at 1.5.12.
- **Font-scale-aware nav** — the left column width, row spacing and row padding scale with Android's font-size setting so labels never wrap or crowd at large fonts.

**Regressions:**

- The first grow animation ignored the transition-length setting entirely — INSTANT and CINEMATIC looked identical.
- The close felt like it lingered after the fade was removed from the open.

**Fixes:**

- **Transition duration ignored** — root cause was `AnimatedVisibility` + `expandVertically` not honouring its tween when the container is forced to `fillMaxSize` (the fixed max-height constraint defeats the expand, so it snaps). Replaced with an explicit measured-height reveal (`BoxWithConstraints` + `animateDpAsState`, clipped) that is genuinely duration-bound. INSTANT → CINEMATIC now spread correctly.
- **Lingering close** — was a full-length opaque shrink once the fade was gone; now open and close both use the transition length symmetrically (earlier settled on a quick close first, then made symmetric at Roger's request).

**Outstanding:**

- The settings-panel landing is intentionally bare (the **settings hero** — a living silhouette / weather / art-deco god-ray scene — was designed this session and parked to version 2; see roadmap v2).
- The DASH UI Scale token is plumbed but unconsumed and has no slider — deferred to 1.5.3 (Appearance › Density & Scale); new surfaces should bind to it as they are built.
- Real settings content is deliberately absent — arrives per the 1.5.3+ rehoming sequence.

**Notes:**

- **Version bump:** `versionName` 1.5.1 → 1.5.2, `versionCode` 21 → 22.
- **interface.md** gained two dated 1.5.2 addenda (additive, originals kept): the reconciled nine-token set with the pairing rule under Theme Tokens, and the two-pane navigation + roll-out + panel-bounds notes under Settings Panel.
- **Element font sizing** was clarified as the element author's domain (recorded): `sp` tracks Android's font scale, `dp.toSp()` frees it — DASH must not impose a policy. The alerts area still tracks the Android font size by default; releasable any time by unit choice, no architectural change.

---

## Version 1.5.1

**Status:** Complete — planning and documentation only, no code. The **first version of the 1.5.x Settings Panel era**: a reconciliation pass that settles the settings tree before any of it is built. No compile, no APK, no reflash — verified by review of the two Bible documents.

**Scope:** Reconcile the settings structure against everything actually built in 1.1.x–1.4.x and against the 1.5.x implementation plan, edit the two Bible documents to match, and resolve the four handoff observations raised when the 1.4.x era closed. Prompted by discovering that a full settings tree already existed in interface.md and had drifted from both the code and the plan.

**What was done:**

- **roadmap.md — the 1.5.x section rewritten** (old entry deleted). The flat "what gets built" bullet list became the reconciled top-level tree plus a twelve-version build sequence (1.5.1 reconciliation → 1.5.12 cleanup), governed by one principle: **a work-in-progress tab is a placeholder, not a version.** The full navigation tree is built once in the shell version (1.5.2); numbered versions after it go only to tabs with a live feature to wire; each placeholder lights up at its own feature's version (Module Panel 1.6.x, Viewport 1.7.x, App Launcher 1.8.x, Elements 1.9.x, theming/overlays/audio/notifications/apps across v2, vehicle/CAN in v3). No reserved-empty slots — refinements renumber, as 1.4.x did when Bluetooth took 1.4.12.

- **interface.md — a dated reconciliation addendum added** beneath the original settings tree (the original kept, per the additive-docs rule). It records six changes and why each: **(1)** a new top-level **Layout** category holding the five placeable surfaces (System Bar, Module Panel, App Launcher, Elements, Overlays), lifted out of Appearance; **(2)** the old `Appearance → Panels` node dissolved into Layout; **(3)** Overlays split by concern — appearance under Layout → Overlays, trigger mapping under Notifications; **(4)** the System Bar settings entry corrected to *Position + Edit Bar Layout entry* (height/zones/element sizing live inside edit mode, per 1.3.9/1.3.13); **(5)** the redundant Spacing subcategory dropped; **(6)** the WIP-placeholder convention recorded. It closes with the reconciled top-level structure as a self-contained tree.

- **The four handoff observations, resolved:** the Transports tab is now a generic list off `TransportManager` (not a hardcoded two/three); "enable/disable per module" is recognised as a new stored-intent concept and filed at 1.5.7 with its design decision flagged; the stale Appearance bar-height line is corrected to the edit-mode entry point; and Module Management is scoped as a *rehome, not a rebuild* (1.5.6).

**Decisions taken this session (with Roger):**
- **Layout as a new top-level category, not a nested "UI" subcategory** — nesting it under Appearance would have added a fourth navigation tier the three-level model can't show, and pushed System Bar two taps deep. Top-level keeps the nav honest and de-loads Appearance (which was carrying eleven subcategories). This is the Module Mantra as a settings tree — the panel is a wall DASH owns (Layout), the module is the king inside it (Modules).
- **A WIP tab is a placeholder, not a version** — the tidying principle that took the plan from 35 versions to twelve, each doing verifiable work.
- **Notification suppression → Notifications, capability-detected** — moved out of Appearance; it needs elevated access a Bronze sideload lacks, so it unlocks on Silver/Gold and degrades silently on Bronze. Not a plain preference toggle.
- **The System category re-added** (Android deep-links + About DASH) at 1.5.11, near the end of the era. Vehicle, Audio, Notifications and Apps also re-included from interface.md as WIP shells.

**Regressions:** None — no code changed. `versionName`/`versionCode` bumped and two Markdown documents edited; the app is byte-for-byte unchanged in behaviour.

**Outstanding / deferred (with homes):**
- **1.5.7 module-disable semantics** — the user-facing disable (a stored intent that withholds ACTIVATE, close to the 1.4.13 quarantine) needs its design pinned down before that version starts.
- **Viewport settings home** — Viewport has no settings subcategory for now; its mode/corner-radius/shadow controls arrive with 1.7.x, and whether they land in Appearance (as a look) or Layout (as a surface) is decided then.

**Notes:**
- **Version bump:** `versionName` 1.4.16 → 1.5.1, `versionCode` 20 → 21. No functional Android change — the version tracks the project so build.gradle stays in step with the changelog (the 1.4.15 precedent); the tablet needs no reflash.

---

## Version 1.4.16

**Status:** Complete — **the cleanup pass that closes the Transport Layer era**, verified on real hardware (2026-07-16): after a cold tablet reboot the modules come alive with **no delay** — the discrete signals change on their normal cadence from the first second instead of frozen for a minute — and the Serial Monitor's per-device labels and swapped arrows read correctly. The fourteenth and final version of the 1.4.x Transport Layer era.

**Scope:** The three deferred niceties from the build — USB hot-plug reconnection reliability, per-device labelling of inbound wire-log lines, and greet-on-device-count-change. What began as "harden the USB reconnection path" turned, under instrumentation, into the discovery that the reconnection was never the problem — the real bug was in the module firmware, and the fix landed in the SDK library, not the transport.

**The headline — the reconnection "bug" was a module-side ACTIVATE-idempotency bug.**
The reported symptom (a board sometimes "takes ages" / seems absent after a reboot) was chased into the USB transport with on-wire logging and *ruled out there*: the transport connects and has the module's `HELLO` in ~2 seconds, cleanly, every time. The delay was elsewhere — and the tell was that only the *discrete, occasional* signals (gear, headlights, door) lagged while the *continuous* ones (speed, ambient) stayed fresh. On the wire: the module heartbeats every 5 s like clockwork and DASH's change detection is flawless — but the value simply didn't change for ~52 s, then broke free at exactly the 60 s mark.

Root cause: the reconciliation desk **re-asserts `ACTIVATE` every sweep** (every 5 s for the first 60 s, then 30 s) as its proof-of-life ping — by design (1.4.6), and DASH's own code calls it "idempotent for the module." But the module wasn't idempotent: on **every** `ACTIVATE` it re-ran its whole activation — the §4b dump, the heartbeat-clock reset, and the builder's `onActivate` hook that reseeds the send-timers (`lastGear`, `lastLights`, `nextDoorFlip`). So during the fast-sweep phase, `ACTIVATE` every 5 s kept resetting those timers, and any signal whose change interval is longer than a sweep never reached it — frozen for the whole ~60 s fast phase after every connect, cold-boot, or reconnect.

**The fix — in the `DashModule` library, not the app:**
- **`onActivated()` now runs only on the SILENT→ACTIVE transition** (`if (!wasActive)`); the module always `ROGER`s (that *is* the liveness proof DASH wants), but a redundant re-assert no longer disturbs it. One guard, in the one place every library module inherits — the SDKable payoff: it fixes every future community module at once. The 5 s heartbeat already re-sends state, so DASH's store stays fresh without a per-activate dump.
- **`linkLost()` added** — a wireless socket/client drop sends no `DEACTIVATE`, so the WiFi/BT sketches call this on the drop: the module forgets it was active (running the safe-state `onDeactivated` hook) and clears its inbound assembler, so on reconnect it is SILENT until DASH re-DISCOVERs and re-ACTIVATEs it (§6). USB never needs it — a cable pull is a full reboot.

**The reference sketches, rewritten onto the fixed library:**
- All old hand-written reference sketches moved to `arduino/old/` (they carry the same bug, but are deprecated).
- Four new library-based sketches in `arduino/current_sketches/` (one folder each, so the Arduino IDE builds them as separate sketches): **BodyUsb** (Uno R4, USB serial), **BodyWifi** (Uno R4 WiFi, TCP client), **PowertrainUsb** (ESP32, USB serial), **PowertrainBt** (ESP32 classic, BT SPP) — same signals and ids as before, so DASH treats them as drop-in replacements. All four compile (arduino-cli); **PowertrainBt flash-verified on hardware** (the cold-reboot test above).
- The `DashModule` library was symlinked into `~/Arduino/libraries/` so the IDE resolves `<Dash.h>` — a setup step the README/examples work will document for community builders.

**Per-device labelling of inbound wire-log lines (+ arrow swap):**
- `WireEvent` gained a `deviceKey`, threaded from the origin already riding on every inbound frame (1.4.14) and from the target of a per-device `sendTo`. The Serial Monitor resolves it to the device's friendly name (falling back to the raw key), shown as `tag·device` — so with two boards on one pipe their lines can finally be told apart. A broadcast to all devices shows the bare transport tag. This is the inbound half of the addressing the SEND-TO dropdown gave outbound (deferred since 1.4.10).
- **The direction arrows were swapped** at Roger's request: inbound (module → DASH) now points **→ right**, outbound **← left** — how it reads from a bystander's view of the wire. Colours still track direction (outbound cyan, inbound green).

**Greet-on-device-count-change:**
- A new device joining an already-CONNECTED transport doesn't change the aggregate status, so the existing status→CONNECTED sweep trigger never fired for it — the new module waited out the reconciliation timer (up to 30 s in the slow phase). Now `DashController` tracks the present device set and fires `reconciliation.sync()` (→ an immediate `DISCOVER`) the moment a new device key appears. Transport-agnostic — it helps a second USB board, a WiFi module dialling in, and a BT module coming into range alike. The existing install-busy guard still holds, so a greet sweep can never interleave an in-flight install.

**Regressions:** None found. The app changes are additive (a wire-log field, an arrow flip, a sweep trigger); the behavioural fix is in the firmware library.

**Decisions taken this session (with Roger):**
- **Fix the module, not DASH.** DASH's re-assert-every-sweep is the deliberate 1.4.6 liveness design and stays; the module honouring the idempotency DASH already assumed is the correct fix — and it makes the re-assert genuinely harmless where before it silently corrupted timers.
- **Instrument before hardening.** The USB reconnection was chased with temporary on-wire logging (`DashUsb`, `DashBroadcast`) rather than fixed by theory — which is what proved the transport innocent and localised the bug. The logging was stripped once the fix was verified.
- **Arrows follow the bystander's-eye convention** — inbound right, outbound left.

**Outstanding / deferred (with homes) — ALL CLOSED 2026-07-17, see the closing note below:**
- ~~**SDK docs**~~ — **done 2026-07-17.** The ACTIVATE-idempotency rule and the `linkLost()` responsibility are now written into `module-sdk.md` / `arduino.md` §6.
- ~~**`arduino_secrets.h.example`**~~ — **done 2026-07-17.** Committed beside `BodyWifi`.
- **The old hand-written sketches in `arduino/old/`** still carry the bug — **deprecated, not fixed, and staying that way.** Not a 1.5.x hangup: they are superseded by `current_sketches/`, referenced by nothing, and exist only as historical record.

---

### Closing the 1.4.x era — the deferred doc pass (2026-07-17)

The two deferred items above were completed in a docs-only session. **No DASH code changed** — no version bump, no rebuild, no APK. `versionName` stays 1.4.16 / `versionCode` 20.

**The SDK docs — the idempotency rule and the link-drop rule, now stated:**
- **`module-sdk.md` §6** (the locked contract) gained two present-tense rules: **"`ACTIVATE` is idempotent — the module honours the re-assert"** (always `ROGER`; run the activation work *only* on the real SILENT→ACTIVE transition) placed directly after the boot-reconciliation paragraph that describes the re-assert, so a builder reads the behaviour and its obligation together; and **"A dropped wireless link — the module returns itself to SILENT"** placed after "Absent = DORMANT". Both additive — 36 lines added, nothing removed.
- **`arduino.md` §6** (the working record) gained two dated blockquote notes in the existing 1.4.6/1.4.13/1.4.14 style, carrying the *why*: the frozen-signals symptom, the 60 s tell, the blind alley through the USB transport, and Roger's "fix the module, not DASH" call. 49 lines added, nothing removed.
- **Why this mattered more than tidying:** the library fix only protects modules that inherit `DashModule`. A community builder writing firmware from scratch works from the document — and the document described DASH's re-assert without ever stating the module's obligation to tolerate it. It would have led them to rebuild the exact bug, faithfully. The SDKable principle cuts both ways: the contract has to state the duty, not just the behaviour.
- **Judgement call:** `module-sdk.md` is locked at Bible weight, but neither addition changes the contract — both describe behaviour DASH has always had and always assumed. Writing down an implicit obligation is not reopening a decision. Roger approved the drafts before they were applied.

**`arduino_secrets.h.example` — committed, and caught broken by verification:**
- Lives at `arduino/current_sketches/BodyWifi/arduino_secrets.h.example` — **beside the sketch, not in `DashModule/examples/`**, because `BodyWifi.ino` includes it with quotes and the toolchain resolves that relative to the sketch folder. The `.gitignore` rule is `arduino/**/arduino_secrets.h`, which matches the exact filename only, so the `.example` is committable with no change to the ignore rules.
- **The first draft did not compile.** It pasted the gitignore glob `arduino/**/arduino_secrets.h` into a C block comment — the `*/` inside that glob closed the comment early and everything below it was compiled as code. Caught by actually building a simulated fresh clone rather than eyeballing the file; the template now describes the rule in words instead of pasting the glob. Had it shipped, the template would have failed on first use — exactly the problem it exists to prevent.
- **Verified** by copying the sketch to a scratch dir with the real secrets removed: template copied per its own instruction → compiles clean (72116 bytes, `DashModule` 1.0.0 + `WiFiS3`); template *not* copied → fails at `BodyWifi.ino:25` with `fatal error: arduino_secrets.h: No such file or directory`, pointing straight at the include and the FIRST BUILD note above it. `BodyWifi.ino`'s header comment now names the template as step one.

**Raised and declined:** a reorganisation was discussed — gitignoring `current_sketches/`, `old/` and `x-type_steeringwheel/`, and copying the sketches into `DashModule/examples/`. Two findings came out of it and are recorded here so they are not rediscovered: **(1)** all three are already tracked, so gitignoring them alone does nothing — untracking needs `git rm -r --cached`; **(2)** `examples/BodySystem` and `current_sketches/BodyUsb` are already the same module (id `...EE05`, same five signals) at two different vintages, so the sketches have already drifted once, unnoticed. Roger's call: leave it. **Not a 1.4.x hangup** — nothing is broken, the shipped sketches are the ones in `current_sketches/`, and the examples question belongs to whenever the Arduino library is properly published.

**1.4.x is CLOSED.** Every version 1.4.1 → 1.4.16 is complete and hardware-verified. Nothing outstanding carries into 1.5.x — the Settings Panel starts clean.

**Notes:**
- **Version bump:** `versionName` 1.4.15 → 1.4.16, `versionCode` 19 → 20.

---

## Version 1.4.15

**Status:** Complete — **the module firmware library**. SYSTEM and LISTENER shipped as a real Arduino library; the ACCESSORY type deferred to after 1.6.x (see below). **Compile-verified** on the Arduino Uno R4 WiFi *and* a classic Uno R3 (AVR) — but **not flash-tested on hardware**: recorded unverified at Roger's explicit instruction (2026-07-13), on the reasoning that the refactored examples reproduce, message-for-message, the wire output of the hand-written reference sketches that *were* hardware-verified across 1.4.1–1.4.14. Any issue surfaces on the next flash and is fixed then. The SDK-consolidation version of the 1.4.x Transport Layer era.

**Scope:** The three acts of roadmap 1.4.15 — reconcile the working record, lock the SDK, write the library — with the ACCESSORY library split out to follow 1.6.x.

**Act 1 — reconciled `arduino/arduino.md`** with dated additive notes (nothing erased): §6's transport-aware absence is now-built (the orange NOT_RESPONDING state, 1.4.14) and the manual button is **REFRESH**, not SYNC; §6's FORCE UNINSTALL wording corrected to the shipped 1.4.6 model (delete immediately, retry `DEACTIVATE` behind it, warn only after the fact — no blocking step); §7 gained the note that install failure is entirely DASH-side and the module contract is unchanged, and that there is deliberately **no `INSTALL_ABORT`** (decided this version); the cheat-sheet `SUBSCRIBE` line tightened to say the *library* fills defaults; and a lock/promotion header added at the top.

**Act 2 — locked the SDK and promoted it to `module-sdk.md`** (a new Bible document). The transport/lifecycle/message SDK (§1–§10, §12) — built and hardware-verified across 1.4.1–1.4.14 — is locked and rewritten as clean present-tense rules (the amendment archaeology folded into the plain text). Registered in the CLAUDE.md document set with Bible weight. **Deliberately left open:** the ACCESSORY panel/layout spec (§11) and the Open Items, which depend on the module panel (1.6.x) and lock in the panel era — they stay in `arduino.md` as the working record.

**Act 3 — the library, `arduino/DashModule/`** (a proper Arduino library):
- **`DashModule` (core)** — line framing over any Arduino `Stream` (so USB `Serial`, `Serial1`, `WiFiClient`, `BluetoothSerial` all work unchanged), the pipe split, HELLO-on-DISCOVER, the install-handshake wrapper (subclass emits declarations, base always closes with `INSTALL_END`), the SILENT→ACTIVE→SILENT lifecycle with `ROGER`, and forbidden-character stripping on every sent field. Data-only-while-active is enforced for free — every send is gated on `isActive()`.
- **`DashSystem`** — `addSignal()` for the install declarations, and an `onReport()` the library calls on the activation dump and every 5 s heartbeat (§4b). The builder never tracks change or manages the heartbeat clock. `broadcast()` / `event()` for immediacy.
- **`DashListener`** — `subscribe()` (the library fills a blank rate/threshold from the signal table before sending, §4c), and the whole change-detection store absorbed — the builder writes only `onSignal` (called on change) and `onEvent` (event-only on fire).
- **`dash_signals.h`** — the `SUBSCRIBE`-defaults table mirrored from `system_commands.md`. Only the 8 continuous signals need entries (everything else defaults to blank/event-driven, and inbound `LISTEN` distinguishes event-only by value-absence), so it is tiny. Hand-mirrored with a "keep in step with system_commands.md" note; a generator is a future nicety.
- **Examples = the refactored reference modules** — `examples/BodySystem/` (the Body SYSTEM module, id …EE05) and `examples/LedListener/` (a LISTENER, id …EE0B). The diff is the point: all the protocol boilerplate is gone into the library; the builder writes only their hardware. This *is* the SDKable proof — the built-ins use the same library a community builder would.

**Footprint (the leanness proof):** BodySystem builds at **20 % flash / 27 % RAM on a bare Uno R3**; LedListener at 13 % / 45 % (the subscription array reserves `DASH_MAX_SUBSCRIPTIONS` slots up front — tunable). So a SYSTEM/LISTENER module built on the library fits the smallest AVR, no special-casing.

**Decisions taken this session (with Roger):**
- **Promote to a dedicated `module-sdk.md`**, not into `transport.md` — the module SDK is the module-facing contract for community builders, a different audience and concern from DASH's internal transport doc.
- **Lock §1–§10/§12 now, leave §11 + Open Items to the panel era** — you can't lock a panel format you haven't rendered.
- **No `INSTALL_ABORT`** — the blocking handshake stays the model; a cancelled install just finishes into a closed session harmlessly.
- **CRC stays CRC32**; SUBSCRIBE defaults come from a signal table generated from `system_commands.md` (one source of truth).
- **ACCESSORY SDK deferred to after 1.6.x** — its `DashAccessory` helper and the §11 lock ride with the module panel.

**Regressions:** None — this version adds documentation and firmware; no DASH (Android) code changed, so the app is functionally unchanged.

**Outstanding / deferred (with homes):**
- **Hardware flash-test of the library** (SYSTEM + LISTENER) — recorded unverified per above; confirm on the next bench (flash `BodySystem` + `LedListener`, watch the LED follow `headlights_on` relayed through DASH).
- **The ACCESSORY SDK** — `DashAccessory` + locking §11 — after 1.6.x.
- **`dash_signals.h` generator** — nice-to-have; hand-mirrored for now.

**Notes:**
- **Version bump:** `versionName` 1.4.14 → 1.4.15, `versionCode` 18 → 19. No functional Android change — the version tracks the project so build.gradle stays in step with the changelog; the tablet needs no reflash for 1.4.15 (it is verified by flashing Arduinos).

---

## Version 1.4.14

**Status:** Complete — **all three failure paths verified on real hardware over USB** (2026-07-13). A new reference sketch, `test_accessory_big.ino` "Big Test Accessory", ships a ~100 KB icon pack (24 icons × 4 KB + a generated layout) so the install runs ~9 s on the wire — long enough to interrupt. Confirmed on the bench: **CANCEL** mid-transfer reverts the card cleanly with no fail badge and frees the sweep at once; **DISCONNECTED** — pulling the USB cable mid-install fails the card immediately (the fast path, no waiting on the timeout); **STALLED** — with `WEDGE_AFTER_BLOCKS` freezing the module mid-handshake (still connected), the card goes to the stalled fail state after the idle timeout. Thirteenth version of the 1.4.x Transport Layer era.

**Scope:** Designed install failure — the home for the failure work parked since 1.4.4, when only the unavoidable bad-CRC abort was built. An install that stalls, loses its device, or is cancelled now ends with a *designed* surface — a reason and a retry — instead of hanging forever or snapping back silently; and a failed install can no longer freeze reconciliation. Two things were worked out with Roger and folded in: a **CANCEL** button, and Roger's **physical-disconnect trip**. Plus a bonus pass on Module Management: **SYNC became REFRESH**, discovered modules that stop answering are pruned on demand, and installed modules that go silent turn orange.

**The build:**

- **The install desk got a clock.** A per-session **idle** watchdog: silence for `IDLE_TIMEOUT_MS` (10 s) aborts the handshake as **STALLED**. Idle, not a total-duration cap — every declaration and every completed block resets it, so a large asset transfer that is genuinely progressing is never killed. The desk gained a `CoroutineScope` for this; `Install.kt`'s old "there is deliberately no timeout" note is retired.

- **A designed fail state.** `states` broadened from `Map<id, Installing>` to `Map<id, InstallState>` where `InstallState` is a sealed `Installing(progress) | Failed(reason)`, and `FailReason` is `STALLED | DISCONNECTED | CORRUPT`. A failure now leaves a **persistent badge** the card renders with an honest message plus **RETRY** and **DISMISS** — never a silent revert. The 1.4.4 CRC/length abort was promoted from its silent `abort()` to a visible **CORRUPT** fail.

- **CANCEL — a clean revert, not a failure.** A user stop is deliberate, so `cancel(id)` drops the session and returns the card to its plain discovered state with no badge. The module keeps streaming its declaration run to the end; those strays drop-and-log against the now-absent session (the well-mannered path already built). Actually halting the module mid-stream would need a new wire message — deferred to the 1.4.15 SDK lock rather than invented here.

- **The disconnect trip (Roger's idea) — the fast, precise path.** Every inbound frame now carries its **origin**: a new `InboundFrame(frame, transportTag, deviceKey)` envelope, stamped where the frame is assembled (inside the per-device/per-socket connection, which already knows its key) and threaded up through all three transports and the controller. An install session captures its source device on the first declaration; when that device leaves the aggregated transport device list, the session fails **DISCONNECTED at once**, instead of waiting out the idle timeout. The timeout is the backstop for a module that wedges *without* dropping its link. `deviceKey` matches the `TransportDevice.key` each transport already publishes (USB `deviceId`, TCP socket id, RFCOMM address), so the two line up for free.

- **Busy means in-flight, not badged — the 1.4.6 fix.** `installBusy` now reads the live `sessions` map (`install.isBusy()`), never the `states` projection that carries lingering `Failed` badges. A failed or cancelled install can no longer keep the reconciliation sweep paused — closing the "a wedged install pauses the sweep forever" item outstanding since 1.4.6.

- **Wired vs wireless absence.** `DashTransport` gained `val wired` (USB true; WiFi, BT false), surfaced as `TransportManager.wiredTags`. The reconciliation desk remembers each id's last transport (from the HELLO's stamped origin), so a not-responding module's DETAILS words the reason honestly: a wired module reads as a fault ("check power and cable"), a wireless one as ordinary ("may be out of range"). This is where the §6 wired/wireless distinction, parked since 1.4.6, finally lives.

- **NOT_RESPONDING — the orange "vanished on us" state (the REFRESH bonus).** A new `ModuleActivity.NOT_RESPONDING`: an installed module **heard this session and then gone silent** past `ABSENT_MS`. Distinct from DORMANT, which stays calm for a module never seen this session (not plugged in yet / out of range — nothing is wrong). Rendered as an orange **NO REPLY** chip; the wired/wireless wording above explains it in DETAILS. Reconciled with Roger's "orange for any installed non-responder" instruction: the orange is the same for wired and wireless; only the DETAILS reason differs.

- **SYNC → REFRESH.** The Module Management button was renamed and given teeth: beyond an immediate sweep, `refresh()` waits a short grace for the `HELLO`s then **prunes every discovered (not-installed) card that didn't answer this round** — a module that once replied but is gone disappears promptly instead of lingering the full `ABSENT_MS`. Installed cards are never pruned; the same sweep's aging is what turns a silent installed module orange. The automatic sweep on transport-up stays gentle (no hard prune), so a just-connected module isn't pruned before it can answer.

- **Reference sketch — `test_accessory_big.ino` ("Big Test Accessory", …EE0A).** An ACCESSORY that exists to make the transfer interruptible. The icon bytes are **generated** from a hash of (icon index, byte offset), so a ~100 KB transfer costs almost no flash or RAM (the §8 streaming discipline) and spans the full 0–255 byte range (a binary-safe-framing test for free). Tunable `ICON_COUNT` / `ICON_BYTES` for a longer window, and `WEDGE_AFTER_BLOCKS` to freeze the module mid-install so the STALLED path can be seen over USB (a cable-pull gives DISCONNECTED, the fast path). Compiles at 20 % flash / 39 % RAM on the R4 WiFi.

**Decisions taken this session (with Roger):**
- **A physical disconnect stops the install** (Roger's call) — the fast, precise trip, with the idle timeout as the backstop for a link-holding wedge.
- **CANCEL is a clean revert, not a failure** — a deliberate stop wears no fail badge.
- **The REFRESH bonus** — rename, prune discovered non-responders on demand, and turn silent installed modules orange. Orange applies to any installed module seen-then-silent; the wired/wireless split only words the DETAILS reason, not the colour.
- **CANCEL does not message the module** — a wire-level `INSTALL_ABORT` is an SDK-contract change, deferred to the 1.4.15 lock rather than invented ad hoc.

**Regressions:** None. The origin envelope is threaded behind the existing `DashTransport` contract; the wire grammar, the database schema, and the four §5 gatekeepers are untouched. The Serial Monitor (which rides `wire`/`devices`, not `inbound`) needed no change.

**Outstanding / deferred (with homes):**
- **The idle watchdog resets per completed block, not per raw byte.** A single block longer than the 10 s timeout would false-STALL even while its bytes flow. Mitigated by the many-medium-blocks shape (real accessories ship several assets anyway), so the "Big Test Accessory" never trips it. Whether to leave it per-block or reset on raw-byte progress from the frame assembler is a noted design point — leaning leave-it (a >10 s single block is pathological).
- **Failed-update edge.** `updateModule` (1.4.13) uninstalls then reinstalls; if that reinstall now fails, the module is left uninstalled with a fail badge (RETRY recovers it). Honest and recoverable, but the copy isn't update-aware yet — a small follow-up.
- **1.4.13 auto-refresh** is now **unlocked** — the designed fail state it waited on exists — but still deferred, available to pick up.
- *(Resolved 2026-07-13 — all three failure paths now hardware-confirmed over USB: CANCEL, DISCONNECTED, and STALLED. Nothing outstanding on verification.)*

**Notes:**
- Also committed: a small informational **gateway-print** in `arduino_wifi.ino` (debug only, no behaviour change) added while working out the phone-hotspot bench model — `arduino_secrets.h` stays gitignored, so no credentials are committed.
- **Version bump:** `versionName` 1.4.13 → 1.4.14, `versionCode` 17 → 18.

---

## Version 1.4.13

**Status:** Complete — verified on real hardware: the `arduino_wifi.ino` "Body WiFi" module was reflashed from `v1.0` to `v1.1` **without uninstalling**. On reconnect DASH recognised it as the installed module but **held it DORMANT** with an amber **UPDATE** chip, and its live data was **refused** — the door/light/cabin signals stopped appearing in the Signal Monitor. One tap of **UPDATE** re-ran the install handshake, re-captured the contract at `v1.1`, and the module came back **ACTIVE** and streaming. Twelfth version of the 1.4.x Transport Layer era.

**Scope:** Firmware version mismatch — DASH notices when a module reports a firmware version different from the one captured at install, and treats the stored install contract as untrustworthy until the module is re-installed.

**The build:**

- **Detection, in the reconciliation desk.** The desk already matched every `HELLO` against the installed database by id to re-assert liveness; it now also compares the sixth `HELLO` field (`version`) against the stored record. **Difference only** — the version is builder free text with no ordering contract (arduino.md §10), so DASH reads it as *same* or *different*, never newer/older. The comparison is taken only from a **well-formed** `HELLO` (via the strict six-field parse), so a truncated line can never raise a phantom mismatch. A new `versionMismatch: StateFlow<Map<id, reportedVersion>>` carries the result.

- **Rejection, not a warning — Roger's call during the bench.** The original plan was "surface but keep working." On hardware Roger saw the mismatched module's data still arriving at the Signal Monitor and ruled that a mismatch must be a **hard rejection** — the version is only worth tracking if a difference actually withholds trust. So a mismatched module is **quarantined**: the desk **does not send it `ACTIVATE`**. That one decision is the entire enforcement — the module is held `DORMANT`, and all four §5 gatekeepers (broadcasts, reports, streams-out, actions-out) already refuse a module that isn't `ACTIVE`, so its traffic is rejected in **both** directions with **no change to any gatekeeper**. And because a compliant module boots SILENT and only speaks after `ACTIVATE`, a quarantined module never transmits at all — the rejection is enforced at the source, not just at the wall. Two defensive guards keep the invariant airtight: `sweep()` forces a mismatched id `DORMANT` regardless of recency, and `onRoger` refuses to let a stray activate-ack wake a quarantined module.

- **One-tap update — `DashController.updateModule(id)`.** A firmware update *is* a reinstall (§6: the module persists nothing): forget the stale record, clear the flag, re-run the install handshake — which re-seeds from the module's live `HELLO` identity (the new firmware's) and re-captures its declarations, then commits and `ACTIVATE`s. The controller is the one place holding both the database and the install desk, so the orchestration lives there. Deliberately sends **no** `DEACTIVATE` first (unlike a user uninstall): the module is about to be reactivated, and a late DEACTIVATE retry could otherwise land after the fresh `ACTIVATE` and wrongly silence the just-updated module. Opening the re-install session sets `installBusy()`, so the sweep holds off on its own during the handshake.

- **Surfacing.** An amber **UPDATE** chip on the installed card (beside the DORMANT/type chips), and in DETAILS a *FIRMWARE VERSION MISMATCH* banner showing `installed` vs `reporting` side by side with the reason — *"Held inactive — its data is refused until it is updated."* — plus a one-tap UPDATE button. The flag **self-heals**: once the module reports the new version that matches the freshly stored record, the next `HELLO` clears it.

- **arduino.md §6 gained the version requirement** (dated additive note): the `version` field is load-bearing, DASH re-checks it every reconnect and quarantines on difference, and the builder must **bump `version` whenever the declared interface changes** (a signal/subscription/asset added, removed, or altered) so DASH knows to re-capture. The §10 field-caps table row for `version` now points at it.

- **Reference module.** `arduino_wifi.ino` "Body WiFi" bumped `MODULE_VERS` `v1.0` → `v1.1` to drive the bench (its stored record was captured at v1.0 in earlier versions, so the reflash produces a genuine mismatch).

**Decisions taken this session (with Roger):**
- **A version mismatch is a hard rejection**, enforced by withholding `ACTIVATE` — quarantine the module in both directions until updated, rather than flag-and-continue.
- **Withhold activation rather than gate each desk** — one decision at the wake-up point rejects the module everywhere, with no gatekeeper changes, and stops a compliant module transmitting at the source.
- **No `DEACTIVATE` on update** — the module is being refreshed, not removed, and a late hush could silence the fresh install.

**Regressions:** None. The change is contained to the reconciliation desk, one new controller method, and the Module Management UI; the wire grammar, the database schema, the install desk, and the four gatekeepers are all untouched.

**Outstanding / deferred (with homes):**
- **Auto-refresh** (DASH re-running the handshake unprompted on a detected mismatch) remains deferred until the install-failure work (1.4.14) exists — auto-triggering installs with nobody watching invites the wedged-install problem. Revisit after 1.4.14.

**Notes:**
- The builder-facing "bump your version when the interface changes" rule is a **new module-facing requirement**, captured in arduino.md §6 now; its promotion into transport.md or a module-rules doc rides with the SDK lock at 1.4.15.
- **Version bump:** `versionName` 1.4.12 → 1.4.13, `versionCode` 16 → 17.

---

## Version 1.4.12

**Status:** Complete — verified on real hardware: an Espressif ESP32 DevKitC running the new `esp32_bt.ino` "Powertrain BT" reference module reached DASH **over Bluetooth Classic (SPP)** — paired once in Android's settings, then DASH connected out to it and ran the full `DISCOVER → HELLO → INSTALL → ACTIVATE → ROGER` handshake and the live BROADCAST stream, all intact over the air. Run alongside the WiFi "Body" and a USB board it gives the genuine **three-transport bench — USB, WiFi and Bluetooth live at once** — the strongest proof yet that nothing above the transport layer cares which pipe carried a message. Eleventh version of the 1.4.x Transport Layer era.

**Scope:** The Bluetooth Classic (SPP) transport — the third `DashTransport`, the wireless sibling of WiFi. Its job is the same proof WiFi gave, one level stronger: a third, differently-shaped pipe slotting in behind the 1.4.1 abstraction with a single line changed above the transport layer.

**The build:**

- **`BluetoothSppTransport`.** The shape is **USB's, not WiFi's**: Bluetooth Classic has no inbound listener, so — like USB — DASH connects *out*, but with no bus to enumerate the "which devices?" answer is the set of **bonded (paired)** devices. Pairing happens once in Android's own Bluetooth settings (transport.md); DASH never pairs programmatically. So the transport runs the same idempotent re-sweep USB does: every few seconds it looks at the bonded set, keeps the DASH modules, and opens an RFCOMM socket to any not already connected. One RFCOMM link is one "device," each with its own reader coroutine and — the 1.4.10 hard requirement carried from cables and sockets to RFCOMM — **its own `FrameAssembler`**. `send` fans out, `send(key)` targets one, `incoming` merges, `status`/`devices` aggregate. The first connect to an ESP32 often fails once (`read failed … ret: -1`) and succeeds on the sweep's retry — normal RFCOMM behaviour, handled by the retry loop, no special-casing.

- **The name marker — `D.A.S.H`.** Classic Bluetooth doesn't advertise a service UUID the friendly way BLE does, and querying one needs a flaky SDP round-trip, so DASH identifies its modules by **device name**: it dials only bonded devices whose name contains the token `D.A.S.H` (the Classic analogue of BLE's DASH service-UUID filter that transport.md describes). The token is the product name — effectively unique, so it excludes every non-module bonded device (phones, headsets, and crucially in a car, **dashcams**, which a bare "DASH" substring would have caught). A builder names their module `D.A.S.H-Powertrain` or similar; the handshake is still the final judge, so a mis-named device costs at most one wasted connect attempt. Chosen with Roger over the plain word and over an SPP-UUID filter.

- **Capability detection / graceful degradation.** On API 31+ `BLUETOOTH_CONNECT` is a runtime (dangerous) permission — requested once on start from `MainScreen`; if denied the transport reports PERMISSION_REQUIRED and keeps sweeping, recovering the moment it's granted. No adapter ⇒ "Bluetooth unavailable"; radio off ⇒ "Bluetooth off"; every path is a quiet status and a running transport, never a crash. **`BLUETOOTH_SCAN` was deliberately not requested** — DASH only ever touches already-bonded devices and never scans, so it isn't needed (a reasoned trim of the roadmap's "CONNECT/SCAN" wording).

- **`TransportManager` — one line.** Registering the transport in the list is the *entire* change above the transport layer, exactly as WiFi was in 1.4.11. Controller, sourceless core, install, database and reconciliation untouched; the Serial Monitor's per-transport status flow (built in 1.4.11) rendered the third `BT` chip with no UI change at all — the payoff of that generic loop.

- **Manifest:** `BLUETOOTH_CONNECT` (runtime, API 31+), legacy `BLUETOOTH` (`maxSdkVersion="30"`), and a non-required `android.hardware.bluetooth` feature so DASH stays installable on Bluetooth-less devices (degrading to "unavailable").

- **Reference module — `esp32_bt.ino` ("Powertrain BT", id …EE08).** The Bluetooth twin of `esp32.ino`: identical Powertrain logic, message layer byte-identical, only the transport object swapped (`Serial` → a `BluetoothSerial` running SPP). Names itself `D.A.S.H-Powertrain`, and goes **SILENT the instant `SerialBT.hasClient()` drops** so a reconnect waits for DASH to re-DISCOVER/ACTIVATE (§6). Carries a board note: Classic SPP exists only on the original ESP32 (WROOM-32) — the S3/C3/C6 are BLE-only and cannot run it.

- **arduino.md gained §12, "Transports — how a module reaches DASH"** — the per-pipe builder checklist (USB, WiFi, and the substantive Bluetooth part: Classic-only board, the `D.A.S.H` name marker, bond-in-Android-settings, go-SILENT-on-drop). Additive, dated; nothing above it changed. (Full reconcile and promotion of these facts remains roadmap 1.4.15's job.)

**Decisions taken this session (with Roger):**
- **The `D.A.S.H` name marker**, as above — over the bare word "DASH" (dashcam collision) and over an SPP-UUID filter (flaky SDP; a name marker excludes non-modules more precisely and for free).
- **`BLUETOOTH_SCAN` not requested** — bonding is done in Android settings, so DASH never scans.
- **BLE deliberately not this version** — its GATT/characteristic/MTU-chunk model doesn't fit the line grammar; it is its own later, more complex transport.

**Regressions:** None. The transport is additive; the only change above the transport layer is the one-line registration.

**The bench bug — an id collision in the reference sketch, found and fixed during on-hardware verification.** On the bench the BT module connected and streamed cleanly but **SYNC never revealed it**. Live logcat proved the transport was correct end-to-end (clean HELLO, ACTIVATE, ROGER, BROADCAST over SPP) and pointed at the real cause: the `esp32_bt.ino` sketch had been given `…EE07`, the id the WiFi "Body" already owned. DASH keys everything by module id, so the two wireless boards were one identity — the WiFi Body had installed first and claimed the record, so reconciliation just re-`ACTIVATE`d the BT board as that module instead of surfacing it as a new discovery. Fixed by moving the BT sketch to `…EE08` (bench map now: Body USB …EE05, Powertrain USB …EE06, Body WiFi …EE07, Powertrain BT …EE08). The transport code was never at fault. A one-off `eng`-truncated BROADCAST seen early did not recur across sustained streaming — a transient, not a framing fault.

**Outstanding / deferred (with homes):**
- **New-device greet latency** — carried from 1.4.11 and transport-agnostic (a second device connecting while another is up waits for the periodic sweep); its fix (sync on device-count change) is filed under the 1.4.16 cleanup pass.
- **BLE** — a future transport of its own, not scheduled here.

**Notes:**
- **The name marker is a new module-facing rule** (how a BT module must be named to be found). It is captured in arduino.md §12 now; its promotion into transport.md or a module-rules doc rides with the SDK lock at 1.4.15.
- **Assigned bench ids must be kept unique by hand** — the collision above is a bench-only artifact; real modules read a factory-unique MAC (§3), so ids never clash in the field. The `esp32_bt.ino` id comment now spells out the whole bench map to stop it recurring.
- **Version bump:** `versionName` 1.4.11 → 1.4.12, `versionCode` 15 → 16.

---

## Version 1.4.11

**Status:** Complete — verified on real hardware: the Arduino Uno R4 WiFi running the new "Body WiFi" reference sketch connected to DASH's TCP server over WiFi (home network, DASH at 192.168.1.77:3274) and came up as a module **over the air** — the first time a module has reached DASH over anything but a cable. Tenth version of the 1.4.x Transport Layer era.

**Scope:** The WiFi TCP transport — the second `DashTransport`, and the version whose entire job is to prove the 1.4.1 pluggable-transport abstraction is genuinely transport-agnostic. A module that arrives over a socket runs the identical lifecycle as one on a cable, because everything above the transport layer routes by module id and neither knows nor cares which pipe carried the bytes.

**The build:**

- **`WifiTcpTransport`.** The inversion vs USB is the heart of it: on USB, DASH is the host that enumerates and opens devices; on TCP, DASH runs a **server** on a fixed port and modules are the **clients that connect in** (transport.md's WiFi model). One accepted socket is one "device," each with its own reader coroutine and — the 1.4.10 hard requirement carried from cables to sockets — **its own `FrameAssembler`**, so an asset BLOCK from one client can't flip another's live bytes into byte-count mode. `send` fans out to all clients, `send(key)` targets one, `incoming` merges, `status`/`devices` aggregate. It binds regardless of network state (WiFi off ⇒ NO_DEVICE "Listening on …", never a hard failure — the graceful-degradation principle), and surfaces the tablet's LAN IP in its status detail so a module author knows where to point firmware. A dropped client closes only that connection; the module goes SILENT and reconciliation re-greets and re-activates it when it returns — the same self-heal a brown-out gets, arriving free from the lifecycle.

- **The port: 3274** — "DASH" on a phone keypad (D-A-S-H → 3-2-7-4). Unprivileged (>1024), honouring the no-root constraint. This is the number every WiFi module's firmware targets from now on (transport.md).

- **`TransportManager` — one line.** Registering the transport in the list is the *entire* change above the transport layer, which is exactly the proof the version set out to give. The controller, sourceless core, install, database and reconciliation are untouched. The `combine`-over-the-list aggregation already handled a second transport with nothing to change.

- **`INTERNET` permission** (normal, auto-granted) added to the manifest.

- **Per-transport status in the Serial Monitor (a fix made mid-session).** The monitor showed a single *merged* status line that collapsed to the liveliest transport — so USB always won it and the WiFi "Listening on <ip>" was invisible whenever a USB board was plugged in (and even when idle, since USB sorts first). It cost a real "I can't see the IP" moment on the bench. Fixed properly: `TransportManager` now exposes each transport's own tagged status and the monitor renders one line per pipe (`USB CONNECTED · …` / `WIFI NO DEVICE · Listening on …`). Correct behaviour for a two-transport world regardless.

- **Reference module — `arduino_wifi.ino` ("Body WiFi", id …EE07).** The WiFi twin of `arduino.ino`: same five signals, same §4b discipline, message layer byte-identical (a `WiFiClient` is an Arduino `Stream`, so only the `DASH_LINK` alias changed from `Serial` to `client`). Its one added job is `maintainLink()` — associate to WiFi, connect to the DASH server, and go **SILENT on any drop** so a reconnect waits for DASH to re-DISCOVER/ACTIVATE (§6 already demands it, so a dropped socket needs no special handling). Serial is freed for human-readable bench debug. A distinct id (…EE07, after EE05 Body-serial and EE06 Powertrain) so it coexists with the serial Body without a §3 clash. Credentials live in `arduino_secrets.h`, **gitignored**, so a real WiFi password never lands in the public repo.

**Decisions taken this session (with Roger):**
- **Port 3274**, as above.
- **A distinct module id for the WiFi Body** rather than reusing …EE05, so both transports' Bodies can be present at once without a §3 id clash.
- **The "MODULE SETUP" convenience button was designed and deliberately parked** to Version 2 (dedicated hardware). A Serial Monitor button emitting a paste-ready `arduino_secrets.h` block: IP and port are readable now, but reading the tablet's *own hotspot SSID/password* needs a system-level permission Android denies a sideloaded (Bronze) app — so it becomes a natural capability-detection feature (auto-fill where privileged, placeholders where not), which belongs with the system-app production hardware. Noted in the roadmap's Version 2 area.

**Regressions:** None. The new transport is additive; the only change above the transport layer is the one-line registration plus the additive per-transport status flow.

**Fixes:** The merged status line masking the WiFi IP (above) — found and fixed within the session, before it could count as a defect in a shipped version.

**Outstanding / deferred (with homes):**
- **New-client greet latency.** A newly connected WiFi client is greeted by the reconciliation `DISCOVER` sweep. The *first* module in a session is greeted instantly (it flips the aggregate status NO_DEVICE→CONNECTED, firing an immediate sync); a *second* connecting while another is already up waits for the periodic sweep (≤30 s steady-state). Pre-existing and transport-agnostic — USB has it too — so it is noted, not fixed here; the proper fix (sync on device-count change) helps both transports and is its own small job.
- **The MODULE SETUP button** — parked to Version 2 (above).

**Notes:**
- **The in-car networking answer, worked out this session.** With the tablet on cellular (SIM) internet and no WiFi network to join, the module network is provided by the *tablet itself*: enable the tablet's WiFi hotspot and the Arduino joins that, while the SIM keeps carrying internet (standard tethering — cellular uplink, local WiFi AP, one radio doing both). Because the tablet is then the access point, its IP is fixed (a stable hotspot gateway such as 192.168.43.1), so `DASH_HOST` stops being a moving target — more stable than joining a shared router. The same idea scales to a dedicated in-car AP or the production board's own SoftAP; wired transports remain the answer for permanently-installed modules. Recorded because it is the real deployment model for every wireless module.
- **Bench heads-up carried in the sketch comments:** if the R4 is powered from the tablet's own USB it also enumerates as a CDC serial device, so DASH's USB transport opens it and sees its debug chatter (harmless, dropped-and-logged) — a pure-WiFi test powers it elsewhere.
- **Version bump:** `versionName` 1.4.10 → 1.4.11, `versionCode` 14 → 15.

---

## Version 1.4.10

**Status:** Complete — verified on the tablet with two real boards on a **powered** hub: an Arduino Uno R4 WiFi (CDC-ACM) and an Espressif ESP32 DevKitC (CP2102) held open **simultaneously** — DASH reading "connected · 2 devices @ 115200 8N1", the first time the multi-device path has ever run. The Signal Monitor shows the standard vocabulary filling from both boards at once (including `ambient_temp` fed by *both* — the sourceless core taking redundant sources by design); the Serial Monitor's SEND TO dropdown lists both boards; and each board, once authorised with "use by default", stops re-prompting on replug. Ninth version of the 1.4.x Transport Layer era.

**Scope:** Multi-device support — the transport layer stops grabbing only the first device and now opens, addresses, and frames *every* attached serial device at once. Built alongside it: the **Signal Monitor** (a live board of every system message and its state — this replaced the planned Devices view), the Serial Monitor **device selector**, and the **USB one-time permission grant** (pulled forward from the pre-1.5 cleanup). The simulation scaffolding stood up in 1.4.7–1.4.9 was removed first. Two new reference SYSTEM sketches (one per board) were written to exercise it all on real copper.

**The build, in order:**

- **Sim scaffolding removed first.** Taken out: the whole `transport/sim/` package (`SimulatedModuleTransport` and the three virtual modules — Sim Vehicle, Sim Accessory, Sim Relay) and the `StateInspectorScreen` dev instrument in its entirety (Roger's call — remove it with the sim rather than keep its data panes), plus all their wiring (`TransportManager.simulated` and its transport-list slot; the State Inspector settings button and MainScreen overlay). The routing machinery they exercised is untouched — the six desks, `SystemState`, `ModuleData`, the `actions` desk, install, database, and reconciliation all remain. From here, real hardware is the verification surface.

- **Multi-device `UsbSerialTransport`.** Was single-device by construction (one `port`, one `ioManager`, one global `assembler`, `connectFirstAvailable()`). Now a per-device inner `DeviceConnection` — each owning its own port, its own IO thread, and **its own `FrameAssembler`** (the hard requirement of this version: a block transfer on device A can no longer flip device B's live bytes into byte-count mode). Held in a `deviceId`-keyed map. The sweep opens *every* attached device; `send()` fans out to all; `incoming` merges all; `status` and a new `devices` list aggregate across them. Permission, detach and read/write errors are all per-device — one board misbehaving never takes another down. The layer above is untouched: DASH still addresses modules by id and broadcasts, so a module behind either cable just answers with its id (transport.md).

- **Inclusive per-device driver resolution — no ESP32 board locked out (Roger's requirement).** Each device is resolved on its own merits, never all-or-nothing across the bus: the known VID/PID table first (CP210x, CH34x, FTDI, PL2303, recognised CDC), then CDC-ACM *only if the device genuinely exposes a CDC interface* (`CdcAcmSerialDriver.probe`). That admits every native-USB ESP32 (S2/S3/C3/C6) not in the table while leaving non-serial peripherals — a hub's card reader, Ethernet adapter, billboard — untouched. The old all-or-nothing fallback would have silently dropped a recognised board's unrecognised neighbour; per-device resolution fixes that, and it was proven live (the ESP32's CP2102 opened while a hub's card reader/Ethernet were correctly ignored).

- **Signal Monitor (replaced the Devices view).** Roger's call: Module Management already answers "what modules do I have," so a physical-device list was thin — a live board of *system messages* fills the real gap the deleted State Inspector left. `SignalMonitorScreen` lists every standard signal (`SystemCommands.allFunctions()`, the in-code copy of `system_commands.md`) against its current value in `SystemState`. Two columns — message + state — plus a faint age; unheard signals show "—" and sit dimmed. Deliberately reads the store *only*: sourceless by design, so it shows the latest value whoever sent it — which is exactly what makes the two-boards-one-`ambient_temp` demo legible. Deliverer/subscriber columns were considered and cut to keep it lean (they would have needed a source-diagnostic record and the subscriptions desk; parked). Reached from settings beside the Serial Monitor; migrates to Developer → Signal Monitor in 1.5.x.

- **Serial Monitor device selector.** The `DashTransport` contract grew a `devices: StateFlow<List<TransportDevice>>` and a targeted `send(line, deviceKey)` (default: broadcast, for transports that don't distinguish devices). `TransportManager` merges devices across transports and routes a targeted send back to the owning pipe (`sendTo`). The monitor gained a "SEND TO" dropdown defaulting to **All devices** (broadcast — what the controller does), with each connected board listed; picking one talks to it alone, and the selection falls back to All if that board is unplugged. Scope note: this governs *sends* only — per-device labelling of *inbound* lines in the wire log was deferred (Roger: "sort it properly when we get into settings").

- **USB one-time permission grant.** Was runtime `requestPermission` only, which on a sideloaded app re-prompts on every replug. Added a `res/xml/device_filter.xml` (standard serial VIDs, decimal per Android convention) and a `USB_DEVICE_ATTACHED` intent-filter on `MainActivity`, which makes Android offer "use by default for this USB device" on first attach. Ticked once per device, it authorises that board persistently — no more re-prompts. One-and-done is the floor: Android forbids zero-consent for a sideloaded (Bronze) app; silent anyway on a system-app production board. The runtime path stays as the fallback for unlisted chips.

- **Two new reference SYSTEM sketches, one per board.** The old `test_*` sketches were flattened into `arduino/old_test/`. New: `arduino/arduino/arduino.ino` ("Body" — id …EE05: doors, lights, `ambient_light`, `ambient_temp`, `button_home_pressed`) and `arduino/esp32/esp32.ino` ("Powertrain" — id …EE06: `gear_position`, `vehicle_speed`, `engine_rpm`, `ambient_temp`, `media_next`). Genuinely different sensor sets on different chips, with `ambient_temp` deliberately shared so the sourceless core visibly takes it from both. Both follow full firmware discipline (SILENT until ACTIVATE, §4b dump + heartbeat, all three §5a behaviours), board differences real (R4 seeds from a floating analog pin, ESP32 from its hardware RNG).

**Decisions taken this session (with Roger):**
- **Devices view dropped for the Signal Monitor** — Module Management already covers "what modules," a message board covers what was missing.
- **Signal Monitor kept to message + state** — deliverer/subscriber cut; if the deliverer is ever wanted it comes from a *diagnostic* record fed by the source-aware gatekeeper, never by making the core store source-aware (that would undo the redundant-source property this version just proved).
- **Per-device wire-log labelling deferred** to the settings-panel pass (1.5.x).

**Regressions:** None. The multi-device transport is a restructure of the USB transport's internals plus additive contract methods; the controller, core, install, database and reconciliation are untouched.

**Fixes:** None in code. The hardware symptom that dominated the session — "installing the second board kills the first, only ever 1 device" — was diagnosed via `adb`/`dumpsys usb` as a **power** problem, not a bug: an unpowered hub (loaded with its own card reader + Gigabit Ethernet) browning out and making the tablet reset its whole USB host controller when a second board drew current. A powered hub resolved it immediately. The transport was correct throughout — it opened exactly the ESP32 alone ("1 device"), ignored the non-serial hub devices, and went to "no device" only because Android genuinely reported zero.

**Outstanding / deferred (with homes):**
- **Per-device labelling of inbound lines in the Serial Monitor wire log** — the dropdown targets sends but the log still merges both boards under the `usb` tag. Parked to the 1.5.x settings work.
- **The USB device-filter brings DASH to the foreground on a matching attach** (it now "handles" those devices). Harmless as the home shell and modules are plugged at power-on anyway; if it ever disrupts in-car app use, a lightweight trampoline activity that grabs the grant and returns to the previous app is the noted refinement.

**Notes:**
- **The powered-hub lesson.** Multi-device bench testing needs a powered hub — an unpowered one can't source two radio-carrying boards off the tablet's battery in OTG host mode, and Samsung's USB protection resets the entire bus rather than just failing the new device. Before the powered hub the ceiling was always "1 device," so the two-device code path had literally never run until this session. Recorded so it doesn't cost a debugging session again.
- **Version bump:** `versionName` 1.4.9 → 1.4.10, `versionCode` 13 → 14.

---

## Version 1.4.9

**Status:** Complete — verified on the tablet (SIMULATOR ON → install Sim Accessory → the MODULE REPORTS pane shows its `test_counter` climbing, the reports that went nowhere until now routed and landing; press **ACTION ▸** → the Serial Monitor shows `ACTION|0000DA5E0002|sim_button` going OUT and `button_presses` climbs by one per press in the reports pane — `button_presses` being itself a `REPORT`, that one number rising proves both halves of the specific column at once: the action reached the module through the gatekeeper, and the module's reply routed back into the per-module store and rendered). Eighth version of the 1.4.x Transport Layer era.

**Scope:** Module message routing — the whole "specific" column of the arduino.md §4 2×2, in and out. `REPORT|id|variable|value` (the module's own private panel data) is routed into a new per-module store, and `ACTION|id|control|value` (a user operating one of the module's panel controls) is emitted back out. This is the private mirror of the general column built in 1.4.7–1.4.8: where `BROADCAST`/`LISTEN` are the shared, sourceless signal bus, `REPORT`/`ACTION` are the module's private one-to-one panel wire. With this version the controller's 2×2 is fully staffed.

**The one architectural point — sourceful, not sourceless:**
- **The id is kept, never consumed.** The broadcast desk (1.4.7) *drops* the sender id so redundant sources for the same signal can coexist — the core is sourceless. `REPORT` is the exact opposite: `temperature` from module X is *that module's panel data* and must never merge with anyone else's, so the id is the store key. `ModuleData` is the sourceful twin of `SystemState`: one small blackboard per module (`id → variable → value`) rather than one shared one. Two modules may each have a `temperature` variable and never collide. That single choice is what 1.4.9 is really about; everything else mirrors 1.4.7.
- **No behaviour lookup, no event bus.** A `REPORT` variable is agnostic — DASH holds no vocabulary for it (that would be reaching into the module's box), so there are no store-only / event-only kinds to distinguish; every report is latest-value-wins. The §4b change check is kept (an ACCESSORY may resend on a heartbeat/dump), but here it only spares the store needless churn — there is no event to gate.

**Design decision taken this session (with Roger):**
- **Build the `ACTION` backend now, ahead of the panel.** The roadmap scoped 1.4.9 to `REPORT` only; Roger asked for `ACTION` too, so the module-panel front end (1.6.x) is "ready to just work" the day it lands. This turned out clean rather than speculative because **DASH cannot and must not validate the control id** — a module's controls live inside its panel assets (BLOCKs), which DASH does not parse until 1.6.x. Validation is the panel's job and it does it for free: the panel only ever renders real controls, so the only ids that can reach `sendAction` are real ones. The desk is therefore deliberately thin — a gatekept emit that stores nothing — and is exactly the seam the panel plugs into: render a control, user taps it, call `sendAction` with the id read from the asset. `TRIGGER` (the other ACCESSORY-out message) was ruled *out* of scope this session and given a home at the new **1.9.x Elements** version — it lands in a shared, origin-aware alert store read by the alerts-area element, so it is element work, not private-panel work (see roadmap, added 2026-07-08).

**Implemented:**
- `ModuleData` (`com.dash.android.core`) — the per-module data store, sourceful twin of `SystemState`. Nested `id → variable → ReportedValue(value, updatedAt)`, in-memory, empty every boot. Deliberately dumb; no event bus. The module panel (1.6.x) is the real reader.
- `ModuleReports` (`com.dash.android.transport`) — the sixth desk, the specific column's inbound half. Same §5 gatekeeper as `Broadcasts` (installed + ACTIVE, else dropped-and-logged) and the same reconciliation liveness feed, but the id is **kept** as the store key. §4b change check drops unchanged repeats before they touch the store. Wired into `DashController.route()` on the `REPORT` TYPE word.
- `Actions` (`com.dash.android.transport`) — the specific column's outbound half, the mirror of `ModuleReports` as `Streams` is of `Broadcasts`. `sendAction(id, control, value)` emits `ACTION|id|control|value` (value optional — omitted for a momentary control), gatekept to installed + ACTIVE, storing nothing. Not in `route()` — driven by a user gesture, not an inbound line, so it is (like `Streams`) one of the two desks that produce without consuming the wire.
- `DashController` — staffs `moduleData`, `moduleReports`, and `actions`; class doc updated to note the 2×2 is now fully staffed and which two desks sit outside `route()`.
- `VirtualModule` (sim base) — an `ACTION` branch in `onLine` and an overridable `onAction(control, value)` hook (default no-op — a SYSTEM/LISTENER module owns no panel).
- `VirtualAccessoryModule` ("Sim Accessory") — now the 1.4.9 round-trip rig: declares one control (`sim_button`) in its layout asset, and on `ACTION|id|sim_button|…` acts and reports `button_presses` back, closing the loop on the wire. Its 2 s `test_counter` reports — unrouted since 1.4.7, deliberately — are now routed.
- `StateInspectorScreen` — a third pane, **MODULE REPORTS** (each active module's variables, name-labelled, value + age), and an **ACTION ▸** bench button standing in for a user pressing the Sim Accessory's control (there is no panel to press yet).
- Version bumped: `versionName` 1.4.8 → 1.4.9, `versionCode` 12 → 13.

**Regressions:**
- None known. The general column (1.4.7/1.4.8), the sourceless core, install, database, and reconciliation are untouched — the specific column is purely additive, a new store plus two desks that reuse the existing gatekeeper and liveness patterns.

**Fixes:**
- None — clean first build.

**Outstanding / deferred (with agreed homes):**
- **`ModuleData` does not clear a module's entries on uninstall mid-session.** Consistent with `SystemState`, which also never clears itself mid-session; harmless because the store is in-memory (gone on restart), keyed by id, and only installed modules are ever rendered. Can be wired to the install/uninstall path if a live demo ever makes the lingering entries look wrong.
- **`ACTION` has no real control vocabulary yet** — it can't, until the panel parses assets (1.6.x). The sim exercises the *path* with a sentinel control id; real control semantics arrive with the panel.
- **`TRIGGER`** — the third ACCESSORY-out message, moved to the new 1.9.x Elements version (a shared origin-aware alert store + the alerts-area element that reads it). Not transport-column work.

**Notes:**
- Built and verified in the session of 2026-07-08. The sourceful-vs-sourceless distinction and the thin-ACTION-backend decision were both agreed before any code.
- Roadmap changed this session alongside the build: a new **1.9.x — Elements** version was added as the final major feature of version 1 (Roger's call — TRIGGER/alerts is a low-priority nice-to-have, so it sits at the end rather than in the transport era), and the 1.3.x "alerts area functional in 1.4.x" line was given a dated supersession note pointing to 1.9.x.
- No firmware twin this session — the simulator proves both directions of the specific column; a real ACCESSORY on the Uno (a panel variable out, a control coming back) can follow at a bench session, as its LISTENER sibling still can from 1.4.8.

---

## Version 1.4.8

**Status:** Complete — verified on the tablet with the built-in simulator (SIMULATOR ON → install Sim Vehicle and the new Sim Relay LISTENER from Module Management → the vehicle's `BROADCAST`s come straight back out to the Relay as `LISTEN` on the Serial Monitor: `vehicle_speed` throttled to ~1 Hz against the 2 Hz source and deadbanded, `engine_rpm` throttled, `door_driver_open`/`headlights_on`/`gear_position` delivered on-change and on the 5 s heartbeat, a full dump the moment the Relay goes ACTIVE, and a valueless `LISTEN|…|media_next` on the MEDIA ▸ poke). Seventh version of the 1.4.x Transport Layer era.

**Scope:** LISTENER streams — the outbound half of the LISTENER type set up at install in 1.4.4. The controller gains its fifth desk (`Streams`), the *mirror* of 1.4.7's broadcast desk: where that one takes `BROADCAST` off the wire and fills the sourceless core, this one watches the core and delivers subscribed signals back out as `LISTEN`, with all of rate/threshold/gate evaluated in DASH (arduino.md §9).

**Design decisions taken this session (all with Roger, 2026-07-07→08):**
- **Defaults are the firmware library's job, not DASH's.** The earlier plan — DASH holding a per-signal default rate/threshold and applying it to a blank subscription field — was dropped. A module declares exactly what it wants; DASH honours the delivered line literally and holds no defaults of its own, so it records nothing extra. This is more aligned with the ethos (DASH holds no opinion on a "sensible" rate) and with SDKable (the default-provider moved to the shared library the sketch author still doesn't touch — "not chosen by the builder" is preserved). It also *simplified* the build: the Streams desk needs zero defaulting logic. arduino.md §4c/§9 and system_commands.md were reworded to match.
- **Malformed subscriptions are dropped, not accommodated (Roger's principle).** DASH assumes modules are written perfectly. A blank optional field is legal and total (no cap / any change / always) and is honoured literally — that is reading the field, not leniency. A present-but-unparseable field (a rate that isn't `Nhz`, a non-numeric threshold) is logged and treated as absent; DASH never guesses the author's intent. The one obligation this leaves — and it is DASH protecting its *own* integrity, not the module's — is that the parser drops bad input cleanly rather than wedging.
- **Watch the store; keep the core dumb.** The desk observes `SystemState` (diffing the value map) rather than the core being taught to push change notifications — the store stays the passive blackboard, the desk holds all the delivery cleverness, per the crossroads principle. Watching the store catches continuous (silent) and boolean (store-and-event) changes with one rule; the event bus is used only for event-only controls.
- **Leading-and-trailing rate throttle**, not pure leading-edge. Pure leading-edge can drop the *resting* value of a stream for up to a full 5 s heartbeat — bad for exactly the steering-angle case §9 uses. Leading+trailing sends the first value of a burst at once and the last value at the window's close, converging within one rate-window. Costs one scheduled flush per throttled subscription (the coroutine-timer pattern the reconciliation desk already uses).
- **Vocabulary-as-editable-data — raised and parked indefinitely.** Whether the signal vocabulary should live in a txt/md/json file rather than code was discussed; parked as premature. The built-in set stays curated in code (type-safe, no runtime parse, no way to ship a malformed vocabulary); user-added *custom* signals are the future patch-bay/custom-fallthrough stage's job, where a user-editable file is the right tool. The message *format* stays fixed in the protocol docs regardless — softness there breaks every module ever built.

**Implemented:**
- `Streams` (`com.dash.android.transport`) — the fifth desk. Watches `SystemState` + the installed/active module list; delivers `LISTEN|id|function|value` (valueless for event-only). Four §4c triggers: on-change (from the store watch), 5 s heartbeat, on-activation dump, and event-only fire (from the event bus). Four §9 controls evaluated here: leading+trailing rate throttle, numeric deadband, and gate + gate_value (a false→true flip fires the current value immediately, ignoring the threshold; while closed nothing is sent). Deliberately *not* in `DashController.route()` — it is driven by watching state, not an inbound TYPE word; the one desk that produces without consuming the wire. Subscriptions are read type-agnostic from every installed module, so a LISTENER's and an ACCESSORY's (§11) are handled identically.
- `DashController` — staffs the fifth desk (`streams`), started alongside the others; wired to `transport::send`, `systemState`, `database.modules`, `reconciliation.activity`.
- `VirtualListenerModule` ("Sim Relay", `0000DA5E0003`) — the third virtual module and the 1.4.8 test rig: subscribes at install to all six Sim Vehicle signals with rates chosen to make the mechanics visible (`vehicle_speed|1hz|2`, `engine_rpm|1hz`, three plain on-change booleans, and event-only `media_next`). Firmware discipline like its siblings — SILENT until ACTIVATE, ROGERs, holds no live loop (a LISTENER only receives). Registered in `SimulatedModuleTransport` (now three modules on the shared-bus preview); a status row added to the State Inspector.
- Version bumped: `versionName` 1.4.7 → 1.4.8, `versionCode` 11 → 12.

**Regressions:**
- None known. The 1.4.7 broadcast path, the core, install, database, and reconciliation are untouched — Streams is purely additive, an observer of existing flows and a producer of outbound lines.

**Fixes:**
- None — clean first build.

**Outstanding / deferred (with agreed homes):**
- **The gate path is built but not yet watched work.** The sim Relay's subscriptions are deliberately ungated for a clean "all signals" read, so the gate (and its gate-open-fires-immediately behaviour) has no runtime exercise yet. Low risk — it reuses the verified store-watch and store-lookup patterns — but recorded honestly. A gated sim subscription (e.g. `vehicle_speed` gated on `gear_position reverse`, watched switch on/off with the GEAR poke) can be added whenever a live demo is wanted.
- **Per-signal continuous defaults** now live library-side (this session's decision) — the numbers stay documented in system_commands.md as the library's reference, but DASH applies none. The firmware library that fills them is later SDK work, not a DASH version.
- **Custom-signal fallthrough + patch-bay + vocabulary-as-data** — the crossroads' configurable future; parked together, not 1.4.x.

**Notes:**
- Built and verified in the session of 2026-07-07→08. The four design points (library-owned defaults, malformed-is-dropped, watch-the-store, leading+trailing throttle) were agreed before any code, and the vocabulary-as-data idea was raised and parked in the same conversation.
- Firmware twin deferred to a later bench session — unlike 1.4.7's Test System module, no hardware LISTENER sketch was written this session; the simulator proves the delivery path, and a real LISTENER on the Uno can follow when convenient (it would subscribe and switch a pin on what it hears).

---

## Version 1.4.7

**Status:** Complete — verified on the tablet with the built-in simulator (SIMULATOR ON → both virtual modules discovered by the sweep → installed from Module Management → the State Inspector's store ticks with the speed/rpm stream, door flips land in both panes, MEDIA ▸ fires an event-only line, heartbeats stream on the wire tap while the event pane stays silent). Sixth version of the 1.4.x Transport Layer era.

**Scope:** system message routing — `BROADCAST|id|function|value` parsed and dispatched into the sourceless core. The controller gains its fourth desk and DASH gains the state store and event bus everything after the transport era will read from. Groundwork laid in 1.4.4 (arduino.md §4b/§5a, `system_commands.md`) got its implementation.

**Design decisions taken this session (all with Roger, 2026-07-07):**
- **The verification surface is a State Inspector, not a real reaction.** 1.4.7's outcomes are internal (a map value changing, an event nothing listens to yet), and the Serial Monitor can only prove delivery, not understanding. A live window into the core — store pane (value + age), event pane (timestamped rolling log) — verifies the mechanism for every signal; wiring one signal to a real behaviour (headlights → dim screen) would have tested one signal through interface-era territory that isn't designed yet. Agreed split: the **machinery is permanent, the screen is deliberately quick and dirty** — a dev instrument like the Serial Monitor, replaceable without ceremony.
- **§4b is in scope by construction.** The dump and heartbeat are ordinary `BROADCAST`s; their entire DASH side is the desk *comparing before it acts* (§4b puts change detection on DASH's side). One comparison makes the activation dump, the 5 s heartbeat, flood-free events, and the self-healing store all fall out free.
- **Verification via simulation, DASH first, firmware after (Roger's call).** A simulated transport with virtual modules behind it, kept to full firmware discipline — SILENT until ACTIVATE, ROGER everything, no access a real board couldn't have — so nothing is faked past the gatekeeper. The inspector's poke buttons (DOOR/GEAR/LIGHTS/MEDIA ▸) are the pretend car's physical inputs, *not* module UI — Roger queried whether buttons made it wrongly ACCESSORY-shaped; resolved: a SYSTEM module's buttons are physical (the steering wheel module is exactly that), and these stand in for the physical world. A dummy ACCESSORY was added at Roger's choice alongside the SYSTEM module.
- **Live broadcast traffic counts as being heard.** The desk feeds the reconciliation liveness clock (`heard(id)`), so a module streaming ten times a second can't age to DORMANT between sweeps and have its data refused mid-stream. ACTIVE is still only ever granted by a `ROGER`.
- **Unknown signals drop and log.** The custom-signal fallthrough and the patch-bay override are the crossroads' future configurable stages, deliberately not built; dropped traffic stays visible on the wire tap.

**Implemented:**
- `SystemState` (`com.dash.android.core` — new package) — the sourceless core: state store (`values: StateFlow<Map<String, StoredSignal>>`) and event bus (`events: SharedFlow<SystemEvent>`, replay 100). Deliberately dumb; never sees a module id.
- `SystemCommands` — the §5a behaviour vocabulary transcribed from `system_commands.md` (which stays authoritative): every standard signal → store-and-event / store-only / event-only.
- `Broadcasts` (`com.dash.android.transport`) — the fourth desk and the §5 gatekeeper: installed + ACTIVE or refused-and-logged; id consumed at the desk; behaviour lookup; §4b compare-then-act. Event-only signals are never deduplicated (each firing is a fresh press) and carry no value on the wire.
- `Reconciliation.heard(id)` — the liveness-clock feed from the broadcast desk.
- `DashController` — staffs the fourth desk; `BROADCAST` routes to it; owns `systemState`.
- `SimulatedModuleTransport` (`transport.sim`, tag `sim`) — a loopback `DashTransport` behind the same contract as USB; boots unplugged; its toggle is the pretend USB lead (off = instant module silence, no goodbye — exactly a yanked cable). Two modules on one pipe is a deliberate shared-bus preview.
- `VirtualSystemModule` ("Sim Vehicle", `0000DA5E0001`) — streams `vehicle_speed`/`engine_rpm` every 500 ms on a sine "drive", random `door_driver_open` flips every 8–20 s, §4b dump on activation + full heartbeat every 5 s, pokes for door/gear/headlights/`media_next`. `VirtualAccessoryModule` ("Sim Accessory", `0000DA5E0002`) — two-block MANIFEST/CRC install, `REPORT` every 2 s while active (correctly unrouted until 1.4.9 — visible on the tap, absent from the store, which is itself a 1.4.7 test).
- `StateInspectorScreen` (settings → OPEN STATE INSPECTOR) — simulator toggle + pokes, store pane with value/age, event pane with timestamped log. Quick and dirty by agreement.
- Version bumped: `versionName` 1.4.6 → 1.4.7, `versionCode` 10 → 11.

**Regressions:**
- None known. USB transport, install, database, and reconciliation paths untouched except the additive `heard()`.

**Fixes:**
- None — clean first build.

**Outstanding / deferred (with agreed homes):**
- **Custom-signal fallthrough + patch-bay override** — the crossroads' configurable stages; future work by design (see the transport-brain notes), not 1.4.x.
- **Sim rig's two-modules-one-pipe contention** (simultaneous HELLOs, bus quiescing) — cannot collide in-process, real on RS485; parked at 1.4.10 with the rest of shared-bus design.
- **The State Inspector is temp** — replaced or redesigned whenever a real surface earns its place; no design debt intended.

**Notes:**
- Built and verified in the session of 2026-07-07 — the same session as the Test Accessory v2 work; the 1.4.7 design discussion (verification surface, §4b scope, bench module) ran as three agreed points before any code.
- *Addendum, same day:* the deferred "firmware after" piece was written and verified too — `arduino/test_system/test_system.ino`, the **Test System module** (`0000DA58EE04`, the bench's fourth id), the hardware twin of the Sim Vehicle: the same pretend drive (500 ms speed/rpm stream, random door, cycling gear, toggling headlights, occasional valueless `media_next`) plus the §4b dump-on-activate and 5 s heartbeat, self-generated so nothing needs wiring to the board. Bench-verified on the Arduino Uno R4 against DASH 1.4.7 — the routing pipeline is proven on copper as well as in simulation.

---

## Version 1.4.6

**Status:** Complete — bench-verified on the Arduino Uno R4 (launch DASH with an installed module plugged in → the green card turns ACTIVE within a sweep, the Serial Monitor shows the DISCOVER → HELLO → ACTIVATE → ROGER rally; install a new module → it goes straight to ACTIVE; uninstall while active → DEACTIVATE → ROGER and the module's heartbeat stops). Fifth version of the 1.4.x Transport Layer era.

**Scope:** startup reconciliation — the version where installed modules stop being dormant records and come alive each session. DASH is the single source of truth (arduino.md §6): a module boots SILENT and must be told to wake every session. The new reconciliation desk does the telling — a persistent `DISCOVER` sweep, `ACTIVATE` with acknowledged retries, Active/Dormant state on Module Management, and `DEACTIVATE` on uninstall. The firmware needed nothing: all three reference sketches had carried the SILENT → ACTIVE state machine since 1.4.4.

**Design decisions taken this session (all with Roger, 2026-07-06):**
- **One DISCOVER — no separate reconnect message.** Roger's instinct was that DISCOVER was for install and reconnection deserved its own message. Rejected on architectural grounds: a broadcast "installed modules, reconnect" is impossible when modules persist nothing and don't know their own install state (§6) — the only question a module can ever answer is "who's there?". The install/reconnect distinction lives in DASH: the same `HELLO` is an install candidate to the discovery desk and a liveness report to the reconciliation desk. A per-id ping and an optional HELLO state field were considered and parked (recorded in arduino.md).
- **The DISCOVER button became SYNC.** With the sweep persistent, install candidates appear on Module Management when hardware is plugged in and age away when it stops answering — a dedicated install-discovery button is redundant. SYNC is §6's manual "check now": one press serving both install and reconnection by running the sweep immediately. This amends 1.4.2's "broadcasts only on the button press, never on a timer" — that rule was the placeholder until the sweep 1.4.2 explicitly reserved for 1.4.6 existed.
- **ACTIVATE is re-asserted every sweep, not only when DASH thinks a module is silent.** A brown-out-rebooted module (engine crank) answers `HELLO` identically to a healthy active one, so a fresh `ROGER` is the only proof of life; activate-only-when-dormant would leave a silently-rebooted module dead until DASH restarts — invisible on the wire for a LISTENER, which sends nothing at runtime. Roger challenged the redundant ACTIVATEs; the traffic was measured (~1% of a 115200 wire for ten modules, even including the future §4b activation dumps; the §4b heartbeat itself is ~7%) and he accepted the chatter over changing the HELLO contract. The state-field alternative is parked, backward-compatible, if it ever matters on a shared bus.
- **Uninstall deletes immediately and warns after, rather than blocking on confirmation.** §6's FORCE UNINSTALL wording supports two readings; the non-blocking one was chosen: the record is deleted at once (DASH forgetting *is* the uninstall), `DEACTIVATE` retries behind it, and only if no ack ever comes does a dialog raise §6's warning — disconnect or power-cycle the module. A module never heard from this session skips the wire entirely, exactly as 1.4.5 behaved.
- **Cadence numbers:** sweep every 5 s for the first 60 s (the Uno Q's ~20–30 s Linux boot is the design case), then every 30 s forever (crank-reset and hot-plug recovery); ack timeout 2 s × 3 attempts; absent after 75 s unheard (≈ two missed slow sweeps), which is also the discovered-card prune horizon. A transport coming up (boot, replug, permission grant) triggers an immediate sweep; the sweep holds off while an install handshake is in flight so a broadcast never interleaves a declaration run.

**Implemented:**
- `Reconciliation` (`com.dash.android.transport`) — the controller's third desk, and its first with a clock. Owns the persistent `DISCOVER` sweep (fast phase → slow forever-rate, `sync()` jumps the timer); `onHello` re-asserts `ACTIVATE` for installed ids; `onRoger` feeds monotonic per-command ack counters (a StateFlow condition-wait, so a fast ROGER can't be missed and a stale one can't satisfy a fresh command) — an activate ack is the only thing that turns a module ACTIVE; `uninstall` deletes immediately then hushes with retried `DEACTIVATE`; exposes `activity: StateFlow<Map<String, ModuleActivity>>` (ACTIVE/DORMANT) and `unconfirmedDeactivation` for the §6 warning.
- `Discovery` — the broadcast moved out (to the sweep); the desk became the passive HELLO collector: upsert by id, entries age out via `prune()` on the sweep's clock. The 1.4.2 clear-and-rebroadcast `discover()` method retired; the kdoc records the amendment.
- `ModuleDatabase` — gains `loaded: StateFlow<Boolean>` so the first sweep waits for the disk read and an early HELLO is matched against the real installed list, never a briefly-empty one.
- `DashController` — staffs the third desk: `HELLO` now routes to *two* desks (deliberately — each ignores the ids that aren't its business), `ROGER` to reconciliation; an install commit goes straight to `activate()` (the §7 flow ends `INSTALL_END` → save → `ACTIVATE`, so a fresh install lights up without waiting for a sweep); a transport reaching CONNECTED triggers `sync()`.
- `ModuleManagementScreen` — DISCOVER button renamed SYNC (calls `reconciliation.sync()`); installed cards wear a green ACTIVE / grey DORMANT chip; uninstall goes through the reconciliation desk; new unconfirmed-deactivation dialog with the §6 warning text; empty-state text now says plug it in and it appears.
- Version bumped: `versionName` 1.4.5 → 1.4.6, `versionCode` 9 → 10.

**Regressions:**
- None. The install handshake, progress bar, Details dialog, and database behave as in 1.4.5. The DISCOVER-button behaviour change (list no longer rebuilt per press; cards appear and age out on their own) is the agreed 1.4.6 design, not a regression.

**Fixes:**
- One compile-time fix during the build: `reconciliation` and `install` reference each other through lambdas (`installBusy` / commit-to-activate), which sent Kotlin's type inference recursive — explicit property types on both broke the cycle.

**Outstanding / deferred (with agreed homes):**
- **Absent-wired = fault vs absent-wireless = quiet dormant (§6)** — today absent is just DORMANT, neutrally; the transport-aware fault visual belongs to the later 1.4.x designed-failure work, alongside the install timeout.
- **A wedged install pauses the sweep indefinitely** — the sweep holds while any install session is open, and installs still have no timeout (deliberate 1.4.4 deferral). Inherits the same later failure work.
- **HELLO state field (`…|version|active/silent`) parked** — would let DASH activate only when needed instead of re-asserting; backward-compatible (no field ⇒ re-assert). Revisit if sweep chatter ever matters on a shared RS485 bus.
- **Simultaneous HELLO replies will collide on a true shared bus** — irrelevant on point-to-point USB, real on RS485 multi-drop; a reply-jitter scheme belongs with the multi-device work (1.4.10+). *(Addendum 2026-07-07: block transfers join the same shared-bus conversation — an install's BLOCK payload and another module's live data would interleave on a shared wire and corrupt the frame, so DASH must quiesce the bus for the transfer. Recorded in the roadmap 1.4.10 entry alongside the per-device-FrameAssembler requirement that keeps point-to-point transports immune.)*

**Notes:**
- Built and bench-verified in the session of 2026-07-06.
- New standing instruction from Roger, applied here and saved for future sessions: documentation updates must be **additive** — record what changed and why beside the original decision, never erase the previous version.
- *Addendum — session of 2026-07-07 (post-verification work, no version bump):* the Test Accessory reference sketch went to **v2** — three live `REPORT` variables (`test_counter`, `needle`, `uptime_s`) every 2 s while ACTIVE, so activation and deactivation are visible on the Serial Monitor as the traffic starting and stopping; the variable names match the panel_layout bindings so the sketch is the ready-made bench module for 1.4.9 and the 1.6.x panel. Bench testing organically demonstrated the hot-plug self-heal: the module was unplugged mid-session, the sweep caught the replug, ACTIVATE/ROGER re-armed it, and the counters restarted from zero — captured in `gallery/v1.4.6-activation-verification.jpeg`, the first entry in the new `gallery/` folder for project pictures. Design fallout recorded the same day: roadmap **1.4.12** (firmware version mismatch — the v2 reflash exposed it) and **1.4.13** (designed install failure) added, **1.4.10** amended (per-device frame assembly; shared-bus quiescing parked), and the **no-live-data-mid-handshake** rule added to arduino.md §7. `DashTheme.kt` also carries an uncommitted colour change — the dark blue palette was difficult to see and was swapped for light grey.

---

## Version 1.4.5

**Status:** Complete — verified (install a module, kill and relaunch DASH, the green card is back from disk; DETAILS reads the saved record; UNINSTALL removes it and its folder). Fourth version of the 1.4.x Transport Layer era.

**Scope:** the module database — the on-disk installed list that arduino.md §6 names as the single source of truth: DASH holds the installed list on disk and tells the module what to do each session; the module persists nothing. An install that completes now survives every restart. This is the last piece of *set up* before 1.4.6 makes modules *do* something — startup reconciliation reads this same list to decide who gets `ACTIVATE`d each boot.

**Design decisions taken this session:**
- **The database holds *installed*; the install desk holds *installing*.** The desk's `states` flow now covers only handshakes under way this session (the old `InstallState` sealed interface collapsed to a plain `Installing(progress)` — its `Installed` half moved to the database wholesale). A completed handshake is handed to a `commit` callback wired to the database, which persists it and owns it from then on. Asset payload bytes are held in the session for exactly that hop: validated in the desk, written to disk by the database, never kept in memory after.
- **Write order is the crash-safety mechanism.** One folder per module under `filesDir/modules/`: an `assets/` folder of raw block payloads, then `module.json` written *last*. A folder interrupted mid-write has no record and is skipped — and swept away — on the next load; a record and its assets are born and die together, so uninstall is one recursive delete.
- **One physical module = one card.** Module Management renders a merge of two sources keyed by id: the on-disk installed list (green cards, present the moment the screen opens — no DISCOVER needed, plugged in or not) and this session's discovered set (INSTALL cards for modules answering right now that aren't installed). A module in both is one green card — discovery just confirmed something DASH knew. DISCOVER still rebuilds the discovered set from scratch each press, but it can never remove an installed card.
- **Wire-supplied names never become filesystem paths unsanitised.** Module ids and asset names arrive off the wire and become folder/file names, so they pass through a plain-alphabet sanitiser (leading dots stripped, collisions suffixed, blanks given fallbacks) before touching disk. The sanitised asset filename is recorded in the new `InstalledAsset.file` field — the panel (1.6.x) reads the bytes from there.

**Implemented:**
- `ModuleDatabase` (`com.dash.android.transport`) — `load()` reads every `module.json` under `filesDir/modules/` at controller start (sweeping recordless debris folders); `commit(module, payloads)` assigns asset filenames, updates the `modules` flow synchronously (the UI sees it immediately), then writes assets-then-record on an IO scope; `uninstall(id)` drops the record and recursively deletes the folder. Exposes `modules: StateFlow<Map<String, InstalledModule>>` — THE installed list; absent here ⇒ not installed. JSON posture matches `DashPreferences`: `ignoreUnknownKeys`, so a record written by an older or newer DASH build still decodes.
- `InstalledModule` / `Subscription` / `InstalledAsset` marked `@Serializable`; `InstalledAsset` gains `file` (the on-disk filename). The record shape built in 1.4.4 serialised as-is — the "deliberately what 1.4.5 will serialise" bet paid off.
- `Install` — `install(id)` now also refuses an already-installed id (uninstall first); sessions accumulate raw payload bytes alongside asset metadata; `onInstallEnd` hands the finished record and payloads to `commit` and clears the session. `uninstall` left the desk entirely — it belongs to the database now.
- `DashController` — takes a `Context`, creates the database, calls `load()` on start, and wires the install desk's `isInstalled` / `commit` to it.
- `ModuleManagementScreen` — renders the merged one-card-per-module list (installed first, alphabetical; then this session's discoveries in answer order); DETAILS reads the on-disk record; UNINSTALL goes through the database; empty-state text now explains installed modules appear automatically.
- Version bumped: `versionName` 1.4.4 → 1.4.5, `versionCode` 8 → 9.

**Regressions:**
- None. The install handshake, progress bar, and Details dialog behave as in 1.4.4 — the change is that the result now outlives the session. Discovery and the Serial Monitor are untouched.

**Fixes:**
- None required.

**Outstanding / deferred (with agreed homes):**
- **A failed disk write degrades, not errors** — the record stays in memory (1.4.4's session-only behaviour) and the failure is logged. A designed failure surface remains part of the later 1.4.x failure work, alongside the install timeout.
- **Asset bytes on disk are read by nothing yet** — the module panel (1.6.x) is their consumer.
- **ACTIVATE / reconciliation → 1.4.6.** An installed module is still dormant; the database's `modules` flow is exactly what reconciliation will read.
- **Firmware-side, carried from the 2026-07-05 session:** the test LISTENER module (`arduino/test_listener/`) is written and compiling but awaits its bench run before commit.

**Notes:**
- Built in the session of 2026-07-05/06; verified by Roger and recorded 2026-07-06.

---

## Version 1.4.4

**Status:** Complete — bench-verified on the Arduino Uno R4 (Settings → MANAGE MODULES → DISCOVER → INSTALL → the pane runs a progress bar, turns green, and DETAILS shows the captured declarations). Third version of the 1.4.x Transport Layer era. Roadmap 1.4.3 was already merged into 1.4.2, so this is the next number.

**Scope note:** all three declaration parsers were built in one version — SYSTEM (`SYSTEM_SIGNAL`), LISTENER (`SUBSCRIBE`), and ACCESSORY (`MANIFEST` + asset `BLOCK`s) — rather than SYSTEM-only. The verify-on-hardware rule normally argues against building parsers with no module to exercise them, but Roger is writing LISTENER and ACCESSORY reference modules to flash onto the R4, so all three paths get real bytes before the version is signed off. The install handshake takes a module from *found* (1.4.2) to *set up*: DASH sends `INSTALL|id`, reads the type-specific declarations, and commits them on `INSTALL_END|id`.

**Design decisions taken this session (ahead of the features that use them):**
- **The install desk is the controller's first stateful and first bidirectional desk.** Discovery is fire-and-collect (each `HELLO` self-contained); an install is a *conversation* — opened by DASH sending `INSTALL`, fed by a run of declaration lines, closed by `INSTALL_END`. State lives in the desk (`Install`), not the controller, which stays a thin dispatcher — the patch-bay-not-a-funnel shape holding under its first real test. Sessions are keyed by module id in a map: the UI drives one install at a time, but the wire is id-addressed (arduino.md §2), so the frame supports many in flight for nothing.
- **Framing belongs to the transport; meaning belongs to the desk.** An asset `BLOCK|id|name|length|crc` is followed by exactly `length` raw bytes that may contain `\n` — so the reader must stop line-framing and read a byte count, then resume. This switch is made *synchronously inside the byte loop on the IO thread*: any asynchronous decision above the assembler would arrive after the payload had already been mis-framed as lines. The assembler does length-framing only (where does the unit end); the desk does CRC validation and record assembly (what the unit means).

**Implemented:**
- `Install` (`com.dash.android.transport`) — the install desk. `install(id)` opens an `InstallSession` (seeded from the module's discovery identity) and sends `INSTALL|id`; `onSignal` / `onSubscribe` / `onManifest` / `onBlock` feed the open session; `onInstallEnd` commits it to an `InstalledModule`; `uninstall(id)` drops the record. Exposes `states: StateFlow<Map<String, InstallState>>` for the UI. A declaration for an id with no open session is logged and ignored (well-mannered — also the forward-compat path for message types a later build will handle).
- `Inbound` (sealed: `Line` / `Block`) — one ordered stream carrying both ordinary lines and length-prefixed asset blocks, so `MANIFEST`, its `BLOCK`s, and `INSTALL_END` reach the desk in the order the module sent them.
- `FrameAssembler` — replaces `LineAssembler`. Frames two ways now: newline-delimited lines and length-prefixed blocks. On completing a `BLOCK|id|name|length|crc` header it switches to raw mode, reads exactly `length` bytes verbatim, and emits `Inbound.Block(header, bytes)`. A `reset()` (called on each connect) prevents a mid-block disconnect corrupting the next session's framing. Provisional 64 KB block cap guards a corrupt header (real asset-size caps are arduino.md §10, still open).
- `InstalledModule` / `Subscription` / `InstalledAsset` — the committed record, deliberately the shape 1.4.5 will serialise to disk. Identity fields (`type`/`name`/`description`/`version`) carry over from the `HELLO`; the handshake adds only the type-specific payload. `Subscription.parse()` captures all seven `SUBSCRIBE` fields (rate/threshold/gate included, though DASH does not act on them until 1.4.8).
- `DashController` now staffs two desks — routes `SYSTEM_SIGNAL` / `SUBSCRIBE` / `MANIFEST` / `INSTALL_END` to the install desk, and hands `Inbound.Block` payloads straight to it. `BLOCK` headers need no route branch (they ride inside `Inbound.Block`) but still appear on the wire tap.
- `TransportManager.inbound` is now `SharedFlow<Inbound>` (was `String`). Block payloads render on the wire tap as the header line plus a readable `«N bytes»` note — never raw binary in the monitor.
- `ModuleManagementScreen` — each discovered pane gains the install lifecycle: an **INSTALL** button → a progress bar (determinate for ACCESSORY once `MANIFEST` gives a byte total, indeterminate pulse for SYSTEM/LISTENER) → a **green pane** with a **DETAILS** button. The Details dialog (a settings-side modal — named a *dialog*, kept distinct from a v3 Overlay) shows the captured declarations type-shaped (signals / subscriptions / assets), with **UNINSTALL** and **DONE**. This dialog is the verification surface for the three parsers.
- Version bumped: `versionName` 1.4.2 → 1.4.4, `versionCode` 7 → 8.

**Protocol details settled this session (the module firmware must match):**
- **Asset `BLOCK` CRC is CRC32 as lowercase hexadecimal, no prefix** (DASH parses the field base-16; Arduino's `String(crc, HEX)` produces exactly this). A decimal CRC would fail every block and abort the install.
- **ACCESSORY variables and interactive controls are not implemented** — their install-declaration framing is an open item (arduino.md §10), so no format was invented. An ACCESSORY install carries `MANIFEST` + `BLOCK`s only until that framing is agreed.
- **`SUBSCRIBE` throttle/gate fields are captured and displayed but inert** until the stream engine (1.4.8).

**Regressions:**
- None. The `LineAssembler` → `FrameAssembler` rename and the `String` → `Inbound` inbound type are behaviour-preserving for ordinary lines (a line becomes `Inbound.Line`, routed exactly as before); the Serial Monitor is unchanged. Discovery is untouched. All install UI is additive.

**Fixes:**
- None required.

**Outstanding / deferred (with agreed homes):**
- **Install timeout + designed fail-state visual → later 1.4.x failure work.** 1.4.4 handles only the unavoidable failure: a block failing CRC/length aborts the session cleanly (record dropped, pane reverts to INSTALL, logged to logcat). A handshake that never sends `INSTALL_END` currently sits pending until relaunch — accepted on the bench, where the modules are under the tester's control.
- **ACCESSORY variables/controls declaration framing** — to be designed with Roger before that half of the panel contract (1.6.x) can land; arduino.md §10.
- **Disk persistence → 1.4.5.** Installed records are session-only; the record shape is ready to serialise.
- **ACTIVATE / live data / state store → 1.4.6+.** Installed means *dormant*: no module is sending yet.

**Notes:**
- Two SDK documents were extended this session as groundwork for 1.4.6+ (not 1.4.4 code): arduino.md **§4b State reporting** (SYSTEM modules dump all current values on `ACTIVATE` and heartbeat them every ~5 s; DASH does change detection) and **§5a Controller architecture** (the state store and the three signal behaviours — store+event / store-only / event-only — driven by `system_commands.md`, the new authoritative signal-vocabulary document). None of that is exercised until modules are activated and stream live data.
- Roger's design-conversation notes arrived using the retired term `SYSTEM_TX`; it was rendered as `BROADCAST` throughout (renamed 2026-06-25) and the substitution recorded in arduino.md's discussion notes. The `light_ambient` signal was also renamed to `ambient_light` for naming consistency.

---

## Version 1.4.2

**Status:** Complete — bench-verified on the Arduino Uno R4 (Settings → MANAGE MODULES → DISCOVER → the R4 appears in the list by name). Second version of the 1.4.x Transport Layer era.

**Scope note:** Roadmap 1.4.3 (HELLO response parsing) is merged into 1.4.2. As with 1.4.1 combining the transport and the monitor, the two are inseparable for verification: a Module Management screen with a DISCOVER button but a list that can never populate cannot be signed off as working. So the outbound broadcast and the inbound parsing were built together, and the first DISCOVER press produces a real module in the list.

**Two architectural decisions taken here, deliberately ahead of the features that will use them (future-readiness over the easy win):**
- **Discovery is a user-driven method of installation, not reconnection.** DASH broadcasts `DISCOVER` only when the user presses the button — never on a timer. It is the user's responsibility to ensure a module has booted first. The automatic low-rate re-sweep of *already-installed* modules described in arduino.md §6 is a separate mechanism belonging to reconnection / startup reconciliation (1.4.6). This refines arduino.md's model (which uses DISCOVER at boot for reconciliation too) — the manual install-time sweep and the automatic reconnect sweep are now distinct jobs. arduino.md itself is unchanged; the refinement is recorded here and revisited when 1.4.6 lands.
- **The message brain is a configurable crossroads, not a funnel.** A new `DashController` is the dispatcher: one inbox (the transport's inbound stream), sort by TYPE word, dispatch to the desk that owns that message type, one outbox. Today exactly one desk is staffed — `Discovery` (`HELLO`); every other TYPE word is ignored for now rather than mishandled (still visible on the wire tap), and adding its desk later is a single new branch in `route()`, never a rewrite. This is the frame for the future *configurable routing* discussed this session: standard signals act by sensible defaults, custom signals fall through to a user-defined desk, and a patch-bay override redirects a signal to Android, to DASH, or back out to another module (Roger's widening of the patch-bay idea beyond CAN to any module input). None of that routing is built now — but the shape here is what lets it plug in without rework.

**Implemented:**
- `DashController` (`com.dash.android.transport`) — the message brain / dispatcher. Owns its own coroutine scope; `start()` collects the transport's inbound stream and routes each line by TYPE word; `stop()` cancels. Created and owned in `MainScreen` alongside the `TransportManager`, living for the app's life via a `DisposableEffect` (started after / stopped before the transport). Holds the `discovery` desk.
- `Discovery` — the discovery desk. `discover()` clears the list and broadcasts `DISCOVER` (through `TransportManager.send`, so it shows on the wire tap like any line); `onHello(line)` parses a routed `HELLO` and upserts into an observable `modules: StateFlow<List<DiscoveredModule>>` by module id (a module answering twice appears once). The list is app-lifetime state — it survives closing and reopening the screen; only the next DISCOVER press clears it.
- `DiscoveredModule` + `parseHello()` — the seed of the `DashMessage` codec (flagged for 1.4.2 in the 1.4.1 outstanding notes). Parses `HELLO|id|type|name|description|version` (arduino.md §2: exactly six pipe-separated fields, one value per field, no embedded delimiters) into a typed value; anything malformed is rejected rather than guessed at, so a corrupt line never becomes a phantom module.
- `ModuleManagementScreen` (`com.dash.android.ui.modules`) — a full-screen instrument reached from settings, mirroring the Serial Monitor route. A left-aligned DISCOVER button (the first thing you do on the screen) and a live list of discovered modules rendered as cards (name, a colour-coded type chip for SYSTEM / ACCESSORY / LISTENER, description, id, version). Empty-state text guides the user to boot the module and press DISCOVER. The installed-module database (1.4.5) and per-module install/enable actions layer into this same screen later.
- `TransportManager` made genuinely transport-agnostic (roadmap's "all active transports"). Now holds a `List<DashTransport>` (just USB today; WiFi TCP joins it in 1.4.11 with nothing here to change) rather than a hardwired single transport. `send()` fans out to every *active* (CONNECTED) transport and records each on the wire tap; inbound lines from *all* transports are merged onto the wire tap tagged by origin; `status` aggregates across all transports, reporting the liveliest state present. A new `inbound: SharedFlow<String>` gives the controller a clean one-direction feed distinct from the observation-only `wire` tap (no replay, so a restarted collector never re-processes stale lines as new modules).
- `SettingsPanel` gains a MODULES section (`MANAGE MODULES →`, wired through a new `onOpenModules` callback exactly like `onOpenSerialMonitor`), placed above the Serial Monitor section. Becomes the Modules tab when the full settings tree is built in 1.5.x.
- `MainScreen` gains a `showModules` route and renders the `ModuleManagementScreen` overlay, mirroring the existing Serial Monitor overlay.
- Version bumped: `versionName` 1.4.1 → 1.4.2, `versionCode` 6 → 7.

**Refinements after first bench check:**
- The single-device status chip was removed from Module Management, and DISCOVER made always-pressable and left-aligned. Reason: DISCOVER broadcasts to *every* module on *every* active transport, so that screen is about the whole bus of modules, not any one device — a "device connected" prompt is the wrong mental model there. Device/connection state belongs to the Serial Monitor and to the Devices view coming in 1.4.10. The Serial Monitor keeps its status chip (honest while there is one device).

**Removed:**
- The DASH Scale section removed from the Settings panel. It has been parked with no active consumer since 1.3.2 (the scale multiplier was removed from bar height); the control changed a value nothing read. The `dashScale` preference, its persistence, and `LocalDashScale` are all untouched — only the dead UI control is gone. DASH Scale returns as a real control in a later version when there is more than one chrome element for it to scale uniformly.

**Regressions:**
- None. The transport-manager generalisation is behaviour-preserving with a single USB transport (the Serial Monitor behaves identically); the discovery brain, the Module Management screen, and the MODULES entry point are all additive; the DASH Scale removal touches only dead UI.

**Fixes:**
- None required.

**Outstanding / deferred (with agreed homes):**
- **Multi-device support & Devices view → 1.4.10** (just before WiFi TCP, which shifts to 1.4.11). Today the USB transport grabs only the *first* device it finds — DASH cannot see or address multiple physical devices. 1.4.10 adds that, a Devices view of what is physically connected, and device selection in the Serial Monitor (a dropdown). Roger's call to progress the module-lifecycle sequence first; not blocking, since install addresses a module by id and works with one device meanwhile.
- **USB one-time permission grant → 1.4.x cleanup before 1.5.x.** Currently Android re-prompts for USB permission on every replug (the runtime grant dies on detach). A `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml` (standard serial VIDs) turns that into a one-time "use by default" grant that survives replug and reboot. Android forbids zero-consent for a sideloaded app, so one-and-done is the floor on Bronze; it is silent anyway on the system-app production board. Deferred deliberately — understood, tolerable for now.
- **Standard system command vocabulary** — a formal list of known signals a module can send that the brain acts on by default (media keys, voice, volume, alongside the existing headlights/reverse). To be drafted in arduino.md "soon, carefully" — contract-level, so not rushed. Not started.
- Next feature: **1.4.4 — install handshake** (`INSTALL|id` → declarations → `INSTALL_END|id`), which takes the R4 from *found* to *set up*.

**Notes:**
- The controller reads the transport's `inbound` stream, not the `wire` tap, on purpose: the wire tap is a read-only observation surface (for the monitor and a future SDK logger element) and carries replay; the brain is the primary consumer and must not re-process replayed history as fresh discoveries.
- The two architectural decisions, the patch-bay/standard-vocabulary direction, and the "record only after verification" working rule are banked in project memory so future sessions inherit the reasoning rather than reconstructing it.

---

## Version 1.4.1

**Status:** Complete — bench-verified on real hardware (Arduino Uno R4). First version of the 1.4.x Transport Layer era.

**Scope note:** The ten-piece transport breakdown from roadmap 1.4.x is being built one piece per version (1.4.1 → 1.4.10). 1.4.1 deliberately combines the first two pieces — the transport interface and the USB serial transport — plus the Serial Monitor, because a transport foundation that cannot be seen working cannot be verified. Every later piece (discovery, handshake, routing…) is verified through the monitor built here.

**Wire format:** Built against the ratified module grammar in `arduino/arduino.md` (pipe-separated `TYPE|id|…`), not the superseded colon grammar still shown in `transport.md` (see the banner note added to `transport.md` on 2026-07-01).

**Implemented:**
- New `com.dash.android.transport` package. `DashTransport` — the pluggable transport abstraction (roadmap 1.4.1): a dumb pipe that moves whole UTF-8 lines in/out and knows nothing of their meaning, exposing `incoming: Flow<String>`, a `status: StateFlow<TransportStatus>`, and `start`/`send`/`stop`. This is the contract WiFi TCP and every future transport implement.
- `LineAssembler` — reassembles the inbound byte stream into complete lines, mirroring the firmware framing exactly (arduino.md §1): line ends at `\n`, stray `\r` tolerated and dropped, over-long lines discarded, bytes decoded as UTF-8 only once a full line has arrived so multi-byte chars split across reads survive.
- `UsbSerialTransport` on the usb-serial-for-android library. Fixed parameters 115200 8N1 (the known module profile — nothing to configure), DTR and RTS asserted on open (see Fixes). Auto-connects when a device is present via a low-rate re-sweep, requests USB permission on demand, and closes on detach. Absence of a device is a normal `NO_DEVICE` state, not a fault; denied permission and open failures degrade to `PERMISSION_REQUIRED`/`ERROR` without crashing — the capability-detection / graceful-degradation pattern. Falls back to treating an attached device as CDC-ACM when the built-in VID/PID table doesn't list it (covers Arduino boards not in the default prober set).
- `WireEvent` + `TransportManager` — the manager owns the transport(s) and exposes the read-only "wire tap" arduino.md calls for: a `SharedFlow<WireEvent>` of every line in/out, tagged and timestamped, with `replay = 200` so a newly-opened monitor immediately shows recent history. `send()` records the outbound line on the tap and forwards it to the transport.
- `SerialMonitorScreen` (`com.dash.android.ui.monitor`) — full-screen dev instrument reached from the settings panel, mirroring the system-bar edit-workspace route. Live scrolling log with direction arrows (→ out / ← in), timestamps, and TYPE-word colour-coding; PAUSE (freezes auto-scroll and capture) and CLEAR; a status chip reading the transport state; and a send box to type a line to the module (e.g. `DISCOVER` → watch `HELLO|…` return) before any handshake automation exists. It is a pure *view* onto the wire — it never owns the connection.
- `TransportManager` is created and owned in `MainScreen` (`start`/`stop` via `DisposableEffect`), so the connection persists for the life of the running app regardless of whether the monitor is open. `SettingsPanel` gains a SERIAL MONITOR section with an OPEN SERIAL MONITOR button, wired through a new `onOpenSerialMonitor` callback exactly like `onEnterEditMode`.
- Build plumbing: JitPack repository added to `settings.gradle.kts`; `usb-serial-for-android:3.9.0` and `kotlinx-coroutines-android:1.8.1` added to the version catalog and app dependencies; `<uses-feature android:name="android.hardware.usb.host" android:required="false" />` added to the manifest (keeps DASH installable on non-OTG devices — the transport simply reports NO DEVICE). `versionName` corrected from a stale `1.3.5` to `1.4.1`, `versionCode` 5 → 6.

**Regressions:**
- None. The transport is additive and self-contained; existing screens are untouched except for the new settings section and the monitor overlay.

**Fixes (all found during bench testing against the Arduino Uno R4, and fixed before sign-off):**
- **No data received despite a CONNECTED status.** `DISCOVER` went out and the board — proven to reply via a Play Store serial monitor — stayed silent. Root cause: DTR and RTS were never asserted on the port. Many USB-CDC bridges (the R4 WiFi's on-board ESP32-S3 among them) gate data flow until the host raises those control lines, so the port opened but the wire stayed dead. Fixed by asserting `setDTR(true)`/`setRTS(true)` immediately after `setParameters`, wrapped in its own `runCatching` so a driver that doesn't support the lines still connects. This was the difference between DASH and the working terminal app, which asserts DTR by default.
- **Hot-plug never connected; permission was never requested.** Plugging the R4 in while DASH was already running did nothing, yet connecting *before* launch worked. Root cause: the hot-plug path depended on the `ACTION_USB_DEVICE_ATTACHED` broadcast, which is unreliable for runtime-registered receivers, so DASH never noticed the device had arrived and never asked for permission. Fixed by not depending on that broadcast at all: a low-rate re-sweep (`RESWEEP_MS` = 1500) re-scans for a device whenever disconnected — which is exactly the "low-rate re-sweep" arduino.md §6 already specifies, so it also covers slow-booting modules. A per-device guard (`pendingPermissionDevice`) ensures permission is requested only once per device rather than on every sweep tick, and `connectFirstAvailable`/`closeConnection` are `@Synchronized` since the sweep and the USB broadcasts run on different threads. A denied permission is deliberately not re-prompted until the device is physically replugged.
- **Keyboard hid the Serial Monitor.** Tapping the send box panned the whole screen up and the log (and often the send box itself) disappeared behind the keyboard. Root cause: with edge-to-edge enabled the window pans rather than resizes for the IME. Fixed with `Modifier.imePadding()` on the monitor's root column so the `weight(1f)` log area shrinks and the send box lifts to sit just above the keyboard, plus `android:windowSoftInputMode="adjustResize"` on the activity as a cross-OEM belt-and-braces.

**Outstanding:**
- `TransportManager` is tied to `MainScreen`'s composition lifetime; an activity recreation (e.g. rotation with auto-rotate on) recreates it and reconnects. Acceptable for a dev instrument; revisit if the transport needs Application/Service scope later.
- No protocol parsing yet by design — the monitor colour-codes by TYPE word for display only. The `DashMessage` codec and the discovery/handshake state machine begin in 1.4.2.

---

## Version 1.3.13

**Status:** Complete

**Implemented:**
- Bar height stepper and element height stepper removed from the Settings panel and added to the edit-mode workspace in `MainScreen`, alongside the zone count control relocated there in 1.3.9. Both modify `editConfig` directly, so changes take effect immediately in the live bar — the user sees the result while still inside edit mode. Changes commit on SAVE or are discarded on CANCEL with the rest of the edit session. Height constraint logic preserved: when bar height decreases, element height auto-clamps to fit. Element height stepper shows "min"/"max" labels at the floor and ceiling as before
- Bar position toggle (TOP/BOTTOM) also relocated from the Settings panel into the edit workspace, sitting above the zone count control. Same deferred-commit behaviour — position change takes effect immediately in the live bar but is not persisted until SAVE. Settings panel SYSTEM BAR section now contains only EDIT BAR LAYOUT and RESET BAR LAYOUT
- RESET BAR LAYOUT relocated from Settings panel into the edit workspace. Resets zones and heights to factory defaults (position preserved). CANCEL discards the reset along with any other unsaved changes — no separate confirm dialog needed. Dead confirm dialog, its state variable, and the AlertDialog import removed from SettingsPanel
- Fixed: bar position toggle was not live during edit mode. Root cause: `activeConfig` was computed inside the bar Column, so the Column's alignment and TOP/BOTTOM branch both read `barConfig.position` (DataStore) rather than the in-progress `editConfig`. Fixed by hoisting `activeConfig` above the Column so all layout decisions reflect the current edit state immediately

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- None

---

## Version 1.3.12

**Status:** Complete

**Implemented:**
- Element box drag bounds clamped in `EditRuler`. `currentXPx` is now constrained to `[0, rulerWidthPx - widthPx]` so neither edge can be dragged off the ruler. Previously `elementDragOffsetPx` was a raw unconstrained accumulator — a large enough leftward drag produced a negative `xPx` passed to `ElementBox`, which applied it as `Modifier.padding(start = xPx.toDp())`. Compose rejects negative padding and crashes. Right-edge clamping added at the same time for symmetry. The element now feels like it hits an invisible wall at both edges during drag. Drop logic reads `elementDragOffsetPx` directly and is unaffected by the visual clamp

**Regressions:**
- None

**Fixes:**
- Crash when element box dragged off the left screen edge

**Outstanding:**
- None

---

## Version 1.3.11

**Status:** Complete

**Implemented:**
- Gesture events consumed unconditionally on every pointer event during drag (previously consumed only when `positionChanged()` returned true). This prevents gesture cancellation when the touch point moves outside the original bounds of the marker being dragged — the likely cause of unpredictable cancellation observed during testing
- Divider arrow pick-up confirmation: `isPressed` state set on DOWN and cleared on release; arrow alpha animates from 0.65 to 1.0 immediately on press, before any movement
- Element box pick-up confirmation: stroke width animates from 1dp to 2dp and alpha from 0.55 to 1.0 immediately on press, before any movement. The thickening stroke serves as the visual pick-up signal described in the 1.3.11 plan — no separate highlight treatment needed
- Ruler visual redesign: ruler background removed entirely — ruler area is now transparent, app content visible through it. A single 1dp horizontal centre track line drawn in barAccent2 at 35% alpha replaces the filled bar. Zone divider lines remain 1dp but now use barAccent2 at 30% alpha. Detent markers use barAccent2 at 40% alpha
- Element boxes redesigned from filled rounded rectangles to stroke-only outlines: 1dp stroke in barAccent2 at 55% alpha at rest, animating to 2dp at 100% alpha when dragging. Bound edge tints (red strips) remain as before, drawn as coloured rect overlays on the outline edges. `clip(RoundedCornerShape)` and `background` removed; replaced with `drawBehind` using `drawRoundRect` with `Stroke` style and `CornerRadius`
- New `barAccent2` theme token added to `DashColors` — default `Color(0xFF7878A0)`, a mid-tone blue-grey sitting between the invisible-dark `barAccent` and the content-bright `barText`. All ruler structural colours (track line, zone lines, detent markers, element box outlines, arrow resting state) read from this token. Independently themeable in version 2 with no component rework

**Regressions:**
- Element box drag and divider drag both broken on first build — boxes highlighted on press but could not be moved

**Fixes:**
- Root cause: `change.consume()` was called before reading `change.positionChange()`. In Compose, `positionChange()` returns `Offset.Zero` once a change has been consumed, so every drag frame reported zero delta and `onDrag` was never called with any movement. Fix: read `dx = change.positionChange().x` first, then call `change.consume()`. The intent of consuming all events (to prevent parent gesture interception) is preserved — just in the correct order

**Outstanding:**
- Element box dragged off the left screen edge crashes the app (negative padding). Captured as 1.3.12 — carries forward

**Notes:**
- The arrow alpha animation uses `spring(stiffness = Spring.StiffnessMedium)` consistent with the existing element box alpha animation — both feel immediate on press and return smoothly on release
- The ruler's transparent background means any colourful app content visible in the viewport will show through behind the ruler area during edit mode. This is intentional and looks clean in practice — the track line and outlines read clearly over typical dark automotive UI backgrounds. If a future theme has a very light or busy viewport background, adding an optional scrim to the ruler area is a straightforward addition via the theme token set
- `strokeWidthDp` in `ElementBox` is a Float from `animateFloatAsState` (in dp units); converted to pixels in `drawBehind` via `strokeWidthDp * density.density` where `density` is captured from `LocalDensity.current` in the composable scope

---

## Version 1.3.10

**Status:** Complete

**Implemented:**
- Snap detents reintroduced at 1/4, 1/3, 1/2, 2/3, and 3/4 of bar width with a 4dp pull threshold. At 4dp, snap only activates when the divider is genuinely close to a detent — the pull zone is tight enough to feel assistive rather than obstructive
- Escape mechanic: if the divider is already settled at a snap point when picked up, it enters free-move immediately. Snap re-engages once the divider has moved more than 4dp from the touch-down position. This eliminates the on-touch-down locking that caused snap to be removed in 1.3.7
- Detent position markers: short vertical tick marks appear at each snap fraction on the ruler while a divider is being dragged; they fade in on drag start and fade out on release. Gives the user a visible target to aim at
- Divider arrow turns red (`0xFFE53935`) when settled at a snap point, derived from config on every recomposition so it updates in real time during drag
- Element box bound edge tinting: a 3dp red strip is drawn on whichever edge of each element box is bound — touching a zone boundary or packed against an adjacent element. Bound state is computed from anchor group membership (LEFT group: left edges always bound; RIGHT group: right edges always bound; CENTRE group: inner edges bound, outer edges free). Strip is clipped by the box's rounded corners
- Live edge highlight during element box drag: while dragging an element box, the relevant edge turns red in real time as a preview of where the element will land. If the dragging box is overlapping another element's footprint, the edge shown reflects the target's anchor (the anchor the dragging element will inherit on swap). If dragging in open space, the edge reflects the intended anchor from the thirds-based position (left third → left edge red, right third → right edge red, centre → neither)
- Element box swap on overlap: dropping an element box onto another element's footprint swaps their positions — the dragging element takes the target's slot (zone, index in list, anchor), the target takes the dragging element's old slot. Dropping into open space uses the existing thirds model and appends to the anchor group as before. This replaces the previous behaviour where moving an element anywhere near another element in the same anchor group would cause unintended displacement

**Regressions:**
- None

**Fixes:**
- N/A

**Outstanding:**
- Element box dragged off the left screen edge crashes the app (negative padding). Captured as 1.3.12

**Notes:**
- The snap threshold of 4dp is deliberately tight and may need adjustment after on-device testing. The consensus before implementation was to start tight and expand if needed — 6dp is the next step if 4dp disappears into the noise
- The escape mechanic uses `totalDragPx` (signed net displacement from touch-down) rather than total distance traveled. If the user reverses direction, accumulated displacement decreases — snap stays disabled until they have committed to a clear move away from the starting point
- `isSnapped` is derived from config via `remember(config, dividerIndex, rulerWidthPx)` rather than a separate state variable. Since config updates every drag frame via `onConfigChange`, the arrow colour updates in real time with no additional state management
- The element swap uses a content-moves-between-slots model: each slot retains its zone, index, and anchor; only the element `id` and `type` move. This preserves the structural integrity of the zone layout
- Ruler visuals (colours, sizes, overall aesthetic) are acknowledged placeholder — aesthetics are deferred; functionality is the priority for the 1.3.x edit mode sequence

---

## Version 1.3.9

**Status:** Complete

**Implemented:**
- Zone count control (1/2/3 buttons) moved from the Settings panel into the edit workspace. Zone count is now part of the edit session — buttons update `editConfig` in memory, committed on SAVE or discarded on CANCEL
- `withZoneCount()` moved from a private extension function in `SettingsPanel.kt` to a method on `SystemBarConfig` in `SystemBarModel.kt`, where it belongs as a pure model transformation
- Zone distribution preset buttons (`DISTRIBUTION` row) removed entirely from Settings. The `ZoneDistribution` data class, both preset lists (`ZONE_DISTRIBUTIONS_2`, `ZONE_DISTRIBUTIONS_3`), `distributionActive()`, and `withDistribution()` are all deleted
- Edit workspace is now a `Column` — ZONES label and 1/2/3 buttons above, SAVE/CANCEL row below
- Settings System Bar section now contains only: position toggle, bar height stepper, element height stepper, EDIT BAR LAYOUT button, RESET button

**Regressions:**
- None

**Fixes:**
- N/A

**Outstanding:**
- None

**Notes:**
- **Zone count moved to edit workspace — deliberate design decision.** Previously, changing zone count wrote directly to DataStore with no way to undo. Moving it into the edit session means the user can experiment with zone count, see the result on the bar in real time, and commit or discard it alongside any divider positions they have changed. The edit session is the correct scope for all layout decisions
- **Distribution presets removed — deliberate design decision.** The preset buttons (`1:1`, `1:2`, `2:1`, etc.) existed to give the user a way to set zone widths before divider dragging existed. They predate 1.3.6/1.3.7. Now that the user can drag a divider to any position they want, the presets are redundant. Removing them simplifies the Settings panel and eliminates a UI element that was already superseded by a better mechanism

---

## Version 1.3.8

**Status:** Complete

**Implemented:**
- DONE button replaced with separate SAVE and CANCEL actions, centred in the screen while edit mode is active
- CANCEL discards all in-progress edits and exits edit mode with no DataStore write. `barConfig` (the DataStore-backed flow) is the implicit snapshot — it is never written until SAVE is pressed, so nulling `editConfig` restores the bar exactly to its last saved state
- SAVE commits the in-memory `editConfig` to DataStore then exits edit mode, identical to what DONE did
- Buttons are side by side in a `Row` centred on screen: CANCEL (grey, `0xFF424242`) on the left, SAVE (green, `0xFF2E7D32`) on the right. Both use the same monospace style as all other DASH text controls

**Regressions:**
- None

**Fixes:**
- N/A

**Outstanding:**
- None

**Notes:**
- The snapshot mechanism requires no additional state variable. `barConfig` comes from DataStore and is only written on SAVE — it is naturally the restore point for CANCEL
- Buttons are in the main content area (the empty space between bar/ruler and the opposite screen edge), consistent with the roadmap intent that the content area becomes the edit workspace. Additional controls move into this workspace in 1.3.9 (zone count) and 1.3.12 (height steppers)

---

## Version 1.3.7

**Status:** Complete

**Implemented:**
- `EditRuler` composable — a 44dp horizontal strip that appears on the bar's inner side when edit mode is active, separated from the bar by an 8dp gap. For a bottom-docked bar it sits above; for a top-docked bar it sits below. The ruler is the complete interaction surface for edit mode; the bar itself is never touched during editing
- Ruler entry/exit transitions — `AnimatedVisibility` with `expandVertically` (spring, medium bouncy) and `fadeIn`/`fadeOut`. The bar stays pinned to the screen edge; the ruler grows inward from the bar. Exit is a quick tween shrink in the same direction
- Divider arrow markers — each zone boundary in the ruler is represented by a filled triangle pointing back toward the bar, centred on an 80dp touch target. Drag is free — position follows touch exactly, clamped only to maintain a 48dp minimum width on each adjacent zone. `rememberUpdatedState` for stable gesture capture. The arrow sits atop a subtle 1dp vertical line that marks the zone boundary in the ruler
- Element footprint boxes — one box per element per zone, positioned to mirror each element's rendered position in the bar. `SystemBar` now fires `onElementMeasured(id, widthPx)` via `Modifier.onSizeChanged` on each element wrapper; `MainScreen` collects these into `elementWidths: Map<String, Int>`. The ruler's `computeElementPositions()` uses those widths and mirrors the Zone Layout's anchor-group packing (padding-aware, LEFT/CENTRE/RIGHT groups). Boxes are 32dp tall, rounded, `barAccent` fill at 40% opacity; dragging brightens them to 75% with a spring-animated alpha transition
- Element repositioning — dragging a box left or right moves it continuously; on release `computeNewConfig()` determines the target zone (which zone range the drop centre fell in) and infers a new anchor from the drop position within that zone (left third → LEFT, middle → CENTRE, right → RIGHT). The element is added to the end of the target zone's element list. The config update is immediate; the box reappears at its new natural position in the recomposed ruler
- `DraggableDivider` removed — the bar now renders plain 1dp dividers in all modes. The bar no longer knows about edit mode at all: `editMode` and `onConfigChange` parameters removed from `SystemBar`. Edit state is held entirely in `MainScreen` and driven through `EditRuler`
- 1.3.6 bar border/tint removed — the ruler's presence is the sole unambiguous signal that edit mode is active. No duplicate mode indicator

**Regressions:**
- Divider arrow markers were completely unresponsive to touch on first build

**Fixes:**
- **Hit area bug — `Modifier.offset` does not move the touch target.** `Modifier.offset { IntOffset(x, y) }` is a layout modifier that places the child at the offset position in the parent's coordinate system, but reports its own bounds as `(0, 0)` to `(childWidth, childHeight)` — the size of the child, not the size of the child plus the offset. Compose's hit testing descends the layout tree checking each node's reported bounds. When the touch point is at the drawn position (e.g. dividerX = 600dp) but the node's reported bounds only cover `(0, 0)` to `(40dp, 44dp)`, the hit test fails and the gesture handler is never reached. Fixed by replacing `Modifier.offset(x)` with `Modifier.padding(start = xDp)` throughout `EditRuler`. `padding` is also a layout modifier but it includes the padded space in the node's own reported bounds — a `padding(start = 580dp).width(40dp)` node correctly reports bounds of `(0, 0)` to `(620dp, 44dp)`, and the touch at 590dp passes the hit test and reaches the inner `pointerInput`
- Touch target enlarged from 40dp to 80dp after on-device testing confirmed that 40dp was registering but felt unreliable
- **Snapping removed.** Detent snap at 1/4 / 1/3 / 1/2 / 2/3 / 3/4 (carried over from 1.3.6's `DraggableDivider`) was rebuilt into the ruler but removed after on-device testing. With a 12dp snap threshold, the divider would lock to the nearest detent on touch-down, requiring the user to drag significantly before it registered any movement — indistinguishable in feel from the divider not responding. Snap may be reintroduced in a later version if there is a genuine case for it. For now drag is free

**Outstanding:**
- Element pick-up affordance (scale-up on press, before drag begins) → 1.3.11
- Drag gesture continuation if touch moves outside marker bounds → 1.3.11

**Notes:**
- `elementWidths` in `MainScreen` is populated by the bar's ongoing rendering — by the time the ruler first appears it is already fully populated. The `onSizeChanged` path fires only when measured widths change (element height change, config change), so there is no recomposition loop
- The `computeElementPositions()` function mirrors the Zone Layout's anchor-group math exactly, including the 8dp horizontal padding that the Layout applies to each zone. The ruler's footprint boxes will sit at the same X positions as the real elements in the bar
- `computeNewConfig()` infers anchor from drop position within the target zone (thirds model). This is intentionally simple for 1.3.7 — fine-grained reordering within an anchor group is a future refinement
- The `Column` wrapping bar + ruler in `MainScreen` is pinned to the same screen edge as the bar was previously. Because the Column is `BottomCenter`/`TopCenter`-aligned, the bar stays against the screen edge and the ruler expands inward as the Column grows — the DONE button's absolute position (against the same edge) continues to overlay the bar correctly
- The 1.3.10 roadmap entry references visual feedback when a divider is at a snap point. If snap is not reintroduced, that entry will need revising when 1.3.10 is reached

---

## Version 1.3.6

**Status:** Complete

**Implemented:**
- Edit mode state — `editMode: Boolean` and `editConfig: SystemBarConfig?` hoisted in `MainScreen`. Edit mode is runtime-only, never persisted. `editConfig` is a live shadow of the bar config during editing; it is initialised from prefs when edit mode is entered and written back to DataStore only when DONE is tapped
- Entry point — "EDIT BAR LAYOUT" button added to the System Bar section of the settings panel. Tapping it closes settings and activates edit mode immediately. Placed in the System Bar section for now; will relocate to a dedicated Appearance section when the full settings tree is built in a later version
- Visual state change — the bar gains a 2dp border (barText at 35% opacity) when edit mode is active. Zone dividers thicken to 2dp at 60% opacity — visually distinct from the normal 1dp at 30% accent
- Draggable zone dividers — in edit mode each divider is replaced by a 16dp touch target containing the 2dp visual line. `pointerInput(Unit)` with `detectDragGestures` drives the drag; `rememberUpdatedState` ensures the gesture handler always reads the latest config without restarting mid-drag. Drag delta is converted to a zone fraction delta and applied to the two adjacent zones, keeping their combined fraction constant
- Detent snap — snap points at 1/4, 1/3, 1/2, 2/3, 3/4 of bar width. Within 12dp of a snap point the divider pulls to it; drag past the threshold and it releases. All positions between snap points are valid — snap assists alignment, it does not constrain placement
- Minimum zone width — 48dp per zone. The clamp prevents either adjacent zone from collapsing below a usable size; fractions are adjusted conservatively so the combined fraction is always conserved
- DONE button — a green "DONE" button overlays the left end of the bar during edit mode (opposite the settings button, which is right-anchored by default). Tapping it saves `editConfig` to DataStore and exits edit mode. Config is written once on exit — not on every drag frame
- Settings button in edit mode — `DashAction.OpenSettings` is ignored while `editMode` is true. DONE is the only exit path

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- Element drag-and-drop repositioning → 1.3.7

**Notes:**
- Edit mode was originally planned as a single version. It was split into 1.3.6 (infrastructure + zone divider dragging) and 1.3.7 (element drag-and-drop) because Compose drag-and-drop for production-quality element repositioning — ghost element, anchor snapping, snap guidelines, per-element sizing — is a substantial undertaking on its own. The split keeps each version completable cleanly and allows divider dragging to be tested and confirmed before element repositioning is layered on top
- `BoxWithConstraints` wraps the `SystemBar` Row to provide `barWidthPx` synchronously for drag fraction calculations. `barWidthPx` is passed to `DraggableDivider` via `rememberUpdatedState` so the gesture handler stays consistent if the bar is ever resized while edit mode is active
- The "EDIT BAR LAYOUT" entry point will move to a dedicated Appearance section of settings when the full settings tree is built. It is deliberately a simple callback — relocation will be trivial
- **Design revision — 1.3.7 onward:** The original plan for 1.3.7 (long-press-drag directly on the bar with ghost element and floating offset preview) was superseded by a design review before implementation began. The new model relocates all editing interaction to an adjacent ruler strip — the bar itself is never touched during editing. This is a substantially different approach to edit mode and replaces the direct-bar-drag model described in the 1.3.6 Outstanding section. The ruler model is now the plan of record from 1.3.7 onwards; the original approach has been retired without being built
- **Roadmap additions — 1.3.12 and 1.3.13:** Following a further design review, two additional versions have been added to the 1.3.x sequence. 1.3.12 relocates the bar height and element height steppers from the main Settings panel into the edit-mode workspace (alongside the zone count control moved in 1.3.9), with real-time resize feedback while inside edit mode. 1.3.13 removes height entirely from the DashElement API surface — the Zone Layout will impose a fixed-height constraint on each element's box before the element's own composable runs, making height an invisible platform concern rather than something element authors need to handle. Both changes are captured in roadmap.md; implementation follows once the preceding edit mode versions are complete
- **Roadmap revision — 1.3.13 updated, 1.3.14 added:** Following a further design review, the 1.3.13 entry has been substantially revised and a new 1.3.14 added. 1.3.13 now covers the complete element sizing contract: height invisible to element authors (imposed by DASH before element code runs), width derived from natural aspect ratio rather than negotiated by the element (proportional scaling like a photograph), rigid-by-default fit-or-no-fit behaviour using the existing zone overflow warning, and compressible elements documented as a future opt-in rather than part of this version. 1.3.14 is a new Spacer architecture correction: the 1.3.5 decision to handle Spacer outside the ElementRegistry is superseded — the Spacer is a standard rigid DashElement with no special layout handling, flexible gaps come from placing multiple instances rather than a variable-width single instance, and building the actual Spacer element is deferred to version 2 alongside Clock, Volume, and Now Playing. This entry explicitly corrects and supersedes the Spacer reasoning recorded in 1.3.5, so that the project history does not carry two contradictory decisions forward unreconciled

---

## Version 1.3.5

**Status:** Complete

**Implemented:**
- Zone splitting: the user can split the system bar into 1, 2, or 3 zones via a zone count control in settings (ZONES: 1 / 2 / 3 buttons). Default state is one zone spanning the full width
- Zone width distribution: when 2 or 3 zones are active, preset distribution buttons appear (DISTRIBUTION). 2-zone presets: 1:1, 1:2, 2:1. 3-zone presets: 1:1:1, 1:2:1, 2:1:1, 1:1:2. The active preset is highlighted. Draggable dividers arrive with edit mode in 1.3.6
- Zone dividers: a 1dp vertical line is drawn between zones at 30% accent opacity. In 1.3.6 these become the draggable edit-mode handles
- Element packing layout: the `Zone` composable is rewritten as a custom Compose `Layout`. Elements are grouped by anchor (LEFT, CENTRE, RIGHT) and packed without overlap — LEFT group packs left-to-right from the left edge; RIGHT group packs left-to-right as a unit flush against the right edge; CENTRE group packs as a unit centred in the zone. All elements are centred vertically within the bar. This layout is the foundation 1.3.6 drag-and-drop builds on
- `ElementType.SPACER` added to the catalogue. `ElementPlacement` gains `spacerWidthDp: Int?` (null for all non-spacer types). Spacer constants added to `SystemBarConfig`: default 16dp, min 4dp, max 120dp, step 4dp. Spacer elements are rendered as invisible sized boxes by the Zone layout. No UI for placing spacers yet — that arrives with edit mode in 1.3.6
- Zone management on count reduction: elements in removed zones are migrated to zone 0 before the zone is dropped

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- Drag-and-drop edit mode (element placement, zone divider dragging, snap guidelines) → 1.3.6

**Notes:**
- `withZoneCount()` and `withDistribution()` are private extension functions on `SystemBarConfig` inside `SettingsPanel.kt` — they are settings-panel concerns and don't need to live in the model
- The packing layout assumes measurables correspond 1:1 with `zone.elements` in list order — this invariant must be maintained when adding new element types. Each element type in the `when` branch must emit exactly one composable node
- Spacer is handled directly in the Zone Layout rather than registered in `ElementRegistry`. The registry is for content elements. Spacer is structural and carries no content or SDK surface
- Zone distribution fractions use float comparison with 0.01f tolerance for preset active-state detection

---

## Version 1.3.4

**Status:** Complete

**Implemented:**
- Element height is now a user-controlled dp value stored in `SystemBarConfig.elementHeightDp` (default 36dp). Elements receive this as `scope.heightDp: Dp` in `ElementScope` and are responsible for rendering sensibly within it — the same contract an SDK element developer will receive
- `SizeVariant` enum removed entirely. It was a stepped abstraction over the thing users actually care about — a concrete size. The dp system is direct, consistent with the bar height control, and unambiguous for SDK element authors
- `ElementPlacement.variant` removed. Element height is global for now; per-element sizing arrives with edit mode in 1.3.6
- Settings panel System Bar section updated — bar height control labelled "SYSTEM BAR SIZE", element height control labelled "ELEMENT SIZE" below it, using the identical +/− stepper pattern
- Element size boundaries: minimum 24dp (label shows "min", − greyed), maximum one step below bar height (label shows "max", + greyed). When bar height is reduced below the element ceiling, element height is auto-clamped in the same save operation — config is always internally consistent
- `AlertsAreaElement` and `SettingsButtonElement` render proportionally to `scope.heightDp` — font and icon sizes scale as a fixed fraction of element height so the visual scales smoothly across the full range
- `SettingButton` composable gains an `enabled` parameter with distinct disabled colours — used for the element size steppers at their limits

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- Zone splitting (up to three), inter-element snap packing, Spacer element → 1.3.5
- Drag-and-drop edit mode → 1.3.6

**Notes:**
- Element height proportions: `AlertsAreaElement` font at 30% of height, padding at 28%/11% horizontal/vertical. `SettingsButtonElement` icon at 55% of height. These feel right at the 36dp default and at both limits — adjust per on-device testing
- The `enabled = false` greyed state on `SettingButton` uses `disabledContainerColor = Color(0xFF1A1A1A)` and `disabledContentColor = Color(0xFF444444)` — visually distinct from the active and inactive states without being harsh
- **Departure from interface.md — element sizing model:** interface.md specifies element sizing as a percentage of bar height. This version implements a direct dp control instead. The dp model keeps bar height and element height as fully independent decisions, which is cleaner for the user and unambiguous for SDK element authors. interface.md should be updated to reflect this decision
- **Departure from interface.md — soft limit behaviour:** interface.md specifies amber soft limit warnings at the lower size boundary. This version instead shows a "min" label and greys the − button at 24dp, and a "max" label with greyed + button at the ceiling. The boundary is communicated through the control itself rather than a separate warning indicator. interface.md should be updated to reflect this decision

---

## Version 1.3.3

**Status:** Complete

**Implemented:**
- Theme token system — `DashColors` data class carrying three named colour tokens: `barBackground`, `barAccent`, `barText`. Exposed via `LocalDashTheme`, a `compositionLocalOf` that any composable in the DASH tree can read without being wired through function parameters
- `DashColors.dark()` factory provides the default token set, carrying the colour values previously hardcoded across three files. Visually identical to 1.3.2 — the change is architectural only
- `MainScreen` provides `LocalDashTheme` at the top of the composition tree alongside the existing `LocalDashScale` provider. Version 2 introduces user-facing presets and theme switching by providing a different `DashColors` instance here — no component below changes
- `SystemBar` — hardcoded `BAR_COLOR` private val removed; bar background now reads `LocalDashTheme.current.barBackground`
- `AlertsAreaElement` — pill background reads `LocalDashTheme.current.barAccent`; ALERTS label reads `LocalDashTheme.current.barText.copy(alpha = 0.55f)`. Subdued text is a semantic derivation of `barText`, not a separate token
- `SettingsButtonElement` — gear icon reads `LocalDashTheme.current.barText`
- New file `ui/theme/DashTheme.kt` — `DashColors`, `LocalDashTheme`. Clean home for all theme infrastructure as the token set grows

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- Element percentage-of-bar-height sizing, size variants (S/M/L), soft-limit amber warnings → 1.3.4
- Zone splitting (up to three), inter-element snap packing, Spacer element → 1.3.5
- Drag-and-drop edit mode → 1.3.6

**Notes:**
- `compositionLocalOf` chosen over `staticCompositionLocalOf` — the former only recomposes readers when the value changes, which is the correct behaviour for version 2 live theme switching. Static would recompose the entire subtree
- **How to add a token to the system:** (1) Add a field with a default value to `DashColors` in `ui/theme/DashTheme.kt` — e.g. `val barHighlight: Color = Color(0xFF4A4AFF)`. (2) Update `DashColors.dark()` (and any future preset factories) with a considered value. (3) Read it anywhere in the composition with `LocalDashTheme.current.barHighlight`. That is the entire change. No call sites break, no provider changes needed, no migration required for stored data

---

## Version 1.3.2

**Status:** Complete

**Implemented:**
- Bar height is now controlled by the dp setting alone. `SystemBar` previously computed `barHeight = config.heightDp.dp * LocalDashScale.current`, meaning both the dp height control in System Bar settings and the DASH Scale +/- control affected bar size simultaneously. The scale multiplier has been removed — `barHeight = config.heightDp.dp`. The DASH Scale setting remains in the settings panel and its value is still persisted; it simply has no wired consumer at this stage
- Unused `LocalDashScale` import removed from `SystemBar.kt`

**Regressions:**
- None

**Fixes:**
- Two controls affecting bar height — the dp height setting and the DASH Scale multiplier were both acting on bar size. Removing the multiplier from bar height calculation resolves the conflict

**Outstanding:**
- Theme token system → 1.3.3
- Element percentage-of-bar-height sizing, size variants (S/M/L), soft-limit amber warnings → 1.3.4
- Zone splitting (up to three), inter-element snap packing, Spacer element → 1.3.5
- Drag-and-drop edit mode → 1.3.6

**Notes:**
- DASH Scale is intentionally parked rather than removed. It does not have a clear job while the bar is the only chrome element. It will be reintroduced as a multiplier across all chrome elements once there is more than one thing for it to scale uniformly
- The DASH Scale section remains visible in settings — it is not hidden or greyed out. Its persistence and the +/- controls still work; the value simply has no active consumer until it is properly wired back in a later version

---

## Version 1.3.1

**Status:** Complete

**Implemented:**
- System bar reworked from a placeholder coloured `Box` into a real, configurable, persistent interface element — `ui/systembar/SystemBar.kt`. Renders at top or bottom per config; height is the user-defined base (`SystemBarConfig.heightDp`) multiplied by the live DASH UI scale, so it stays consistent with the rest of DASH chrome
- Persisted data model — `ui/systembar/SystemBarModel.kt`. `@Serializable` `SystemBarConfig` → `ZoneConfig` → `ElementPlacement`, with `BarPosition`, `ElementType`, `ElementAnchor`, `SizeVariant`, `ElementKind` enums. Deliberately shaped now to hold the full 1.3.x feature set (multiple zones, per-element anchors and variants) so the stored format never needs migrating as later increments land. `SystemBarConfig.default()` guarantees the two mandatory elements are always present
- Persistence from the first commit — bar config stored as JSON in the existing DataStore via `kotlinx.serialization` (`Json { ignoreUnknownKeys = true; encodeDefaults = true }`). `DashPreferences` gains `systemBarConfig` flow, `saveSystemBarConfig()`, and `resetSystemBar()`. Decode is wrapped in `runCatching` with a fallback to `default()`, so an absent, corrupt, or schema-changed config never crashes — it cleanly returns to default
- Two mandatory elements built — `AlertsAreaElement` (informational, placeholder visual until transport lands in 1.4.x) and `SettingsButtonElement` (interactive). The settings button enforces a hard 48dp minimum touch target via `Modifier.sizeIn`, regardless of bar height, exactly as interface.md mandates — the visible glyph may shrink, the touch area never does
- SDKable element framework — `DashElement` interface, `ElementScope`, `DashAction`, and `ElementRegistry`. Built-in elements receive nothing a community element could not; this is the seed of the v3 Element SDK. Interactive elements reach the platform only through the narrow `DashAction` channel (currently just `OpenSettings`)
- Settings relocated behind the settings button — `ui/settings/SettingsPanel.kt`. The debug controls that previously occupied the centre of the main screen (App Density, Rotation, DASH Scale, Splash Screen, Launcher, Exit) now live in a full-screen scrollable panel opened only from the bar's settings button. This is the intended model: the settings button is the one route into settings
- New System Bar settings section — position toggle (TOP/BOTTOM), height −/+ (40–120dp in 4dp steps), and a "Reset bar layout" button guarded by a confirmation dialog that returns the bar to its default (bottom, 56dp, alerts + settings)
- Not-default-launcher banner and diagnostic overlay now position themselves opposite the bar so they never collide with it when the bar is moved to the top

**Regressions:**
- None observed in build. On-device verification pending (see Outstanding)

**Fixes:**
- None

**Outstanding:**
- Scale/height conflict in the bar — DASH Scale multiplier applied to bar height alongside the dp control, giving two controls that both affect bar size → 1.3.2
- Element percentage-of-bar-height sizing, size variants (S/M/L), and soft-limit amber warnings → 1.3.4
- Zone splitting (up to three), inter-element snap packing, and the Spacer element → 1.3.5
- Drag-and-drop edit mode (long-press pickup, snap guidelines, draggable zone dividers) → 1.3.6
- Theme token system — named colour tokens (barBackground, barAccent, barText) as the single source of truth for system bar and element colours; prerequisite for settings panel visual identity inheritance in 1.5.x → 1.3.3
- On-device confirmation on the Pixel 8 / tablet: bar renders, settings button opens panel, position and height persist across an app kill, reset works

**Notes:**
- `kotlinx.serialization` added (plugin + `kotlinx-serialization-json` 1.7.3) — first use of JSON persistence in DASH. Flat preference keys don't map cleanly to a nested bar config, so JSON-in-DataStore is the right tool here; the existing flat keys (density, scale, splash, etc.) are untouched
- Version code bumped to 3, versionName to 1.3.1. The build file had been sitting at 1.2.0/2, one behind the 1.2.1 changelog entry — now reconciled
- Bar element rendering currently uses a single full-width zone with anchor-based placement (left/centre/right). The `SystemBar` composable already iterates zones with weighted widths, so multi-zone in 1.3.3 is an extension rather than a rewrite

---

## Version 1.2.1

**Status:** Complete

**Implemented:**
- Splash screen now triggers on screen wake as well as cold boot — when the tablet wakes from sleep and DASH is the default launcher, the splash appears on top of DASH after the lock screen is dismissed
- `pendingWakeSplash` flag added to MainActivity — a dynamic `BroadcastReceiver` for `ACTION_SCREEN_ON` sets the flag when the screen wakes (conditional on DASH being the default launcher). The flag is consumed in `ON_RESUME`, which triggers the splash at the point DASH actually becomes visible rather than when the screen first turns on. This ensures the 2.5s timer starts after the lock screen is dismissed, not before
- Receiver registered with `ContextCompat.registerReceiver` and `RECEIVER_NOT_EXPORTED` — correct API 33+ handling. `ACTION_SCREEN_ON` cannot be registered in the manifest; dynamic registration is required

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- None

**Notes:**
- The flag approach (rather than directly setting `showSplash = true` from the receiver) is necessary because the lock screen sits between `ACTION_SCREEN_ON` and DASH becoming visible. Triggering the splash directly from the receiver starts the 2.5s timer while the lock screen is still showing — by the time the user unlocks, the splash has already auto-dismissed

---

## Version 1.2.0

**Status:** Complete

**Implemented:**
- Launcher manifest declaration — HOME and DEFAULT_HOME intent filters added to MainActivity. `android:launchMode="singleTask"` and `android:stateNotNeeded="true"` added — standard launcher attributes preventing multiple instances and allowing Android to safely kill launcher state
- BootReceiver — `RECEIVE_BOOT_COMPLETED` permission and `launcher/BootReceiver.kt` registered in manifest. Receives `BOOT_COMPLETED` and launches MainActivity. Foundation for 1.4.x startup reconciliation
- Default launcher prompt — on every `ON_RESUME`, checks whether DASH is the default home app. If not, a purple banner is shown across the top of the screen; tapping it opens the system launcher-selection dialog (API 29+: `RoleManager.createRequestRoleIntent(ROLE_HOME)` — shows a proper "make DASH your home app?" system prompt; API 24–28: opens `Settings.ACTION_HOME_SETTINGS`). Banner disappears automatically once DASH is set as default
- Change launcher escape button — "CHANGE LAUNCHER →" button permanently available in debug settings. Opens `Settings.ACTION_HOME_SETTINGS` (falls back to `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`). Allows switching away from DASH on development hardware without needing ADB
- Splash screen — full-screen overlay shown on cold boot (`savedInstanceState == null`). Two modes: COLOUR (solid fill, three presets: Black, DASH Navy, Dark Slate) and IMAGE (user-selected via system photo picker, URI persisted with `takePersistableUriPermission`). Auto-dismisses after 2.5 seconds with 400ms fade-out; tap also dismisses. "PREVIEW SPLASH" button in debug UI allows testing without rebooting. Image loading uses `ContentResolver` + `BitmapFactory` — no additional dependencies required
- Navigation bar suppression — carried forward unchanged from 1.1.4. `hideSystemBars()` hides both status bar and navigation bar via `WindowInsetsControllerCompat`; `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` provides the transient fallback. Reapplied on every `onWindowFocusChanged`. Confirmed correct behaviour as a launcher

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- None

**Notes:**
- Ignition-driven screen wake/sleep behaviour deliberately deferred — depends on 1.4.x transport layer's system message parsing (`SYSTEM:ignition:on` / `SYSTEM:ignition:off`). Standard Android screen behaviour is fully adequate at this stage: DASH is the home app, so it is what the user sees when the screen wakes. No additional work is needed or appropriate here
- The `CHANGE LAUNCHER →` button is for development use on the Pixel 8. It will remain in whatever settings panel replaces the current debug UI in 1.6.x
- Version code bumped to 2 alongside 1.2.0

---

## Version 1.1.1

**Status:** Complete

**Implemented:**
- Full Android project created from scratch — Kotlin, Jetpack Compose, minSdk 24, package com.dash.android
- App density preset system — Compact (160dpi), Normal (240dpi), Comfortable (320dpi), Large (480dpi) — wired to Android system density via IWindowManager reflection. Requires WRITE_SECURE_SETTINGS permission granted once via ADB during development; production system app gets it natively
- DASH UI scale — fluid +/- system in 0.1x increments (0.5x–2.0x, default 1.0x) — drives DASH chrome size independently of system density
- Placeholder system bar — a horizontal strip at the bottom of the screen whose height responds to DASH scale, proving the scale system works
- Diagnostic overlay — top-left corner readout showing screen resolution, native DPI, selected density preset, and current scale value
- Exit button — resets system density to device default before closing, returning the phone to its normal state
- DASH density isolation — attachBaseContext override using DENSITY_DEVICE_STABLE ensures DASH chrome is immune to system density changes
- DataStore preference persistence — density preset and scale value survive app restarts
- interface.md updated — DASH UI scale changed from named presets to fluid stepwise system to match the actual design intent

**Regressions:**
- None at this stage — first implementation

**Fixes:**
- None required

**Outstanding:**
- Density preset system not functioning on physical hardware (Pixel 8) — buttons have no visible effect despite WRITE_SECURE_SETTINGS being granted via ADB. Root cause unknown at close of this version — carries forward to 1.1.2
- Scale independence verification pending — confirming that DASH scale changes do not affect third party apps

**Notes:**
- The ADB permission grant (`adb shell pm grant com.dash.android android.permission.WRITE_SECURE_SETTINGS`) is a one-time development setup step. Without it the density buttons have no effect — no crash, just silently no-ops
- System density persists while DASH is in the background by design — this is what makes the Spotify test possible
- DASH UI scale uses stepwise +/- rather than named presets — this was clarified with Roger during this session and interface.md was updated accordingly
- The gradlew wrapper was borrowed from a sibling project (AE2_infinitequantumstorage) rather than generated by Android Studio, since no Android Studio new-project wizard was used

---

## Version 1.1.2

**Status:** Complete

**Implemented:**
- Rotation controls added to test environment — AUTO, PORTRAIT, and LANDSCAPE buttons
- AUTO mode uses sensor-driven rotation (default on first launch)
- PORTRAIT and LANDSCAPE buttons lock orientation and disable auto-rotate
- Selection persists via DataStore — rotation state survives app restarts
- Manifest screen orientation constraint removed — orientation now controlled entirely in code via `requestedOrientation`

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- Density preset system still not functioning on physical hardware — carries forward to 1.1.3

**Notes:**
- `LaunchedEffect(autoRotate, lockedOrientation)` in MainScreen applies orientation changes reactively as prefs change — no activity restart required

---

## Version 1.1.3

**Status:** Complete

**Implemented:**
- App Density capability check — on startup, DASH attempts the density-change operation and catches any failure (reflection failure, security exception, permission denial, or anything else). If the probe fails for any reason, the App Density section is greyed out with the message: "App density requires elevated system permissions not available on this installation."
- If the probe succeeds the setting works as originally designed — buttons are shown and functional

**Regressions:**
- None

**Fixes:**
- App Density previously swallowed all exceptions silently, giving no feedback when the operation failed. The capability check surfaces this correctly

**Outstanding:**
- None for 1.1.x — density and scale foundation is complete. App Density confirmed unavailable on Pixel 8 (capability check working correctly). Full verification of App Density deferred to a board where DASH runs as a system app and the call path is natively available

**Notes:**
- DASH UI Scale (the +/− buttons) works correctly on the Pixel 8 — this is DASH's own internal scaling and requires no system permissions
- App Density (system-wide DPI) requires IWindowManager access that Android's hidden API restrictions block for non-system apps on recent Android versions. The capability check correctly identifies this regardless of the specific restriction — the same check will pass on the Orange Pi when DASH is installed as a system app
- The probe sets density to `DENSITY_DEVICE_STABLE` (the device's native DPI), which is visually a no-op but exercises the full reflection call path

---

## Version 1.1.4

**Status:** Complete

**Implemented:**
- System bars (status bar and navigation bar) hidden on launch — DASH occupies the full screen
- `onWindowFocusChanged` override reapplies the hide whenever the window regains focus — notifications and dialogs can temporarily restore bars, but DASH reclaims full screen when it returns to the foreground
- Swipe from edge temporarily reveals bars then auto-hides (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) — keeps back gesture accessible during development without permanently surfacing the bars

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- None

**Notes:**
- Implemented via `WindowInsetsControllerCompat` — handles API differences between Android 7 (minSdk 24) and current versions transparently

---

## Version 1.1.5

**Status:** Complete

**Implemented:**
- App Density UI is capability-conditional. On startup, DASH probes `setForcedDisplayDensityForUser()` via `checkCapability()`. Two exclusive branches result:
  - **System app path** (probe succeeds — Orange Pi production): DASH shows its own native preset buttons (Compact / Normal / Comfortable / Large) that apply density directly via IWindowManager. No external navigation required
  - **Consumer device path** (probe fails — Pixel 8, any sideloaded build): DASH reads the current system density freely from `Settings.Global display_density_forced` (no permissions needed) and shows a button that opens Android's Display size and text settings screen directly. The current density label refreshes on every `ON_RESUME` so it updates immediately when the user returns from Android settings
- Deep link targets `Settings$TextReadingPreferenceActivity` (the Display size and text screen) directly on AOSP/Pixel devices, with a silent fallback to `Settings.ACTION_DISPLAY_SETTINGS` on devices where that component doesn't exist
- DensityManager gains `readCurrentSystemDpi()`, `tryWriteSystemDpi()`, and `formatDpi()` — IWindowManager reflection methods retained intact for the system app path

**Regressions:**
- None

**Fixes:**
- None

**Outstanding:**
- None

**Notes:**
- The two paths are mutually exclusive — system privileges mean DASH controls density natively; no privileges mean DASH defers to Android settings. No hybrid or intermediate state is shown
- `display_density_forced` in Settings.Global is the exact key Android's own Display Size setting writes — reading it requires no permissions and is always accurate
- Shizuku is the next step for consumer-device density control — it grants IWindowManager access via ADB without root, enabling the native preset path on sideloaded builds. Carries forward to v1.1.6
- The `tryWriteSystemDpi()` method (Settings.Global.putInt path) exists in DensityManager but is not exposed in the UI — it was a secondary investigation path that was not pursued. Can be removed in a future cleanup if Shizuku proves to be the right next step

---

## Version 1.1.6

**Status:** Complete

**Implemented:**
- `readCurrentSystemDpi()` rewritten to read from `context.applicationContext.resources.displayMetrics.densityDpi` instead of `Settings.Global.display_density_forced`
- `tryWriteSystemDpi()` removed — it was an unexposed investigation path from v1.1.5 with no UI entry point

**Regressions:**
- None

**Fixes:**
- Consumer path density label ("Current: X dpi") was stuck showing "Compact (160 dpi)" regardless of actual system density. Root cause: `display_density_forced` in Settings.Global had been written directly to 160 by the ADB test in v1.1.5, and Android's Display Size setting does not reliably update this key. Reading from `applicationContext.resources.displayMetrics.densityDpi` bypasses Settings.Global entirely and returns the actual live system density. The ON_RESUME observer was working correctly throughout — only the read source was wrong

**Outstanding:**
- None

**Notes:**
- `applicationContext` is not affected by DASH's activity-level `attachBaseContext` density isolation — it reflects real system density
- Shizuku is parked indefinitely. The consumer deep-link path is adequate for now. Shizuku may be revisited in version 2 if consumer density control becomes a priority
- 1.1.x is now complete

---

*This document is maintained throughout development. Every version increment — including third number refinements — requires an entry here before the version is considered complete.*
