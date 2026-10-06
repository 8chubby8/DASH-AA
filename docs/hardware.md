# DASH — Board Research & Selection

## Key Principles

DASH is hardware agnostic above the BSP boundary. The board choice is the user's responsibility. This document records research into available Android-compatible SBCs for use as a DASH host, ordered by approximate UK price.

**Critical caveat throughout:** CAN and RS485 being present in a board's Linux BSP does not automatically mean they are exposed in the Android BSP. Where confirmed in Android, this is noted. Where unverified, it is flagged. This must be confirmed before purchase if native CAN or RS485 is required.

**Pricing note:** Prices are approximate UK-landed figures including shipping and import duty where applicable. Direct-from-manufacturer prices are lower but attract import duty on arrival. Amazon UK and reseller prices are typically 40–60% higher than the raw manufacturer price but are duty-free for UK buyers.

---

## Hardware Compatibility Standards

These standards define the hardware requirements for a device or board to run DASH at a given level. They describe what the hardware must provide — not what DASH can or cannot do. DASH's capabilities are defined elsewhere. The distinction between tiers is always about how a capability is implemented at the hardware level, not whether it is available.

---

### 🥉 Bronze — Any Android Device

**Minimum hardware requirements:**
- Android 7.0 or above
- USB host (OTG) support
- Sufficient GPU and RAM to render the DASH Compose UI smoothly (minimum 4GB RAM recommended)

**Android system access:** DASH runs as a standard sideloaded Android app. No system app privileges. Features that require elevated Android permissions are capability-detected and degrade gracefully — the feature is absent or limited, but DASH continues without error or complaint. This is not a deficiency of Bronze — it is the correct and expected behaviour of a capability-aware codebase on consumer hardware.

**How interfaces are provided:**
- All vehicle interfaces (CAN, RS485, UART) via USB adapters or bridge devices
- CAN sniffing via USB CAN adapter (e.g. CANable 2.0 Pro)
- Module connections via USB serial (usb-serial-for-android)
- Vehicle system data (ignition, headlights, reverse, steering wheel controls) via a user-programmed ESP32 bridge module connected over USB
- WiFi and Bluetooth via onboard hardware (most tablets/phones) or USB dongle
- Cellular internet via phone hotspot, USB dongle, or onboard modem
- Audio via onboard jack, USB audio adapter, or HDMI extraction
- Microphone via Bluetooth HFP (for calls) or USB audio interface
- Touchscreen via the device's own display or USB HID touch overlay

**Qualifying devices:**
- Any Android smartphone (Android 7+, USB OTG confirmed)
- Any Android tablet (Android 7+, USB OTG confirmed)
- Budget SBCs with a working Android BSP and USB host (e.g. Orange Pi 5, Radxa Rock 5B)

**Notes:** A Bronze installation is a fully functional DASH installation. The ESP32 vehicle bridge module closes the gap for ignition sensing, headlight detection, reverse sensing, and steering wheel controls without requiring native hardware interfaces. Camera stitching is not achievable at Bronze due to processor constraints on typical Bronze devices such as phones and tablets, but is possible on Bronze-tier SBCs with sufficient GPU headroom if cameras are connected via USB.

---

### 🥈 Silver — Capable SBC with Native Interfaces

**Minimum hardware requirements:**
- All Bronze requirements, plus:
- RK3588 or equivalent SoC with sufficient GPU headroom for real-time camera stitching alongside the UI
- At least one native interface (CAN, RS485, or UART) exposed and confirmed working in the Android BSP — not just the Linux BSP
- M.2 slot for cellular modem (preferred) or USB cellular as acceptable fallback
- Reliable thermal management for sustained automotive use

**Android system access:** Silver and Gold require a custom Android firmware build where DASH is included as a privileged system app from the start. This is not an add-on or a post-install configuration — it is a firmware decision made at build time. System app status is what unlocks full Android system-level access for DASH: density control, display management, and any other feature that requires elevated permissions. Features that were capability-detected and gracefully degraded on Bronze unlock automatically on Silver and Gold hardware at runtime. No separate codebase is required — the same DASH binary runs on all tiers and capability gates determine what is available.

**How interfaces are provided:**
- CAN sniffing via native onboard CAN controller (preferred) — USB CAN adapter remains acceptable
- RS485 and UART via native board interfaces where available
- Module connections via native UART/RS485 or USB serial
- Vehicle system data via native CAN or ESP32 bridge module — user's choice
- Audio, microphone, cellular may still be USB-based — this is acceptable at Silver
- Touchscreen via MIPI DSI with BSP touch driver or USB HID

