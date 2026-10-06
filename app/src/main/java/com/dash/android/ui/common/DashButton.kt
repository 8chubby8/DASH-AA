package com.dash.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE

/**
 * The DASH button — a token-bordered rounded tap, drawn by DASH rather than inherited from Material.
 *
 * Established at 1.5.8 for the Module Manager's actions and promoted here at 1.5.15, when the bar's
 * edit-mode Save/Cancel became the last two controls in DASH still wearing Material's shape,
 * elevation, ripple and padding. It was private to `ModulesContent` until then; promoting it beat
 * writing a second copy — two implementations of one idiom drift apart, and the drift is always
 * noticed later than it starts.
 *
 * Give it a [fill] for an action that carries weight (a commit, a semantic colour), or leave it null
 * for the quiet outlined form used by anything neutral. [ink] overrides the text colour where the
 * fill demands it.
 *
 * **This is not a general-purpose Material replacement.** DASH deliberately still uses Material's
 * `Text`, `Icon` and `LinearProgressIndicator`, all of which take explicit colours at every call
 * site and so never read a theme DASH does not provide — and version 2 plans a MaterialTheme mapped
 * from the DASH tokens, which is the proper fix for anything that *does* want to read one.
 */
@Composable
fun DashButton(
    label: String,
    onClick: () -> Unit,
    fill: Color? = null,
    ink: Color? = null,
    modifier: Modifier = Modifier,
    /** Left-align the label instead of centring it — for a full-width row that reads as a list item
     *  rather than a button, such as the Android settings links. */
    alignStart: Boolean = false,
) {
    val theme = LocalDashTheme.current
    val ink2 = theme.textColourSecondary
    val textColour = ink ?: if (fill != null) Color.White else ink2

    // The same container as PresetSegment and Stepper (roadmap 1.5.15, Roger): an 8% wash inside a
    // hairline border on an 11dp radius, with the content inset by 3dp. A button is a one-cell
    // segment — it had been carrying its own shape, a 0.5-alpha border and a 10dp radius, which is
    // why it read as a different family sitting next to them.
    Box(
        modifier = modifier
            .clip(SHAPE)
            .background(fill ?: ink2.copy(alpha = 0.08f))
            .border(1.dp, ink2.copy(alpha = if (fill != null) 0f else 0.18f), SHAPE)
            .clickable { onClick() }
            .padding(OUTER_PAD),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        // The label wraps rather than truncating, and the box grows to hold it. The width is shared
        // across every control in the nook, so a long label has to go down instead of out — a
        // truncated action is one you cannot read, which is worse than a taller box (roadmap 1.5.15,
        // Roger).
        Text(
            label,
            color = textColour,
            fontSize = BODY,
            lineHeight = BODY_LINE,
            fontFamily = theme.font,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}

private val SHAPE = RoundedCornerShape(11.dp)
private val OUTER_PAD = 3.dp
