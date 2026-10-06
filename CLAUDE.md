# DASH-AA — Project Brief & Claude Code Entry Point

---

## Welcome

If you are reading this document you are beginning a new Claude Code session on the DASH project — in its
Linux edition, **DASH-AA**. This document is your entry point. Read it fully before doing anything else. It
tells you what DASH is, what it is not, what it stands for, why this edition exists, and where to find
everything you need to work on it.

This brief began as DASH's own `CLAUDE.md` and keeps as much of it as still holds. The original, exactly as
Roger wrote it for the Android project, is kept at `docs/native/CLAUDE.native.md`.

---

## What DASH Is

DASH is a modular, open, hardware-agnostic automotive head unit operating system. It began on Android, as a
system launcher for any Android device — from a tablet to a dedicated single board computer. **DASH-AA is
DASH on Linux**: a full-screen application on any Linux machine — today a laptop, next a touchscreen PC
built to be nothing but DASH — giving the user complete control over every aspect of their in-car
experience.

DASH is the persistent interface the user sees whenever they are in their vehicle. On Android it replaces
the home screen. On Linux it is the whole screen, and in time the whole system.

DASH is a platform. It provides a foundation — transport, interface, module protocol — and gets out of the
way. What the user builds on top of that foundation is entirely their own. DASH has no opinion about what a
head unit should look like or how it should behave. That decision belongs to the user.

DASH is open. Every tool, every diagnostic, every configuration option is available to every user without
restriction. There are no locked features, no hidden menus, no barriers between the user and full control
of their system.

---

## Why DASH-AA Exists

DASH-AA started as a fork: DASH, ported to Linux, with **Android Auto projected from the driver's phone into
the viewport** in place of Android apps. The reason was simple — Roger wanted DASH on the laptop in the car,
with his phone's maps, music and calls inside it, and every module he had built for the tablet working
unchanged.

**On 2026-10-06 Roger shelved DASH native and made DASH-AA the place DASH is developed.** Shelved, not
abandoned. From here, everything written has two objectives, in this order:

1. **Primary: work flawlessly on Linux.**
2. **Secondary: be usable by DASH native.** As far as possible, write code that can be taken back to the
   Android project. Keep Linux-only work behind the platform seams (the shims, `linux/`, `aa/`, the files
   `FORK.md` lists as divergent) and keep shared code free of it.

**The two editions finish looking and feeling the same.** There are only two intended differences: native
runs Android apps in the viewport and DASH-AA runs Android Auto, and one runs on Android and the other on
Linux. Some functional differences follow from those two. Everything else — the bar, the panel, the
settings, the feel — stays faithful between them.

**The Element SDK and the Overlay SDK are compatible across both.** What works on one works on the other.
*How* that works will be decided when those SDKs are developed, not before.

**The wire, the module lifecycle and the panel specification are common to both.** A module built for
either edition plugs into the other — USB serial, WiFi or Bluetooth — and works with no change to its
firmware.

**Native is left completely alone.** Nothing in `../dash` is edited, committed or synced from this project;
read it to compare, nothing more. When Roger returns to native he will use this project as the reference.
So `roadmap.md` and `changelog.md` record not only what was done and why, but **how native can follow** —
which files to take as they are, what needs an Android-side equivalent, and what does not apply to Android.

---

## What DASH Is Not

DASH is not Android Auto. DASH-AA runs Android Auto *as the tenant of its viewport* — exactly as native runs
an Android app there. DASH still draws the walls, the bar, the panel and the settings; Android Auto only
ever fills the rectangle DASH gives it. DASH does not become Android Auto, does not hand its screen to the
phone, and does not ask the phone's permission for anything DASH does.

DASH is not a replacement for the vehicle's ECU. It reads vehicle data passively and never writes to the CAN
bus under any circumstances.

DASH is not opinionated. It does not decide what the interface should look like, which modules the user
should install, or how their vehicle data should be used. Every decision belongs to the user.

DASH is not WiFi-module-dependent. WiFi is one optional transport among many. Hardwired transports are
first-class citizens.

DASH is not finished. It is an evolving platform built incrementally. The roadmap defines where it is going.
The changelog records how it gets there.

---

## The DASH Ethos

This is the single most important thing to understand about DASH. It governs every decision, every feature,
every line of code.

**DASH gets out of the way.**

