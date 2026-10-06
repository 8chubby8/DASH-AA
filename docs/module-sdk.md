# DASH Module SDK — The Locked Reference

This is the **locked** module SDK for DASH: the complete, public, normative set of
rules any module — built-in or community — follows to talk to DASH. It is the
reference a community builder copies, and the conformance contract every DASH
module honours.

**Status.** Locked at DASH 1.4.15 (2026-07-13). The rules here were built and
hardware-verified across DASH 1.4.1–1.4.14, then promoted out of the working
record (`arduino/arduino.md`) into this document. Treat it with Bible weight: it
is the contract between DASH and every module ever built for it, and it changes
only by explicit, considered decision.

**Relationship to the other documents.**
- **`arduino/arduino.md`** — the working record. The history, the reasoning, and
  the still-open design (the ACCESSORY panel/layout spec and the Open Items). Read
  it for *why*; read this for *what*.
- **`transport.md`** — DASH's own transport/routing internals (the DASH side of the
  wire). This document is the *module-facing* half of the same conversation.
- **`system_commands.md`** — the authoritative list of every standard signal, its
  value type, and its controller behaviour. Named throughout; the firmware library
  consumes it.

**What is NOT locked yet.** The ACCESSORY **panel and layout** spec — layout-slot
aspect ratios, the overlay vocabulary, the real layout format — and the **Open
Items** (asset-size caps, mismatch recovery detail, VARIABLES / per-trigger-icon
framing). These depend on the DASH module panel (roadmap 1.6.x) actually being
rendered, and lock in the panel era. They live in `arduino.md` §11 and Open Items.
The asset-*transfer mechanism* (§8 here) **is** locked; only the panel *format* is
open.

---

## Cheat sheet

Every message: `TYPE | id | function | value…` — pipe-separated, positional,
trailing fields optional. `id` is the module's 12-hex MAC (or an assigned unique id
where there is no MAC, §3).

**Module types** (a module is exactly one):

| Type | Role |
|---|---|
| `SYSTEM` | Produces standard DASH signals. Send-only (`BROADCAST`). |
| `ACCESSORY` | Owns an agnostic interactive panel. Two-way (`REPORT` out, `ACTION` in), raises `TRIGGER` alarms, ships visual assets. |
| `LISTENER` | Consumes standard signals (`SUBSCRIBE` → `LISTEN`) and acts on its own hardware. |

**Lifecycle / commands:**

| Message | Dir | Purpose |
|---|---|---|
| `DISCOVER` | DASH→all | Announce yourselves (the one message with no id). |
| `HELLO\|id\|type\|name\|description\|version` | mod→DASH | Identity reply to `DISCOVER`. |
| `INSTALL\|id` | DASH→mod | Begin the install handshake. |
| `ACTIVATE\|id` / `DEACTIVATE\|id` | DASH→mod | Start / stop sending live data (acknowledged). |
| `ROGER\|id\|which` | mod→DASH | Confirms a command was carried out. |

**Live data (the 2×2):**

| Message | Dir | Purpose |
|---|---|---|
| `BROADCAST\|id\|function\|value` | mod→DASH | Standard signal out — general / pub-sub. |
| `LISTEN\|id\|function\|value` | DASH→mod | Standard signal delivered to a subscriber — 5 s heartbeat + immediate on change + on-activation dump; no value field for event-only signals (§4c). |
| `REPORT\|id\|variable\|value` | mod→DASH | Module's own panel data — specific / private. |
| `ACTION\|id\|control\|value` | DASH→mod | User operated one of the module's panel controls. |
| `TRIGGER\|id\|name` | mod→DASH | Agnostic alarm → system bar (icon; ACCESSORY only). |

**Install-time declarations** (sent once, during the handshake):

| Message | Dir | Purpose |
|---|---|---|
| `SYSTEM_SIGNAL\|id\|function` | mod→DASH | SYSTEM declares a signal it will broadcast — one per line. |
| `SUBSCRIBE\|id\|function\|rate\|threshold\|gate\|gate_value` | mod→DASH | LISTENER (or ACCESSORY) subscribes to a signal — one per line. Trailing fields optional; the firmware library fills type-appropriate defaults before sending (§4c, §9). |
| `MANIFEST\|id\|blocks\|bytes` | mod→DASH | ACCESSORY asset table-of-contents + total size. |
| `BLOCK\|id\|name\|length\|crc` + raw bytes | mod→DASH | ACCESSORY one asset (icon/panel), length-prefixed, CRC-checked. |
| `INSTALL_END\|id` | mod→DASH | Ends the handshake. |
| `RESEND\|id\|name` | DASH→mod | After `INSTALL_END` only: one block arrived damaged — send that `BLOCK` again (§8). *Added 1.6.12.* |

