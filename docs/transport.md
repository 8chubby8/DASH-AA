# DASH — Transport & Module Protocol

## Purpose of This Document

This document serves two audiences.

**For DASH developers:** This is the definitive reference for how messages are sent, received, and handled within the system. No DASH feature that involves external communication should be built outside the boundaries defined here.

**For module builders:** This document tells you everything you need to know to build a module that works with DASH. If your module follows this protocol, DASH will understand it regardless of how it is connected.

---

## ⚠️ Protocol Grammar Superseded — See `arduino/arduino.md`

*Status note added 2026-07-01.*

**The message grammar and handshake described in this document have been superseded.** Across several design sessions between 2026-06-22 and 2026-06-30 the DASH Module SDK was reworked in detail in the working record **`arduino/arduino.md`**, which is now the authoritative wire format. Both the steering-wheel reference firmware (`x-type_steeringwheel/`) and the DASH transport layer being built in roadmap 1.4.x target `arduino.md` — **not** the older format still written below.

The old grammar is **left in place on purpose.** It is our working — it records how the protocol reached its current shape, and why. Do not implement from it. When you need the wire format, go to `arduino/arduino.md`.

**What changed (old → new):**

| This document (superseded) | `arduino/arduino.md` (authoritative) |
|---|---|
| `DASH:DISCOVER` | `DISCOVER` |
| six-field discovery line `TRANSPORT\|ID\|NAME\|TYPE\|DESC\|VERSION` | `HELLO\|id\|type\|name\|description\|version` — transport field dropped (DASH knows it from the connection); field order changed |
| `SYSTEM:function:value` | `BROADCAST\|id\|function\|value` |
| `MODULE:moduleID:variable:value` | `REPORT\|id\|variable\|value` |
| `MODULE:moduleID:TRIGGER:name` | `TRIGGER\|id\|name` |
| `DASH:INSTALL:moduleID` | `INSTALL\|id` |
| `SYSTEM_SIGNALS:sig1,sig2` (comma list) | one `SYSTEM_SIGNAL\|id\|function` line per signal — no comma lists anywhere |
| `RELAY_SIGNALS:…` + `DASH:RELAY:SYSTEM:function:value` | `SUBSCRIBE\|id\|function\|…` at install + `LISTEN\|id\|function\|value` at runtime |
| module types `SYSTEM / ACCESSORY / HYBRID` | `SYSTEM / ACCESSORY / LISTENER` — **HYBRID retired** (several jobs = several independent modules) |
| colon `:` delimiter | pipe `\|` delimiter — positional fields, parsed by one plain split |

**Also changed conceptually:** every message now carries the module `id` in field 2 (only `DISCOVER` is exempt), which retired the RS485 address-prefix special case; acknowledgements are `ROGER`; and a board doing several jobs presents several independent modules rather than one HYBRID.

**What in this document still stands:** the Core Philosophy (transport agnosticism), the Supported Transports catalogue, the CAN Patch Bay, Startup Reconciliation *as a concept* (its message names are superseded), and the Key Principles Summary. Only the concrete **message grammar, discovery response, module-type list, installation handshake, and system-message relay format** below are superseded.

When the SDK is finally locked, the `arduino.md` rules will be promoted into this document on Roger's express instruction, and this note removed.

> **Update — 2026-08-06.** Two documents have since been promoted out of the working record, and they,
> not `arduino.md`, are now what you should read:
>
> - **`module-sdk.md`** — the **locked** module SDK (roadmap 1.4.15). The message grammar, module types,
>   lifecycle, handshake and asset transfer. This is the authority for everything the table above marks
>   superseded.
> - **`module-layout.md`** — the **panel specification** (roadmap 1.6.x, provisional until 1.6.10).
>   How a module describes what DASH should draw. See the further note in the Installation Handshake
>   section below.
>
> `arduino/arduino.md` continues as the working record — the reasoning and the history behind both.

---

## Core Philosophy

DASH does not care how a message arrives. It only cares what the message says.

A message sent over USB serial, WiFi, Bluetooth, RS485, UART, CAN, or Ethernet is treated identically once it reaches DASH. The transport is simply the pipe. The message is what matters.

This means a module builder chooses whatever connection method suits their installation. They write the same message format regardless. DASH handles the rest.

---

## Supported Transports

Each transport has its own physical characteristics and discovery mechanism. The message content is always identical across all transports.

---

### USB Serial

**Type:** Hot-plug

