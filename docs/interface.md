# DASH — Interface Architecture & Design

---

## The DASH Ethos

DASH gets out of the way.

The platform provides the rules, the tools, and the foundation. What gets built on top of that foundation belongs entirely to the user. Their car, their screen, their modules, their layout, their colours, their elements, their overlays, their experience. DASH has no opinion about what that should look like.

This principle governs every interface decision documented here. When a developer is deciding whether to constrain something or leave it open, this statement is the answer. The user is the master of their own system. Always.

---

## Architectural Overview

The DASH interface is built from three independent layers that work together without coupling to each other.

**The Transport Layer** moves data in and out of DASH. Defined in transport.md.

**The Signal and Trigger Layer** routes incoming data to the correct destination — standard signal slots, system message handlers, module panels, overlay triggers. It is the nervous system of DASH.

**The Rendering Layer** presents everything the user sees. Elements, overlays, module panels, the viewport. Defined in this document.

Nothing in the rendering layer needs to know how data arrived. Nothing in the transport layer needs to know how data is displayed. The layers are cleanly separated. This separation is what makes DASH genuinely hardware agnostic and infinitely extensible.

---

## DASH as a System Launcher

DASH declares itself as the Android system launcher. It registers as the default HOME application. From the moment the user sets DASH as their default launcher, every home action — home button press, back-to-home gesture, cold boot — brings the user to DASH.

Android protects the active launcher from memory management. DASH is always running. Module connections, transport listeners, signal monitors, and background services remain active continuously without risk of the system killing them.

**There is no traditional home screen.** The viewport always displays an application. The system never goes black. A default startup app — typically a navigation or dashboard app — is displayed when no other app is foregrounded. The user returns to that default by selecting it from the launcher, not by pressing a home button in the traditional sense.

---

## Power and Wake Behaviour

DASH is designed for continuous power from the vehicle battery. The normal operational cycle is screen sleep and screen wake — not system shutdown and cold boot.

**Normal cycle:**
- Ignition on → SYSTEM:ignition:on received → screen wakes → splash screen plays → DASH foregrounds
- Ignition off → SYSTEM:ignition:off received → screen sleeps → system continues running in background

**Cold boot** occurs only on first installation, system update, or manual reboot. On cold boot DASH is the first thing seen after Android initialises. The splash screen plays before the main interface appears.

**Splash screen** plays on both cold boot and screen wake from ignition. It is user-definable in Appearance settings — custom image, colour, or the default DASH branding. Duration is user-configurable.

---

## Display Density and UI Scale

Density and scale are two independent systems. They affect each other indirectly but are configured and applied separately.

### App Density

Android's system-wide display density controls how all applications render on screen. Increasing density makes everything larger — text, buttons, touch targets. Decreasing density fits more information on screen.

DASH presents density as named presets rather than a raw DPI value. The presets map to Android's standard density buckets:

| Preset | Density | Character |
|--------|---------|-----------|
| Compact | mdpi | Maximum information, smaller elements |
| Normal | hdpi | Balanced default |
| Comfortable | xhdpi | Larger elements, easier reading |
| Large | xxhdpi | Maximum size, closest viewing distance |

The user sets density once to make third party apps render correctly for their screen and viewing distance. This is the foundation. Everything else is built on top of it.

Density is configured in Appearance → Density.

### DASH UI Scale

DASH UI scale controls the size of DASH's own interface elements — the system bar, panels, zones, and elements — independently of system density. Changing density does not affect DASH chrome. Changing DASH scale does not affect third party apps.

DASH UI scale is a fluid, stepwise system. The user adjusts it with plus and minus controls in increments of 0.1x — for example 0.8x, 0.9x, 1.0x, 1.1x, 1.2x. There is no fixed set of named presets. The user dials the scale to exactly what works for their screen size and viewing distance. 1.0x is the default balanced starting point.

DASH overrides Android's density system for its own windows, ensuring the two controls remain cleanly separated and predictable. The user changes density — only apps respond. The user changes DASH scale — only DASH chrome responds.

UI scale is configured in Appearance → Density.

> **Reconciliation — 2026-07-22 (roadmap 1.5.3).** Building the Size & Scale tab, the "DASH UI Scale" model above was reworked and is **superseded for version 1**; the original is kept for the record. The single fluid global multiplier gave way to **per-surface sizing plus a DASH-owned text size**, agreed with Roger:
>
> - **Each DASH surface owns its own size.** The system bar and its elements are sized by their own ± steppers (writing `SystemBarConfig`); the app-favourites bar gains its own (1.8.x); the module panel is fixed. There is no single global "scale everything" knob — surfaces are dialled individually, which is what actually holds up across a phone, a tablet and a head unit. (Density is handled by `dp`; screen *size* and aspect ratio are handled per surface.)
> - **DASH text size is DASH's own.** A `dashTextScale` is applied at the composition root as a `fontScale` override, so all DASH chrome text follows the DASH text-size stepper and **ignores Android's font setting entirely.** Android's font size — and its density — are left for the viewport apps. This is the clean separation the two-systems split above was reaching for: Android's knobs are the apps'; DASH owns all of its own sizing.
> - **The tab is "Size & Scale"**, two headed sections — **DASH Scale** (bar size, element size, app-favourites-bar size, DASH text size) and **Android** (app density + font size, capability-gated: native controls mirroring Android's own display-size page where DASH has the privilege, an honest link out where it doesn't).
> - *Outstanding:* only *text* is taken off Android so far — DASH's `dp` still follows the system density until the dp-renormalisation step lands, so app density still moves DASH chrome until then.

---

## The System Bar

The system bar is the only persistent interface element in DASH. It is always visible, always on screen, and cannot be hidden or dismissed under any circumstances.

**Position:** Top or bottom of the screen only. Never left or right edges — those are reserved for panels.

**Two elements are non-negotiable and must always be present on the system bar:**

1. **The Alerts Area** — vehicle and module alerts must always be visible. This is a safety consideration. The user must always be able to see what their vehicle and modules are reporting.

2. **The Settings Button** — the only way to reconfigure DASH. If it could be removed or hidden, the user could configure themselves into an unrecoverable state. The settings button has a hard minimum touch target of 48dp that is enforced silently regardless of bar scale. The visible icon may shrink with the bar but the interactive area never goes below 48dp.

Where these two mandatory elements are positioned within the bar is user-configurable. Their presence is not.

### System Bar Height

The system bar has a user-defined height. Height is the master measurement from which all element sizing within the bar is derived.

**Sizing rules:**
- Every element has a maximum size expressed as a percentage of bar height. As the bar grows, all element ceilings rise proportionally.
- Every element has a minimum size expressed as a percentage of bar height. Soft limits with amber warnings apply when elements approach the minimum.
- Element size is independent of bar scale. Making the bar taller does not automatically make elements bigger. The user sets element size separately within the new ceiling.
- Elements can never exceed the bar height vertically. This is a hard ceiling enforced silently.

> **Design decision — v1.3.4:** The percentage model above was reconsidered during implementation and replaced with a direct dp control. The reason is that percentages structurally contradict the independence rule stated above. If an element is set to 80% of bar height and the bar grows from 56dp to 80dp, the element grows from 45dp to 64dp — even though the user only changed the bar. That is not independence. A dp value is genuinely independent: the bar changes, the element stays exactly where the user put it, the ceiling simply moves. The user's two decisions — how tall is the bar, how tall are the elements — remain cleanly separate. There is a second reason: SDK element authors need to know concretely what size they are drawing into. A dp value in `ElementScope` is unambiguous. A percentage relative to a runtime bar height is not something a developer can design against meaningfully. The dp model is honest about what the element is receiving. The ceiling rule remains intact: element height is capped at one dp step below bar height, enforced through the control itself.

### Zone System

The system bar is divided into between one and three user-defined zones. Zones are the structural containers within which elements are placed.

**Rules:**
- Default state is one zone spanning the full width of the bar
- The user may add a second or third zone by splitting the bar
- Three zones is the hard maximum
- Zones always span the full width of the system bar — they cannot be inset from the edges
- Zone boundaries are draggable dividers in edit mode
- Zone dividers snap to sensible positions — quarter bar, third bar, half bar
- Each zone has a minimum width sufficient to hold at least one element at minimum size

### Element Positioning Within Zones

Within each zone, elements snap to defined anchor points:
- Left edge of zone
- Centre of zone
- Right edge of zone
- Left edge of an existing element within the zone
- Right edge of an existing element within the zone

These are the only valid snap positions. No freeform pixel positioning.

**Multiple elements in one zone** pack against each other or against zone edges according to where they are snapped. Elements within a zone are aware of each other and never overlap.

**Overflow** is prevented — if elements exceed zone width DASH shows a soft warning in edit mode. Elements cannot spill into adjacent zones.

**Spacer element** — a special invisible element with a user-defined width that occupies space without displaying content. Used to create breathing room between elements or to pull elements away from screen edges. The spacer is the mechanism for all spacing control within zones.

**Vertical positioning** — elements are centred vertically within the bar by default. The user can offset the vertical anchor point of all elements up or down within the bar. Expressed as a percentage of bar height from the top edge. Useful for accommodating camera notches or personal preference. Soft limits prevent elements clipping the bar edges.

### Edit Mode

