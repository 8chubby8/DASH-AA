package com.dash.android.transport

/**
 * A module that answered a `DISCOVER` broadcast with a `HELLO` identity line.
 *
 * This is a *discovered* module, not an *installed* one — the two are distinct in arduino.md §6
 * ("a module can exist and be discoverable without being installed"). The installed-module database
 * arrives in 1.4.5; until then this is the only module record DASH holds, and it is transient —
 * rebuilt from scratch each time the user presses DISCOVER.
 *
 * The seed of the `DashMessage` codec the changelog flagged for 1.4.2: [parseHello] is the first
 * concrete parse of a wire line into a typed value. It grows into a fuller codec as the install
 * handshake (1.4.4) and message routing (1.4.7+) land.
 */
data class DiscoveredModule(
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val version: String
) {
    companion object {
        /**
         * Parse a `HELLO|id|type|name|description|version` line into a [DiscoveredModule], or null
         * if the line is not a well-formed HELLO.
         *
         * The grammar (arduino.md §2) is one value per field with no embedded delimiters, so a HELLO
         * is exactly six pipe-separated fields — no more, no fewer. Anything else is malformed and
         * rejected rather than guessed at, so a corrupt line never becomes a phantom module.
         */
        fun parseHello(line: String): DiscoveredModule? {
            val parts = line.split('|')
            if (parts.size != 6) return null
            if (parts[0].trim() != "HELLO") return null
            val id = parts[1].trim()
            if (id.isEmpty()) return null
            return DiscoveredModule(
                id = id,
                type = parts[2].trim(),
                name = parts[3].trim(),
                description = parts[4].trim(),
                version = parts[5].trim()
            )
        }
    }
}
