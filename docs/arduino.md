# DASH Module SDK — Working Record

This document is the working record for the DASH module firmware / SDK design.
It captures the agreed **Module Definition** — the rules any module (built-in or
community) follows to talk to DASH — plus the items still open.

It is the **living working record** for the module SDK, and is part of the DASH
document set (see CLAUDE.md). It is *not* a locked Bible document — it is updated
freely as the SDK design progresses, and **nothing here changes transport.md**.
Once the SDK is locked, the agreed rules will be promoted — either into
transport.md (on Roger's express instruction) or into a dedicated module-rules
document.

> **SDK LOCKED & PROMOTED — DASH 1.4.15 (2026-07-13).** The transport/lifecycle/
> message SDK (§1–§10, §12) has been built and hardware-verified across
> 1.4.1–1.4.14 and is now **locked**, and the normative rules have been promoted
> into a dedicated Bible document, **`module-sdk.md`** — the clean, public,
> locked SDK reference community builders copy. `module-sdk.md` is authoritative
> from here on; **this document remains the working record** — the history, the
> reasoning, the open items — and is still edited freely.
>
> **What is NOT locked (and stays here as live design):** §11 (ACCESSORY panel /
> layout — aspect ratios, overlay vocabulary, the real layout format) and the
> **Open Items** below. These depend on the 1.6.x module panel actually being
> rendered and lock in the panel era, not now. `module-sdk.md` documents the
> asset-*transfer* mechanism (§8) as locked, and points here for the panel spec.
>
> **Decisions banked at the lock (2026-07-13):** CRC stays **CRC32** (the CRC16-
> for-weak-boards idea dropped unless a real need appears); **no `INSTALL_ABORT`**
> (§7); the firmware library fills `SUBSCRIBE` defaults from the signal table,
> generated from `system_commands.md` so the vocabulary has one source.

---

## Purpose

We are writing reference firmware against the DASH protocol in order to
pressure-test it and settle every ambiguity a real module exposes. The end
product is a **locked module SDK**, with the firmware doubling as:

1. The conformance test rig for the DASH-side transport layer (roadmap 1.4.x,
   not yet built).
2. The reference implementation community module builders will copy.

This honours the **SDKable principle**: built-in modules get no special access a
community builder couldn't have. The reference firmware *is* that level playing
field, made concrete.

---

## The Plan

Two reference modules, on hardware Roger already has:

| Order | Board         | Module type | Why |
|-------|---------------|-------------|-----|
| 1st   | Arduino Uno Q | SYSTEM      | Exercises the full core loop (discover → install → live data → relay) with **no** heavy payload (no icon, no layouts). Cleanest start. |
| 2nd   | ESP32         | ACCESSORY   | Adds the hard problems — shipping an icon and layout panels out of flash. Built once the core is proven. |

---

# Module Definition

The agreed, current design. This is the concept laid down for building the SDK.

## Cheat sheet (quick reference)

Every message: `TYPE | id | function | value…` — pipe-separated, positional,
trailing fields optional. `id` = the 12-hex MAC.

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
| `LISTEN\|id\|function\|value` | DASH→mod | Standard signal delivered to a subscriber — heartbeat (5s) + immediate on change + on-activation dump; no value field for event-only signals (see §4c). |
| `REPORT\|id\|variable\|value` | mod→DASH | Module's own panel data — specific / private. |
| `ACTION\|id\|control\|value` | DASH→mod | User operated one of the module's panel controls. |
| `TRIGGER\|id\|name` | mod→DASH | Agnostic alarm → system bar (icon; ACCESSORY only). |

**Install-time declarations** (sent once, during the handshake):

| Message | Dir | Purpose |
|---|---|---|
| `SYSTEM_SIGNAL\|id\|function` | mod→DASH | SYSTEM declares a signal it will broadcast — one per line. |
| `SUBSCRIBE\|id\|function\|rate\|threshold\|gate\|gate_value` | mod→DASH | LISTENER subscribes to a signal — one per line. Trailing fields optional; when blank, the **firmware library** fills the type-appropriate default (event-driven for boolean/multi-state, a sensible rate+threshold for continuous) from `system_commands.md` *before the line is sent* — never the builder, never DASH (§4c). |
| `MANIFEST\|id\|blocks\|bytes` | mod→DASH | ACCESSORY asset table-of-contents + total size. |
| `BLOCK\|id\|name\|length\|crc` + raw bytes | mod→DASH | ACCESSORY one asset (icon/panel), length-prefixed, CRC-checked. |
| `INSTALL_END\|id` | mod→DASH | Ends the handshake. |

Pairings to remember: `SYSTEM_SIGNAL`→`BROADCAST` and `SUBSCRIBE`→`LISTEN` are
each "declare at install → message at runtime"; `REPORT`↔`ACTION` are the two
halves of one private panel wire.

## 1. The wire — framing

- A module and DASH talk in **plain UTF-8 text, one message per line**.
- Each line ends with a newline. DASH **emits** `\n`; DASH **accepts** both `\n`
  and `\r\n` (it trims a trailing `\r`). So a standard `Serial.println()` works.
- Text, not binary, so the conversation is readable in any serial monitor —
  debuggability is a first-class goal.

## 2. The message structure (one grammar for everything)

Every message has the same shape, fields separated by the pipe `|`:

```
TYPE | id | function | value1 | value2 | value3
```

- **Positional.** The meaning of each field is fixed by position.
- **Fields fall off from the right.** A message uses as many fields as it needs;
  unused trailing fields are simply absent. You may **not** skip a field in the
  middle — if a later field is present, all earlier ones must be too. Legal
  shapes: `TYPE`, `TYPE|id`, `TYPE|id|function`, `TYPE|id|function|value1`, …
- **Field 1 (TYPE)** declares what the message is and therefore how to read the
  rest.
- **Field 2 (id)** is the module ID. **Every message names its module**, in both
  directions (see §4). The one bootstrap exception is `DISCOVER`, which has no
  target (it goes to everyone).
- **Fields 3+** are payload; their meaning is defined per TYPE.

### Delimiter and content rules

- The delimiter is the pipe **`|`** — chosen because it essentially never appears
  in real names, descriptions or data, so forbidding it costs nothing.
- **Exactly two characters are forbidden in any field content:** the newline
  (it ends a message) and the pipe `|` (it separates fields). **Everything else
  is allowed** — including colons, so a value like `12:30` or `21.5` is fine.
- Because the delimiter never appears in content, **parsing is a plain split on
  `|`** — no escaping, no clever "split only the first N" logic. The SDK strips
  the two forbidden characters from any field before sending.
- **Forward compatibility:** a receiver **ignores extra trailing fields it does
  not recognise.** This lets the structure grow (a future `value2`/`value3`)
  without breaking older firmware.
- **One value per field — no embedded lists.** A field never holds a
  comma-separated (or otherwise sub-delimited) list. To declare several things,
  send several messages — one item per line. (This is why a module declares each
  signal it sends/subscribes to on its own `SYSTEM_SIGNAL` / `SUBSCRIBE` line,
  rather than a single comma-joined line.) Keeps parsing a single plain split with
  no nested grammar anywhere.

## 3. Module identity (the id)

- The id is the full **12-hex-character MAC address**, separators removed,
  uppercase — e.g. `A4CF12B8D9E0`. (A bare MAC is written `A4:CF:12:B8:D9:E0`;
  the colons are dropped.)
- The MAC is factory-assigned and globally unique, so there is zero risk of two
  modules clashing and zero management for the builder — firmware just reads its
  own MAC at boot.
- **MAC-less fallback:** a board with no networking (e.g. a classic Arduino Uno
  R3) has no MAC. There the builder must assign a unique id themselves. Rule:
  *use the MAC where you have one; otherwise it is on you to guarantee
  uniqueness.*
- The id is load-bearing in three places — message routing, install/
  reconciliation records, and addressing a specific module on a shared bus — so
  its uniqueness matters.

## 4. The message vocabulary

**DASH → module** (commands). No `DASH:` prefix — the TYPE word already says it
is a command:

| Message | Meaning |
|---|---|
| `DISCOVER` | Broadcast to everyone: announce yourself. |
| `INSTALL\|id` | Set this module up (begins the install handshake). |
| `ACTIVATE\|id` | You are installed — start sending live data. |
| `DEACTIVATE\|id` | Stop sending. |
| `LISTEN\|id\|function\|value` | Here is a **standard** signal you subscribed to — delivered on a 5s heartbeat, immediately on change, and once on activation; event-only signals arrive with no value field, on fire only (see §4c). |
| `ACTION\|id\|control\|value` | A user operated one of **your panel's** controls — **agnostic** input. |

**module → DASH:**

| Message | Meaning |
|---|---|
| `HELLO\|id\|type\|name\|description\|version` | Discovery response (identity). `type` is the module's single type (see §4a) — one word, never a combination. Transport is **not** included — DASH knows it from the connection. |
| `BROADCAST\|id\|function\|value` | Standard DASH signal (headlights, reverse…). |
| `REPORT\|id\|variable\|value` | The module's own agnostic data. |
| `TRIGGER\|id\|name` | An agnostic alarm → system-bar alert area (carries an icon; ACCESSORY only). |
| `ROGER\|id\|which` | Thumbs-up confirming a command was carried out. |

**Examples** (full MAC shown; shortened elsewhere for readability):

```
DISCOVER
HELLO|A4CF12B8D9E0|ACCESSORY|Climate Control|Dual zone cabin temp & fan|v1.2
INSTALL|A4CF12B8D9E0
BROADCAST|A4CF12B8D9E0|reverse|active
REPORT|A4CF12B8D9E0|temperature|21.5
LISTEN|A4CF12B8D9E0|headlights|on
ACTION|A4CF12B8D9E0|fan_up|on
```

**Symmetry to note.** The four data messages form a 2×2 — *general vs specific*
(does it go to everyone, or to one place?) × *send vs receive* (from the module's
point of view):

| | **send** (module→DASH) | **receive** (DASH→module) |
|---|---|---|
| **general** (standard signals — pub/sub) | `BROADCAST\|id\|function\|value` | `LISTEN\|id\|function\|value` |
| **specific** (agnostic — the module's own panel) | `REPORT\|id\|variable\|value` | `ACTION\|id\|control\|value` |

The naming *style* matches the channel's *shape*. Standard signals are a shared
bus, so they get pub/sub verbs — you `BROADCAST` to all, you `LISTEN` for what you
want. The agnostic panel is a private one-to-one wire, so it gets the directed
pair — you `REPORT` your data out, and an `ACTION` comes back in. Same id slot,
same payload shape across all four.

## 4a. Module types

A module is exactly **one** type. The `type` word in `HELLO` is a single word —
**never** a combination. The three types are mutually exclusive:

| Type | What it does |
|---|---|
| `SYSTEM` | Sends standard DASH signals — DASH's known vocabulary (reverse, headlights, ignition). Send-only. |
| `ACCESSORY` | Owns an agnostic interactive panel — things DASH knows nothing about; the module is king inside its box. **Two-way:** ships the panel + its data (`REPORT`) and triggers, **and receives interactions with it** (`ACTION`). |
| `LISTENER` | Receives subscribed **standard** signals from DASH (relays) and acts on them — e.g. switches its own hardware. |

**A module does one job.** To do several jobs, a board presents **several
modules** — one per job, each with its own id and its own single type. To DASH
they are fundamentally separate modules that happen to share a board.

Example — one board, three ids, one of each type:
- a `SYSTEM` module reporting the vehicle's exterior lights,
- an `ACCESSORY` module drawing a climate panel,
- a `LISTENER` module switching an aux relay on a subscribed signal.

DASH sees **three modules**. It never knows (or cares) they share silicon — what
happens inside the board is the module's business (module is king). The firmware
just honours **each module's lifecycle independently**: discover, install,
activate and uninstall happen **per id**. (Unique id per face is easy — an ESP32
has its base MAC plus derived per-interface MACs; otherwise the builder assigns
per the §3 fallback.)

**Why these three, and why no HYBRID:**
- The real fault line is *language*: **SYSTEM speaks DASH's known vocabulary;
  ACCESSORY speaks its own and DASH doesn't care.** `LISTENER` is the inbound
  counterpart on the **standard** side — standard signals flowing *to* a module so
  it can act.
- **Two kinds of "inbound" — keep them apart.** A `LISTENER` module takes in
  **standard** signals (relays). An `ACCESSORY` takes in **its own panel's**
  interactions (`ACTION`) — fully agnostic, intrinsic to having an interactive
  panel. Different mechanisms, different domains; that's why ACCESSORY is two-way
  but isn't a `LISTENER`. (A climate panel that *also* wants standard signals like
  headlights relayed is still an `ACCESSORY` face **plus** a `LISTENER` face.)
- **HYBRID is retired.** It only ever meant "two jobs in one," which is now
  simply two modules on one board.
- **Triggers belong to ACCESSORY only.** A trigger carries an **icon**, and only
  an ACCESSORY ships assets — so SYSTEM and LISTENER have nothing to draw, and no
  agnostic alarm to raise. Anything that must raise an alarm is an ACCESSORY.
- **Gap 8 resolved:** a `SYSTEM` module is send-only and cannot subscribe to
  relay. To receive a *standard* signal you add a separate `LISTENER` module.

**On the wire,** the `type` word in `HELLO` names the one type, and what the
module declares at install matches it — `SYSTEM_SIGNAL` lines (SYSTEM), panel/icon
`BLOCK`s (ACCESSORY), `SUBSCRIBE` lines (LISTENER).

## 4b. State reporting (SYSTEM modules)

A SYSTEM module does not only report *changes* — it reports **current state**, on
two occasions, so the controller's state store is never guessing.

**On activation — the full dump.** When DASH sends `ACTIVATE|id`, the module
immediately replies (alongside its `ROGER|id|activate`) with the current value of
**every** signal it manages, each as an ordinary `BROADCAST|id|function|value`.
This is a full state dump, not a change notification: the module reports what it
currently sees whether or not anything has changed. It closes the gap where a
signal's value would otherwise be unknown until the next time it happened to
change — e.g. a door that was already open before the module came online.

**Periodic heartbeat.** Independent of any change, a SYSTEM module re-sends its
current signal values at a regular interval — **recommended every 5 seconds**.
Again this is a state report, not a change notification. The module never has to
track whether a value changed; it simply reports current state on the heartbeat.

**The controller owns change detection.** For both the dump and the heartbeat,
DASH compares each incoming value against its state store: if it differs, DASH
updates the store and fires an event; if it is the same, DASH does nothing. All
change-detection logic lives in the controller, not the module. This keeps the
module dumb and stateless (it reads its inputs and reports them) and makes the
store **self-healing** — a value missed during a brief disconnect is corrected
within one heartbeat. It is the same self-healing property §6 notes for live data,
now guaranteed on an interval rather than left to chance.

Both the dump and the heartbeat apply to **stateful** signals only — the
store-and-event and store-only kinds (§5a). Momentary **event-only** controls
have no current value, so they are never dumped and never heartbeated; they fire
only live, on the press.

**The library handles it.** Both behaviours are built into the module firmware
library — the builder writes neither. A community SYSTEM module gets the
on-activation dump and the heartbeat for free, exactly as a built-in one does
(SDKable).

## 4c. LISTENER module — full specification

A `LISTENER` module subscribes to one or more standard signals at install time
and receives those signals from the DASH controller at runtime. It acts on its
own hardware in response — switching a relay, driving an output, controlling a
device.

### Subscribing (install time)

The module sends one `SUBSCRIBE` message per signal it wants to receive:

```
SUBSCRIBE|id|function|rate|threshold|gate|gate_value
```

All fields after `function` are optional. Their defaults are determined by the
**signal's type**, and are filled by the **firmware library**, not chosen by the
builder:

- **Boolean and multi-state signals** default to **event-driven delivery** — no
  rate, no threshold. DASH delivers only on change.
- **Continuous signals** default to a sensible rate and threshold appropriate to
  that signal type. The builder overrides these only if it needs different
  behaviour.
- **Gate** is always optional for any signal type. If omitted, the signal is
  delivered whenever the controller would normally deliver it.

> **Amendment, 2026-07-08 (with Roger):** defaults are the **firmware library's**
> responsibility, not DASH's. When a builder leaves a field blank, the library
> fills the type-appropriate default *before* the `SUBSCRIBE` line is sent, so the
> line reaching DASH is already complete. DASH holds no defaults and records
> nothing extra — it honours whatever arrives, literally. This keeps the
> default-provider off the sketch author (the principle "not chosen by the builder"
> still holds — the *library* chooses) and out of DASH (ethos: DASH holds no
> opinion on a signal's rate; SDKable: the default lives in the shared library
> every module uses). `system_commands.md` remains the reference for the *numbers*;
> the *consumer* of them is the library, not the controller. A blank field that
> does reach DASH is legal and total (no cap / any change / always) and honoured as
> such; a present-but-malformed field is logged and ignored, never guessed at.

A LISTENER that only needs to know when something crosses a threshold can
subscribe to a continuous signal with a threshold and no rate — DASH delivers
only when the value crosses the threshold, not constantly.

(See §9 for the full mechanics of `rate` / `threshold` / `gate` and shared-bus
bandwidth — this section covers what the LISTENER receives and when.)

### Delivery (runtime) — three ways

The controller delivers signals to the LISTENER in three ways:

**Heartbeat.** Every 5 seconds the controller pushes the current value of every
subscribed **stateful** signal to the LISTENER via a `LISTEN` message,
regardless of whether the value has changed. The LISTENER compares the incoming
value to its last known value, acts if different, ignores if the same. This
keeps the LISTENER current even if it missed an event, or came online after the
state was already set.

**Event delivery.** The moment a subscribed stateful signal changes, the
controller immediately pushes the new value to the LISTENER outside of the
heartbeat cycle. The LISTENER does not wait up to 5 seconds to find out about a
change — it is told immediately.

**On activation.** The moment DASH sends `ACTIVATE|id` to a LISTENER module, the
controller immediately pushes the current value of every subscribed stateful
signal, before the first heartbeat. This ensures the LISTENER knows the full
current state from the moment it comes online, with no gap for states that
changed before the module booted.

**Event-only signals.** Momentary controls such as `media_next` or
`button_home_pressed` carry no value and have no state. If a LISTENER subscribes
to an event-only signal, the controller delivers a `LISTEN` message with no
value field the moment the event fires. There is no heartbeat delivery and no
on-activation delivery for event-only signals — there is no state to deliver.

```
LISTEN|id|door_driver_open|true      ← stateful, has a value
LISTEN|id|media_next                  ← event-only, no value
```

### Change detection lives in the firmware library

Change detection lives in the LISTENER firmware library, not the builder's
code. The library receives every `LISTEN` message, compares it to the last
known value for that signal, and calls the builder's registered callback only
when the value has changed. The builder writes only what to do when a signal
changes — not the comparison logic.

### Symmetry with the SYSTEM module

The SYSTEM module (§4b) reports upward to the controller on a heartbeat and
immediately on change. The controller delivers downward to the LISTENER on a
heartbeat and immediately on change. Same rhythm, both directions — the 5
second heartbeat interval matches.

## 5. The two layers inside DASH (source-awareness)

- **Receiving layer (gatekeeper)** — what the connections plug into. It is
  inherently source-aware: it knows which module/connection a message came from,
  and it blocks data from any module that is not currently installed and active.
- **Main system (core)** — acts on *meaning*, not on *who sent it*. It is
  sourceless: told "headlights on", it dims the screen regardless of source
  (this allows redundant sources for the same signal).

The id rides in every message for the **gatekeeper's** benefit. For `BROADCAST`
messages the gatekeeper consumes the id and hands the core a clean
source-free message, so the core stays sourceless (recommended; passing the id
through for the core to ignore is an equally valid alternative — it is a
DASH-internal detail, invisible to the module).

This is why there is no RS485 special case: because **every** message carries the
id, a shared bus is handled exactly like any other transport. The earlier
"add an address prefix only on RS485" idea is **superseded** by uniform ids.

## 5a. Controller architecture (the state store & the three behaviours)

This is DASH-side, not module-side — but it is recorded here because it is the
other half of the `BROADCAST` contract, and because it settles exactly what a
SYSTEM module's signals *mean* once they arrive. **`system_commands.md` is the
authoritative signal vocabulary and behaviour reference** — the master list of
every standard signal, its value type, and which of the three behaviours below it
uses. This section describes how the controller acts on that list.

**Three behaviours, chosen by the signal — never by the module.** Every incoming
`BROADCAST` is handled in one of three ways, determined by the signal's type in
`system_commands.md`. The module neither knows nor declares which — it just sends
its value; the controller looks the behaviour up.

- **Store and event** — boolean and multi-state signals (`door_driver_open`,
  `gear_position`, `ignition_state`…). The controller updates the state store
  **and** fires an event in one step. The interface can react immediately to the
  event or query the store at any time for the current value.
- **Store only** — continuous signals (`vehicle_speed`, `steering_angle`,
  `engine_rpm`, `fuel_level`, `coolant_temp`, `ambient_temp`, `ambient_light`,
  `charge_level`). The value updates silently in the store on each new reading; no
  event fires. Interface components that need these read the store directly at
  whatever rate they choose. Firing events on high-frequency continuous signals
  would flood listeners for no benefit.
- **Event only** — momentary control signals (`media_next`, `media_prev`,
  `media_play_pause`, `media_volume_up`, `media_volume_down`, `voice_activate`,
  `button_home_pressed`). No value travels with these — the message *is* the whole
  content. The controller fires the event and forgets it; nothing is stored,
  because there is no state to maintain.

**The wire is always event-driven; the three behaviours are internal.** Every
signal reaches the controller as a message — the store/event distinction is
entirely inside DASH and is **never expressed on the wire**. A builder never
declares a behaviour. This is what keeps modules dumb and the vocabulary
authoritative.

**Event-only signals carry no value field.** A momentary control on the wire is
just:

```
BROADCAST|id|media_next
```

No value field — its absence is the signal. (This is the "trailing fields
optional" rule from the cheat sheet doing real work: the controller sees no value
and knows immediately this is event-only.) State and event signals always carry a
value:

```
BROADCAST|id|door_driver_open|true
BROADCAST|id|gear_position|reverse
```

**The state store.** The controller keeps a persistent in-memory store — the
current known value of every *stateful* signal (store-and-event and store-only)
received since DASH started. The interface reads current values straight from it.
It is populated on module activation by the state dump and kept current by the
periodic heartbeat (§4b). If DASH restarts, the store is empty until modules
report in — which the on-activation dump guarantees they do the moment they go
active. Event-only controls never enter the store; they have no state to hold.

## 6. Module lifecycle

**Rules:**
- Every module — SYSTEM included — must be **installed** before it sends any data.
  A module can exist and be discoverable without being installed.
- A module sends data **only** while active. This keeps the bus clear.
- On uninstall, DASH deletes the module's record **and** the module stops sending.

**Single source of truth is DASH.** The module persists *nothing* about its
install state. It boots **SILENT** (answers `DISCOVER`, sends no data). DASH holds
the installed list on disk and tells the module what to do each session. This
makes the rules self-enforcing: a module uninstalled while powered off simply
never gets activated next boot, so it stays silent — no desync, no "zombie".

**States:** `SILENT` (boot; identity only) → `ACTIVE` (after `ACTIVATE`; sends
data) → back to `SILENT` (after `DEACTIVATE`).

**INSTALL ≠ ACTIVATE:**
- `INSTALL` happens **once** — the full handshake; assets/declarations saved to
  disk.
- `ACTIVATE` happens **every boot** for an already-installed module —
  lightweight, no re-transfer.

**Boot reconciliation:** `DISCOVER` → match replies against the installed list →
`ACTIVATE` the matches. DASH keeps a **low-rate re-sweep** running so a slow
booter is picked up whenever it appears (the Arduino Uno Q's full Linux boot of
~20–30 s is the worst case — a fixed "3 tries then give up" would miss it).

> **`ACTIVATE` idempotency — the module's obligation (added 2026-07-17, built and
> hardware-verified in DASH 1.4.16).** The low-rate re-sweep above does more than
> pick up a slow booter: it **re-asserts `ACTIVATE` every sweep** as a liveness
> ping (1.4.6), because a fresh `ROGER` is the only proof a module is genuinely
> active *now* — it heals a brown-out reboot. That design is deliberate and it
> stays. But it only works if the module treats a repeat `ACTIVATE` as a no-op,
> which the SDK had always *assumed* and never *stated*.
>
> Ours didn't. Until 1.4.16 the library re-ran its whole activation on every
> `ACTIVATE` — the §4b dump, the heartbeat clock, and the builder's `onActivate`
> hook, which reseeds the send-timers. During the fast-sweep phase (every 5 s for
> the first 60 s) that reset the timers faster than they could run, so any signal
> whose change interval exceeded a sweep never advanced. The tell on the wire: the
> discrete signals (gear, headlights, door) sat frozen while the continuous ones
> (speed, ambient) stayed fresh, and everything broke free at exactly 60 s — the
> moment the sweep drops to 30 s. It read as a USB reconnection fault and was
> chased into the transport before instrumentation proved the transport innocent.
>
> **The rule, now stated:** always `ROGER` (that *is* the proof of life), but run
> the activation work **only on the real SILENT→ACTIVE transition**. Nothing is
> lost by standing still — an ACTIVE module's 5 s heartbeat already re-sends state.
>
> *(Roger's call, 2026-07-16: fix the module, not DASH. DASH honouring its own
> 1.4.6 liveness design is correct; the module honouring the idempotency DASH
> already assumed is the fix — and it makes the re-assert genuinely harmless where
> before it silently corrupted timers. The guard lives in the `DashModule` base
> class, so every library module and every future community module inherits it —
> the SDKable principle paying out.)*

