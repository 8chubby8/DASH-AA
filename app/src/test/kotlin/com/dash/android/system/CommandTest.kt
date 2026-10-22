package com.dash.android.system

import com.dash.android.system.linux.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The time limit holds (DASH-AA 1.2.1): bluetoothctl with no Bluetooth service froze System › This Machine. */
class CommandTest {
    @Test fun `a program that never finishes is stopped at the limit`() {
        val t = System.currentTimeMillis()
        assertNull(Command.run("sleep", "30", seconds = 1))
        assertTrue(System.currentTimeMillis() - t < 5_000, "took ${System.currentTimeMillis() - t} ms")
    }

    @Test fun `a program that waits to be typed to gets nothing and finishes`() {
        assertEquals("", Command.run("cat", seconds = 2)!!.out)
    }

    @Test fun `output, errors and the exit code`() {
        val r = Command.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"), 5)!!
        assertEquals(3, r.code)
        assertEquals("out\nerr\n", r.out)
        assertEquals("out\n", Command.run(listOf("sh", "-c", "echo out; echo err >&2"), 5, errors = false)!!.out)
    }
}
