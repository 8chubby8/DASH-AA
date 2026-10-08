package com.dash.android.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.display.DisplayState
import com.dash.android.display.DisplaySystem
import com.dash.android.display.Screen
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.MAINBODY_LINE
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A change to the screens being tried (DASH-AA 1.1.5): made at once, but kept only when the person says
 * so, and undone by itself after [KEEP_SECONDS] — as GNOME's and KDE's own display settings do. A wrong
 * resolution, refresh rate or turn can leave a screen dark or touch pointing elsewhere, with nothing left
 * to tap; the countdown is the way back.
 *
 * Lives outside any tab, so it runs on whatever happens to the settings panel — the window reflowing
 * into its new shape, or the panel being closed — and the change is undone if nobody keeps it.
 */
object DisplayTrial {
    data class Pending(val what: String, val endsAt: Long, val keep: () -> Unit, val goBack: () -> Unit)

    val pending = MutableStateFlow<Pending?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null

    /**
     * Try a change: [apply] it now, [keep] it if kept, [goBack] otherwise. A change tried while another
     * waits undoes the first.
     */
    fun begin(what: String, apply: () -> Unit, keep: () -> Unit, goBack: () -> Unit) {
        pending.value?.let { timer?.cancel(); it.goBack() }
        pending.value = Pending(what, System.currentTimeMillis() + KEEP_SECONDS * 1000L, keep, goBack)
        apply()
        timer = scope.launch {
            delay(KEEP_SECONDS * 1000L)
            goBack()
        }
    }

    fun keep() {
        val p = pending.value ?: return
        timer?.cancel()
        pending.value = null
        p.keep()
    }

    fun goBack() {
        val p = pending.value ?: return
        timer?.cancel()
        pending.value = null
        p.goBack()
    }

    /** Drop the change being tried without undoing it — the caller is replacing it with something else. */
    fun cancel() {
        timer?.cancel()
        pending.value = null
    }

    /** How long a change waits to be kept — GNOME's own figure. */
    const val KEEP_SECONDS = 15
}

/** The "Keep this?" question, big, because it may be read on a screen that has just changed under the driver's hand. */
@Composable
fun KeepBanner() {
    val p by DisplayTrial.pending.collectAsState()
    val pending = p ?: return
    val theme = LocalDashTheme.current
    val shape = RoundedCornerShape(11.dp)
    var left by remember(pending) { mutableIntStateOf(DisplayTrial.KEEP_SECONDS) }
    LaunchedEffect(pending) {
        while (true) {
            left = ((pending.endsAt - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
            delay(250)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Keep ${pending.what}? Going back in $left s.",
            color = theme.textColourSecondary,
            fontSize = MAINBODY,
            lineHeight = MAINBODY_LINE,
            fontFamily = theme.font,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashButton("Keep", onClick = { DisplayTrial.keep() }, modifier = Modifier.weight(1f))
            DashButton("Go back", onClick = { DisplayTrial.goBack() }, modifier = Modifier.weight(1f))
        }
    }
}

/** Try [changed] on top of the screens as they are; keep or go back by the countdown. */
internal fun tryChange(display: DisplaySystem, state: DisplayState, what: String, vararg changed: Screen) {
    val before = state.screens
    val after = before.map { s -> changed.firstOrNull { it.id == s.id } ?: s }
    DisplayTrial.begin(
        what = what,
        apply = { display.arrange(after, keep = false) },
        keep = { display.arrange(after, keep = true) },
        goBack = { display.arrange(before, keep = false) },
    )
}

@Composable
internal fun rememberDisplay(): Pair<DisplaySystem, DisplayState> {
    val app = LocalContext.current.applicationContext as DashApplication
    val state by app.display.state.collectAsState()
    return app.display to state
}

@Composable
internal fun Note(text: String) {
    val theme = LocalDashTheme.current
    Text(text, color = theme.textColourSecondary.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
}

internal data class ChoiceRow(val label: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit)

/** Choices one row each, the chosen one filled — as the Audio tabs' device lists. */
@Composable
internal fun ChoiceList(rows: List<ChoiceRow>) {
    val theme = LocalDashTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { r ->
            val ink = if (r.selected) theme.backgroundColourSecondary else theme.textColourSecondary
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (r.selected) theme.textColourSecondary else Color.Transparent)
                    .clickable { if (!r.selected) r.onClick() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(r.label, color = ink, fontSize = BODY, fontFamily = theme.font)
                if (r.detail != null) Text(r.detail, color = ink.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
            }
        }
    }
}
