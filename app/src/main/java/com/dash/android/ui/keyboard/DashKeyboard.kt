package com.dash.android.ui.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.connections.ConnectionsPreferences
import com.dash.android.connections.ConnectionsSettings
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE
import com.dash.android.ui.common.MAINBODY
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * **DASH's on-screen keyboard** (DASH-AA 1.1.6). With no desktop there is no keyboard on the screen, and a
 * Wi-Fi password, a Bluetooth PIN or the DASH network's name cannot be typed without one. It is DASH's own
 * chrome, drawn over everything, and types into whichever [KeyboardField] opened it. A real keyboard, when
 * one is plugged in, keeps working beside it.
 *
 * UK QWERTY, with two pages of symbols that between them hold every printable character a password may
 * use. Shift once for one capital, twice for caps lock; hold backspace to keep deleting.
 *
 * **Only while the car is still**, by default ([CarStill], [ConnectionsSettings.keyboardOnlyWhenStill]).
 * With no module reporting the speed or the handbrake, it is always there.
 *
 * **For native:** Android has keyboards of its own, so native does not need this; it is shared code all
 * the same, for a head unit with none installed.
 */
object DashKeyboard {
    /** What is being typed into: its name, its text, whether it is a secret, and what Done does. */
    class Target(val label: String, val text: MutableState<String>, val secret: Boolean, val digits: Boolean = false, val onDone: () -> Unit)

    val target = MutableStateFlow<Target?>(null)

    fun open(t: Target) { target.value = t }
    fun close() { target.value = null }
}

/**
 * A box to type into: tapping it brings up DASH's keyboard, and a real keyboard types into it too. A
 * [secret] shows dots, with an eye to show what was typed.
 */
