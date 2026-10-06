package com.dash.android.ui.systembar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dash.android.ui.theme.LocalDashTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val RULER_HEIGHT = 44.dp
private val ARROW_TOUCH_WIDTH = 80.dp
private val ARROW_SIZE_DP = 10.dp
private val ELEMENT_BOX_HEIGHT = 32.dp
private val ELEMENT_BOX_MIN_WIDTH_DP = 48.dp
private val SNAP_THRESHOLD_DP = 4.dp
private val DETENT_MARKER_HEIGHT = 16.dp
private val BOUND_EDGE_WIDTH = 3.dp

private val SNAP_COLOR = Color(0xFFE53935)
private val SNAP_FRACTIONS = listOf(0.25f, 1f / 3f, 0.5f, 2f / 3f, 0.75f)

/**
 * The edit mode ruler — a horizontal strip that appears adjacent to the system bar when edit mode
 * is active. The bar itself is never touched during editing; all interaction happens here.
 *
 * Visually the ruler is transparent — a single 1dp centre line using [accentColourSecondary] floats in the
 * touch area with element boxes and divider arrows overlaid on top of it. No filled background.
 *
 * Zone dividers are represented as triangle arrow markers pointing back toward the bar, and are
 * fully draggable, clamped to a minimum zone width of 48dp. Snap detents sit at 1/4, 1/3, 1/2,
 * 2/3, and 3/4 of bar width with a 4dp pull threshold. The escape mechanic ensures that picking
 * up a divider already settled at a snap point gives immediate free movement; snap re-engages once
 * the divider moves past the threshold distance. Detent position markers appear on the ruler while
 * a divider is being dragged and disappear on release. The arrow turns red when settled at a snap,
 * and brightens immediately on press as pick-up confirmation.
 *
 * Elements are represented as footprint-sized outline boxes (stroke only, no fill). Edges that are
 * bound to a zone boundary or an adjacent element show a red tint. Dragging an element box across
 * a zone boundary moves the element to that zone. The stroke thickens immediately on press as
 * pick-up confirmation — before any drag movement — and returns to resting weight on release.
 *
 * [elementWidths] is populated by [SystemBar] via [onSizeChanged] on each element's Box — it
 * carries each element's measured pixel width at the current [SystemBarConfig.elementHeightDp].
 * On first entry to edit mode this map is already populated from the bar's prior rendering.
 */
