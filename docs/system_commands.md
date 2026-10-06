# DASH System Commands — Tentative Vocabulary

> Working document. Incomplete. Subject to change.

**Behaviour key:**
- **store + event** — value saved to state store, event fired on change. Interface can react immediately or query store.
- **store only** — value saved to state store silently. Interface reads directly from store. No event fired.
- **event only** — momentary. Fired and forgotten. Nothing persisted.

**LISTENER subscribe defaults:** every signal below also carries a default
`rate` and `threshold` for a LISTENER's `SUBSCRIBE` message (see arduino.md
§4c/§9). A builder leaving those fields blank gets these defaults; they are
overridden only if the module needs different behaviour.

> **Amendment, 2026-07-08 (with Roger):** these defaults are filled by the
> **firmware library**, not by DASH. The numbers below are the library's reference
> — it substitutes them into a blank field before the `SUBSCRIBE` line is sent, so
> the line reaching DASH is already complete. **DASH holds no defaults of its own**
> and honours whatever arrives literally; a blank field means the total default (no
> cap / any change / always), a malformed field is logged and ignored. This keeps
> DASH out of the way and the built-in/community playing field level (the default
> lives in the shared library, not DASH's core). The behaviour column (store/event)
> is different — that stays DASH's, because it is how the controller *routes* a
> signal, not how a module asks for it.
- **Boolean / multi-state signals** — default is **event-driven**: no rate, no
  threshold. DASH delivers only on change (plus the §4c heartbeat/activation
  dump).
- **Continuous signals** — default rate and threshold are given per signal
  below, chosen to be a sensible balance between responsiveness and bus load
  for that signal's nature.
- **Event-only signals** (Controls table) have no state and no default
  rate/threshold — delivery is on fire only, per §4c.

---

## Doors & Access

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| door_driver_open | boolean | true / false | store + event |
| door_passenger_open | boolean | true / false | store + event |
| door_rear_left_open | boolean | true / false | store + event |
| door_rear_right_open | boolean | true / false | store + event |
| door_boot_open | boolean | true / false | store + event |
| door_boot_glass_open | boolean | true / false | store + event |
| door_bonnet_open | boolean | true / false | store + event |
| door_fuel_flap_open | boolean | true / false | store + event |
| charge_port_open | boolean | true / false | store + event |

---

## Windows

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| window_driver_up | boolean | true / false | store + event |
| window_passenger_up | boolean | true / false | store + event |

---

## Lights

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| headlights_on | boolean | true / false | store + event |
| fog_lights_front_on | boolean | true / false | store + event |
| fog_lights_rear_on | boolean | true / false | store + event |
| hazard_lights_on | boolean | true / false | store + event |
| interior_lights_on | boolean | true / false | store + event |
| indicator_left_on | boolean | true / false | store + event |
| indicator_right_on | boolean | true / false | store + event |

---

## Wipers

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| wipers_front_state | multi-state | off / intermittent / low / high | store + event |
| wipers_rear_on | boolean | true / false | store + event |

---

## Vehicle State

| Signal | Type | Values | Behaviour | Default rate | Default threshold |
|--------|------|--------|-----------|---------------|--------------------|
| ignition_state | multi-state | off / accessory / on | store + event | — (event-driven) | — |
| handbrake_on | boolean | true / false | store + event | — (event-driven) | — |
| gear_position | multi-state | park / reverse / neutral / drive / 1 / 2 / 3 | store + event | — (event-driven) | — |
| vehicle_speed | continuous | km/h | store only | 5hz | 1 km/h |
| steering_angle | continuous | degrees | store only | 20hz | 2 degrees |
| engine_rpm | continuous | rpm | store only | 5hz | 50 rpm |
| fuel_level | continuous | percentage 0-100 | store only | 0.2hz | 1 % |
| coolant_temp | continuous | degrees C | store only | 0.5hz | 1 °C |
| ambient_temp | continuous | degrees C | store only | 0.1hz | 0.5 °C |
| ambient_light | continuous | lux | store only | 1hz | 10 lux |

---

## Safety

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| seatbelt_driver_fastened | boolean | true / false | store + event |
| seatbelt_passenger_fastened | boolean | true / false | store + event |

---

## EV / Charging

| Signal | Type | Values | Behaviour | Default rate | Default threshold |
|--------|------|--------|-----------|---------------|--------------------|
| charge_connected | boolean | true / false | store + event | — (event-driven) | — |
| charge_level | continuous | percentage 0-100 | store only | 0.1hz | 1 % |

---

## Media

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| media_muted | boolean | true / false | store + event |
| media_source | string | source name | store + event |

---

## Controls

| Signal | Type | Values | Behaviour |
|--------|------|--------|-----------|
| media_play_pause | event | — | event only |
| media_next | event | — | event only |
| media_prev | event | — | event only |
| media_volume_up | event | — | event only |
| media_volume_down | event | — | event only |
| voice_activate | event | — | event only |
| button_home_pressed | event | — | event only |
| module_panel_left | event | — | event only |
| module_panel_right | event | — | event only |

> **Added 2026-08-19 (roadmap 1.6.8) — Roger's call.** The module panel switch.
> A SYSTEM module reports these and DASH moves one module along its panel order;
> a steering-wheel button or a dash switch is the best switch there is in a moving
> vehicle, because it needs no eyes at all.
>
> **Left and right are the direction of travel through the module order, not a
> screen direction.** `module_panel_left` is the previous module and
> `module_panel_right` the next one, whichever edge the panel is docked to and
> whichever way its tab bar happens to run. Two signals, not four — a vertical
> panel does not get `up`/`down` twins, because the user is choosing a *module*
> rather than aiming at a place on the glass.
>
> **They are inert with nothing to switch to**, which is the ordinary case: one
> installed module, or none able to fill the selected slot (`module-layout.md` §6).
> A module firing these into a DASH with one panel is not an error and is not
> reported as one — the same way a press on a control the firmware does not
> recognise is ignored in silence.
