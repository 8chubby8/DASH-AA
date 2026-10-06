package com.dash.android.ui.systembar.elements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dash.android.R
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.systembar.DashAction
import com.dash.android.ui.systembar.DashElement
import com.dash.android.ui.systembar.ElementKind
import com.dash.android.ui.systembar.ElementScope
import com.dash.android.ui.systembar.ElementType

/**
 * The Settings Button — a mandatory, always-present element and the only route into DASH
 * settings. If it could be hidden the user could configure themselves into an unrecoverable
 * state, so it can never be removed. It toggles the settings panel: a press opens the panel, a
 * second press closes it. The open/close decision lives in MainScreen — the element just fires
 * [DashAction.OpenSettings] and MainScreen flips the panel state.
 *
 * interface.md mandates a hard minimum touch target of 48dp that holds regardless of bar scale:
 * the visible glyph may shrink with the bar, but the interactive area never drops below 48dp.
 * That floor is enforced here with [Modifier.sizeIn], silently.
 */
object SettingsButtonElement : DashElement {
    override val type = ElementType.SETTINGS_BUTTON
    override val displayName = "Settings Button"
    override val kind = ElementKind.INTERACTIVE
    override val mandatory = true

    private const val MIN_TOUCH_TARGET_DP = 48

    @Composable
    override fun Content(scope: ElementScope) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = MIN_TOUCH_TARGET_DP.dp, minHeight = MIN_TOUCH_TARGET_DP.dp)
                .clickable { scope.onAction(DashAction.OpenSettings) },
            contentAlignment = Alignment.Center
        ) {
            // Real vector gear sized to fill the element cell (a small inset keeps it off the
            // edges), tinted from the theme's primary icon token. The 48dp touch floor is the
            // sizeIn above, independent of this visible size.
            Icon(
                painter = painterResource(R.drawable.ic_settings_gear),
                contentDescription = "Settings",
                tint = LocalDashTheme.current.iconColourPrimary,
                modifier = Modifier.size(scope.heightDp).padding(2.dp)
            )
        }
    }
}
