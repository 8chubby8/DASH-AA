package com.dash.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The art-deco rule that sits under a heading — a short solid stub, then a hairline fading out to
 * nothing across the remaining width.
 *
 * Shared (roadmap 1.5.15) so the settings tree's heading and the headings inside the content box draw
 * the same rule rather than two copies of it. [colour] is the caller's ink, because the two sit on
 * opposite surfaces: the tree heading is drawn in the primary set on the light panel, the box's
 * headings in the secondary set on the dark box.
 */
@Composable
fun HeadingRule(colour: Color, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 2.dp).fillMaxWidth(),
    ) {
        Box(
            Modifier.width(22.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colour)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.weight(1f).height(2.dp).background(
                Brush.horizontalGradient(listOf(colour.copy(alpha = 0.5f), Color.Transparent))
            )
        )
    }
}
