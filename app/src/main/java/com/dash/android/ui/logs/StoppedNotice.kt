package com.dash.android.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.dash.android.logs.LogBook
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.settings.content.LinkButton
import com.dash.android.ui.theme.LocalDashTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.dp

/**
 * **"DASH stopped unexpectedly last time"** (1.1.9, Roger: yes) — said once, quietly, on the next start.
 *
 * A small card, not a question over everything: it never blocks the screen and is never drawn over Android
 * Auto (the viewport belongs to the app, interface.md principle 7) — the caller shows it only while the
 * phone is not projecting and the settings are closed. Until it is answered it is also at the top of
 * Developer › Logs. SHOW LOG opens that run there; OK puts it away for good.
 */
@Composable
fun StoppedNotice(modifier: Modifier = Modifier, onShowLog: () -> Unit) {
    val run by LogBook.previousStop.collectAsState()
    val r = run ?: return
    val theme = LocalDashTheme.current
    val ink = theme.textColourSecondary
    val at = SimpleDateFormat("d MMM 'at' HH:mm", Locale.UK).format(Date(r.ended ?: r.started))
    Column(
        modifier
            .widthIn(max = 520.dp)
            .padding(24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.backgroundColourSecondary)
            .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("DASH stopped unexpectedly last time", color = ink, fontSize = MAINBODY, fontFamily = theme.font)
        Text("On $at. What it was doing is kept in its log.", color = ink.copy(alpha = 0.7f), fontSize = BODY, fontFamily = theme.font)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LinkButton("SHOW LOG", Color(0xFFFFB74D)) { LogBook.focus.value = r.file; LogBook.noticed(r); onShowLog() }
            LinkButton("OK") { LogBook.noticed(r) }
        }
    }
}
