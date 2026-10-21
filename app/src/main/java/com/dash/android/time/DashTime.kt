package com.dash.android.time

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * **The time, for anything that shows it** (DASH-AA 1.1.8). Elements and overlays read it here, built-in and
 * community alike (Roger: "date and time would need to be readable from the elements and overlays sdk,
 * should someone want to have access to the information"). The Element SDK hands it to every element in
 * [com.dash.android.ui.systembar.ElementScope.time]; the Overlay SDK's scope will carry the same when it is
 * built. DASH's own analogue clock reads it from here too — nothing built in gets the time another way.
 *
 * One reading a second, on the second: the moment, the zone, the date and time as numbers, and the same
 * already written the way the user chose in Date & Time, so a clock that just wants to show the time shows
 * it as everything else in DASH does.
 *
 * **Why DASH keeps its own zone rather than asking Java.** A running program reads the machine's time zone
 * once, when it starts. Change the zone (or travel into another, with Follow Location on) and Java would go
 * on showing the old one until DASH restarted. So the zone comes from [TimeSystem], live, and is handed to
 * the rest of the program as the default too.
 *
 * Shared code: nothing of Linux here. **For native:** take as it is.
 */
object DashTime {
    private val _clock = MutableStateFlow(read(System.currentTimeMillis(), ZoneId.systemDefault(), TimeSettings(), synced = null))
    val clock: StateFlow<DashClock> = _clock

    @Volatile private var started = false

    /** Tick for as long as [scope] lives, from the machine's zone and the user's settings. Once per process. */
    fun start(scope: CoroutineScope, machine: StateFlow<TimeState>, settings: Flow<TimeSettings>) {
        if (started) return
        started = true
        var zone = ZoneId.systemDefault()
        var chosen = TimeSettings()
        var synced: Boolean? = null
        fun emit() { _clock.value = read(System.currentTimeMillis(), zone, chosen, synced) }
        scope.launch {
            combine(machine, settings) { m, s -> m to s }.collect { (m, s) ->
                val z = m.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                if (z != zone) {
                    zone = z
                    // Everything else in the program that asks Java for the time follows too.
                    TimeZone.setDefault(TimeZone.getTimeZone(z))
                }
                chosen = s
                synced = if (m.available) m.automatic && m.synced else null
                emit()
            }
        }
        scope.launch {
            while (true) {
                emit()
                // Just after the next whole second, so a second hand steps in time with the real clock.
                delay(1000L - System.currentTimeMillis() % 1000 + 5)
            }
        }
    }

    /** One reading of the clock — public so an SDK's tests can make one. */
    fun read(epochMillis: Long, zone: ZoneId, settings: TimeSettings, synced: Boolean?): DashClock {
        val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val offset = at.offset.totalSeconds / 60
        return DashClock(
            epochMillis = epochMillis,
            zone = zone.id,
            // Summer or winter as it is at this moment: "BST", "GMT"; zones with no short name give their offset.
            zoneShort = at.format(DateTimeFormatter.ofPattern("zzz", Locale.UK)),
            utcOffsetMinutes = offset,
            year = at.year,
            month = at.monthValue,
            day = at.dayOfMonth,
            dayOfWeek = at.dayOfWeek.value,
            hour = at.hour,
            minute = at.minute,
            second = at.second,
            hours24 = settings.hours24,
            time = formatTime(at.toLocalDateTime(), settings.hours24),
            date = formatDate(at.toLocalDateTime(), settings.dateStyle),
            synced = synced,
        )
    }

    fun formatTime(t: LocalDateTime, hours24: Boolean): String =
        t.format(DateTimeFormatter.ofPattern(if (hours24) "HH:mm" else "h:mm a", Locale.UK))

    fun formatDate(t: LocalDateTime, style: DateStyle): String =
        t.format(DateTimeFormatter.ofPattern(style.pattern, Locale.UK))

    /** "UTC", "UTC+1", "UTC−3:30". */
    fun offsetLabel(minutes: Int): String = when {
        minutes == 0 -> "UTC"
        else -> "UTC" + (if (minutes > 0) "+" else "−") + (Math.abs(minutes) / 60) +
            (Math.abs(minutes) % 60).let { if (it == 0) "" else ":%02d".format(it) }
    }
}

/**
 * One reading of the clock, as every element and overlay sees it.
 *
 * The numbers are the local date and time in [zone]; [time] and [date] are the same written the way the user
 * chose in System › Date & Time. Plain values only, so a component built for one edition reads it the same
 * on the other.
 */
data class DashClock(
    /** The moment, in milliseconds since 1970 (UTC). */
    val epochMillis: Long,
    /** The time zone, as "Europe/London". */
    val zone: String,
    /** The zone's short name now, as "BST". */
    val zoneShort: String,
    /** Minutes ahead of UTC now (behind is negative). */
    val utcOffsetMinutes: Int,
    val year: Int,
    /** 1–12. */
    val month: Int,
    /** 1–31. */
    val day: Int,
    /** 1 Monday – 7 Sunday. */
    val dayOfWeek: Int,
    /** 0–23, whatever [hours24] says. */
    val hour: Int,
    val minute: Int,
    val second: Int,
    /** The user's choice: 24-hour (true) or 12-hour time. */
    val hours24: Boolean,
    /** The time as the user chose to see it: "14:05" or "2:05 pm". */
    val time: String,
    /** The date as the user chose to see it: "Wed 21 Oct 2026", "21/10/2026", … */
    val date: String,
    /** True when the clock is set from the internet and in step; false when it is set by hand or not yet in step; null when DASH cannot tell. */
    val synced: Boolean?,
)