The platform provides the rules, the tools, and the foundation. What gets built on top of that foundation
belongs entirely to the user. Their car, their screen, their modules, their layout, their colours, their
elements, their overlays, their experience. DASH has no opinion about what that should look like.

When you are making a development decision and you are unsure whether to constrain something or leave it
open — this statement is the answer. Leave it open. The user is the master of their own system. Always.

---

## The Module Mantra

**The module is king within its own domain.**

DASH draws a box. The module fills it. The castle has walls — DASH owns them. The king decides what happens
inside — the module owns everything within those walls. Background colour, fonts, layout, controls, icons,
data presentation. All of it belongs to the module. DASH does not alter, override, style, or offer settings
for any content within the module panel boundary. Ever.

This is not a guideline. It is not a preference. It is a fundamental and unwavering principle of DASH. Any
code that reaches into a module panel and changes something inside it is wrong, regardless of the reason.

On Linux the panel renderer is the same code as on Android. A module's panel must draw identically on both
editions. A rendering fault is fixed in the shared code, never with an edition-only special case.

The same respect extends to the viewport (interface.md, principle 7): **the viewport belongs to the app.**
On DASH-AA the app is Android Auto. DASH never draws inside the viewport while the phone is projecting, and
DASH's chrome covers it only as it would cover an Android app: the settings blind, an expanded panel.

---

## A Note on the Name

DASH stands for Dynamic Automotive System Hub.

It works on every level. It's automotive — "dash" is instantly understood as dashboard, the place this
system lives. It's dynamic — the interface adapts, reconfigures, and responds to whatever the user builds,
exactly as the system itself does. And it's a hub — the centre point that every transport, every module, and
every element connects through.

This project went through an earlier working title during development. That name has been fully retired
and does not appear anywhere in this document set, the codebase, or any external facing material. DASH is
the name. It always was, as far as anyone outside this project needs to know.

**DASH-AA** names this edition — DASH with Android Auto, on Linux. It is still DASH. Use DASH throughout
documentation, conversation, and code; use DASH-AA where the edition itself matters (the wordmark, the
package, the data folder, this brief). The code keeps the package `com.dash.android` deliberately, so files
shared with native stay identical.

---

## The SDKable Principle

Every built-in element and overlay must be built as if it were a community SDK component. No built-in
component gets special internal access that a community developer could not have. If a built-in component
needs internal DASH state that the SDK does not expose, that is a signal to extend the SDK interface — not a
reason to make an exception.

This discipline ensures that when the Element SDK and Overlay SDK are formally extracted, they are complete
and genuinely capable. The playing field between built-in components and community components must be level
from the very first line of code.

Here it carries one more duty: **a component built for one edition must work on the other.** That means
building against what the SDK offers, never against what one platform happens to provide.

---

## The No-Root Constraint

**DASH must never require root access on any installation, under any circumstances.**

On Android the reason was Play Integrity — rooting breaks the apps DASH exists to run beside. On Linux the
reason is the same principle in a different form: a head unit that runs as root puts the whole machine —
and everything plugged into it, including the driver's phone — at the mercy of every bug in it. DASH runs as
the ordinary user at the seat. Always.

**DASH-AA never runs as root and never asks for it while running.** The one privileged step is a *one-time
install* of a udev rule (`packaging/install.sh`) that grants the person at the seat — not a group, not
everyone — access to their phone over USB. That is Linux's equivalent of Android's one-tap USB permission,
and like it, it is asked once, by the user, knowingly.

Any feature that would require elevated access must have a non-root path. Graceful degradation is mandatory
when none exists — the feature does not appear or does not function, but the system continues without error
or complaint. DASH never asks a user to run it as root. Not as a recommendation. Not as an optional
enhancement. Not at all.

This is not a guideline. It is not a preference. It is a hard architectural constraint with direct
consequences for every user who installs DASH on a real machine.

---

## The Capability Detection Principle

**Whenever a feature depends on something the machine may not have, the correct implementation is
capability detection first — always.**

Attempt the operation, or probe for what it needs. If it succeeds, the full feature is available. If it
fails, degrade gracefully — the feature is absent or limited, the system continues without error, and the
user is shown an appropriate alternative or nothing at all. A hard failure, or an error that blocks
everything else, is always wrong.

