package com.dash.android.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.ui.motion.LocalDashTransitions
import com.dash.android.ui.motion.TransitionId
import com.dash.android.ui.settings.content.SettingsContent
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.weather.LocalWeatherSnapshot
import com.dash.android.ui.weather.WeatherScene
import com.dash.android.weather.WeatherArt
import com.dash.android.weather.WeatherSnapshot
import com.dash.android.ui.common.BOX_PAD
import com.dash.android.ui.common.NAV_ROW_INSET
import com.dash.android.ui.common.PANEL_GAP
import com.dash.android.ui.common.TREE_GUTTER
import com.dash.android.ui.common.HEADING_LINE
import com.dash.android.ui.common.HeadingRule
import com.dash.android.ui.common.MAINBODY

/**
 * The DASH settings shell. **Adaptive** (roadmap 1.5.x): it lays itself out from the space actually
 * available, not a fixed shape.
 *
 * - **Wide** (tablet, landscape, head unit): the two-pane model — the category/subcategory tree in
 *   the left margin, the content box beside it. Picking a category only reveals its subtree; the
 *   content box stays on its empty landing until a *subcategory* is chosen (category → sub → content).
 * - **Narrow** (phone portrait): the progressive drill-down from interface.md's original three-level
 *   model — the tree fills the screen; tapping a category replaces it with that category's subtree;
 *   tapping a subcategory replaces that with the content. A back control pinned to the bottom walks
 *   one level down and closes at the top. No landing pane on a narrow screen — there's nothing to
 *   land on until you pick something.
 *
 * The two share one navigation state, so rotating the device (the Activity is not recreated — see
 * the manifest) simply reflows between the two.
 */
private const val WIDE_BREAKPOINT_DP = 600

private data class NarrowScreen(val category: SettingsCategory?, val subId: String?) {
    val depth: Int get() = if (subId != null) 2 else if (category != null) 1 else 0
}

/** Find a subcategory (and its owning category) by id anywhere in the tree. The content box resolves
 *  its sub this way so a single-sub category can open its content directly from the main tree, with no
 *  owning category selected in between (roadmap 1.5.9 removal). */
private fun findSubAcrossTree(id: String?): Pair<SettingsCategory, SettingsSub>? {
    if (id == null) return null
    for (cat in DASH_SETTINGS_TREE) {
        val sub = cat.subs.firstOrNull { it.id == id }
        if (sub != null) return cat to sub
    }
    return null
}

