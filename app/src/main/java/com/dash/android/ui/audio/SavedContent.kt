package com.dash.android.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dash.android.DashApplication
import com.dash.android.audio.CarSound
import com.dash.android.audio.SoundMemory
import com.dash.android.audio.SoundPreferences
import com.dash.android.audio.SoundSettings
import com.dash.android.audio.SpeakerPosition
import com.dash.android.audio.sameSound
import com.dash.android.ui.common.BODY
import com.dash.android.ui.common.BODY_LINE
import com.dash.android.ui.common.DashButton
import com.dash.android.ui.common.SETTING_SPACING
import com.dash.android.ui.common.SUBHEADING
import com.dash.android.ui.common.controlWidth
import com.dash.android.ui.settings.content.SettingBlock
import com.dash.android.ui.settings.content.SettingsContentHeader
import com.dash.android.ui.settings.content.SettingsSectionHeader
import com.dash.android.ui.theme.LocalDashTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Audio › Saved (DASH-AA 1.1.4, Roger 2026-10-08): the car sound in numbered slots, like a car radio's
 * memory buttons — **press to load, hold to save**. The slot holding what plays now is filled. Loading
 * keeps what was there first, so **Undo last load** puts it back. Shared code, as the other Audio tabs.
 */
@Composable
fun AudioSavedContent() {
    val app = LocalContext.current.applicationContext as DashApplication
    val prefs = remember { SoundPreferences(app) }
    val memories = app.soundMemories
    val scope = rememberCoroutineScope()
    val soundSettings by prefs.settings.collectAsState(initial = SoundSettings())
    val slots by memories.slots.collectAsState()
    val undo by memories.undo.collectAsState()
    val controlWidth = Modifier.width(controlWidth(LocalDensity.current.fontScale))
    val car = soundSettings.car
    var done by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_SPACING)) {
        SettingsContentHeader("Saved")
        Note("Like a radio's memory buttons: hold a slot to save the car sound there, press it to load. A slot " +
            "keeps the speakers with their levels and distances, the equaliser, balance, fade, crossover, " +
            "loudness, surround, speed volume and calls — not the volume.")
        slots.forEachIndexed { i, m ->
            Slot(
                number = i + 1,
                memory = m,
                inUse = m != null && m.car.sameSound(car),
                onPress = {
                    scope.launch {
                        var loaded = false
                        prefs.update { s -> memories.load(i, s.car)?.let { loaded = true; s.copy(car = it) } ?: s }
                        done = if (loaded) "Loaded ${i + 1}." else "${i + 1} is empty — hold it to save the car sound there."
                    }
                },
                onHold = {
                    memories.save(i, car)
                    done = "Saved to ${i + 1}."
                },
            )
        }
        done?.let { Note(it) }
        SettingsSectionHeader("Undo")
        SettingBlock(
            name = "Undo last load",
            help = undo?.let { "Back to the car sound as it was before the last load, at ${time(it.savedAt)}. Press again to undo the undo." }
                ?: "Nothing loaded yet.",
            control = {
                DashButton(
                    "Undo",
                    onClick = {
                        scope.launch {
                            prefs.update { s -> memories.undo(s.car)?.let { s.copy(car = it) } ?: s }
                            done = "Put back as it was."
                        }
                    },
                    modifier = controlWidth,
                )
            },
        )
    }
}

/** One slot: its number, when it was saved and what it holds; filled when it is what plays now. */
@Composable
private fun Slot(number: Int, memory: SoundMemory?, inUse: Boolean, onPress: () -> Unit, onHold: () -> Unit) {
    val theme = LocalDashTheme.current
    val ink = if (inUse) theme.backgroundColourSecondary else theme.textColourSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (inUse) theme.textColourSecondary else theme.textColourSecondary.copy(alpha = 0.08f))
            .border(1.dp, theme.textColourSecondary.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .pointerInput(onPress, onHold) { detectTapGestures(onTap = { onPress() }, onLongPress = { onHold() }) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = ink, fontSize = SUBHEADING, fontFamily = theme.font)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (memory == null) {
                Text("Empty", color = ink, fontSize = BODY, fontFamily = theme.font)
                Text("Hold to save", color = ink.copy(alpha = 0.68f), fontSize = BODY, fontFamily = theme.font)
            } else {
                Text(
                    (if (inUse) "In use · " else "") + "Saved ${time(memory.savedAt)}",
                    color = ink, fontSize = BODY, fontFamily = theme.font,
                )
                Text(summary(memory.car), color = ink.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
            }
        }
    }
}

/** What a slot holds, in a line: "Front · Rear · Subwoofer · equaliser · loudness 2 · time aligned". */
private fun summary(car: CarSound): String = buildList {
    if (car.speakers.isEmpty()) add("No speakers")
    SpeakerPosition.entries.filter { it in car.speakers }.forEach { add(it.label) }
    add(if (car.eq.any { it != 0 }) "equaliser" else "flat")
    if (car.loudness > 0) add("loudness ${car.loudness}")
    if (car.timeAlignment) add("time aligned")
    if (car.speedVolume > 0) add("speed ${car.speedVolume}")
}.joinToString(" · ")

private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)

private fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

@Composable
private fun Note(text: String) {
    val theme = LocalDashTheme.current
    Text(text, color = theme.textColourSecondary.copy(alpha = 0.68f), fontSize = BODY, lineHeight = BODY_LINE, fontFamily = theme.font)
}
