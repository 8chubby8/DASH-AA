package com.dash.android.ui.settings.content

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.common.HEADING
import com.dash.android.ui.common.HEADING_LINE
import com.dash.android.ui.common.SECTION_HEADER_GAP
import com.dash.android.ui.common.SUBHEADING_LINE
import com.dash.android.ui.common.HeadingRule
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.SUBHEADING
import com.dash.android.ui.common.BODY_LINE

/**
 * Reusable building blocks for a settings content box (roadmap 1.5.3). Every subcategory that goes
 * live fills the same scaffold — a header, then a stack of setting blocks, each pairing a control
 * with an optional live preview — so the whole panel reads as one system wherever you are in it.
 *
 * Everything here lives on the *secondary* surface (the content box is [DashTheme.backgroundColourSecondary]),
 * so the pairing rule holds: text and structure are drawn in the secondary set. Selected chips flip
 * to the light secondary-text colour with the box colour as their ink, which is the one high-contrast
 * accent the muted default theme affords without crossing the pairing.
 */

/**
 * Header zone — identical on every subcategory and every section within one: a title and the art-deco
 * rule beneath it. Nothing else.
 *
 * **There is deliberately no description.** It used to take an optional one, and the result was that
 * some pages had a paragraph between the title and the rule and some didn't, so no two tabs opened
 * the same way (roadmap 1.5.15, Roger). Removing the parameter rather than merely emptying the call
 * sites is what keeps it that way — the rule now enforces itself instead of relying on whoever writes
 * the next tab remembering it.
 *
 * Anything a user genuinely needs told belongs on the control it concerns, as [SettingBlock]'s `help`
 * — beside the thing it explains rather than in a preamble above everything.
 */
@Composable
fun SettingsContentHeader(title: String) {
    val theme = LocalDashTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = theme.textColourSecondary,
            fontSize = HEADING,
            lineHeight = HEADING_LINE,
            fontFamily = theme.font,
            letterSpacing = 0.5.sp,
        )
        HeadingRule(theme.textColourSecondary)
    }
}

/**
 * A section heading *within* a page — the second rank, under the page's own
 * [SettingsContentHeader]. One tier down in type, so a page reads as a title and then its sections
 * rather than as a stack of equal shouts (roadmap 1.5.15, Roger).
 *
 * **No rule beneath it.** The rule is what marks the top of a page; giving it to every section too
 * would spend the one piece of punctuation DASH has on something that is not the page's title, and
 * the hierarchy stops being visible at a glance.
 */
@Composable
fun SettingsSectionHeader(title: String) {
    val theme = LocalDashTheme.current
    Text(
        title,
        color = theme.textColourSecondary,
        fontSize = SUBHEADING,
        lineHeight = SUBHEADING_LINE,
        fontFamily = theme.font,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = SECTION_HEADER_GAP),
    )
}

/**
 * One setting: name + optional help on one side, the control on the other, with an optional
 * full-width live preview beneath. Splits left/right when the box is wide enough, stacks when it
 * isn't — the responsive behaviour that keeps it readable from a phone to a head unit.
 *
 * [fullWidthControl] is the **escape hatch** from the right-hand-column rule described on
 * [com.dash.android.ui.common.CONTROL_WIDTH]: a control that cannot be used at the shared width
 * stacks under its label at full width instead. Transitions' six-speed segments are the case it
 * exists for. Do not reach for it to make something look better — only when it is otherwise unusable.
 */
@Composable
fun SettingBlock(
    name: String,
    help: String? = null,
    tag: String? = null,
    fullWidthControl: Boolean = false,
    control: @Composable () -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    val theme = LocalDashTheme.current
    val label: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, color = theme.textColourSecondary, fontSize = BODY, fontFamily = theme.font)
            if (!help.isNullOrBlank()) {
                Text(
                    help,
                    color = theme.textColourSecondary.copy(alpha = 0.68f),
                    fontSize = BODY,
                    lineHeight = BODY_LINE,
                    fontFamily = theme.font,
                )
            }
            if (tag != null) Tag(tag)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A control that wants the full width (a wide segmented selector) always stacks under its
        // label, even where the block would otherwise split label-left / control-right — a six-cell
        // segment has nowhere near enough room in the right-hand nook.
        val wide = maxWidth >= 520.dp && !fullWidthControl
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { label() }
                    control()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    label()
                    control()
                }
            }
            preview?.invoke()
        }
    }
}