@Composable
fun EditRuler(
    config: SystemBarConfig,
    elementWidths: Map<String, Int>,
    barPosition: BarPosition,
    onConfigChange: (SystemBarConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalDashTheme.current
    val density = LocalDensity.current
    val minZonePx = remember(density) { with(density) { 48.dp.toPx() } }
    val paddingPx = remember(density) { with(density) { 8.dp.roundToPx() } }
    val minBoxWidthPx = remember(density) { with(density) { ELEMENT_BOX_MIN_WIDTH_DP.roundToPx() } }

    var draggedElementId by remember { mutableStateOf<String?>(null) }
    var elementDragOffsetPx by remember { mutableFloatStateOf(0f) }
    var activeDividerIndex by remember { mutableStateOf<Int?>(null) }

    val boundEdges = remember(config) { computeBoundEdges(config) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
    ) {
        val rulerWidthPx = constraints.maxWidth.toFloat()
        val currentConfig by rememberUpdatedState(config)

        val naturalPositions = remember(config, elementWidths, rulerWidthPx, paddingPx) {
            computeElementPositions(config, elementWidths, rulerWidthPx.toInt(), paddingPx)
        }

        // Centre track line
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = theme.accentColourSecondary.copy(alpha = 0.35f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Detent markers — visible only while a divider is being dragged
        AnimatedVisibility(
            visible = activeDividerIndex != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val markerWidthPx = 1.dp.toPx()
                val markerHeightPx = DETENT_MARKER_HEIGHT.toPx()
                val markerTop = (size.height - markerHeightPx) / 2f
                SNAP_FRACTIONS.forEach { fraction ->
                    val x = fraction * size.width
                    drawRect(
                        color = theme.accentColourSecondary.copy(alpha = 0.4f),
                        topLeft = Offset(x - markerWidthPx / 2f, markerTop),
                        size = Size(markerWidthPx, markerHeightPx)
                    )
                }
            }
        }

        // Zone divider lines and arrow markers
        var cumulativeFraction = 0f
        config.zones.forEachIndexed { index, zone ->
            if (index > 0) {
                val dividerX = (cumulativeFraction * rulerWidthPx).roundToInt()

                key("line_${zone.id}") {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(dividerX, 0) }
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(theme.accentColourSecondary.copy(alpha = 0.3f))
                    )
                }

                key("arrow_${zone.id}") {
                    DividerArrow(
                        config = config,
                        dividerIndex = index - 1,
                        dividerXPx = dividerX,
                        barPosition = barPosition,
                        rulerWidthPx = rulerWidthPx,
                        minZonePx = minZonePx,
                        onConfigChange = onConfigChange,
                        onDragStart = { activeDividerIndex = index - 1 },
                        onDragEnd = { activeDividerIndex = null },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
            cumulativeFraction += zone.widthFraction
        }

        // Element footprint boxes
        config.zones.forEach { zone ->
            zone.elements.forEach { placement ->
                val naturalX = naturalPositions[placement.id] ?: 0
                val widthPx = (elementWidths[placement.id] ?: minBoxWidthPx).coerceAtLeast(minBoxWidthPx)
                val isDragging = draggedElementId == placement.id
                val currentXPx = (naturalX + if (isDragging) elementDragOffsetPx.roundToInt() else 0)
                    .coerceIn(0, (rulerWidthPx.toInt() - widthPx).coerceAtLeast(0))
                val dragTargetId: String? = if (isDragging) {
                    val dragRight = currentXPx + widthPx
                    config.zones.flatMap { it.elements }.firstOrNull { other ->
                        if (other.id == placement.id) return@firstOrNull false
                        val otherLeft = naturalPositions[other.id] ?: return@firstOrNull false
                        val otherRight = otherLeft + (elementWidths[other.id] ?: minBoxWidthPx)
                        currentXPx < otherRight && dragRight > otherLeft
                    }?.id
                } else null

                val (leftBound, rightBound) = if (isDragging) {
                    if (dragTargetId != null) {
                        val targetAnchor = config.zones.flatMap { it.elements }
                            .firstOrNull { it.id == dragTargetId }?.anchor
                        when (targetAnchor) {
                            ElementAnchor.LEFT -> Pair(true, false)
                            ElementAnchor.RIGHT -> Pair(false, true)
                            else -> Pair(false, false)
                        }
                    } else {
                        val dragCenterX = currentXPx + widthPx / 2f
                        when (computeIntendedAnchor(config, dragCenterX, rulerWidthPx.toInt())) {
                            ElementAnchor.LEFT -> Pair(true, false)
                            ElementAnchor.RIGHT -> Pair(false, true)
                            ElementAnchor.CENTRE -> Pair(false, false)
                        }
                    }
                } else {
                    boundEdges[placement.id] ?: Pair(false, false)
                }

                key(placement.id) {
                    ElementBox(
                        widthPx = widthPx,
                        xPx = currentXPx,
                        isDragging = isDragging,
                        leftEdgeBound = leftBound,
                        rightEdgeBound = rightBound,
                        onDragStart = {
                            draggedElementId = placement.id
                            elementDragOffsetPx = 0f
                        },
                        onDrag = { delta -> elementDragOffsetPx += delta },
                        onDragEnd = {
                            val dragLeft = (naturalX + elementDragOffsetPx).roundToInt()
                            val dragRight = dragLeft + widthPx
                            val targetId = config.zones.flatMap { it.elements }.firstOrNull { other ->
                                if (other.id == placement.id) return@firstOrNull false
                                val otherLeft = naturalPositions[other.id] ?: return@firstOrNull false
                                val otherRight = otherLeft + (elementWidths[other.id] ?: minBoxWidthPx)
                                dragLeft < otherRight && dragRight > otherLeft
                            }?.id
                            val newConfig = if (targetId != null) {
                                swapElements(currentConfig, placement.id, targetId)
                            } else {
                                val finalCenterX = naturalX + elementDragOffsetPx + widthPx / 2f
                                computeNewConfig(currentConfig, placement.id, finalCenterX, rulerWidthPx.toInt())
                            }
                            onConfigChange(newConfig)
                            draggedElementId = null
                            elementDragOffsetPx = 0f
                        },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
        }
    }
}

@Composable
private fun DividerArrow(
    config: SystemBarConfig,
    dividerIndex: Int,
    dividerXPx: Int,
    barPosition: BarPosition,
    rulerWidthPx: Float,
    minZonePx: Float,
    onConfigChange: (SystemBarConfig) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalDashTheme.current
    val density = LocalDensity.current
    val currentConfig by rememberUpdatedState(config)
    val currentRulerWidthPx by rememberUpdatedState(rulerWidthPx)
    val touchWidthPx = remember(density) { with(density) { ARROW_TOUCH_WIDTH.roundToPx() } }
    val snapThresholdPx = remember(density) { with(density) { SNAP_THRESHOLD_DP.toPx() } }

    var isPressed by remember { mutableStateOf(false) }

    val isSnapped = remember(config, dividerIndex, rulerWidthPx) {
        if (rulerWidthPx <= 0f) false
        else {
            val leftOffset = config.zones.take(dividerIndex).sumOf { it.widthFraction.toDouble() }.toFloat()
            val dividerFraction = leftOffset + (config.zones.getOrNull(dividerIndex)?.widthFraction ?: 0f)
            val thresholdFraction = snapThresholdPx / rulerWidthPx
            SNAP_FRACTIONS.any { abs(dividerFraction - it) <= thresholdFraction }
        }
    }

    val arrowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0.65f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "arrow_alpha"
    )

    val arrowColor = if (isSnapped) SNAP_COLOR else theme.accentColourSecondary.copy(alpha = arrowAlpha)
    val startDp = with(density) { (dividerXPx - touchWidthPx / 2).coerceAtLeast(0).toDp() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(start = startDp)
            .width(ARROW_TOUCH_WIDTH)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    onDragStart()

                    val leftOffset = currentConfig.zones.take(dividerIndex)
                        .sumOf { it.widthFraction.toDouble() }.toFloat()
                    val startDividerFraction = leftOffset +
                        (currentConfig.zones.getOrNull(dividerIndex)?.widthFraction ?: 0f)
                    val thresholdFraction = if (currentRulerWidthPx > 0f) snapThresholdPx / currentRulerWidthPx else 0f

                    val startingAtSnap = SNAP_FRACTIONS.any { abs(startDividerFraction - it) <= thresholdFraction }
                    var snappingEnabled = !startingAtSnap
                    var totalDragPx = 0f

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            val dx = change.positionChange().x
                            change.consume()
                            if (dx != 0f) {
                                totalDragPx += dx

                                if (!snappingEnabled && abs(totalDragPx) > snapThresholdPx) {
                                    snappingEnabled = true
                                }

                                val zones = currentConfig.zones.toMutableList()
                                val leftZone = zones[dividerIndex]
                                val rightZone = zones[dividerIndex + 1]
                                val combinedFraction = leftZone.widthFraction + rightZone.widthFraction
                                val minFraction = minZonePx / currentRulerWidthPx

                                val currentAbsolute = leftOffset + leftZone.widthFraction
                                val rawAbsolute = currentAbsolute + dx / currentRulerWidthPx
                                val minAbsolute = leftOffset + minFraction
                                val maxAbsolute = leftOffset + combinedFraction - minFraction
                                val clampedAbsolute = rawAbsolute.coerceIn(minAbsolute, maxAbsolute)

                                val finalAbsolute = if (snappingEnabled) {
                                    val snapThreshFrac = if (currentRulerWidthPx > 0f) snapThresholdPx / currentRulerWidthPx else 0f
                                    applySnap(clampedAbsolute, snapThreshFrac).coerceIn(minAbsolute, maxAbsolute)
                                } else {
                                    clampedAbsolute
                                }

                                zones[dividerIndex] = leftZone.copy(widthFraction = finalAbsolute - leftOffset)
                                zones[dividerIndex + 1] = rightZone.copy(widthFraction = combinedFraction - (finalAbsolute - leftOffset))
                                onConfigChange(currentConfig.copy(zones = zones))
                            }
                        }
                    } finally {
                        isPressed = false
                        onDragEnd()
                    }
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .width(ARROW_SIZE_DP)
                .height(ARROW_SIZE_DP)
        ) {
            val path = Path()
            if (barPosition == BarPosition.BOTTOM) {
                path.moveTo(size.width / 2f, size.height)
                path.lineTo(0f, 0f)
                path.lineTo(size.width, 0f)
            } else {
                path.moveTo(size.width / 2f, 0f)
                path.lineTo(0f, size.height)
                path.lineTo(size.width, size.height)
            }
            path.close()
            drawPath(path, arrowColor)
        }
    }
}

@Composable
private fun ElementBox(
    widthPx: Int,
    xPx: Int,
    isDragging: Boolean,
    leftEdgeBound: Boolean,
    rightEdgeBound: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalDashTheme.current
    val density = LocalDensity.current
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val strokeWidthDp by animateFloatAsState(
        targetValue = if (isDragging) 2f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "element_box_stroke"
    )
    val strokeAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0.55f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "element_box_alpha"
    )

    val accent2 = theme.accentColourSecondary

    Box(
        modifier = modifier
            .padding(start = with(density) { xPx.toDp() })
            .width(with(density) { widthPx.toDp() })
            .height(ELEMENT_BOX_HEIGHT)
            .drawBehind {
                val strokePx = strokeWidthDp * density.density
                val cornerPx = 4.dp.toPx()
                drawRoundRect(
                    color = accent2.copy(alpha = strokeAlpha),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(width = strokePx)
                )
                val edgePx = BOUND_EDGE_WIDTH.toPx()
                if (leftEdgeBound) drawRect(
                    color = SNAP_COLOR.copy(alpha = 0.85f),
                    topLeft = Offset.Zero,
                    size = Size(edgePx, size.height)
                )
                if (rightEdgeBound) drawRect(
                    color = SNAP_COLOR.copy(alpha = 0.85f),
                    topLeft = Offset(size.width - edgePx, 0f),
                    size = Size(edgePx, size.height)
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentOnDragStart()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            currentOnDragEnd()
                            break
                        }
                        val dx = change.positionChange().x
                        change.consume()
                        if (dx != 0f) currentOnDrag(dx)
                    }
                }
            }
    )
}