**Qualifying boards:**
- Orange Pi 5 Plus, Radxa Rock 5B+ (if Android BSP peripheral coverage confirmed)
- NanoPC-T6 LTS (with confirmed Android BSP native interface support)
- Boardcon Idea3588, Boardcon EM3588

**Notes:** Silver is the natural target for an enthusiast permanent car installation. Native interfaces reduce USB port consumption and improve wiring cleanliness for modules that are permanently installed. The ESP32 bridge approach remains valid at Silver for users who prefer it or whose board doesn't expose all interfaces natively.

---

### 🥇 Gold — Automotive-Grade Board

**Minimum hardware requirements:**
- All Silver requirements, plus:
- Native CAN, RS485, and UART all confirmed working in the Android BSP
- Native multi-channel MIPI CSI camera support in the Android BSP for surround view pipeline
- Automotive-grade or industrial-grade SoC (extended temperature rating)
- 12V DC power input (direct automotive power without USB-C PD conversion)
- Long-term supply availability from the manufacturer

**How interfaces are provided:**
- CAN, RS485, UART all native — no USB bridges required for permanent wiring
- Camera pipeline via native MIPI CSI channels
- Audio and cellular may still be USB-based — acceptable at Gold
- Touchscreen via MIPI DSI with confirmed BSP driver

**Qualifying boards:**
- Firefly AIO-3588Q (commercial grade)
- Firefly AIO-3588JQ
- Firefly AIO-3588MQ (automotive-grade RK3588M silicon — the definitive Gold board)
- Boardcon EM3588 (with 8x AHD camera inputs — exceeds Gold in camera capability)

**Notes:** Gold is the correct tier for a production-quality permanent car installation intended for long-term daily use. The 12V DC input on Firefly boards eliminates the USB-C PD converter from the power chain entirely, improving reliability. USB implementations for audio and cellular are not a deficiency at Gold — they are clean, reliable solutions that require no native BSP support. As with Silver, DASH must be included in the firmware build as a privileged system app — this is a build-time firmware decision, not a runtime configuration.

---

## Support Rating System

| Rating | Meaning |
|--------|---------|
| ⭐ | Community only, minimal manufacturer involvement |
| ⭐⭐ | BSP provided by manufacturer, patchy peripheral coverage, community-driven |
| ⭐⭐⭐ | Good manufacturer support, regular BSP updates, reasonable documentation |
| ⭐⭐⭐⭐ | Commercial vendor, proper documentation, confirmed automotive interfaces |
| ⭐⭐⭐⭐⭐ | Best in class, full SDK, automotive-targeted, comprehensive docs |

---

## Budget Tier — Under £100

### Android Smartphone or Tablet
- **Tier: 🥉 Bronze**
- **SoC:** Any (device-dependent)
- **Price:** Free if existing hardware / £30–150 secondhand
- **Android:** Android 7.0+ required
- **CAN/RS485 onboard:** No — USB adapters only
- **M.2/5G:** No — cellular via onboard modem (if present) or phone hotspot
- **WiFi/Bluetooth:** Yes — onboard on all modern devices
- **Notes:** The lowest barrier Bronze entry point. Any Android phone or tablet with USB OTG confirmed working qualifies. All vehicle interfaces via USB adapters and ESP32 bridge modules. No camera stitching capability. Ignition sensing, headlight detection, reverse sensing, and steering wheel controls all achievable via a user-programmed ESP32 system bridge module over USB. A legitimate permanent installation for users whose requirements do not exceed Bronze. Verify USB OTG host mode works on the specific device before committing — not all devices enable host mode even if the port is physically present.
- **Support: N/A — manufacturer device**

---

### Orange Pi 5
- **SoC:** Rockchip RK3588S
- **Price:** ~£45–60 (bare board, direct)
- **Android:** Android 12
- **CAN/RS485 onboard:** No
- **M.2/5G:** No M.2 on base model
- **WiFi:** No (added on 5B variant)
- **Notes:** Entry point to RK3588S performance. Android BSP functional but lags behind Linux BSP in peripheral coverage. No onboard CAN or RS485 — USB adapters required. Community is large and active. Manufacturer documentation is thin. Good budget option if modules are connected over USB only.
- **Support: ⭐⭐**

