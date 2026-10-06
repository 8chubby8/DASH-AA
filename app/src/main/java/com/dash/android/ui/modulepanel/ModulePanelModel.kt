package com.dash.android.ui.modulepanel

import com.dash.android.ui.systembar.BarPosition
import kotlinx.serialization.Serializable

/** The screen edge the module panel docks to. All four are available (roadmap 1.6.3). */
@Serializable
enum class PanelEdge(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
    LEFT("Left"),
    RIGHT("Right");

    /** Top and bottom give a horizontal panel; left and right a vertical one. */
    val horizontal: Boolean get() = this == TOP || this == BOTTOM

    val opposite: PanelEdge
        get() = when (this) {
            TOP -> BOTTOM
            BOTTOM -> TOP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}

/**
 * The three panel sizes (roadmap 1.6.4), each a **fixed aspect ratio** rather than a measurement.
 *
 * **The six layout slots, in whole numbers** (Roger, 1.6.4 — whole numbers read better than the
 * fractional forms they were first sketched in, and a module author should never have to picture
 * a shape described in quarters):
 *
 * |        | Horizontal | Vertical |
 * |--------|------------|----------|
 * | Large  | **8 × 3**  | 3 × 8    |
 * | Medium | **16 × 3** | 3 × 16   |
 * | Small  | **16 × 1** | 1 × 16   |
 *
 * Every vertical slot is its horizontal twin stood on its end, so **one pair of numbers serves
 * both** — which is why the ratio is expressed against the *long edge* rather than against width.
 * Held as [longUnits] and [thickUnits] rather than a float so the published ratio and the
 * arithmetic are the same fact and cannot drift apart; [aspect] is derived, never stored.
 *
 * Chosen from what Roger wants in his own car rather than derived for a general case. Small lands
 * at roughly system-bar height on a wide screen, which is where interface.md's original "1× the
 * system bar" impression always pointed. **Provisional** until the slot set locks into
 * `module-sdk.md` at 1.6.10.
 */
@Serializable
enum class PanelSize(val label: String, val longUnits: Int, val thickUnits: Int) {
    SMALL("Small", 16, 1),
    MEDIUM("Medium", 16, 3),
    LARGE("Large", 8, 3);

    /** Long edge ÷ thickness. Derived, so it can never disagree with the published ratio. */
    val aspect: Float get() = longUnits.toFloat() / thickUnits.toFloat()

    /** The slot as a module author writes it: long edge first, thickness second. */
    val horizontalRatio: String get() = "$longUnits × $thickUnits"
    val verticalRatio: String get() = "$thickUnits × $longUnits"
}

/**
 * What the module panel does with itself (roadmap 1.6.9).
 *
 * **The default is [OFF], and that is the whole point** *(Roger, 2026-08-27)*. DASH ships with no
 * module panel until the user asks for one. It has no opinion about how much of somebody's screen a
 * head unit should spend, so it spends none until told — which also retires the question of what a
 * sensible default *size* would be, since there is no default panel to size.
 *
 * **The four states are one choice, and the settings page is built from it.** Picking one decides
 * which other controls exist at all: [OFF] needs nothing, [FULL] needs a size, [RETRACTED] adds a
 * timer, [SHRUNK] adds a resting size as well. The constraint that a resting size must be thinner
 * than the full size is therefore *structural* — the second list is built from the first, so an
 * invalid pairing cannot be expressed and never has to be detected, warned about or corrected.
 *
 * **[SHRUNK] is shrinking; the overflow mitigation is *compacting*** *(Roger, 2026-08-27)*. The two
 * were briefly the same word and are not the same thing. Shrinking is a feature: a panel resting at
 * a smaller layout its author drew on purpose. Compacting is mitigation: DASH scaling a panel that
 * asked for more screen than exists, so a bad choice degrades instead of drawing wrong. *"The
 * byproduct of a stupid user selecting the wrong panel layout size is mitigation of that stupidity,
 * not a feature."*
 */
@Serializable
enum class PanelVisibility(val label: String) {
    /** No panel at all, and therefore no tab bar. The screen is entirely the user's. */
    OFF("Off"),

    /** Always drawn at its full size. Never expands, never retracts — the 1.6.2–1.6.8 behaviour. */
    FULL("Full"),

    /** Rests off screen behind its own edge. A tap on the tab bar draws it out; the timer folds it back. */
    RETRACTED("Retracted"),

    /** Rests at a smaller layout the module shipped. A tap expands it to full; the timer shrinks it back. */
    SHRUNK("Shrunk");

    /** Whether a tap on the tab bar has a larger state to open into. */
    val expands: Boolean get() = this == RETRACTED || this == SHRUNK
}

/** What each tab carries (roadmap 1.6.9). The 1.6.8 bar showed names only, so that is the default. */
@Serializable
enum class TabStyle(val label: String) {
    NAME("Names"),
    PIPS("Pips"),
    BOTH("Both"),
}

/**
 * How the tabs sit along the bar (roadmap 1.6.9 — *Spread*, Roger's word). [FILL] shares the whole
 * edge between the tabs, which is the 1.6.8 behaviour; the other three size each tab to what it
 * carries and gather them at the start, the middle or the end of the edge. Start is left on a
 * horizontal bar and top on a vertical one.
 */
@Serializable
enum class TabSpread(val label: String) {
    FILL("Fill"),
    START("Start"),
    CENTRE("Centre"),
    END("End"),
}

/**
 * Which theme surface the bar takes (roadmap 1.6.9). Both are the theme's own pairings, never a
 * free colour: [LIGHT] is the primary surface with a dark selected pill — the 2026-08-26 pairing —
 * and [DARK] swaps them. The tokens themselves stay the user's, in Appearance.
 */
@Serializable
enum class TabColour(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
}

/** How long the panel stays expanded before folding back. Seconds, on a stepper like every other size in DASH. */
object PanelDwellSpec {
    const val DEFAULT_SECONDS = 10
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60
    const val STEP_SECONDS = 5
}

/**
 * The module panel's configuration (roadmap 1.6.3).
 *
 * [edge] is the user's **preference**, not necessarily where the panel is drawn — see
 * [effectiveEdge]. Deliberately shaped to grow: [size] arrived at 1.6.4, and persistent / floating
 * mode lands at 1.6.9, which belongs here rather than as a loose key.
 */
@Serializable
data class ModulePanelConfig(
    val edge: PanelEdge = PanelEdge.BOTTOM,
    /**
     * What the panel does with itself, and the control every other control on the page hangs off.
     * **Defaults to [PanelVisibility.OFF]** — DASH draws no panel until asked.
     */
    val visibility: PanelVisibility = PanelVisibility.OFF,
    /** The panel's **full** size — what it is when expanded, or simply what it is when [PanelVisibility.FULL]. */
    val size: PanelSize = PanelSize.LARGE,
    /**
     * The size it rests at under [PanelVisibility.SHRUNK]. Always thinner than [size], which the
     * settings page enforces by construction rather than by validation.
     */
    val restSize: PanelSize = PanelSize.SMALL,
    /** Seconds expanded before folding back to rest. Bounds in [PanelDwellSpec]. */
    val dwellSeconds: Int = 10,
    /**
     * How thick the module selector bar is, in dp. Bounds and reasoning live with the bar itself, in
     * [com.dash.android.ui.modulepanel.ModuleTabsSpec].
     *
     * **Stored here, controlled from the Module Panel page** — the bar is part of the panel assembly,
     * so its size travels with the panel's edge and size rather than in a loose key of its own. The
     * default is written as a literal rather than as `ModuleTabsSpec.DEFAULT_DP` on purpose: this is
     * a serialised field, and a default that moves when a constant is edited would silently
     * reinterpret every config already written to disk without that value.
     */
    val tabThicknessDp: Int = 36,
    /**
     * Whether the tab bar is drawn at all (roadmap 1.6.9). **Hidden is a plain preference and needs
     * no guarding** — rule 2 already guarantees settings can open whatever the panel is doing, so
     * Layout › Module Panel is reachable from any configuration and the bar can always be brought
     * back. What hiding costs is the user's to judge: no switching, and under
     * [PanelVisibility.RETRACTED] nothing to pull the panel out with.
     */
    val tabShown: Boolean = true,
    /** What a tab shows — the module's name, a pip, or both. */
    val tabStyle: TabStyle = TabStyle.NAME,
    /** Whether the tabs share the whole edge or gather at one end or the middle of it. */
    val tabSpread: TabSpread = TabSpread.FILL,
    /** Which way round the bar takes the theme's two surfaces. */
    val tabColour: TabColour = TabColour.LIGHT,
    /**
     * The tab order, as module ids (roadmap 1.6.11). Set from Modules › Module Management with up /
     * down arrows. Ids not listed — a module installed since — follow in name order, so a new
     * module simply joins the end and nothing has to be written for it.
     */
    val order: List<String> = emptyList(),
    /**
     * The main module, or null for none — **none is the default** *(Roger, 2026-10-01)*. With none,
     * DASH reopens on whichever module was last shown (1.6.8). With one, DASH starts on it, and
     * [returnSeconds] can hand the panel back to it.
     */
    val mainModuleId: String? = null,
    /** Seconds another module stays up before the main one returns. **0 is Never, the default.** */
    val returnSeconds: Int = 0,
) {
    /**
     * The bar's thickness as the layout pays for it — zero when hidden. Every measurement of the
     * assembly goes through this rather than [tabThicknessDp], so a hidden bar gives its space
     * back to the viewport and rule 2 measures the screen that is really there.
     */
    val tabBarDp: Int get() = if (tabShown) tabThicknessDp else 0

    /**
     * The size the panel **rests** at, or null when nothing rests — [PanelVisibility.OFF] has no
     * panel and [PanelVisibility.RETRACTED] rests off screen behind its edge.
     *
     * **This is the size the viewport is laid out for, always** *(Roger, 2026-08-27)*. The expanded
     * panel is drawn *over* the viewport rather than pushing it, so the running app never relayouts
     * when the panel opens — and the user pays for the state the panel is usually in rather than the
     * state it is briefly in.
     */
    val restingSize: PanelSize?
        get() = when (visibility) {
            PanelVisibility.OFF -> null
            PanelVisibility.FULL -> size
            PanelVisibility.RETRACTED -> null
            PanelVisibility.SHRUNK -> restSize
        }

    /** [modules] in the user's tab order, unlisted ones after in name order. */
    fun <T> inOrder(modules: List<T>, id: (T) -> String, name: (T) -> String): List<T> =
        modules.sortedWith(
            compareBy<T> { m -> order.indexOf(id(m)).let { if (it < 0) Int.MAX_VALUE else it } }
                .thenBy { name(it).lowercase() }
        )

    companion object {
        fun default() = ModulePanelConfig()

        /** The return timer's stops, in seconds. 0 is Never. */
        val RETURN_STOPS = listOf(0, 10, 20, 30, 45, 60, 90, 120, 180, 300)

        /** Resting sizes that have something thicker to expand into — everything but the thickest. */
        fun restChoices(): List<PanelSize> =
            PanelSize.entries.filter { r -> PanelSize.entries.any { it.aspect < r.aspect } }
                .sortedByDescending { it.aspect }

        /** Full sizes thicker than [rest]. A higher aspect is a thinner panel, so thicker means lower. */
        fun fullChoices(rest: PanelSize): List<PanelSize> =
            PanelSize.entries.filter { it.aspect < rest.aspect }.sortedByDescending { it.aspect }
    }
}

/**
 * Where the panel actually draws, given the user's preference and where the bar currently is.
 *
 * **The panel and the system bar never share an edge and never stack** (Roger, 1.6.2). The bar is
 * senior, so it is the panel that yields — and DASH yields it *for* the user rather than leaving
 * them to discover the collision and fix it themselves.
 *
 * **The preference is never overwritten.** A collision displaces the panel to the opposite edge for
 * as long as it lasts; move the bar away and the panel returns to the edge the user chose. Storing
 * the displacement instead would let DASH quietly rewrite a user's setting and never give it back,
 * which is the opposite of getting out of the way. Only horizontal preferences can ever collide —
 * the bar occupies top or bottom, so a left- or right-docked panel is always safe.
 */
fun effectiveEdge(preferred: PanelEdge, bar: BarPosition): PanelEdge {
    val barEdge = if (bar == BarPosition.TOP) PanelEdge.TOP else PanelEdge.BOTTOM
    return if (preferred == barEdge) preferred.opposite else preferred
}
