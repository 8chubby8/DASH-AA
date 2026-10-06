package com.dash.android.panel

import com.dash.android.ui.modulepanel.PanelSize

/**
 * The twelve layout slots, as names on the wire (roadmap 1.6.6).
 *
 * **This list is what makes a layout recognisable.** `module-layout.md` §9 rules that a layout
 * document needs no message of its own: *"a block carrying one of them is a layout and any other
 * block is an asset"*. That rule needs somewhere to look the names up, and until this file existed
 * there was nowhere — the twelve names appeared only as a single example in prose, which is why
 * 1.6.5's `h_large_day` landed on the tablet's disk as an ordinary asset.
 *
 * **Six shapes × two ambient modes** (`module-layout.md` §6):
 *
 * |        | Horizontal    | Vertical      |
 * |--------|---------------|---------------|
 * | Large  | `h_large_*`   | `v_large_*`   |
 * | Medium | `h_medium_*`  | `v_medium_*`  |
 * | Small  | `h_small_*`   | `v_small_*`   |
 *
 * The names are **derived, never listed** — from [PanelSize], which already owns the six ratios,
 * and the two enums below. A slot cannot be added to DASH without its name existing, and a name
 * cannot exist without a slot, which is the same discipline `PanelSize.aspect` uses to keep the
 * published ratio and the arithmetic from drifting apart.
 */
data class LayoutSlot(
    val size: PanelSize,
    val orientation: SlotOrientation,
    val ambient: Ambient,
) {
    /** The block name a module ships this layout under — `h_large_day`. */
    val blockName: String
        get() = "${orientation.prefix}_${size.name.lowercase()}_${ambient.name.lowercase()}"

    /** The same shape in the other ambient mode. §6's night→day fallback reads this. */
    fun withAmbient(other: Ambient) = copy(ambient = other)

    override fun toString() = blockName

    companion object {
        /** All twelve, in a stable order: by size, then orientation, then ambient. */
        val ALL: List<LayoutSlot> = PanelSize.entries.flatMap { size ->
            SlotOrientation.entries.flatMap { orientation ->
                Ambient.entries.map { ambient -> LayoutSlot(size, orientation, ambient) }
            }
        }

        private val byName: Map<String, LayoutSlot> = ALL.associateBy { it.blockName }

        /** The slot this block name denotes, or null — in which case the block is an asset (§9). */
        fun forName(blockName: String): LayoutSlot? = byName[blockName.trim()]

        /** Whether a `BLOCK` arriving under this name is a layout document rather than artwork. */
        fun isLayoutName(blockName: String): Boolean = forName(blockName) != null
    }
}

/**
 * Which way round the slot is.
 *
 * Every vertical slot is its horizontal twin stood on its end (§6), so this picks between the two
 * readings of one pair of numbers rather than naming a seventh and eighth shape.
 */
enum class SlotOrientation(val prefix: String) {
    HORIZONTAL("h"),
    VERTICAL("v"),
}

/**
 * Day or night — the ambient mode the slot is drawn for.
 *
 * **Named `day`/`night`, not `light`/`dark`** (§6, renamed 2026-08-05): the switch is genuinely day
 * and night in a vehicle rather than a theme's lightness, and a user may perfectly well run a dark
 * DASH theme in daylight.
 *
 * **Nothing selects [NIGHT] yet.** The Ambient feature is a version 2 setting, so for the whole of
 * the 1.6.x era DASH resolves the `day` slot and §6's *"a missing `night` variant falls back to the
 * `day` one"* is the only fallback that can fire. The mode exists here because the *names* lock at
 * 1.6.10 and a module author shipping night artwork today must have it accepted and stored, not
 * refused for a switch DASH has not built yet.
 */
enum class Ambient {
    DAY,
    NIGHT,
}
