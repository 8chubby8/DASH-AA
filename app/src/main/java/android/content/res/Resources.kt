package android.content.res

import java.awt.GraphicsEnvironment

/**
 * DASH-AA platform shim — `Resources.displayMetrics`, which upstream's Module Panel tab reads so its
 * tiles picture *this* screen's proportions. Answered from the primary display's real resolution.
 */
class Resources {
    val displayMetrics: DisplayMetrics get() = DisplayMetrics.current()
}

class DisplayMetrics(
    @JvmField val widthPixels: Int,
    @JvmField val heightPixels: Int,
) {
    companion object {
        fun current(): DisplayMetrics = runCatching {
            val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
            DisplayMetrics(mode.width, mode.height)
        }.getOrDefault(DisplayMetrics(1920, 1080))
    }
}