> **The firmware `version` field — a required freshness check (added 2026-07-12,
> built and hardware-verified in DASH 1.4.13).** The sixth `HELLO` field
> (`HELLO|id|type|name|description|version`) is the module's **firmware version** —
> builder free text, ≤ 12 chars (§10), with **no ordering contract**: DASH never
> reads it as "newer/older", only as *same* or *different*.
>
> It is **load-bearing**, not cosmetic. DASH captures the version at install as
> part of the install contract (alongside the module's `SYSTEM_SIGNAL` /
> `SUBSCRIBE` / MANIFEST declarations) and **re-checks it against the `HELLO` on
> every reconnect**. If the reported version differs from the stored one, the
> stored contract can no longer be trusted, so DASH **quarantines the module**: it
> is **not** `ACTIVATE`d, it is held `DORMANT`, and every gatekeeper refuses its
> traffic in both directions — the module's `BROADCAST`/`REPORT` are dropped and no
> `LISTEN`/`ACTION` is sent to it. Because a compliant module boots SILENT and only
> speaks after `ACTIVATE`, a quarantined module never transmits at all. The user
> clears it with a one-tap **UPDATE** (a re-install — the handshake re-runs and the
> contract is re-captured from the new firmware), after which the module activates
> normally. *(Roger's call, 2026-07-12: a mismatch is a hard rejection, not a
> warning — the version is only worth carrying if a difference actually withholds
> trust. This supersedes the initial "surface but keep working" design.)*
>
> **The builder's obligation:** treat `version` as the contract's fingerprint.
> **Bump it whenever anything DASH stored at install changes** — a signal added or
> removed, a subscription altered, a panel asset changed. Bumping it is what tells
> DASH to re-capture; *failing* to bump it after changing declarations would leave
> DASH running on a stale contract with no way to know. A pure logic change that
> touches none of the declared interface may keep the same version (DASH will
> re-activate silently), but when in doubt, bump it — the cost is one UPDATE tap.
> Keep the string **stable across reboots** for a given firmware build: it is read
> fresh from a constant each boot, never persisted, so it must not vary run to run.

