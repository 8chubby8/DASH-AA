package com.dash.android.location

import com.dash.android.aa.AaBridge
import com.dash.android.aa.AaSettings
import com.dash.android.aa.protocol.Aa
import com.dash.android.aa.protocol.ProtoMessage
import com.dash.android.core.SystemState
import com.dash.android.weather.ManualLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up places only: nobody's real position goes in a public repository. */
class LocationTest {

    /** A sentence with its checksum worked out, the way a receiver sends it. */
    private fun nmea(body: String): String {
        var x = 0
        body.forEach { x = x xor it.code }
        return "$" + body + "*" + String.format(Locale.ROOT, "%02X", x)
    }

    // 52°30.000'N 1°45.000'W — open countryside, invented for the tests.
    private val moving = listOf(
        nmea("GPRMC,120000.00,A,5230.00000,N,00145.00000,W,30.0,90.0,211026,,,A"),
        nmea("GPGGA,120000.00,5230.00000,N,00145.00000,W,1,08,1.20,100.5,M,48.0,M,,"),
        nmea("GPGSA,A,3,01,02,03,04,05,06,07,08,,,,,2.00,1.20,1.60"),
        nmea("GPGSV,3,1,11,01,40,083,40,02,17,308,41,03,07,344,39,04,22,228,38"),
        nmea("GLGSV,1,1,03,65,40,083,40,66,17,308,41,67,07,344,39"),
    )

    @Test fun `a good sentence is told from a module's line`() {
        assertTrue(Nmea.isSentence(moving[0]))
        assertFalse(Nmea.isSentence(moving[0].dropLast(1) + "0"))      // wrong checksum
        assertFalse(Nmea.isSentence("HELLO|A4CF12|SYSTEM|1.0"))
        assertFalse(Nmea.isSentence("\$GPRMC,no,checksum"))
    }

    @Test fun `a moving 3D fix, read whole`() {
        val p = NmeaParser()
        moving.forEach { p.feed(it) }
        val r = p.reading
        assertEquals(GpsFix.FIX_3D, r.fix)
        assertEquals(52.5, r.latitude!!, 1e-9)
        assertEquals(-1.75, r.longitude!!, 1e-9)
        assertEquals(55.56, r.speedKmh!!, 0.01)                      // 30 knots
        assertEquals(90.0, r.headingDegrees!!, 1e-9)
        assertEquals(100.5, r.altitudeMetres!!, 1e-9)
        assertEquals(8, r.satellitesUsed)
        assertEquals(14, r.satellitesInView)                          // GPS and GLONASS together
        assertEquals(3.0, r.accuracyMetres!!, 1e-9)                   // HDOP 1.2 × 2.5 m
        assertNotNull(r.utcMillis)
    }

    @Test fun `parked, the direction is not known`() {
        val p = NmeaParser()
        p.feed(nmea("GPRMC,120001.00,A,5230.00000,N,00145.00000,W,0.2,,211026,,,A"))
        assertNull(p.reading.headingDegrees)
        assertEquals(0.37, p.reading.speedKmh!!, 0.01)
    }

    @Test fun `losing the fix forgets the position rather than keeping an old one`() {
        val p = NmeaParser()
        moving.forEach { p.feed(it) }
        p.feed(nmea("GPRMC,120002.00,V,,,,,,,211026,,,N"))
        p.feed(nmea("GPGGA,120002.00,,,,,0,03,,,,,,,"))
        p.feed(nmea("GPGSA,A,1,,,,,,,,,,,,,,,"))
        assertEquals(GpsFix.NONE, p.reading.fix)
        assertNull(p.reading.latitude)
        assertNull(p.reading.altitudeMetres)
        assertEquals(14, p.reading.satellitesInView)                  // still searching, still counted
    }

    private class Rig(mode: LocationMode, gnss: GnssState = GnssState(), manual: ManualLocation? = null, net: NetworkPlace? = null) {
        val state = SystemState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gnss = MutableStateFlow(gnss)
        val settings = MutableStateFlow(LocationSettings(mode))
        val module = MutableStateFlow(false)
        val rules = LocationRules(state, this.gnss, settings, flowOf(manual), module, scope,
            network = { net }, nameFor = { _, _ -> "Testbury" }).also { it.start() }
        fun settle() = Thread.sleep(300)
        fun v(name: String) = state.current(name)
    }

    private fun fixed(): GnssState {
        val p = NmeaParser()
        moving.forEach { p.feed(it) }
        return GnssState("Test GPS", p.reading)
    }

