package com.dash.android.ui.systembar

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * The contract every system bar element implements — built-in and, in future, community-built
 * alike. This interface is the seed of the v3 Element SDK: per the SDKable principle, a built-in
 * element receives nothing here that a community element could not also receive. If a built-in
 * ever needs more, the answer is to widen [ElementScope] / [DashAction] — never to reach around
 * this interface for privileged internal access.
 */
interface DashElement {
    /** The catalogue identity used to resolve placements back to their implementation. */
    val type: ElementType

    /** Human-readable name for the element library UI. */
    val displayName: String

    /** Whether the element shows information, takes interaction, or both. */
    val kind: ElementKind

    /**
     * Mandatory elements (Alerts Area, Settings Button) can never be removed from the bar. The
     * data model already guarantees their presence; this flag lets UI surface that to the user.
     */
    val mandatory: Boolean

    /** Renders the element. [scope] carries everything the element is permitted to know. */
    @Composable
    fun Content(scope: ElementScope)
}

/**
 * A system-level action an interactive element can request DASH to perform. This is the only
 * channel through which an element reaches the platform — kept deliberately narrow and explicit.
 * Extended as interactive elements arrive (reveal launcher, reveal module panel, navigate back,
 * show the volume overlay, and so on).
 */
sealed interface DashAction {
    data object OpenSettings : DashAction
}

/**
 * The render context handed to an element. [heightDp] is the user-defined element height the
 * element must render within. [barHeight] is the full bar height — available if an element needs
 * to know its ceiling. Nothing here is built-in-only.
 */
class ElementScope(
    val heightDp: Dp,
    val barHeight: Dp,
    val onAction: (DashAction) -> Unit
)
