package com.meetme.server.application.port.output

import com.meetme.server.domain.location.GeoCoordinate

data class NormalizedPlaceSnapshot(
    val providerPlaceId: String,
    val displayName: String,
    val coordinate: GeoCoordinate,
)

sealed interface PlaceNormalizationResult {
    data class Resolved(
        val place: NormalizedPlaceSnapshot,
    ) : PlaceNormalizationResult

    data object NoExactMatch : PlaceNormalizationResult

    data object AmbiguousExactMatch : PlaceNormalizationResult
}

enum class PlaceSearchFailureKind {
    CONFIGURATION,
    TIMEOUT,
    RATE_LIMIT,
    SERVER,
    CLIENT,
    INVALID_RESPONSE,
}

class PlaceSearchException(
    val kind: PlaceSearchFailureKind,
    cause: Throwable? = null,
) : RuntimeException(kind.name, cause)

interface PlaceSearchPort {
    fun normalize(query: String): PlaceNormalizationResult
}