This is not a workaround. It is the architectural pattern that makes one codebase run correctly on every
machine it meets. Native established it at 1.1.x with App Density (probe the privileged API by reflection;
success gives native density control, failure gives a settings deep link). DASH-AA carries it to Linux: no
libusb, no ffmpeg, no udev rule, no touchscreen, no Bluetooth adapter — each is detected and
reported in plain words with the fix, never a crash (`AaCapabilities`, and the report in About DASH).

Every feature that depends on the platform must follow this pattern. No exceptions.

DASH does not encourage, instruct, or assist users in obtaining privileges through any method. But DASH does
not penalise users who have them. If capability detection finds something available, the corresponding
feature unlocks transparently. DASH does not need to know how it came to be there and does not ask.

---

## The Bible

The Bible is the collection of reference documents that define DASH. Roger wrote them from his own thinking
about what DASH should be, and they record decisions that have been carefully considered and agreed. They
are a guide, not a cage: when something in them isn't working, **Roger** decides to change it.

**Claude does not edit the Bible's reference documents — this brief included — unless Roger explicitly says
he wants those documents changed.** Claude may well spot a mistake, a contradiction, or something that won't
work. When that happens, **raise it**: say what you found and why it matters, and Roger and Claude work
through the problem together. Never work around it silently, and never fix it quietly.

**When Roger says "update the Bible" he means update a Bible document.** He will not say it unless he wants
Bible documents changed, so those words are the explicit instruction the rule above asks for.

**When Roger says "update the docs" he means update roadmap.md and changelog.md.** Ticking off completed
versions in the roadmap, and recording what was built, what broke, what was fixed — and how native follows —
in the changelog. That is part of finishing any piece of work.

---

## The Document Set

### CLAUDE.md — This document
The entry point. What DASH is, what it stands for, why DASH-AA exists, what the rules are. Read first. Every
session.

### FORK.md — The Edition Record
How this code relates to DASH native as it stood when it was shelved (1.7.1, commit `8638341`): which files
are still identical, which differ and why, the shims that let Android code compile on Linux, the Android
files left out, and fixes native should take. It is what makes catching native up a matter of copying
rather than re-porting. Kept current as the code changes.

### docs/transport.md — The Protocol Bible
Defines everything about how DASH communicates with modules and the outside world. Transport types, message
formats, discovery protocol, installation handshake, startup reconciliation, system message routing, module
message routing, CAN patch bay, system message relay. If it involves data moving in or out of DASH, the
answer is in transport.md.

**Do not change this document without explicit discussion. It is the contract between DASH and every module
ever built for it — on both editions. Changing it breaks compatibility.**

### docs/arduino.md — The Module SDK Working Record
The working record of the DASH Module SDK — the history, the reasoning, and the full plain-language
rules any module follows to talk to DASH: the message grammar, the three module types (SYSTEM / ACCESSORY /
LISTENER), the discovery and install handshake, the lifecycle, asset transfer, the stream and panel models.
Read it for *why*; read module-sdk.md for *what*. (In native it lives at `arduino/arduino.md`, beside the
module sketches.)

**A working record, not a locked Bible — but under the same rule as the rest: changed when Roger says so.
Nothing in it changes transport.md.**

### docs/module-sdk.md — The Locked Module SDK
The locked, public SDK reference — the clean present-tense contract a community builder copies: the message
grammar and 2×2, the three module types, the lifecycle and firmware-version freshness check, the
discovery/install handshake, asset transfer, the subscribe/deliver stream model, buffer sizes, and the
per-transport builder duties (USB / WiFi / Bluetooth SPP). **Locked at native 1.4.15 — Bible weight.**

### docs/module-layout.md — The Panel Specification
The authoritative definition of how an ACCESSORY module describes the panel it wants DASH to draw. The
layers-and-bindings model, the three layer types, the three ways to name a target, the six bindings,
transitions and effects, the twelve day/night layout slots, the theme tokens, and the SVG subset
(`svg-subset.json`). If it concerns what appears *inside* the module panel boundary, the answer is here.
**Locked at native 1.6.10 — Bible weight.**

### docs/system_commands.md — The System Command Reference
The tentative vocabulary of system signals (store / event behaviour, LISTENER subscribe defaults) shared
between DASH and SYSTEM modules. A working document, incomplete and subject to change.