@Composable
private fun Tag(text: String) {
    val theme = LocalDashTheme.current
    Text(
        text.uppercase(),
        color = theme.textColourSecondary.copy(alpha = 0.9f),
        fontSize = TINY,
        letterSpacing = 1.sp,
        fontFamily = theme.font,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A rounded segmented selector — the preset control (density, bar position, and so on). */
@Composable
fun PresetSegment(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val theme = LocalDashTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) theme.textColourSecondary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l,
                    color = if (sel) theme.backgroundColourSecondary else theme.textColourSecondary.copy(alpha = 0.75f),
                    fontSize = BODY,
                    fontFamily = theme.font,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A segmented selector that **shrinks to fit** rather than scrolling. The cells share the width
 * equally, and the label font is measured down until the longest label fits its cell — so a control
 * with many long options (the six transition speeds) stays one tidy row on a phone in portrait
 * instead of truncating and side-scrolling. Give it the full width (it fills what it's handed).
 */
@Composable
fun FitPresetSegment(
    labels: List<String>,
    selected: Int,
    maxFontSp: Float = 12.5f,
    minFontSp: Float = 8f,
    onSelect: (Int) -> Unit,
) {
    val theme = LocalDashTheme.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val outerPad = 3.dp
    val gap = 2.dp
    val chipHPad = 6.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = labels.size.coerceAtLeast(1)
        // Width left for a cell's *text* once the frame, gaps and per-chip padding are removed.
        val chrome = outerPad * 2 + gap * (n - 1) + chipHPad * 2 * n
        val perCellTextPx = with(density) { ((maxWidth - chrome) / n).coerceAtLeast(0.dp).toPx() }

        // Largest font (stepping down from max) at which the widest label still fits a cell.
        val fontSp = remember(labels, perCellTextPx, theme.font) {
            var fs = maxFontSp
            while (fs > minFontSp) {
                val widest = labels.maxOf { l ->
                    measurer.measure(
                        AnnotatedString(l),
                        style = TextStyle(fontSize = fs.sp, fontFamily = theme.font),
                    ).size.width
                }
                if (widest <= perCellTextPx) break
                fs -= 0.5f
            }
            fs
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(theme.textColourSecondary.copy(alpha = 0.08f))
                .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
                .padding(outerPad),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, l ->
                val sel = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) theme.textColourSecondary else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = chipHPad, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        l,
                        color = if (sel) theme.backgroundColourSecondary else theme.textColourSecondary.copy(alpha = 0.75f),
                        fontSize = fontSp.sp,
                        fontFamily = theme.font,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A ± stepper for a fluid value — the size controls (bar, elements, DASH text) and anything else
 * dialled up and down. [sub] is an optional caption under the value; [enabled] false greys it and
 * stops the buttons, for a control whose feature has not landed yet.
 */
@Composable
fun Stepper(
    value: String,
    sub: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val theme = LocalDashTheme.current
    val ink = if (enabled) theme.textColourSecondary else theme.textColourSecondary.copy(alpha = 0.4f)
    // The readout scales with the text, so "72 dp" stays on one line at any DASH text size — at 1.4x
    // a fixed 66dp box wrapped the unit onto a second line (roadmap 1.5.15, Roger). One width for
    // every stepper on the page, so a column of them lines up whatever each one happens to read.
    val fontScale = LocalDensity.current.fontScale
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = if (enabled) 0.08f else 0.04f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = if (enabled) 0.18f else 0.1f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        // SpaceBetween so a stepper given the shared control width puts its buttons at the ends and
        // the readout in the middle; with no width imposed it still sizes to its content.
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepButton("−", ink, enabled, onMinus)
        Column(
            modifier = Modifier.weight(1f, fill = false).widthIn(min = (STEPPER_VALUE_WIDTH * fontScale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = ink, fontSize = SUBHEADING, fontFamily = theme.font, maxLines = 1, softWrap = false)
            if (sub != null) {
                Text(
                    sub.uppercase(),
                    color = ink.copy(alpha = 0.55f),
                    fontSize = TINY,
                    letterSpacing = 1.2.sp,
                    fontFamily = theme.font,
                )
            }
        }
        StepButton("+", ink, enabled, onPlus)
    }
}

@Composable
private fun StepButton(sign: String, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, color = ink, fontSize = SUBHEADING, fontFamily = theme.font)
    }
}

/** A raised card that shows the effect of a setting live, so the change is seen and not just read. */
@Composable
fun LivePreviewCard(label: String, content: @Composable () -> Unit) {
    val theme = LocalDashTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.06f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.14f), RoundedCornerShape(11.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                color = theme.textColourSecondary.copy(alpha = 0.55f),
                fontSize = TINY,
                letterSpacing = 1.6.sp,
                fontFamily = theme.font,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(1.dp).background(theme.textColourSecondary.copy(alpha = 0.14f)))
        }
        content()
    }
}