Element positioning only occurs in a dedicated edit mode accessed from the Appearance settings. During normal operation the bar is completely locked — nothing moves accidentally.

In edit mode:
- The bar displays a visual state change indicating it is editable
- Elements can be long pressed, picked up, and dragged
- Snap guidelines appear as elements approach snap points
- Zone dividers become draggable
- Elements animate to show the result of placement before the user commits

> **Design decision — v1.3.7:** The direct-bar-drag model described above was reconsidered in a design review before implementation and replaced with a ruler-based interaction model. The principles of the new approach are as follows.
>
> **The bar is never touched during editing.** All interaction is relocated to a ruler strip that appears adjacent to the bar when edit mode is active — below the bar if it is top-docked, above it if it is bottom-docked, always separated by a finger-width gap. The user's hand never occludes the thing they are adjusting. This is the core structural departure from the original model.
>
> **The ruler has a fixed, predictable position.** It is always on the inner side of the bar — between the bar and the main content area — at a consistent gap regardless of bar position or height. The user always knows where it is without looking for it.
>
> **The ruler has a consistent visual language.** Zone dividers are represented as arrow markers pointing back toward their position on the bar. Elements are represented as footprint-sized boxes matching each element's actual physical width in the bar — these boxes are visible only in edit mode and have no persistent visual boundary in normal operation. There is one colour convention throughout the ruler: red tint means bound or settled. An element box shows red on whichever edge is currently bound to a zone edge or an adjacent element's edge. A divider arrow turns red when settled exactly at a snap point — not merely near one. No text-based position labelling is used; the colour is the signal.
>
> **Edit-mode signalling is unambiguous but not duplicated.** The ruler's presence is itself a clear, unambiguous signal that edit mode is active. A second signal — the whole-bar border and tint introduced in 1.3.6 — would be repeating the same information across two different channels. It is removed. Colour in the ruler is reserved for meaningful per-marker state (bound, settled) rather than general mode indication.
>
> **The main content area becomes the edit workspace.** Save and Cancel actions occupy the main content area during editing — the same area normally occupied by the running app. This reuses the existing pattern where a focused task takes over the full screen, as already established by the settings panel. On entering edit mode, the current bar configuration is snapshotted. Save commits the edited state to DataStore. Cancel discards all changes and restores the snapshot exactly, with no write.

---

## Elements

Elements are self-contained UI components that live inside zones on the system bar. They are the building blocks of the DASH interface chrome.

### What an Element Is

An element has a single well-defined purpose. It may display information, accept user interaction, or both. It is self-contained — it knows what it does, it knows how to present itself at each of its size variants, and it handles its own logic internally.

### Element Size Variants

Every element defines multiple size variants. The user selects the variant that suits their available zone space and personal preference. Variants are typically Small, Medium, and Large, though individual elements may define their own variant names appropriate to their function.

**Example — Now Playing element:**
- Small: Album art thumbnail and play/pause button only
- Medium: Adds track name and artist
- Large: Adds progress bar and full transport controls

### Element Appearance

Elements receive their colours and fonts from the active DASH theme tokens. An element never defines its own independent colour scheme. This ensures visual consistency across all elements regardless of who built them. A community-built element automatically looks coherent within the user's chosen theme.

### Built-in Elements

DASH ships with the following built-in elements:

| Element | Type | Description |
|---------|------|-------------|
| Alerts Area | Informational | Vehicle and module alerts. Mandatory. |
| Settings Button | Interactive | Opens the settings panel. Mandatory. |
| Now Playing | Both | Current media track, album art, transport controls |
| Clock and Date | Informational | Time display with optional date. Multiple format options. |
| Volume | Both | Current volume level with tap-to-adjust overlay trigger |
| Connectivity Status | Informational | WiFi, Bluetooth, cellular signal indicators |
| Back Button | Interactive | Android back navigation. Optional. |
| App Launcher | Interactive | Reveals the app launcher tray |
| Module Panel Reveal | Interactive | Reveals the floating module panel |
| Spacer | Layout | Invisible width element for spacing control |

> **Amendment — 2026-07-30 (roadmap 1.6.1).** **Module Panel Reveal is dropped from the built-in element library.** The row above is kept for the record. Hide-and-reveal is *panel behaviour* — the peek strip and the swipe that pulls the panel out — and it is built as part of the module panel at **1.6.9**, not as a system bar element. The row predates that decision. See the Module Panel section for the full note.

### User-Defined Elements — The Element SDK

DASH provides an Element SDK allowing developers and users to build their own elements. A custom element is a self-contained component that follows the DASH element specification. When placed in the elements folder it appears in the element library automatically alongside built-in elements.

**An element package contains:**
- A declaration file stating name, description, version, and size variants
- Compose implementation for each size variant
- An icon for the element library
- A declaration of any DASH data sources or theme tokens it consumes

**The element specification requires:**
- Declaration of all supported size variants
- Consumption of theme tokens for all colours and fonts — no hardcoded values
- Respect for the bar height ceiling — no element may exceed bar height vertically
- Declaration of whether the element is informational, interactive, or both

Elements that do not conform to the specification are skipped on load with a log entry. They do not crash DASH or affect other elements.

**Architectural principle:** New DASH interface features do not require modifying the core platform. They are written as elements and dropped into the elements folder. The core platform remains stable while the interface evolves through the element system.

> **Design decision — v1.3.13:** The element sizing contract was finalised before more elements were built against an earlier, looser model. The settled contract works as follows.
>
> **Height is invisible to element authors.** It is not passed as a parameter, not queryable, not declarable, and not present anywhere in the API surface an element author interacts with. DASH determines height from the global element height setting and applies it by constraining the space given to the element before that element's own composable code runs. The element sees "here is a box" — the box happens to be a specific size, but the element neither knows nor cares what that size is.
>
> **Width derives from natural aspect ratio, not from negotiation.** Each element has a natural aspect ratio, defined purely by how its author designed its appearance — the same way a photograph has its own shape. Given the imposed height, DASH computes the corresponding width automatically by scaling the design proportionally. The element does not express a preferred width, request available space, or participate in the calculation. The width is a consequence of the height and the design, nothing more.
>
> **Elements are rigid by default.** At any given height an element has exactly one natural size. It either fits in the remaining zone space or it does not. If it does not fit, DASH refuses placement and shows the zone-overflow soft warning already specified in the Zone System section. DASH never compresses, distorts, clips, or overlaps elements to force a fit — that outcome is always a layout error, not an acceptable fallback. Making room is the user's responsibility, by resizing the bar, rearranging elements, or removing something. As a documented future direction, not part of the current contract: an element author may later opt into making their element compressible, defining multiple distinct presentations for different amounts of available space. This is deferred until a real element complex enough to warrant it is built, which aligns with the additional elements planned for version 2.
>
> **Bar height and element height are single global values.** Bar height is the height of the one bar. Element height applies uniformly to every element on the bar, regardless of which zone they occupy or who built them. There is no per-element height setting, by design. Visual consistency across the bar is structural and cannot be violated by individual elements, because height is not something an element can influence.
>
> **Only two elements are native to DASH.** The Alerts Area and the Settings Button are the only elements that exist outside the SDK pathway — they are mandatory, they are built into the platform, and they are the reason the system bar is useful as a safety and access mechanism. Every other element, including the Spacer when it is built, is an SDK element by design: it uses the same contract any third-party developer would use, it goes through the same ElementRegistry, and it receives no special access to DASH internals. DASH shipping an element by default does not make that element native. Only the mandatory two are native.

---

## Theme Tokens

DASH uses a named colour token system. Every component that renders visual DASH chrome — the system bar, the edit mode ruler, elements, overlays, and the settings panel — reads colours from the active token set rather than using hardcoded values. This is the mechanism that makes version 2 theming possible: swapping the token set at the top of the composition tree changes the appearance of everything at once, with no changes to any individual component.

The token set is defined in `DashColors` and provided via `LocalDashTheme`. One default set exists in version 1. Version 2 introduces user-selectable presets and a custom colour editor — both work by providing a different `DashColors` instance; the components themselves do not change.

### Token Reference

| Token | Default | Used For |
|-------|---------|----------|
| `barBackground` | `0xFF1A1A2E` | System bar fill. The foundation colour everything else sits on. Also the implied background for the settings panel and any DASH-native surfaces that inherit from the bar. |
| `barAccent` | `0xFF26263F` | Subtle structural separators within the bar — zone divider lines in the bar itself. Deliberately close to `barBackground` so it reads as a hint rather than a feature. |
| `barAccent2` | `0xFF7878A0` | Visible interactive structure — edit mode ruler track line, zone divider lines in the ruler, detent markers, element box outlines, and the divider arrow resting state. Sits between the invisible-dark `barAccent` and the content-bright `barText`. Used wherever a structural element needs to be clearly readable against the bar background without competing with content. |
| `barText` | `0xFFB0B0C8` | Foreground content — text, icons, and any element content that must be readable on `barBackground`. The brightest of the four tokens. |

### Adding Tokens

When a new component requires a colour that does not fit an existing token semantically, add a new token to `DashColors` with a sensible default and a clear name. Do not reuse an existing token for a purpose it was not intended for. Do not hardcode a colour value in a component. The token set is the single source of truth for all DASH chrome colours — keeping it complete and accurate is what makes future theming viable.