**How it works:** Each USB serial device connected to DASH gets its own dedicated serial port. DASH sends the discovery broadcast down every open serial port. Each module responds on its own port. This is the simplest and most universally supported transport.

**Module requirements:** The module must present as a USB CDC serial device (standard on all ESP32 variants). No special drivers required on the Android side.

**Use case:** Development, bench testing, and hot-plug modules that are not permanently wired.

---

### WiFi (TCP)

**Type:** Hot-plug

**How it works:** DASH runs a TCP server on a fixed known port. WiFi modules connect to that server when they power up. DASH knows a module is present because the module initiates the connection. Discovery broadcasts are sent to all currently connected TCP clients.

**Concurrent internet use:** The DASH TCP server is simply an application running on Android's network stack. It does not affect or restrict internet access in any way. The same WiFi connection simultaneously carries internet traffic, module communications, and any other Android network activity. There is no conflict and no special configuration required.

**Module requirements:** The module must know the DASH device IP address or hostname and connect to the DASH TCP port on startup. The DASH port number will be defined during development and documented here when confirmed.

**Use case:** Modules in locations where wiring is impractical, or wireless retrofit installs.

---

### Bluetooth Classic (SPP)

**Type:** Hot-plug

**How it works:** The module pairs with DASH once via standard Bluetooth pairing. After pairing, the connection behaves identically to a USB serial connection from DASH's perspective. DASH sends the discovery broadcast to all paired and connected SPP devices.

**Module requirements:** The module must implement the Bluetooth Serial Port Profile (SPP). Supported natively on all ESP32 variants.

**Use case:** Wireless modules where BLE is insufficient, or where a serial-style connection is preferred.

---

### Bluetooth Low Energy (BLE)

**Type:** Hot-plug

**How it works:** The module advertises itself as a BLE peripheral with a DASH-specific service UUID. DASH scans for this UUID during discovery. Only devices advertising the correct DASH service UUID are recognised as modules. This prevents non-module Bluetooth devices such as phones from appearing in the module list.

**Module requirements:** The module must advertise the DASH BLE service UUID (to be defined during development). Supported on all ESP32 variants with BLE capability.

**Note:** A phone connected via Bluetooth for HFP calls will never appear as a module because it does not advertise the DASH service UUID.

**Use case:** Low power wireless modules, sensors, or devices where Classic BT is not appropriate.

---

### UART

**Type:** Permanent

**How it works:** A direct serial connection between a permanently wired module and the board's UART pins. DASH sends the discovery broadcast on each configured UART port at startup. UART modules are expected to be present at boot — if a UART module does not respond, DASH treats this as a fault condition rather than a normal disconnect.

**Module requirements:** The module must be wired to the board's UART TX/RX pins and configured to the correct baud rate (to be confirmed during development).

**Use case:** Permanently installed modules in a vehicle where a clean wired connection is preferred over USB.

---

### RS485

**Type:** Permanent

**How it works:** RS485 is a shared bus — multiple modules can share a single two-wire cable run. DASH sends a broadcast on the RS485 bus and each module responds in turn with a small randomised delay to avoid simultaneous responses. DASH collects all responses within a defined window.

**Module requirements:** The module must be connected to the RS485 bus with correct termination. Each module on the bus must have its own unique module ID to avoid conflicts.

**Use case:** Multiple permanently installed modules sharing a single cable run, particularly useful for door modules, window controls, or any install with several wired modules spread across the vehicle.

---

### CAN Bus

**Type:** Permanent (passive)

**Important distinction:** CAN bus in DASH serves two separate purposes.

**Purpose 1 — System data sniffing:** DASH passively reads the vehicle's own CAN bus to extract system-level data such as speed, door states, and steering angle. This is read-only. DASH never writes to the vehicle CAN bus under any circumstances.

**Purpose 2 — Module transport:** A user-built module that communicates via CAN. DASH reserves a specific CAN message ID for module discovery broadcasts and a designated reply ID for module responses.

These two uses are handled by separate internal services in DASH and do not interfere with each other.

**Use case:** Native integration with vehicle data, or permanently wired modules on a board with native CAN support.

---

### Ethernet

**Type:** Permanent or hot-plug depending on installation

**How it works:** Identical to WiFi TCP. DASH runs a TCP server. Ethernet modules connect to it on the known port. Suitable for high-bandwidth modules or fixed network installations.

**Use case:** Advanced installs, RV or van builds with a local network, or any scenario where a wired network connection is more appropriate than WiFi.

