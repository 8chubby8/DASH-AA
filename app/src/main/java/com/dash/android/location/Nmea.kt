package com.dash.android.location

import java.util.Locale

/**
 * **A GPS receiver's own language** (DASH-AA 1.1.11) — NMEA 0183, the lines of text almost every GPS
 * receiver sends once a second: `$GPRMC,…*6E`. [NmeaParser] turns them into one [GnssReading].
 *
 * Read, in any constellation (`GP` GPS, `GL` GLONASS, `GA` Galileo, `GB`/`BD` BeiDou, `GN` combined):
 *
 * | Sentence | What DASH takes from it |
 * |---|---|
 * | RMC | position, speed, direction of travel, whether the fix is good, the UTC date and time |
 * | GGA | position, fix quality, satellites in use, HDOP, altitude |
 * | GSA | 2D or 3D, HDOP |
 * | VTG | speed and direction of travel |
 * | GSV | satellites in view |
 *
 * A receiver gives no accuracy in metres in these, so [GnssReading.accuracyMetres] is estimated from the
 * HDOP the way receiver makers state it (HDOP × a few metres). Every other sentence is ignored.
 *
 * Shared code, nothing of Linux. **For native:** take as it is — a USB or Bluetooth receiver on Android
 * speaks the same.
 */
object Nmea {
    /** A well-formed sentence with a correct checksum — what tells a GPS receiver apart from a DASH module. */
    fun isSentence(line: String): Boolean = fields(line) != null

    /** The sentence's fields, from the talker-and-type (`GPRMC`) on, or null if it is not a good sentence. */
    fun fields(line: String): List<String>? {
        val s = line.trim()
        if (s.length < 9 || s[0] != '$') return null
        val star = s.lastIndexOf('*')
        if (star < 0 || star + 3 != s.length) return null
        val sum = s.substring(star + 1).toIntOrNull(16) ?: return null
        var x = 0
        for (i in 1 until star) x = x xor s[i].code
        if (x != sum) return null
        val f = s.substring(1, star).split(',')
        if (f[0].length != 5 || !f[0].all { it.isLetter() && it.isUpperCase() }) return null
        return f
    }
}

/** One moment from a receiver: everything it said, null where it said nothing. */
data class GnssReading(
    val fix: GpsFix = GpsFix.NONE,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    /** Direction of travel from true north — null while too slow to tell. */
    val headingDegrees: Double? = null,
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    /** The receiver's UTC time and date, epoch milliseconds — for a clock with no internet, later. */
    val utcMillis: Long? = null,
)

/** Feed it every line from one receiver; [reading] is what it has said so far. Not thread-safe: one reader thread. */
class NmeaParser {
    var reading = GnssReading()
        private set

    private var mode = 0                       // GSA: 1 none, 2 2D, 3 3D
    private var quality = 0                    // GGA: 0 none
    private var rmcValid = false
    private var date: String? = null
    private val inView = mutableMapOf<String, Int>()

    /** Take one line. True when it was a sentence the reading changed for. */
    fun feed(line: String): Boolean {
        val f = Nmea.fields(line) ?: return false
        val talker = f[0].substring(0, 2)
        val before = reading
        when (f[0].substring(2)) {
            "RMC" -> rmc(f)
            "GGA" -> gga(f)
            "GSA" -> gsa(f)
            "VTG" -> vtg(f)
            "GSV" -> gsv(talker, f)
            else -> return false
        }
        reading = reading.copy(fix = fix())
        if (reading.fix == GpsFix.NONE) {
            reading = reading.copy(latitude = null, longitude = null, speedKmh = null, headingDegrees = null, altitudeMetres = null, accuracyMetres = null)
        } else if (reading.fix == GpsFix.FIX_2D) {
            reading = reading.copy(altitudeMetres = null)
        }
        return reading != before
    }

