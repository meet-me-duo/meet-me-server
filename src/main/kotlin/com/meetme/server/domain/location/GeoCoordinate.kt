package com.meetme.server.domain.location

import java.math.BigDecimal

data class GeoCoordinate private constructor(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
) {
    companion object {
        fun of(
            latitude: BigDecimal,
            longitude: BigDecimal,
        ): GeoCoordinate {
            require(latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE) { "Latitude must be between -90 and 90" }
            require(longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE) {
                "Longitude must be between -180 and 180"
            }
            return GeoCoordinate(latitude, longitude)
        }

        private val MIN_LATITUDE = BigDecimal("-90")
        private val MAX_LATITUDE = BigDecimal("90")
        private val MIN_LONGITUDE = BigDecimal("-180")
        private val MAX_LONGITUDE = BigDecimal("180")
    }
}