**Absent = DORMANT, not an error, and transport-aware:**
- A **wired** module (UART/RS485) expected but absent → **fault** indication.
- A **wireless** module (WiFi/BT) absent → normal dormant, no alarm; auto-
  reconnects when it returns.
- A **REFRESH** button offers a manual "check now" (e.g. after fixing wiring).

> **Built and reconciled (DASH 1.4.14, 2026-07-13).** The transport-aware
> distinction above is now real. An installed module *heard this session and then
> gone silent* reads as an orange **NOT_RESPONDING** state; a module never seen
> this session stays a calm **DORMANT** (not plugged in yet / out of range —
> nothing wrong). The wired/wireless split words the *reason* shown in DETAILS —
> a wired module reads as a fault ("check power and cable"), a wireless one as
> ordinary ("may be out of range"). The manual "check now" button, called **SYNC**
> here originally, was renamed **REFRESH** in 1.4.14 and additionally prunes
> discovered (not-installed) modules that no longer answer.

**Acknowledgements:**
- Live data (`BROADCAST`/`REPORT`) is **fire-and-forget** — stateless and
  self-healing; a lost value is overwritten by the next.
- `ACTIVATE`/`DEACTIVATE` are **acknowledged and retried** (the module sends
  `ROGER`), so DASH can be sure a module actually started/stopped.
- The install handshake is self-acknowledging — it ends with `INSTALL_END`.

