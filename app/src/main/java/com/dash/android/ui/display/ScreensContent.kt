package com.dash.android.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dash.android.display.DisplaySystem
import com.dash.android.display.DisplayState
import com.dash.android.display.Overscan
import com.dash.android.display.Screen
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.TINY
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.InfoRows
import com.dash.android.ui.settings.content.PresetSegment
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.settings.content.Stepper
import com.dash.android.ui.theme.LocalDashTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display › Screens (DASH-AA 1.1.5): every screen plugged in, as the display program has them — which
 * are on and where, which is DASH's main one, which show the same picture, and each one's resolution,
 * refresh rate, scale, adaptive sync and overscan. The machine's own display settings, because on the
 * head unit there is no desktop to have them instead (roadmap 1.1.x).
 *
 * Every change is tried, not made: it applies at once and goes back by itself unless kept
 * ([DisplayTrial]). A screen plugged in for the first time is asked about at the top. A screen's
 * place is which side of the main one it sits; DASH packs them edge to edge.
 *
 * **For native:** Android has one screen and no such settings; native leaves this tab out.
 */
@Composable
fun ScreensContent() {
    val (display, state) = rememberDisplay()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    var chosenId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Screens")
        if (!state.available) { Note(state.note); return@Column }
        KeepBanner()
        state.failure?.let { Note(it) }

        state.screens.filter { it.id in state.unfamiliar }.forEach { NewScreen(it, state, display) }

        val chosen = state.screens.firstOrNull { it.id == chosenId } ?: state.main ?: state.screens.firstOrNull() ?: return@Column
        if (state.screens.size > 1) {
            ScreenPicture(state, chosen.id) { chosenId = it }
            Note("Tap a screen to set it up.")
        }

        SettingsSectionHeader(chosen.name + if (chosen.primary) " — main" else "")
        if (state.screens.size > 1 && state.features.arrange) {
            SettingBlock(
                name = "Use this screen",
                control = {
                    PresetSegment(listOf("Off", "On"), if (chosen.enabled) 1 else 0, controlWidth) { i ->
                        val on = i == 1
                        if (on == chosen.enabled) return@PresetSegment
                        if (!on && state.screens.count { it.enabled && it.mirrorOf == null } <= 1 && chosen.mirrorOf == null) return@PresetSegment
                        tryChange(display, state, if (on) "${chosen.name} on" else "${chosen.name} off",
                            chosen.copy(enabled = on, primary = chosen.primary && on, mirrorOf = null))
                    }
                },
            )
        }
        if (!chosen.enabled) return@Column

        if (state.screens.count { it.enabled } > 1) {
            if (!chosen.primary && chosen.mirrorOf == null) {
                SettingBlock(
                    name = "Main screen",
                    help = "DASH shows on the main screen.",
                    control = {
                        DashButton("Make this the main screen", onClick = {
                            tryChange(display, state, "${chosen.name} as the main screen",
                                *state.screens.map { it.copy(primary = it.id == chosen.id) }.toTypedArray())
                        }, modifier = controlWidth)
                    },
                )
            }
            if (state.features.mirror && !chosen.primary) {
                val main = state.main
                SettingBlock(
                    name = "Shows",
                    control = {
                        PresetSegment(listOf("Its own", "Same as main"), if (chosen.mirrorOf != null) 1 else 0, controlWidth) { i ->
                            val mirror = if (i == 1) main?.id else null
                            if (mirror == chosen.mirrorOf) return@PresetSegment
                            tryChange(display, state, if (mirror != null) "${chosen.name} showing the main screen" else "${chosen.name} on its own",
                                chosen.copy(mirrorOf = mirror))
                        }
                    },
                )
            }
            if (!chosen.primary && chosen.mirrorOf == null) {
                val main = state.main
                val sides = listOf("left", "above", "below", "right")
                val now = main?.let { sideOf(chosen, it) } ?: "right"
                SettingBlock(
                    name = "Place",
                    help = "Which side of the main screen it sits.",
                    control = {
                        PresetSegment(listOf("Left", "Above", "Below", "Right"), sides.indexOf(now), controlWidth) { i ->
                            if (main == null || sides[i] == now) return@PresetSegment
                            val (x, y) = when (sides[i]) {
                                "left" -> main.x - chosen.width - 1 to main.y
                                "right" -> main.x + main.width + 1 to main.y
                                "above" -> main.x to main.y - chosen.height - 1
                                else -> main.x to main.y + main.height + 1
                            }
                            tryChange(display, state, "${chosen.name} to the ${sides[i]}".replace("to the above", "above").replace("to the below", "below"),
                                chosen.copy(x = x, y = y))
                        }
                    },
                )
            }
        }

        if (chosen.mirrorOf != null) {
            Note("It shows the main screen's picture, at the main screen's resolution.")
            return@Column
        }

        val resolutions = chosen.modes.groupBy { it.width to it.height }.toList()
            .sortedWith(compareByDescending<Pair<Pair<Int, Int>, List<com.dash.android.display.ScreenMode>>> { it.first.first * it.first.second })
        SettingBlock(
            name = "Resolution",
            fullWidthControl = true,
            control = {
                ChoiceList(resolutions.map { (size, modes) ->
                    val selected = chosen.mode?.let { it.width to it.height } == size
                    ChoiceRow("${size.first} × ${size.second}", if (modes.any { it.preferred }) "The screen's own" else null, selected) {
                        val best = modes.firstOrNull { it.preferred } ?: modes.minBy { abs(it.refresh - (chosen.mode?.refresh ?: 60.0)) }
                        tryChange(display, state, "${size.first} × ${size.second}", chosen.copy(mode = best))
                    }
                })
            },
        )
        val rates = chosen.modes.filter { m -> chosen.mode?.let { it.width == m.width && it.height == m.height } == true }
            .distinctBy { (it.refresh * 100).roundToInt() }.sortedBy { it.refresh }
        if (rates.size > 1) {
            SettingBlock(
                name = "Refresh rate",
                help = "Times a second the picture is drawn.",
                fullWidthControl = rates.size > 3,
                control = {
                    PresetSegment(rates.map { hz(it.refresh) }, rates.indexOfFirst { it.id == chosen.mode?.id }.coerceAtLeast(0),
                        if (rates.size > 3) Modifier.fillMaxWidth() else controlWidth) { i ->
                        if (rates[i].id == chosen.mode?.id) return@PresetSegment
                        tryChange(display, state, hz(rates[i].refresh), chosen.copy(mode = rates[i]))
                    }
                },
            )
        }
        chosen.vrr?.let { on ->
            SettingBlock(
                name = "Adaptive sync",
                help = "Lets the screen follow what is drawn (VRR) — smoother video, less power.",
                control = {
                    PresetSegment(listOf("Off", "On"), if (on) 1 else 0, controlWidth) { i ->
                        if ((i == 1) == on) return@PresetSegment
                        tryChange(display, state, "adaptive sync ${if (i == 1) "on" else "off"}", chosen.copy(vrr = i == 1))
                    }
                },
            )
        }
        chosen.overscan?.let { o -> OverscanBlock(o, controlWidth) { v -> tryChange(display, state, "the picture's edges", chosen.copy(overscan = v)) } }

        val scales = chosen.scales.ifEmpty { (4..12).map { it * 0.25 } }
        SettingBlock(
            name = "Scale",
            help = "How big everything is drawn on this screen — the machine's scale. DASH's own size is " +
                "Appearance › Size & Scale, and DASH takes up a new scale the next time it starts." +
                if (state.features.oneScale) " Every screen shares one scale here." else "",
            control = {
                val i = scales.indexOfFirst { abs(it - chosen.scale) < 0.01 }.let { if (it < 0) scales.indexOfFirst { s -> s > chosen.scale } else it }
                Stepper(
                    value = "${(chosen.scale * 100).roundToInt()}%",
                    modifier = controlWidth,
                    onMinus = { scales.getOrNull(i - 1)?.let { tryChange(display, state, "${(it * 100).roundToInt()}% scale", chosen.copy(scale = it)) } },
                    onPlus = { scales.getOrNull(i + 1)?.let { tryChange(display, state, "${(it * 100).roundToInt()}% scale", chosen.copy(scale = it)) } },
                )
            },
        )
        InfoRows(listOf("Through" to (state.program ?: "—"), "Connector" to chosen.id))
    }
}