---

## Message Categories

Every message sent to DASH belongs to one of two categories. The first word of every message declares which category it belongs to.

---

### SYSTEM Messages

System messages address functions that are hard-baked into DASH. These are standard vehicle behaviours that DASH already knows how to respond to — things like headlights, reverse gear, door states, and interior lighting.

DASH ships with these behaviours built in. Whether a user implements them or not is entirely their choice. If no source ever sends a headlights message, the headlights function simply sits dormant. If multiple sources send the same message simultaneously, DASH acts on receipt and does not track or care about the origin.

**System messages are stateless and sourceless.** DASH does not know or care whether a system message came from an ESP32, a CAN patch bay translation, a WiFi device, or multiple sources at once. The message is what matters, not who sent it.

**Format:**
```
SYSTEM:function:value
```

**Examples:**
```
SYSTEM:headlights:on
SYSTEM:headlights:off
SYSTEM:reverse:active
SYSTEM:reverse:inactive
SYSTEM:door_driver:open
SYSTEM:door_driver:closed
SYSTEM:indicator_left:on
SYSTEM:indicator_right:on
SYSTEM:handbrake:on
SYSTEM:handbrake:off
```

The full list of supported SYSTEM functions will be maintained as a separate appendix within this document as they are defined. This list is the authoritative reference for module builders implementing system-level modules.

**Multiple sources:** Any number of devices may send the same system message. DASH treats each message independently on receipt. This is a feature — it provides redundancy and allows, for example, both a CAN patch bay and a directly wired ESP32 to report the same vehicle state.

**Output destinations:** When a system message arrives, DASH may act on it in up to three ways simultaneously. First, any hard-baked DASH behaviour associated with that message fires — headlights dimming the UI, reverse triggering the camera view, and so on. Second, any standard signal slots mapped to that message are updated — speed, steering angle, door state, and so on. Third, any overlay the user has configured to trigger on that message fires — a visual notification layer appearing above the interface. All three can occur from a single incoming message. Which of the three apply depends entirely on how the user has configured their system.

---

### MODULE Messages

Module messages address functionality that DASH has no prior knowledge of. These are user-defined features — climate control, tyre pressure monitoring, leisure battery levels, ride height, or anything else the user chooses to build. The module itself defines what it does and how it should be displayed.

Module messages are routed to the module panel and displayed using the layout the module provided during installation. DASH renders the module's UI without needing to understand what the data means.

**Format:**
```
MODULE:moduleID:variable:value
```

**Examples:**
```
MODULE:A4CF12:temperature:21.5
MODULE:A4CF12:fan_speed:3
MODULE:7B3D44:tyre_fl:32.1
MODULE:7B3D44:tyre_fr:31.8
```

The module ID in every message matches the hardware ID the module declared during discovery. This is how DASH routes the message to the correct module panel when multiple modules are active simultaneously.

**Module triggers** are the one exception to module messages staying within the module panel. A trigger sent by a module surfaces in the system bar alert area rather than within the module panel itself. This is for events that require the user's attention regardless of which panel is currently visible.

Each trigger must be defined with an icon. The icon is provided as part of the module's installation data — each named trigger the module may fire should have a corresponding SVG icon registered against it. When a trigger fires, DASH displays that icon in the alert area of the system bar.

If a trigger fires and no icon has been defined for it, DASH will display a generic alert icon as a fallback. The alert will still appear and function correctly — the fallback simply ensures the alert area never fails to show something meaningful even if the module builder has not supplied a custom icon.

**Format:**
```
MODULE:moduleID:TRIGGER:triggerName
```

**Example:**
```
MODULE:A4CF12:TRIGGER:filter_warning
```

**Icon definition** (provided during installation, one entry per possible trigger):
```
TRIGGER_ICON:triggerName:{SVG icon data}
```

**Output destinations:** When a module trigger fires, DASH acts on it in up to two ways simultaneously. First, the trigger icon is displayed in the alert area of the system bar. Second, any overlay the user has configured to fire on that trigger appears above the interface. Both can occur from a single trigger. Which apply depends on the user's configuration.

---

## Discovery Protocol

When a user initiates a module search, DASH simultaneously broadcasts a discovery message across all active transports. Each transport uses its own broadcast mechanism appropriate to its nature, but the content of the broadcast and the expected response format are identical across all transports.

---

### Discovery Broadcast

DASH sends the following broadcast:

```
DASH:DISCOVER
```