@Composable
fun KeyboardField(
    text: MutableState<String>,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    digits: Boolean = false,
    onDone: () -> Unit = {},
) {
    val theme = LocalDashTheme.current
    var shown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val open = { DashKeyboard.open(DashKeyboard.Target(label, text, secret, digits) { onDone(); DashKeyboard.close() }) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open(); runCatching { focus.requestFocus() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = theme.textColourSecondary.copy(alpha = 0.4f), fontSize = BODY, fontFamily = theme.font)
            }
            BasicTextField(
                value = text.value,
                onValueChange = { text.value = it },
                singleLine = true,
                textStyle = TextStyle(color = theme.textColourSecondary, fontSize = BODY, fontFamily = theme.font),
                cursorBrush = SolidColor(theme.textColourSecondary),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (digits) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                // Tapped (or reached by Tab), the box takes the real keyboard's typing and brings up DASH's.
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) open() },
            )
        }
        if (secret) {
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                if (shown) "Hide" else "Show",
                color = theme.textColourSecondary,
                fontSize = BODY,
                fontFamily = theme.font,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { shown = !shown }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** The keyboard's one setting — on Display › Touchscreen. */
@Composable
fun KeyboardSettings() {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { ConnectionsPreferences(app) }
    val settings by prefs.settings.collectAsState(initial = ConnectionsSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reported by remember {
        CarStill.of(app.controller.systemState.values, app.controller.database.modules, app.controller.reconciliation.activity)
    }.collectAsState(initial = null)
    com.dash.android.ui.settings.content.SettingBlock(
        name = "Only while the car is stopped",
        help = "DASH's keyboard, for passwords and names, works only while a module reports the car stopped " +
            "(speed zero, or the handbrake on). " +
            if (reported == null) "No module reports either now, so the keyboard is always available." else "",
        control = {
            com.dash.android.ui.settings.content.PresetSegment(
                listOf("Off", "On"),
                if (settings.keyboardOnlyWhenStill) 1 else 0,
                Modifier.widthIn(max = com.dash.android.ui.common.controlWidth(androidx.compose.ui.platform.LocalDensity.current.fontScale)),
            ) { i -> scope.launch { prefs.update { it.copy(keyboardOnlyWhenStill = i == 1) } } }
        },
    )
}

private enum class Page { LETTERS, NUMBERS, SYMBOLS }

private val LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val NUMBERS = listOf(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), listOf("@", "#", "£", "_", "&", "-", "+", "(", ")", "/"), listOf("*", "\"", "'", ":", ";", "!", "?"))
private val SYMBOLS = listOf(listOf("~", "`", "|", "\\", "^", "=", "{", "}", "[", "]"), listOf("$", "€", "%", "<", ">", "¬", "¦", "§", "°", "·"), listOf("¿", "¡", "«", "»", "±", "×", "÷"))

/** The keyboard itself: up from the bottom of the screen while something is being typed. */
@Composable
fun KeyboardOverlay(modifier: Modifier = Modifier) {
    val t by DashKeyboard.target.collectAsState()
    val app = LocalContext.current.applicationContext as DashApplication
    val settings by remember { ConnectionsPreferences(app).settings }.collectAsState(initial = ConnectionsSettings())
    val still by remember {
        CarStill.of(app.controller.systemState.values, app.controller.database.modules, app.controller.reconciliation.activity)
    }.collectAsState(initial = null)
    val locked = settings.keyboardOnlyWhenStill && still == false

    AnimatedVisibility(
        visible = t != null,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val target = t ?: return@AnimatedVisibility
        Keyboard(target, locked)
    }
}

@Composable
private fun Keyboard(target: DashKeyboard.Target, locked: Boolean) {
    val theme = LocalDashTheme.current
    // A PIN or an address opens on the numbers; the letters are a tap away.
    var page by remember(target) { mutableStateOf(if (target.digits) Page.NUMBERS else Page.LETTERS) }
    var shift by remember(target) { mutableStateOf(false) }
    var capsLock by remember(target) { mutableStateOf(false) }
    var lastShift by remember { mutableStateOf(0L) }
    var shown by remember(target) { mutableStateOf(false) }

    fun type(s: String) {
        target.text.value += s
        if (shift && !capsLock) shift = false
    }
    fun back() { target.text.value = target.text.value.dropLast(1) }

    Box(
        Modifier
            .fillMaxWidth()
            .background(theme.backgroundColourPrimary)
            // Taps on the keyboard's own floor stay on it — never reaching what is underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 980.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // What is being typed, here as well as in its box — the box may be under the keyboard.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(target.label, color = theme.textColourPrimary.copy(alpha = 0.7f), fontSize = BODY, fontFamily = theme.font)
                val v = target.text.value
                Text(
                    if (target.secret && !shown) "•".repeat(v.length) else v,
                    color = theme.textColourPrimary,
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (target.secret) SmallKey(if (shown) "Hide" else "Show") { shown = !shown }
                SmallKey("Close") { DashKeyboard.close() }
            }
            if (locked) {
                Text(
                    "The keyboard is available when the car is stopped.",
                    color = theme.textColourPrimary,
                    fontSize = MAINBODY,
                    fontFamily = theme.font,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                )
                return@Column
            }
            val upper = shift || capsLock
            when (page) {
                Page.LETTERS -> {
                    KeyRow { LETTERS[0].forEach { c -> Key(if (upper) c.uppercase() else c.toString()) { type(if (upper) c.uppercase() else c.toString()) } } }
                    KeyRow(inset = 0.5f) { LETTERS[1].forEach { c -> Key(if (upper) c.uppercase() else c.toString()) { type(if (upper) c.uppercase() else c.toString()) } } }
                    KeyRow {
                        Key(if (capsLock) "⇪" else "⇧", weight = 1.5f, lit = upper) {
                            val now = System.currentTimeMillis()
                            when {
                                capsLock -> { capsLock = false; shift = false }
                                shift && now - lastShift < 450 -> capsLock = true
                                else -> shift = !shift
                            }
                            lastShift = now
                        }
                        LETTERS[2].forEach { c -> Key(if (upper) c.uppercase() else c.toString()) { type(if (upper) c.uppercase() else c.toString()) } }
                        BackKey(weight = 1.5f, onBack = ::back)
                    }
                }
                Page.NUMBERS, Page.SYMBOLS -> {
                    val rows = if (page == Page.NUMBERS) NUMBERS else SYMBOLS
                    KeyRow { rows[0].forEach { s -> Key(s) { type(s) } } }
                    KeyRow { rows[1].forEach { s -> Key(s) { type(s) } } }
                    KeyRow {
                        Key(if (page == Page.NUMBERS) "=\\<" else "123", weight = 1.5f) { page = if (page == Page.NUMBERS) Page.SYMBOLS else Page.NUMBERS }
                        rows[2].forEach { s -> Key(s) { type(s) } }
                        BackKey(weight = 1.5f, onBack = ::back)
                    }
                }
            }
            KeyRow {
                Key(if (page == Page.LETTERS) "123" else "ABC", weight = 1.5f) { page = if (page == Page.LETTERS) Page.NUMBERS else Page.LETTERS }
                Key(",") { type(",") }
                Key("space", weight = 5f) { type(" ") }
                Key(".") { type(".") }
                Key("Done", weight = 1.5f, lit = true) { target.onDone() }
            }
        }
    }
}

@Composable
private fun KeyRow(inset: Float = 0f, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (inset > 0f) Spacer(Modifier.weight(inset))
        content()
        if (inset > 0f) Spacer(Modifier.weight(inset))
    }
}

@Composable
private fun RowScope.Key(label: String, weight: Float = 1f, lit: Boolean = false, onTap: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(
        Modifier
            .weight(weight)
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(if (lit) theme.textColourPrimary.copy(alpha = 0.85f) else theme.textColourPrimary.copy(alpha = 0.12f))
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (lit) theme.backgroundColourPrimary else theme.textColourPrimary, fontSize = MAINBODY, fontFamily = theme.font, maxLines = 1)
    }
}

/** Backspace: one character a tap; held, it keeps going. */
@Composable
private fun RowScope.BackKey(weight: Float, onBack: () -> Unit) {
    val theme = LocalDashTheme.current
    Box(
        Modifier
            .weight(weight)
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.textColourPrimary.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onBack()
                    coroutineScope {
                        val repeat = launch {
                            delay(450)
                            while (true) { onBack(); delay(60) }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌫", color = theme.textColourPrimary, fontSize = MAINBODY, fontFamily = theme.font)
    }
}

@Composable
private fun SmallKey(label: String, onTap: () -> Unit) {
    val theme = LocalDashTheme.current
    Text(
        label,
        color = theme.textColourPrimary,
        fontSize = BODY,
        lineHeight = BODY_LINE,
        fontFamily = theme.font,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(theme.textColourPrimary.copy(alpha = 0.12f))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val KEY_HEIGHT = 52.dp