> **`linkLost()` — the wireless module's own drop detection (added 2026-07-17,
> built in DASH 1.4.16).** The ack rules above cover the orderly path: DASH says
> `DEACTIVATE`, the module `ROGER`s and stops. A **wireless** module has a path
> with no ack at all — the link simply vanishes. A dropped TCP socket or BT client
> sends no `DEACTIVATE`, and unlike a USB module (where a cable pull is a full
> reboot) the board stays powered, still believing it is ACTIVE.
>
> So the wireless module detects the drop and **returns itself to SILENT**:
> forgetting it was active, running the safe-state `onDeactivated` hook, and
> clearing its inbound assembler so a half-received line can't be spliced onto the
> next connection. On reconnect it is SILENT until DASH re-`DISCOVER`s and
> re-`ACTIVATE`s it — the ordinary boot path, and the thing that keeps DASH the
> single source of truth. §12 already stated this per-transport ("go back to
> SILENT until DASH `DISCOVER`s you again"; `hasClient()` going false); it is a
> lifecycle rule, so it belongs here too.
>
> *(In the `DashModule` library it is one call — `linkLost()` — made from the
> sketch's link-maintenance loop, and harmless when already down. USB sketches
> never call it.)*

**Uninstall robustness:** because the module remembers nothing, it cannot return
as a zombie after a reboot. A lost `DEACTIVATE` ack is at worst transient.

> **Reconciled with the built behaviour (DASH 1.4.6, 2026-07-13).** The shipped
> model is *not* a blocking FORCE UNINSTALL step. DASH **deletes the record
> immediately** — forgetting *is* the uninstall — then retries `DEACTIVATE`
> behind it, and raises the warning only **after the fact** if no `ROGER` ever
> comes: *"…the module could still send misleading data, so either disconnect the
> module or power-cycle the module."* (Power-cycle the **module**, not DASH —
> DASH cannot stop a powered module already running.) There is no separate FORCE
> UNINSTALL button; the uninstall is never blocked waiting on an ack.

## 7. Discovery & install handshake (the flow)

```
DASH   → DISCOVER                                  (to everyone)
module → HELLO|id|type|name|description|version    (each module that hears it)

— user picks a module and installs —

DASH   → INSTALL|id                                (only the matching module replies)

  — an ACCESSORY sends its assets (see §8) —
module → MANIFEST|id|blocks|bytes                  (table of contents + total size)
module → BLOCK|id|icon|1342|<crc>
         <1342 raw bytes>
module → BLOCK|id|h_small_light|980|<crc>
         <980 raw bytes>
module → ... (further BLOCKs)

  — a SYSTEM module declares each signal it sends, one per line —
module → SYSTEM_SIGNAL|id|reverse
module → SYSTEM_SIGNAL|id|headlights

  — a LISTENER module declares each signal it wants, one per line (see §9) —
module → SUBSCRIBE|id|headlights                            (plain on-change)
module → SUBSCRIBE|id|steering_angle|20hz||reverse|active   (throttled + gated)

module → INSTALL_END|id
DASH   → (validate, save the complete record to disk)
DASH   → ACTIVATE|id
module → ROGER|id|activate
         (live data now flows)
```

A given module sends declarations for **its own type**, plus optionally
`SUBSCRIBE` lines — a SYSTEM module sends `SYSTEM_SIGNAL` lines, a LISTENER
module `SUBSCRIBE` lines, an ACCESSORY its `BLOCK`s **and, optionally,**
`SUBSCRIBE` lines of its own (§11). The flow above stacks all three only to show
them in one place.

What an install transfers depends on the module's **type** (§4a):
- **`SYSTEM`** → one `SYSTEM_SIGNAL` line per standard signal it **sends**. No visuals.
- **`ACCESSORY`** → its **visuals** (icon + layout panels, as `BLOCK`s), its
  trigger icons, its display **variables** (the `REPORT` data it pushes), **and its
  interactive controls** (each with an id, so DASH can route a `ACTION` event back
  when the user operates it). Variables out and control ids in are the two halves of
  the panel contract. It may **also** send `SUBSCRIBE` lines for standard signals
  it wants delivered to it (§11) — handled identically to a LISTENER's.
- **`LISTENER`** → one `SUBSCRIBE` line per signal it wants **delivered** (§9).

(A board doing several jobs installs several modules — one per type — each with
its own handshake.)

**No live data mid-handshake** *(rule added 2026-07-07)* — from answering
`INSTALL|id` until `INSTALL_END|id` is sent, a module sends **only** handshake
messages. No `REPORT`, no `BROADCAST`, no heartbeat — nothing may interleave a
declaration run or a block transfer. The lifecycle already makes this natural:
a first-time install starts from SILENT (there is no live data to hush), and a
reinstall/update is preceded by `DEACTIVATE` (DASH quiesces the module before
re-running the handshake — roadmap 1.4.12). Firmware whose handshake blocks its
main loop — as all three reference sketches do — gets the rule for free;
firmware that sends from timers or interrupts must suspend them for the
duration. (The mirror-image DASH-side guarantees — per-device frame assembly on
point-to-point transports, bus quiescing on a future shared bus — are DASH
1.4.10 work, not module-facing protocol.)

> **Install failure is DASH-side; the module contract is unchanged (DASH 1.4.14,
> 2026-07-13).** DASH now gives a stalled, disconnected, or user-cancelled
> install a *designed* failure surface — an idle-timeout, a fast disconnect trip,
> a CANCEL button, each shown with a reason and a retry. **None of this is
> module-facing.** A module still just answers `INSTALL` by streaming its
> declarations to `INSTALL_END`, exactly as before. If DASH cancels or the install
> fails mid-run, the module simply finishes its monologue into a closed session —
> DASH drops-and-logs the stray lines harmlessly, and re-`DISCOVER`s the module
> for a fresh attempt. **There is deliberately no `INSTALL_ABORT` message
> (decided 2026-07-13):** making the handshake interruptible mid-send would force
> non-blocking asset streaming on every module to save a couple of seconds of
> harmless monologue — not worth it. The blocking handshake stays the model, and
> the firmware library implements exactly that.

## 8. Asset transfer (the install payload)

ACCESSORY visuals (an SVG icon and up to twelve layout panels) are multi-line and
KB-scale, so they don't fit the one-line message rule. They are carried inside
the bounded **install handshake** as length-prefixed, checksummed blocks:

- `BLOCK|id|name|length|crc`, followed by exactly `length` raw bytes.
- The byte count makes the block **collision-proof** (content is read verbatim,
  newlines and all) and **bounded** (DASH can reject an oversize block before
  reading it). Begin/end sentinels were rejected — an SVG could legitimately
  contain the sentinel text.
- **CRC** (default **CRC32**) catches corruption. It is applied to the install
  payload **only**, because that data is **saved to disk** and rendered every
  boot — silent corruption there would persist. Live data needs no checksum (it
  self-heals). The firmware can precompute each asset's CRC at build time, so even
  a weak board pays nothing at install time.
- Assets live in **flash** (`PROGMEM`, or LittleFS/SPIFFS on ESP32) and are
  **streamed** out a chunk at a time — never held whole in RAM. A modest board
  can send a 5 KB icon with a tiny working buffer.
- On a CRC/length mismatch DASH aborts the bounded handshake cleanly; nothing
  half-corrupted reaches disk.

**Capability tiering (not a flaw):** a rich ACCESSORY is realistic on ESP32+
(megabytes of flash). A classic Uno R3 (32 KB) builds SYSTEM modules (no payload).

## 9. Streams — standard signals delivered to a LISTENER module

A `LISTENER` module subscribes, at install, to the signals it wants delivered. DASH
stores the subscriptions and delivers values at runtime. This lets a module react
to vehicle state without its own sensor — and because system signals are
sourceless, the state may originate from a *different* module entirely, mediated
by DASH, with neither aware of the other.

### One subscription message, one delivery message

```
subscribe (once, at install):  SUBSCRIBE | id | function | rate | threshold | gate | gate_value
deliver   (running):           LISTEN | id | function | value
```

- **Delivery is always plain `LISTEN`** — `LISTEN|id|headlights|on`,
  `LISTEN|id|steering_angle|360`. The module receiving a value never needs to know
  whether it came throttled or on-change; the cleverness all lives in the
  subscription.
- **There is no separate "boolean" vs "stream" subscription.** To DASH every
  signal is the same — it just relays values. A discrete on/off signal is simply a
  stream with no rate cap and no threshold. `SUBSCRIBE|id|headlights` (everything
  trailing left off) **is** an on-change subscription. *Everything a module
  receives is a stream; a boolean is just a slow one.*

### The four optional controls

All evaluated **inside DASH** (the gatekeeper/core), so the wire only ever carries
useful data — filter as far upstream as possible. All optional; blank or absent =
default:

| Field | Default | Meaning |
|---|---|---|
| `rate` | on-change (no cap) | Max delivery frequency, e.g. `20hz`. A time throttle. |
| `threshold` | any change | Minimum change before re-sending (deadband), in the signal's own units, e.g. `2`. A value throttle. |
| `gate` + `gate_value` | always active | A condition the stream runs **only** under, written as the usual `function value` pair — `reverse active`. While false, nothing is sent at all. |

> **Note, 2026-07-08 (implemented in 1.4.8):** "default" above is what the
> **firmware library** fills into a blank field before sending — not something DASH
> supplies (see §4c amendment). DASH evaluates the controls **exactly as delivered**:
> a blank field carries its literal total meaning (no cap / any change / always); a
> present-but-unparseable field (a rate that isn't `Nhz`, a non-numeric threshold)
> is logged and treated as absent, never repaired or guessed at. The `rate` throttle
> is **leading-and-trailing** — the first value of a burst is delivered at once and
> the last value at the window's close, so a stream converges to its resting value
> within one rate-window rather than waiting on the heartbeat.

```
SUBSCRIBE|id|steering_angle|20hz                     just rate-capped
SUBSCRIBE|id|steering_angle|20hz|2                   + deadband
SUBSCRIBE|id|steering_angle|20hz||reverse|active     + gate (blank threshold)
SUBSCRIBE|id|steering_angle|20hz|2|reverse|active    the lot
SUBSCRIBE|id|headlights                              plain on-change (a "boolean")
```

- **Gate is implicit equality.** `reverse active` means "while signal `reverse`
  equals `active`." (`<` / `>` and AND/OR are deliberately deferred — add only if a
  real need appears.) The gate references a standard signal DASH already holds in
  its sourceless core, so DASH can evaluate it with no module involvement.
- **Gate-open fires immediately.** When a gate flips false→true (you select
  reverse), DASH sends the current value at once, ignoring the threshold, so the
  module isn't stale on activation. Normal throttling then resumes; gate-close just
  stops.

### Shared-bus bandwidth (a DASH-side concern)

The gate does the heavy lifting — most streams are gated off most of the time, so
aggregate load stays low. The rate cap bounds the worst case, so DASH can sum
subscribed rates and know the ceiling. If a slow shared bus can't carry it, DASH
**clamps the effective rate and lets the module degrade gracefully** (fewer
updates) — never a hard refusal. The module declares what it would *like*; DASH
delivers what the bus can *bear*. (Detailed bus management is DASH 1.4.x, not
module-facing protocol.)

