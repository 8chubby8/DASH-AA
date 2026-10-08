package com.dash.android.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dash.android.DashApplication
import com.dash.android.connections.PairingRequest
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.HEADING
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.common.MAINBODY_LINE
import com.dash.android.ui.common.SUBHEADING
import com.dash.android.ui.keyboard.DashKeyboard
import com.dash.android.ui.keyboard.KeyboardField
import com.dash.android.ui.theme.LocalDashTheme

/**
 * **The pairing question** (DASH-AA 1.1.6), over everything — a phone can start pairing from its own
 * settings at any moment, not only while DASH's Bluetooth tab is open. It is DASH's system prompt, as
 * Android's own pairing dialog is on native, and goes when answered or when the device gives up.
 */
@Composable
fun PairingPrompt() {
    val app = LocalContext.current.applicationContext as DashApplication
    val r by app.bluetooth.request.collectAsState()
    val request = r ?: return
    val theme = LocalDashTheme.current
    val answer = { yes: Boolean, typed: String? -> app.bluetooth.answer(request, yes, typed); DashKeyboard.close() }
    // With the keyboard up the card moves to the top, so the keys never hide its box or its buttons.
    val typing by DashKeyboard.target.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = if (typing != null) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(theme.backgroundColourSecondary)
                .border(1.dp, theme.textColourSecondary.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = when (request) {
                is PairingRequest.Show -> "Pairing with ${request.name}"
                else -> "Pair with ${request.name}?"
            }
            Text(title, color = theme.textColourSecondary, fontSize = SUBHEADING, fontFamily = theme.font)
            when (request) {
                is PairingRequest.Confirm -> {
                    Line("Check that ${request.name} shows this code:")
                    Code(request.code)
                    Buttons("Pair" to { answer(true, null) }, "Don't pair" to { answer(false, null) })
                }
                is PairingRequest.Show -> {
                    Line("Type this code on ${request.name}, then press Enter there:")
                    Code(request.code)
                    Buttons("Cancel" to { answer(false, null) })
                }
                is PairingRequest.Allow -> {
                    Line("${request.name} is asking to pair with this machine.")
                    Buttons("Allow" to { answer(true, null) }, "Don't allow" to { answer(false, null) })
                }
                is PairingRequest.EnterPin, is PairingRequest.EnterPasskey -> {
                    val pin = request is PairingRequest.EnterPin
                    Line(if (pin) "Type ${request.name}'s PIN. Most modules use 1234 or 0000 unless their maker changed it."
                        else "Type the six-digit code ${request.name} shows.")
                    val typed = remember(request) { mutableStateOf("") }
                    val ok = if (pin) typed.value.isNotBlank() else typed.value.length == 6 && typed.value.all { it.isDigit() }
                    KeyboardField(typed, if (pin) "PIN" else "Code", Modifier.fillMaxWidth(), digits = true, onDone = { if (ok) answer(true, typed.value) })
                    Buttons("Pair" to { if (ok) answer(true, typed.value) }, "Cancel" to { answer(false, null) })
                }
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    val theme = LocalDashTheme.current
    Text(text, color = theme.textColourSecondary.copy(alpha = 0.8f), fontSize = MAINBODY, lineHeight = MAINBODY_LINE, fontFamily = theme.font)
}

@Composable
private fun Code(code: String) {
    val theme = LocalDashTheme.current
    // Spaced in threes, as phones show it.
    val shown = if (code.length == 6) code.take(3) + " " + code.drop(3) else code
    Text(shown, color = theme.textColourSecondary, fontSize = HEADING * 1.6f, letterSpacing = 4.sp, fontFamily = theme.font)
}

@Composable
private fun Buttons(vararg b: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        b.forEach { (label, onClick) -> DashButton(label, onClick = onClick, modifier = Modifier.weight(1f)) }
    }
}
