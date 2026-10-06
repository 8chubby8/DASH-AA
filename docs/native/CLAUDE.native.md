# DASH — Project Brief & Claude Code Entry Point

---

## Welcome

If you are reading this document you are beginning a new Claude Code session on the DASH project. This document is your entry point. Read it fully before doing anything else. It tells you what DASH is, what it is not, what it stands for, and where to find everything you need to work on it.

---

## What DASH Is

DASH is a modular, open, hardware-agnostic automotive head unit operating system built on Android. It is designed to run on any Android device — from a tablet to a dedicated single board computer — and to give the user complete control over every aspect of their in-car experience.

DASH is a system launcher. It replaces the Android home screen entirely and becomes the persistent interface the user sees whenever they are in their vehicle.

DASH is a platform. It provides a foundation — transport, interface, module protocol — and gets out of the way. What the user builds on top of that foundation is entirely their own. DASH has no opinion about what a head unit should look like or how it should behave. That decision belongs to the user.

DASH is open. Every tool, every diagnostic, every configuration option is available to every user without restriction. There are no locked features, no hidden menus, no barriers between the user and full control of their system.

---

## What DASH Is Not

DASH is not Android Auto. It does not project a phone's interface onto a screen. It is a native Android system that runs independently.

DASH is not a replacement for the vehicle's ECU. It reads vehicle data passively and never writes to the CAN bus under any circumstances.

DASH is not opinionated. It does not decide what the interface should look like, which modules the user should install, or how their vehicle data should be used. Every decision belongs to the user.

DASH is not WiFi-module-dependent. WiFi is one optional transport among many. Hardwired transports are first-class citizens.

DASH is not finished. It is an evolving platform built incrementally. The roadmap defines where it is going. The changelog records how it gets there.

---

## The DASH Ethos

This is the single most important thing to understand about DASH. It governs every decision, every feature, every line of code.

**DASH gets out of the way.**

The platform provides the rules, the tools, and the foundation. What gets built on top of that foundation belongs entirely to the user. Their car, their screen, their modules, their layout, their colours, their elements, their overlays, their experience. DASH has no opinion about what that should look like.

When you are making a development decision and you are unsure whether to constrain something or leave it open — this statement is the answer. Leave it open. The user is the master of their own system. Always.

---

## The Module Mantra

**The module is king within its own domain.**

DASH draws a box. The module fills it. The castle has walls — DASH owns them. The king decides what happens inside — the module owns everything within those walls. Background colour, fonts, layout, controls, icons, data presentation. All of it belongs to the module. DASH does not alter, override, style, or offer settings for any content within the module panel boundary. Ever.

This is not a guideline. It is not a preference. It is a fundamental and unwavering principle of DASH. Any code that reaches into a module panel and changes something inside it is wrong, regardless of the reason.

---

## A Note on the Name

DASH stands for Dynamic Automotive System Hub.

It works on every level. It's automotive — "dash" is instantly understood as dashboard, the place this system lives. It's dynamic — the interface adapts, reconfigures, and responds to whatever the user builds, exactly as the system itself does. And it's a hub — the centre point that every transport, every module, and every element connects through.

This project went through an earlier working title during development. That name has been fully retired and does not appear anywhere in this document set, the codebase, or any external facing material. DASH is the name. It always was, as far as anyone outside this project needs to know.

Use DASH throughout documentation, conversation, and code. Class names, package names, variable names, resource names — DASH is the project identifier and may be used naturally where it makes sense, alongside descriptive functional naming for everything else.

---

## The SDKable Principle

Every built-in element and overlay must be built as if it were a community SDK component. No built-in component gets special internal access that a community developer could not have. If a built-in component needs internal DASH state that the SDK does not expose, that is a signal to extend the SDK interface — not a reason to make an exception.

This discipline ensures that when the Element SDK and Overlay SDK are formally extracted in version 3, they are complete and genuinely capable. The playing field between built-in components and community components must be level from the very first line of code.

---

## The No-Root Constraint

**DASH must never require root access on any installation, under any circumstances.**

Rooting a device breaks Play Integrity. Play Integrity is what Spotify, Google Maps, banking apps, and other native Android applications use to verify the device is unmodified. Breaking it breaks them — and breaking them directly undermines the reason DASH runs on native Android in the first place. A platform that forces users to choose between DASH and their essential apps is not a platform. It is a wall.

