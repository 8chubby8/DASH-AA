package com.dash.android.time

import com.dash.android.time.linux.TimeDated
import com.dash.android.weather.ManualLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeTest {
    private val summer = ZonedDateTime.of(2026, 10, 21, 14, 5, 9, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()
    private val winter = ZonedDateTime.of(2026, 12, 25, 9, 30, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()

    @Test fun `one reading, written the user's way, in the machine's zone`() {
        val c = DashTime.read(summer, ZoneId.of("Europe/London"), TimeSettings(), synced = true)
        assertEquals(listOf(2026, 10, 21, 3, 14, 5, 9), listOf(c.year, c.month, c.day, c.dayOfWeek, c.hour, c.minute, c.second))
        assertEquals("14:05", c.time)
        assertEquals("Wed 21 Oct 2026", c.date)
        assertEquals("BST", c.zoneShort)
        assertEquals(60, c.utcOffsetMinutes)

        val twelve = DashTime.read(summer, ZoneId.of("Europe/London"), TimeSettings(hours24 = false, dateStyle = DateStyle.DAY_FIRST), null)
        assertEquals("2:05 pm", twelve.time)
        assertEquals("21/10/2026", twelve.date)
        assertEquals("GMT", DashTime.read(winter, ZoneId.of("Europe/London"), TimeSettings(), null).zoneShort)

        // The same moment, somewhere else.
        val kolkata = DashTime.read(summer, ZoneId.of("Asia/Kolkata"), TimeSettings(), null)
        assertEquals("18:35", kolkata.time)
        assertEquals(330, kolkata.utcOffsetMinutes)
    }

    @Test fun `every date style`() {
        val t = DashTime.read(summer, ZoneId.of("Europe/London"), TimeSettings(), null)
        val local = java.time.LocalDateTime.of(t.year, t.month, t.day, t.hour, t.minute)
        assertEquals(
            listOf("Wednesday 21 October 2026", "Wed 21 Oct 2026", "21/10/2026", "10/21/2026", "2026-10-21"),
            DateStyle.entries.map { DashTime.formatDate(local, it) },
        )
    }

    @Test fun `offsets in a few characters`() {
        assertEquals("UTC", DashTime.offsetLabel(0))
        assertEquals("UTC+1", DashTime.offsetLabel(60))
        assertEquals("UTC+5:30", DashTime.offsetLabel(330))
        assertEquals("UTC−3:30", DashTime.offsetLabel(-210))
    }

    @Test fun `DASH may change the clock only when polkit says yes to all three, without a password`() {
        assertEquals(true, TimeDated.allowedFrom(listOf(0, 0, 0)))
        assertEquals(false, TimeDated.allowedFrom(listOf(0, 2, 0)))  // one wants a password
        assertEquals(false, TimeDated.allowedFrom(listOf(1, 1, 1)))
        assertNull(TimeDated.allowedFrom(listOf(-1, -1, -1)))         // no pkcheck: try, and say if refused
    }

    @Test fun `a refusal in plain words, and never a password box`() {
        val e = RuntimeException("org.freedesktop.DBus.Error.InteractiveAuthorizationRequired: Interactive authentication required.")
        val text = TimeDated.plain(e, "change the time zone")
        assertTrue(text.startsWith("Could not change the time zone: the machine wants a password"), text)
        assertTrue("install.sh" in text)
    }

    @Test fun `the zone follows Location only when asked, and only when it differs`() {
        val time = PretendTime()
        val settings = MutableStateFlow(TimeSettings())
        val location = MutableStateFlow<ManualLocation?>(null)
        val looked = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rules = TimeRules(time, settings, location, scope, find = { p -> looked += (p?.name ?: "connection"); if (p == null) "Europe/London" else "Europe/Paris" })
        rules.start()
        Thread.sleep(300)
        assertTrue(looked.isEmpty(), "off: nothing looked up")

        settings.value = TimeSettings(zoneFromLocation = true)
        waitFor { rules.found.value?.zone == "Europe/London" }
        Thread.sleep(200)
        assertTrue(time.done.isEmpty(), "already London: nothing set")

        location.value = ManualLocation("Paris", 48.85, 2.35)
        waitFor { time.done == listOf("zone Europe/Paris") }
        assertEquals(ZoneLookup("Europe/Paris", "Paris"), rules.found.value)

        settings.value = TimeSettings(zoneFromLocation = false)
        waitFor { rules.found.value == null }
        scope.cancel()
    }

    @Test fun `no internet leaves the zone alone, and a refused machine is never asked`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val offline = PretendTime()
        val r1 = TimeRules(offline, MutableStateFlow(TimeSettings(zoneFromLocation = true)), MutableStateFlow(null), scope, find = { null })
        r1.start()
        waitFor { r1.found.value != null }
        assertNull(r1.found.value!!.zone)

        val refused = PretendTime(PretendTime.REFUSED)
        val r2 = TimeRules(refused, MutableStateFlow(TimeSettings(zoneFromLocation = true)), MutableStateFlow(null), scope, find = { "Europe/Paris" })
        r2.start()
        waitFor { r2.found.value != null }
        Thread.sleep(200)
        assertTrue(offline.done.isEmpty() && refused.done.isEmpty())
        scope.cancel()
    }

    @Test fun `a zone the machine does not know is never set`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val time = PretendTime()
        val r = TimeRules(time, MutableStateFlow(TimeSettings(zoneFromLocation = true)), MutableStateFlow(null), scope, find = { "Mars/Olympus_Mons" })
        r.start()
        waitFor { r.found.value != null }
        Thread.sleep(200)
        assertFalse(time.done.any { it.startsWith("zone") })
        scope.cancel()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }
}