/** A quiet inline action — deep-links out, "reset", and the like. [colour] overrides the default ink
 *  for the few actions that carry a meaning of their own (a PAUSE that is green, a CLEAR that is red). */
@Composable
fun LinkButton(text: String, colour: Color? = null, onClick: () -> Unit) {
    val theme = LocalDashTheme.current
    Text(
        text,
        color = colour ?: theme.textColourSecondary,
        fontSize = TINY,
        fontFamily = theme.font,
        textAlign = TextAlign.End,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/**
 * A QR code, in the white panel it needs (roadmap 1.5.14).
 *
 * The white matte is not decoration. A QR is read as dark modules on a light field, and the settings
 * surface is [DashTheme.backgroundColourSecondary] — dark since 1.5.12 — so a code dropped straight
 * onto it is inverted and many scanners will not take it. The panel restores the contrast the format
 * assumes.
 *
 * Drawn with [FilterQuality.None] on purpose: a QR is hard-edged squares, and bilinear smoothing on
 * scale-up softens the module edges, which is exactly what a scanner is trying to find.
 */
@Composable
fun QrPanel(code: ImageBitmap, contentDescription: String, size: Dp = 108.dp) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(6.dp)
    ) {
        Image(
            bitmap = code,
            contentDescription = contentDescription,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * A link out of DASH: what it is, where it goes, a QR code, and an OPEN button (roadmap 1.5.14).
 *
 * **Why the QR is the primary affordance and the button is the convenience.** DASH runs on head
 * units that may have no browser installed, no keyboard, and no practical way to type a URL — and
 * the person who wants the link is usually stood beside the car holding the phone that should
 * receive it. So the code is always drawn, and [onOpen] is only offered when something on the device
 * can actually handle it: the caller passes null when nothing resolves, and the button is simply
 * absent rather than present and dead. Capability detection, applied to an intent.
 */
@Composable
fun LinkRow(
    label: String,
    url: String,
    qr: ImageBitmap,
    onOpen: (() -> Unit)?,
) {
    val theme = LocalDashTheme.current
    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = theme.textColourSecondary, fontSize = MAINBODY, fontFamily = theme.font)
            Text(
                url,
                color = theme.textColourSecondary.copy(alpha = 0.68f),
                fontSize = BODY,
                lineHeight = BODY_LINE,
                fontFamily = theme.font,
            )
        }
    }

    // The whole row opens the link — the address is the control, not a caption beside a button. Where
    // nothing on the device can open it the row is inert and loses its chevron, and the QR code is
    // then the only way through, which is exactly what it is there for.
    val tappable = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .then(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier)
        .padding(horizontal = 10.dp, vertical = 10.dp)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 420.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = tappable,
            ) {
                Box(Modifier.weight(1f)) { details() }
                QrPanel(qr, "$label QR code")
            }
        } else {
            Column(
                modifier = tappable,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { details() }
                    }
                QrPanel(qr, "$label QR code")
            }
        }
    }
}


/**
 * A block of label-and-value lines — the device report, and anything else that is read rather than
 * changed.
 *
 * **The label column is measured, not a constant.** It is sized to the widest label actually present
 * at the size it is actually drawn, and re-measured when the font or the text scale changes. A fixed
 * width is wrong for the same reason it was wrong on the Serial Monitor's columns in 1.5.13: DASH
 * lets the user change its text size, so any number chosen here wraps "Android font scale" onto two
 * lines for somebody. Taking the whole block together is what makes that possible — a row on its own
 * cannot know how wide its siblings' labels are.
 *
 * Below [STACK_BELOW] there is genuinely not room for two columns, so the value drops under its
 * label rather than being squeezed into a strip a few characters wide.
 */