/**
 * Computes which edges of each element box are bound — touching a zone boundary or adjacent
 * element. Bound state is determined entirely by anchor group membership and position within
 * the group; element widths are irrelevant because anchor-group packing always packs tight.
 *
 * LEFT group: first element left-bound to zone left boundary; all consecutive edges bound.
 * RIGHT group: last element right-bound to zone right boundary; all consecutive edges bound.
 * CENTRE group: elements bind to each other; outer edges are free (floating in centre space).
 */
private fun computeBoundEdges(config: SystemBarConfig): Map<String, Pair<Boolean, Boolean>> {
    val result = mutableMapOf<String, Pair<Boolean, Boolean>>()
    config.zones.forEach { zone ->
        val leftGroup = zone.elements.filter { it.anchor == ElementAnchor.LEFT }
        val centreGroup = zone.elements.filter { it.anchor == ElementAnchor.CENTRE }
        val rightGroup = zone.elements.filter { it.anchor == ElementAnchor.RIGHT }

        leftGroup.forEachIndexed { i, placement ->
            result[placement.id] = Pair(true, i < leftGroup.lastIndex)
        }
        centreGroup.forEachIndexed { i, placement ->
            result[placement.id] = Pair(i > 0, i < centreGroup.lastIndex)
        }
        rightGroup.forEachIndexed { i, placement ->
            result[placement.id] = Pair(i > 0, true)
        }
    }
    return result
}