This message includes the DASH identifier so that only devices specifically programmed as DASH modules respond. Non-module Bluetooth devices, phones, and other connected equipment will not respond because they have no knowledge of this protocol.

---

### Discovery Response

Any module that receives the discovery broadcast responds with exactly six fields in a defined order:

```
TRANSPORT | MODULE_ID | NAME | TYPE | DESCRIPTION | VERSION
```

**Field definitions:**

| Field | Description |
|---|---|
| TRANSPORT | The transport this module is connected on: USB, WIFI, BT_SPP, BT_BLE, UART, RS485, CAN, ETH |
| MODULE_ID | The module's unique hardware identifier — see note below |
| NAME | A short human-readable name for the module |
| TYPE | SYSTEM, ACCESSORY, or HYBRID (see Module Types below) |
| DESCRIPTION | A brief plain-language description of what the module does |
| VERSION | The module firmware version |

**Module ID — builder responsibility:**
The module ID is defined by the module builder and programmed into the module firmware. DASH does not assign or validate IDs. The builder is entirely responsible for ensuring their module ID does not conflict with any other module in their system. If two modules share the same ID, routing conflicts and undefined behaviour will result.

The simplest and most reliable way to guarantee a unique ID is to use the ESP32's built-in MAC address, which is factory-assigned and globally unique. This requires no manual management and eliminates any risk of conflict even when a user has many modules installed. Module builders are strongly encouraged to use the MAC address as their module ID.

**Example responses:**
```
WIFI | A4CF12 | Climate Control | ACCESSORY | Dual zone cabin temperature and fan control | v1.2
USB | 7B3D44 | Steering Controls | SYSTEM | Media and voice steering wheel button inputs | v0.9
RS485 | C8B021 | Door Controller | HYBRID | Door lock control and open/close status reporting | v1.0
```

---

### Module Types

**SYSTEM**
The module sends system-level messages only. It has no UI of its own because DASH already knows how to handle the signals it sends. During installation, no layout data or icon is requested or expected.

Examples: A steering wheel control module, an ignition sensor, a headlight detector, a reverse light monitor.

**ACCESSORY**
The module has its own UI that DASH renders in the module panel. During installation, DASH requests and receives layout data, variable definitions, and an icon from the module.

Examples: Climate control, tyre pressure monitor, leisure battery display, ride height controller.

**HYBRID**
The module both sends system-level messages and has its own UI. During installation, DASH requests and receives the full data set — system signal definitions and layout data.

*Note: HYBRID is defined here as a future capability. Full implementation details will be added when this type is developed.*

---

## Installation Handshake

Once a user selects a discovered module and confirms installation, the following sequence occurs.

**Step 1 — Install request**
DASH sends a targeted install request to the specific module ID:
```
DASH:INSTALL:moduleID
```

**Step 2 — Module response**

The module responds with data appropriate to its declared type.

*For SYSTEM modules:*
```
SYSTEM_SIGNALS:signal1,signal2,signal3
```
The module declares which system signals it will send. DASH saves this list against the module ID.

*For ACCESSORY modules:*

> **⚠️ Panel & layout superseded — see `module-layout.md`.** *(Note added 2026-08-06, roadmap 1.6.x.)*
>
> Everything in this ACCESSORY subsection — the slot names, the size definitions, the display-mode
> definitions, the fallback behaviour, and the `ICON:` / `VARIABLES:` / `TRIGGER_ICON:` declarations —
> is **superseded by `module-layout.md`**, which is now the authoritative panel specification. It is
> left in place per the additive rule: it records how the layout model reached its current shape.
> **Do not implement from it.**
>
> **What changed:**
>
> | This document (superseded) | `module-layout.md` (authoritative) |
> |---|---|
> | `_light` / `_dark` slot suffixes | **`_day` / `_night`** — the switch is Ambient, genuinely day and night in a vehicle, not a theme's lightness |
> | small / medium / large as **1× / 2× / 4× the system bar height** | **fixed ratios** — large 8×3, medium 16×3, small 16×1, each vertical slot its horizontal twin stood on its end. A panel cannot be tied to a measurement the user can move. |
> | `ICON:` / `VARIABLES:` / `TRIGGER_ICON:` colon declarations | `MANIFEST` + `BLOCK` (`module-sdk.md` §8) |
> | "PNG background plus overlays" | **layers and bindings** — raster, vector and text are all layer types; coordinates and element names are both ways of naming a target |
>
> **Still standing from below:** that every slot is optional; that a module must define at least one
> slot to be a valid ACCESSORY; and that a missing night variant falls back to the day one.
>
> **One item is deprecated outright** *(ruled by Roger, 2026-08-06)*: the fallback sentence below says
> DASH *"scales the nearest available size up to fill the space."* **It no longer applies.** DASH never
> scales a layout into a shape it was not drawn for — stretching an 8×3 design into a 16×1 slot
> misrepresents the author's work, and DASH forming an opinion about how someone's artwork should be
> reshaped is exactly what the Module Mantra forbids. A panel is drawn as authored or not drawn at all.
> See `module-layout.md` §6.

