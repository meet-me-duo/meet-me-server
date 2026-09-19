package com.meetme.server.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.application.port.input.CandidateListView
import com.meetme.server.application.port.input.CandidatePlaceView
import com.meetme.server.application.port.input.CandidateView
import com.meetme.server.application.port.input.ConfirmedResultView
import com.meetme.server.application.port.input.UnappliedInputView
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.matching.PlanType
import com.meetme.server.domain.meeting.MeetingMode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "후보 대표 장소")
data class CandidatePlaceResponse(
    @field:JsonProperty("display_name")
    @field:Schema(description = "정규화된 표시 이름")
    val displayName: String,
    @field:Schema(description = "WGS84 위도")
    val latitude: Double,
    @field:Schema(description = "WGS84 경도")
    val longitude: Double,
) {
    companion object {
        fun from(view: CandidatePlaceView) = CandidatePlaceResponse(view.displayName, view.latitude, view.longitude)
    }
}

@Schema(description = "후보의 실제 UTC 시간 구간")
data class CandidateTimeRangeResponse(
    @field:JsonProperty("start_at")
    @field:Schema(description = "포함 시작 절대 시각")
    val startAt: Instant,
    @field:JsonProperty("end_at")
    @field:Schema(description = "배타적 종료 절대 시각")
    val endAt: Instant,
)

@Schema(description = "결정론적으로 계산된 모임 후보")
data class CandidateResponse(
    @field:JsonProperty("candidate_id")
    @field:Schema(description = "후보 식별자")
    val candidateId: UUID,
    @field:JsonProperty("plan_type")
    @field:Schema(description = "후보 플랜 A, B 또는 C")
    val planType: PlanType,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "후보의 대면 또는 비대면 방식")
    val meetingMode: MeetingMode,
    @field:Schema(description = "후보 표시 순위", minimum = "1", maximum = "3")
    val rank: Int,
    @field:JsonProperty("attendance_count")
    @field:Schema(description = "후보에 포함되는 참여자 수")
    val attendanceCount: Int,
    @field:JsonProperty("total_participants")
    @field:Schema(description = "고정 배치의 전체 참여자 수")
    val totalParticipants: Int,
    @field:JsonProperty("time_ranges")
    @field:Schema(description = "UTC 실제 시점 기준 후보 시간 구간")
    val timeRanges: List<CandidateTimeRangeResponse>,
    @field:Schema(description = "대면 후보의 대표 장소", nullable = true)
    val place: CandidatePlaceResponse?,
    @field:Schema(description = "요청 locale로 생성한 후보 요약")
    val summary: String,
) {
    companion object {
        fun from(view: CandidateView) =
            CandidateResponse(
                view.candidateId,
                view.planType,
                view.meetingMode,
                view.rank,
                view.attendanceCount,
                view.totalParticipants,
                view.timeRanges.map { CandidateTimeRangeResponse(it.startInclusive, it.endExclusive) },
                view.place?.let(CandidatePlaceResponse::from),
                view.summary,
            )
    }
}

@Schema(description = "후보 생성 결과")
data class CandidateListResponse(
    @field:Schema(description = "전체 또는 부분 반영 품질")
    val quality: CandidateQuality,
    @field:JsonProperty("applied_submissions")
    @field:Schema(description = "매칭에 반영된 제출 수")
    val appliedSubmissions: Int,
    @field:JsonProperty("total_submissions")
    @field:Schema(description = "고정 배치의 전체 제출 수")
    val totalSubmissions: Int,
    @field:JsonProperty("unapplied_inputs")
    @field:Schema(description = "반영되지 않은 입력 수")
    val unappliedInputs: Int,
    @field:Schema(description = "0개일 수 있는 우선순위 후보 배열")
    val candidates: List<CandidateResponse>,
) {
    companion object {
        fun from(view: CandidateListView) =
            CandidateListResponse(
                view.quality,
                view.appliedSubmissions,
                view.totalSubmissions,
                view.unappliedInputs,
                view.candidates.map(CandidateResponse::from),
            )
    }
}

@Schema(description = "주최자에게만 공개되는 미반영 입력")
data class UnappliedInputResponse(
    @field:JsonProperty("participant_display_name")
    @field:Schema(description = "입력을 제출한 참여자의 표시 이름")
    val participantDisplayName: String,
    @field:JsonProperty("raw_text")
    @field:Schema(description = "후보에 반영되지 않은 원문")
    val rawText: String,
    @field:Schema(description = "언어 중립 미반영 사유 코드")
    val reason: String,
) {
    companion object {
        fun from(view: UnappliedInputView) = UnappliedInputResponse(view.participantDisplayName, view.rawText, view.reason)
    }
}

@Schema(description = "주최자가 확정한 최종 결과")
data class ConfirmedResultResponse(
    @field:Schema(description = "확정 후보")
    val candidate: CandidateResponse,
    @field:JsonProperty("confirmed_at")
    @field:Schema(description = "확정 시각")
    val confirmedAt: Instant,
) {
    companion object {
        fun from(view: ConfirmedResultView) = ConfirmedResultResponse(CandidateResponse.from(view.candidate), view.confirmedAt)
    }
}