On a shared bus a delivered `LISTEN` carries the **target's** id, so the
addressed module acts and the rest ignore it — the mirror of upstream messages
carrying the **sender's** id.

## 10. Buffer & line sizes

- **Firmware receive buffer:** 64 bytes is enough — the longest inbound message
  is a `LISTEN`. Exposed as a tunable `#define`; lean default, raise if wanted.
- **Protocol max line ≈ 160 bytes**, a DASH-side concern only (Android has the
  RAM). It derives from the longest line — the `HELLO` response — via the field
  caps below.

**Field caps** (longest `HELLO` line derives from these):

| Field | Cap | Notes |
|---|---|---|
| id | 12 | MAC |
| type | 9 | single type word, e.g. `ACCESSORY` (§4a) |
| name | 24 | builder text |
| description | 64 | builder text (also a UI tidiness cap) |
| version | 12 | firmware version — builder text, but **load-bearing**: DASH re-checks it every reconnect and quarantines a module whose version no longer matches its stored install contract (§6) |

---

## 11. ACCESSORY Module — Panel and Layout

> **⚠️ PROMOTED — 2026-08-06 (roadmap 1.6.x). The specification now lives in
> `module-layout.md`.**
>
> This section is **no longer the authority** on the panel format. Everything
> normative has moved to **`module-layout.md`** in the repository root, which is
> the single source of truth for layer types, targets, bindings, slots, theme
> tokens and the SVG subset. Same treatment §1–§10 received when `module-sdk.md`
> was promoted at 1.4.15: **the spec moves out, the reasoning stays here.**
>
> **Read this section for *why*. Read `module-layout.md` for *what*.**
>
> **Two things below are now superseded rather than merely provisional**, and are
> kept per the additive rule — do not implement from them:
>
> - **The canvas model — "a PNG image as the visual foundation" with overlays
>   placed on top.** Superseded by **layers and bindings**. Raster, vector and
>   text are all layer types with none privileged as the background; coordinates
>   and element names are both simply ways of naming a target. The old framing
>   assumed one opaque raster at the bottom, which quietly forced raster and
>   vector to be two separate systems with two separate specifications. It also
>   had it backwards in emphasis: of the first three real panels designed against
>   it, the PNG-foundation path was the one that needed the most hand-measuring,
>   and vector — absent from this section entirely — turned out to be the
>   pleasanter path to author.
> - **The theme token examples `@barBackground`, `@barText`, `@barAccent`.**
>   Those tokens **do not exist**. The four `bar*` tokens were retired at roadmap
>   1.5.2 and replaced by nine. See `module-layout.md` §7 for the real set.
>
> **What remains sound below**, and was carried forward: normalised coordinates,
> the `@` prefix convention for tokens, fixed aspect ratios per slot, the six
> ratios drafted at 1.6.4, and ACCESSORY subscriptions behaving exactly as a
> LISTENER's.

> **Work in progress.** The concepts below are agreed. The concrete numbers —
> final panel dimensions for each layout slot, and the full overlay vocabulary —
> are **not yet locked**. Treat this section as direction, not a frozen spec.
> This refines the layout-panel side of §8's asset model: the panel itself is a
> PNG canvas plus overlay metadata (both still shipped as `BLOCK`s); the trigger
> **icon** is unaffected by this section.

### Panel layout model — canvas, not components

The ACCESSORY panel uses a **canvas model**, not a component model. The module
author provides a **PNG image** as the visual foundation — drawn however they
like, in any design tool — and places **overlays** on top of it for interactive
and data-driven behaviour. DASH renders the PNG as the panel background and
handles the overlays. DASH never dictates what the visual design looks like —
the art is entirely the author's domain (module is king).

### Overlays

Overlays sit on top of the PNG background and provide behaviour. The
**provisional** overlay types:

| Overlay | Behaviour |
|---|---|
| **Text** | Displays a value from a named `REPORT` variable at a defined position. Font size and colour are either a literal value or a theme token reference. |
| **Touch** | An invisible tappable area that sends a named `ACTION` when pressed. Has a position and size. Optionally shows a visual state change on press. |
| **Image** | A second PNG layered on top at a defined position. Useful for state indicators that swap between images. |
| **Transform** | An image that rotates or translates based on a `REPORT` variable's value. For needles, sliders, and level indicators. |
| **Animation** | Plays a GIF (or similar animated asset), shipped as a `BLOCK` like any other image, **once, all the way through**. One-shot only — DASH and the module don't track duration, size, or looping; the command is simply "play this animation." Triggered by either a **touch overlay's control id** (fires once on press) or a **`REPORT` variable + condition** (fires once when the condition becomes true). Both trigger sources behave identically — one command, `play the animation`, differing only in what fires it. |

For a **permanent** visual change, use an Image overlay instead — that's what
it's for. An Animation overlay can also be used to *transition into* a
permanent state: play a GIF whose final frame matches an Image overlay sitting
beneath or beside it, so the animation reads as the transition rather than a
separate effect. This falls out naturally from the two overlay types coexisting
— DASH implements nothing special for it.

This overlay vocabulary is provisional and will be extended as real module
panels are designed.

### Coordinate system — normalised units

Overlay positions are expressed in **normalised units** — a fraction of the
canvas width and height, not absolute pixels. An overlay at position `0.5, 0.3`
is always in the same relative position regardless of actual panel dimensions.
DASH converts to real pixels at render time.

### Theme token references

A module author can reference DASH theme tokens instead of specifying literal
colour values. The token is prefixed with **`@`** to distinguish it from a
literal hex value — e.g. `@barBackground`, `@barText`, `@barAccent`. DASH
resolves the token at render time using the current active theme. When the user
changes their theme, all panels using token references update automatically,
with no module involvement. Literal colour values remain valid for authors who
want a specific branded look independent of the user's theme.

### Fixed aspect ratios — universality over flexibility

The module panel aspect ratio is **fixed by the spec** for each layout slot —
small, medium, large, horizontal, vertical. These ratios are defined once and
never change. Every module author designs to these fixed ratios. Every DASH
installation honours them exactly, regardless of screen size or bar height. The
panel dimensions are **independent of the system bar height** — they do not
scale with it.

This is what makes modules universal and swappable. A module designed to the
spec works on any DASH installation without modification. The author designs to
a known shape; DASH enforces that shape. Both sides honour the same contract.

**The actual ratio values for each of the six layout slots are not yet
defined** — this will be decided once real panels are being designed and the
proportions can be evaluated against real content.

> **Drafted — 2026-08-01 (roadmap 1.6.4). The six ratios now have numbers.**
> The paragraph above stands as written — this is that decision being taken,
> the proportions having been evaluated on real hardware.
>
> | | Horizontal (`h_`) | Vertical (`v_`) |
> |---|---|---|
> | **Large** | **8 × 3** | **3 × 8** |
> | **Medium** | **16 × 3** | **3 × 16** |
> | **Small** | **16 × 1** | **1 × 16** |
>
> Long edge first, thickness second. **Every vertical slot is its horizontal
> twin stood on its end**, so a builder learns three shapes rather than six, and
> DASH holds one pair of numbers per size rather than two.
>
> **How they were arrived at.** By eye on a Galaxy Tab S9 Ultra, not by
> reasoning — the large slot went 3:1 → 4×3 → 4×2 → 4×1.5 across four builds
> before it looked right, and medium and small were then set against it. Roger's
> call throughout, chosen for what he wants in his own car rather than derived
> for a general case. The fractional forms (4×1.5, 4×0.75, 4×0.25) were the
> working notation and were converted to whole numbers for publication: a module
> author should not have to picture a shape described in quarters.
>
> **What the numbers mean in practice.** A panel's share of the screen is
> *screen aspect ÷ panel aspect*. On a 16:10 screen in landscape the horizontal
> slots take 60% / 30% / 10% of the height, and the vertical ones roughly
> 22% / 11% / 4% of the width — vertical gives up the system bar's height from
> its long edge before the ratio is applied (see below). Small lands at about
> system-bar height on a wide screen, which is where the DASH-side documentation
> always informally placed it.
>
> **The long edge is the docked edge less whatever the system bar has taken from
> it.** Docked top or bottom the bar sits on the opposite horizontal edge and
> takes nothing, so the long edge is the full screen width. Docked left or right
> the bar spans the full width and eats into the vertical run, so the long edge
> is the screen height *less the bar*. A module author does not need to care —
> they draw to the ratio and DASH scales it — but it is why the two orientations
> are not simply mirror images on any given screen.
>
> **Still a draft, deliberately.** These are provisional exactly as this section
> has always been: a spec is not locked before it has been built against, and
> what has been built so far is DASH's own container, not a module drawn to
> these shapes. They lock into `module-sdk.md` at **1.6.10**, once two real
> modules have been rendered through them. Held DASH-side as
> `PanelSize.longUnits` / `thickUnits` — integers, with the aspect derived — so
> the published ratio and the arithmetic are one fact and cannot drift apart.

### ACCESSORY subscriptions

An ACCESSORY module may include `SUBSCRIBE` declarations in its install
handshake alongside its `MANIFEST` and `BLOCK` asset declarations. The
controller handles these **identically** to a LISTENER's subscriptions —
heartbeat delivery every 5 seconds, immediate delivery on change, on-activation
full state dump (§4c). The controller does not distinguish between a LISTENER
module and an ACCESSORY module that has subscriptions — it simply delivers to
all subscribers.

### For testing purposes — provisional test layout format (temporary)

> This is **not** the agreed spec — it exists only to exercise the DASH-side
> panel rendering pipeline end to end before the final format is locked. It
> does not need to be accurate to the eventual format, and will be discarded
> once the real spec lands.

A simple pipe-delimited or JSON description of a PNG background plus one or two
overlays is sufficient to prove the pipeline — for example:

```
TEST_LAYOUT|background.png
TEST_OVERLAY|text|temperature|0.5|0.3|@barText|18
TEST_OVERLAY|touch|fan_up|0.8|0.6|0.1|0.1
```