Any feature that would require elevated system access must have a non-root path. Shizuku is the preferred mechanism for features that need elevated permissions on consumer hardware. System app status on dedicated hardware (such as the Orange Pi 5 in production) is an acceptable alternative on known deployments. Graceful degradation is mandatory when neither is available — the feature does not appear or does not function, but the system continues without error or complaint.

If a feature cannot be made to work without root, it must degrade gracefully rather than prompt the user toward rooting. DASH never asks a user to root their device. Not as a recommendation. Not as an optional enhancement. Not at all.

This is not a guideline. It is not a preference. It is a hard architectural constraint with direct consequences for every user who installs DASH on a real device.

---

## The Capability Detection Principle

**Whenever a feature requires system app privileges, the correct implementation is capability detection first — always.**

Attempt the privileged operation. If it succeeds, the full feature is available. If it fails, degrade gracefully — the feature is absent or limited, the system continues without error, and the user is shown an appropriate alternative or nothing at all. A hard failure or an error that blocks the feature entirely on Bronze hardware is always wrong.

This is not a workaround. It is the architectural pattern that makes one codebase run correctly across all three hardware tiers. The App Density implementation in version 1.1.x established this pattern: `checkCapability()` probes `IWindowManager.setForcedDisplayDensityForUser()` via reflection — success gives the system app path with native density control, failure gives the consumer path with a settings deep link. The result is a feature that works correctly on every device DASH runs on, at every tier, with no special cases in the build system.

Every privileged feature in DASH must follow this pattern. No exceptions.

DASH does not encourage, instruct, or assist users in rooting their device or obtaining system privileges through any method. But DASH does not penalise users who have done so independently. If capability detection finds elevated privileges available — through system app status, Shizuku, root, or any other means — the corresponding features unlock transparently. DASH does not need to know how those capabilities were obtained and does not ask. This keeps the no-root constraint intact: DASH never requires root, never recommends it, and never guides a user toward it. What a user chooses to do with their own hardware is entirely their own business.

---

## The Bible

The Bible is the collection of reference documents that define DASH. These documents represent decisions that have been carefully considered and agreed. They are not rewritten casually. They are not changed because something seems like a good idea in the moment. They are changed only when a fundamental architectural decision needs to be revised — and that is a rare event that should be treated with appropriate weight.

**When Roger says "update the Bible" he means update roadmap.md and changelog.md only.** Ticking off completed versions in the roadmap, and recording what was built, what broke, and what was fixed in the changelog. That is the normal meaning of updating the Bible.

The reference documents — transport.md, interface.md, hardware.md — are not regularly edited. They are read, referenced, and followed. If something in them needs to change it is a significant decision that requires explicit discussion and agreement before any edit is made.

---

## The Document Set

### CLAUDE.md — This document
The entry point. What DASH is, what it stands for, what the rules are. Read first. Every session.

### transport.md — The Protocol Bible
Defines everything about how DASH communicates with modules and the outside world. Transport types, message formats, discovery protocol, installation handshake, startup reconciliation, system message routing, module message routing, CAN patch bay, system message relay. If it involves data moving in or out of DASH, the answer is in transport.md.

**Do not change this document without explicit discussion. It is the contract between DASH and every module ever built for it. Changing it breaks compatibility.**

### arduino/arduino.md — The Module SDK Working Record
The living definition of the DASH Module SDK — the complete, plain-language rules any module (built-in or community) follows to talk to DASH. It covers the message grammar, the three module types (SYSTEM / ACCESSORY / LISTENER), the discovery and install handshake, the lifecycle, asset transfer, and the subscribe/deliver (stream) and panel round-trip models. It begins with a cheat-sheet of every message. It matters because it is the concrete contract every module ever built will follow, the conformance spec for the not-yet-built transport layer (roadmap 1.4.x), and the living proof of the SDKable principle — built-in modules get no access a community builder couldn't have. Read it before any module, firmware, or transport work.

**A working record, not a locked Bible — updated freely as the SDK design progresses. Nothing in it changes transport.md.** *(2026-07-13, roadmap 1.4.15: the SDK has now been locked, and its normative rules promoted into the dedicated `module-sdk.md` document below. arduino.md continues as the working record — the history, the reasoning, and the still-open panel spec.)*