> **Token set update — 2026-07-20 (roadmap 1.5.2).** Building the settings panel, the token set above was reconciled and extended, and `DashColors` renamed to **`DashTheme`** — it now carries a **font** as well as colours (all still provided via `LocalDashTheme`). The original four `bar*` colour tokens are **retired**; their roles are absorbed into a fuller, cleanly-named set with the same defaults-flipped-to-light values 1.3.11 introduced. Nothing about the mechanism changes: one default set, and version 2 presets still work by providing a different `DashTheme` at the top of the tree.
>
> **The 1.5.2 token set:**
>
> | Token | Default | Role |
> |-------|---------|------|
> | `backgroundColourPrimary` | `0xFFE5E5EA` | Primary surface — system bar fill **and** the settings-panel background (shared, so the panel grows seamlessly from the bar). Was `barBackground`. |
> | `backgroundColourSecondary` | `0xFF848482` | Raised surface on the primary — the settings content box. |
> | `textColourPrimary` | `0xFF000000` | Text on the primary surface. Absorbs the old `barText`. |
> | `textColourSecondary` | `0xFFE5E5EA` | Text on the secondary surface. |
> | `iconColourPrimary` | `0xFF000000` | Icons on the primary surface — e.g. the settings-button gear. |
> | `iconColourSecondary` | `0xFFE5E5EA` | Icons on the secondary surface. |
> | `accentColourPrimary` | `0xFFD1D1D6` | Subtle structure on the primary — bar zone dividers, nav-row selection. Was `barAccent`. |
> | `accentColourSecondary` | `0xFF8E8E93` | Visible interactive structure — edit-ruler track, detents, element outlines. Was `barAccent2`. |
> | `font` | Monospace | The one typeface for all DASH chrome text. A single setting (the v2 font picker) changes every label at once — no DASH text hardcodes a font. Font *size* is a separate axis and, for elements, belongs to the element author, not DASH. |
>
> **The pairing rule (must not be crossed):** the colours come in matched primary/secondary pairs. Primary text/icons sit on the primary background; secondary text/icons sit on the secondary background. Secondary text on the primary background — both currently the same light grey — would be invisible. Two full sets exist deliberately, so a surface and its content always have contrast and the user keeps control of both.

---

## The Viewport

The viewport is the dedicated display area for all Android applications. Every app that runs on DASH — Google Maps, Spotify, YouTube, any installed Android app — opens and displays fully within the viewport boundaries.

The viewport is the space remaining after all persistent bars and panels have claimed their portions of the screen. Its size is determined entirely by the user's layout configuration.

**Rules:**
- Apps always fill the viewport completely — DASH never letterboxes or pillarboxes
- The viewport is the app's entire world — everything outside it is DASH's domain
- Apps receive correct inset information so interactive content respects bar areas even in Passive mode
- The viewport corner style matches the active viewport mode

### Viewport Modes

Three viewport modes define the visual relationship between the viewport and the surrounding DASH interface. The mode is selected in Appearance → Viewport and forms part of the preset system.

**Flush**
The default. All bars, panels, and the viewport share square edges and sit flush against each other and the screen edges. No rounding, no overlap, no transparency. Everything occupies its own defined rectangle. Nothing bleeds into anything else. Clean, functional, immediately legible.

**Dominant**
The viewport appears to float above the surrounding interface. The viewport has rounded corners and a subtle elevation shadow. The interface chrome recedes visually as a background layer. The app content is the hero of the screen. Bold and modern in character.

**Passive**
The viewport extends underneath floating bars and panels which float above it as an overlay layer. Apps bleed edge to edge or close to it. Bars use semi-transparent or frosted glass backgrounds, hinting at the content beneath. The interface feels minimal and immersive. Maximum visual real estate for the app. Frosted glass requires Android 12 or above — older versions receive a semi-transparent colour approximation.

---

## The Module Panel

The module panel is the display area for installed accessory modules. DASH draws the container. The module fills it entirely. The module is king within its own domain — background colour, fonts, layout, controls, and all visual content within the panel boundary are defined by the module exclusively. DASH does not alter, override, or offer settings for module panel content.

> **No drawable module, no panel — 2026-08-12 (roadmap 1.6.x).** The panel is drawn **only when an installed module has a layout for the currently selected size.** With none — nothing installed, or nothing providing that size — **the module panel is not drawn at all**, and the screen reclaims the space. This **supersedes the empty-box tenancy from 1.6.2** (*"DASH occupying the empty box is the one tenancy it is entitled to"*): it is not entitled to it. Reserving screen for content that does not exist is the same fault as stretching artwork to fill a shape it was not drawn for. A module lacking the selected size is simply not shown, and DASH never substitutes or scales another size in its place. See `module-layout.md` §6. *(Not yet built.)*

> **The contents of the panel are specified in `module-layout.md`.** *(Pointer added 2026-08-06, roadmap 1.6.x.)* That document is the authority on everything **inside** the boundary — layer types, targets, bindings, the twelve slots, theme tokens and the SVG subset. This document owns the boundary itself: where the panel docks, what size it is, how it relates to the system bar, how it is revealed. **The wall is described here. What the king does inside it is described there.** Where the two disagree about panel contents, `module-layout.md` wins.

### Docking

The module panel docks to any of the four screen edges — top, bottom, left, or right.

**Orientation is automatic based on docking edge:**
- Top or bottom edge → horizontal orientation → h_ layout slots used
- Left or right edge → vertical orientation → v_ layout slots used

The user can override the automatic orientation if preferred.

> **Superseded — 2026-08-06.** The slot suffixes are **`_day` / `_night`**, not `_light` / `_dark` — the switch is Ambient, genuinely day and night in a vehicle, rather than a theme's lightness, and a user may perfectly well run a dark DASH theme in daylight. See `module-layout.md` §6. *(The automatic-orientation rule above is also superseded — see the 2026-07-30 amendment further down, which carries the full reasoning.)*

### Panel Sizes

Three sizes defined relative to system bar height:
- **Small** — 1× system bar height
- **Medium** — 2× system bar height
- **Large** — 4× system bar height

### System Bar Relationship

The module panel never overwrites or overruns the system bar. If the system bar is at the top and the module panel is docked to the right, the module panel's top edge begins where the system bar ends. The system bar is always the senior element.

### Persistent and Floating Modes

**Persistent** — the panel is always visible and always occupies its defined screen space.

**Floating** — the panel is hidden by default. A peek strip — a few dp of the panel edge — remains visible at the docking edge, acting as a visual handle and swipe target. The user swipes inward from the peek strip to reveal the full panel. The panel tracks the swipe gesture in real time, feeling physically pulled from behind the screen edge.

The Module Panel Reveal element on the system bar provides an alternative reveal mechanism. Tapping it reveals the panel. Tapping again cycles through installed modules. This is optional — the user places it on the system bar if they want it.

> **Superseded — 2026-08-27 (roadmap 1.6.9). Persistent and Floating are replaced by four visibility states.** The two paragraphs above are kept for the record. *(Roger.)*
>
> **The panel has one setting with four values, and the settings page is built from it:**
>
> - **Off** — no panel at all, and therefore no tab bar. **This is the default on a fresh install.** DASH has no opinion about how much of somebody's screen a head unit should spend, so it spends none until asked — which also retires the question of what a default *size* would be, since there is no default panel to size. It is also a capability the user did not previously have: the size tiles had no off state, so installing an ACCESSORY module got you a panel whether you wanted one or not.
> - **Full** — always drawn at its size. This is the 1.6.2–1.6.8 behaviour, and what "Persistent" meant above.
> - **Retracted** — rests off screen behind its own edge. A tap on the tab bar draws it out; a timer folds it back. This is what "Floating" meant above, with the peek strip now named as what it is: the tab bar.
> - **Shrunk** — rests at a *smaller layout the module's author drew*, and expands to full on a tap. New at 1.6.9 and the reason the model needed four values rather than two.
>
> **Shrinking is the feature; compacting is the mitigation.** They are deliberately different words. Shrinking is a panel resting at a smaller authored layout on purpose. **Compacting** is DASH scaling a panel that asked for more screen than exists, so a bad choice degrades instead of drawing wrong.
>
> **The viewport is laid out for the resting state and never for the expanded one.** An expanded panel is drawn **over** the viewport rather than pushing it, so the running app never relayouts when the panel opens. *Why it matters:* **the user pays for the state the panel is usually in, not the state it is briefly in.** A Large panel resting off screen costs the viewport nothing but the tab bar and covers the screen only while it is being looked at — which is what makes a large panel reasonable on a device where a permanent one never was.
>
> **The tab bar is the reveal control, and it rides inward with the panel** as it expands, staying on the panel's inboard face per 1.6.8. **A tap means: show me this module, at the largest slot it can fill.** Tapping the module already on screen folds the panel back by hand rather than waiting out the timer.
>
> **The bar lists the modules that can fill any slot the panel can reach — resting or full.** Stated once because it is the same invariant three separate rules needed: **the bar lists what you can go to, and is never filtered by what happens to be on screen.**
>
> **Any touch inside the panel restarts the timer, and observing a touch is not taking it.** A panel that folded shut under a finger mid-adjustment would be a fault. 1.6.8's rule that every gesture inside the boundary belongs to the module is untouched — DASH learns that a finger landed and consumes nothing.
>
> **At rest, a module with no resting layout is substituted, and the substitution is never stored.** A module shipping only a large layout cannot be what rests in a small band, so the first module that *can* is drawn there. **The user chooses the resting module by choosing which module ships the resting layout** — however many modules are installed, the one that ships the small layout is the one that rests. What DASH remembers the user chose is written only on an actual tap, so it survives the rest and survives a restart. This is the third place DASH displaces without ever rewriting a preference, alongside `effectiveEdge` for position and the settings yield for size.

