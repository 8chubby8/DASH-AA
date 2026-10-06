package com.dash.android.transport

import com.dash.android.panel.LayoutSlot
import kotlinx.serialization.Serializable

/**
 * A module that has completed the install handshake (roadmap 1.4.4). Where a [DiscoveredModule] is
 * only *found* (it answered `DISCOVER`), an installed module is *set up*: DASH has sent `INSTALL|id`,
 * read the module's type-specific declarations, and committed them here on `INSTALL_END`.
 *
 * The record carries the identity fields **from the original `HELLO`** (a module declares its identity
 * once, at discovery — the handshake adds only the type-specific payload) and exactly one of the three
 * payloads populated, per the module's type (arduino.md §7):
 *
 *  - **SYSTEM** → [signals], the standard signals it will `BROADCAST`.
 *  - **LISTENER** → [subscriptions], the signals it wants delivered.
 *  - **ACCESSORY** → [assets], the icon/panel blocks it shipped.
 *
 * Since 1.4.5 this record is **persistent**: the [ModuleDatabase] serialises it to disk on commit and
 * reloads it every boot, making DASH the single source of truth for install state (arduino.md §6).
 *
 * **The ACCESSORY half of the record needs no more fields than this** *(settled at 1.6.6)*. An item
 * had been open since 1.4.4 for the framing of an ACCESSORY's variable and control declarations —
 * *"the other half of the panel contract"*. There is no framing to lock, because there is no
 * separate declaration: **the layout is the declaration** (`module-layout.md` §9). Every variable a
 * module reports and every control it accepts is named inside the layout documents it shipped as
 * blocks, so [assets] already carries both, and [slots] reads back which shapes it can be drawn in.
 */
@Serializable
data class InstalledModule(
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val version: String,
    val signals: List<String> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val assets: List<InstalledAsset> = emptyList()
) {
    /**
     * The layout slots this module can be drawn in (roadmap 1.6.6).
     *
     * **Derived from the block names, never stored.** `module-layout.md` §9 rules that a block
     * carrying a slot name *is* a layout and any other block is an asset — so the classification is
     * a function of what was already recorded at install, and writing it down as a second field
     * would only create something that could disagree with the first. The same discipline
     * `PanelSize.aspect` uses.
     *
     * It also means a module installed **before** DASH could recognise a layout needs no reinstall:
     * 1.6.5's Tank Gauge shipped `h_large_day` and it landed on disk as an ordinary asset, because
     * the twelve names existed nowhere machine-readable. They do now, and the record it wrote is
     * read correctly without being touched.
     *
     * A layout whose CRC failed is not offered — a half-arrived document is not a layout DASH can
     * honestly say it holds.
     */
    val slots: List<LayoutSlot>
        get() = assets.filter { it.crcOk }.mapNotNull { LayoutSlot.forName(it.name) }

    /** The stored file holding [name]'s bytes, or null if this module never shipped that block. */
    fun assetFile(name: String): String? =
        assets.firstOrNull { it.name == name && it.crcOk }?.file?.takeIf { it.isNotBlank() }
}

/**
 * One `SUBSCRIBE|id|function|rate|threshold|gate|gate_value` line from a LISTENER (arduino.md §9). The
 * four control fields are optional; absent ones are stored blank. DASH captures all of them now but
 * does not yet *act* on rate/threshold/gate — the stream engine that honours them is roadmap 1.4.8.
 */
@Serializable
data class Subscription(
    val function: String,
    val rate: String = "",
    val threshold: String = "",
    val gate: String = "",
    val gateValue: String = ""
) {
    companion object {
        /** Parse a `SUBSCRIBE` line into a [Subscription], or null if it has no function field. */
        fun parse(line: String): Subscription? {
            val p = line.split('|')
            // SUBSCRIBE | id | function [ | rate | threshold | gate | gate_value ]
            if (p.size < 3) return null
            val function = p[2].trim()
            if (function.isEmpty()) return null
            fun field(i: Int) = p.getOrNull(i)?.trim().orEmpty()
            return Subscription(
                function = function,
                rate = field(3),
                threshold = field(4),
                gate = field(5),
                gateValue = field(6)
            )
        }
    }
}

/**
 * One asset received during an ACCESSORY install: a `BLOCK|id|name|length|crc` header and its payload.
 * [name] is what the module called it on the wire; [file] is the sanitised filename the payload bytes
 * live under in the module's on-disk `assets/` folder, assigned by the [ModuleDatabase] at commit.
 * The panel (1.6.x) reads the bytes from there.
 */
@Serializable
data class InstalledAsset(
    val name: String,
    val bytes: Int,
    val crcOk: Boolean,
    val file: String = ""
)
