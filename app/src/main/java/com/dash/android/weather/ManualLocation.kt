package com.dash.android.weather

import kotlinx.serialization.Serializable

/**
 * A place the user has pinned by name (roadmap 1.5.4). When set it sits at the very top of the
 * location cascade — ahead of GPS and IP — so the weather scene reports the place the user chose
 * regardless of where the device thinks it is. Resolved once from a typed name via Open-Meteo's
 * geocoder ([WeatherProvider.geocodeCity]); the coordinates are stored so no lookup is needed again.
 */
@Serializable
data class ManualLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
