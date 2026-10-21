package com.dash.android.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dash.android.ui.theme.LocalDashTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 1.1.10: DASH's keyboard comes up for a box only when no real keyboard is plugged in — or by hand. */
class DashKeyboardTest {
    private val target = DashKeyboard.Target("Password", mutableStateOf(""), secret = true) {}

    @AfterTest fun reset() { DashKeyboard.close(); DashKeyboard.realKeyboard.value = false }

    @Test fun noRealKeyboardBringsItUp() {
        DashKeyboard.realKeyboard.value = false
        DashKeyboard.open(target)
        assertEquals(target, DashKeyboard.target.value)
    }

    @Test fun aRealKeyboardKeepsItDownUnlessAskedByHand() {
        DashKeyboard.realKeyboard.value = true
        DashKeyboard.open(target)
        assertNull(DashKeyboard.target.value, "a box is typed into with the real keyboard")
        DashKeyboard.open(target, byHand = true)
        assertEquals(target, DashKeyboard.target.value, "Keyboard on the box brings it up anyway")
    }

    @Test fun drawABoxWithAKeyboardPlugged() {
        if (System.getProperty("screenshots") == null) return
        DashKeyboard.realKeyboard.value = true
        val scene = ImageComposeScene(900, 160, Density(1.5f)) {
            Box(Modifier.fillMaxSize().background(LocalDashTheme.current.backgroundColourSecondary).padding(20.dp)) {
                KeyboardField(mutableStateOf("hunter2"), "Password", Modifier.fillMaxWidth(), secret = true)
            }
        }
        repeat(5) { scene.render(System.nanoTime()) }
        File("build/screenshots").mkdirs()
        File("build/screenshots/keyboard-box-plugged.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