---

### Orange Pi 5B
- **Tier: 🥉 Bronze**
- **SoC:** Rockchip RK3588S
- **Price:** ~£70–110 (depending on RAM)
- **Android:** Android 12
- **CAN/RS485 onboard:** No
- **M.2/5G:** No
- **WiFi:** Yes — WiFi 6 + Bluetooth 5.3 built in, onboard eMMC 32–256GB
- **Notes:** Better value than the 5 if WiFi is needed — avoids buying a separate M.2 WiFi card. Same Android BSP caveats. No CAN or RS485 regardless. Suitable for DASH installs using USB transport only.
- **Support: ⭐⭐**

---

### Khadas VIM3 Pro
- **Tier: 🥉 Bronze (development/AAOS testing only)**
- **SoC:** Amlogic S922X (Cortex-A73, not A76)
- **Price:** ~£80–100
- **Android:** AAOS via Snapp Automotive free image
- **CAN/RS485 onboard:** No
- **M.2/5G:** M.2 available
- **WiFi:** Yes
- **Notes:** The only board at this price with a genuine AAOS image available (Snapp Automotive). Makes it the cheapest path to running real AAOS for development and testing. Performance is lower than RK3588 — not suitable as a production DASH board for camera stitching workloads. Recommended specifically as a development and AAOS familiarisation platform only.
- **Support: ⭐⭐⭐ (for development use)**

---

## Mid Tier — £100–250

### Radxa Rock 5B / 5B+
- **Tier: 🥈 Silver (pending Android BSP peripheral verification)**
- **SoC:** Rockchip RK3588
- **Price:** ~£100–140 (bare board, direct or AliExpress) / ~£150–190 (Amazon UK)
- **Android:** Android 12
- **CAN/RS485 onboard:** No
- **M.2/5G:** Yes — Rock 5B+ has three M.2 slots (2x NVMe, 1x cellular)
- **WiFi:** M.2 E-key required (not included)
- **Notes:** Strong community, well-documented hardware. Honest caveat: the BSP kernel is a Rockchip BSP kernel assembled from older sources with cherry-picked patches — not a clean LTS kernel. Android BSP is available but peripheral coverage is patchy. No onboard CAN or RS485, and community reports suggest these don't surface cleanly in the Android BSP even via external hardware. Radxa's documentation is good for Linux, thinner for Android specifically. The 5B+ adds three M.2 slots which is attractive for cellular modem expansion.
- **Support: ⭐⭐**

---

### Khadas VIM4
- **Tier: 🥉 Bronze / 🥈 Silver border (GPU headroom unverified for stitching)**
- **SoC:** Amlogic A311D2 (Cortex-A73, not A76)
- **Price:** ~£150–180
- **Android:** Android
- **CAN/RS485 onboard:** No
- **M.2/5G:** M.2 available
- **WiFi:** Yes — WiFi 6 + Bluetooth 5.1
- **Notes:** Premium build quality — machined aluminium case options, active cooling, polished out-of-box experience. Android performance is strong with smooth 4K playback and responsive UI. Less raw CPU performance than RK3588 (A73 vs A76 cores). Good choice if build quality and out-of-box Android experience matter more than absolute performance or automotive interfaces. No CAN or RS485 regardless.
- **Support: ⭐⭐⭐**

---

### NanoPC-T6 LTS (FriendlyElec)
- **Tier: 🥈 Silver (pending native interface Android BSP confirmation)**
- **SoC:** Rockchip RK3588
- **Price:** ~£115–130 (direct from FriendlyElec + duty) / ~£220–290 (Amazon UK / resellers)
- **Android:** Android 14 (BSP source publicly available on GitLab)
- **CAN/RS485 onboard:** No
- **M.2/5G:** M.2 B-Key (NVMe) + M.2 E-Key (WiFi)
- **WiFi:** M.2 E-Key module required
- **Notes:** A strong mid-tier option with one key differentiator — FriendlyElec publishes their Android BSP source openly on GitLab. This transparency is rare and valuable: you can see exactly what is and isn't in the BSP, and adding CAN support via an external USB adapter is achievable with the source available. Android 14 is the most current Android version available on any non-Firefly board in this list. Documentation is good. No onboard CAN or RS485, but the published source means adding USB-bridged support is cleaner than on opaque BSPs. Note: the T6 Plus variant is ~$295 direct / significantly more via resellers.
- **Support: ⭐⭐⭐**

