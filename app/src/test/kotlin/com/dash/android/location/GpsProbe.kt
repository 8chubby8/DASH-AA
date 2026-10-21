package com.dash.android.location

import com.dash.android.DashApplication
import com.dash.android.location.linux.GnssReceivers
import com.dash.android.transport.usb.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/** Opt-in (-Dgps=1): a GPS receiver plugged into this machine, found and read through DASH's own USB transport. Prints no position. */
class GpsProbe {
    @Test fun `find and read the GPS`() {
        if (System.getProperty("gps") == null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val usb = UsbSerialTransport(DashApplication(kotlin.io.path.createTempDirectory("gps").toFile()), scope)
        usb.start()
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            val g = GnssReceivers.state.value
            val r = g.reading
            println("GPS receiver=${g.receiver} fix=${r.fix} position=${if (r.latitude != null) "yes" else "no"} speed=${r.speedKmh} " +
                "heading=${r.headingDegrees} altitude=${r.altitudeMetres} accuracy=${r.accuracyMetres} sats=${r.satellitesUsed}/${r.satellitesInView} " +
                "utc=${r.utcMillis?.let { java.time.Instant.ofEpochMilli(it) }} moduleDevices=${usb.devices.value.size}")
            Thread.sleep(2000)
        }
        usb.stop()
        scope.cancel()
    }
}