Kept deliberately separate from the agreed decisions above so it is never
mistaken for locked spec.

---

## 12. Transports — how a module reaches DASH

> *Added 2026-07-12 (roadmap 1.4.12, Bluetooth). DASH is deliberately
> transport-agnostic — a message is a message once the bytes arrive, and the
> transport is **never** part of the grammar (there is no transport field in
> `HELLO`; DASH knows the pipe from the connection, §4). Everything in §1–§11
> is identical on every pipe. But the **builder** has a few per-transport duties
> at the physical layer — how the board presents itself and connects. This
> section is that per-transport checklist. It is additive: nothing here changes
> a single message. The full reconcile and promotion of these facts is roadmap
> 1.4.15's job; this section captures what a builder needs today.*

Whichever pipe you choose, the module logic is the same: boot **SILENT**, answer
`DISCOVER` with `HELLO`, run the install handshake, and only stream once
`ACTIVATE`d (§6). What differs is purely how the board gets onto the wire.

### USB serial

The simplest pipe and the one the reference sketches default to. The board
presents as a **USB CDC serial device** (native on every ESP32, and via the
on-board bridge on the Arduino Uno R4 WiFi). **DASH is the host** — it enumerates
the bus and opens you at **115200 8N1**. You do nothing but `Serial.begin(115200)`.
Wired, so DASH treats a silent USB module as a potential **fault**, not a quiet
dormant (§6).

### WiFi (TCP)

**DASH is the server; the module is the client.** DASH listens on a fixed TCP
port — **3274** (`D-A-S-H` on a phone keypad) — and your module opens a socket to
`DASH_HOST:3274` on boot, then talks the ordinary line grammar over it. On a
dropped socket, reconnect and go back to SILENT until DASH `DISCOVER`s you again.
`DASH_HOST` is the tablet's address on the module network (fixed when the tablet
is the hotspot). Wireless, so absence is **dormant, never a fault** (§6). *(See
arduino_wifi.ino.)*

### Bluetooth Classic (SPP)

The wireless sibling of WiFi, over a Bluetooth serial link. Four things a builder
must get right:

1. **Classic SPP, not BLE — and a Classic-capable board.** Use the Serial Port
   Profile (`BluetoothSerial` on the ESP32). BLE is a different, later transport
   and does **not** apply here. Only the **original ESP32** (WROOM-32 etc.) has a
   Classic radio; the **ESP32-S3 / C3 / C6 are BLE-only** and cannot run an SPP
   module. Pick a classic ESP32 DevKitC for a Bluetooth module.

2. **The name marker — your Bluetooth name must contain `D.A.S.H`.** Classic
   Bluetooth has no friendly service-UUID advertisement like BLE, so **DASH
   identifies its modules by device name**: it dials only bonded devices whose
   name carries the token `D.A.S.H` (the Classic analogue of BLE's DASH service
   UUID). That token is the product name and is effectively unique — no phone,
   headset or dashcam carries it — so it lets DASH find its own modules and leave
   everything else you've paired untouched. Name your module `D.A.S.H-Powertrain`,
   `D.A.S.H Climate`, or anything you like *as long as it contains `D.A.S.H`*.
   Set it in `SerialBT.begin("D.A.S.H-…")`. **A module without the token in its
   name will never be found.** The handshake is still the final judge — DASH sends
   `DISCOVER` and only a real module answers `HELLO` — so the name marker is the
   cheap first filter, not the whole story.

3. **Bond once in Android settings — DASH never pairs for you.** SPP requires the
   device to be **bonded (paired)** before a connection can open. You do that once,
   the normal way, in the Android device's own Bluetooth settings. DASH then
   connects **out** to the bonded module on every sweep (like USB, DASH initiates;
   unlike WiFi, where the module dials in). Your module runs the SPP **server** —
   `SerialBT.begin(name)` and wait to be connected to.

4. **Go SILENT the instant the link drops.** A wireless link drops normally (out
   of range, powered down). When it does, reset your module to SILENT so that when
   DASH reconnects and re-`ACTIVATE`s you (it re-asserts activation every sweep,
   §6/1.4.6) you resume from a clean state rather than a stale clock. On the ESP32,
   `SerialBT.hasClient()` going false is your cue. Absence is **dormant, never a
   fault** (§6). *(See esp32_bt.ino — the Bluetooth twin of esp32.ino, identical
   but for the transport object.)*

---

## Open Items

Still to settle before the SDK is fully locked:

- **Asset sizing** — max icon size and max layout-panel size (bounds DASH's
  pre-flight check); final CRC width (CRC32 default vs CRC16 for very weak
  boards); mismatch recovery (abort whole install vs re-request one block);
  framing for VARIABLES and per-trigger icons (likely the same `BLOCK`
  mechanism). *(was Gap 7 sub-decisions)*

> **2026-10-01, roadmap 1.6.10 — where these landed at the lock.** Asset caps: none
> (`module-layout.md` §2). CRC32 stands. VARIABLES framing: the layout *is* the declaration
> (`module-layout.md` §9). Mismatch recovery: still abort-the-install — the per-block `RESEND`
> retry for USB was designed in the roadmap and **deferred** by Roger rather than built.
> Per-trigger and per-tab icons: **deferred to version 3** (Roger). The layout spec locked the
> same day, and `DashAccessory` joined the library, extracted from the draft class the three
> Gauge sketches carried.
>
> **2026-10-01, later the same day — roadmap 1.6.12.** Roger reversed the deferral: mismatch
> recovery is now **re-request one block**. A damaged block is noted, the install runs to its
> end, then DASH sends `RESEND|id|name` for each, up to five times *(three as first written, raised after testing)*. Additive — a module that
> does not know `RESEND` ignores it and the install fails as before. Normative text in
> `module-sdk.md` §8; the library answers it for the builder.
>
> **Tested the same afternoon.** 20 installs from a classic ESP32 over USB: 9 arrived damaged, all 9
> repaired. 22 from an Uno R4 WiFi over USB: none damaged. The damage is the classic ESP32's
> USB-to-serial converter chip, not USB itself — so the SDK now *recommends* a native-USB board for
> USB modules (`module-sdk.md` §12). Roger: installs are rare, once per firmware version, so an
> occasional failed install on a less compatible board is acceptable.

### Future additions (DASH-side — roadmap 1.4.x, not the module SDK)