---

### Mixtile Blade 3
- **Tier: 🥉 Bronze**
- **SoC:** Rockchip RK3588
- **Price:** ~£130–200
- **Android:** Android (with Linux container)
- **CAN/RS485 onboard:** No
- **M.2/5G:** mini-PCIe
- **WiFi:** Yes
- **Notes:** Primarily designed as a stackable cluster board rather than a standalone head unit platform. Functional Android BSP. Community smaller than Radxa or FriendlyElec. The automotive use case is not a focus for this vendor. Not recommended for DASH — better options available at similar price.
- **Support: ⭐⭐**

---

## Industrial Tier — £200–400

### Boardcon Idea3588
- **Tier: 🥈 Silver / 🥇 Gold border**
- **SoC:** Rockchip RK3588
- **Price:** POA — contact vendor (estimated £200–350)
- **Android:** Android 14
- **CAN/RS485 onboard:** Yes — both confirmed
- **M.2/5G:** PCIe 2.0 + PCIe 3.0 + SATA 3.0
- **SIM slot:** Yes — onboard SIM card slot (notable — most boards rely on modem module SIM tray)
- **WiFi:** Optional
- **Notes:** Commercial embedded vendor with proper technical documentation and email support. Android 14 is the most current Android version confirmed with CAN and RS485. The onboard SIM slot is a genuine differentiator over most boards. Interfaces confirmed: RS485, CAN, UART, MIPI CSI, MIPI DSI, PCIe, HDMI IN, DisplayPort, SATA, audio I/O. Pricing is quote-based. Worth emailing for a firm UK price before dismissing or selecting.
- **Support: ⭐⭐⭐⭐**

---

### Boardcon EM3588
- **Tier: 🥇 Gold**
- **SoC:** Rockchip RK3588
- **Price:** POA — contact vendor (estimated £250–450)
- **Android:** Android (version confirmed, exact release TBC)
- **CAN/RS485 onboard:** Yes — both confirmed
- **M.2/5G:** 2x M.2 PCIe 3.0 + SATA 3.0 + optional 4G LTE via mPCIe
- **WiFi:** Yes — WiFi 6 + Bluetooth 5.2 built in
- **Notes:** More capable sibling to the Idea3588. Key additional feature: 8x AHD camera inputs, directly relevant to the DASH surround view milestone. Dual M.2 PCIe 3.0 slots. RS232, RS485, and CAN all confirmed. Commercial grade documentation. Higher pricing than Idea3588. Best board on the list for future camera integration.
- **Support: ⭐⭐⭐⭐**

---

## Professional/Automotive Tier — £300+

### Firefly AIO-3588Q
- **Tier: 🥇 Gold**
- **SoC:** Rockchip RK3588
- **Price:** ~£330–370 (base 4GB/32GB) / up to ~£700 (32GB/256GB)
- **Android:** Android 12
- **CAN/RS485 onboard:** Yes — both confirmed in Android BSP
- **M.2/5G:** PCIe 3.0 + SATA 3.0 + M.2 expansion
- **WiFi:** Optional M.2
- **GPIO:** 20-pin header with GPIO, ADC, SPI, I2C
- **Power:** DC 12V input — directly compatible with automotive 12V via DC-DC converter
- **Operating temp:** -20°C to 60°C (commercial) / -40°C to 85°C (industrial grade)
- **Notes:** The benchmark for DASH production use. Best Android BSP documentation of any board in this list. CAN and RS485 confirmed in the Android BSP — not just the Linux BSP. Full SDK download, comprehensive wiki, tutorials, and technical documentation provided as standard. Commercial, industrial, and automotive grade core boards available. The 12V DC input is a practical advantage for car installation — no USB-C PD converter required. **Current recommended production board for DASH.**
- **Support: ⭐⭐⭐⭐⭐**

---

### Firefly AIO-3588JQ
- **Tier: 🥇 Gold**
- **SoC:** Rockchip RK3588
- **Price:** ~£350–400
- **Android:** Android 12
- **CAN/RS485 onboard:** Yes — both confirmed
- **Notes:** Essentially the AIO-3588Q on a different carrier board layout targeting slightly different industrial configurations. Same BSP quality, same documentation standard, same SDK. Check Firefly's site for the specific interface configuration differences versus the AIO-3588Q.
- **Support: ⭐⭐⭐⭐⭐**

