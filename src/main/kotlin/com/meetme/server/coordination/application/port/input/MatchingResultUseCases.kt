package com.meetme.server.coordination.application.port.input

import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class CandidatePlaceView(
    val displayName: String,
    val latitude: Double?,
    val longitude: Double?,
)

data class CandidateView(
    val candidateId: UUID,
    val planType: PlanType,
    val meetingMode: MeetingMode,
    val rank: Int,
    val attendanceCount: Int,
    val totalParticipants: Int,
    val timeRanges: List<InstantTimeRange>,
    val place: CandidatePlaceView?,
    val summary: String,
)

data class CandidateListView(
    val quality: CandidateQuality,
    val appliedSubmissions: Int,
    val totalSubmissions: Int,
    val unappliedInputs: Int,
    val candidates: List<CandidateView>,
)

data class UnappliedInputView(
    val participantDisplayName: String,
    val rawText: String,
    val reason: String,
)

data class ConfirmedResultView(
    val candidate: CandidateView,
    val confirmedAt: Instant,
)

interface GetCandidatesUseCase {
    fun getCandidates(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): CandidateListView
}

interface GetUnappliedInputsUseCase {
    fun getUnappliedInputs(
        inviteCode: String,
        rawCredential: String?,
    ): List<UnappliedInputView>
}

interface ConfirmCandidateUseCase {
    fun confirm(
        inviteCode: String,
        candidateId: UUID,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView
}

interface GetConfirmedResultUseCase {
    fun getConfirmed(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView
}

enum class MatchingResultErrorCode {
    ROOM_NOT_FOUND,
    GUEST_SESSION_REQUIRED,
    GUEST_SESSION_INVALID,
    CANDIDATES_NOT_READY,
    CANDIDATE_NOT_FOUND,
    RESULT_NOT_CONFIRMED,
    PARTICIPANT_REQUIRED,
    HOST_PERMISSION_REQUIRED,
    CANDIDATE_ALREADY_CONFIRMED,
}

class MatchingResultException(
    val code: MatchingResultErrorCode,
) : RuntimeException(code.name)