@Composable
fun InfoRows(rows: List<Pair<String, String>>, spacing: Dp = 9.dp) {
    val theme = LocalDashTheme.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = BODY, fontFamily = theme.font)

    val labelWidth = with(LocalDensity.current) {
        (rows.maxOfOrNull { measurer.measure(AnnotatedString(it.first), labelStyle).size.width } ?: 0)
            .toDp()
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= STACK_BELOW
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            rows.forEach { (label, value) ->
                val name: @Composable () -> Unit = {
                    Text(
                        label,
                        color = theme.textColourSecondary.copy(alpha = 0.62f),
                        fontSize = BODY,
                        fontFamily = theme.font,
                        maxLines = 1,
                    )
                }
                val reading: @Composable () -> Unit = {
                    Text(
                        value,
                        color = theme.textColourSecondary,
                        fontSize = BODY,
                        lineHeight = BODY_LINE,
                        fontFamily = theme.font,
                        textAlign = if (wide) TextAlign.End else TextAlign.Start,
                    )
                }
                if (wide) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.width(labelWidth)) { name() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { reading() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        name()
                        reading()
                    }
                }
            }
        }
    }
}

private val STACK_BELOW = 420.dp

/** One row of a [DashMenu] — what the user reads, and the value it stands for. */
data class MenuOption(val label: String, val value: String)

/**
 * A DASH dropdown menu (roadmap 1.5.12).
 *
 * Replaces Material's `DropdownMenu`, which was the one thing on a DASH surface that could not be
 * styled from its call site: a Material component reads its colours from `MaterialTheme`, and DASH
 * has never provided one — deliberately, since it owns its own token system. Every other Material
 * component DASH uses (`Text`, above all) takes an explicit colour at every call site and so never
 * consults it, which is why the gap stayed invisible until a menu appeared.
 *
 * Built on `Popup` from compose-ui rather than anything in material3, so it inherits nothing from
 * Google's design language: DASH's surface, DASH's border, DASH's font, DASH's ink. Anchor it by
 * placing it inside the `Box` that holds the control it belongs to — it positions itself directly
 * beneath.
 *
 * The rows are set at 13.5sp rather than the 12sp Material used, which was small enough to be worth
 * complaining about.
 */
@Composable
fun DashMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<MenuOption>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    if (!expanded) return
    val theme = LocalDashTheme.current
    val ink = theme.textColourSecondary
    val shape = RoundedCornerShape(8.dp)
    val rowStyle = TextStyle(fontSize = MAINBODY, fontFamily = theme.font)

    // Width is measured from the widest label rather than left to the layout. A Popup is given the
    // whole window as its maximum, so a child calling fillMaxWidth expands to the full screen — which
    // is exactly what the first cut did. Intrinsic sizing is not an option either, because it cannot
    // be measured through a scrolling container. So: measure the text, add the padding, clamp.
    val measurer = rememberTextMeasurer()
    val widest = options.maxOfOrNull { measurer.measure(AnnotatedString(it.label), rowStyle).size.width } ?: 0
    val menuWidth = with(LocalDensity.current) { widest.toDp() + ROW_PAD * 2 }
        .coerceIn(MENU_MIN_WIDTH, MENU_MAX_WIDTH)

    Popup(
        popupPositionProvider = BelowAnchor,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .shadow(10.dp, shape)
                .clip(shape)
                .background(theme.backgroundColourSecondary)
                .border(1.dp, ink.copy(alpha = 0.35f), shape)
                .width(menuWidth)
                // Capped so a filter over a chatty bus — every module id it has ever seen — scrolls
                // rather than running off the screen.
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                Text(
                    option.label,
                    color = if (isSelected) ink else ink.copy(alpha = 0.78f),
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) ink.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(option.value); onDismiss() }
                        .padding(horizontal = ROW_PAD, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Drops the menu *below* its anchor, and keeps it on screen.
 *
 * `Popup`'s `alignment` parameter positions the popup **within** the anchor's bounds, so `BottomStart`
 * aligns its bottom edge with the anchor's bottom and it grows *upwards* — which is what the first cut
 * did. Dropping downwards means offsetting by the anchor's height, and only a position provider is
 * given that: it receives the anchor's bounds and the measured popup size, so it can also nudge the
 * menu left when it would overflow the right edge, and flip it above the anchor when there genuinely
 * is not room below.
 */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** The stepper readout's width at 1.0 text scale; it grows with the text from there. Sized to hold
 *  the widest thing a stepper says — a three-digit value and its unit, "120 dp". */
private const val STEPPER_VALUE_WIDTH = 78

private val ROW_PAD = 14.dp
private val MENU_MIN_WIDTH = 110.dp
private val MENU_MAX_WIDTH = 320.dp
