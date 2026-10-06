package com.dash.android.ui.systembar

import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A request to enter the system-bar edit workspace (roadmap 1.5.7). Provided at the composition root
 * by [com.dash.android.ui.screen.MainScreen]; the Layout › System Bar settings tab calls it so
 * "Edit bar layout" opens edit mode and closes the settings panel. A CompositionLocal keeps the
 * settings-content dispatcher signature untouched — the same pattern as the splash preview hook.
 */
val LocalEnterBarEdit = compositionLocalOf<() -> Unit> { {} }

/**
 * The persisted configuration of the DASH system bar.
 *
 * This model is serialised to JSON and stored in DataStore. It is intentionally designed up
 * front to hold the full 1.3.x feature set — multiple zones, per-element anchors and size
 * variants — even though 1.3.1 only exercises a single full-width zone with anchor-based
 * placement. Building the shape now means the stored format never has to be migrated as the
 * later increments (sizing, zone splitting, edit mode) come online.
 *
 * Per interface.md the system bar always carries two mandatory elements — the Alerts Area and
 * the Settings Button. [SystemBarConfig.default] guarantees their presence; nothing in the data
 * model permits a configuration without them, and the reset path always returns to this default.
 */

/** Where the bar sits on screen. Top or bottom only — the side edges are reserved for panels. */
@Serializable
enum class BarPosition { TOP, BOTTOM }

/** The built-in element catalogue. Extended as new elements are added in later versions. */
@Serializable
enum class ElementType { ALERTS_AREA, SETTINGS_BUTTON, SPACER }

/** Snap anchor of an element within its zone. The only valid horizontal positions in 1.3.1. */
@Serializable
enum class ElementAnchor { LEFT, CENTRE, RIGHT }

/** Whether an element displays information, accepts interaction, or both. */
@Serializable
enum class ElementKind { INFORMATIONAL, INTERACTIVE, BOTH }

/** A single placed element instance within a zone. [spacerWidthDp] is only used when type == SPACER. */
@Serializable
data class ElementPlacement(
    val id: String,
    val type: ElementType,
    val anchor: ElementAnchor,
    val spacerWidthDp: Int? = null,
)

/**
 * A structural container within the bar. In 1.3.1 there is always exactly one zone spanning the
 * full width ([widthFraction] = 1f); zone splitting arrives in 1.3.3.
 */
@Serializable
data class ZoneConfig(
    val id: String,
    val widthFraction: Float = 1f,
    val elements: List<ElementPlacement> = emptyList()
)

/**
 * The complete system bar configuration. [heightDp] is the user-defined bar height — the master
 * measurement. [elementHeightDp] is the global element height; elements render at this size and
 * are capped at [heightDp] minus one step so they always fit inside the bar.
 */
@Serializable
data class SystemBarConfig(
    val position: BarPosition = BarPosition.BOTTOM,
    val heightDp: Int = DEFAULT_HEIGHT_DP,
    val elementHeightDp: Int = DEFAULT_ELEMENT_HEIGHT_DP,
    val zones: List<ZoneConfig> = emptyList()
) {
    fun withZoneCount(count: Int): SystemBarConfig {
        val current = zones.size
        return when {
            count == current -> this
            count > current -> {
                val fractions = List(count) { 1f / count }
                val expanded = zones.mapIndexed { i, z -> z.copy(widthFraction = fractions[i]) } +
                    (current until count).map { i ->
                        ZoneConfig(id = UUID.randomUUID().toString(), widthFraction = fractions[i])
                    }
                copy(zones = expanded)
            }
            else -> {
                val kept = zones.take(count)
                val orphaned = zones.drop(count).flatMap { it.elements }
                val fractions = List(count) { 1f / count }
                copy(zones = kept.mapIndexed { i, z ->
                    z.copy(
                        widthFraction = fractions[i],
                        elements = if (i == 0) z.elements + orphaned else z.elements
                    )
                })
            }
        }
    }

    companion object {
        const val DEFAULT_HEIGHT_DP = 56
        const val MIN_HEIGHT_DP = 40
        const val MAX_HEIGHT_DP = 120
        const val HEIGHT_STEP_DP = 4

        const val DEFAULT_ELEMENT_HEIGHT_DP = 36
        const val MIN_ELEMENT_HEIGHT_DP = 24
        const val ELEMENT_HEIGHT_STEP_DP = 4

        const val DEFAULT_SPACER_WIDTH_DP = 16
        const val MIN_SPACER_WIDTH_DP = 4
        const val MAX_SPACER_WIDTH_DP = 120
        const val SPACER_WIDTH_STEP_DP = 4

        /**
         * The factory default: one full-width zone carrying the two mandatory elements — Alerts
         * Area anchored left, Settings Button anchored right. This is what a fresh install shows
         * and what "Reset bar layout" returns to.
         */
        fun default(): SystemBarConfig = SystemBarConfig(
            position = BarPosition.BOTTOM,
            heightDp = DEFAULT_HEIGHT_DP,
            zones = listOf(
                ZoneConfig(
                    id = UUID.randomUUID().toString(),
                    widthFraction = 1f,
                    elements = listOf(
                        ElementPlacement(
                            id = UUID.randomUUID().toString(),
                            type = ElementType.ALERTS_AREA,
                            anchor = ElementAnchor.LEFT
                        ),
                        ElementPlacement(
                            id = UUID.randomUUID().toString(),
                            type = ElementType.SETTINGS_BUTTON,
                            anchor = ElementAnchor.RIGHT
                        )
                    )
                )
            )
        )
    }
}