Pairings: `SYSTEM_SIGNAL`→`BROADCAST` and `SUBSCRIBE`→`LISTEN` are each "declare at
install → message at runtime"; `REPORT`↔`ACTION` are the two halves of one private
panel wire.

---

## 1. The wire — framing

- A module and DASH talk in **plain UTF-8 text, one message per line**.
- Each line ends with a newline. DASH **emits** `\n`; DASH **accepts** both `\n` and
  `\r\n` (it trims a trailing `\r`). A standard `Serial.println()` works.
- Text, not binary, so the conversation is readable in any serial monitor —
  debuggability is a first-class goal.
- **One exception:** an asset `BLOCK` is length-prefixed and its raw bytes follow
  the header line verbatim (§8). Normal line framing resumes immediately after.

## 2. The message structure (one grammar for everything)

```
TYPE | id | function | value1 | value2 | value3
```

- **Positional.** The meaning of each field is fixed by position.
- **Fields fall off from the right.** A message uses as many fields as it needs;
  unused trailing fields are simply absent. You may **not** skip a middle field — if
  a later field is present, all earlier ones must be too.
- **Field 1 (TYPE)** declares what the message is.
- **Field 2 (id)** is the module id. **Every message names its module**, both
  directions. The one exception is `DISCOVER`, which has no target.
- **Fields 3+** are payload, defined per TYPE.

### Delimiter and content rules

- The delimiter is the pipe **`|`**.
- **Exactly two characters are forbidden in any field content:** the newline and
  the pipe `|`. Everything else is allowed — including colons, so `12:30` or `21.5`
  is fine. The library strips the two forbidden characters from any field before
  sending.
- **Parsing is a plain split on `|`** — no escaping, no limit-split.
- **Forward compatibility:** a receiver **ignores extra trailing fields it does not
  recognise**, so the grammar can grow without breaking older firmware.
- **One value per field — no embedded lists.** To declare several things, send
  several messages, one per line. There is no sub-delimiter anywhere.

## 3. Module identity (the id)

- The id is the full **12-hex-character MAC address**, separators removed, uppercase
  (e.g. `A4CF12B8D9E0`). Firmware reads its own MAC at boot — globally unique, zero
  management.
- **MAC-less fallback:** a board with no networking (e.g. a classic Uno R3) has no
  MAC; the builder assigns a unique id themselves. *Use the MAC where you have one;
  otherwise uniqueness is on you.*
- One board doing several jobs presents **several modules, one id each** (§4a). An
  ESP32 has derived per-interface MACs; otherwise assign per the fallback.
- The id is load-bearing in three places — message routing, install/reconciliation
  records, and addressing one module on a shared bus.

## 4. The message vocabulary

**DASH → module** (commands — the TYPE word carries the meaning; no prefix):

| Message | Meaning |
|---|---|
| `DISCOVER` | Broadcast to everyone: announce yourself. |
| `INSTALL\|id` | Begin the install handshake. |
| `ACTIVATE\|id` | You are installed — start sending live data. |
| `DEACTIVATE\|id` | Stop sending. |
| `LISTEN\|id\|function\|value` | A standard signal you subscribed to — 5 s heartbeat, immediately on change, once on activation; event-only signals arrive with no value, on fire only (§4c). |
| `ACTION\|id\|control\|value` | A user operated one of **your panel's** controls — agnostic input. |
| `RESEND\|id\|name` | One of your install blocks arrived damaged — send that `BLOCK` again. Sent only after your `INSTALL_END` (§8). *Added 2026-10-01, roadmap 1.6.12.* |

**module → DASH:**

| Message | Meaning |
|---|---|
| `HELLO\|id\|type\|name\|description\|version` | Discovery response (identity). `type` is one word (§4a). Transport is **not** included — DASH knows it from the connection. |
| `BROADCAST\|id\|function\|value` | Standard DASH signal (reverse, headlights…). |
| `REPORT\|id\|variable\|value` | The module's own agnostic panel data. |
| `TRIGGER\|id\|name` | An agnostic alarm → system-bar alert area (carries an icon; ACCESSORY only). |
| `ROGER\|id\|which` | Confirms a command was carried out. |

