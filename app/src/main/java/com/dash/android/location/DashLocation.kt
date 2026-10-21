package com.dash.android.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Where DASH is, for anything that wants to know** (DASH-AA 1.1.11). Elements and overlays read it here,
 * built-in and community alike (Roger: the location data is "accessable through the element or overlay
 * sdk"). The Element SDK hands it to every element as [com.dash.android.ui.systembar.ElementScope.location];
 * the Overlay SDK's scope will carry the same when it is built. DASH's own weather, Follow Location and
 * System › Location read it from here too — nothing built in gets the location another way.
 *
 * It is the same reading DASH raises as the `location_*` signals (system_commands.md, Location), written
 * by [LocationRules]. A value DASH does not know right now is null — the signals' `unknown` — never zero
 * and never the last value; each element decides what to show.
 *
 * Shared code: nothing of Linux here. **For native:** take as it is; Android's own location fills the
 * GPS side where the Linux edition reads a receiver.
 */
object DashLocation {
    private val _current = MutableStateFlow(DashFix())
    val current: StateFlow<DashFix> = _current.asStateFlow()

    /** Written by [LocationRules] only. */
    internal fun publish(fix: DashFix) { _current.value = fix }
}

/** One reading of where DASH is. Public so an SDK's tests can make one. */
data class DashFix(
    val source: LocationSource = LocationSource.NONE,
    val fix: GpsFix = GpsFix.NONE,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    /** Direction of travel from true north, 0–359 — null while parked. */
    val headingDegrees: Double? = null,
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    /** A name for the place, when one is known: the pinned place, the town the internet says, or near the GPS fix. */
    val place: String? = null,
    /** The GPS receiver DASH is reading, by name — null with none plugged in. Present even with no fix yet. */
    val receiver: String? = null,
    /** When this reading was taken, epoch milliseconds. */
    val at: Long = 0,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null
}

/** Where a reading comes from — `location_source`'s values. */
enum class LocationSource(val signal: String) {
    NONE("none"), GPS("gps"), NETWORK("network"), MANUAL("manual");

    companion object {
        fun of(value: String?): LocationSource? = entries.firstOrNull { it.signal == value }
    }
}

/** A GPS's own quality — `location_fix`'s values. */
enum class GpsFix(val signal: String) {
    NONE("none"), FIX_2D("2d"), FIX_3D("3d");

    companion object {
        fun of(value: String?): GpsFix? = entries.firstOrNull { it.signal == value }
    }
}

/** What GPS receivers the machine has, and what the best of them says. Filled by the platform. */
data class GnssState(
    /** The receiver read, by name — null with none plugged in. */
    val receiver: String? = null,
    val reading: GnssReading = GnssReading(),
)