The module provides layout data for up to twelve layout slots covering all combinations of size, orientation, and display mode. Each slot is optional — the module only needs to provide the slots it supports. DASH falls back gracefully when a requested slot is not defined.

**Layout slots:**
```
h_small_light    h_small_dark
h_medium_light   h_medium_dark
h_large_light    h_large_dark
v_small_light    v_small_dark
v_medium_light   v_medium_dark
v_large_light    v_large_dark
```

Size definitions:
- **small** — 1× the system bar height
- **medium** — 2× the system bar height
- **large** — 4× the system bar height

Orientation definitions:
- **h** — horizontal, suited to top or bottom edge docking
- **v** — vertical, suited to left or right edge docking

Display mode definitions:
- **light** — light mode layout
- **dark** — dark mode layout

**Fallback behaviour:** If DASH requests a dark variant and the module has not defined one, DASH renders the light variant instead. If neither variant is defined for a requested size, DASH scales the nearest available size up to fill the space. A module must define at least one layout slot to be considered a valid ACCESSORY module.

The module also provides its icon, variable definitions, and optionally declares system messages it wishes to receive (see System Message Relay below):
```
ICON:{SVG icon data}
VARIABLES:{variable definitions}
RELAY_SIGNALS:signal1,signal2,signal3
TRIGGER_ICON:triggerName:{SVG icon data}
```

*For HYBRID modules:*
Both SYSTEM and ACCESSORY data, in sequence.

**Step 3 — Save to disk**
DASH saves the complete module record to disk. The record includes all six discovery fields, all provided layout slots, icon data, variable definitions, relay signal subscriptions, and trigger icon definitions. From this point the module is a known installed module.

---

## System Message Relay

During installation, an ACCESSORY or HYBRID module may declare a list of system messages it wishes to receive. DASH stores this subscription list against the module ID.

Whenever DASH receives or internally generates a system message that matches a module's subscription list, DASH relays that message directly to the module over its registered transport.

**Declaration format (during installation):**
```
RELAY_SIGNALS:headlights,ignition,reverse
```

**Relay message format (sent by DASH to the module at runtime):**
```
DASH:RELAY:SYSTEM:function:value
```

**Example:**
```
DASH:RELAY:SYSTEM:headlights:on
DASH:RELAY:SYSTEM:ignition:off
```

**Purpose:** This allows modules to respond intelligently to vehicle state without needing their own sensors for that state. A climate module might subscribe to headlights to dim its display when the car headlights come on. A door controller might subscribe to ignition to trigger automatic locking. A tyre pressure module might subscribe to vehicle speed to adjust its polling frequency.

**The module remains king.** DASH does not know or care why a module has subscribed to a signal. It simply delivers the message. What the module does with it is entirely the module's decision. DASH does not alter the module panel as a result of a relay — the module chooses whether to switch layouts, update a value, trigger an action, or ignore the message entirely.

**Dark mode relay:** When DASH switches between light and dark mode — whether triggered by the user, a headlights system message, time of day, or any other source — it internally updates its current display mode and requests the appropriate layout slot from each active module. If the module has defined a dark layout variant, DASH renders it. If not, it falls back to the light variant. No relay message is needed for this — it is handled automatically by the layout slot system.

---

## Startup Reconciliation

On every boot, DASH reads the installed module list from disk and verifies which modules are present.

**Process:**
1. DASH broadcasts `DASH:DISCOVER` across all active transports
2. Responding module IDs are compared against the installed list
3. Modules that respond are marked **Active** and their UI is loaded
4. Modules that do not respond are marked **Dormant**

**Dormant modules** remain in the installed database. Their configuration is preserved. They are not shown in the active module panel but are not deleted. When a dormant module reconnects — on next boot or dynamically if DASH detects it mid-session — it returns to Active status automatically.