private fun swapElements(config: SystemBarConfig, idA: String, idB: String): SystemBarConfig {
    data class Loc(val zoneIdx: Int, val elemIdx: Int, val placement: ElementPlacement)
    var locA: Loc? = null
    var locB: Loc? = null
    config.zones.forEachIndexed { zIdx, zone ->
        zone.elements.forEachIndexed { eIdx, p ->
            if (p.id == idA) locA = Loc(zIdx, eIdx, p)
            if (p.id == idB) locB = Loc(zIdx, eIdx, p)
        }
    }
    val a = locA ?: return config
    val b = locB ?: return config
    return config.copy(zones = config.zones.mapIndexed { zIdx, zone ->
        val newElements = zone.elements.toMutableList()
        if (zIdx == a.zoneIdx) newElements[a.elemIdx] = a.placement.copy(id = b.placement.id, type = b.placement.type, spacerWidthDp = b.placement.spacerWidthDp)
        if (zIdx == b.zoneIdx) newElements[b.elemIdx] = b.placement.copy(id = a.placement.id, type = a.placement.type, spacerWidthDp = a.placement.spacerWidthDp)
        zone.copy(elements = newElements)
    })
}

private fun computeIntendedAnchor(config: SystemBarConfig, centerX: Float, rulerWidthPx: Int): ElementAnchor {
    var cumulativePx = 0
    var targetZoneIdx = config.zones.lastIndex
    for ((idx, zone) in config.zones.withIndex()) {
        val zoneWidthPx = (zone.widthFraction * rulerWidthPx).roundToInt()
        if (centerX < cumulativePx + zoneWidthPx) {
            targetZoneIdx = idx
            break
        }
        cumulativePx += zoneWidthPx
    }
    val targetZoneStartPx = config.zones.take(targetZoneIdx).sumOf { (it.widthFraction * rulerWidthPx).roundToInt() }
    val targetZoneWidthPx = (config.zones[targetZoneIdx].widthFraction * rulerWidthPx).roundToInt()
    val posInZone = centerX - targetZoneStartPx
    return when {
        posInZone < targetZoneWidthPx / 3f -> ElementAnchor.LEFT
        posInZone < targetZoneWidthPx * 2f / 3f -> ElementAnchor.CENTRE
        else -> ElementAnchor.RIGHT
    }
}

