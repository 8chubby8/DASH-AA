package com.dash.android.system.linux

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * **Is a keyboard plugged in?** (DASH-AA 1.1.10). Roger: "If a keyboard is plugged in then it should use
 * that. If there's no keyboard then it can default to an on-screen keyboard."
 *
 * Read from `/proc/bus/input/devices`, which every user may read — no permission, no rule. A keyboard is
 * a device with keys for every letter: that leaves out the power and sleep buttons, a laptop's hot keys,
 * the volume keys on a headset or a screen's bezel, all of which the kernel also calls keyboards. A
 * laptop's own keyboard counts — it is a keyboard. A Surface's Type Cover comes and goes as it is clipped
 * on, so the answer is watched, not read once.
 *
 * Capability detection with the user's word above it: some USB receivers for a mouse alone say they have
 * a keyboard, so the Terminal can always show its on-screen keyboard by hand.
 *
 * **For native:** Android says this itself (`Configuration.keyboard == KEYBOARD_QWERTY`, and
 * `hardKeyboardHidden`); native reads that instead of this file.
 */
object Keyboards {
    data class Keyboard(val name: String)

    private const val DEVICES = "/proc/bus/input/devices"
    private const val EV_KEY = 1

    // The kernel's numbers for the letter keys, Q to P, A to L, Z to M (linux/input-event-codes.h).
    private val LETTERS = (16..25) + (30..38) + (44..50)

    /** Every keyboard plugged in now; empty when there is none, or the file cannot be read. */
    fun plugged(text: String? = runCatching { File(DEVICES).readText() }.getOrNull()): List<Keyboard> {
        if (text == null) return emptyList()
        return text.split("\n\n").mapNotNull { block ->
            val name = Regex("""N: Name="(.*)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val ev = bits(Regex("""B: EV=([0-9a-f ]+)""").find(block)?.groupValues?.get(1))
            val keys = bits(Regex("""B: KEY=([0-9a-f ]+)""").find(block)?.groupValues?.get(1))
            if (EV_KEY in ev && keys.containsAll(LETTERS)) Keyboard(name) else null
        }
    }

    /** [plugged], watched: a new list whenever a keyboard comes or goes (checked every two seconds). */
    fun watch(): Flow<List<Keyboard>> = flow {
        while (true) {
            emit(plugged())
            delay(2000)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** The kernel prints a bitmap as hex words, most significant first. */
    private fun bits(words: String?): Set<Int> {
        if (words.isNullOrBlank()) return emptySet()
        val set = mutableSetOf<Int>()
        words.trim().split(' ').reversed().forEachIndexed { w, hex ->
            val v = hex.toULongOrNull(16) ?: return@forEachIndexed
            for (b in 0 until 64) if ((v shr b) and 1uL == 1uL) set += w * 64 + b
        }
        return set
    }
}