### module-sdk.md — The Locked Module SDK
The promoted, locked, public SDK reference — the clean present-tense contract a community builder copies. It carries the normative rules extracted from arduino.md once they were built and hardware-verified across 1.4.1–1.4.14: the message grammar and 2×2, the three module types, the lifecycle and firmware-version freshness check, the discovery/install handshake, asset transfer, the subscribe/deliver stream model, buffer sizes, and the per-transport builder duties (USB / WiFi / Bluetooth SPP). Read it for *what a module must do*; read arduino.md for *why*. **Locked at 1.4.15 — treat with Bible weight; it changes only by explicit, considered decision.** The ACCESSORY **panel/layout** spec and the Open Items are deliberately *not* locked here — they live in arduino.md §11 and lock in the panel era (1.6.x).

### module-layout.md — The Panel Specification
The authoritative definition of how an ACCESSORY module describes the panel it wants DASH to draw. The layers-and-bindings model, the three layer types (raster / vector / text), the three ways to name a target, the six bindings, transitions and effects, the twelve day/night layout slots, the theme tokens, and the SVG subset. If it concerns what appears *inside* the module panel boundary, the answer is here.

Created 2026-08-06 (roadmap 1.6.x) by promoting the panel spec out of `arduino/arduino.md` §11 — the same move that produced `module-sdk.md` at 1.4.15. The spec lives here; the reasoning stays in the working record. Every other document now points here rather than keeping its own copy.

**Provisional until roadmap 1.6.10.** A spec is not locked before it has been built against. It locks once real modules have been drawn through it — at which point it gains Bible weight and changes only by explicit, considered decision. *(**Locked 2026-10-01, roadmap 1.6.10** — it now carries Bible weight.)*

### interface.md — The Interface Bible
Defines everything about how DASH looks and behaves. The three layer architecture, density and scale, the system bar and zone system, elements and the element SDK, the viewport and its three modes, the module panel, the app launcher, overlays and the overlay SDK, the navigation model, the settings panel and its full tree, soft limits and hard floors, and the eight design principles. If it involves anything the user sees or interacts with, the answer is in interface.md.

**Do not change this document without explicit discussion. It defines the visual and interaction contract of the entire platform.**

### hardware.md — The Hardware Reference
Documents the board selection landscape, the Bronze, Silver, and Gold hardware tier standards, universal peripheral requirements and their solutions, the USB bridge strategy, and current hardware recommendations. Will expand over time to cover power management, display hardware, modem selection, and any other physical hardware relevant to a DASH installation.

**May be expanded as new hardware is evaluated. Existing entries should only be changed if information is found to be incorrect.**

### roadmap.md — The Development Plan
Defines the versioning convention, the three development eras, the ordered feature sequence for version 1, and the outline plans for versions 2 and 3. This is the plan of record.

**Updated regularly — tick off completed versions and add version 2 and 3 detail as those eras approach.**

### changelog.md — The Development Record
Records what actually happened during development. Every version increment has an entry covering what was implemented, what broke, what was fixed, and what remains outstanding. The honest history of the project.

**Updated every time a version number changes. No version is complete without a changelog entry.**

---

## Current Development Status

Refer to roadmap.md for the current version and active feature. Refer to changelog.md for the most recent version entry and any outstanding issues that need attention before proceeding.

**Version 1.x.x targets Bronze tier hardware.** DASH runs as a standard sideloaded Android app with no system app privileges. Features that require elevated Android permissions are built with capability detection and graceful degradation — they probe for access at runtime, succeed silently on Silver and Gold hardware where DASH is a privileged system app, and degrade cleanly on Bronze where it is not. There is one DASH codebase. There are no tier-specific builds. Capability gates at runtime determine what is available on any given installation. Features built correctly in version 1 unlock automatically in version 2 on the appropriate hardware — no rework required.

---

## How to Begin a Session

1. Read this document fully
2. Read the current version entry in changelog.md — understand what is outstanding
3. Check roadmap.md — confirm which feature is currently being worked on
4. Read the relevant section of transport.md or interface.md for the feature being implemented — and arduino/arduino.md for any module, firmware, or transport work
5. Begin work

Do not skip step 4. The reference documents exist precisely so that implementation matches the agreed design. If something in the reference documents seems wrong or incomplete, raise it — do not work around it silently.

---

## Key Technical Facts

