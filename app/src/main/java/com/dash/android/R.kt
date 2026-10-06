package com.dash.android

/**
 * DASH-AA platform shim — the generated `R` class Android builds from `res/`.
 *
 * Only the three drawables upstream's shared code names exist. Each id is an index into [paths]; the
 * files themselves are upstream's own, copied unchanged into `src/main/resources` (the gear is still
 * Android vector-drawable XML, which Compose for Desktop reads natively).
 */
object R {
    object drawable {
        const val ic_settings_gear = 1
        const val qr_source = 2
        const val qr_issues = 3
    }

    internal val paths = mapOf(
        drawable.ic_settings_gear to "ic_settings_gear.xml",
        drawable.qr_source to "qr_source.png",
        drawable.qr_issues to "qr_issues.png",
    )
}
