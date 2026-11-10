package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CandidatePlace
import com.meetme.server.coordination.domain.MeetingCandidate
import com.meetme.server.coordination.domain.matching.DeterministicCandidate
import com.meetme.server.shared.domain.CandidateId

/** Storage projection only; candidate selection, rank and identity are supplied by the caller. */
object MatchingCandidateStorageMapper {
    fun map(
        candidate: DeterministicCandidate,
        id: CandidateId,
        rank: Int,
        totalParticipants: Int,
    ): MeetingCandidate =
        MeetingCandidate(
            id = id,
            rank = rank,
            timeRanges = candidate.timeRanges,
            planType = candidate.planType,
            meetingMode = candidate.meetingMode,
            participantIds = candidate.participantIds,
            totalParticipants = totalParticipants,
            unverifiedPlaceParticipantIds = candidate.unverifiedPlaceParticipantIds,
            place =
                candidate.representativeArea?.let {
                    CandidatePlace(displayName = it.displayName)
                } ?: candidate.representativePlace?.let {
                    CandidatePlace(
                        displayName = "공통 가능 지역",
                        coordinate = it,
                    )
                },
        )
}
