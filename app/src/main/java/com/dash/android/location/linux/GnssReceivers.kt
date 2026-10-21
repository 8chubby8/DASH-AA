package com.dash.android.location.linux

import android.util.Log
import com.dash.android.location.GnssState
import com.dash.android.location.GpsFix
import com.dash.android.location.NmeaParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **The GPS receivers plugged into the machine** (DASH-AA 1.1.11). A receiver is not a DASH module: it never
 * says `HELLO`, it just talks NMEA. So the USB transport, on hearing a good NMEA sentence from a port before
 * any DASH message, **claims** the port for this instead of the module bus — native's "raw claim" (its
 * roadmap 1.10.x). Its lines come here and nowhere else: not to discovery, not to the Serial Monitor.
 *
 * No gpsd, no other program: DASH reads the receiver itself, so the 1.2.x system needs nothing more. More
 * than one receiver is fine; the one with the best fix is used.
 *
 * Linux only. **For native:** Android's own location service takes this place.
 */
object GnssReceivers {
    private class Receiver(val name: String) {
        val parser = NmeaParser()
    }

    private val receivers = LinkedHashMap<String, Receiver>()

    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state.asStateFlow()

    @Synchronized
    fun claim(path: String, name: String) {
        if (receivers.containsKey(path)) return
        receivers[path] = Receiver(name)
        Log.i(TAG, "GPS receiver on $path ($name)")
        publish()
    }

    @Synchronized
    fun feed(path: String, line: String) {
        val r = receivers[path] ?: return
        if (r.parser.feed(line)) publish()
    }

    @Synchronized
    fun release(path: String) {
        if (receivers.remove(path) != null) {
            Log.i(TAG, "GPS receiver on $path gone")
            publish()
        }
    }

    private fun publish() {
        val best = receivers.values.maxByOrNull { rank(it.parser.reading.fix) }
        _state.value = if (best == null) GnssState() else GnssState(best.name, best.parser.reading)
    }

    private fun rank(f: GpsFix) = when (f) { GpsFix.FIX_3D -> 2; GpsFix.FIX_2D -> 1; GpsFix.NONE -> 0 }

    private const val TAG = "DashLocation"
}
