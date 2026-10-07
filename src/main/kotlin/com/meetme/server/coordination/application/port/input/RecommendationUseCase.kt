package com.meetme.server.coordination.application.port.input

import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class RecommendationVariantView(
    val variantId: UUID,
    val meetingMode: MeetingMode,
    val attendanceCount: Int,
    val totalParticipants: Int,
    val partialAttendance: Boolean,
    val place: CandidatePlaceView?,
)

data class RecommendationOptionView(
    val optionId: UUID,
    val rank: Int?,
    val timeRange: InstantTimeRange,
    val variants: List<RecommendationVariantView>,
    val summary: String,
)

data class RecommendationListView(
    val protocol: String?,
    val analysisId: UUID,
    val stateVersion: Long,
    val quality: CandidateQuality,
    val totalOptions: Int,
    val options: List<RecommendationOptionView>,
    val hasAlternatives: Boolean,
    val nextCursor: String? = null,
)

data class SelectRecommendationCommand(
    val inviteCode: String,
    val optionId: UUID,
    val analysisId: UUID,
    val variantId: UUID,
    val startAt: Instant,
    val endAt: Instant,
    val rawCredential: String?,
    val locale: Locale,
)

interface RecommendationUseCase {
    fun primary(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): RecommendationListView

    fun alternatives(
        inviteCode: String,
        analysisId: UUID,
        cursor: String?,
        limit: Int,
        rawCredential: String?,
        locale: Locale,
    ): RecommendationListView

    fun confirm(command: SelectRecommendationCommand): ConfirmedResultView
}
