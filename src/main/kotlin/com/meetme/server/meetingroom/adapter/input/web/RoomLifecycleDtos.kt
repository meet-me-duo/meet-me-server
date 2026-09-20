package com.meetme.server.meetingroom.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.meetingroom.application.port.input.InputDisclosurePolicy
import com.meetme.server.meetingroom.application.port.input.PublicRoomStatus
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.time.SearchRangeSource
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

@Schema(description = "익명 모임 방 생성 요청")
data class CreateRoomRequest(
    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(description = "모임 목적", example = "프로젝트 킥오프")
    val purpose: String,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "선호 모임 방식")
    val meetingMode: MeetingMode,
    @field:NotBlank
    @field:Size(max = 50)
    @field:JsonProperty("host_display_name")
    @field:Schema(description = "주최자 표시 이름, 공백 제거 후 1~50자", example = "민수")
    val hostDisplayName: String,
    @field:Min(2)
    @field:JsonProperty("expected_participants")
    @field:Schema(description = "자동 마감할 고유 제출 참여자 목표, 설정하면 2 이상", nullable = true)
    val expectedParticipants: Int? = null,
    @field:JsonProperty("submission_deadline")
    @field:Schema(description = "제출 마감 절대 시각", nullable = true, example = "2026-09-21T12:00:00Z")
    val submissionDeadline: Instant? = null,
    @field:JsonProperty("manual_only")
    @field:Schema(description = "자동 조건 없이 주최자가 수동으로만 마감하는지 여부")
    val manualOnly: Boolean = false,
    @field:JsonProperty("search_start_date")
    @field:Schema(description = "후보 탐색 시작 지역 날짜. 종료일과 함께 생략하면 오늘부터 14일", nullable = true)
    val searchStartDate: LocalDate? = null,
    @field:JsonProperty("search_end_date")
    @field:Schema(description = "후보 탐색 배타적 종료 지역 날짜. 시작일과 함께 제공", nullable = true)
    val searchEndDate: LocalDate? = null,
)

@Schema(description = "익명 참여 요청")
data class JoinRoomRequest(
    @field:NotBlank
    @field:Size(max = 50)
    @field:JsonProperty("display_name")
    @field:Schema(description = "참여자 표시 이름, 최초 참여에만 사용", example = "지수")
    val displayName: String,
)

@Schema(description = "수동 마감 요청")
data class CloseRoomRequest(
    @field:JsonProperty("confirm_early")
    @field:Schema(description = "자동 조건 충족 전 조기 마감을 재확인했는지 여부")
    val confirmEarly: Boolean = false,
)

@Schema(description = "현재 브라우저 세션의 방 참여 상태")
data class ViewerParticipationResponse(
    @field:Schema(description = "현재 세션이 이 방에 참여했는지 여부")
    val joined: Boolean,
    @field:JsonProperty("display_name")
    @field:Schema(description = "현재 세션 참여자의 표시 이름", nullable = true)
    val displayName: String?,
    @field:Schema(description = "현재 세션 참여자의 역할", nullable = true)
    val role: ParticipantRole?,
)

@Schema(description = "민감 식별자를 제외한 공개 방 정보")
data class RoomResponse(
    @field:JsonProperty("invite_code")
    @field:Schema(description = "공개 방 초대 코드", minLength = 22, maxLength = 22, pattern = "^[A-Za-z0-9_-]{22}$")
    val inviteCode: String,
    @field:Schema(description = "모임 목적")
    val purpose: String,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "선호 모임 방식")
    val meetingMode: MeetingMode,
    @field:JsonProperty("time_zone_id")
    @field:Schema(description = "IANA 방 시간대", example = "Asia/Seoul")
    val timeZoneId: String,
    @field:JsonProperty("search_start_date")
    @field:Schema(description = "후보 탐색 시작 지역 날짜")
    val searchStartDate: LocalDate,
    @field:JsonProperty("search_end_date")
    @field:Schema(description = "후보 탐색 배타적 종료 지역 날짜")
    val searchEndDate: LocalDate,
    @field:JsonProperty("search_range_source")
    @field:Schema(description = "탐색 날짜 범위 결정 출처")
    val searchRangeSource: SearchRangeSource,
    @field:JsonProperty("expected_participants")
    @field:Schema(description = "자동 마감 참여자 목표", nullable = true)
    val expectedParticipants: Int?,
    @field:JsonProperty("submission_deadline")
    @field:Schema(description = "제출 마감 절대 시각", nullable = true)
    val submissionDeadline: Instant?,
    @field:JsonProperty("manual_only")
    @field:Schema(description = "수동 전용 마감 정책 여부")
    val manualOnly: Boolean,
    @field:JsonProperty("collection_status")
    @field:Schema(description = "입력 수집 상태")
    val collectionStatus: CollectionStatus,
    @field:JsonProperty("closure_reason")
    @field:Schema(description = "입력 수집 마감 원인", nullable = true)
    val closureReason: ClosureReason?,
    @field:JsonProperty("closed_at")
    @field:Schema(description = "입력 수집 마감 절대 시각", nullable = true)
    val closedAt: Instant?,
    @field:JsonProperty("public_status")
    @field:Schema(description = "내부 상태를 조합한 공개 진행 상태")
    val publicStatus: PublicRoomStatus,
    @field:Schema(description = "현재 브라우저 세션의 참여 정보")
    val viewer: ViewerParticipationResponse,
    @field:JsonProperty("input_disclosure_policy")
    @field:Schema(description = "부분 결과에서 미반영 원문을 공개하는 범위")
    val inputDisclosurePolicy: InputDisclosurePolicy,
) {
    companion object {
        fun from(view: RoomView) =
            RoomResponse(
                view.inviteCode,
                view.purpose,
                view.meetingMode,
                view.timeZoneId,
                view.searchStartDate,
                view.searchEndDate,
                view.searchRangeSource,
                view.expectedParticipants,
                view.submissionDeadline,
                view.manualOnly,
                view.collectionStatus,
                view.closureReason,
                view.closedAt,
                view.publicStatus,
                ViewerParticipationResponse(view.viewer.joined, view.viewer.displayName, view.viewer.role),
                view.inputDisclosurePolicy,
            )
    }
}
