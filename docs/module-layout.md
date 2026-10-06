# DASH Module Layout — The Panel Specification

---

## Status

**Locked — 2026-10-01, roadmap 1.6.10.** Drawn through by two real modules (the Tank Gauge and
Climate), across WiFi, Bluetooth and USB, bidirectionally, with multi-module switching — which
is the condition this section set for the lock. From here it carries Bible weight and changes
only by explicit, considered decision. Additions that old modules never see — the browser rule,
§9 — remain the way it grows. *The text below is the status as first written, kept for the
record.*

~~**Provisional. Locks at roadmap 1.6.10.**~~

This document is the authoritative specification for how an ACCESSORY module describes the
panel it wants DASH to draw. It is **not yet locked** — a spec is not frozen before it has
been built against, and at the time of writing nothing has been rendered to it. It locks
into place at **1.6.10**, once two real modules have been drawn through it and the format
has been proven rather than reasoned about.

Until then it is the single source of truth for the layout model, and every other document
points here rather than restating it.

**Related documents:**

| Document | Covers |
|---|---|
| `transport.md` | The wire — how anything reaches DASH at all |
| `module-sdk.md` | **Locked.** How a module talks to DASH — messages, lifecycle, handshake, asset transfer |
| **`module-layout.md`** | **This document.** How a module describes what to draw |
| `arduino/arduino.md` | The working record — the reasoning, the history, the arguments |
| `interface.md` | DASH's own interface, including the panel container |
| `panel-preview/CLAUDE.md` | The companion previewer and linter that implements this spec |

**The carrier is already locked and is not this document's business.** Layouts and assets
reach DASH as `BLOCK|id|name|length|crc` inside the install handshake, CRC32-checked,
streamed from flash. See `module-sdk.md` §8. This document describes only what those blocks
*contain*.

---

## Purpose

DASH draws a box. The module fills it. This document defines the language a module uses to
say what goes in that box.

Everything here follows from the Module Mantra: **the module is king within its own
domain.** DASH provides the container, the rendering, and the data plumbing. What the panel
looks like — its art, its colours, its fonts, its arrangement — belongs entirely to the
module author. Nothing in this specification gives DASH an opinion about the contents of a
panel, and nothing in it should ever be extended to.

---

## 1. The model — layers and bindings

A panel is described by two things and nothing else.

**Layers** are what is drawn. An ordered stack, composited bottom to top. Each layer is a
raster image, a vector document, or text drawn by DASH.

**Bindings** are what happens. Each one attaches a behaviour to a target — draw this value
here, turn this element when that value changes, send an action when this area is pressed.

That is the whole model. There is no third concept.

> **On the earlier framing.** This supersedes the "PNG background plus overlays" model
> described in `arduino/arduino.md` §11 before 2026-08-06. That framing assumed one opaque
> raster at the bottom with decorations placed on top of it by coordinate, which quietly
> forced raster and vector to be two separate systems with two separate specifications.
> Layers and bindings collapse them into one: raster and vector are both simply layer
> types, and coordinates and element names are both simply ways of naming a target. The
> reasoning is recorded in `arduino/arduino.md`.

### Nothing is privileged as "the background"

The bottom layer is at the bottom. That is its only distinction. A panel may be a single
raster, a single vector, a raster with vector over it, or any other combination. The author
decides, per layer, using whichever tool suits that layer.

### Bindings may share a target

**Two or more bindings may point at the same target, and this is the normal case, not an
edge case.** An airflow arrow on a climate panel is typically both a `touch` binding (press
it to select that vent mode) and a `style` binding (colour it to show whether it is
currently selected). An author who assumes one binding per target will design around a
limit that does not exist.

---

### The wire format is not the storage format

