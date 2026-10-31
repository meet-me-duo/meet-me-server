package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.shared.domain.ParticipantId

/** Known restrictions remain binding. Missing verification permits a tentative time proposal, never a feasibility claim. */
internal object OfflinePlacePolicy {
    data class Resolution(
        val area: CompatiblePlaceArea? = null,
        val coordinate: GeoCoordinate? = null,
        val unverifiedParticipantIds: List<ParticipantId> = emptyList(),
    )

    fun resolve(participants: List<ParticipantMatchInput>): Resolution? {
        if (participants.any {
                it.offlineArea == null && it.offlineRegion == null && it.offlinePlaceAssessment == OfflinePlaceAssessment.LEGACY_REQUIRED
            }
        ) {
            return null
        }
        val areas = participants.mapNotNull { it.offlineArea }
        val regions = participants.mapNotNull { it.offlineRegion }
        // The two legacy spatial representations have no defined conversion contract.
        if (areas.isNotEmpty() && regions.isNotEmpty()) return null
        val pending = participants.filter { it.offlinePlaceAssessment == OfflinePlaceAssessment.UNVERIFIED }.map { it.participantId }
        if (areas.isNotEmpty()) {
            val common = areas.map { it.alternatives.map { area -> area.key }.toSet() }.reduce { left, right -> left intersect right }
            val key = common.sorted().firstOrNull() ?: return null
            val name = areas.flatMap { it.alternatives }.filter { it.key == key }.minOf { it.displayName }
            return Resolution(area = CompatiblePlaceArea(key, name), unverifiedParticipantIds = pending)
        }
        if (regions.isNotEmpty()) {
            val coordinate = GeoMatcher.representativePoint(regions) ?: return null
            return Resolution(coordinate = coordinate, unverifiedParticipantIds = pending)
        }
        return Resolution(unverifiedParticipantIds = pending)
    }

    fun resolveAll(participants: List<ParticipantMatchInput>): List<Resolution> {
        val first = resolve(participants) ?: return emptyList()
        if (first.area == null) return listOf(first)
        val areas = participants.mapNotNull { it.offlineArea }.map { it.alternatives }
        val keys = areas.map { values -> values.map { it.key }.toSet() }.reduce { left, right -> left intersect right }
        return keys.sorted().map { key ->
            first.copy(area = CompatiblePlaceArea(key, areas.flatten().filter { it.key == key }.minOf { it.displayName }))
        }
    }

    fun wildcard(input: ParticipantMatchInput): Boolean =
        input.offlineArea == null && input.offlineRegion == null && input.offlinePlaceAssessment != OfflinePlaceAssessment.LEGACY_REQUIRED
}
