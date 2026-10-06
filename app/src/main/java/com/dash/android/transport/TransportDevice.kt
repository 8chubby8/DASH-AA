package com.dash.android.transport

/**
 * One physical device currently connected on a transport (roadmap 1.4.10). A device is the *wire*
 * level, distinct from a module (the logical, protocol level): one board is one device however many
 * modules it hosts. The Serial Monitor's "talk to one device" selector renders these; a future
 * Devices view would too.
 *
 * [key] is unique within its own transport (a USB deviceId, a TCP socket id…). [transportTag] pairs
 * with it to route a targeted send back to the right transport, and to show which pipe the device is on.
 */
data class TransportDevice(
    val key: String,
    val label: String,
    val transportTag: String
)
