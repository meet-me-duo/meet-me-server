package com.meetme.server.coordination.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.coordination.domain.CandidateQuality
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "추천 variant의 실제 모임 방식")
enum class RecommendationMeetingMode {
    IN_PERSON,
    REMOTE,
}

@Schema(description = "시간안의 방식·장소·참석 조합")
data class RecommendationVariantResponse(
    @field:JsonProperty("variant_id")
    @field:Schema(description = "선택에 필요한 variant UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val variantId: UUID,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "대면 또는 온라인 방식", requiredMode = Schema.RequiredMode.REQUIRED)
    val meetingMode: RecommendationMeetingMode,
    @field:JsonProperty("attendance_count")
    @field:Schema(description = "참석 인원", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "2")
    val attendanceCount: Int,
    @field:JsonProperty("total_participants")
    @field:Schema(description = "고정 배치 전체 인원", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "2")
    val totalParticipants: Int,
    @field:JsonProperty("partial_attendance")
    @field:Schema(description = "전체 인원 중 일부 참석 여부", requiredMode = Schema.RequiredMode.REQUIRED)
    val partialAttendance: Boolean,
    @field:JsonProperty("place")
    @field:Schema(description = "대면 대표 지역; 온라인은 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val place: CandidatePlaceResponse?,
)

@Schema(description = "실제로 가능한 하나의 연속 시간안")
data class RecommendationOptionResponse(
    @field:JsonProperty("option_id")
    @field:Schema(description = "분석에 고정된 시간안 UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val optionId: UUID,
    @field:JsonProperty("rank")
    @field:Schema(
        description = "primary 표시 순서1~3; 대안은 null",
        requiredMode = Schema.RequiredMode.REQUIRED,
        nullable = true,
        minimum = "1",
        maximum = "3",
    )
    val rank: Int?,
    @field:JsonProperty("time_range")
    @field:Schema(description = "사용자가 실제 시작·종료를 고를 수 있는 전체 연속 창", requiredMode = Schema.RequiredMode.REQUIRED)
    val timeRange: CandidateTimeRangeResponse,
    @field:JsonProperty("variants")
    @field:Schema(description = "같은 시간의 모든 유효 방식·장소·참석 조합", requiredMode = Schema.RequiredMode.REQUIRED)
    val variants: List<RecommendationVariantResponse>,
    @field:JsonProperty("summary")
    @field:Schema(description = "실제 지역 날짜와 양쪽 종료 경계를 보존한 시간 설명", requiredMode = Schema.RequiredMode.REQUIRED)
    val summary: String,
)

@Schema(description = "분석에 고정된 날짜·시간 추천 또는 대안 페이지")
data class RecommendationListResponse(
    @field:JsonProperty("protocol")
    @field:Schema(
        description = "diverse-time-v1 지원 시 실제 시각 선택 가능; null은 legacy 분석",
        requiredMode = Schema.RequiredMode.REQUIRED,
        nullable = true,
    )
    val protocol: String?,
    @field:JsonProperty("analysis_id")
    @field:Schema(description = "현재 분석 UUID; 대안 요청·확정 시 동일 값 필수", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val analysisId: UUID,
    @field:JsonProperty("state_version")
    @field:Schema(description = "방 snapshot 버전", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    val stateVersion: Long,
    @field:JsonProperty("quality")
    @field:Schema(description = "입력 반영 품질", requiredMode = Schema.RequiredMode.REQUIRED)
    val quality: CandidateQuality,
    @field:JsonProperty("total_options")
    @field:Schema(description = "전체 시간안 수; primary와 대안 포함", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    val totalOptions: Int,
    @field:JsonProperty("options")
    @field:Schema(description = "primary 최대3 또는 요청한 대안 페이지", requiredMode = Schema.RequiredMode.REQUIRED)
    val options: List<RecommendationOptionResponse>,
    @field:JsonProperty("has_alternatives")
    @field:Schema(description = "primary 외 대안이 하나 이상 있음", requiredMode = Schema.RequiredMode.REQUIRED)
    val hasAlternatives: Boolean,
    @field:JsonProperty("next_cursor")
    @field:Schema(description = "다음 대안 페이지 cursor; null은 끝. 다른 분석에 재사용 불가", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val nextCursor: String?,
)

@Schema(description = "추천 창 안에서 실제 모임 시작·종료 선택")
data class RecommendationConfirmationRequest(
    @field:JsonProperty("analysis_id")
    @field:Schema(description = "현재 분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val analysisId: UUID,
    @field:JsonProperty("variant_id")
    @field:Schema(description = "해당 option의 variant UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val variantId: UUID,
    @field:JsonProperty("start_at")
    @field:Schema(
        description = "포함 시작; option.start 이상이며 end_at보다 이른 시각; 마이크로초까지",
        requiredMode = Schema.RequiredMode.REQUIRED,
        format = "date-time",
    )
    val startAt: Instant,
    @field:JsonProperty("end_at")
    @field:Schema(description = "배타적 종료; option.end 이하; 마이크로초까지", requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
    val endAt: Instant,
)

@Schema(description = "실제 확정된 typed 선택; legacy candidate FK와 별도")
data class RecommendationSelectionResponse(
    @field:JsonProperty("protocol")
    @field:Schema(description = "확정 프로토콜 diverse-time-v1", requiredMode = Schema.RequiredMode.REQUIRED)
    val protocol: String,
    @field:JsonProperty("analysis_id")
    @field:Schema(description = "확정 분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val analysisId: UUID,
    @field:JsonProperty("option_id")
    @field:Schema(description = "확정 시간안 UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val optionId: UUID,
    @field:JsonProperty("variant_id")
    @field:Schema(description = "확정 방식·장소·참석 조합 UUID", requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val variantId: UUID,
    @field:JsonProperty("start_at")
    @field:Schema(description = "실제 모임 포함 시작", requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
    val startAt: Instant,
    @field:JsonProperty("end_at")
    @field:Schema(description = "실제 모임 배타적 종료", requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time")
    val endAt: Instant,
)