- **Transport log / serial monitor.** Because every message is pipe-separated
  positional text, the wire is already a table (PSV). DASH can render a live,
  scrolling log with the pipes turned into aligned monospace columns: direction
  arrow (→ out / ← in), TYPE colour-coded, id, source/transport tag, timestamp,
  with filter / pause / clear and a raw↔table toggle (raw stays grep/copy-friendly).
  - **Two homes:** a **Transport Monitor** in the settings panel (the serious
    diagnostic surface), and a **live logger element/overlay** on the main
    viewscreen so you can *watch* a message go out the instant you press a panel
    control. Serves the brief's debuggability-first and openness principles.
  - **Architectural hook:** to build it the SDKable way, the **1.4.x transport
    layer must expose a read-only "tap"** — an observable message stream an
    element/overlay can subscribe to. Bake it in from the start. It doesn't dent
    module-is-king: a logger observes **the wire** (DASH's domain), never reaches
    inside a panel.

### Considered & parked

- **Module grouping** (`GROUP|groupid|id`, per-face, `groupid` = device base MAC).
  Lets DASH link several faces on one board so it can group them in the UI, fault
  them together, and offer "remove all." Sound design — per-face declaration (no
  privileged parent), globally-unique groupid (no collision). **Parked** as
  over-engineered: faces work fine fully independent, and we have no evidence yet
  of boards routinely presenting grouped faces. Revisit if a real need appears.
  An earlier variant — encoding the group into a short numeric id (3-digit subnet
  + 3-digit module) — was rejected: it throws away the MAC's global uniqueness,
  reintroduces assignment/collisions, and bakes topology into identity.

- **A separate reconnect message** (distinct from `DISCOVER`, for the §6 boot
  reconciliation sweep). Raised by Roger while building DASH 1.4.6 — the feeling
  that `DISCOVER` belongs to installation and reconnection deserves its own word.
  **Rejected** on architectural grounds: a broadcast "installed modules, report
  in" is impossible when modules persist nothing and don't know their own install
  state (§6) — the only question a module can ever answer is *who's there?*, and
  `HELLO` is that answer. The install/reconnect distinction lives in DASH's
  handling of the replies (unknown id → install candidate; installed id →
  `ACTIVATE`), not on the wire. A per-id ping variant was also rejected: N retry
  loops against possibly-absent hardware instead of one broadcast, a new message
  every firmware must implement forever, and it loses the free hot-plug
  detection the shared reply gives. *(2026-07-06)*

- **A HELLO state field** — an optional seventh field, `active` / `silent`, so
  the reconciliation sweep could send `ACTIVATE` only to modules that need it
  instead of re-asserting every sweep (a rebooted module's `HELLO` is otherwise
  indistinguishable from a healthy one's). Backward-compatible by the trailing-
  fields rule: no field ⇒ state unknown ⇒ DASH re-asserts, which is exactly the
  1.4.6 behaviour. **Parked** after measuring the re-assert cost (~1% of a
  115200 wire for ten modules, even with the future §4b activation dumps; the
  §4b heartbeat itself is ~7%) — the chatter doesn't justify touching the
  identity message every module ever built will send. Revisit if sweep bursts
  ever matter on a shared RS485 bus. *(2026-07-06)*

**Next:** begin the Arduino SDKable firmware work against this definition,
starting with the SYSTEM module on the Arduino Uno Q.

---

## Discussion Notes

### Session — 2026-06-22
First design session. Worked the protocol end to end and consolidated it into the
Module Definition above. Key arrivals:
- **Unified message structure** `TYPE|id|function|value1[|value2]`, positional,
  trailing-optional, single `|` delimiter, two forbidden characters (newline and
  `|`), simple split parsing, ignore-unknown-trailing-fields for growth.
- **Every message names its module** (id in field 2), both directions — fixing an
  inconsistency where SYSTEM messages had no id and RS485 needed a special prefix.
- **`HELLO`** replaces the old six-field discovery line; transport dropped (DASH
  infers it from the connection).
- **No `DASH:` prefix** on commands; the TYPE word carries the meaning.
- **Lifecycle = Model B** (DASH single source of truth), INSTALL ≠ ACTIVATE,
  transport-aware dormancy, acked stream control, force-uninstall safety.
- **Source-aware gatekeeper vs sourceless core** as an explicit layering.

**Superseded / removed this session:** the `:` delimiter; the Gap 3 limit-split
and escaping schemes (no longer needed once the delimiter is banned from
content); the RS485 address-prefix special case (replaced by uniform ids); the
`DASH:` command prefix; the `TRANSPORT|...`-first discovery line.

### Session — 2026-06-22 (second sitting)
Worked Gap 8 and it grew into a rework of the type model. Arrivals:
- **Three mutually-exclusive types** — `SYSTEM` / `ACCESSORY` / `RECEIVE` (§4a).
  A module is exactly **one** type; the `HELLO` `type` field is a single word,
  never a combination. **HYBRID retired.**
- **Combinations come from multiple modules, not from one module.** A board doing
  several jobs presents several modules — one per type, one id each — and to DASH
  they are fundamentally separate modules that share a board.
  - *(En route we briefly modelled these as composable flags on one module, then
    Roger corrected it: one module = one thing. Composable-flags model dropped;
    `RECEIVE` is a full type, not a flag.)*
- **The real fault line** is *language*: SYSTEM speaks DASH's known vocabulary;
  ACCESSORY speaks its own and DASH doesn't care. `RECEIVE` is the inbound side.
- **Triggers belong to ACCESSORY only**, because a trigger carries an icon and
  only an accessory ships assets. Anything that must raise an alarm is an ACCESSORY.
- **Gap 8 resolved:** a `SYSTEM` module is send-only and can't subscribe to relay;
  to receive a signal you add a separate `RECEIVE` module.
- **One board, several modules** confirmed (shared-bus-of-N, unique id per face,
  independent lifecycles).
- **Grouping** (`GROUP|groupid|id`) designed then **parked** (see Considered &
  parked above).

### Session — 2026-06-22 (third sitting)
Settled Gap 9 (relay/streams) and a related cleanup. Arrivals:
- **One subscription, one delivery.** Subscribe at install with
  `RELAY_STREAM|id|function|rate|threshold|gate|gate_value`; DASH delivers with
  plain `RELAY|id|function|value` for everything. The module never needs to know a
  value was throttled.
- **No boolean-vs-stream distinction.** Every received signal is a stream; a
  discrete on/off is just a stream with no rate cap and no threshold. So
  `RELAY_SIGNALS` is **retired** — folded into `RELAY_STREAM` with trailing fields
  left off. *(Gap 9 resolved.)*
- **Four optional controls**, all evaluated in DASH: `rate` (Hz cap), `threshold`
  (deadband, signal's own units), and `gate` + `gate_value` (run only while a
  standard signal equals a value). Gate is implicit equality; `<`/`>`/AND/OR
  deferred. Gate-open fires an immediate value; gate-close stops. Shared-bus
  overload → DASH clamps the rate and degrades gracefully, never hard-refuses.
- **Gate uses the universal `function value` pair** (`reverse active`), not a
  `reverse=active` mini-syntax — consistent with how signals appear everywhere else.
- **Comma-lists eliminated.** New §2 rule: *one value per field, no embedded
  lists* — declare several things with several one-per-line messages. This retired
  the comma-joined `RELAY_SIGNALS` **and** turned the send-side declaration into
  one `SYSTEM_SIGNAL` line per signal (was `SYSTEM_SIGNALS|id|sig1,sig2`). The
  protocol now has **no sub-delimiter anywhere** — parsing is one plain split.

### Session — 2026-06-22 (fourth sitting)
Closed a real hole and captured a spin-off idea.
- **ACCESSORY is two-way.** It had been defined send-only, but an interactive
  panel must receive its own control presses or it's just a picture. Added the
  agnostic inbound message `CONTROL|id|control|value` *(name provisional)* — the
  mirror of outbound `MODULE|id|variable|value`. Full symmetry now: standard
  `SYSTEM`↔`RELAY`, agnostic `MODULE`↔`CONTROL`.
- **Two distinct inbounds, kept apart:** `RECEIVE` takes in **standard** relays;
  `ACCESSORY` takes in **its own panel's** interactions. Different domains, so
  ACCESSORY is two-way without being a `RECEIVE`.
- **Panel contract** = display **variables** (out) + interactive **control ids**
  (in), both declared at install. Folds into the open asset/panel item.
- *(Open: final name for `CONTROL` — Roger to decide. Possible "RECEIVE" rename
  later, since two types now receive something.)*
- **Spin-off captured:** a **transport log / serial monitor** (the wire is already
  a PSV table) as a DASH-side 1.4.x addition, needing a read-only transport "tap"
  for the SDKable element/overlay version. Filed under Future additions.

### Session — 2026-06-22 (fifth sitting)
Renamed the four data-plane messages to a readable 2×2 (domain × direction,
direction from the **module's** point of view). Old → new:
- `SYSTEM` → **`SYSTEM_TX`** (module sends a standard signal)
- `RELAY` → **`SYSTEM_RX`** (DASH delivers a standard signal)
- `MODULE` → **`MODULE_TX`** (module sends its own agnostic data)
- `CONTROL` → **`MODULE_RX`** (DASH delivers a panel interaction) — settles the
  provisional `CONTROL` name.
- Cascade: the subscribe message `RELAY_STREAM` → **`STREAM`** (the word `RELAY`
  is retired). `SYSTEM_SIGNAL` (send-side declaration) unchanged.
- Evocative names outside the data plane keep their words (`HELLO`, `DISCOVER`,
  `INSTALL`, `ACTIVATE`/`DEACTIVATE`, `ACK`, `MANIFEST`, `BLOCK`, `TRIGGER`,
  `SYSTEM_SIGNAL`, `STREAM`).
- Nice consequence: types now map onto messages — SYSTEM does `SYSTEM_TX`, RECEIVE
  does `SYSTEM_RX`, ACCESSORY does `MODULE_TX` + `MODULE_RX`.

### Session — 2026-06-25 (sixth sitting)
Final pass on the message and type names — chasing human-readable words that also
tell the truth about each channel's shape. Old → new:
- `SYSTEM_TX` → **`BROADCAST`** (module sends a standard signal — to all)
- `SYSTEM_RX` → **`LISTEN`** (DASH delivers a standard signal you subscribed to)
- `MODULE_TX` → **`REPORT`** (module sends its own data to its panel — to one place)
- `MODULE_RX` → **`ACTION`** (user operated a panel control) — settles the
  long-provisional `CONTROL`/`MODULE_RX` name.
- Type **`RECEIVE` → `LISTENER`** (it does the `LISTEN` message).
- Reasoning: the **standard** channel is pub/sub (one-to-many) → role verbs
  `BROADCAST`/`LISTEN`; the **agnostic** channel is a private one-to-one wire →
  the directed pair `REPORT`/`ACTION`. Naming style mirrors channel topology, and
  all four are distinct words (best at-a-glance scannability in the serial monitor).
  Rejected en route: `MOD_DASH/DASH_MOD` (swapped-token blur), `COMMAND` (overloads
  "lifecycle command", a touch cold). `REPORT`/`ACTION` reads as the dashboard
  metaphor: the panel reports, the operator acts.
- Subscribe message `STREAM` → **`SUBSCRIBE`** — completes the pub/sub triad
  (`BROADCAST` publish / `SUBSCRIBE` / `LISTEN` receive), names the action not the
  byproduct, and reads cleanly for booleans too (`SUBSCRIBE|id|headlights`). The
  "everything you receive is a stream" idea stays as a concept; only the message
  word changed. `SYSTEM_SIGNAL` and the lifecycle/handshake words unchanged.
- Acknowledgement `ACK` → **`ROGER`** — it was the only abbreviation left in an
  otherwise plain-English set. "Roger" is a real radio acknowledgement (so it reads
  intuitively to any community builder) *and* a cheeky nod (Roger's name, his dad's
  suggestion). The `which` field still says what was actioned (`ROGER|id|activate`).

**For eventual promotion to transport.md / a module-rules doc (on instruction):**
the unified message structure and delimiter, `HELLO`, the three module types
(`SYSTEM`/`ACCESSORY`/`LISTENER`, one per module), the ACTIVATE/DEACTIVATE
vocabulary, the gatekeeper-vs-core layering, the subscribe/deliver model
(`SUBSCRIBE` subscribe, `LISTEN` deliver), and the agnostic panel round-trip
(`REPORT` out / `ACTION` in).

### Session — 2026-06-30
Began the first reference module — the **X-Type steering wheel** SYSTEM module —
on real hardware, and started writing the lifecycle handshake firmware. This is
the canonical SYSTEM build the plan called for: full core loop, no payload.
- **Hardware settled: Arduino Uno R4 WiFi** (not the Uno Q originally pencilled
  in). A normal single-MCU Arduino — the RA4M1 runs the sketch; the on-board
  ESP32-S3-MINI-1 is the WiFi/BT radio *and* the USB-serial bridge (USB-C →
  ESP32-S3 → RA4M1). So `Serial` enumerates straight to the host (DASH) as a CDC
  port — it "acts like an Uno R3," and none of the Uno Q's dual-brain/bridge
  complications apply. The protocol logic lives on the MCU in plain Arduino C++
  (most SDKable: it's what a community builder on a bare MCU copies), behind a
  one-line `#define DASH_SERIAL Serial` so the same code ports by changing only
  the transport object.
- **id:** the R4 WiFi has a real MAC (the ESP32-S3's, read via `WiFiS3`), but the
  first sketch uses an *assigned* id — the §3 "no-MAC" fallback — to prove the
  handshake with zero WiFi dependency. Real-MAC reading is a deliberate later
  step.
- **`INSTALL_END` now carries the id → `INSTALL_END|id`.** The §7 flow example
  had written it bare, which contradicted the §2 invariant that every message
  names its module (only `DISCOVER` is exempt). Resolved in favour of §2;
  cheat-sheet and §7 example updated to match. *(Roger's decision.)*
- **DASH-side reality noted:** the transport layer is roadmap 1.4.x and not yet
  built, so near-term the firmware is proven on the bench (serial monitor / a
  mock-DASH transcript). Real in-app integration lands with 1.4.x.

### Session — 2026-07-02
**State reporting made explicit for SYSTEM modules** (added as §4b). Two
behaviours agreed as protocol requirements:
- **On-activation full dump** — after `ACTIVATE|id`, the module immediately
  `BROADCAST`s the current value of every signal it manages, changed or not, so
  the state store is fully populated the moment the module comes online (no gap
  for a state that changed before the module booted).
- **Periodic heartbeat** — the module re-reports all current signal values on a
  regular interval (recommended 5 s), independent of whether anything changed.
- **Change detection lives in the controller, not the module.** DASH compares
  each incoming value to its state store, updates and fires an event only on a
  difference, and ignores a match. The module stays dumb and stateless; the
  firmware library provides both behaviours so the builder writes neither.
- **Vocabulary note:** the incoming instruction referred to a `SYSTEM_TX`
  message; that name was retired to `BROADCAST` in the 2026-06-25 sitting, so the
  state reports are ordinary `BROADCAST|id|function|value` lines. Recorded here so
  the older term isn't reintroduced. *(Roger's notes, from a design conversation.)*

### Session — 2026-07-02 (second sitting)
**Controller architecture agreed — the three-behaviour model** (added as §5a).
The controller acts on every incoming `BROADCAST` in one of three ways, chosen by
the signal's type in `system_commands.md`, never by the module:
- **Store and event** — booleans/multi-state: update the store *and* fire an event.
- **Store only** — continuous signals: update the store silently, fire nothing
  (firing on high-frequency values would flood listeners).
- **Event only** — momentary controls: fire and forget, store nothing.
Decisions banked:
- **The wire is always event-driven.** The three-way distinction is entirely
  internal to DASH and is *never* expressed on the wire; a builder never declares
  a behaviour. Keeps modules dumb and `system_commands.md` authoritative.
- **Event-only signals carry no value field** — `BROADCAST|id|media_next`. The
  absence of a value *is* the signal; the controller reads it via the cheat
  sheet's "trailing fields optional" rule. Stateful signals always carry a value.
- **The state store** holds the current value of every stateful signal since DASH
  started, populated by the on-activation dump and kept fresh by the heartbeat
  (§4b); empty after a DASH restart until modules report in. Event-only controls
  never enter it.
- **§4b tightened:** the dump and heartbeat apply to stateful signals only —
  event-only controls have no state to dump or heartbeat.
- **Vocabulary note (again):** the incoming instruction wrote `SYSTEM_TX`
  throughout; rendered as `BROADCAST` per the 2026-06-25 rename.
- **`system_commands.md`** named the authoritative signal vocabulary & behaviour
  reference. No formal references list exists in this doc, so the citation lives
  in §5a rather than in a list of its own.

### Session — 2026-07-03
**LISTENER delivery model agreed** (added as §4c, placed after the SYSTEM
module's §4b). Settles how the controller delivers subscribed signals downward,
mirroring the SYSTEM module's upward reporting model:
- **Heartbeat every 5 seconds** — the controller pushes the current value of
  every subscribed stateful signal to the LISTENER on a fixed interval,
  changed or not. The LISTENER compares to its last known value and acts only
  on a difference.
- **Immediate event delivery** — a subscribed signal that changes is pushed to
  the LISTENER the instant it changes, outside the heartbeat cycle. No waiting
  up to 5 seconds to find out.
- **On-activation full state dump** — the moment `ACTIVATE|id` is sent, the
  controller immediately delivers the current value of every subscribed
  stateful signal, before the first heartbeat, so the LISTENER has full current
  state from the moment it comes online.
- **Event-only signals delivered on fire, with no heartbeat and no
  on-activation delivery** — momentary controls (`media_next`,
  `button_home_pressed`…) have no state, so a `LISTEN` with no value field is
  sent only the instant the event fires.
- **Change detection lives in the firmware library**, not the builder's code —
  the library compares each incoming `LISTEN` to the last known value and calls
  the builder's callback only on a difference, exactly mirroring how the SYSTEM
  module library handles the dump/heartbeat on the send side (§4b).
- **Deliberate symmetry with the SYSTEM module**, noted explicitly: SYSTEM
  reports upward on a heartbeat + immediately on change; the controller
  delivers downward to LISTENER on the same rhythm — heartbeat + immediately on
  change — with the same 5 second interval both ways.

### Session — 2026-07-03 (second sitting)
**ACCESSORY panel and layout model agreed** (added as §11, marked work in
progress). First real design pass on what an ACCESSORY panel actually is,
beyond "ships assets as `BLOCK`s":
- **Canvas model, not components.** The author supplies a PNG as the visual
  foundation, drawn in any tool, and places overlays on top for behaviour. DASH
  renders the PNG and handles the overlays; it never dictates the visual design
  — a direct expression of module-is-king applied to the panel itself.
- **Normalised coordinate system.** Overlay positions are a fraction of canvas
  width/height (e.g. `0.5, 0.3`), not absolute pixels, so a layout holds its
  relative position regardless of actual rendered panel size. DASH converts to
  pixels at render time.
- **Theme token references via `@` prefix.** An author can write `@barText`
  instead of a literal colour; DASH resolves it against the active theme at
  render time, so panels re-theme automatically with no module involvement.
  Literal hex values remain valid for authors who want a look independent of
  the user's theme.
- **Fixed aspect ratios — universality over flexibility.** Each layout slot
  (small/medium/large/horizontal/vertical) has one fixed ratio, defined once,
  independent of screen size or bar height. Reasoning banked explicitly: this
  is what makes a module *universal and swappable* — designed to a known shape
  once, that shape honoured identically on every installation. **The actual
  ratio numbers are deliberately deferred** until real panels are being
  designed and can be judged against real content — recorded as an open item
  inside §11, not a general Open Items entry, since it's specific to this
  section.
- **ACCESSORY subscriptions mirror LISTENER exactly.** An ACCESSORY may send
  `SUBSCRIBE` lines alongside its `MANIFEST`/`BLOCK` declarations; the
  controller applies the identical §4c delivery model (5s heartbeat, immediate
  on-change, on-activation dump) and does not care whether the subscriber is a
  LISTENER or an ACCESSORY — it just delivers to subscribers. This closes the
  gap where an interactive panel wanting standard signals (e.g. showing
  headlight state) would otherwise have needed a bolted-on second module.
- **Provisional test layout format**, deliberately unspecified and marked
  temporary — enough to prove the DASH-side rendering pipeline (PNG background
  + one or two overlays) works end to end without waiting on the locked spec.
  Documented inside §11 clearly separated from the agreed decisions so it's
  never mistaken for real spec.
- **Overlay vocabulary (text/touch/image/transform) marked provisional** —
  expected to grow once real panels expose needs not yet anticipated.

### Session — 2026-07-03 (third sitting)
**Fifth overlay type added: Animation** (§11 overlay table), refined over a
short back-and-forth:
- Started as a "Highlight" idea (a bright emphasis effect DASH would render on
  demand — glow/pulse/flash). Rejected: DASH generating the effect would have
  meant DASH authoring part of the visual design, which cuts against the
  canvas model's whole premise (module is king over the art).
- **Landed on: an author-supplied GIF, played once, all the way through.**
  Shipped as a `BLOCK` like any other image asset. Renamed Highlight →
  **Animation** to match. DASH and the module track nothing about it — no
  duration, no size, no looping — the command is simply "play this animation."
- **Two trigger sources, one command.** A touch overlay's control id (fires on
  press) or a `REPORT` variable + condition (fires when the condition becomes
  true) both just say "play the animation" — explicitly **one-shot only** in
  both cases. An earlier version of the condition case considered looping/
  holding while the condition stayed true; **rejected** — sustained/permanent
  states are the Image overlay's job, not Animation's. Keeps the two overlay
  types cleanly separated by purpose (temporary flourish vs. persistent state).
  Reasoning: *"for permanent changes the png overlay feature will be used."*
- **Chaining noted as a technique, not a feature.** A GIF whose final frame
  matches an adjacent Image overlay reads as an animated transition into a new
  permanent state — this falls out of the two overlay types coexisting; DASH
  implements nothing special for it.

### Session — 2026-07-06
DASH 1.4.6 (startup reconciliation) built and bench-verified — the first version
where the §6 lifecycle runs end to end on the real wire. No changes to the
message vocabulary; the session's decisions are about how DASH *uses* it:
- **The §6 boot reconciliation and low-rate re-sweep are now real.** One
  persistent `DISCOVER` sweep (5 s for the first minute — the Uno Q slow-boot
  case — then 30 s forever) serves discovery-for-install and reconnection at
  once. En route Roger proposed a dedicated reconnect message; rejected and
  recorded in Considered & parked — the module can't know which broadcast means
  it, so the distinction belongs to DASH's reply-handling, not the wire.
- **`ACTIVATE` is re-asserted on every sweep answer**, because both module
  states answer `DISCOVER` identically and a fresh `ROGER` is the only proof a
  module is genuinely active *now* — this is what heals a crank brown-out reboot
  within one sweep. Roger challenged the redundancy; the traffic was measured
  (~1% of a 115200 wire for ten modules) and the re-assert kept. The HELLO
  state-field alternative is recorded in Considered & parked.
- **The §6 SYNC button absorbed the DISCOVER button** on Module Management —
  with the sweep persistent, one manual "check now" serves install and
  reconnection alike.
- **The §6 uninstall wording was interpreted** as: delete the record
  immediately (forgetting *is* the uninstall), retry `DEACTIVATE` behind it,
  and raise the disconnect-or-power-cycle warning only if no `ROGER` ever
  comes — a warning after the fact rather than a blocking FORCE UNINSTALL step.
- **The reference sketches needed nothing.** All three had carried the
  SILENT → ACTIVE machine, `ROGER`, and the data-only-while-active rule since
  1.4.4 — the SDK-conformance bet paying off exactly as intended.