/** A screen plugged in for the first time, and the question of what it should do. */
@Composable
private fun NewScreen(s: Screen, state: DisplayState, display: DisplaySystem) {
    val theme = LocalDashTheme.current
    val shape = RoundedCornerShape(11.dp)
    val main = state.main
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${s.name} is new. What should it do?", color = theme.textColourSecondary, fontSize = BODY, fontFamily = theme.font)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val beside = main?.let { s.copy(enabled = true, mirrorOf = null, primary = false, x = it.x + it.width + 1, y = it.y) }
            DashButton("Extend", onClick = { beside?.let { tryChange(display, state, "${s.name} beside the main screen", it) } }, modifier = Modifier.weight(1f))
            if (state.features.mirror && main != null) {
                DashButton("Mirror", onClick = { tryChange(display, state, "${s.name} showing the main screen", s.copy(enabled = true, primary = false, mirrorOf = main.id)) },
                    modifier = Modifier.weight(1f))
            }
            DashButton("Off", onClick = { display.arrange(listOf(s.copy(enabled = false, primary = false, mirrorOf = null)), keep = true) }, modifier = Modifier.weight(1f))
        }
        Text("DASH remembers the answer and sets this screen up the same way whenever it is plugged in.",
            color = theme.textColourSecondary.copy(alpha = 0.68f), fontSize = BODY, fontFamily = theme.font)
    }
}