---

### Firefly AIO-3588MQ
- **Tier: 🥇 Gold (definitive)**
- **SoC:** Rockchip RK3588M (automotive-grade silicon variant)
- **Price:** £400+ (exact POA)
- **Android:** Android 12
- **CAN/RS485 onboard:** Yes — both confirmed
- **Notes:** Uses the RK3588M — the actual automotive-grade silicon, not the commercial RK3588. Designed specifically for smart cockpit and ADAS applications. 10-year supply guarantee from Firefly. Extended temperature rating. Automotive-qualified components throughout. Supports 4, 6, and 8 channel wide-angle seamless camera stitching natively — directly relevant to DASH surround view. For a personal single-vehicle build the premium over the AIO-3588Q is hard to justify. For a production multi-vehicle deployment this is the correct board.
- **Support: ⭐⭐⭐⭐⭐**

---

## Quick Reference Summary

| Board | Tier | SoC | UK Price (approx) | Android | CAN/RS485 | M.2 | Support |
|---|---|---|---|---|---|---|---|
| Phone / Tablet | 🥉 | Any | Free–£150 | Android 7+ | No | No | N/A |
| Orange Pi 5 | 🥉 | RK3588S | £45–60 | Android 12 | No | No | ⭐⭐ |
| Orange Pi 5B | 🥉 | RK3588S | £70–110 | Android 12 | No | No | ⭐⭐ |
| Khadas VIM3 Pro | 🥉 dev | S922X | £80–100 | AAOS (Snapp) | No | Yes | ⭐⭐⭐ |
| Mixtile Blade 3 | 🥉 | RK3588 | £130–200 | Android | No | mini-PCIe | ⭐⭐ |
| Khadas VIM4 | 🥉/🥈 | A311D2 | £150–180 | Android | No | Yes | ⭐⭐⭐ |
| Radxa Rock 5B | 🥈* | RK3588 | £100–190 | Android 12 | No | Yes | ⭐⭐ |
| NanoPC-T6 LTS | 🥈* | RK3588 | £115–290 | Android 14 | No | Yes | ⭐⭐⭐ |
| Boardcon Idea3588 | 🥈/🥇 | RK3588 | POA | Android 14 | Yes + SIM | Yes | ⭐⭐⭐⭐ |
| Boardcon EM3588 | 🥇 | RK3588 | POA | Android | Yes + 8x cam | Yes | ⭐⭐⭐⭐ |
| Firefly AIO-3588Q | 🥇 | RK3588 | £330–370 | Android 12 | Yes ✓ | Yes | ⭐⭐⭐⭐⭐ |
| Firefly AIO-3588JQ | 🥇 | RK3588 | £350–400 | Android 12 | Yes ✓ | Yes | ⭐⭐⭐⭐⭐ |
| Firefly AIO-3588MQ | 🥇 | RK3588M | £400+ | Android 12 | Yes ✓ | Yes | ⭐⭐⭐⭐⭐ |

*🥈 pending confirmation of native interface support in Android BSP

---

## Universal DASH Peripheral Requirements

Regardless of board selected, every DASH installation requires the following capabilities. Most SBCs are deficient in several of these areas. This section documents each requirement, common deficiencies, and the recommended solution.

---

### 1. Bluetooth

**Required for:** Phone HFP (calls through car speakers/mic), Bluetooth audio streaming, optional Bluetooth module transport.

**Deficient boards:** Orange Pi 5 (base), most Firefly and Boardcon boards.

**Solution:** M.2 E-key combo WiFi/Bluetooth card. Intel AX200 is the recommended option — WiFi 6 + Bluetooth 5.2, well-supported in Android BSPs, ~£15. Covers both Bluetooth and WiFi in one card. Boards with onboard WiFi/BT (Orange Pi 5B, Khadas VIM3/VIM4) are already covered.

---

### 2. WiFi

**Required for:** App installs, OTA updates, home/hotspot internet, optional WiFi module transport.

**Deficient boards:** Orange Pi 5 (base), Firefly AIO-3588Q, most Boardcon boards.

**Solution:** Same Intel AX200 M.2 card as Bluetooth above. One card covers both. Note: if the board's single M.2 slot is used for a cellular modem, a USB WiFi adapter (~£10) is the fallback — less elegant but functional.

