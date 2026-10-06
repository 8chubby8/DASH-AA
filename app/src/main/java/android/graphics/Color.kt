package android.graphics

/**
 * DASH-AA platform shim — the three `android.graphics.Color` helpers upstream calls.
 *
 * [parseColor] keeps Android's exact contract because the panel parser depends on it: `#RRGGBB` and
 * `#AARRGGBB` only (the parser expands `#RGB` itself), plus Android's small set of colour names, and an
 * [IllegalArgumentException] for anything else — which upstream's `runCatching` turns into "not a
 * colour", the browser rule. A shim that accepted more would let a layout render here and fail on a
 * tablet, which is exactly the divergence the previewer's Prime Directive forbids.
 */
object Color {

    @JvmStatic
    fun parseColor(colorString: String): Int {
        if (colorString.startsWith("#")) {
            val hex = colorString.substring(1)
            val value = hex.toLongOrNull(16) ?: throw IllegalArgumentException("Unknown color")
            return when (hex.length) {
                6 -> (value or 0xFF000000L).toInt()
                8 -> value.toInt()
                else -> throw IllegalArgumentException("Unknown color")
            }
        }
        return NAMED[colorString.lowercase()] ?: throw IllegalArgumentException("Unknown color")
    }

    @JvmStatic
    fun colorToHSV(color: Int, hsv: FloatArray) =
        RGBToHSV((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)

    @JvmStatic
    fun RGBToHSV(red: Int, green: Int, blue: Int, hsv: FloatArray) {
        val r = red / 255f; val g = green / 255f; val b = blue / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val delta = max - min
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        hsv[0] = if (h < 0f) h + 360f else h
        hsv[1] = if (max == 0f) 0f else delta / max
        hsv[2] = max
    }

    /** Android's own named colours — the framework's list, not CSS's (svg-subset.json carries those). */
    private val NAMED = mapOf(
        "black" to 0xFF000000.toInt(), "darkgray" to 0xFF444444.toInt(), "gray" to 0xFF888888.toInt(),
        "lightgray" to 0xFFCCCCCC.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(), "aqua" to 0xFF00FFFF.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(), "darkgrey" to 0xFF444444.toInt(), "grey" to 0xFF888888.toInt(),
        "lightgrey" to 0xFFCCCCCC.toInt(), "lime" to 0xFF00FF00.toInt(), "maroon" to 0xFF800000.toInt(),
        "navy" to 0xFF000080.toInt(), "olive" to 0xFF808000.toInt(), "purple" to 0xFF800080.toInt(),
        "silver" to 0xFFC0C0C0.toInt(), "teal" to 0xFF008080.toInt(),
    )
}
