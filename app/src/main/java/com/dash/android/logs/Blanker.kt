package com.dash.android.logs

/**
 * **Private details out of a log** (1.1.9) — for what is shown in Developer › Logs and for everything saved
 * to a USB stick.
 *
 * Roger, 2026-10-21: "I just don't want to share a log on GitHub and it have all data all over it. And
 * also if somebody shares a log with me because of a crash I don't want their information to be
 * compromised." On disk the log stays complete; what is shown, and what leaves the machine, is blanked.
 *
 * **Labels, not holes.** A detail is replaced by a label that stays the same for the life of one Blanker —
 * one viewing, or one save — so "Phone 1 connected … Phone 1 dropped" still reads as one phone. A
 * Bluetooth or Wi-Fi address keeps its maker ("Device 2 · Google"), which is not personal and often is the
 * clue. A device's name and its address get the same label.
 *
 * What goes:
 * - Bluetooth and Wi-Fi hardware addresses (`AA:BB:…`, and BlueZ's `dev_AA_BB_…`);
 * - the names of paired and nearby devices — but not modules, whose names are the builder's, not a person's;
 * - Wi-Fi network names;
 * - the place DASH found itself, and map coordinates;
 * - internet addresses — except the ones inside a home or car network (192.168…, 10…), which say nothing
 *   about who you are and are how modules are found;
 * - email addresses, phone numbers, USB serial numbers;
 * - the user's name and the machine's name (`/home/roger/…` becomes `~/…`).
 *
 * DASH's own lines are blanked completely, since DASH knows what it writes. The machine's log is free text
 * from every program on it: addresses are caught by their shape and the names DASH knows are swapped, but
 * a name some other program prints that DASH has never seen cannot be promised.
 */