---

### 3. Always-on Internet (Cellular)

**Required for:** Maps, streaming, and internet access independent of a paired phone hotspot.

**Deficient boards:** All boards listed — none have onboard cellular.

**Solution (preferred):** M.2 modem card (Quectel EM05 for 4G LTE, RM500Q for 5G) in the M.2 slot with a nano-SIM. Requires the board to have a free M.2 slot after WiFi is covered. The Orange Pi 5B has onboard WiFi, freeing its M.2 slot specifically for a modem — this makes it more attractive for cellular installs than the base Orange Pi 5.

**Solution (fallback):** USB 4G/5G dongle (~£30–50). Works on any Android board with USB host. Less clean, consumes a USB port, but universally compatible. Huawei E3372 (4G) is well-supported on Android.

**RIL caveat:** For Android to treat a modem as a proper cellular data source (with signal bars, data roaming, etc.), the board's Android BSP needs RIL (Radio Interface Layer) support for the specific modem. Without RIL, a USB modem may still work as a network interface via RNDIS/NCM but won't integrate with the Android telephony stack. Verify RIL support with the board vendor for your chosen modem before purchasing.

---

### 4. Digital Audio Output

**Required for:** Feeding DASH audio output to a DSP, processor, or DAC via SPDIF optical or coaxial. Roger's installation targets a WiiM Ultra or miniDSP 2x8 which accepts SPDIF.

**Deficient boards:** All boards listed — none provide native SPDIF output. HDMI carries digital audio but requires extraction.

**Android audio layer note:** Android's audio subsystem resamples all audio to 48kHz/16-bit internally by default. Bit-perfect output requires either a USB audio interface with a UAC2-compliant driver and an app like USB Audio Player Pro that bypasses the Android mixer, or accepting the 48kHz/16-bit output for standard car audio use. For typical head unit audio into a DSP, 48kHz/16-bit is entirely adequate.

**Solution A — HDMI audio extractor (~£15–20):** Sits between the board's HDMI output and the display. Extracts the digital audio stream and provides SPDIF optical (Toslink) and/or coaxial output alongside passing HDMI through to the display. Works on any board with HDMI. Clean, passive, no USB port consumed. Recommended for most installs.

**Solution B — USB audio interface (~£20–40):** A UAC1/UAC2 compliant USB audio device with SPDIF output. Android 5.0 and above supports a subset of USB Audio Class 1 features natively, meaning a UAC1-compliant device is plug-and-play. A USB audio interface also provides microphone input simultaneously (see below), making it a two-in-one solution. StarTech ICUSBAUDIO2D is a confirmed working example — USB in, SPDIF optical out + 3.5mm analogue out + 3.5mm mic in.

---

### 5. Analogue Audio Output

**Required for:** 3.5mm or RCA output to an amplifier or head unit auxiliary input for simpler installs that don't use a DSP with digital input.

**Deficient boards:** Orange Pi 5B, Firefly, Boardcon, most industrial boards — no 3.5mm jack. Orange Pi 5 (base) does have a 3.5mm jack.

**Solution A:** If using the HDMI audio extractor above, most extractors include RCA analogue outputs alongside SPDIF. One device covers both digital and analogue out.

**Solution B:** USB audio adapter (~£5–10). A basic UAC1 USB soundcard with 3.5mm output is the lowest cost solution. Works plug-and-play on Android 5.0+.

---

### 6. Microphone Input

**Required for:** Bluetooth HFP call audio (in-car microphone for calls routed through DASH), voice commands if implemented.

**Bluetooth HFP note:** For phone calls routed via Bluetooth HFP, the microphone is handled entirely by the Bluetooth audio profile — the SBC microphone input is not involved. Bluetooth HFP includes its own bidirectional audio path. A physical microphone input on the board is therefore only needed for non-Bluetooth voice input — local voice commands, driver monitoring audio, or in-car intercom features.

**Deficient boards:** All boards listed — none have onboard microphone input.

**Solution:** USB audio interface with microphone input. The StarTech device mentioned under digital audio output covers this simultaneously. Alternatively, a basic USB soundcard with 3.5mm mic input (~£8). Android requires UAC2 for bidirectional audio at 48kHz/24-bit, but UAC1 is sufficient for standard voice quality microphone input. A physical microphone then connects to the USB audio device's 3.5mm mic jack and is mounted in the cabin.