*(Settled 2026-08-12, Roger's point.)*

A layout and its assets are **installed** — written to disk during the handshake and read
from disk from then on. **The module is never consulted again.** It is not asked to resend
its layout, it is not told when the panel is drawn, and it plays no part in rendering.

**What DASH keeps on disk is DASH's own business**, provided what is drawn is what the
author described. DASH may parse, normalise, restructure and cache a layout into whatever
form renders fastest — pre-parsing an SVG into its drawable form at install rather than
re-reading XML on every boot, for instance. On modest hardware driving a live gauge that is
the difference between a panel that feels instant and one that hitches.

The module hands over a document. How that document is stored is an implementation detail
and carries no contract.

> **One thing this must never do: pre-resolve a delegated value.** See §7 — a theme token is
> stored *as a token* and resolved in the frame being drawn. Baking it to a literal at
> install would freeze the panel at whatever theme was active on install day.

---

## 2. Layer types

| Type | What it is |
|---|---|
| **raster** | A PNG, shipped as a `BLOCK`. Photographic or painted artwork, brand assets, anything a vector cannot express. |
| **vector** | An SVG, shipped as a `BLOCK`, drawn from the defined subset (§9). Resolution-independent, restyleable, and its named elements can be bound to directly. |
| **text** | Not an asset. Text drawn by DASH from a live value — see below. |

**Raster and vector are equal citizens.** Neither is the base case with the other bolted
on. A panel may mix them freely, and a module may use different approaches for different
slots — vector for a small slot where a raster would be mush, raster for a large one where
the photograph is the point.

### The text layer

A text layer names the module variable it displays, and carries everything about how that
value is drawn: position, size, font, colour, alignment, **decimal places**, sign, and any
prefix or suffix such as a unit.

It must express **decimal places** — a sensor reading `30.0` beside one reading `29.5` must
not print as `30` — and **signed values**, since a ride height goes negative. Units are the
author's choice: a suffix on the layer, or painted into the artwork beside it.

Colour is a literal value or a DASH theme token (§7), like anything else.

**Typeface — two options, and only two.** *(Ruled 2026-08-06.)*

- **`@font`** — DASH's own font token. The panel's text follows whatever typeface the user
  has chosen system-wide, and changes with it.
- **A named Android font family** — `sans-serif`, `serif`, `monospace` and the standard
  variants. The author picks a face that suits their artwork, independent of the user's
  theme.

**A module never ships a typeface.** It names one; it does not carry one. Four reasons: a
font file is typically 50–500 KB against artwork measured in single figures; it would need
font-loading-from-bytes in the parser; nothing designed so far needs a particular face; and
**licensing** — an author embedding a commercial font in a module they then distribute
publicly has a problem, and DASH should not be the thing that hands it to them.

`sans-serif`, `serif` and `monospace` are guaranteed by the Android framework and always
resolve. Other family names are attempted and **fall back to the default if the device does
not have them** — the browser rule again, and necessary because what a vendor ships varies
between a consumer tablet and a bare head-unit image.

> **`monospace` earns its place for a specific reason.** In a proportional face, `29.5`
> becoming `30.0` shifts the whole readout sideways, because `1` and `9` are not the same
> width. On a gauge updating several times a second that jitter is genuinely distracting.
> An author with changing numbers usually wants monospace and may not know it yet.

> **Text is a layer, not a binding.** *(Settled 2026-08-06, writing the notation.)* It
> occupies a place in the stack and z-orders against everything else; it has a position, a
> size and a font. A "text binding" would have been a binding that *creates* something
> rather than modifying something already drawn, which is a different kind of thing
> altogether. A `style` binding may still target a text layer to change its colour — a
> reading that goes red past a threshold — exactly as it targets any other layer.

### The trade the author is making

Both paths work. They cost different things, and an author should choose knowingly:

- **Raster** gives complete freedom of appearance — anything that can be painted can be
  shown. The cost is that **every dynamic element needs its position measured by hand** as
  normalised coordinates, and that a PNG cannot scale up cleanly, so it must be authored
  for the largest screen it will ever meet.
- **Vector** costs a subset (§9) and a design tool that exports SVG. In exchange, an
  element's position *is* its position — there is nothing to measure and nothing to drift
  when the artwork is nudged — and the panel is crisp at any size on any screen.

### Asset size — no limits, honest advice, and graceful degradation

*(Ruled 2026-08-12, Roger: "we should not inhibit, but we should advise of the consequences.
It is not our place to judge what others do with their system.")*

**DASH imposes no maximum asset size and rejects nothing for being too large.** There is no
cap per block, no cap per module, and no refusal path. An author draws what they want to
draw.

**What DASH does instead is degrade.** It attempts to load every asset at full fidelity. If
the device cannot allocate the memory, **the asset is loaded downsampled rather than
refused** — the panel renders a little softer on weak hardware and pin-sharp on strong
hardware, and nothing fails. This is the Capability Detection Principle applied to artwork:
attempt, and where it will not go, fall back rather than error.

The consequence is one module, no tiers, no special cases. A generous asset renders properly
on a Gold-tier head unit with memory to spare, and the same module still works on a phone. A
fixed cap would have punished the capable hardware in order to protect the weak.

> **This does not breach the Module Mantra.** DASH already scales every asset to whatever
> size the panel happens to be on a given screen; downsampling under memory pressure is the
> same proportional operation with a different trigger. It renders the author's design at
> lower fidelity — it does not change the design. That is categorically different from
> stretching a layout into a shape it was not drawn for (§6), which changes what the thing
> *is*, and which DASH never does.

**The advice, which is the author's to take or ignore:**

A large horizontal panel at full width on a large tablet renders around **2560 × 960
pixels**. A bitmap of that size occupies roughly **10 MB of live memory regardless of what
the file weighs on disk**, because a PNG is compressed on disk and uncompressed in use.
Several layers, across two or three installed modules, is where a modest device starts to
struggle.

So: **draw at the resolution the panel actually renders at, and no further.** A 4K
background for a strip that renders 2560 wide costs memory and buys nothing — it will simply
be scaled back down before it reaches the screen.

**File size on the module is a solved problem and should not drive the decision.** ESP32
boards ship with 4 MB of flash and 8 or 16 MB variants cost pennies more; a microSD slot over
SPI is three wires and adds gigabytes. Vector is still fifty to a hundred times smaller than
raster for the same panel, and that remains a reason to choose it — but running out of room
on the module is not.

---

## 3. Targets — the three ways to point at something

Every binding names a target. There are three kinds.

| Target | Applies to | Example |
|---|---|---|
| **layer** | Any layer | The needle is its own PNG; rotating it means rotating that layer |
| **rect** | Anything, at normalised 0–1 coordinates | An invisible touch area over painted artwork |
| **name** | Elements inside a vector layer only | `arrow_head`, taken from the element's `id` |

**Normalised coordinates** are fractions of the panel, not pixels. A rect at `0.5, 0.3` is
in the same relative place on a phone and on a head unit. DASH converts to real pixels at
render time.

**Names come from the artwork.** In every major vector tool, a layer or object name becomes
the SVG element's `id` on export. An author names a layer `needle` in Inkscape and binds to
`needle`. This is why vector is pleasanter to author: the binding and the art cannot drift
apart, because they are the same fact.

---

## 4. Bindings

**Five kinds.** Four are proven against real panel designs; `translate` is included on the
strength of an obvious use case rather than a drawn one.

### 4.1 ~~`text`~~ — moved to §2

> **Superseded — 2026-08-06.** This was originally specified as a sixth binding. It is not
> a binding; it is a **layer type**, and its specification now sits in §2 with the other
> layer types. The numbering is left intact so that references elsewhere still resolve.
>
> The error surfaced the moment the notation was written: text has a position, a size and a
> place in the z-order, which are layer properties, and a "text binding" would have been the
> only binding that *creates* something rather than modifying something already drawn.

### 4.2 `touch`

Makes a target pressable. On press, DASH sends `ACTION|id|control|value` to the module.

DASH does not validate the control id — the panel validates it for free, since it can only
ever render controls the layout actually declares.

A touch binding may optionally show a visual response on press. This is distinct from
`style`: press feedback is momentary and about the interaction; `style` reflects state.

### 4.3 `style`

Changes a target's appearance based on a value. Fill, stroke, tint, or opacity.

**On vector**, this restyles the named element. **On raster**, this is a **tint** — ship
the artwork once in greyscale and colour it per state, rather than shipping an "on" image
and an "off" image.

`style` must support two kinds of case:

- **Discrete** — when the value is `HIGH`, use this colour; otherwise the default. Covers
  toggles, mode selectors, presets.
- **Range** — when the value falls below or above a threshold, use this colour. Covers
  warnings: a tyre under pressure, a temperature over limit.

**Opacity is how things appear and disappear.** There is no separate visibility binding —
hiding something is setting its opacity to zero, which also means it can fade rather than
only blink. Two stacked layers with opposing opacity bindings give image-swapping with no
additional mechanism.

**A `style` binding with no variable is a static restyle** — *"this element is always this
colour"*. It watches nothing and never changes. This is how an author delegates a colour to
a DASH theme token (§7) without the artwork itself having to carry any DASH-specific
markup, and it is why SVG files stay plain, valid and previewable in the author's own tool.

### 4.4 `reveal`

Draws a target up to a proportion of itself — a bar filling from the bottom, a meter, an
arc gauge.

**This is a clip, not a scale.** Scaling an element to show a proportion distorts it: a bar
with rounded ends or a gradient deforms visibly as it shrinks. Revealing draws the element
at its true size and clips it, which preserves the artwork at every value.

Needs an input range (what counts as empty, what counts as full) and a direction.

### 4.5 `rotate`

Turns a target about a pivot, mapped from a value.

Needs a pivot point, an input range and an output angle range — a gauge marked 0–11 BAR
sweeping across roughly 280° rather than a full circle.

**The pivot is a fraction of the target's own box** *(amended 2026-08-12)*, so a needle turns about
the same point whether it was shipped as its own raster layer or drawn as a named path inside a
vector one. See §9 for the field and the reasoning.

### 4.6 `translate`

Moves a target along an axis, mapped from a value. Sliders, level indicators, a vehicle
body rising and falling with its suspension.

*Included on reasoning rather than evidence — no drawn panel has yet required it. If it is
still unused when this document locks at 1.6.10, it is a candidate for removal.*

---

## 5. Transitions and effects

These are not binding kinds. They are properties available to **any** binding.

### Transitions

When a bound value changes, the visual may **ease** to its new state rather than snapping —
a duration and a curve. The needle sweeps, the bar slides, the vehicle settles to its new
ride height over a couple of seconds, which in an air-suspension panel mirrors the physical
reality of what the hardware is doing.

This is small to implement, applies uniformly across every binding kind, and is most of
what separates a panel that feels built from one that feels like a spreadsheet.

> **Ruled 2026-08-12 — panel transitions are the module's, always.**
>
> DASH has carried a user-set transition length since 1.5.2 (INSTANT → CINEMATIC). The
> question was what happens when a user who chose INSTANT installs a module asking for an
> 800 ms sweep — an apparent collision between *the module is king within its own domain* and
> *the user is master of their own system*.
>
> **There is no collision.** The user **is** master, because the user owns the module. This
> is an open platform: the firmware is theirs to write, and the layout is a plain data file
> sitting on the module's flash — changing `"ease": 800` to `"ease": 0` is editing a value,
> not rewriting a program, and the previewer will show them the result before they flash it.
> Their remedy is not a DASH setting; it is the thing they already control.
>
> So **DASH's transition setting governs DASH's own chrome and never reaches inside a
> module panel**, and a duration is never passed to a module or negotiated with one. A
> `@transition` delegation token was considered and is **not** provided: it would exist only
> to resolve a conflict that does not arise.

### Effects

Something moves without a value driving it — a warning that pulses, an indicator that
blinks while a condition holds. DASH-driven, no asset required.

---

## 6. Layout slots

### The twelve slots

**Six shapes × two ambient modes.**

| | Horizontal | Vertical |
|---|---|---|
| **Large** | **8 × 3** | **3 × 8** |
| **Medium** | **16 × 3** | **3 × 16** |
| **Small** | **16 × 1** | **1 × 16** |

Long edge first, thickness second. **Every vertical slot is its horizontal twin stood on
its end**, so there are three shapes to learn rather than six.

Each shape exists in a **`day`** and a **`night`** variant, driven by the Ambient feature.

> **Naming — 2026-08-05.** These were `_light` / `_dark` in `transport.md`. Renamed because
> the switch is genuinely day and night in a vehicle, not a theme's lightness — a user may
> perfectly well run a dark DASH theme in daylight, and `light`/`dark` invited that
> confusion.

> **Why day/night needs separate artwork at all.** Theme tokens (§7) restyle only what the
> author *delegated* to DASH. They can never restyle the author's own art — a photographic
> background, a gauge face with baked-in shading, a brand logo. A module that genuinely
> changes appearance at night needs different artwork, and that is a second slot.

**The Ambient switch changes the theme tokens and the active slot together**, as one
coordinated change, or a panel renders night artwork in day colours.

### Every slot is authored completely and independently

**No reflow. No inheritance. DASH never rearranges a layout to fit a different shape.**

8 × 3 and 16 × 1 are not one design at two sizes; they are different designs. Anything DASH
did to reflow between them would be DASH forming an opinion about the author's artwork,
which the Module Mantra forbids outright. It is also the hardest problem on the web, and
DASH's fixed slots exist precisely so that nobody ever has to solve it.

### Assets are shared; layouts are not

One needle, shipped once as one `BLOCK`, referenced by as many slot layouts as want it. The
author fully authors each slot but never ships the same artwork twelve times.

### Partial support is normal

**Every slot is optional.** An author ships the slots they want and no more. A module
supporting one shape in one ambient mode is a legitimate module.

> **DASH never scales a layout into a shape it was not drawn for.** *(Ruled 2026-08-06.)*
>
> `transport.md`'s installation-handshake section previously said *"if neither variant is
> defined for a requested size, DASH scales the nearest available size up to fill the
> space."* **That rule is deprecated.** Stretching an 8×3 design into a 16×1 slot
> misrepresents the author's work, and DASH forming its own opinion about how someone's
> artwork should be reshaped is exactly what the Module Mantra forbids. A panel is either
> drawn as authored or not drawn at all.
>
> Carried forward from `transport.md` and still standing: every slot is optional; a module
> must define at least one slot to be a valid ACCESSORY; and **a missing `night` variant
> falls back to the `day` one** — that fallback is uncontroversial, because it is the same
> artwork rather than a distorted shape.
>
> **Ruled 2026-08-12 — no layout, no panel.**
>
> DASH holds a record of every layout every installed module provided, so it always knows
> what it can honestly draw. **A module with no layout for the selected size is simply not
> shown.** Not stretched, not substituted, not apologised for with a placeholder.
>
> *Two modules installed: the first provides large and small, the second large only. The user
> selects small. **Only the first module appears.*** The second is not absent through error —
> its author did not draw that shape, and DASH will not invent it. The remedy belongs to
> whoever can actually fix it: supply the missing layout, or select a size the module has.
>
> *No modules installed, or none providing the selected size: **no module panel is drawn at
> all.***
>
> > **This supersedes the empty-box tenancy from roadmap 1.6.2**, which held that *"with no
> > module installed there is no king in the castle, so DASH occupying the empty box is the
> > one tenancy it is entitled to."* It is not entitled to it. Reserving a strip of screen to
> > display nothing is the same failure as stretching artwork to fill a shape — DASH taking
> > space it has no content for. The screen reclaims that space until a module can fill it.
> > *(A DASH-side change, not yet built.)*
>
> **Still open, and a UI question rather than a format one:** a module that vanishes when the
> user switches size needs to say why, or it simply looks broken. That belongs with the
> Module Manager and the size selector, and hardens at 1.6.8 where several modules may
> support different sets.
>
> > **Closed — 2026-08-19 (roadmap 1.6.8), Roger's ruling. It doesn't say why.**
> >
> > **The rule above is the whole answer.** The user selected a size; a module with no layout
> > for it is not drawn. Annotating the panel size selector with a count of how many modules
> > can fill each size was proposed and **rejected**: it would be DASH narrating a choice the
> > user made and can unmake by changing the size back — which is DASH explaining its own
> > behaviour rather than getting out of the way.
> >
> > **What a module actually shipped is a different question and is answered plainly.** The
> > Module Manager states, against each module's record, how many layouts it has and which of
> > the twelve slots they are (roadmap 1.6.11). That is a fact about the module, reported where
> > a user goes to find out what a module is — not DASH accounting for its own decisions.

### Progressive disclosure

Because slots are independent, a module may show **different amounts of information** in
different shapes — ten parameters in large, five in small.

This makes the user's panel-size setting a question about **information**, not merely
space: how much do you want to see at a glance. The author decides what survives the cut.

---

## 7. Theme tokens

An author may reference DASH's theme colours instead of specifying literal values. The
token is prefixed with **`@`** to distinguish it from a literal. DASH resolves it at render
time against the active theme, so a panel using tokens follows the user's colours with no
module involvement.

**The nine tokens:**

`@backgroundColourPrimary` · `@backgroundColourSecondary` · `@textColourPrimary` ·
`@textColourSecondary` · `@iconColourPrimary` · `@iconColourSecondary` ·
`@accentColourPrimary` · `@accentColourSecondary` · `@font`

> **Correction — 2026-08-06.** `arduino/arduino.md` §11 and `module-sdk.md` both offered
> `@barBackground`, `@barText` and `@barAccent` as examples. **Those tokens do not exist.**
> The four `bar*` tokens were retired at roadmap 1.5.2 and replaced by the nine above. Any
> module written against the old names would have been written against a vocabulary DASH
> had already discarded.

**Literal values remain equally valid.** An author wanting a specific branded appearance
independent of the user's theme simply specifies colours directly. Tokens are a facility,
never a requirement — the module is king, and that includes the right to ignore DASH's
palette entirely.

### Delegation is not abdication

*(Principle established 2026-08-12, Roger's reasoning.)*

The Module Mantra says DASH must not alter, override or style module content. A theme token
looks, at first glance, like it breaches that — DASH is choosing a colour inside the panel.

It does not, and the distinction matters enough to state as a rule:

> **If the module asks DASH to choose, DASH choosing is not overriding. It is obeying.**

The mantra protects the module's *authority*. It does not oblige the module to *exercise*
that authority over every particular. An author writing `@accentColourPrimary` has issued an
instruction — *"this one follows the system"* — and DASH honouring it for the life of the
installation is compliance with that instruction, not interference with it.

Roger's framing: a king ordering a lord to see that the people are fed has not surrendered
his authority by leaving the method to the lord. The discretion was part of the order.

**And because the decree was made once, at install, it never needs restating.** The module
is not told when the user changes theme. It does not need to know. It already said what
should happen.

> **The boundary that keeps this from being stretched: delegation is always explicit and
> opt-in.** DASH may never *infer* a delegation the author did not make, and where there is
> any doubt about whether a value was delegated, **the literal wins**. Without that boundary,
> "the king permitted discretion" becomes a licence for DASH to start forming opinions, which
> is precisely the failure the Mantra exists to prevent.

### Tokens are resolved when the panel is drawn

A token is stored **as a token** and resolved in the frame being rendered — never flattened
to a literal at install time. This is what makes a themed panel follow the user's colours
live, and it is the whole reason the arrangement above works without the module being
involved. See §1.

### DASH's status colours are not available to modules

DASH has a status palette — the greens, ambers and reds that mean *healthy*, *waiting* and
*faulted* across its own interface. **These are deliberately not exposed as module tokens.**

They are, in the words of the source that defines them, **"inks, not fills"**: every value
is measured for contrast against DASH's own settings surface. A module panel has the
*module's* background, which DASH knows nothing about — a green measured at 6.93:1 on dark
grey may be invisible on a white panel. Handing an author a colour that is illegible in
half its possible uses is worse than handing them none.

**A module wanting a warning colour picks its own**, because only the module knows what it
is drawing on. This is the same reasoning that keeps the status palette out of `DashTheme`
in the first place: a colour carrying a *meaning* rather than an identity belongs to
whoever owns the surface it appears on.

---

## 8. What the module knows, and what it does not

**The module never knows which slot is being shown.**

It ships every layout it supports at install, and thereafter reports everything it has,
continuously. DASH draws whatever the active layout binds; values that no active binding
uses are simply not drawn.

**The module is the server. The layouts are the pages. DASH is the browser.** The server
sends what it has; the browser decides what is on screen. The server never needs to know
the window size.

This keeps the module simple and stateless with respect to DASH's interface — no new
message, no notification of UI state, no firmware branching on panel size.

*Accepted cost: a module may report data that is not currently drawn. This is deliberate.
The alternative couples the module to DASH's interface state, which is a considerably worse
trade than some redundant traffic on the wire.*

### The module reports facts. It never issues instructions.

A module never says *"highlight the high button."* It says:

```
REPORT|a4cf12b8e901|preset|high
```

— *"my preset is currently high."* No colour, no position, no element name, nothing about
the panel at all. **The layout decides what that fact looks like**, through a `style`
binding watching `preset`.

This is what keeps firmware and appearance genuinely separate. An author can redraw the
whole panel, move every control, and ship it for all twelve slots, and the firmware never
changes — because it was only ever stating a fact about itself. The same fact drives twelve
different layouts differently, and the module does not know there are twelve.

**On activation the module sends everything it has** (the full state dump, `module-sdk.md`
§4c), so the panel is correct from its first frame rather than blank until something
happens to change.

### Pressing a control — optimistic update with a timeout

*(Settled 2026-08-06, Roger's ruling.)*

When a `touch` binding fires, DASH sends `ACTION|id|control|value` and **updates the panel
immediately**, without waiting for the module. The module then confirms by reporting its new
state, and DASH reconciles.

> **A control's name *is* the variable it predicts, and its `value` is the prediction.**
> *(Amended 2026-08-14, roadmap 1.6.7 — built against, and Roger's ruling. This was assumed
> throughout the section from the day it was written and stated nowhere, which is the kind of
> gap that only shows up when somebody implements it.)*
>
> Everything above depends on it. A press of `control: "preset", value: "high"` is confirmed by
> `REPORT|id|preset|high`, and the reconcile can only match the two because they carry the same
> name. Without the rule DASH would know a press had been sent and have no idea which variable to
> draw ahead of.
>
> **So a `touch` binding's `control` names a variable the module reports, and its `value` is the
> value that variable is expected to take.** Read that way, an `ACTION` is simply *"set this to
> this"*, and the confirming `REPORT` is *"it is now this"* — which is why no separate
> acknowledgement message was ever needed.
>
> **A control with no `value` predicts nothing**, and that is where the momentary case below comes
> from. It is not a special case in DASH; it falls out of the rule. There is nothing to draw ahead
> of, so nothing to time out and nothing to revert.
>
> **What the rule does not do is constrain what a control may be.** A button that means *"one more
> than whatever it is now"* cannot name its result in advance, so it carries no value and is
> momentary — the panel moves when the module says it moved. That is a perfectly good control and
> the format has no arithmetic to offer it (§10). An author who wants a press drawn instantly gives
> it a literal.

**Three outcomes, of which only one is a fault:**

| The module reports | Meaning | DASH does |
|---|---|---|
| The value that was requested | It worked | Keeps the optimistic state |
| **A different value** | It heard, and could not comply — **not an error** | Draws what the module actually said |
| **Nothing, within the timeout** | It did not hear | Reverts to the previous state, and counts it |

The middle row needs no special handling: the module reporting `preset = mid` after a press
of HIGH is simply the truth arriving, and the panel draws facts.

**Escalation.** A single timeout reverts quietly — messages get lost, and a dropped packet
on a shared bus is not a fault worth interrupting a driver over. **Repeated** timeouts mean
a module that is alive and heartbeating but not honouring actions, which is a genuine
malfunction and is worth surfacing. It is also distinct from the existing "present but gone
quiet" state: a module ignoring you while still replying is a different and more sinister
failure than one that has simply stopped talking.

**Why optimistic rather than waiting.** A button that does not respond until a round trip
completes feels broken even when it is working perfectly. The timeout is what makes
optimism honest — the panel may briefly show intent rather than reality, but that window is
bounded and has a defined ending.

**The timeout measures acknowledgement, not completion.** This is the distinction that keeps
it simple. Raising an air-suspended vehicle genuinely takes several seconds, but the module
should confirm `preset` — *what it is aiming for* — the instant it accepts the press, and
report the achieved heights as **separate variables** as the car moves. A third variable can
report *moving* versus *settled* if the author wants an indicator for it.

So a module reports three independent facts, and **DASH never learns that raising a car
takes time** — it draws facts as they arrive. Because acknowledgement is always immediate
work, a single generous timeout of a second or two covers every transport, with nothing to
declare and nothing to tune.

**There is no separate acknowledgement message.** The `REPORT` does both jobs — it confirms
the press *and* states the new truth. A `ROGER` for actions was considered and rejected: it
would be a second message confirming something the first already proved.

**Momentary controls need no special case.** A control that changes no displayed state — a
reset, a trigger, a "level now" — has no `style` binding watching it, so nothing changes
optimistically, nothing times out, and there is nothing to revert. Press feedback still
fires, because that is local and immediate and has nothing to do with the module.

### 8a. The module's own message board

*(Added 2026-08-15, roadmap 1.6.7 — Roger's design. Provisional with the rest of this document.)*

**A module may declare, in its layout, the values each of its variables takes and in what order.**

```json
"variables": {
  "fan":         { "values": ["off","1","2","3","4","5","6","max"], "wrap": false },
  "heated_seat": { "values": ["off","lvl1","lvl2"],                 "wrap": true  },
  "auto":        { "values": ["off","on"],                          "wrap": true  }
}
```

**This is the module's private version of DASH's own system message board.** The shared board has
a declared vocabulary with a type and a set of values per signal, written by DASH because the board
is shared. A module's board is private, so the module writes it. That single difference is the whole
idea, and it is why this does not breach the mantra: nobody is reaching into anybody's box.

**It is a list, and there is no type system.** A toggle is a two-element list that wraps. A fan is
an eight-element list that stops. Neither needs a rule of its own, and there is no boolean, no enum
and no numeric range to learn.

#### What it is for: a press that draws immediately

Without it, a `+` cannot be drawn ahead of the module, because *"one more than whatever it is now"*
is not a value anyone can name in advance. With it, **DASH finds the current value in the list,
moves one place, and sends what it lands on**:

```
ACTION|a4cf12b8e901|fan|4
```

So a stepper is not a special kind of control at all — it is an ordinary set-this-to-this, and the
rule above applies to it unchanged. **After this, every control on the wire is the same message**,
and the momentary case shrinks back to what it always should have been: genuinely stateless things,
a reset or a "level now".

> **DASH does no arithmetic on a module's data.** It never adds one to `3`. It moves along a list
> the module wrote, with no idea what the values mean or that `4` is larger than `3` — the order is
> the only thing it knows, and the module supplied that. This is delegation, exactly as a theme
> token is (§7), rather than DASH forming an opinion about a module's data.

**Values are written out longhand.** A `range` shorthand was designed and rejected (2026-08-15,
Roger's ruling): the deciding case was a temperature scale whose ends are `min` and `max` rather
than numbers, so the shorthand could not express the very thing it was invented for. Twice now a
real control has had words at the ends and numbers in the middle — a fan and a temperature — which
suggests that is the norm rather than the exception. **The value strings are also the contract**:
what DASH sends and what it matches the module's `REPORT` against. A generated `17.0` would never
match a reported `17`, and writing them by hand removes the question.

#### The rules

- **No board, no prediction.** A module that declares none behaves exactly as it did before: presses
  go out, values change when the module answers. The browser rule, applied to a whole feature.
- **A value the list does not contain still works.** It displays and drives every binding, because
  it is a string like any other. There is simply no index to step from, so prediction switches off
  until a known value returns. Never an error.
- **A press that lands nowhere sends nothing.** At an end stop on a non-wrapping list — or from a
  value the board does not list — there is no direction left on the wire to send, and the only thing
  such a press could carry is DASH's *current* value, which would command the module to whatever
  DASH last believed. A stale belief swallowing a press is a nuisance; a stale belief issuing a
  command is the second-source-of-truth failure this design exists to prevent. The heartbeat
  corrects a stale belief within a second or two anyway.
- **The board holds only what the panel can operate.** A sensor reading is not a list, and an array
  for `tank_pressure` would be nonsense. Reported values continue to be declared implicitly by the
  bindings that use them (§9).

### When a bound variable never arrives

A binding may reference a variable the module never reports — a typo in the layout, or a
variable dropped by a later firmware revision.

**The binding does nothing and its target keeps its default appearance.** Never an error,
never a blank panel: the browser rule applies to data exactly as it applies to artwork.

**The previewer must shout about it.** Silently doing nothing is how an author loses an
afternoon to a mistyped variable name, and catching precisely this is why the previewer
lints rather than merely draws.

---

## 9. The notation

The format is **JSON**. *(Drafted 2026-08-06. Provisional — it locks at 1.6.10 along with
the rest, and the previewer is expected to change it before then.)*

> **Why JSON.** Every field is named, so nothing is positional and nothing can be
> miscounted — a wrong field in a positional format produces a needle that points subtly
> wrong rather than an error anybody can see. Optional fields are simply absent. DASH parses
> it with no new code (`kotlinx.serialization.json` is already a dependency) using a hardened
> parser, which matters for data arriving from a device DASH does not control. Authors can
> validate their work in tools they already have before flashing anything. And unknown keys
> are ignored by default, which is the graceful-degradation rule below, for free.
>
> A layout is a **document**, not a message. It is authored by a human, saved, versioned and
> edited over months, then transferred once at install. That it travels inside a
> pipe-delimited message does not make it one.

### One document per slot

**A layout document describes exactly one slot, and the `BLOCK` name is the slot name.**

```
BLOCK|a4cf12b8e901|h_large_day|2841|3f9a1c04
```

DASH knows the twelve slot names, so a block carrying one of them is a layout and any other
block is an asset. No new message, no extra field, and a module ships only the slots it
supports.

Assets are referenced from a layout by their own block name — `dial.png` in a layout means
the block the module sent under that name. **Assets may be referenced from any number of
slot layouts**, which is how artwork is shared without being shipped twice (§6).

### The document

Two lists, and nothing else:

```json
{
  "layers":   [ ... ],
  "bindings": [ ... ]
}
```

### Coordinates and values

| Convention | Meaning |
|---|---|
| **Positions and sizes** | Fractions of the panel. `0,0` is top-left, `1,1` is bottom-right. Never pixels — the panel is a different physical size on every screen. |
| **`pivot`** | A fraction of the **target's own box**, not the panel. `[0.5, 1.0]` is the middle of its bottom edge. *(Amended 2026-08-12 — was "the layer's own box"; see `rotate` in §9.)* |
| **Colours** | A literal (`"#FF6600"`) or a DASH theme token (`"@accentColourPrimary"`, §7). |
| **Angles** | Degrees, clockwise, zero pointing up. |
| **Durations** | Milliseconds. |

### Layers

Drawn in list order, first at the bottom. Common to every layer:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | The name bindings use to point at this layer. Unique within the document. |
| `type` | yes | `raster`, `vector` or `text`. |
| `at` | no | Top-left position (or anchor point, for text). **Omitted on a raster or vector layer means the layer fills the panel** — which is how a background is expressed without needing the concept of one. |
| `opacity` | no | `0`–`1`. Default `1`. |

**`raster` and `vector` additionally take:**

| Field | Required | Meaning |
|---|---|---|
| `asset` | yes | The block name of the PNG or SVG. |
| `size` | no | `[width, height]` as fractions of the panel. Omitted means fill. |
| `pivot` | no | Rotation centre, within the layer's own box. Default `[0.5, 0.5]`. A `rotate` binding may carry its own `pivot`, which overrides this — see §9's `rotate`. |

**`text` additionally takes:**

| Field | Required | Meaning |
|---|---|---|
| `value` | yes | The module variable to display. |
| `fontSize` | yes | Fraction of the panel's height. |
| `align` | no | `left`, `center` or `right`, relative to `at`. Default `left`. |
| `colour` | no | Literal or token. Default `@textColourPrimary`. |
| `font` | no | `@font` for DASH's own, or an Android family name (`monospace`). Default `@font`. **A module never ships a typeface** — see §2. |
| `weight` | no | `normal` or `bold`. Default `normal`. |
| `italic` | no | `true` or `false`. Default `false`. |
| `decimals` | no | Decimal places. Omitted means print the value as received. |
| `prefix` / `suffix` | no | Text either side of the value — units, symbols. |

*Text carries no `size`: it has an anchor and a type size, not a box.*

### Bindings

Every binding names what it acts on, in one of two ways:

| Field | Points at |
|---|---|
| `target` | A **layer id** (`"needle"`), or a **named element inside a vector layer**, written `layer#element` (`"cabin#arrow_head"`). |
| `rect` | A bare area, `[x, y, width, height]`, attached to no layer. For touch areas over painted artwork. |

> **`layer#element` closes the name-scoping problem.** Two vector layers may each contain an
> element called `needle` without ambiguity, because the layer always qualifies the element.

Common to every binding:

| Field | Required | Meaning |
|---|---|---|
| `bind` | yes | `touch`, `style`, `reveal`, `rotate` or `translate`. |
| `target` / `rect` | yes | One or the other. |
| `ease` | no | Milliseconds to move to a new state. Omitted means snap. |

**`touch`**

| Field | Required | Meaning |
|---|---|---|
| `control` | yes | The control name sent as `ACTION\|id\|control\|value`. **It is also the variable this press predicts** — see §8. |
| `value` | no | The value sent, and the value that variable is expected to take. Omitted sends no value field at all, which makes the control momentary (§8). |
| `step` | no | Moves `control` along its declared list (§8a) instead of naming a value — `1` for the next, `-1` for the previous. DASH works out the value before sending, so a stepper is an ordinary set-this-to-this. Mutually exclusive with `value`. |
| `feedback` | no | Appearance applied while pressed — `colour` and/or `opacity`. Local and immediate; nothing to do with the module (§8). |

> *Both rows amended 2026-08-14 (roadmap 1.6.7), alongside §8's rule. `control` gained the half of
> its job the table never mentioned, and `value` previously read "omitted sends an empty value
> field" — it sends **no** field, so an omitted value produces `ACTION|id|control` rather than a
> line with a trailing separator. That is the same shape an event-only broadcast takes, and it is
> what a momentary control actually means on the wire.*

**`style`**

| Field | Required | Meaning |
|---|---|---|
| `value` | **no** | The module variable watched. **Omitted makes this a static restyle** — always applied, never changing. This is how a token is delegated to an element (§7). |
| `cases` | only with `value` | Ordered list. **First match wins.** |
| `default` | no | Applied when no case matches. |

Each case matches on `is` (exact), `below`, `above` or `between`, and carries any of
`colour`, `opacity`, or `pulse` (a period in milliseconds — the effect from §5). A static
restyle carries those same properties directly on the binding, with no `cases` at all:

```json
{ "bind": "style", "target": "dial#logo", "colour": "@accentColourPrimary" }
```

*`colour` means "make this thing this colour": DASH tints a raster layer and fills a vector
element. One word, because the author's intent is the same either way.*

**`reveal`**

| Field | Required | Meaning |
|---|---|---|
| `value` | yes | The variable driving the fill. |
| `from` | yes | `[empty, full]` — the input range. |
| `direction` | no | `up`, `down`, `left` or `right`. Default `up`. |

**`rotate`**

| Field | Required | Meaning |
|---|---|---|
| `value` | yes | The variable driving the angle. |
| `from` | yes | `[min, max]` input range. |
| `to` | yes | `[angle, angle]` output range in degrees. |
| `pivot` | no | Rotation centre, as a fraction of the **target's own box**. Overrides the layer's `pivot` when present. Default `[0.5, 0.5]`. |

**Rotation happens about the target's `pivot`** — the layer's own box where the target is a layer,
and the element's bounding box where the target is `layer#element`.

> **One rule, both routes.** *(Amended 2026-08-12, after the parser spike. This previously read
> "rotation happens about the layer's `pivot`", with `pivot` available only as a layer field.)*
>
> A pivot on the *layer* is right for a needle shipped as its own small raster layer: `[0.5, 1.0]` is
> the middle of its bottom edge, which is where a needle turns. **Nothing about that case changes.**
>
> It does not work for `layer#element`. A vector overlay normally fills the panel, so the layer's box
> *is* the panel, and the needle is one element among twenty — leaving the author to measure their
> dial's hub as a fraction of the whole panel (`[0.1875, 0.5]` in the spike's gauge) and to measure
> it again every time they nudge the artwork. And because it was a single field on the layer, **two
> elements in one layer could not rotate about different centres**, so a twin-needle gauge could not
> be expressed at all.
>
> Reading the pivot against *the target* fixes both without adding a concept: a needle is
> `[0.5, 1.0]` whether it was shipped as a raster layer or drawn as a named path inside a vector one.
>
> ```json
> { "bind": "rotate", "target": "dial#needle", "value": "tank_pressure",
>   "pivot": [0.5, 1.0], "from": [0, 11], "to": [-140, 140] }
> ```

**`translate`**

| Field | Required | Meaning |
|---|---|---|
| `value` | yes | The variable driving the movement. |
| `from` | yes | `[min, max]` input range. |
| `to` | yes | `[[x, y], [x, y]]` — start and end offsets, as fractions of the panel. |

### A complete example

The air-ride panel from the design sessions — a pressure gauge, two ride-height readouts,
and three preset buttons:

```json
{
  "layers": [
    { "id": "face",   "type": "raster", "asset": "dial.png" },
    { "id": "car",    "type": "raster", "asset": "car.png",    "at": [0.55, 0.15], "size": [0.35, 0.35] },
    { "id": "needle", "type": "raster", "asset": "needle.png", "at": [0.20, 0.18], "size": [0.03, 0.24],
                      "pivot": [0.5, 1.0] },

    { "id": "front_h", "type": "text", "value": "front_height", "at": [0.58, 0.55],
                       "fontSize": 0.08, "decimals": 1, "align": "center" },
    { "id": "rear_h",  "type": "text", "value": "rear_height",  "at": [0.80, 0.55],
                       "fontSize": 0.08, "decimals": 1, "align": "center" },

    { "id": "btn_high", "type": "raster", "asset": "btn_h.png", "at": [0.56, 0.70], "size": [0.09, 0.14] },
    { "id": "btn_mid",  "type": "raster", "asset": "btn_m.png", "at": [0.68, 0.70], "size": [0.09, 0.14] },
    { "id": "btn_low",  "type": "raster", "asset": "btn_l.png", "at": [0.80, 0.70], "size": [0.09, 0.14] }
  ],

  "bindings": [
    { "bind": "rotate", "target": "needle", "value": "tank_pressure",
      "from": [0, 11], "to": [-140, 140], "ease": 600 },

    { "bind": "touch", "target": "btn_high", "control": "preset", "value": "high" },
    { "bind": "touch", "target": "btn_mid",  "control": "preset", "value": "mid"  },
    { "bind": "touch", "target": "btn_low",  "control": "preset", "value": "low"  },

    { "bind": "style", "target": "btn_high", "value": "preset",
      "cases":   [ { "is": "high", "colour": "@accentColourPrimary" } ],
      "default": { "colour": "@iconColourSecondary" } }
  ]
}
```

`btn_high` appears **twice** — once to be pressable, once to change colour. That is the
share-a-target case from §1, and the notation was chosen to make it plainly visible rather
than clever.

### The layout is the declaration

**A module declares no variables and no controls separately.** The layout already names
every variable it binds to and every control it can send, so DASH learns both by reading it.

> **This closes an item open since roadmap 1.4.4**, recorded in `InstalledModule.kt` as
> *"ACCESSORY variables and interactive controls — the other half of the panel contract —
> have no locked install-declaration framing yet."* There is no framing to lock, because
> there is no separate declaration. It also retrospectively justifies the 1.4.9 decision not
> to validate incoming control ids on the grounds that *"the panel does that for free, only
> ever rendering real controls"* — which is exactly what this makes true.

### The SVG subset

DASH renders a defined subset of SVG, not the whole specification. *(Drafted 2026-08-12.
Provisional; the spike is expected to move it.)*

**What shapes the subset.** It is not an arbitrary choice. Compose's vector model — the one
Android's VectorDrawable expresses, and `ImageVector` with it — is **paths, groups, transforms,
fills, strokes, gradients and opacity**, and everything in the subset has to land on that.
Conveniently, SVG's other shapes all convert to paths with simple arithmetic, which is
exactly what Android's own build tooling does when it turns an SVG into a VectorDrawable —
DASH does the same thing at parse time rather than build time.

> **The model, not the vehicle.** *(Amended 2026-08-12, after the parser spike. This paragraph
> previously described `ImageVector` as "DASH's rendering target".)* That model is what the subset
> must satisfy, and it is why the list below looks as it does. It is **not** what DASH builds at
> runtime. An `ImageVector` is assembled once and drawn whole, so nothing inside it can be reached —
> and a `rotate` binding on `dial#needle` (§4.5) could then only be honoured by rebuilding the entire
> vector tree on every frame the needle moves, discarding the painter's cache each time, on a surface
> that is persistent, always on screen and driven by live vehicle data.
>
> DASH parses into **its own flat representation instead: named nodes, each holding a path built once
> at parse time and its own accumulated transform.** Bound elements are addressable by construction,
> and a frame costs a matrix and a paint. Verified on the Tab S9 Ultra with a needle sweeping
> continuously — the full 120 Hz panel rate, zero dropped frames, zero missed vsyncs.
>
> The path data itself is handed to Compose's own `PathParser`, the same code Android uses for
> VectorDrawables, so the most intricate part of SVG is inherited rather than reimplemented.

**Elements in:**

`svg` (requires `viewBox`) · `g` · `path` · `rect` · `circle` · `ellipse` · `line` ·
`polygon` · `polyline` · `defs` · `linearGradient` · `radialGradient` · `stop`

**Attributes in:**

`id` · `d` · shape geometry (`x`, `y`, `width`, `height`, `rx`, `ry`, `cx`, `cy`, `r`,
`x1`, `y1`, `x2`, `y2`, `points`) · `fill` · `stroke` · `stroke-width` ·
`stroke-linecap` · `stroke-linejoin` · `fill-rule` · `opacity` · `fill-opacity` ·
`stroke-opacity` · `transform` · `viewBox` · gradient attributes (`offset`, `stop-color`,
`stop-opacity`, `gradientUnits`, `gradientTransform`, `fx`, `fy`, `spreadMethod`)

> **Gradients gained their contents.** *(Amended 2026-08-12, after the parser spike. The lists
> previously permitted `linearGradient` and `radialGradient` while naming nothing a gradient is made
> of — no `stop`, no `offset`, no `stop-color`, no `stop-opacity`, no `gradientUnits`.)* A gradient
> without stops is not a weak gradient, it is nothing, so the omission made the entries meaningless.
> Found by building a parser against the list rather than by reading it.
>
> **`gradientTransform` is in the subset deliberately, and DASH must implement it** (Roger,
> 2026-08-12). Coordinates default to fractions of the shape's own bounding box, but **Inkscape
> almost always writes the other mode** — `userSpaceOnUse`, with a `gradientTransform` beside it
> carrying the rotation and scale. Ignore it and an ordinary rotated gradient renders along the wrong
> axis.
>
> It is the one exclusion that could not have been softened by the *bake it in at export* advice
> everywhere else in this section relies on: a gradient cannot be flattened to paths without
> rasterising it, at which point it has stopped being vector artwork. "Gradients work, but only if
> you never rotate one" is exactly the quiet trap that makes a published subset feel arbitrary, in
> the most likely tool doing the most ordinary thing.

> **`id` is the load-bearing one.** It is what a binding targets (§9). An element without an
> `id` can be drawn but never bound to, and the previewer should say so when a layout
> references something that is not there.
>
> > **The id problem in practice is the opposite one.** *(Amended 2026-08-12, after the parser
> > spike.)* Real tool output rarely contains an element without an id, because **Inkscape names
> > everything whether the author did or not** — an unnamed rectangle comes back as `rect4`, eleven
> > tick marks as `line4`…`line14`. The hazard is not a missing id but one that **exists, means
> > nothing, and changes on the next export**, silently breaking a binding that used to work.
> >
> > So the advice worth giving is *name anything you intend to bind to, in your tool's object
> > properties* — and it must be given **once per document, not once per element**. A file carrying a
> > dozen tool-generated ids would otherwise bury every warning that matters under a list of tick
> > marks.
> >
> > A layout that targets something genuinely absent is still an error, and still reported per
> > occurrence. That case is unchanged.

**Colours:** hex (`#RGB`, `#RRGGBB`), `rgb()`, the standard named colours, and `none`.

**Out:** `text` · `image` · `use` · `symbol` · `filter` · `mask` · `pattern` · `marker` ·
`clipPath` · `foreignObject` · `script` · `style` elements and `class` (CSS) · embedded
fonts · SMIL animation

### Two things an author must know

**1. Convert text to paths before exporting.** Compose's vector model has no text element
at all, so `<text>` has nowhere to land. Every vector tool has the command — Inkscape's
*Object to Path*, Illustrator's *Create Outlines*.

This is also correct rather than merely necessary: **text that never changes is artwork, and
text that changes needs a text layer** (§2). There is no third case, so nothing is lost.

**2. Inline `style` attributes are parsed.** Inkscape writes most presentation as
`style="fill:#ff0000;stroke:none"` rather than as separate attributes. DASH parses that
attribute — **not CSS, simply a semicolon-separated list of the same properties listed
above.** Without it a large share of real Inkscape output would render as black shapes,
which would make the subset look broken on the most likely tool an author will use.

The `<style>` *element*, with CSS selectors and `class` attributes, remains out. That is a
stylesheet engine, and it is not one.

### Theme tokens do not appear in SVG

**Artwork carries literal colours only.** `fill="@accentColourPrimary"` is not valid SVG —
an author's design tool would show it as broken or black, which destroys the entire benefit
of authoring in a real tool.

Delegated colours are expressed as **static `style` bindings in the layout** (§4.3), which
keeps the artwork plain, valid, and previewing correctly everywhere, and keeps every piece
of DASH-specific knowledge in the one file the author is already writing.

> **Considered and rejected:** a `data-dash-fill` attribute alongside a normal `fill`. Valid
> SVG and it would preview correctly, but it depends on the design tool preserving unknown
> attributes through a round trip, it needs the author to open an XML editor, and it splits
> DASH-awareness across two files. The JSON costs one line per themed element instead — the
> same effort, in a better place.

### The subset is data, not prose

The lists above are the human-readable form. **The authoritative form is machine-readable
data, read by both DASH's parser and the previewer** — never two hand-maintained lists.

**That file is `svg-subset.json`, in the repository root beside this document** *(written 2026-08-12;
this section previously described a file that did not yet exist)*. It carries the permitted elements
and attributes, the transform functions, the 147 CSS colour names — Android's own colour parser knows
about fifteen of them — and, for everything excluded, **the reason and the advice as text**. The
messages live in the data too, not just the lists, so that both implementations tell an author the
same thing in the same words. The build copies it into the app's assets, on the same
one-file-in-git discipline as the licence.

This is the previewer's Prime Directive (`panel-preview/CLAUDE.md`) and it exists because a
previewer that disagrees with the app is worse than no previewer at all: it manufactures
confidence where there was healthy doubt. An author with no tool checks their work. An
author with a tool that quietly lies does not.

### Unknown input is skipped, never fatal

**The browser rule.** DASH renders what it understands and ignores what it does not. An
author using something outside the subset gets a panel missing one flourish — never a dead
panel, never an error.

This is what allows the subset to **grow across versions without ever breaking a module
already installed in someone's vehicle**, and it is why a smaller honest subset is safer
for authors than a larger vague one.

*Practical note: most of what the subset excludes is **effects**, and effects can be baked
into the artwork at export — every major vector tool will flatten a filter to paths. A drop
shadow drawn as shapes is still a drop shadow. The honest framing is not "a crippled SVG"
but **effects are flattened at export; geometry stays live** — and geometry is the part that
must stay live, because that is what the module's data drives.*

---

## 10. Deliberately excluded

Recorded so that nobody re-proposes them without knowing they were considered.

- **Image swapping as its own concept.** Two stacked layers with opposing opacity bindings
  does it. No new mechanism required.
- **Radio groups, button groups, mutual exclusion.** A module reports which preset is
  active; DASH colours what it is told. Exclusivity is the module's own logic and never
  enters the format.
- **Templating or repetition.** A four-wheel pressure panel is sixteen near-identical
  bindings and that is acceptable. Explicit and repetitive beats clever and surprising.
- **Reflow between slots.** See §6.
- **Canned animation playback** — a self-contained sequence with its own timeline. The
  expensive one: frame formats, decoding, duration tracking. Nothing designed so far needs
  it, and transitions plus effects (§5) cover most of what people reach to animation for.
  Deferred, not rejected; it can arrive later under the browser rule without breaking
  anything already shipped.
- **Lottie**, in any capacity. Ruled out 2026-08-05: an After Effects animation format
  pressed into service as a drawing system is a forced fit.

---

## Open items

| Item | Blocking? |
|---|---|
| How many repeated action timeouts constitute a fault, and how it surfaces (§8) | No — tune against real hardware |
| ~~Whether `translate` survives to the lock (§4.6)~~ | **Closed 2026-08-14 — it stays** (Roger). The test at a lock is whether a binding was ever proven, not whether something happens to use it today; it was proven at 1.6.6. |
| **Module icons on tabs** — `MANIFEST` carries `blocks` and `bytes` only, so an icon is an SDK change | **Deferred to version 3** (Roger, 2026-10-01). Not added at the lock; tabs carry the `HELLO` name and/or a pip. |
| Whether `style` needs separate `fill` and `stroke` for vector, rather than one `colour` | No — deliberately minimal for now; the browser rule permits adding it later |

**Closed since this document was created:**

| Item | Closed by |
|---|---|
| **How DASH tells the user a module is hidden because it lacks the selected size (§6)** — open since this document was created | **Ruled 2026-08-19 (roadmap 1.6.8) — it doesn't.** §6's existing rule is the whole answer; a count on the panel size selector was proposed and rejected as DASH narrating a choice the user made and can unmake. What a module *shipped* is stated against its record in the Module Manager instead (roadmap 1.6.11). |
| **Implementing `gradientTransform` (§9)** — admitted to the subset 2026-08-12, unimplemented by the spike, so a rotated Inkscape gradient drew along the wrong axis | **Built 2026-08-13 (roadmap 1.6.6).** Control points are taken through the gradient's own transform *before* being resolved against the shape — exact for translation, rotation and uniform scale, which is the case Inkscape actually writes. A non-uniform scale or skew is approximated, because Compose's `Brush` takes control points rather than a matrix and a radius is one number; the parser **warns** when it does so rather than leaving the author to find an elliptical gradient drawn round. |
| **The subset as machine-readable data (§9)** — open since this document was created, and the one item marked *blocking* | **Written 2026-08-12 — `svg-subset.json` at the repository root.** Drafted against a working parser and real Inkscape output rather than from the prose, which is what caught the missing gradient contents and the id warning being backwards. |
| The JSON notation | Drafted 2026-08-06 (§9) |
| Name scoping when two vector layers contain the same element id | The `layer#element` target form (§9) |
| ACCESSORY variables/controls install-declaration framing — open since roadmap 1.4.4 | The layout *is* the declaration (§9) |
| Font family for text layers | Ruled 2026-08-06 — `@font` or a named Android family; a module never ships a typeface (§2) |
| How theme tokens reach vector artwork | Ruled 2026-08-12 — they do not; SVG carries literals, delegation is a static `style` binding (§9) |
| Whether DASH may transform a layout when storing it | Ruled 2026-08-12 — yes; the wire format is not the storage format (§1) |
| What DASH shows when a module lacks the selected slot | Ruled 2026-08-12 — nothing; and with no drawable module, no panel at all (§6) |
| Transitions: module duration vs the user's INSTANT–CINEMATIC setting | Ruled 2026-08-12 — the module's, always; DASH's setting never enters a panel (§5) |
| Asset size caps — open since roadmap 1.4.4 | Ruled 2026-08-12 — there are none; DASH advises and degrades gracefully instead (§2) |
| Authoring resolution for raster assets | Ruled 2026-08-12 — the author's call, with the arithmetic published as advice (§2) |
| Whether DASH scales a layout into a slot it was not drawn for | Ruled 2026-08-06 — it never does (§6) |

---

## Where this came from

The model in this document was designed with Roger on **2026-08-05 and 2026-08-06**, and
tested against **three real panel concepts he drew for his own vehicles**: a single-zone
climate control, a four-wheel tyre pressure and temperature monitor, and an air-ride
suspension panel with ride height and presets.

Designing against real panels rather than in the abstract changed the outcome repeatedly.
The climate panel showed that the dominant behaviour is **colour change**, not image
swapping, and that a single element commonly carries two bindings. The pressure monitor
showed that proportional fill is a **clip, not a scale**, and forced number formatting into
the specification. The air-ride panel produced the only rotation in three designs, and its
presets demonstrated that **mutual exclusion never needs to exist in the format at all**.

None of those findings would have survived a specification written from first principles,
and two of them contradicted assumptions that had been in `arduino.md` §11 for months.

*The panels themselves are concepts, not commitments. They exist here as evidence of what
the format must be able to express — and as an indication of what a community author,
building something entirely different, will need the same tools to do.*

---

*This document is the authoritative panel specification. It locked at roadmap 1.6.10
(2026-10-01). Read `arduino/arduino.md` for the reasoning behind these decisions, `module-sdk.md`
for how a module talks to DASH, and `panel-preview/CLAUDE.md` for the tool that checks a
layout against this spec.*
