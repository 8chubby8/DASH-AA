package com.dash.android.weather

import java.util.Calendar
import kotlin.math.ceil

/**
 * The weather model for the settings-landing scene (roadmap 1.5.4). This is the layered weather hero
 * brought forward from version 2 — *minus* the vehicle silhouette and live-car interaction, which
 * stay in v2. It maps the outside world onto a handful of moods, and the scene compositor renders
 * those moods as four layers, back to front:
 *
 *   1. **Skybox** — nine *time-of-day* states ([TimeOfDay]). Carries time only: the sky colour and the
 *      sun's height. Condition is never baked in here.
 *   2. **Clouds** — seven levels ([cloudLevel]) driven by real cloud cover; this layer carries the
 *      condition's weight over the sky.
 *   3. **Background** — three painted states ([BackgroundState]); everything between is a grade.
 *   4. **Foreground** — procedural rain / snow / fog. Never art.
 *
 * **Offline is the floor.** Everything derives from the device clock alone if no weather is available
 * — [WeatherSnapshot.clockOnly] always renders a correct time-of-day sky with no network, no location
 * and no permission. The live layer only ever *upgrades* that baseline.
 */

/**
 * The seven sky families DASH recognises. Every WMO `weather_code` collapses onto one of these. It no
 * longer picks the skybox (time does that now) — it drives cloud weight, the background state, the
 * foreground particles and the text label.
 */
enum class SkyCondition { CLEAR, PARTLY, OVERCAST, FOG, RAIN, SNOW, STORM }

/**
 * The nine time-of-day states the skybox is painted in. The skybox carries *time only* — the sky's
 * colour and the sun's height. The sun rides the right third and moves vertically: low near the
 * horizon at dawn/dusk, high at midday. Dawn and dusk are told apart by the painting's colour, not
 * the sun's position.
 */
enum class TimeOfDay { DAWN, SUNRISE, MORNING, MIDDAY, AFTERNOON, EVENING, SUNSET, DUSK, NIGHT }

/**
 * The three painted background states. Each exists only because it holds content a grade can't add:
 * DAY (nothing extra), NIGHT (lit windows and streetlamps), SNOW (lying snow). Everything in between
 * — dusk, an overcast noon — is one of these graded.
 */
enum class BackgroundState { DAY, NIGHT, SNOW }

/** What the foreground particle layer draws. Derived from the condition; never from art. */
enum class Precip { NONE, RAIN, SNOW }

/**
 * Map a WMO weather interpretation code (Open-Meteo's `weather_code`) onto a sky family. Ranges follow
 * WMO 4677: 0 clear, 1–2 mainly clear/partly, 3 overcast, 45/48 fog, 51–67 drizzle+rain (incl.
 * freezing), 71–77 snow, 80–82 rain showers, 85–86 snow showers, 95–99 thunderstorm. Anything
 * unrecognised falls back to OVERCAST — a safe, neutral sky.
 */
fun wmoToCondition(code: Int): SkyCondition = when (code) {
    0 -> SkyCondition.CLEAR
    1, 2 -> SkyCondition.PARTLY
    3 -> SkyCondition.OVERCAST
    45, 48 -> SkyCondition.FOG
    in 51..67 -> SkyCondition.RAIN
    in 71..77 -> SkyCondition.SNOW
    in 80..82 -> SkyCondition.RAIN
    85, 86 -> SkyCondition.SNOW
    in 95..99 -> SkyCondition.STORM
    else -> SkyCondition.OVERCAST
}

/** The precipitation the foreground layer draws for a given sky. */
fun SkyCondition.precip(): Precip = when (this) {
    SkyCondition.RAIN, SkyCondition.STORM -> Precip.RAIN
    SkyCondition.SNOW -> Precip.SNOW
    else -> Precip.NONE
}

/** A short human label for the text readout. */
fun SkyCondition.label(): String = when (this) {
    SkyCondition.CLEAR -> "Clear"
    SkyCondition.PARTLY -> "Partly cloudy"
    SkyCondition.OVERCAST -> "Overcast"
    SkyCondition.FOG -> "Fog"
    SkyCondition.RAIN -> "Rain"
    SkyCondition.SNOW -> "Snow"
    SkyCondition.STORM -> "Storm"
}

/**
 * Coarse time-of-day from a 24-hour clock hour, refined by Open-Meteo's `is_day` when known. Nine
 * bands across the day; `is_day` pulls an obviously-daytime band to night (or a night band to
 * morning) so a high-latitude summer night or polar winter reads right rather than trusting the hour.
 */
fun timeOfDayFor(hour: Int, isDay: Boolean? = null): TimeOfDay {
    val band = when (hour) {
        5 -> TimeOfDay.DAWN
        6, 7 -> TimeOfDay.SUNRISE
        8, 9, 10 -> TimeOfDay.MORNING
        11, 12, 13 -> TimeOfDay.MIDDAY
        14, 15, 16 -> TimeOfDay.AFTERNOON
        17, 18 -> TimeOfDay.EVENING
        19, 20 -> TimeOfDay.SUNSET
        21 -> TimeOfDay.DUSK
        else -> TimeOfDay.NIGHT // 22, 23, 0–4
    }
    return when {
        isDay == false && band in DAYLIGHT_BANDS -> TimeOfDay.NIGHT
        isDay == true && band == TimeOfDay.NIGHT -> TimeOfDay.MORNING
        else -> band
    }
}

private val DAYLIGHT_BANDS = setOf(TimeOfDay.MORNING, TimeOfDay.MIDDAY, TimeOfDay.AFTERNOON)

/** The device's current 24-hour clock hour, from local time. */
fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

/**
 * Which of the seven cloud overlays a 0..100 cover maps to; 0 means no cloud layer at all (clear).
 * Level 1 is the lightest scatter, level 7 full overcast.
 */
fun cloudLevel(coverPercent: Int): Int =
    if (coverPercent <= 2) 0
    else ceil(coverPercent.coerceIn(0, 100) / 100.0 * 7).toInt().coerceIn(1, 7)

/**
 * One immutable reading the scene renders. [live] distinguishes a real weather reading from the
 * offline clock-only baseline — the readout shows "—" for an unknown temperature rather than
 * inventing one.
 */
data class WeatherSnapshot(
    val condition: SkyCondition,
    val timeOfDay: TimeOfDay,
    val temperatureC: Double?,
    val windKph: Double,
    val windFromDegrees: Double,
    val cloudCoverPercent: Int,
    val precipitation: Precip,
    val locationName: String?,
    val live: Boolean,
) {
    /** Which painted background this reading uses: snow wins outright, else night through the dark hours. */
    fun backgroundState(): BackgroundState = when {
        condition == SkyCondition.SNOW -> BackgroundState.SNOW
        timeOfDay == TimeOfDay.NIGHT || timeOfDay == TimeOfDay.DUSK -> BackgroundState.NIGHT
        else -> BackgroundState.DAY
    }

    companion object {
        /**
         * The always-available offline render: time-of-day from the device clock, a neutral clear
         * sky, no temperature. This is what shows with no network, no location and no permission —
         * and what the live layer replaces the moment any weather source succeeds.
         */
        fun clockOnly(): WeatherSnapshot {
            val tod = timeOfDayFor(currentHour())
            return WeatherSnapshot(
                condition = SkyCondition.CLEAR,
                timeOfDay = tod,
                temperatureC = null,
                windKph = 6.0,
                windFromDegrees = 270.0,
                cloudCoverPercent = 0,
                precipitation = Precip.NONE,
                locationName = null,
                live = false,
            )
        }
    }
}
