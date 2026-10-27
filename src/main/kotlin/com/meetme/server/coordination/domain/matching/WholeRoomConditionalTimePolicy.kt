package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange

data class ConditionalTimeResolution(
    val participants: List<ParticipantMatchInput>,
    val unsupportedParticipantIds: Set<ParticipantId>,
)

object WholeRoomConditionalTimePolicy {
    /** Resolve once for the frozen room, before either matcher enumerates any participant subsets. */
    fun resolve(
        primaryInputs: List<ParticipantMatchInput>,
        conditionalFallbacks: Map<ParticipantId, List<InstantTimeRange>>,
        primaryInputsKnown: Boolean = true,
    ): ConditionalTimeResolution {
        if (conditionalFallbacks.isEmpty()) return ConditionalTimeResolution(primaryInputs, emptySet())
        val participantIds = primaryInputs.map { it.participantId }.toSet()
        require(participantIds.size == primaryInputs.size)
        require(participantIds.containsAll(conditionalFallbacks.keys))
        if (!primaryInputsKnown || conditionalFallbacks.size > 1) {
            return ConditionalTimeResolution(
                primaryInputs.map { input ->
                    if (input.participantId in conditionalFallbacks) {
                        input.copy(availableTimes = emptyList(), explicitPreferences = input.explicitPreferences.copy(canScore = false))
                    } else {
                        input
                    }
                },
                conditionalFallbacks.keys.toSet(),
            )
        }
        val primaryIntersection = primaryInputs.map { it.availableTimes }.reduce(TimeRangeMatcher::intersect)
        if (primaryIntersection.isNotEmpty()) return ConditionalTimeResolution(primaryInputs, emptySet())
        return ConditionalTimeResolution(
            primaryInputs.map { input ->
                conditionalFallbacks[input.participantId]?.let { fallback ->
                    input.copy(availableTimes = TimeRangeMatcher.normalize(input.availableTimes + fallback))
                } ?: input
            },
            emptySet(),
        )
    }
}
