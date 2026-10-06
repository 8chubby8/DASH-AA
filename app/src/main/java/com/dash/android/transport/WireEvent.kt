package com.dash.android.transport

/** Direction of a line on the wire, from DASH's point of view. */
enum class WireDirection {
    OUT,  // DASH → module
    IN    // module → DASH
}

/**
 * One line observed on the wire, with the metadata the Serial Monitor needs to render it.
 *
 * This is the read-only "tap" arduino/arduino.md calls for: every line that flows in or out,
 * tagged and timestamped, so a monitor (and, in a later version, an SDK logger element/overlay)
 * can observe the wire — DASH's own domain — without ever reaching inside a module panel.
 */
data class WireEvent(
    val timestamp: Long,
    val direction: WireDirection,
    val transportTag: String,
    val line: String,
    // Which device on [transportTag] this line came from / went to, when known (roadmap 1.4.16).
    // Inbound: the origin device (from InboundFrame). Outbound: the target of a per-device send;
    // null for a broadcast that went to every device on the transport.
    val deviceKey: String? = null
)