@Composable
fun SettingsShell(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialSubId: String? = null,
) {
    val theme = LocalDashTheme.current
    // Seed the selection from [initialSubId] when opening — the shell is dropped from composition when
    // the blind closes, so a fresh open lands here. Used to return the user to the tab they left when
    // a focused task (bar edit mode) took over the screen, rather than dumping them at the top.
    var selectedCategory by remember {
        // A seeded sub that belongs to a single-sub (leaf) category opens directly, so leave its
        // category unselected — matching how tapping such a category behaves.
        mutableStateOf(
            initialSubId?.let { id ->
                DASH_SETTINGS_TREE.firstOrNull { cat -> cat.subs.any { it.id == id } }?.takeIf { it.subs.size > 1 }
            }
        )
    }
    var selectedSubId by remember { mutableStateOf(initialSubId) }

    // Tapping a category: a multi-sub category drills into its subtree; a single-sub category is a leaf
    // — it opens its one sub's content straight away, with the main tree left in place behind it.
    val selectCategory: (SettingsCategory) -> Unit = { cat ->
        if (cat.subs.size == 1) {
            selectedCategory = null
            selectedSubId = cat.subs.single().id
        } else {
            selectedCategory = cat
            selectedSubId = null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(theme.backgroundColourPrimary)
    ) {
        val wide = maxWidth >= WIDE_BREAKPOINT_DP.dp

        if (wide) {
            WideSettings(
                selectedCategory = selectedCategory,
                selectedSubId = selectedSubId,
                onSelectCategory = selectCategory,
                onSelectSub = { selectedSubId = it },
                onBack = {
                    when {
                        selectedCategory != null -> {
                            selectedCategory = null
                            selectedSubId = null
                        }
                        // A leaf category's content is open with the main tree still showing — back
                        // deselects it (to the landing) rather than closing settings outright.
                        selectedSubId != null -> selectedSubId = null
                        else -> onClose()
                    }
                },
            )
        } else {
            NarrowSettings(
                selectedCategory = selectedCategory,
                selectedSubId = selectedSubId,
                onSelectCategory = selectCategory,
                onSelectSub = { selectedSubId = it },
                onBack = {
                    when {
                        selectedSubId != null -> selectedSubId = null
                        selectedCategory != null -> selectedCategory = null
                        else -> onClose()
                    }
                },
            )
        }
    }
}

// ── Wide: two-pane ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WideSettings(
    selectedCategory: SettingsCategory?,
    selectedSubId: String?,
    onSelectCategory: (SettingsCategory) -> Unit,
    onSelectSub: (String) -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val transitions = LocalDashTransitions.current
    val fontScale = LocalDensity.current.fontScale
    // A leaf category's content shows with the main tree still on the left, so "in a subtree" is true
    // for a drilled category *or* a directly-opened leaf sub — both want BACK, not CLOSE.
    val showBack = selectedCategory != null || selectedSubId != null
    // Breadcrumb names the category even when a leaf sub is open without its category selected.
    val crumbCategory = selectedCategory ?: findSubAcrossTree(selectedSubId)?.first

    // The breadcrumb heads the *tree column*, not the whole shell, so the content box starts level with
    // it and gets the full height of the panel (Roger, 2026-07-27 — the box was starting below the
    // heading, level with the top of the tree, and was noticeably short because of it). The Row's top
    // padding is the breadcrumb's own former top padding, which is what puts the box's top edge on the
    // same line as the heading text.
    // One gap on all four outer margins (roadmap 1.5.15, Roger). They had grown apart — 24 top, 28
    // bottom, 16 at each screen edge — which read as the box sitting slightly wrong rather than as any
    // one margin being obviously off. The gutter between the tree and the box is a different thing —
    // internal spacing, not a margin — and stays its own smaller value.
    Row(modifier = Modifier.fillMaxSize().padding(PANEL_GAP)) {
        // Left margin: tree, or the selected category's subtree. Width scales with the font so
        // long labels don't ellipsise as the text grows.
        Column(
            modifier = Modifier
                .width((260 * fontScale).dp)
                .fillMaxHeight()
                .padding(end = TREE_GUTTER)
        ) {
            // Dropped by the content box's own inner padding so the tree's heading sits on the same
            // line as the heading inside the box (roadmap 1.5.15, Roger). Without it the breadcrumb
            // sat at the panel's top edge while every box header started BOX_PAD lower.
            Breadcrumb(
                crumbCategory?.label?.uppercase() ?: "SETTINGS",
                Modifier.padding(start = NAV_ROW_INSET, top = BOX_PAD, bottom = 14.dp),
            )

            AnimatedContent(
                targetState = selectedCategory,
                transitionSpec = {
                    // Into a subtree is DRILL IN; back to the tree is BACK OUT — each its own speed.
                    if (targetState != null) {
                        val d = transitions.millis(TransitionId.SETTINGS_NAV_DRILL_IN)
                        val slide = tween<IntOffset>(d)
                        (slideInHorizontally(slide) { it / 3 } + fadeIn(tween(d))) togetherWith
                            (slideOutHorizontally(slide) { -it / 3 } + fadeOut(tween(d * 2 / 3)))
                    } else {
                        val d = transitions.millis(TransitionId.SETTINGS_NAV_BACK_OUT)
                        val slide = tween<IntOffset>(d)
                        (slideInHorizontally(slide) { -it / 3 } + fadeIn(tween(d))) togetherWith
                            (slideOutHorizontally(slide) { it / 3 } + fadeOut(tween(d * 2 / 3)))
                    }
                },
                modifier = Modifier.weight(1f),
                label = "tree"
            ) { category ->
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy((4 * fontScale).dp)
                ) {
                    if (category == null) {
                        DASH_SETTINGS_TREE.forEach { cat ->
                            // A leaf category reads as selected when its one sub's content is open.
                            val leafOpen = cat.subs.size == 1 && cat.subs.single().id == selectedSubId
                            NavRow(cat.label, trailing = null, selected = leafOpen) { onSelectCategory(cat) }
                        }
                    } else {
                        category.subs.forEach { sub ->
                            NavRow(sub.label, trailing = null, selected = sub.id == selectedSubId) { onSelectSub(sub.id) }
                        }
                    }
                }
            }

            NavRow(if (showBack) "‹ BACK" else "‹ CLOSE", trailing = null, selected = false) { onBack() }
        }

        // Right: the content box. A crossfade carries the eye between the weather landing and a
        // chosen subcategory's content (and between subcategories) rather than a hard cut.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val sub = findSubAcrossTree(selectedSubId)?.second
            // Crossfade, not AnimatedContent, for the content swap: it re-targets each state's
            // alpha over the full duration when interrupted, so tapping back to a tab whose fade
            // hasn't finished animates home at the chosen speed rather than snapping. At LABORIOUS
            // the interruption window is seconds long, which is where the snap showed up.
            Crossfade(
                targetState = sub,
                animationSpec = tween(transitions.millis(TransitionId.SETTINGS_CONTENT_SWAP)),
                label = "content"
            ) { target ->
                if (target != null) {
                    SettingsContentBox(target, Modifier.fillMaxSize())
                } else {
                    // Landing: the layered weather scene (roadmap 1.5.4). Renders offline from the
                    // device clock; the live weather layer upgrades it when a source is available.
                    WeatherLanding(Modifier.fillMaxSize())
                }
            }
    }
}
}