/** How the screens sit, drawn to scale, the chosen one filled. Tap one to choose it. */
@Composable
private fun ScreenPicture(state: DisplayState, chosenId: String, onChoose: (String) -> Unit) {
    val theme = LocalDashTheme.current
    val shown = state.screens.filter { it.enabled && it.mirrorOf == null && it.width > 0 }
    val off = state.screens.filter { !it.enabled }
    if (shown.isEmpty()) return
    val minX = shown.minOf { it.x }
    val minY = shown.minOf { it.y }
    val w = shown.maxOf { it.x + it.width } - minX
    val h = shown.maxOf { it.y + it.height } - minY
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = minOf(maxWidth.value / w, PICTURE_HEIGHT / h)
        Box(Modifier.width((w * scale).dp).height((h * scale).dp).align(Alignment.Center)) {
            shown.forEach { s ->
                val selected = s.id == chosenId || state.screens.any { it.mirrorOf == s.id && it.id == chosenId }
                val mirrors = state.screens.filter { it.mirrorOf == s.id }
                Box(
                    Modifier
                        .offset(((s.x - minX) * scale).dp, ((s.y - minY) * scale).dp)
                        .size((s.width * scale).dp - 3.dp, (s.height * scale).dp - 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.textColourSecondary.copy(alpha = if (selected) 0.22f else 0.06f))
                        .border(if (selected) 2.dp else 1.dp, theme.textColourSecondary.copy(alpha = if (selected) 0.8f else 0.3f), RoundedCornerShape(6.dp))
                        .clickable { onChoose(s.id) }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        listOf(s.name + if (s.primary) " (main)" else "").plus(mirrors.map { "+ ${it.name}" }).joinToString("\n"),
                        color = theme.textColourSecondary, fontSize = TINY, textAlign = TextAlign.Center, fontFamily = theme.font,
                    )
                }
            }
        }
    }
    if (off.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            off.forEach { s -> DashButton("${s.name} (off)", onClick = { onChoose(s.id) }) }
        }
    }
}

@Composable
private fun OverscanBlock(o: Overscan, controlWidth: Modifier, onSet: (Overscan) -> Unit) {
    SettingBlock(
        name = "Fit to the edges",
        help = "Shrinks the picture for a screen that cuts its edges off (overscan) — common on HDMI car screens.",
        control = {
            if (!o.adjustable) {
                PresetSegment(listOf("Off", "On"), if (o.percent > 0) 1 else 0, controlWidth) { i ->
                    if ((i == 1) != (o.percent > 0)) onSet(o.copy(percent = if (i == 1) 1 else 0))
                }
            } else {
                Stepper(
                    value = "${o.percent}%",
                    modifier = controlWidth,
                    onMinus = { if (o.percent > 0) onSet(o.copy(percent = o.percent - 1)) },
                    onPlus = { if (o.percent < 100) onSet(o.copy(percent = o.percent + 1)) },
                )
            }
        },
    )
}

private fun sideOf(s: Screen, main: Screen): String {
    val dx = (s.x + s.width / 2.0) - (main.x + main.width / 2.0)
    val dy = (s.y + s.height / 2.0) - (main.y + main.height / 2.0)
    return if (abs(dx) * main.height.coerceAtLeast(1) >= abs(dy) * main.width.coerceAtLeast(1)) {
        if (dx >= 0) "right" else "left"
    } else if (dy >= 0) "below" else "above"
}

private fun hz(refresh: Double): String =
    if (abs(refresh - refresh.roundToInt()) < 0.05) "${refresh.roundToInt()} Hz" else String.format(Locale.UK, "%.2f Hz", refresh)

private const val PICTURE_HEIGHT = 150f