**Permanent vs wireless transports:** DASH handles dormant state differently based on transport type. A permanently wired module (UART, RS485, CAN) that fails to respond at startup is treated as a potential fault — the system bar may indicate a missing expected module. A wireless module (WiFi, Bluetooth) that fails to respond is treated as out of range or powered off — no fault indication, just dormant status.

---

## CAN Patch Bay

When DASH is actively sniffing the vehicle CAN bus, raw CAN frames arrive continuously. These frames do not speak DASH protocol. A translation layer converts them into DASH system messages.

The CAN Patch Bay is the user-configurable mapping system within DASH settings that connects identified CAN signals to DASH system calls.

**How it works:**

The left side of the patch bay shows CAN signals the user has identified using the DASH CAN Learning Tool — signals they have named and confirmed, such as "headlight feed" or "reverse gear signal".

The right side shows the published list of DASH system calls.

The user draws a connection between a CAN signal and a system call. DASH saves this mapping. From that point forward, whenever the mapped CAN signal changes to the defined value, DASH internally generates the corresponding system message exactly as if a module had sent it.

**Example mapping:**
```
CAN signal "headlight feed" value 0x01  →  SYSTEM:headlights:on
CAN signal "headlight feed" value 0x00  →  SYSTEM:headlights:off
CAN signal "reverse gear"   value 0x01  →  SYSTEM:reverse:active
```

The patch bay translation is entirely internal to DASH. From the perspective of the system message handler, a CAN-translated message and an ESP32-sent message are identical.

---

## Key Principles Summary

These principles govern all transport and protocol decisions in DASH. Any feature development that touches external communication must respect them.

**1. Transport agnosticism**
Message content is identical across all transports. Only the delivery mechanism differs.

**2. Stateless and sourceless system messages**
DASH acts on system message receipt. It does not track, filter, or validate the source. Multiple sources sending the same message is a feature, not a problem.

**3. Module identity via hardware ID**
Every module is uniquely identified by its hardware ID. This ID appears in every module message and is the key used for all routing, storage, and reconciliation.

**4. Graceful degradation**
DASH operates normally regardless of which modules are present. Missing modules become dormant. Present modules are active. No module is required for DASH to function.

**5. User ownership**
The user decides which modules to install, which transports to use, and which system functions to implement. DASH does not assume or require any particular configuration.

**6. Read-only CAN**
DASH never writes to the vehicle CAN bus under any circumstances. CAN access is passive and read-only at all times.

---

## Appendix A — System Function Reference

**Important:** This appendix is a placeholder only. The system functions listed below are illustrative examples to demonstrate the message format and intended behaviour. They are not confirmed, final, or authoritative. The names, values, and DASH responses listed here may change entirely when system messages are formally defined in a dedicated design session.

**DASH developers must not treat this list as an implementation specification.** System message implementation will be defined separately and this appendix updated at that time.

*Illustrative examples only:*

| Function | Values | Illustrative DASH Response |
|---|---|---|
| headlights | on / off | UI dimming, headlight indicator |
| reverse | active / inactive | Trigger rear camera view |
| door_driver | open / closed | Door indicator in system bar |
| door_passenger | open / closed | Door indicator in system bar |
| door_rear_left | open / closed | Door indicator in system bar |
| door_rear_right | open / closed | Door indicator in system bar |
| handbrake | on / off | Handbrake indicator |
| indicator_left | on / off | Indicator mirror in system bar |
| indicator_right | on / off | Indicator mirror in system bar |
| ignition | on / off / accessory | System wake / sleep behaviour |

*This list is not exhaustive and the names and values shown are not final. Full system message definitions will be completed in a later design session and documented here as the authoritative reference at that time.*

---

## Appendix B — Future Improvements

The following items have been identified as desirable future additions to the transport system. They are documented here so they are not forgotten, but are not part of the current implementation scope.

**HYBRID module type**
Full implementation of modules that combine system signal reporting with their own accessory UI.

**Source dominance**
A user-configurable priority setting per system function, allowing one source to take precedence over others in cases where multiple sources may conflict rather than duplicate.

**Module version management**
Flagging when a reconnecting module presents a different firmware version than the version recorded at installation time.

**Incompatible module state**
A defined red state in the module discovery panel for modules that cannot be used with the current DASH version or hardware configuration. Criteria to be defined.

---

*This document is the authoritative reference for DASH transport and module protocol. All module development and all DASH features involving external communication must conform to the definitions contained here.*