### Stacking and Floating Rules

**If both the module panel and app launcher are persistent** they may share the same edge and stack. On a bottom edge the app launcher sits above the module panel, closer to the content area.

**If either the module panel or app launcher is floating** they must occupy different edges. Two floating panels on the same edge creates an ambiguous swipe interaction that cannot be resolved reliably.

### Switching Between Modules

Installed modules are cycled by swiping within the panel:
- Horizontal panel → swipe left and right
- Vertical panel → swipe up and down

> **Superseded — 2026-08-19 (roadmap 1.6.8), Roger's call. The swipe is dropped. Switching is the tab bar.**
>
> The three lines above are kept for the record and are not what DASH does. **The swipe belongs to
> the module, not to DASH.**
>
> *Why:* everything inside the panel boundary is the module's box under the Module Mantra, and a
> single-finger gesture inside it is the module's input to claim — hold-to-repeat on a stepper, a
> drag slider, a swipe through a list of presets. None of those exist in `module-layout.md` today;
> all of them are things an author will reasonably want. **Once DASH takes that gesture it cannot
> give it back** without breaking every module built in the meantime, so it is not taken at all.
> This is the same reasoning that keeps DASH out of the panel's colours and fonts, applied to input.
>
> The cost is real and was accepted knowingly: the in-panel swipe is free, and what replaces it
> spends screen permanently.
>
> **What replaces it: a tab bar on the panel's inboard edge** — the side facing the content area.
> DASH owns the walls; this makes one wall thick enough to touch. **One tab per module that can
> fill the currently selected slot, and tapping a tab goes straight to that module** rather than
> cycling — six modules are one tap apart, not five. It is also where the *which module am I on*
> indicator honestly lives, because a pip drawn inside the panel would be DASH chrome in the
> module's box. Tab labels come from the module's `HELLO` name, which the module already declares.
>
> **The tab bar and the floating peek strip are the same object.** The strip described under
> Persistent and Floating Modes above — the few dp left on screen when a floating panel is hidden —
> *is* the bar. It is built as the switcher at **1.6.8** and gains its reveal behaviour, its size and
> its customisation at **1.6.9**, where both of its jobs are settled together rather than the same
> strip being designed twice.
>
> **The bar is DASH's surface, so the user dresses it.** Thickness (bound to the UI Scale token),
> tab style, alignment along the edge, and colour from the theme tokens — all at 1.6.9. Whether it
> can be hidden entirely carries 1.6.9's own trap: hiding the only switch a user has strands them on
> one module with no way off it, which is the reachability rule that version exists for, applied to
> the bar rather than to the panel.
>
> **A second route in, which needs no screen at all:** `module_panel_left` and `module_panel_right`
> are now standard system commands (`system_commands.md`, Controls). A SYSTEM module reports them
> and DASH moves one module along the order — a steering-wheel button or a dash switch, which is
> the best switch there is in a moving vehicle because it needs no eyes. **Left and right are the
> direction of travel through the module order, not a screen direction**, so a vertical panel does
> not get `up`/`down` twins.
>
> **This also supersedes one line of the 2026-07-30 reconciliation below**, which said *"cycling
> between installed modules is already the in-panel swipe described above"* when it dropped the
> Module Panel Reveal element. That element stays dropped and for the reason given — it is not an
> element's job to operate another surface — but the behaviour it was said to duplicate is now the
> tab bar. If a bar-mounted control is ever wanted in the Elements era (1.9.x), it drives the tab
> bar, exactly as that note intended.
>
> **Where the bar physically goes (roadmap 1.6.8, Roger's call): outside the panel, cutting into the
> viewport.** Not out of the panel's own footprint — the module's box would then be thinner than the
> slot ratio it was authored to, and a module drawn for 8 × 3 must be drawn into 8 × 3. The panel
> keeps its exact shape; the cost lands on content area. **The bar shows whenever the panel shows,
> including with only one module installed**, so the viewport is the same size on Monday as on
> Friday — installing a module changes what is *in* the panel, not how much content area the screen
> has. A single tab is a label rather than a dead control, so 1.5.15's no-dead-controls rule does not
> object. With no module able to fill the slot there is no panel at all (§6), and therefore no bar.
>
> **Which modules get a tab:** installed ACCESSORY, has a layout for the selected slot. SYSTEM and
> LISTENER modules never appear — they have no panel to draw. **A silent module keeps its tab**: the
> install record is the tenancy, §6 is a rule about layouts rather than liveness, and DASH does not
> annotate a tab with the state of its board. **Different id, different module** — the same firmware
> on two wires is two modules and gets two tabs, and DASH neither disambiguates them nor comments on
> it.
>
> **The switch is a cross-fade**, with the outgoing panel held until the incoming one is ready so
> there is never an empty box. Not a slide: tapping a tab can jump from the first module to the
> fourth, so there is nothing meaningful to slide past. It is **DASH's** transition and takes the
> user's INSTANT–CINEMATIC setting, unlike anything inside a panel.
>
> **Also settled here (roadmap 1.6.8):** there is **no tab ordering in this version** — tabs sit in
> database order, and the panel order, the dominant module and the return dwell are one story told
> once at 1.6.11 rather than half-built here. Which module was last shown **survives a restart**,
> because DASH restarts every ignition cycle. And a module that cannot fill the selected size is
> simply absent from the bar, per `module-layout.md` §6 — **and DASH says nothing about it**
> (Roger). The rule is the whole answer; annotating the size tiles with a module count was proposed
> and rejected, because it would be DASH narrating a choice the user made and can unmake by changing
> the size back. What a module *did* ship is a different thing and is stated plainly against its
> record in the Module Manager, at 1.6.11.

> **Reconciliation — 2026-07-30 (roadmap 1.6.1).** Ahead of building the module panel, this section was walked against the 1.6.x plan and three things settled differently. The text above is kept for the record; the notes below are what 1.6.x implements.
>
> **1. Orientation is manual, not automatic.** The "orientation is automatic based on docking edge" rule above is **superseded**. There is no automatic flip, and therefore nothing to override — **the user sets the panel's orientation directly**, as its own setting, independently of which edge it is docked to. *Why:* deriving orientation from the docked edge is DASH deciding on the user's behalf what shape their panel should be, which is the one thing DASH does not do. A vertical panel on a bottom edge is a legitimate thing to want, and the automatic rule made it a fight with the system rather than a choice. The h_ / v_ layout slots are unaffected — a module still ships both variants; **which one DASH draws follows the panel's orientation setting**, not its docked edge.
>
> **Settings consequence:** `Layout › Module Panel` gains an **Orientation** control (horizontal / vertical) alongside docking edge, mode, peek strip size and default size.
>
> **2. The Module Panel Reveal element is dropped.** The paragraph above describing it as an alternative reveal mechanism is **superseded**, along with its row in the built-in element table. Reveal is panel behaviour, built with the panel at **1.6.9** — the peek strip and the real-time swipe. Cycling between installed modules is already the in-panel swipe described above. *Why:* it is not an element's job to operate another surface; it duplicated behaviour the panel already owns. If a bar-mounted reveal is ever wanted it can be added in the Elements era (1.9.x) as a genuine element, built on the panel behaviour rather than instead of it.
>
> **3. Stacking rule enforcement moves to 1.8.x.** The Stacking and Floating Rules above still stand as written and are unchanged — but they govern the floating module panel **against the floating app launcher**, and the launcher does not exist until **1.8.x**. The rule cannot be enforced against something that isn't built, so enforcement ships with the launcher. Until then the module panel is the only floating surface and the conflict cannot arise.

> **Build note — 2026-07-30 (roadmap 1.6.2).** The panel was built for the first time. Three things settled in the doing, recorded here per the additive-docs rule.
>
> **1. The panel and the system bar never share an edge.** This **supersedes the System Bar Relationship section above**, which had them meeting — the panel's edge beginning where the bar's ends. They now cannot meet at all: the two never occupy the same edge and never stack. If the user moves the bar onto the edge the panel holds, **DASH moves the panel out of their way** rather than making them resolve it. *Why (Roger):* the bar can move, so a rule that merely says "don't overrun the bar" leaves the user to discover the collision and fix it themselves. Getting out of the way means DASH absorbs it. The bar remains senior — it is the panel that yields.
>
> At 1.6.2 this resolves to **the panel taking the edge opposite the bar**, which covers every case while docking is still a version away and only two bar positions exist. What happens once the user has *chosen* an edge and the bar then lands on it — whether their choice is remembered and restored when the bar moves away — is settled at **1.6.3** with docking.
>
> **2. The large slot has a real ratio: 4 × 1.5** (width × height, so height is 0.375 of width). The Panel Sizes section above gives the sizes as multiples of system bar height — that was an *impression* of size, and this is the first slot to be given a number against it. Arrived at by eye on the Tab S9 Ultra across four attempts (3:1 → 4×3 → 4×2 → 4×1.5), which is the only honest way to pick a shape. **Provisional**: the remaining eleven slots are designed at 1.6.4 as real cases arrive, and the full set locks into `module-sdk.md` at **1.6.10**. Held as one constant (`ModulePanelSpec.LARGE_ASPECT`) so it stays cheap to change until then.
>
> *Noted for the slot set:* a panel's height share of the screen is simply **screen aspect ÷ panel aspect**, so a full-width panel is shallow on a portrait screen and deep on a landscape one — 4×1.5 is 23% of a portrait tablet and 60% of the same tablet landscape. This is not a defect. A head unit does not rotate; a user picks the slot suiting the one orientation their screen is mounted in, which is what the twelve are *for*. It only looks like a problem on a dev tablet that spins.
>
> **3. The container is a plain fill, not a frame.** `backgroundColourPrimary`, no border, no corner radius, no content — a default to be drawn over, never a designed surround. When a module owns the box the fill is simply not seen. With no module installed there is no king in the castle, so DASH occupying the empty box is its one permitted tenancy, and it ends the moment a module claims the space. **Settings can never cover the panel** — the blind insets around it on both edges and rolls out into the band between bar and panel. The panel is hidden during bar edit mode, which is a focused task rather than the home screen.

> **Docking — 2026-07-30 (roadmap 1.6.3).** The panel gained all four edges and its settings tab. `Layout › Module Panel` is **live**, ending the WIP placeholder the 2026-07-20 tree reconciliation gave it.
>
> **1. The long edge is the docked edge, less whatever the bar has already taken from it.** Docked top or bottom, the bar is on the opposite horizontal edge and takes nothing, so the panel runs the full screen width. Docked left or right, the bar spans the full width at top or bottom and eats into the vertical run, so the panel runs **the screen height less the bar height** and butts against the end away from it. A consequence worth stating: **changing the bar height resizes a vertical panel.** This refines the Docking section above, which described the edges but never said what the panel measures itself against.
>
> **2. The vertical slot is the horizontal one stood on its end — 1.5 × 4.** One ratio serves both orientations once it is expressed against the *long* edge rather than against width. Vertical is the shape that suits a landscape screen, where the horizontal slot is deepest — the two together give a sensible option in either orientation for the first time. Both remain provisional until the set locks at 1.6.10.
>
> **3. Preference and effective edge are different things.** The stored setting is the edge the user *chose*. Where the panel actually draws is that edge unless the bar holds it, in which case the panel is displaced to the opposite one **for as long as the collision lasts**. Move the bar away and the panel returns on its own. *Why:* storing the displacement would let DASH quietly rewrite a user's setting and never give it back, which is the opposite of getting out of the way. Only horizontal preferences can collide — the bar occupies top or bottom, so a left- or right-docked panel is always safe.
>
> **4. The edge the bar holds cannot be newly chosen** (Roger). Its tile is greyed, unclickable, and labelled with the reason rather than left silently dead; its glyph drops the panel and shows the bar alone, because a tile that says *this edge belongs to the bar* should not illustrate a fallback. All four tiles stay in place rather than one being removed, so the row does not reshuffle whenever the bar moves.
>
> *This is a deliberate exception to the no-dead-controls principle recorded at Layout › Rotation (1.5.15).* There, every option was genuinely valid and greying would have obstructed a real choice. Here the edge is genuinely occupied, so a disabled control is the honest state rather than an obstruction. **Greying prevents only new selection** — a preference that becomes occupied later still displaces, and its tile then reads as both selected and unavailable, which says the choice is being held rather than lost.
>
> **5. Moving between edges is a transition.** An edge change moves the panel in two dimensions and resizes it, so it registers as `MODULE_PANEL_MOVE` and takes its speed from Appearance › Transitions like every other DASH motion — the self-growing registry absorbed it with no settings rework, exactly as that pattern intends.

> **The six slot ratios — 2026-08-01 (roadmap 1.6.4).** All three sizes are built, and the Panel Sizes section above — which gave them as multiples of system bar height — is **superseded**. That was an impression of size; these are the shapes.
>
> | | Horizontal | Vertical |
> |---|---|---|
> | **Large** | **8 × 3** | **3 × 8** |
> | **Medium** | **16 × 3** | **3 × 16** |
> | **Small** | **16 × 1** | **1 × 16** |
>
> Long edge first, thickness second. **Every vertical slot is its horizontal twin stood on its end**, so there are three shapes to learn rather than six, and DASH stores one pair of numbers per size. Recorded in whole numbers rather than the fractional forms they were sketched in (4×1.5, 4×0.75, 4×0.25 — Roger's call): a module author should not have to picture a shape described in quarters. Held as integers with the aspect derived from them, so the published ratio and the arithmetic cannot drift apart.
>
> **What they come to.** A panel's share of the screen is *screen aspect ÷ panel aspect* — on a 16:10 screen in landscape the horizontal slots take 60% / 30% / 10% of the height, and the vertical ones about 22% / 11% / 4% of the width. **Small lands at roughly system-bar height on a wide screen**, which is exactly where the superseded "1× the system bar" wording always pointed — the impression was right, it just could not survive a user-adjustable bar.
>
> **Provisional until 1.6.10**, when the set locks into `module-sdk.md`. Drafted in full at arduino.md §11, which is where a module author reads them.
>
> **Settings: size first, position second** (Roger). Size is the larger decision, and the position tiles draw the panel at whatever size is chosen above them, so `Layout › Module Panel` reads top to bottom as one continuous answer. Both rows are glyphs of the real screen, with panel thickness taken from the same ratio the real panel uses — so the three sizes are honestly to scale against each other rather than three arbitrary bands.

---

## The App Launcher

The app launcher is a two-tier system providing quick access to pinned favourite apps and a full library of all installed apps.

### Tier One — Favourites Bar

A persistent or floating strip of pinned app icon slots. The user defines the number of slots — this number drives the bar dimensions. DASH calculates icon size from the available bar length divided by the slot count, respecting minimum and maximum icon size limits. The user never sets bar width or icon size directly.

Empty slots display as visible placeholders — a subtle outlined space indicating room is available. If the user wants no empty spaces they set the slot count to match the number of apps they intend to pin.

The Favourites Bar may be:
- Absent entirely — the user uses only the full launcher tray
- Persistent — always visible as a strip
- Floating — hidden with a peek strip, revealed by swipe or system bar element

### Tier Two — Launcher Tray

The full app library. Opens from the favourites bar by swiping outward, or directly from a system bar element if no favourites bar is configured.

**Contents:**
- App grid showing all installed apps
- Recently used apps surfaced at the top of the grid
- Search bar at the top filtering both apps and DASH functions
- Long press any app to pin it directly to the favourites bar

### App Launcher Reveal Element

An optional system bar element — displaying a grid icon — that opens the launcher tray directly. If a favourites bar is configured, tapping the element reveals it first. If no favourites bar is configured, tapping goes straight to the full tray.

---

## Overlays

Overlays are transient UI layers that appear above the entire interface — above the viewport, above all bars and panels — to deliver contextual information without interrupting the user's primary task. They appear, communicate, and dismiss without requiring user action unless configured to persist.

### Two Overlay Categories

**Transient Overlays**
System state notifications. Volume changes, input selection, brightness adjustments. Consumed in the moment, no value in reviewing later. Auto-dismiss after 3 seconds by default. Not logged, not counted, not badged.

**Notification Overlays**
Communication and event notifications. Messages, missed calls, app alerts. Logged to notification history. A count badge on a system bar element shows unread notifications. Auto-dismiss after 5 seconds by default, or persistent until tapped — user configurable. When persistent and the vehicle is moving, auto-dismiss after 5 seconds overrides the persistent setting if vehicle speed data is available.

### Overlay Appearance

Each overlay defines:
- **Shape** — Rectangle, Rounded Rectangle, Pill, or Circle
- **Size** — Small, Medium, or Large within soft limits
- **Position** — Top centre, Bottom centre, Left centre, Right centre, or four corners
- **Opacity** — 0–100%
- **Background colour** — from theme tokens
- **Duration** — per overlay instance

### Overlay Content

An overlay contains any combination of icon, label, value, and progress or level indicator. Content is readable at a glance. Overlays are not designed for detailed reading — they deliver a single piece of information quickly.

### Multiple Overlays

When two overlays fire in rapid succession the second replaces the first immediately. For automotive use the current state is always more relevant than the previous state.

### Overlay Triggers

Any system message, module trigger, or Android notification can be mapped to an overlay in Notification settings. The user maps the event to an overlay definition. DASH fires the overlay when the event occurs. The mapping is user-configurable — no event is hardcoded to any specific overlay.

**Example mappings:**
- SYSTEM:volume:changed → Volume transient overlay
- MODULE:A4CF12:TRIGGER:filter_warning → Filter warning notification overlay
- SYSTEM:reverse:active → Reverse camera overlay (full screen or partial)
- Android message notification → Message notification overlay

### Overlay SDK

DASH provides an Overlay SDK allowing developers and users to build custom overlays. A custom overlay package placed in the overlays folder appears in the overlay library automatically. Custom overlays are mapped to triggers in the same settings panel as built-in overlays.

**An overlay package contains:**
- A declaration file stating name, description, version, and category
- Compose implementation for each supported shape variant
- Default duration and dismiss behaviour
- Theme token consumption declarations

---

## Navigation Model

DASH implements a simplified automotive navigation model. Traditional Android navigation concepts are replaced with more appropriate automotive equivalents.

**No recents panel.** The launcher tray provides instant access to any installed app. An app switcher adds unnecessary complexity. If the user wants Maps they tap Maps. If they want Spotify they tap Spotify.

**Back is optional.** A Back Button element can be placed on the system bar by the user if desired. When the Android navigation bar is hidden, swiping up from the very bottom of the screen temporarily reveals the transient navigation bar — a native Android behaviour that cannot be suppressed — providing back access when needed. As a future enhancement, DASH may detect when an app has a back stack and display the back element contextually only when it is useful.

**Home is an optional element.** The settings button is the only mandatory system bar element. Home behaviour — returning to the default startup app — is provided by the optional Home element or by selecting the default app from the launcher.

**Android navigation bar** is hidden by default on SBC installations where DASH has system-level access. On tablet proof-of-concept installations, three-button navigation is the fallback — it has no conflicts with DASH gesture interactions.

**Gesture conflict management.** DASH uses edge swipes for floating panel reveals. Android's full gesture navigation also uses edge swipes for back. DASH addresses this by hiding Android navigation entirely on supported installations. On installations where this is not possible, gesture exclusion rectangles are declared for DASH panel areas. The absolute bottom edge is avoided for all DASH swipe interactions to prevent conflict with Android's persistent home gesture zone.

---

## Settings Panel

The settings panel is accessed via the Settings Button element on the system bar. It occupies the full screen with the exception of the system bar, which remains visible and accessible at all times.

### Visual Identity

The settings panel is not user-definable in its visual appearance. It inherits its visual identity from the active system bar theme — accent colours, fonts, font colours, and scale are all derived from the system bar's current configuration. The design language is consistent and coherent. If the system bar is flush, the settings panel is flush against it. If the system bar is floating glass, the settings panel fills the full screen with the system bar floating above it.

### Navigation Structure

Three-level progressive navigation with animated transitions.

**Level One** — Major category column on the far left. Always visible. Appearance, Modules, Transports, Vehicle, Audio, Notifications, Apps, System, Developer.

**Level Two** — Subcategory column appears to the right when a major category is selected. The major category column remains visible.

**Level Three** — When a subcategory is selected, the major category column slides off to the left. The subcategory column shifts left into the vacated space. The content area expands to fill the majority of the screen. A back affordance — the subcategory column or a subtle arrow — slides the major column back in when the user wants to navigate elsewhere.

### Settings Tree

```
DASH Settings
│
├── Appearance
│   ├── Presets
│   │   ├── Built-in presets (Flush, Dominant, Passive)
│   │   ├── User saved presets
│   │   └── Import / Export preset
│   ├── Viewport
│   │   ├── Viewport mode (Flush / Dominant / Passive)
│   │   ├── Corner radius
│   │   └── Shadow settings
│   ├── System Bar
│   │   ├── Position (top / bottom)
│   │   ├── Height
│   │   ├── Zone configuration
│   │   │   ├── Number of zones (1–3)
│   │   │   └── Zone divider positions
│   │   ├── Element layout editor
│   │   └── Element vertical positioning
│   ├── Panels
│   │   ├── Module Panel
│   │   │   ├── Docking edge
│   │   │   ├── Persistent or floating
│   │   │   ├── Peek strip size                          ← subsumed by Tab bar › Thickness
│   │   │   ├── Default size (small / medium / large)
│   │   │   └── Tab bar                                  ← added 2026-08-19 (1.6.9)
│   │   │       ├── Thickness (UI Scale bound)
│   │   │       ├── Tab style (pips / names / both)
│   │   │       ├── Alignment along the edge
│   │   │       ├── Colour
│   │   │       └── Shown or hidden
│   │   └── App Launcher
│   │       ├── Persistent or floating
│   │       ├── Docking edge
│   │       ├── Favourite slot count
│   │       └── Reveal method (swipe / element / both)
│   ├── Colours
│   │   ├── System bar colour
│   │   ├── Panel colours
│   │   ├── Accent colours
│   │   ├── Font colours
│   │   └── Window border colours
│   ├── Fonts
│   │   ├── System font selector
│   │   └── Font size per element type
│   ├── Spacing
│   │   ├── Element vertical position within bar
│   │   └── Zone padding
│   ├── Overlays
│   │   ├── Shape preset
│   │   ├── Size
│   │   ├── Position on screen
│   │   ├── Opacity
│   │   ├── Transient duration
│   │   ├── Notification duration
│   │   └── Dismiss behaviour (auto / tap to dismiss)
│   ├── Density
│   │   ├── App density preset
│   │   └── DASH UI scale preset
│   ├── Splash Screen
│   │   ├── Image or colour selection
│   │   └── Display duration
│   └── Ambient Mode
│       └── (to be defined)
│
├── Modules
│   ├── Discovery
│   │   └── Search for modules
│   └── Installed Modules
│       └── Per module
│           ├── Enable / disable
│           ├── Transport assignment
│           ├── Relay signal subscriptions
│           └── Remove / uninstall
│
├── Transports
│   ├── USB Serial
│   ├── WiFi
│   ├── Bluetooth Classic (SPP)
│   ├── Bluetooth LE
│   ├── UART
│   ├── RS485
│   ├── CAN Bus
│   └── Ethernet
│
├── Vehicle
│   ├── CAN Patch Bay
│   ├── OBD2
│   ├── Signal Slots
│   ├── DBC Profiles
│   └── Vehicle Profile
│
├── Audio
│   ├── Output selection (digital / analogue)
│   ├── Audio routing
│   ├── Volume behaviour
│   └── Per app audio permissions
│
├── Notifications
│   ├── Per app notification management
│   ├── Duration settings
│   ├── Driving mode rules
│   └── Notification history settings
│
├── Apps
│   ├── Installed apps
│   ├── Default app assignments
│   ├── App permissions
│   └── Storage management
│
├── System
│   ├── WiFi → (Android deep link)
│   ├── Bluetooth → (Android deep link)
│   ├── Display → (Android deep link)
│   ├── Storage → (Android deep link)
│   ├── Accessibility → (Android deep link)
│   ├── Date and Time → (Android deep link)
│   └── About DASH
│
└── Developer
    ├── (Safety acknowledgement — shown once)
    ├── CAN Logger
    ├── Transport Diagnostics
    ├── Element SDK Tools
    ├── Overlay SDK Tools
    ├── Signal Monitor
    ├── Log Viewer
    └── Version and build info
```

> **Reconciliation addendum — 2026-07-20 (roadmap 1.5.1).** The tree above is the original settings structure. Ahead of building the settings panel (1.5.x) it was walked against everything actually built in 1.1.x–1.4.x and against the 1.5.x implementation plan, and reconciled. The tree above is kept for the record; **the reconciled structure below is the one 1.5.x implements.** Nothing here changes a *feature* — only where its settings live in the tree, and how the tree maps to the three-level navigation.
>
> **What changed, and why:**
>
> 1. **New top-level category: Layout.** The five *placeable surfaces* — System Bar, Module Panel, App Launcher, Elements, Overlays — are lifted out of Appearance into their own top-level **Layout** category. *Why:* these are the discrete things DASH draws and positions (where they dock, how big, what reveals them), which is a different concern from the cross-cutting visual skin (colours, fonts, density). This is the Module Mantra as a settings tree — the panel is a *wall* DASH owns (Layout), the module is the *king* inside it (Modules). It also keeps the three-level navigation honest: nesting these under Appearance would have added a fourth tier the major→subcategory→content model can't show, and it de-loads Appearance, which was carrying eleven subcategories. Appearance keeps only the visual skin: Density, Splash Screen, Colours, Fonts, Presets, Ambient Mode.
>
> 2. **Panels dissolved into Layout.** The old `Appearance → Panels → {Module Panel, App Launcher}` grouping is flattened: Module Panel and App Launcher become direct Layout subcategories, siblings of System Bar. *Why:* with Layout as the surfaces category, the intermediate "Panels" node is redundant, and it keeps every surface at the same navigable depth. The stacking rule (a floating module panel and a floating launcher cannot share an edge) is still configured in one place — both now sit in Layout.
>
> 3. **Overlays split by concern.** Overlay *appearance* (shape, size, position, opacity, durations, dismiss behaviour) lives in **Layout → Overlays**. Overlay *trigger mapping* (what system signal, module trigger, or Android notification fires an overlay) lives in **Notifications → Overlay trigger mapping**. *Why:* these are two genuinely different jobs — styling a surface vs. wiring an event to it — and the trigger side was always described as living in Notification settings (see the Overlays section of this document). One feature, two correct homes.
>
> 4. **System Bar entry corrected.** In settings, System Bar is **Position + an Edit Bar Layout entry point + Reset** — not flat height/zone/element sliders. *Why:* 1.3.9 and 1.3.13 deliberately moved bar height, zone count, and element sizing *inside* edit mode so the user sees the result live while editing. The settings subcategory is the door to that workspace, not a duplicate set of controls.
>
>    *Refined — 2026-07-23 (roadmap 1.5.7).* Built out with two corrections to the split. **Zone count joins Position in the box**, so the tab is **Position + Zones + Edit Bar Layout entry + Reset**; zone *boundary positions* stay a drag-on-the-ruler task, only the discrete *count* is a box control. And bar height / element sizing left edit mode but did **not** come to this tab — they had already become live steppers in **Appearance › Size & Scale** (1.5.3), so edit mode simply shed its duplicate copies. The upshot: **edit mode is reduced to the ruler beside the bar plus Save/Cancel** — colours on the token set, the module panel and not-default-launcher banner hidden so the workspace is uncluttered, and Save/Cancel return to the System Bar tab rather than the home screen. The "not flat sliders" principle above still holds — the tab is a door plus two discrete choices, not the live height/element sliders that decision rejected.
>
> 5. **Spacing subcategory dropped.** The old `Appearance → Spacing` (element vertical position within bar, zone padding) is removed as redundant — its contents duplicate the System Bar element-positioning that now lives in edit mode. If a genuine cross-cutting spacing control emerges later it can return as its own thing.
>
> 6. **The work-in-progress convention.** The tree is complete and navigable from the shell version (1.5.2), but most tabs are placeholders for features not yet built. A placeholder is honestly labelled ("arrives with vX.x") and navigable, not a dead control. Each lights up at its own feature's version — Module Panel at 1.6.x, Viewport at 1.7.x, App Launcher at 1.8.x, Elements at 1.9.x, the theming/overlays/audio/notifications/apps tabs across version 2, vehicle/CAN in version 3. Viewport currently has **no** settings subcategory at all (removed here); its mode/corner-radius/shadow controls arrive with 1.7.x and a Viewport home is decided then (Appearance-as-look vs Layout-as-surface).
>
> **The reconciled top-level structure:**
>
> ```
> DASH Settings
> ├── Appearance    — the visual skin: Density, Splash Screen, Colours, Fonts, Presets, Ambient Mode
> ├── Layout        — the placeable surfaces: System Bar, Module Panel, App Launcher, Elements, Overlays
> ├── Modules       — Discovery; Installed Modules (per module: enable/disable, transport assignment,
> │                   relay subscriptions, uninstall)
> ├── Transports    — a generic list driven off TransportManager (USB, WiFi, Bluetooth today; future
> │                   transports auto-listed), each with enable/disable + its own configuration
> ├── Vehicle       — CAN Patch Bay, OBD2, Signal Slots, DBC Profiles, Vehicle Profile   (v3)
> ├── Audio         — output selection, routing, volume behaviour, per-app audio permissions   (v2)
> ├── Notifications — notification suppression (capability-detected), overlay trigger mapping,
> │                   per-app management, durations, driving-mode rules, history   (v2)
> ├── Apps          — installed apps, default assignments, permissions, storage   (v2/v3)
> ├── System        — Android deep-links (WiFi/Bluetooth/Display/Storage/Accessibility/Date&Time)
> │                   + About DASH
> └── Developer     — safety acknowledgement, Serial Monitor, Signal Monitor, transport diagnostics,
>                     log viewer, CAN Logger (v3), SDK tools (v3)
> ```
>
> **Notification suppression** (a master toggle making DASH the sole notification surface) is filed under Notifications, not Appearance, and is a capability-detected feature — it needs elevated access a Bronze sideload lacks, so it unlocks on Silver/Gold system-app hardware and degrades silently on Bronze. It is not a mere preference toggle; it gets its own designed capability path when built.

> **Settings panel build — 2026-07-20 (roadmap 1.5.2).** The shell was built and two things in the sections above settled differently in practice; recorded here per the additive-docs rule (the originals are kept).
>
> **Navigation is two-pane, not three-column.** The "Navigation Structure" above describes three columns (major stays visible, subcategory to its right, content on a third). In build it became a cleaner two-pane model, agreed with Roger: the left margin shows the **main tree** (the ten categories); tapping a category makes its **subcategories grow in and the main tree drop out of view** — never three columns at once, only the main tree *or* one subtree. The **content box** appears on the right when a category is chosen (soft-radius, `backgroundColourSecondary` fill), auto-opening the first subcategory. A **back button pinned to the bottom of the left margin** walks up one level — subtree → main tree — and closes the panel when there is no level left to climb. This supersedes the three-column description for version 1. The left column's width and row spacing scale with Android's font-size setting so labels never wrap or crowd.

> **Adaptive layout & the content scaffold — 2026-07-22 (roadmap 1.5.3).** Building the first real tab, the panel learned to reshape itself to the space available, and a reusable scaffold was settled so every later tab is built the same way. Recorded here per the additive-docs rule.
>
> **The panel is adaptive.** It measures the space it actually has (not a whole-window size class) and chooses:
> - **Wide** (≥ ~600dp — tablet, landscape, head unit): the two-pane model above. The content box holds on an **empty landing box** — drawn straight away, never blank — until a *subcategory* is chosen; the path is category → subcategory → content.
> - **Narrow** (< ~600dp — phone portrait): the original three-level **drill-down** the "Navigation Structure" section first described. The tree fills the screen; a category replaces it with its subtree; a subcategory replaces that with the content; a back control pinned to the bottom walks one level down and closes at the top. No landing pane on a narrow screen — DASH looks best on a tablet-sized device, and the phone gets function over the show.
> Both share one navigation state, so rotating the device **reflows** between them rather than restarting — the Activity handles config changes itself and is not recreated, so an open screen (settings, a monitor) survives a rotation. The breakpoint is a single tunable constant.
>
> **The content scaffold.** Every subcategory's content is built from one small, reusable vocabulary, so the panel reads as one system and new tabs are quick to add:
> - a **header** — title, a thin art-deco rule, and an *optional* description (used only when it tells the user something the controls below don't already make plain);
> - **setting blocks** — each a label + plain-language help paired with a control, plus an optional **live preview** that shows the effect as it is changed; a block splits label-left / control-right when there's width and stacks when there isn't;
> - a **control vocabulary** — preset segment, ± stepper, link-out, live-preview card — covering what DASH needs, extended rather than reinvented per tab;
> - a **one-line-per-tab router** — a subcategory goes live by claiming its id in one place; the navigation shell never changes.
> This is the SDKable discipline applied to settings: the built-in tabs get no structure a future one couldn't reuse. It lives in `ui/settings/content/` (`SettingsContentHeader`, `SettingBlock`, `PresetSegment`, `Stepper`, `LinkButton`, `LivePreviewCard`, `SettingsContent`).
>
> **The panel rolls out from the bar.** It grows from the bar's edge like a blind — an explicitly animated height, not a fade — on the same `backgroundColourPrimary` fill so there is no seam, with the bar floating above it and staying reachable. Open and close both take the **user-configurable transition length**: a new Appearance setting (`LocalTransitionMillis`, presets INSTANT → CINEMATIC), because DASH has no opinion on how fast the user's interface should move. The **settings button toggles** the panel — a press opens it, a second press closes it — and is now a real vector icon that fills its cell, tinted from `iconColourPrimary`.
>
> **Panel bounds — the panel is not always full screen.** It covers the launcher and the viewport, sits **below** the bar (never over it), and **conforms to the module panel**: it yields to a persistent (non-retractable) module panel, keeping it fully visible, and covers a retracted one. This is the Module Mantra as layout — DASH's own settings chrome never sits on top of the king's castle.

> **Amendment — 2026-08-27 (roadmap 1.6.9). The settings panel no longer always yields to the module panel.** The paragraph above is kept for the record; what follows is what DASH does. *(Roger — "ive previously said that the settings panel does not overlap the module panel ever. i think this is wrong.")*
>
> **Why it changed.** The rule was right about the Module Mantra and wrong about what happens when honouring it makes DASH unusable. The module panel is a fixed aspect ratio anchored to its docked edge, so its thickness is a *consequence* of the screen rather than a number anybody chose — and on a tall narrow device that consequence is brutal. A large vertical slot on a 412dp-wide phone takes 358dp of it, leaving the settings blind a **54dp band**. **The module panel's own setting therefore became unreachable by the settings panel**, with no way out, because DASH sets its own `requestedOrientation` and no Android rotation control gets round it. `module-layout.md` §6 explicitly permits a module to ship the layout that springs that trap, so it is not an edge case. **The courtesy loses to the trap.**
>
> **This does not weaken the Module Mantra.** The Mantra forbids DASH reaching *into* the module's box to alter, style, override or offer settings for what the module drew. A user-initiated surface temporarily occluding the panel reaches into nothing: the panel is untouched, and it returns exactly as it was.
>
> **What DASH does instead.** When the settings panel opens, DASH measures **the rectangle it would get** — the screen, less the system bar, less the whole assembly (panel *and* tab bar) on whichever edge it holds. That rectangle must be a usable shape:
>
> - **The tolerance is the rectangle's signed aspect ratio, width ÷ height** — no narrower than **0.48**, no wider than **4.40**. Signed rather than long-edge-over-short, and that distinction is the finding: a leftover space 2.41 : 1 *wide* is perfectly usable and one 2.29 : 1 *tall* is not, so one unsigned bound cannot separate them.
> - **The bounds are asymmetric — roughly 4 : 1 wide against 2 : 1 tall — and structurally so.** A setting's control is a fixed 190dp and cannot shrink, so **width is structural** and a narrow band breaks; the content box scrolls, so **height is elastic** and a short band merely degrades.
> - **A hard 302dp minimum width** is kept as a backstop, moving with the text scale. No ratio rescues a band too narrow to hold one control.
>
> Outside the tolerance the panel yields, in this order:
>
> 1. **Step down** to the largest smaller slot **the module currently on screen** actually ships — Large → Medium → Small, **never crossing orientation**.
> 2. **Retract** off its edge if there is no such slot. Retracting rather than covering: the castle visibly steps aside and visibly comes back, which the user can read, where a blind drawn over the top would simply make the panel vanish.
>
> **Retract is the guarantee; step-down is the courtesy** *(Roger)*. The guarantee is unconditional and holds against any layout a module ships. The step-down is only available when the author drew more than one slot — which is an incentive for module authors, not a requirement on them; §6's *partial support is normal* is unchanged.
>
> **The tab bar never moves.** It is DASH's own chrome rather than the king's castle, it is the peek strip, and it is the one surface that must never become unreachable — so the assembly collapses to the bar alone and the bar stays exactly where it was.
>
> **The user's stored preference is never rewritten.** The displacement lasts as long as the settings panel is open and not one frame longer — the same discipline `effectiveEdge` applies when the system bar displaces the panel, now applied to *size* instead of *position*.

> **Appearance › Transitions — 2026-07-23 (roadmap 1.5.5).** DASH's motion controls were built as their own Appearance subcategory. The reconciled Appearance list above (2026-07-20) named Density, Splash Screen, Colours, Fonts, Presets, Ambient Mode; **add Transitions** to it as a live subcategory. Recorded here per the additive-docs rule; the 1.5.2 rollout note above is superseded on two points, called out below.
>
> **The governing rule: if it's a transition, it goes in Transitions.** A *transition* is a surface revealing, hiding, or moving between states — a panel rolling out, a view sliding, content crossfading, a splash fading. Every one of these is the user's to control and gets its own breakout here. **Control-feedback micro-animation** intrinsic to how a widget *works* — a toggle thumb sliding, an edit-ruler handle settling — is **not** a transition and stays fixed in the code; it belongs to the control's feel, not the user's motion taste. This rule is the line between the two, and it is deliberately simple so it needs no case-by-case adjudication.
>
> **The naming.** The design conversation weighed "Motion" for the subcategory; it settled on **Transitions**, because that is exactly the noun the governing rule uses — the tab holds transitions, so it is called Transitions. ("Motion" would have implied it also owns micro-animation, which it explicitly does not.)
>
> **Six presets, per transition.** INSTANT (0ms, a true hard cut) · FAST (250) · NORMAL (450, default) · SLOW (750) · CINEMATIC (1100) · **LABORIOUS (3000)**. LABORIOUS is a deliberately, gloriously slow option — the ethos made literal: DASH has no opinion on how fast the user's interface should move, including "absurdly slow, because I want to." *This supersedes the 1.5.2 note's "INSTANT → CINEMATIC" — the set now ends at LABORIOUS.*
>
> **A master pace over a per-transition breakout.** A **master** control at the top sets every transition at once. Below it, **each transition breaks out to its own six-preset control** — settings *open* and settings *close* are two separate controls, the nav slide is its own, and so on; nothing is lumped. The master is **derived, never stored**: when every transition shares one speed it shows that speed selected; the moment any single one diverges it shows nothing selected and wears a **"Custom"** tag. Re-tapping a master preset re-syncs the lot. This is the games-menu pattern (a master graphics preset that greys to Custom when one setting is changed), and deriving the master rather than storing it means it can never fall out of step with the rows.
>
> **The list is registry-driven and self-growing.** Controls are rendered straight off a transition **registry** (`TransitionId`), one control per entry — so a new surface's transition gains a control here the moment it registers, with no settings rework. This is the same self-growing pattern the Transports list uses off `TransportManager`. Order is chronological-as-added for now, to be tidied into a sensible order once every transition in DASH exists. The seven transitions present at 1.5.5: settings panel **open** / **close**, settings nav **drill in** / **back out**, settings content **swap**, splash **fade in** / **fade out**. Future surfaces (module panel 1.6.x, app launcher 1.8.x, overlay fades v2, …) register into the same list as they are built.
>
> **The splash boundary.** A splash **fade** is a transition, so its fade-in and fade-out live here — *not* in the Splash tab. The Splash tab (roadmap 1.5.6) owns the splash's **image/colour selection and its dwell duration** (how long it holds fully visible between the fades). This split is the governing rule applied cleanly: the fades are motion, the dwell and the artwork are not. *(Splash gained a genuine fade-**in** at 1.5.5; before this it only faded out.)*
>
> **Under the hood.** The single global `LocalTransitionMillis` from 1.5.2 is **superseded** by a `LocalDashTransitions` holder provided at the composition root, which resolves each `TransitionId` to its stored speed (`DashPreferences.transitions` / `setTransition` / `setAllTransitions`). The parked transition control in the legacy settings bridge is **removed** — this version is its rehome. It lives with the rest of the content vocabulary in `ui/settings/content/` (`MotionContent`), built on the existing scaffold (`PresetSegment`, `SettingBlock`, `LivePreviewCard`), each row carrying a small live demo that replays at the chosen speed so the change is felt, not just read.

### Developer Tab

The Developer tab is openly accessible — no passcode, no gesture, no hidden unlock sequence. DASH trusts its users with all of its tools.

On first entry the user sees a single acknowledgement screen. Plain language, honest tone. Something like: these tools give you direct access to DASH internals — changes here can affect system stability, proceed thoughtfully. A single confirm button. Remembered after first acknowledgement. The warning can be shown again from within the tab if the user wants a reminder.

This is a safety notice, not a locked door.

---

## The Three Extension SDKs

DASH provides three extension SDKs that together cover every layer of the interface. They share the same underlying philosophy — DASH defines the rules and the container, the developer defines the content.

**Module Protocol** — for external hardware modules describing their UI panels and data. Defined in transport.md.

**Element SDK** — for bar components. Elements that live inside zones on the system bar. Built-in elements and community elements use identical specifications.

**Overlay SDK** — for notification and state overlays. Built-in overlays and community overlays use identical specifications.

A motivated developer could rebuild the entire DASH visual experience using only community components without touching the core platform. The core is stable. The surface is infinitely extensible.

---

## Soft Limits and Hard Floors

DASH enforces sizing constraints through two mechanisms.

**Soft limits** apply to most user-configurable sizes. When an element or panel approaches a threshold below which it becomes difficult to use or read at dashboard viewing distance, DASH shows a subtle amber indicator in the settings editor. The user can acknowledge and proceed. The soft limit is education, not restriction.

**Hard floors** apply only to safety-critical elements. The settings button touch target has a hard minimum of 48dp. It cannot be configured below this value. DASH enforces it silently — the user never needs to know it exists. The visible icon may scale with the bar but the interactive area never goes below 48dp.

Soft limits appear contextually in real time during adjustment — as the user drags a size control toward the threshold the indicator appears immediately. Not as a post-save validation, not as a modal warning. Just a gentle signal visible while the decision is being made.

> **Design decision — v1.3.4:** The amber soft limit model above was reconsidered during implementation for element sizing and replaced with boundary labels integrated directly into the control. When element height reaches its minimum, the value display changes to "min" and the decrease button greys out. When it reaches the ceiling, the display changes to "max" and the increase button greys out. The reason for this change is that a separate amber indicator is a more complex mechanism than the situation requires. The original model describes a warning that appears *near* a threshold, implying the user can proceed past it — soft limit as advisory. But the element size floor and ceiling are not advisories; they are the actual boundaries of the control. Communicating the boundary through the control itself — changing the label, disabling the button — is simpler, more honest, and requires no separate warning infrastructure. The information the user needs is in the thing they are interacting with, at the moment they need it. The hard floor on the settings button touch target (48dp) is unchanged and continues to be enforced silently.

---

## Design Principles Summary

These principles govern all interface decisions in DASH. Any feature that touches the interface must respect them.

**1. The user is master**
DASH has no opinion about what the interface should look like. Every visual and layout decision belongs to the user.

**2. Get out of the way**
The interface exists to serve the user's goals. It never imposes, never restricts, never decides for the user what they should see or how they should interact.

**3. The module is king within its domain**
DASH draws the module panel container. The module fills it. DASH does not alter, style, or override any content within the module panel boundary.

**4. Everything is optional except the mandatory two**
The alerts area and the settings button are the only non-negotiable elements. Everything else — panels, elements, overlays, the launcher, even home and back — is the user's choice to include or exclude.

**5. Progressive disclosure**
Features are available when the user goes looking for them. The default experience is clean and uncluttered. Complexity is opt-in.

**6. Consistent design language**
The settings panel, overlays, and all DASH chrome inherit their visual identity from the user's chosen theme. Nothing feels like a different application.

**7. The viewport belongs to the app**
When an app is running, the viewport is its domain. DASH does not interfere with what an app renders within the viewport boundary.

**8. Safety through design**
The alerts area is always visible. The settings button is always reachable. The back mechanism is always available through the transient navigation bar. The user can never be stranded.

---

*This document is the authoritative reference for DASH interface architecture and design. All interface development must conform to the definitions and principles contained here.*
