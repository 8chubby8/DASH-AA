package com.dash.android.ui.systembar.elements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.dash.android.ui.theme.LocalDashTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.ui.systembar.DashElement
import com.dash.android.ui.systembar.ElementKind
import com.dash.android.ui.systembar.ElementScope
import com.dash.android.ui.systembar.ElementType

/**
 * The Alerts Area — a mandatory, always-present element. Vehicle and module alerts must always be
 * visible; this is a safety consideration and the reason the element can never be removed.
 *
 * In 1.3.1 this is a placeholder visual only. It becomes functional in 1.4.x once the transport
 * layer is live and there are real alerts to display.
 */
object AlertsAreaElement : DashElement {
    override val type = ElementType.ALERTS_AREA
    override val displayName = "Alerts Area"
    override val kind = ElementKind.INFORMATIONAL
    override val mandatory = true

    @Composable
    override fun Content(scope: ElementScope) {
        val theme = LocalDashTheme.current
        val h = scope.heightDp.value
        Box(
            modifier = Modifier
                .height(scope.heightDp)
                .clip(RoundedCornerShape(4.dp))
                .background(theme.accentColourPrimary)
                .padding(horizontal = (h * 0.28f).dp, vertical = (h * 0.11f).dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = "ALERTS",
                color = theme.textColourPrimary.copy(alpha = 0.55f),
                fontSize = (h * 0.30f).sp,
                fontFamily = theme.font,
                letterSpacing = (h * 0.06f).sp
            )
        }
    }
}