---

### 7. Touchscreen Input

**Required for:** Primary user input method for the DASH UI.

**Two approaches:**

**MIPI DSI panel with I2C touch controller (Goodix GT911 or similar):** Single ribbon cable carries display and touch. Requires the board's Android BSP to include drivers for the specific touch controller. Verify BSP touch controller support before purchasing a panel. Most RK3588 Android BSPs include Goodix GT911 support.

**HDMI display with USB HID touch overlay:** Works universally on any Android board with USB host regardless of BSP. The display connects via HDMI, touch via USB as a standard HID device. Android treats it as a standard input device — no driver work required. Less elegant (two cables) but more universally compatible. Suitable if BSP touch support is uncertain.

**For the DASH Lexan panel install:** A dedicated MIPI DSI panel bonded to Lexan with USB touch overlay is the recommended approach, providing a clean single cable run for display and a reliable USB touch input.

---

### 8. Powered USB Hub

**Required on:** All installations, mandatory on budget boards.

With ESP32 module connections, CAN USB adapter, UART adapter, USB audio interface, and potentially a USB cellular modem or USB microphone all connected simultaneously, a powered USB hub is not optional — it is a core component of any DASH installation.

**Specification:** Minimum 4-port, ideally 7-port. Must be independently powered — not bus-powered — to supply adequate current to all connected devices. For car installation, a hub powered from the 12V → USB-C converter is the correct approach. For bench development, any quality powered USB hub works. Anker and Ugreen are reliable brands.

---

### Summary Table — Deficiencies and Solutions

| Requirement | Deficient Boards | Solution | Approx Cost |
|---|---|---|---|
| Bluetooth | Orange Pi 5, most Firefly/Boardcon | Intel AX200 M.2 card | ~£15 |
| WiFi | Orange Pi 5, most Firefly/Boardcon | Intel AX200 M.2 card (same card) | ~£0 extra |
| Cellular internet | All boards | M.2 modem (preferred) or USB 4G dongle | £30–80 |
| Digital audio out (SPDIF) | All boards | HDMI audio extractor | ~£15–20 |
| Analogue audio out | Most boards (except OPi5 base) | Included in HDMI extractor or USB soundcard | ~£5–10 |
| Microphone input | All boards | USB audio interface with 3.5mm mic jack | ~£10–20 |
| Touchscreen | All boards | MIPI DSI + BSP driver or HDMI + USB HID | £0 (USB HID) |
| Powered USB hub | All boards | 7-port powered hub | ~£20–30 |

**Minimum universal peripheral cost (any DASH board): ~£80–120** on top of the board itself, assuming no cellular modem and using USB HID touch.

---

## USB Bridge Strategy — The Budget Case

DASH's transport abstraction layer means native CAN and RS485 on the board is architecturally irrelevant. USB bridge adapters present identically to DASH as any other transport — the module protocol is unchanged, the usb-serial-for-android library handles the connection, and the abstraction layer routes data through regardless of physical transport. There is no functional loss versus a board with native CAN.

This makes USB bridging the correct approach for budget installs and early development:

- **CANable 2.0 Pro** (slcan firmware) — USB CAN adapter — ~£20
- **USB to UART adapter** (CH340 or FTDI) — ~£8

Total hardware cost for full CAN and UART capability: ~£28. These work on any Android board with USB host.

For installs where CAN or RS485 is needed permanently wired rather than via USB dongle, an RS485/CAN HAT (e.g. Waveshare RS485 CAN HAT B via SPI) can be added later to any board that exposes SPI in its Android BSP.

---

## Current Recommendations

**Phase 0 — Proof of concept (now):** Any Android 7+ phone or tablet. Free if sourced from existing hardware. Use USB OTG hub for module connections.

**Phase 1 — Budget production board:** Orange Pi 5 (~£55) + M.2 WiFi card (~£15) + CANable 2.0 Pro (~£20) + USB UART adapter (~£8) + eMMC module (~£20) + cooling (~£10). **Total ~£130.** Verify USB host works in Android BSP before purchasing. No native CAN or RS485 needed — USB bridges handle both. Requires confirmation that Android BSP exposes USB host properly.

**AAOS familiarisation (optional):** Khadas VIM3 Pro (~£80) with Snapp Automotive image. Development and testing only, not a production board.

