package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange

enum class RecommendationParticipantStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

data class RecommendationParticipantEvidence(
    val input: ParticipantMatchInput?,
    val interpretationComplete: Boolean,
    val timeKnown: Boolean,
    val placeReferenceKnown: Boolean = true,
)

object RecommendationParticipantStatusPolicy {
    fun assess(
        participantId: ParticipantId,
        window: InstantTimeRange,
        variant: RecommendationVariant,
        evidence: RecommendationParticipantEvidence,
        roomMode: MeetingMode,
    ): RecommendationParticipantStatus {
        val input = evidence.input ?: return RecommendationParticipantStatus.UNKNOWN
        if (input.participantId != participantId || !evidence.interpretationComplete || !evidence.timeKnown) {
            return RecommendationParticipantStatus.UNKNOWN
        }
        if (variant.meetingMode == MeetingMode.EITHER ||
            (roomMode != MeetingMode.EITHER && variant.meetingMode != roomMode)
        ) {
            return RecommendationParticipantStatus.UNKNOWN
        }
        val included = participantId in variant.participantIds
        val timeFits =
            TimeRangeMatcher.normalize(input.availableTimes).any {
                it.startInclusive <= window.startInclusive && window.endExclusive <= it.endExclusive
            }
        if (!timeFits) return confirmedConflict(included)
        val place =
            if (variant.meetingMode == MeetingMode.REMOTE) {
                PlaceMatch.MATCH
            } else {
                placeMatch(input, variant, evidence.placeReferenceKnown)
            }
        if (place == PlaceMatch.CONFLICT) return confirmedConflict(included)
        if (place == PlaceMatch.UNKNOWN || participantId in variant.unverifiedPlaceParticipantIds || !included) {
            return RecommendationParticipantStatus.UNKNOWN
        }
        return RecommendationParticipantStatus.AVAILABLE
    }

    private fun confirmedConflict(included: Boolean): RecommendationParticipantStatus =
        if (included) RecommendationParticipantStatus.UNKNOWN else RecommendationParticipantStatus.UNAVAILABLE

    private fun placeMatch(
        input: ParticipantMatchInput,
        variant: RecommendationVariant,
        referenceKnown: Boolean,
    ): PlaceMatch {
        if (input.offlineRegion != null ||
            (variant.representativePlace != null && variant.representativeArea != null) ||
            input.offlinePlaceAssessment == OfflinePlaceAssessment.LEGACY_REQUIRED
        ) {
            return PlaceMatch.UNKNOWN
        }
        input.offlineArea?.let { allowed ->
            if (!referenceKnown) return PlaceMatch.UNKNOWN
            val place = variant.representativeArea ?: return PlaceMatch.UNKNOWN
            if (allowed.alternatives.none { it.key == place.key }) return PlaceMatch.CONFLICT
        }
        return if (input.offlinePlaceAssessment == OfflinePlaceAssessment.UNVERIFIED) PlaceMatch.UNKNOWN else PlaceMatch.MATCH
    }

    private enum class PlaceMatch {
        MATCH,
        CONFLICT,
        UNKNOWN,
    }
}