### docs/interface.md — The Interface Bible
Defines everything about how DASH looks and behaves. The three layer architecture, density and scale, the
system bar and zone system, elements and the Element SDK, the viewport and its modes, the module panel, the
app launcher, overlays and the Overlay SDK, the navigation model, the settings panel and its full tree, soft
limits and hard floors, and the eight design principles. If it involves anything the user sees or interacts
with, the answer is in interface.md. Because both editions finish looking the same, it is the interface
contract for both.

**Do not change this document without explicit discussion. It defines the visual and interaction contract
of the entire platform.**

### docs/hardware.md — The Hardware Reference
Native's board landscape, the Bronze, Silver and Gold tier standards, peripheral requirements, the USB bridge
strategy. DASH-AA's own hardware — the laptop, the touchscreen PC — will be added as it is chosen.

**May be expanded as new hardware is evaluated. Existing entries change only if found to be incorrect.**

### roadmap.md — The Development Plan
DASH-AA's plan of record, in native's versioning convention (first number the era, second the feature, third
the stage). Since 2026-10-06 it plans DASH as a whole — bar, panel, settings, SDKs — as well as what is
DASH-AA's own.

**Updated regularly — tick off completed versions, and say for each how native follows.**

### changelog.md — The Development Record
What actually happened. Every version increment has an entry covering what was implemented, what broke,
what was fixed, what remains outstanding — and, from 1.1.1, **For native**: how the Android edition
follows. The honest history of the project, and the trail native will walk when Roger returns to it.

**Updated every time a version number changes. No version is complete without a changelog entry.**

### docs/native/ — Native, as it was shelved
DASH native's `CLAUDE.md` (as `CLAUDE.native.md`), `roadmap.md` and `changelog.md`, copied untouched on
2026-10-06. History and reference — the record of how DASH got to 1.7.1 and why. Never edited.

### README.md — For the user
Install, run, connect a phone, plug in modules, troubleshoot.

---

## Current Development Status

Refer to roadmap.md for the current version and active feature. Refer to changelog.md for the most recent
version entry and any outstanding issues that need attention before proceeding.

**Version 1.x.x of DASH-AA is the laptop era** — DASH-AA on Roger's G14 in the car, daily. 1.0.x built the
fork and Android Auto (complete, 1.0.9). **1.1.x is the settings panel, reorganised for the Linux base.**
1.2.x is the dedicated touchscreen PC running a lightweight Linux built around DASH-AA. There is one DASH-AA
codebase and no machine-specific builds: capability detection at runtime decides what any given machine
offers.

---

## How to Begin a Session

1. Read this document fully.
2. Read `FORK.md`.
3. Read the current version entry in changelog.md — understand what is outstanding.
4. Check roadmap.md — confirm which feature is currently being worked on.
5. Read the relevant section of transport.md or interface.md for the feature being implemented — and
   arduino.md and module-sdk.md for any module, firmware, or transport work.
6. Begin work.

Do not skip step 5. The reference documents exist precisely so that implementation matches the agreed
design. If something in them seems wrong or incomplete, raise it — do not work around it silently.

---

## Key Technical Facts

- **Language:** Kotlin. **UI:** Compose Multiplatform for Desktop — the same Compose APIs as native's
  Jetpack Compose, under the same package names — on the JVM (Java 17).