class Blanker(
    private val known: Known = Known(),
    /** The maker for a hardware address's first half, if it can be found; null when not. */
    private val maker: (String) -> String? = { null },
) {
    data class Device(val address: String, val name: String, val phone: Boolean = false, val module: Boolean = false)

    data class Known(
        val devices: List<Device> = emptyList(),
        val networks: Set<String> = emptySet(),
        val places: Set<String> = emptySet(),
        val user: String? = null,
        val hostname: String? = null,
    )

    private val labels = HashMap<String, String>()
    private val counts = HashMap<String, Int>()
    private val makers = HashMap<String, String?>()
    private val places = known.places.toMutableSet()

    private fun label(key: String, kind: String): String =
        labels.getOrPut(key) { "$kind ${(counts[kind] ?: 0) + 1}".also { counts[kind] = (counts[kind] ?: 0) + 1 } }

    /** Device labels by address, so a name and its address read the same. */
    private fun deviceLabel(address: String): String {
        val key = address.uppercase().replace('_', ':').replace('-', ':')
        val device = known.devices.firstOrNull { it.address.equals(key, ignoreCase = true) }
        val base = label(key, if (device?.phone == true) "Phone" else "Device")
        val oui = key.replace(":", "").take(6)
        // A random (private) address has the second-lowest bit of its first byte set: it has no maker.
        val random = (key.take(2).toIntOrNull(16) ?: 0) and 0x02 != 0
        val made = if (random) null else makers.getOrPut(oui) { runCatching { maker(oui) }.getOrNull()?.let(::shortMaker) }
        return if (made != null) "$base · $made" else base
    }

    private val names: List<Pair<Regex, () -> String>> by lazy {
        val list = mutableListOf<Pair<String, () -> String>>()
        known.devices.filter { !it.module && it.name.length >= 3 && it.name != it.address }
            .forEach { d -> list += d.name to { deviceLabel(d.address).substringBefore(" · ") } }
        known.networks.filter { it.length >= 3 }.forEach { n -> list += n to { label("net:$n", "Network") } }
        known.hostname?.takeIf { it.length >= 3 }?.let { h -> list += h to { "this-machine" } }
        // Longest first, so "Roger's Pixel 8 Pro" goes before a "Pixel" inside it.
        list.sortedByDescending { it.first.length }.map { (name, to) -> Regex("(?<![\\w])" + Regex.escape(name) + "(?![\\w])") to to }
    }

    fun blank(text: String): String {
        var s = text
        // Places the weather said it found, learnt as they go past.
        LOCATED.find(s)?.groupValues?.get(1)?.takeIf { it.length >= 3 }?.let { places += it }

        s = MAC.replace(s) { deviceLabel(it.value.takeLast(17)).let { l -> if (it.value.startsWith("dev_")) "dev_($l)" else l } }
        for ((re, to) in names) if (re.containsMatchIn(s)) s = re.replace(s) { to() }
        s = SSID.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + label("net:" + m.groupValues[3], "Network") + m.groupValues[2] }
        for (p in places) if (p.length >= 3) s = s.replace(p, "(place)")
        s = COORDS.replace(s, "(map position)")
        s = LAT_LON.replace(s) { it.groupValues[1] + "(map position)" }
        s = EMAIL.replace(s, "(email address)")
        s = IPV6.replace(s) { if (it.value == "::1") it.value else "(internet address)" }
        s = IPV4.replace(s) { if (local(it.value)) it.value else "(internet address)" }
        s = PHONE_NUMBER.replace(s, "(phone number)")
        s = SERIAL.replace(s) { it.groupValues[1] + "(serial)" }
        known.user?.takeIf { it.length >= 2 }?.let { u ->
            s = s.replace("/home/$u", "~").replace("/run/media/$u", "/run/media/user")
            s = Regex("(?<![\\w])" + Regex.escape(u) + "(?![\\w])").replace(s, "user")
        }
        return s
    }

    private fun local(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 10 || p[0] == 127 || p[0] == 0 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254) || p[0] >= 224 || (p[0] == 100 && p[1] in 64..127)
    }

    private fun shortMaker(full: String): String =
        full.replace(Regex("""[,.]?\s*(Co\.?,?\s*)?(Ltd|Inc|LLC|Corp(oration)?|GmbH|Limited|Electronics|Technology|Technologies|Ind\.?)\.?\b.*""", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd(',', '.').ifBlank { full }

    companion object {
        private val MAC = Regex("""(?<![\w:])(?:dev_)?(?:[0-9A-Fa-f]{2}[:_-]){5}[0-9A-Fa-f]{2}(?![\w:])""")
        private val LOCATED = Regex("""located (.+?) \(-?\d""")
        /** "(51.4545, -2.5879)" — a pair in brackets with the precision of a place, not a volume. */
        private val COORDS = Regex("""\(-?\d{1,3}\.\d{3,},\s*-?\d{1,3}\.\d{3,}\)""")
        private val LAT_LON = Regex("""((?:lat|lon|lng|latitude|longitude)\s*[=:]\s*)-?\d{1,3}\.\d+""", RegexOption.IGNORE_CASE)
        /** Not systemd's "modprobe@sd_mod.service" and the like: a domain has no underscores, and ends in a word. */
        private val EMAIL = Regex("""[\w.+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.(?!(?:service|socket|target|mount|timer|slice|scope|device|path)\b)[A-Za-z]{2,}(?![\w.])""")
        /** A network named in the machine's log that DASH has not seen: wpa_supplicant's SSID='…'. */
        private val SSID = Regex("""(\bSSID\s*[=:]?\s*)(['"])(.+?)\2""", RegexOption.IGNORE_CASE)
        private val IPV4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])""")
        /** Five groups or more, or with "::" — so clock times (10:41:29) are never taken for one. */
        private val IPV6 = Regex("""(?<![\w:.])(?:(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,5})?|::(?:[0-9A-Fa-f]{1,4}:){0,5}[0-9A-Fa-f]{1,4})(?:%\w+)?(?:/\d{1,3})?(?![\w:])""")
        private val PHONE_NUMBER = Regex("""(?<![\w+])(?:\+\d{10,14}|0\d{9,10})(?![\w])""")
        private val SERIAL = Regex("""((?:SerialNumber|serial|Serial|ID_SERIAL_SHORT|ID_SERIAL)\s*[:=]\s*)\S+""")
    }
}