// ── Narrow: single-pane drill-down ─────────────────────────────────────────────────────────────
@Composable
private fun NarrowSettings(
    selectedCategory: SettingsCategory?,
    selectedSubId: String?,
    onSelectCategory: (SettingsCategory) -> Unit,
    onSelectSub: (String) -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val transitions = LocalDashTransitions.current
    val screen = NarrowScreen(selectedCategory, selectedSubId)
    // Name the category even when a leaf sub is open without its category selected.
    val crumbCategory = selectedCategory ?: findSubAcrossTree(selectedSubId)?.first

    Column(Modifier.fillMaxSize()) {
        Breadcrumb(crumbCategory?.label?.uppercase() ?: "SETTINGS")

        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // The same DRILL IN / BACK OUT pair as the wide layout — one control governs the
                // navigation slide in both, so the user isn't asked to set "the same slide" twice.
                val forward = targetState.depth > initialState.depth
                val d = transitions.millis(
                    if (forward) TransitionId.SETTINGS_NAV_DRILL_IN else TransitionId.SETTINGS_NAV_BACK_OUT
                )
                val slide = tween<IntOffset>(d)
                if (forward) {
                    (slideInHorizontally(slide) { it } + fadeIn(tween(d))) togetherWith
                        (slideOutHorizontally(slide) { -it / 3 } + fadeOut(tween(d * 2 / 3)))
                } else {
                    (slideInHorizontally(slide) { -it / 3 } + fadeIn(tween(d))) togetherWith
                        (slideOutHorizontally(slide) { it } + fadeOut(tween(d * 2 / 3)))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "drill"
        ) { s ->
            when {
                s.subId != null -> {
                    val sub = findSubAcrossTree(s.subId)?.second
                    if (sub != null) {
                        SettingsContentBox(sub, Modifier.fillMaxSize().padding(horizontal = PANEL_GAP))
                    }
                }
                s.category != null -> {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = PANEL_GAP),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        s.category.subs.forEach { sub ->
                            NavRow(sub.label, trailing = null, selected = false) { onSelectSub(sub.id) }
                        }
                    }
                }
                else -> {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = PANEL_GAP),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DASH_SETTINGS_TREE.forEach { cat ->
                            NavRow(cat.label, trailing = null, selected = false) { onSelectCategory(cat) }
                        }
                                }
                }
            }
        }

        // Back control pinned to the bottom — one level down, or close at the top.
        Box(modifier = Modifier.fillMaxWidth().padding(start = PANEL_GAP, end = PANEL_GAP, top = 4.dp, bottom = PANEL_GAP)) {
            NavRow(
                label = if (screen.depth > 0) "‹ BACK" else "‹ CLOSE",
                trailing = null,
                selected = false,
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun Breadcrumb(
    text: String,
    // Positionable because the two layouts place it differently: in the narrow layout it heads the whole
    // screen, in the wide one it heads the *tree column* so the content box can start level with it.
    modifier: Modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 14.dp),
) {
    val theme = LocalDashTheme.current
    // Carries the same art-deco rule as the headings inside the content box (roadmap 1.5.15, Roger),
    // drawn in the primary ink because the tree sits on the light panel rather than the dark box.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = text,
            color = theme.textColourPrimary,
            fontSize = MAINBODY,
            // Same line box as the box's headings, so both rules sit on one line despite the
            // seven-point difference in type size. See HEADING_LINE.
            lineHeight = HEADING_LINE,
            fontFamily = theme.font,
            letterSpacing = 3.sp,
        )
        HeadingRule(theme.textColourPrimary)
    }
}

