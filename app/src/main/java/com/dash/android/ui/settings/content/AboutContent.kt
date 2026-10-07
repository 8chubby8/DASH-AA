package com.dash.android.ui.settings.content

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.BuildConfig
import com.dash.android.R
import com.dash.android.ui.theme.LocalDashTheme
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.MAINBODY_LINE

/**
 * System › About DASH (roadmap 1.5.14).
 *
 * Three sections, in the order a stranger needs them: **what this is** (the name and the version),
 * **who made it**, and **where to find it**. The fourth, **what it found on this device**, moved to its
 * own tab, System › This Machine, in DASH-AA 1.1.1 — it will grow as each new tab checks for what it
 * needs, and About is the wrong place for a list that long.
 *
 * The licence lives on its own tab next door rather than here. About answers *who and where*; the
 * licence is a legal text with real bulk — the GPL in full, plus every dependency — and folding it
 * in would have buried the human part of this page under it.
 *
 * Nothing on this screen is a control. It is the one tab in DASH that is purely read.
 */

private const val URL_SOURCE = "https://github.com/8chubby8/DASH"
private const val URL_ISSUES = "https://github.com/8chubby8/DASH/issues"

@Composable
fun AboutContent() {
    val theme = LocalDashTheme.current
    val context = LocalContext.current

    // An ordinary tab: the settings shell owns the scroll and the content padding, exactly as it
    // does for every other read-and-set surface. Nothing here needs the box height.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {

        // ── Identity ─────────────────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The tab is already named "About DASH" by the navigation, and the wordmark below says
            // DASH in letters an inch high — so the heading spends itself on the one thing neither
            // of them says: what the name stands for.
            SettingsContentHeader("Dynamic Automotive System Hub")
            Text(
                "DASH-AA",
                color = theme.textColourSecondary,
                fontSize = 40.sp,
                letterSpacing = 6.sp,
                fontFamily = theme.font,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = theme.textColourSecondary,
                fontSize = MAINBODY,
                fontFamily = theme.font,
            )
            // DASH-AA: the fork says which DASH it mirrors, because that is what decides which modules
            // and panels it understands — the protocol and the panel spec are upstream's, unchanged.
            Text(
                "The Linux edition, with Android Auto in the viewport · mirrors DASH ${BuildConfig.UPSTREAM_VERSION}",
                color = theme.textColourSecondary.copy(alpha = 0.62f),
                fontSize = BODY,
                fontFamily = theme.font,
            )
            Text(
                "Built ${BuildConfig.BUILD_DATE}",
                color = theme.textColourSecondary.copy(alpha = 0.62f),
                fontSize = BODY,
                fontFamily = theme.font,
            )
        }

        // ── The author ───────────────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsContentHeader("Built by 8chubby8")
            Paragraph(
                "DASH started because I wanted a specific system in my Jaguar X-Type. I went " +
                    "looking for something that did what I wanted, and it didn't exist. So I " +
                    "decided to build it myself."
            )
            Paragraph(
                "Then it grew — because the thing I wanted wasn't really a particular dashboard. " +
                    "It was the ability to have exactly what I wanted, instead of what somebody " +
                    "else decided I should have. That's what DASH is for."
            )
            Paragraph(
                "It's free, it's open, and every tool in it is available to you without " +
                    "restriction. Your car, your screen, your modules, your layout. DASH provides " +
                    "the foundation and gets out of the way."
            )
        }

        // ── Links ────────────────────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SettingsContentHeader("Links")
            LinkRow(
                label = "Source code",
                url = URL_SOURCE,
                qr = ImageBitmap.imageResource(R.drawable.qr_source),
                onOpen = openerFor(context, URL_SOURCE),
            )
            LinkRow(
                label = "Report a problem",
                url = URL_ISSUES,
                qr = ImageBitmap.imageResource(R.drawable.qr_issues),
                onOpen = openerFor(context, URL_ISSUES),
            )
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    val theme = LocalDashTheme.current
    Text(
        text,
        color = theme.textColourSecondary.copy(alpha = 0.8f),
        fontSize = MAINBODY,
        lineHeight = MAINBODY_LINE,
        fontFamily = theme.font,
    )
}

/**
 * An opener for [url], or null when this device has nothing that can open it.
 *
 * A dedicated head unit may well have no browser at all, and an OPEN button that does nothing when
 * pressed is worse than no button — it reads as DASH being broken. So the caller gets null and draws
 * no button; the QR code beside it means the link is still reachable either way.
 */
private fun openerFor(@Suppress("UNUSED_PARAMETER") context: Context, url: String): (() -> Unit)? {
    // DASH-AA: the desktop's "is there a browser?" is whether xdg-open exists — the same capability
    // question upstream asks Android, answered the Linux way. No browser, no button; the QR remains.
    val xdgOpen = listOf("/usr/bin/xdg-open", "/usr/local/bin/xdg-open").firstOrNull { java.io.File(it).canExecute() }
        ?: return null
    return { runCatching { ProcessBuilder(xdgOpen, url).start() } }
}