    private fun fix(): GpsFix = when {
        !rmcValid && quality == 0 -> GpsFix.NONE
        mode == 1 -> GpsFix.NONE
        mode == 2 -> GpsFix.FIX_2D
        mode == 3 -> GpsFix.FIX_3D
        // No GSA from this receiver: an altitude says 3D.
        quality > 0 -> if (reading.altitudeMetres != null) GpsFix.FIX_3D else GpsFix.FIX_2D
        else -> GpsFix.FIX_2D
    }

    private fun rmc(f: List<String>) {
        if (f.size < 10) return
        rmcValid = f[2] == "A"
        if (!rmcValid) return
        val lat = coordinate(f[3], f[4])
        val lon = coordinate(f[5], f[6])
        val kmh = f[7].toDoubleOrNull()?.times(KNOT_KMH)
        date = f[9].takeIf { it.length == 6 }
        reading = reading.copy(
            latitude = lat ?: reading.latitude,
            longitude = lon ?: reading.longitude,
            speedKmh = kmh ?: reading.speedKmh,
            headingDegrees = heading(f[8].toDoubleOrNull(), kmh),
            utcMillis = utc(f[1], date) ?: reading.utcMillis,
        )
    }

    private fun gga(f: List<String>) {
        if (f.size < 10) return
        quality = f[6].toIntOrNull() ?: 0
        if (quality == 0) return
        val hdop = f[8].toDoubleOrNull()
        reading = reading.copy(
            latitude = coordinate(f[2], f[3]) ?: reading.latitude,
            longitude = coordinate(f[4], f[5]) ?: reading.longitude,
            satellitesUsed = f[7].toIntOrNull() ?: reading.satellitesUsed,
            accuracyMetres = hdop?.let { accuracy(it) } ?: reading.accuracyMetres,
            altitudeMetres = f[9].toDoubleOrNull() ?: reading.altitudeMetres,
        )
    }

    private fun gsa(f: List<String>) {
        if (f.size < 3) return
        mode = f[2].toIntOrNull() ?: mode
        val hdop = f.getOrNull(16)?.toDoubleOrNull()
        if (hdop != null) reading = reading.copy(accuracyMetres = accuracy(hdop))
    }

    private fun vtg(f: List<String>) {
        if (f.size < 8) return
        val kmh = f[7].toDoubleOrNull() ?: return
        reading = reading.copy(speedKmh = kmh, headingDegrees = heading(f[1].toDoubleOrNull(), kmh))
    }

    private fun gsv(talker: String, f: List<String>) {
        val n = f.getOrNull(3)?.toIntOrNull() ?: return
        inView[talker] = n
        reading = reading.copy(satellitesInView = inView.values.sum())
    }

    /** `ddmm.mmmm`,`N` → decimal degrees, south and west negative. */
    private fun coordinate(value: String, hemisphere: String): Double? {
        val v = value.toDoubleOrNull() ?: return null
        val degrees = (v / 100).toInt()
        val d = degrees + (v - degrees * 100) / 60
        return when (hemisphere) {
            "N", "E" -> d
            "S", "W" -> -d
            else -> null
        }
    }

    /** A direction only while moving fast enough for it to mean something. */
    private fun heading(course: Double?, kmh: Double?): Double? =
        course?.takeIf { kmh != null && kmh >= MOVING_KMH }?.let { ((it % 360) + 360) % 360 }

    private fun accuracy(hdop: Double) = hdop * UERE_METRES

    private fun utc(time: String, date: String?): Long? {
        if (date == null || time.length < 6) return null
        return runCatching {
            java.time.LocalDateTime.of(
                2000 + date.substring(4, 6).toInt(), date.substring(2, 4).toInt(), date.substring(0, 2).toInt(),
                time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt(),
                String.format(Locale.ROOT, "%.3f", ("0" + time.substring(6).ifEmpty { ".0" }).toDouble()).substringAfter('.').toInt() * 1_000_000,
            ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        const val KNOT_KMH = 1.852
        /** Below walking pace a receiver's direction of travel is noise. */
        const val MOVING_KMH = 3.0
        /** Metres of error per unit of HDOP — the usual figure for a consumer receiver. */
        const val UERE_METRES = 2.5
    }
}