@Composable
private fun WeatherLanding(modifier: Modifier = Modifier) {
    val theme = LocalDashTheme.current
    val context = LocalContext.current
    val art = remember { WeatherArt(context) }
    // The snapshot is warmed and cached at the composition root (see MainScreen) and provided here, so
    // the scene opens on real weather rather than flashing through the clock-only floor. It falls back
    // to the offline floor only for the first moment after a cold boot, before the warm-up returns.
    val snapshot = LocalWeatherSnapshot.current ?: WeatherSnapshot.clockOnly()
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.backgroundColourSecondary)
    ) {
        WeatherScene(snapshot, art, theme.font, Modifier.fillMaxSize())
    }
}


@Composable
private fun SettingsContentBox(sub: SettingsSub, modifier: Modifier = Modifier) {
    val theme = LocalDashTheme.current
    val surface = modifier
        .clip(RoundedCornerShape(16.dp))
        .background(theme.backgroundColourSecondary)
    if (sub.fillsBox) {
        // A tab that manages its own scrolling gets the box height and no outer scroll or blanket
        // padding — it pins its own controls and scrolls its own body (1.5.8 Module Management).
        Box(surface.fillMaxSize()) {
            SettingsContent(sub)
        }
    } else {
        Column(
            modifier = surface
                .verticalScroll(rememberScrollState())
                .padding(BOX_PAD)
        ) {
            SettingsContent(sub)
        }
    }
}

@Composable
private fun NavRow(
    label: String,
    trailing: String?,
    selected: Boolean,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val fontScale = LocalDensity.current.fontScale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) theme.accentColourPrimary else theme.backgroundColourPrimary)
            .clickable { onClick() }
            .padding(horizontal = NAV_ROW_INSET, vertical = (12 * fontScale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (dim) theme.textColourPrimary.copy(alpha = 0.45f) else theme.textColourPrimary,
            fontSize = MAINBODY,
            fontFamily = theme.font,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (trailing != null) {
            Text(trailing, color = theme.textColourPrimary.copy(alpha = 0.5f), fontSize = MAINBODY, fontFamily = theme.font)
        }
    }
}