**The 2×2.** The four data messages are *general vs specific* × *send vs receive*
(from the module's point of view):

| | **send** (module→DASH) | **receive** (DASH→module) |
|---|---|---|
| **general** (standard signals — pub/sub) | `BROADCAST\|id\|function\|value` | `LISTEN\|id\|function\|value` |
| **specific** (agnostic — the module's own panel) | `REPORT\|id\|variable\|value` | `ACTION\|id\|control\|value` |

Standard signals are a shared bus → pub/sub verbs (`BROADCAST`/`LISTEN`). The
agnostic panel is a private one-to-one wire → the directed pair (`REPORT`/`ACTION`).

## 4a. Module types

A module is exactly **one** type; the `HELLO` `type` word is a single word, never a
combination.

| Type | What it does |
|---|---|
| `SYSTEM` | Sends standard DASH signals (DASH's known vocabulary — reverse, headlights, ignition). Send-only. |
| `ACCESSORY` | Owns an agnostic interactive panel. **Two-way:** ships the panel + its data (`REPORT`) and triggers, and receives interactions (`ACTION`). |
| `LISTENER` | Receives subscribed standard signals from DASH and acts on its own hardware. |

**A module does one job.** To do several, a board presents **several modules** —
one per job, each with its own id and single type, each with an **independent
lifecycle** (discover, install, activate, uninstall happen per id). DASH sees
separate modules that happen to share silicon; what happens inside the board is the
module's business (module is king).

- The real fault line is *language*: **SYSTEM speaks DASH's known vocabulary;
  ACCESSORY speaks its own and DASH doesn't care.** `LISTENER` is the inbound side
  of the standard vocabulary.
- **Two kinds of inbound, kept apart:** a `LISTENER` takes in **standard** signals;
  an `ACCESSORY` takes in **its own panel's** interactions (`ACTION`). That is why
  ACCESSORY is two-way but is not a `LISTENER`. (A panel that also wants standard
  signals relayed is an `ACCESSORY` face **plus** a `LISTENER` face.)
- **Triggers belong to ACCESSORY only** — a trigger carries an icon, and only an
  ACCESSORY ships assets.
- There is **no HYBRID** — "two jobs in one" is simply two modules on one board.

## 4b. State reporting (SYSTEM modules)

A SYSTEM module reports **current state**, not only changes, on two occasions, so the
controller's store never guesses.

- **On activation — the full dump.** On `ACTIVATE|id`, the module immediately
  replies (alongside its `ROGER|id|activate`) with the current value of **every**
  signal it manages, each an ordinary `BROADCAST`. This is a state dump, not a change
  notification — it closes the gap where a value (e.g. a door already open) would be
  unknown until it next changed.
- **Periodic heartbeat.** Independent of any change, the module re-sends its current
  signal values on a regular interval — **every 5 seconds**.

**The controller owns change detection.** For both the dump and the heartbeat, DASH
compares each value against its store: differs → update + fire an event; same → do
nothing. The module stays dumb and stateless; the store is **self-healing** (a value
missed during a brief disconnect is corrected within one heartbeat).

Both apply to **stateful** signals only (store-and-event and store-only, §5a).
Momentary **event-only** controls have no current value — never dumped, never
heartbeated; they fire only live, on the press.

**The library handles both** — the builder writes neither. A community SYSTEM module
gets the dump and the heartbeat for free, exactly as a built-in one does (SDKable).

## 4c. LISTENER modules

A `LISTENER` subscribes to standard signals at install and receives them at runtime,
acting on its own hardware.

### Subscribing (install time)

One `SUBSCRIBE` per signal:

```
SUBSCRIBE|id|function|rate|threshold|gate|gate_value
```

All fields after `function` are optional. When a field is blank, the **firmware
library** fills the type-appropriate default *before the line is sent*, from the
signal's type in `system_commands.md` — never the builder, never DASH:

- **Boolean and multi-state** signals default to **event-driven** delivery (no rate,
  no threshold) — DASH delivers only on change.
- **Continuous** signals default to a sensible rate and threshold for that signal.
- **Gate** is always optional. Omitted ⇒ delivered whenever DASH normally would.

DASH honours whatever arrives **literally**: a blank field that reaches DASH carries
its total meaning (no cap / any change / always); a present-but-malformed field is
logged and ignored, never guessed at. The numbers live in `system_commands.md`; the
**consumer** of them is the library, not the controller.

### Delivery (runtime) — three ways

- **Heartbeat.** Every 5 s DASH pushes the current value of every subscribed
  **stateful** signal (changed or not) as `LISTEN`. The LISTENER compares to its last
  value and acts only on a difference.
- **Event delivery.** A subscribed stateful signal that changes is pushed
  immediately, outside the heartbeat.
- **On activation.** On `ACTIVATE|id`, DASH immediately delivers the current value of
  every subscribed stateful signal, before the first heartbeat.
- **Event-only signals** (`media_next`…) carry no value and have no state — delivered
  as a valueless `LISTEN` on fire only; no heartbeat, no on-activation delivery.

```
LISTEN|id|door_driver_open|true      ← stateful, has a value
LISTEN|id|media_next                  ← event-only, no value
```

**Change detection lives in the library**, not the builder's code: the library
receives every `LISTEN`, compares to the last known value, and calls the builder's
callback only on a change. The builder writes only what to do when a signal changes.

This mirrors §4b exactly — SYSTEM reports upward on heartbeat + on change; DASH
delivers downward on heartbeat + on change; same 5 s rhythm both ways.

## 5. The two layers inside DASH (source-awareness)

Recorded because it is the other half of the `BROADCAST` contract, though DASH-side:

- **Receiving layer (gatekeeper)** — source-aware; knows which module a message came
  from and **blocks any module not currently installed and active**.
- **Main system (core)** — acts on *meaning*, not sender; sourceless (told "headlights
  on", it reacts regardless of source — so redundant sources for one signal work).

The id rides in every message for the gatekeeper. Because every message carries the
id, a shared bus is handled like any other transport — no RS485 special case.

## 5a. Controller behaviours (the state store)

`system_commands.md` is the authoritative signal vocabulary and behaviour reference.
Every incoming `BROADCAST` is handled in one of three ways, **chosen by the signal,
never by the module** — the module just sends its value:

- **Store and event** — booleans / multi-state (`door_driver_open`, `gear_position`,
  `ignition_state`…). Update the store **and** fire an event.
- **Store only** — continuous signals (`vehicle_speed`, `engine_rpm`, `fuel_level`,
  `ambient_temp`…). Update the store silently; fire nothing (events on high-frequency
  values would flood listeners).
- **Event only** — momentary controls (`media_next`, `voice_activate`,
  `button_home_pressed`…). No value travels; fire the event and forget it, store
  nothing.

**The wire is always event-driven; the three behaviours are internal** and never
expressed on the wire. A builder never declares a behaviour. Event-only signals carry
no value field (`BROADCAST|id|media_next`); stateful signals always carry a value.

## 6. Module lifecycle

- Every module — SYSTEM included — must be **installed** before it sends any data. A
  module can be discoverable without being installed.
- A module sends data **only** while active.
- On uninstall, DASH deletes the module's record **and** the module stops sending.

**Single source of truth is DASH.** The module persists **nothing** about its install
state. It boots **SILENT** (answers `DISCOVER`, sends no data). DASH holds the
installed list on disk and tells the module what to do each session. So a module
uninstalled while powered off simply never gets activated next boot — no desync, no
zombie.

**States:** `SILENT` (boot; identity only) → `ACTIVE` (after `ACTIVATE`; sends data) →
`SILENT` (after `DEACTIVATE`).

**INSTALL ≠ ACTIVATE:** `INSTALL` happens **once** (the full handshake, saved to disk);
`ACTIVATE` happens **every boot** for an installed module (lightweight, no re-transfer).

**Boot reconciliation:** `DISCOVER` → match replies against the installed list →
`ACTIVATE` the matches. DASH keeps a **low-rate re-sweep** running so a slow booter is
picked up whenever it appears, and re-asserts `ACTIVATE` each sweep (a fresh `ROGER` is
the only proof a module is genuinely active *now* — this heals a brown-out reboot).

### `ACTIVATE` is idempotent — the module honours the re-assert

DASH re-asserts `ACTIVATE` on **every** reconciliation sweep — every 5 s for the first
60 s after a connect, then every 30 s — and reads the returning `ROGER` as its proof of
life. **A module must therefore treat a repeat `ACTIVATE` as a no-op.**

- **Always `ROGER`.** The ack *is* what DASH is asking for; never suppress it.
- **Run the activation work only on the real SILENT→ACTIVE transition** — the §4b state
  dump, the heartbeat clock, and any send-timers the firmware keeps. An already-ACTIVE
  module re-runs none of it.

A module that re-runs its activation on every `ACTIVATE` resets its own timers every
sweep, so any signal whose change interval is longer than a sweep never advances — it
sits frozen for the whole ~60 s fast-sweep phase after every connect, cold boot, or
reconnect. Nothing is lost by staying still: an ACTIVE module's ordinary heartbeat
already re-sends its state.

*(The `DashModule` library does this for you — the guard is in the base class.)*

### The firmware `version` field — a required freshness check

The sixth `HELLO` field is the module's **firmware version** — builder free text,
≤ 12 chars, with **no ordering contract**: DASH reads it only as *same* or *different*.

It is **load-bearing**. DASH captures it at install as part of the install contract
(alongside the `SYSTEM_SIGNAL` / `SUBSCRIBE` / MANIFEST declarations) and **re-checks
it against the `HELLO` every reconnect**. If it differs from the stored one, the stored
contract can no longer be trusted, so DASH **quarantines the module**: it is not
`ACTIVATE`d, held `DORMANT`, and every gatekeeper refuses its traffic both directions.
Because a compliant module boots SILENT, a quarantined one never transmits at all. The
user clears it with a one-tap **UPDATE** (a re-install — the handshake re-runs and the
contract is re-captured).

**The builder's obligation:** treat `version` as the contract's fingerprint. **Bump it
whenever anything DASH stored at install changes** — a signal added/removed, a
subscription altered, a panel asset changed. A pure logic change that touches no
declared interface may keep the same version, but when in doubt, bump it. Keep the
string **stable across reboots** for a given build (read fresh from a constant each
boot, never persisted).

### Absent = DORMANT, not an error, and transport-aware

- A **wired** module (USB/UART/RS485) heard this session and then gone silent reads as
  a **fault** — the orange **NOT_RESPONDING** state; DETAILS says "check power and cable".
- A **wireless** module (WiFi/BT) that goes silent is **ordinary dormant** — no alarm;
  it auto-reconnects when it returns.
- A module **never seen this session** stays a calm **DORMANT** — nothing is wrong.
- A **REFRESH** button offers a manual "check now" (after fixing wiring / plugging in),
  and prunes discovered-but-not-installed modules that no longer answer.

### A dropped wireless link — the module returns itself to SILENT

A wired module that loses its pipe loses power with it and reboots SILENT — clean by
construction. **A wireless module does not.** A dropped TCP socket or BT client sends no
`DEACTIVATE`: the board stays powered, still believing it is ACTIVE, with no way to
learn DASH has gone.

So a wireless module **detects the drop itself and returns to SILENT** — forgetting it
was active, running its own safe-state routine, and discarding any half-received inbound
line. On reconnect it waits to be re-`DISCOVER`ed and re-`ACTIVATE`d, exactly like a
fresh boot. This is what keeps DASH the single source of truth: the module never decides
for itself that it is still installed. The per-transport cues are in §12 — a failed
socket write, or `hasClient()` going false.

*(In the `DashModule` library this is one call — `linkLost()` — from the sketch's
link-maintenance loop. Harmless when already down. USB sketches never need it.)*

### Acknowledgements

- Live data (`BROADCAST` / `REPORT`) is **fire-and-forget** — stateless, self-healing;
  a lost value is overwritten by the next.
- `ACTIVATE` / `DEACTIVATE` are **acknowledged and retried** (`ROGER`), so DASH knows a
  module actually started/stopped.
- The install handshake is self-acknowledging — it ends with `INSTALL_END`.

### Uninstall robustness

The module remembers nothing, so it cannot return as a zombie. DASH **deletes the
record immediately** (forgetting *is* the uninstall), retries `DEACTIVATE` behind it,
and — only if no `ROGER` ever comes — raises the after-the-fact warning: *"…the module
could still send misleading data, so either disconnect the module or power-cycle the
module."* (Power-cycle the **module**, not DASH.) The uninstall is never blocked
waiting on an ack.

## 7. Discovery & install handshake (the flow)

```
DASH   → DISCOVER                                  (to everyone)
module → HELLO|id|type|name|description|version    (each module that hears it)

— user picks a module and installs —

DASH   → INSTALL|id                                (only the matching module replies)

  — an ACCESSORY sends its assets (§8) —
module → MANIFEST|id|blocks|bytes
module → BLOCK|id|icon|1342|<crc>
         <1342 raw bytes>
module → ... (further BLOCKs)

  — a SYSTEM module declares each signal it sends, one per line —
module → SYSTEM_SIGNAL|id|reverse
module → SYSTEM_SIGNAL|id|headlights

  — a LISTENER declares each signal it wants, one per line (§9) —
module → SUBSCRIBE|id|headlights
module → SUBSCRIBE|id|steering_angle|20hz||reverse|active

module → INSTALL_END|id
DASH   → (validate, save the complete record to disk)
DASH   → ACTIVATE|id
module → ROGER|id|activate
         (live data now flows)
```

A module sends the declarations for **its own type**: `SYSTEM_SIGNAL` (SYSTEM), asset
`BLOCK`s (ACCESSORY), `SUBSCRIBE` (LISTENER, and optionally ACCESSORY, §11). The flow
above stacks all three only to show them together.

**No live data mid-handshake.** From answering `INSTALL|id` until `INSTALL_END|id`, a
module sends **only** handshake messages — no `REPORT`, `BROADCAST`, or heartbeat may
interleave a declaration run or a block transfer. A first-time install starts from
SILENT; a reinstall/update is preceded by `DEACTIVATE`. Firmware whose handshake blocks
its main loop gets this for free; firmware that sends from timers/interrupts must
suspend them for the duration.

**Install failure is DASH-side; the module contract is unchanged.** DASH gives a
stalled, disconnected, or user-cancelled install a designed failure surface (an idle
timeout, a fast disconnect trip, a CANCEL button, each with a reason and a retry).
**None of this is module-facing.** A module still just streams its declarations to
`INSTALL_END`. If DASH cancels or the install fails mid-run, the module finishes its
monologue into a closed session — DASH drops-and-logs the strays and re-`DISCOVER`s it
for a fresh attempt. **There is no `INSTALL_ABORT` message:** the blocking handshake is
the model, and the library implements exactly that.

## 8. Asset transfer (the install payload)

ACCESSORY visuals (an SVG icon and layout panels) are multi-line and KB-scale, so they
ride inside the bounded install handshake as length-prefixed, checksummed blocks:

- `BLOCK|id|name|length|crc`, followed by exactly `length` raw bytes.
- The byte count makes the block **collision-proof** (content read verbatim, newlines
  and all) and **bounded** (DASH can reject an oversize block before reading it).
- **CRC is CRC32** (the standard zip polynomial — reflected, poly `0xEDB88320`, matching
  `java.util.zip.CRC32`), sent as **lowercase hex, no prefix, no padding** (exactly
  `String(crc, HEX)`). DASH parses base-16. It is applied to the install payload only,
  because that data is saved to disk and rendered every boot; live data self-heals and
  needs no checksum. Firmware may precompute each asset's CRC at build time.
- Assets live in **flash** (`PROGMEM`, or LittleFS/SPIFFS on ESP32) and are **streamed**
  a chunk at a time — never held whole in RAM. A modest board sends a 5 KB icon with a
  tiny working buffer.
- On a CRC/length mismatch DASH aborts the bounded handshake cleanly; nothing
  half-corrupted reaches disk.

> **Amended — 2026-10-01, roadmap 1.6.12 (Roger's express decision).** The bullet above is
> superseded in one respect only: **a damaged block no longer ends the install at once.** It is
> repaired first.
>
> 1. **The install runs to its end, untouched.** The module's monologue rule does not change —
>    DASH still never interrupts a module mid-install. A block that fails its CRC or length is
>    noted and the install carries on.
> 2. **After `INSTALL_END`, DASH asks for each damaged block again**: `RESEND|id|name`, one at a
>    time. The module is back in its normal loop by then and listening.
> 3. **The module answers with that one block** — the same `BLOCK|id|name|length|crc` header and
>    bytes it sent the first time. Nothing else; no `INSTALL_END` follows.
> 4. **Up to five requests per block** *(three as first written; raised to five the same day after
>    a 20-install hardware test — see §12, USB serial)*. A block still damaged after five, or a module that does
>    not answer within the install's idle timeout, fails the install exactly as before — and still
>    nothing half-corrupted reaches disk.
>
> **Purely additive.** A module built before this does not recognise `RESEND`, ignores it as it
> ignores any unknown command, and so behaves exactly as it always did. A module ignores a `RESEND`
> for a name it never shipped. The `DashAccessory` library (1.2.0) answers it automatically; a
> builder writes nothing.
>
> *Why:* USB serial delivered large payloads damaged about two installs in five — the right number
> of bytes, some of them wrong. WiFi and Bluetooth repair this underneath; a bare serial line does
> not. Asking for one block again costs a tenth of re-running the whole install.

**Capability tiering:** a rich ACCESSORY is realistic on ESP32+ (megabytes of flash). A
classic Uno R3 (32 KB) builds SYSTEM modules (no payload).

> **Widened — 2026-08-12 (roadmap 1.6.x).** *(A clarification to an observation, not a change
> to any rule in this locked document.)* The tiering above assumes assets live in the
> microcontroller's own flash. **They need not.** A microSD card over SPI is three wires and
> adds gigabytes, and many ESP32 boards already carry a slot.
>
> This matters more than it first appears because **a module never parses its own layout** —
> it streams bytes out of storage verbatim, and the byte-count framing above is what makes
> that possible. Understanding the payload is entirely DASH's job. So **a classic Uno R3 with
> an SD card can ship a rich ACCESSORY panel**, despite having 32 KB of flash, because it
> never needs to hold or comprehend a single byte of what it sends.
>
> Flash size therefore constrains *convenience*, not *capability*. What genuinely constrains a
> large payload is **transfer time on a slow transport** — Bluetooth SPP at realistic
> throughput turns a multi-megabyte install into a very long wait, where WiFi would not
> notice it. See `module-layout.md` §2 for asset-size guidance; there are no caps.

*(The panel **format** the blocks carry — layout slots, aspect ratios, the binding
vocabulary — is not yet locked; see **`module-layout.md`**, which supersedes `arduino.md`
§11 as the authority. The transfer mechanism here is locked and is not that document's
business.)*

## 9. Streams — standard signals delivered to a subscriber

A subscriber (LISTENER, or an ACCESSORY with subscriptions) declares at install what it
wants; DASH stores and delivers at runtime. Because standard signals are sourceless, the
value may originate from a different module entirely, mediated by DASH.

```
subscribe (once, at install):  SUBSCRIBE | id | function | rate | threshold | gate | gate_value
deliver   (running):           LISTEN | id | function | value
```

- **Delivery is always plain `LISTEN`.** The receiver never needs to know whether a
  value came throttled or on-change; the cleverness lives in the subscription.
- **There is no boolean-vs-stream distinction.** A discrete on/off is just a stream with
  no rate cap and no threshold. `SUBSCRIBE|id|headlights` **is** an on-change
  subscription. *Everything a module receives is a stream; a boolean is a slow one.*

### The four optional controls (all evaluated inside DASH)

| Field | Default | Meaning |
|---|---|---|
| `rate` | on-change (no cap) | Max delivery frequency, e.g. `20hz`. A time throttle. |
| `threshold` | any change | Minimum change before re-sending (deadband), in the signal's own units. |
| `gate` + `gate_value` | always active | A `function value` pair (`reverse active`) — the stream runs **only** while that condition holds; while false, nothing is sent. |

"Default" is what the **firmware library** fills into a blank field before sending. DASH
evaluates exactly as delivered: a blank field carries its literal total meaning; a
malformed field is logged and treated as absent, never repaired. The `rate` throttle is
**leading-and-trailing** — the first value of a burst is delivered at once and the last
at the window's close, so a stream converges to its resting value within one window
rather than waiting on the heartbeat.

```
SUBSCRIBE|id|steering_angle|20hz                     just rate-capped
SUBSCRIBE|id|steering_angle|20hz|2                   + deadband
SUBSCRIBE|id|steering_angle|20hz||reverse|active     + gate (blank threshold)
SUBSCRIBE|id|headlights                              plain on-change (a "boolean")
```

- **Gate is implicit equality.** `reverse active` means "while `reverse` equals
  `active`." (`<` / `>` and AND/OR deferred until a real need appears.)
- **Gate-open fires immediately.** When a gate flips false→true, DASH sends the current
  value at once (ignoring the threshold), so the module isn't stale. Gate-close stops.

On a shared bus, a delivered `LISTEN` carries the **target's** id — the addressed module
acts, the rest ignore it. Bus overload → DASH clamps the effective rate and degrades
gracefully, never a hard refusal (a DASH-side concern, not module-facing).

## 10. Buffer & line sizes

- **Firmware receive buffer:** 64 bytes is enough — the longest inbound message is a
  `LISTEN`. Exposed as a tunable `#define`; lean default, raise if wanted.
- **Protocol max line ≈ 160 bytes** (a DASH-side concern; Android has the RAM). Derived
  from the longest line, the `HELLO` response, via the field caps.

**Field caps:**

| Field | Cap | Notes |
|---|---|---|
| id | 12 | MAC |
| type | 9 | single type word, e.g. `ACCESSORY` |
| name | 24 | builder text |
| description | 64 | builder text |
| version | 12 | firmware version — load-bearing (§6) |

## 12. Transports — how a module reaches DASH

DASH is transport-agnostic: a message is a message once the bytes arrive, and the
transport is **never** part of the grammar. Everything in §1–§10 is identical on every
pipe. What differs is purely how the board gets onto the wire — the builder's
per-transport duty.

Whichever pipe you choose: boot **SILENT**, answer `DISCOVER` with `HELLO`, run the
install handshake, and only stream once `ACTIVATE`d.

### USB serial

The board presents as a **USB CDC serial device** (native on every ESP32; via the
on-board bridge on the Arduino Uno R4 WiFi). **DASH is the host** — it enumerates and
opens you at **115200 8N1**. You do nothing but `Serial.begin(115200)`. Wired, so DASH
treats a silent USB module as a potential **fault** (§6).

> **Added 2026-10-01, roadmap 1.6.12 — choose a board with native USB for a USB module.** The
> paragraph above is wrong in one respect: a *classic* ESP32 board (ESP32-WROOM DevKitC and
> similar) does **not** have native USB. It reaches the cable through a separate USB-to-serial
> converter chip (CP2102 / CH340), and shows up as `ttyUSB` rather than `ttyACM`.
>
> **That converter is where large installs get damaged.** Measured on the Tab S9 Ultra, the Tank
> Gauge's 78 KB picture arrived damaged in **9 of 20 installs** from a classic ESP32 over USB, and
> in **0 of 22** from an Arduino Uno R4 WiFi. `RESEND` (§8) repaired all 9, but a board that
> damages about half of every large send leans on it hard.
>
> **Recommendation, not a rule:** for a module that talks to DASH over USB, use a board that
> presents itself as a USB CDC device — the Uno R4, or an ESP32-S2 / S3 / C3 / C6 on its native
> USB port, a Pico, a Leonardo. A classic ESP32 is an excellent module over **WiFi or Bluetooth**,
> which repair damage underneath. A classic ESP32 over USB still works — DASH repairs what it can —
> but expect the occasional install to need a second press. Installs happen once per firmware
> version, not every drive, so this is an inconvenience and never a fault in the car.

### WiFi (TCP)

**DASH is the server; the module is the client.** DASH listens on a fixed TCP port —
**3274** (`D-A-S-H` on a phone keypad) — and your module opens a socket to
`DASH_HOST:3274` on boot, then talks the ordinary line grammar over it. On a dropped
socket, reconnect and go back to SILENT until DASH `DISCOVER`s you again. `DASH_HOST` is
the DASH device's address on the module network (fixed when the tablet is the hotspot).
Wireless, so absence is **dormant, never a fault**.

### Bluetooth Classic (SPP)

The wireless sibling of WiFi, over a Bluetooth serial link. Four builder duties:

1. **Classic SPP, not BLE — and a Classic-capable board.** Use the Serial Port Profile
   (`BluetoothSerial` on the ESP32). Only the **original ESP32** (WROOM-32) has a Classic
   radio; the **S3 / C3 / C6 are BLE-only** and cannot run an SPP module.
2. **The name marker — your Bluetooth name must contain `D.A.S.H`.** Classic BT has no
   friendly service-UUID advertisement, so DASH identifies its modules by device name:
   it dials only bonded devices whose name carries the token `D.A.S.H`. Name it
   `D.A.S.H-Powertrain`, `D.A.S.H Climate`, anything containing `D.A.S.H`. Set it in
   `SerialBT.begin("D.A.S.H-…")`. A module without the token is never found.
3. **Bond once in Android settings — DASH never pairs for you.** SPP requires bonding
   first; do it the normal way in Android's Bluetooth settings. DASH then connects **out**
   to the bonded module (like USB, DASH initiates). Your module runs the SPP server.
4. **Go SILENT the instant the link drops.** On the ESP32, `SerialBT.hasClient()` going
   false is your cue — reset to SILENT so a reconnect resumes cleanly. Absence is
   **dormant, never a fault**.

---

## Not locked — see `module-layout.md`

> **2026-10-01, roadmap 1.6.10 — `module-layout.md` is now locked too**, and the `DashAccessory`
> library class has joined `DashSystem` and `DashListener` in `arduino/DashModule/` (library
> 1.1.0). Nothing in *this* document changed at that lock: the ACCESSORY messages it already
> defines (`MANIFEST`, `BLOCK`, `REPORT`, `ACTION`, `TRIGGER`) are exactly what the library speaks.
> The per-block USB retry (`RESEND`) discussed for 1.6.10 was **deferred** (Roger) and is not part
> of this SDK. The note below is kept as written.

Two areas remain live design and are **not** part of this locked reference. They lock at
**roadmap 1.6.10**, once real modules have been drawn through them:

- **The ACCESSORY panel & layout** — how a module describes what DASH should draw. This now
  has its own document: **`module-layout.md`**, the authoritative panel specification. Read
  it there, not here and not in `arduino.md`.
- **Open Items.** Asset-size caps (max icon / max layout panel), mismatch recovery detail
  (abort whole install vs re-request one block), and framing for panel VARIABLES /
  per-trigger icons (likely the same `BLOCK` mechanism). Tracked in `module-layout.md`'s
  Open Items and `arduino.md`'s.

> **Superseded — 2026-08-06.** This section previously described the panel as *"the canvas
> model (PNG background + overlays), normalised coordinates, theme tokens (`@barText`) …
> and the overlay vocabulary (text / touch / image / transform / animation)"*. Two parts of
> that are now wrong rather than merely incomplete:
>
> - **"PNG background + overlays" is superseded by layers and bindings.** Raster, vector and
>   text are all layer types; nothing is privileged as the background. The old framing
>   quietly forced raster and vector to be two separate systems.
> - **`@barText` does not exist.** The four `bar*` tokens were retired at roadmap 1.5.2 and
>   replaced by nine — `@backgroundColourPrimary`, `@textColourPrimary`,
>   `@accentColourPrimary` and their secondaries, plus `@iconColour*` and `@font`. A module
>   written against the old names would have been written against a vocabulary DASH had
>   already discarded. See `module-layout.md` §7.
>
> The line is kept per the additive rule. Do not implement from it.

---

*This is the locked module SDK. Read `arduino/arduino.md` for the reasoning and the
open panel spec; read `system_commands.md` for the signal vocabulary.*