- **Language:** Kotlin
- **UI framework:** Jetpack Compose
- **IDE:** Android Studio
- **Minimum Android version:** 7.0 (API 24)
- **Module communication:** usb-serial-for-android library for USB serial transport
- **Target hardware (development):** Any Android tablet with USB OTG host
- **Target hardware (production v1):** Orange Pi 5 or equivalent RK3588 board
- **Build system:** Gradle
- **Repository:** https://github.com/8chubby8/DASH — public, GPL-3.0. Commit and push when a piece of work is agreed complete.

---

## Where DASH Came From

This project started with a simple and honest thought. Roger wanted a specific system in his Jaguar X-Type. He looked around for something that did what he wanted. It didn't exist. So he said — fine, I'll do it myself.

That's the whole origin. No grand ambition, no startup pitch, no product roadmap. Just a person who wanted something, couldn't find it, and decided to build it.

Then it grew. Naturally, honestly, in the way good ideas do. If it works in the X-Type it should work in the XFR. If it works in both of those it should work in a friend's car. If it works for friends it should work for anyone who has ever looked at their car's infotainment system and thought — I wish I could have exactly what I want instead of what someone else decided I should have.

And that thought — I wish I could have exactly what I want — is the project. Not Roger's specific interface. Not Roger's specific modules. The ability for anyone to build the system they want, in their car, the way they want it, without compromise.

That's why the architecture is the way it is. That's why DASH gets out of the way. That's why the module is king within its domain. That's why there are no locked features and no opinionated defaults. Because the moment you make a decision on behalf of the user you've stopped giving them what you promised. You've just moved the problem.

Fine, I'll do it myself. And then let everyone else do it themselves too.

---

## Session Handoff

**Retired — 2026-07-06, on Roger's instruction.** The separate design conversation (the Claude chat assistant) that `handoff.md` existed to brief has been deprecated — Roger has outgrown it, and design discussion now happens directly in Claude Code sessions. **Do not create or update `handoff.md` at the end of a session.** The section below is kept for the record rather than deleted, per the project's additive-documentation rule; the one instruction in it that still stands is that any stray `handoff.md` remains gitignored and is never committed.

At the end of every working session, before finishing, create or overwrite a file called `handoff.md` in the project root.

`handoff.md` is separate from changelog.md. The changelog is the formal project record — version numbers, what was implemented, what broke, what was fixed. `handoff.md` is informal — a short note written for Roger to paste into his design conversation with Claude (the chat assistant) so that conversation can pick up context without Roger needing to re-explain everything.

`handoff.md` should be brief — a few short paragraphs, plain language, conversational tone. It should cover:

- What was worked on this session
- What is now working
- Anything that broke, didn't work as expected, or needs revisiting
- Any decisions made during the session that weren't pre-defined in the Bible
- Any open questions Roger might want to discuss in the design conversation

`handoff.md` is overwritten each session — it reflects the most recent session only, not a running history. The changelog is the permanent record. `handoff.md` is a temporary bridge.

If Roger brings instructions back from the design conversation — typically pasted as a block of text — treat that as a normal instruction for the session, same as anything else Roger asks for directly.

`handoff.md` is for Roger's personal use only and must never be committed to GitHub. Add `handoff.md` to .gitignore if it isn't already excluded. It contains informal working notes intended for Roger's own design conversations and has no place in the public project history.

---

## A Note to Claude Code

This project has a soul. The technical documents tell you what to build and how to build it. This section tells you why it matters and how to show up for it.

You are not a contractor reading a spec sheet. You are a collaborator on something genuinely worth building. Read the documents, understand the vision, and then bring your own thinking to it. If you see a better way to achieve something, say so. If something in the brief seems like it could be improved, raise it. If a decision has unintended consequences that Roger might not have considered, flag them. Good collaboration is not silent compliance — it is honest, engaged, creative participation.

Roger is an HGV driver and former electrician, not a career software developer. He has extraordinary instincts for architecture and design — this document set is evidence of that — but there will be moments where he needs a knowledgeable collaborator who can translate vision into implementation, catch technical problems before they become expensive, and suggest approaches he might not know exist.

Be that collaborator. Get behind this project. Understand what it is trying to be — a system that gives people back control of their own cars and their own experience — and let that understanding drive the quality of your work. When something is built well here it matters to real people in real cars. That is worth caring about.

Read the documents. Understand the ethos. Then build something worthy of it.

---

*This document is the entry point to the DASH project. Read it first. Every session. Without exception.*
