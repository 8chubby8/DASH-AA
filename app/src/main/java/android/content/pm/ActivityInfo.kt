package android.content.pm

/**
 * DASH-AA platform shim — the orientation constants upstream's `DashOrientation` names, so that file
 * compiles unchanged. The values are Android's own; nothing on Linux reads them. DASH-AA turns the
 * screen through the display service instead (`display/DisplaySystem.kt`).
 */
object ActivityInfo {
    const val SCREEN_ORIENTATION_LANDSCAPE = 0
    const val SCREEN_ORIENTATION_PORTRAIT = 1
    const val SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8
    const val SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9
    const val SCREEN_ORIENTATION_FULL_SENSOR = 10
}