**Phase 2 — If native interfaces become necessary:** Firefly AIO-3588Q (~£340). Best BSP, confirmed CAN/RS485 in Android BSP, 12V DC input, proper documentation. Only warranted if USB bridge approach proves insufficient for a specific use case.

**Future production (multi-vehicle/commercial):** Firefly AIO-3588MQ. Automotive-grade silicon, 10-year supply, ADAS camera support.

**Worth investigating further:** Boardcon Idea3588 — Android 14, onboard SIM, confirmed CAN/RS485, quote-based pricing may be competitive. Contact vendor for firm UK price.

---

---

## Module Boards — USB Compatibility

*(Added 2026-10-01, roadmap 1.6.12.)*

A module that talks to DASH **over a USB cable** should use a board with **native USB** — one that
shows up on the head unit as a USB CDC device (`ttyACM`). Good choices: **Arduino Uno R4** (WiFi or
Minima), **ESP32-S2 / S3 / C3 / C6** on their native USB port, Raspberry Pi **Pico**, Arduino
**Leonardo / Micro**.

A **classic ESP32** (ESP32-WROOM DevKitC and lookalikes) talks to USB through a separate
converter chip (CP2102 / CH340, shows as `ttyUSB`). In testing it damaged a large panel picture in
9 of 20 installs; an Uno R4 damaged none in 22. DASH repairs damaged pieces automatically (the
`RESEND` message, `module-sdk.md` §8), so it still works — but the occasional install will need a
second press. **A classic ESP32 is a perfectly good module over WiFi or Bluetooth**, which repair
damage on their own.

This is a recommendation, not a requirement. Installs happen once per firmware version, not every
journey.

---

## Device-Specific Notes

Notes on specific devices encountered during development or testing, covering quirks, confirmed working configurations, and anything that would not be obvious from the hardware spec alone.

---

### SVITOO P108 Tablet (Unisoc T606, Android 16)

**Device serial:** `P108V0301626032756`  
**Build:** `P108_T_EEA_V0.1_20251231`  
**Android:** 16, security patch 2025-11-05  
**SoC:** Unisoc T606, ARM64  

**Play Services signing certificate mismatch**

This tablet shipped with Google Play Services signed with a non-standard Google certificate rather than the common certificate used on most Android devices:

| | Cert SHA-1 |
|---|---|
| This device (system APK) | `2169eddb5fbb1fdf241c262681024692c4fc1ecb` |
| Standard Google cert (most devices) | `bd32424203e0fb25f36b57e5aa356f9bdd1da998` |

Android refuses to install an update signed by a different key without a valid v3 key rotation chain. Attempting to sideload a standard Play Services APK from APKMirror — the one defaulting to the `bd32` cert variant — fails with a misleading error:

```
INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from base.apk
using APK Signature Scheme v3: SHA-512 digest of contents did not verify
```

This error looks like APK corruption or a download problem. It is not. It is a certificate mismatch. The APK is valid — the device is rejecting it because the signing key does not match and no rotation chain is present. This error occurs regardless of install method (streamed, non-streaming, pushed to `/data/local/tmp/`, after uninstalling user overlay first).

**When updating Play Services on this tablet:**

On APKMirror's Play Services release page, each variant row shows a short signing cert identifier. Always select the variant showing **`2169`**, not the default `bd32`. The correct variant string is **`3891 2169`**.

Confirmed working upgrade: Play Services 26.18.33 (arm64-v8a + armeabi-v7a, Android 15+, nodpi, cert `3891 2169`) — installs cleanly over the factory 25.30.31.

The `bd32` variant will always fail on this device regardless of install method.

**System Play Services location:** `/product/priv-app/GmsCore/GmsCore.apk`

**Useful ADB reference for this device**

```bash
# Confirm device is connected
adb devices

# Check installed Play Services version
adb shell dumpsys package com.google.android.gms | grep versionName

# Check where Play Services is installed (confirms system vs user overlay)
adb shell pm path com.google.android.gms

# Revert to system Play Services (removes user-installed update, keeps data)
# Use before a fresh sideload if the upgrade path is causing issues
adb shell pm uninstall -k --user 0 com.google.android.gms

# Install a Play Services APK (must be the 2169 cert variant for this device)
adb install -r /path/to/playservices.apk
```

---

*Document generated from architecture and hardware research sessions. Prices correct at time of research — verify before purchase. All prices approximate UK-landed including duty and shipping where applicable.*