private fun applySnap(fraction: Float, thresholdFraction: Float): Float {
    val nearest = SNAP_FRACTIONS.minByOrNull { abs(fraction - it) } ?: return fraction
    return if (abs(fraction - nearest) <= thresholdFraction) nearest else fraction
}

/**
 * Computes the natural (undragged) ruler X position for every element in the config, mirroring
 * the Zone layout's anchor-group packing logic. [paddingPx] matches Zone's horizontal padding.
 */
private fun computeElementPositions(
    config: SystemBarConfig,
    elementWidths: Map<String, Int>,
    rulerWidthPx: Int,
    paddingPx: Int
): Map<String, Int> {
    val positions = mutableMapOf<String, Int>()
    var zoneStartPx = 0

    config.zones.forEach { zone ->
        val zoneWidthPx = (zone.widthFraction * rulerWidthPx).roundToInt()
        val availableWidth = zoneWidthPx - 2 * paddingPx

        val leftElements = zone.elements.filter { it.anchor == ElementAnchor.LEFT }
        val centreElements = zone.elements.filter { it.anchor == ElementAnchor.CENTRE }
        val rightElements = zone.elements.filter { it.anchor == ElementAnchor.RIGHT }

        var x = zoneStartPx + paddingPx
        leftElements.forEach { placement ->
            positions[placement.id] = x
            x += elementWidths[placement.id] ?: 0
        }

        val rightTotal = rightElements.sumOf { elementWidths[it.id] ?: 0 }
        x = zoneStartPx + paddingPx + availableWidth - rightTotal
        rightElements.forEach { placement ->
            positions[placement.id] = x
            x += elementWidths[placement.id] ?: 0
        }

        val centreTotal = centreElements.sumOf { elementWidths[it.id] ?: 0 }
        x = zoneStartPx + paddingPx + (availableWidth - centreTotal) / 2
        centreElements.forEach { placement ->
            positions[placement.id] = x
            x += elementWidths[placement.id] ?: 0
        }

        zoneStartPx += zoneWidthPx
    }

    return positions
}

/**
 * Determines the updated [SystemBarConfig] after an element is dropped at [dropCenterX] in ruler
 * coordinates. The target zone is the zone whose pixel range contains the drop point. The new
 * anchor is inferred from the drop position within that zone: left third → LEFT, middle third →
 * CENTRE, right third → RIGHT. The element is appended to the end of the target zone's list.
 */
private fun computeNewConfig(
    config: SystemBarConfig,
    elementId: String,
    dropCenterX: Float,
    rulerWidthPx: Int
): SystemBarConfig {
    var movingPlacement: ElementPlacement? = null
    config.zones.forEach { zone ->
        zone.elements.forEach { if (it.id == elementId) movingPlacement = it }
    }
    val placement = movingPlacement ?: return config

    var cumulativePx = 0
    var targetZoneIdx = config.zones.lastIndex
    for ((idx, zone) in config.zones.withIndex()) {
        val zoneWidthPx = (zone.widthFraction * rulerWidthPx).roundToInt()
        if (dropCenterX < cumulativePx + zoneWidthPx) {
            targetZoneIdx = idx
            break
        }
        cumulativePx += zoneWidthPx
    }

    val targetZoneStartPx = config.zones.take(targetZoneIdx).sumOf { (it.widthFraction * rulerWidthPx).roundToInt() }
    val targetZoneWidthPx = (config.zones[targetZoneIdx].widthFraction * rulerWidthPx).roundToInt()
    val posInZone = dropCenterX - targetZoneStartPx
    val newAnchor = when {
        posInZone < targetZoneWidthPx / 3f -> ElementAnchor.LEFT
        posInZone < targetZoneWidthPx * 2f / 3f -> ElementAnchor.CENTRE
        else -> ElementAnchor.RIGHT
    }

    val newPlacement = placement.copy(anchor = newAnchor)
    val newZones = config.zones.mapIndexed { zIdx, zone ->
        val withoutElement = zone.elements.filter { it.id != elementId }
        if (zIdx == targetZoneIdx) zone.copy(elements = withoutElement + newPlacement)
        else zone.copy(elements = withoutElement)
    }

    return config.copy(zones = newZones)
}