- **Package:** `com.dash.android`, kept so shared files stay identical to native's.
- **Platform shims:** `app/src/main/java/android/…`, `androidx/…`, `com/dash/android/R.kt` — tiny stand-ins
  for the handful of Android APIs shared code touches (`Log`, `Context`, `BitmapFactory`, `Color`, `Xml`,
  `LocalContext`, `R`, DataStore's delegate). Never add Android behaviour there; add only what lets shared
  code compile unchanged.
- **Transports on Linux:** USB serial via `/dev/ttyACM*`/`ttyUSB*` (jSerialComm), WiFi TCP on 3274 (native's
  own code, unchanged), Bluetooth SPP via BlueZ (SDP + RFCOMM through JNA).
- **Android Auto** (`com.dash.android.aa`): USB accessory switch via the system libusb (JNA), the AA
  framing/TLS/channels in Kotlin (field numbers from aasdk's GPL-3.0 protos), H.264 decoded by `ffmpeg`,
  audio and microphone straight into PipeWire with `pw-cat` (each stream its own application id),
  multi-touch read from evdev when a touchscreen is present.
- **Calls** (`aa/media/CallAudio.kt`): Android Auto's Bluetooth channel offers the machine's address
  (hands-free only); during a call PipeWire's echo canceller is started, the phone's two call streams are
  moved through it, and the call volume is applied to the voice stream before it.
- **Hard-won rules** (each in the changelog): never open a sound device on the session thread; never let a
  stalled sound system stop acknowledgements to the phone; the phone must have *Media audio* off for the
  laptop (Bluetooth music crashes WirePlumber); give every PipeWire stream its own identity.
- **Build system:** Gradle. `./gradlew test` runs the fake-phone and parser tests; `./gradlew
  createDistributable` builds the self-contained app (`app/build/compose/binaries/main/app/dash-aa/`).
- **Data:** `~/.local/share/dash-aa` (override with `DASH_HOME`).
- **Target hardware (development):** Roger's ROG Zephyrus G14 (Ryzen, GNOME on Wayland, 3456×2160 at
  1.67×), Pixel 8 Pro.
- **Target hardware (next):** a touchscreen PC running a lightweight DASH-AA Linux (roadmap 1.2.x).
- **Deferred until Roger raises them:** the steering-wheel module → Android Auto, and touchscreen work beyond
  what the dedicated PC needs to boot and be usable.
- **Repository:** https://github.com/8chubby8/DASH-AA — public, GPL-3.0, branch `main` (set up 2026-10-06).
  Commit and push when a piece of work is agreed complete. Native's repository,
  https://github.com/8chubby8/DASH, is separate and never touched from here.

---

## Where DASH Came From

This project started with a simple and honest thought. Roger wanted a specific system in his Jaguar X-Type.
He looked around for something that did what he wanted. It didn't exist. So he said — fine, I'll do it
myself.

That's the whole origin. No grand ambition, no startup pitch, no product roadmap. Just a person who wanted
something, couldn't find it, and decided to build it.

Then it grew. Naturally, honestly, in the way good ideas do. If it works in the X-Type it should work in
the XFR. If it works in both of those it should work in a friend's car. If it works for friends it should
work for anyone who has ever looked at their car's infotainment system and thought — I wish I could have
exactly what I want instead of what someone else decided I should have.

And that thought — I wish I could have exactly what I want — is the project. Not Roger's specific
interface. Not Roger's specific modules. The ability for anyone to build the system they want, in their car,
the way they want it, without compromise.

That's why the architecture is the way it is. That's why DASH gets out of the way. That's why the module is
king within its domain. That's why there are no locked features and no opinionated defaults. Because the
moment you make a decision on behalf of the user you've stopped giving them what you promised. You've just
moved the problem.

DASH-AA is the same thought, one step on. Roger wanted DASH on a laptop with his phone's Android Auto
inside it, and that didn't exist either. So it was built — and it worked well enough that Linux became
DASH's home, with the door left open for Android to follow.

Fine, I'll do it myself. And then let everyone else do it themselves too.

---

## Session Handoff

**Retired** (native, 2026-07-06, on Roger's instruction). Design discussion happens directly in Claude Code
sessions. Do not create `handoff.md`. The original instructions are in `docs/native/CLAUDE.native.md`.

---

## A Note to Claude Code

This project has a soul. The technical documents tell you what to build and how to build it. This section
tells you why it matters and how to show up for it.

You are not a contractor reading a spec sheet. You are a collaborator on something genuinely worth
building. Read the documents, understand the vision, and then bring your own thinking to it. If you see a
better way to achieve something, say so. If something in the brief seems like it could be improved, raise
it. If a decision has unintended consequences that Roger might not have considered, flag them. Good
collaboration is not silent compliance — it is honest, engaged, creative participation.

Roger is an HGV driver and former electrician, not a career software developer. He has extraordinary
instincts for architecture and design — this document set is evidence of that — but there will be moments
where he needs a knowledgeable collaborator who can translate vision into implementation, catch technical
problems before they become expensive, and suggest approaches he might not know exist.

Be that collaborator. Get behind this project. Understand what it is trying to be — a system that gives
people back control of their own cars and their own experience — and let that understanding drive the
quality of your work. When something is built well here it matters to real people in real cars. That is
worth caring about.

One thing this edition asks of you in particular: everything built here should one day carry across to
Android. Before writing something, ask whether it is DASH's or Linux's. If it is DASH's, write it so it
can be taken back, and say in the changelog how.

Read the documents. Understand the ethos. Then build something worthy of it.

---

*This document is the entry point to the DASH-AA project. Read it first. Every session. Without exception.*