    @Test fun `automatic takes the GPS, raised as the signals`() {
        val r = Rig(LocationMode.AUTOMATIC, fixed(), net = NetworkPlace(51.0, 0.0, "Elsewhere"))
        r.settle()
        assertEquals("gps", r.v("location_source"))
        assertEquals("3d", r.v("location_fix"))
        assertEquals("52.500000,-1.750000", r.v("location_position"))
        assertEquals("55.6", r.v("location_speed"))
        assertEquals("90", r.v("location_heading"))
        assertEquals("101", r.v("location_altitude"))
        assertEquals("3", r.v("location_accuracy"))
        assertEquals("8", r.v("location_satellites"))
        assertEquals("Testbury", DashLocation.current.value.place)
        r.scope.cancel()
    }

    @Test fun `automatic with no fix falls back to the internet, the rest unknown`() {
        val r = Rig(LocationMode.AUTOMATIC, GnssState("Test GPS"), net = NetworkPlace(51.0, 0.5, "Elsewhere"))
        r.settle()
        assertEquals("network", r.v("location_source"))
        assertEquals("none", r.v("location_fix"))
        assertEquals("51.000000,0.500000", r.v("location_position"))
        listOf("location_speed", "location_heading", "location_altitude", "location_accuracy").forEach { assertEquals("unknown", r.v(it), it) }
        assertEquals("Test GPS", DashLocation.current.value.receiver)
        r.scope.cancel()
    }

    @Test fun `manual and off`() {
        val r = Rig(LocationMode.MANUAL, fixed(), manual = ManualLocation("Pinville", 50.0, -3.0))
        r.settle()
        assertEquals("manual", r.v("location_source"))
        assertEquals("50.000000,-3.000000", r.v("location_position"))
        r.settings.value = LocationSettings(LocationMode.OFF)
        r.settle()
        assertEquals("none", r.v("location_source"))
        assertEquals("unknown", r.v("location_position"))
        r.scope.cancel()
    }

    @Test fun `a module reporting the location owns the signals`() {
        val r = Rig(LocationMode.AUTOMATIC)
        r.settle()
        r.module.value = true
        r.state.store("location_source", "gps")
        r.state.store("location_position", "48.000000,2.000000")
        r.state.store("location_speed", "12.0")
        r.settle()
        assertEquals("48.000000,2.000000", r.v("location_position"))   // DASH did not overwrite it
        assertEquals(LocationSource.GPS, DashLocation.current.value.source)
        assertEquals(12.0, DashLocation.current.value.speedKmh)
        r.scope.cancel()
    }

    @Test fun `places move on only after the distance`() = runBlocking {
        val fixes = flowOf(
            DashFix(source = LocationSource.GPS, latitude = 52.5, longitude = -1.75),
            DashFix(source = LocationSource.GPS, latitude = 52.51, longitude = -1.75),   // ~1 km
            DashFix(source = LocationSource.GPS, latitude = 52.6, longitude = -1.75),    // ~11 km
            DashFix(),
        )
        val got = LocationRules.places(fixes, 10.0).toList()
        assertEquals(listOf(52.5, 52.6, null), got.map { it?.latitude })
    }

    @Test fun `Android Auto is offered the GPS, and only a GPS`() {
        val state = SystemState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = AaBridge(state, scope, gpsPresent = { true })
        assertTrue(Aa.SENSOR_LOCATION in bridge.sensorsFor(AaSettings()))
        assertFalse(Aa.SENSOR_LOCATION in bridge.sensorsFor(AaSettings(carLocation = false)))
        state.store("location_source", "network")
        state.store("location_position", "52.500000,-1.750000")
        assertNull(bridge.eventFor(Aa.SENSOR_LOCATION, AaSettings()))
        state.store("location_source", "gps")
        state.store("location_speed", "36.0")
        state.store("location_heading", "unknown")
        state.store("location_accuracy", "3")
        val m = ProtoMessage.parse(bridge.eventFor(Aa.SENSOR_LOCATION, AaSettings())!!).message(1)!!
        assertEquals(525_000_000, m.int(2))
        assertEquals(-17_500_000, m.int(3))
        assertEquals(3000, m.int(4))
        assertEquals(10_000, m.int(6))                                // 36 km/h = 10 m/s
        assertNull(m.int(7))                                          // no direction: left out, not zero
        scope.cancel()
    }

    @Test fun `distance`() {
        assertEquals(111.2, LocationRules.distanceKm(52.0, 0.0, 53.0, 0.0), 0.1)
    }
}
