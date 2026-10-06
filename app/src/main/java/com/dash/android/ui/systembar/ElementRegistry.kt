package com.dash.android.ui.systembar

import com.dash.android.ui.systembar.elements.AlertsAreaElement
import com.dash.android.ui.systembar.elements.SettingsButtonElement

/**
 * Resolves an [ElementType] from a stored placement to its live [DashElement] implementation.
 *
 * Built-in elements register here. When the Element SDK is extracted in v3, community elements
 * discovered in the elements folder will be added to this same lookup — built-ins hold no
 * privileged position in it.
 */
object ElementRegistry {

    private val elements: Map<ElementType, DashElement> = listOf(
        AlertsAreaElement,
        SettingsButtonElement
    ).associateBy { it.type }

    /** The implementation for [type], or null if no element is registered for it. */
    fun get(type: ElementType): DashElement? = elements[type]
}
