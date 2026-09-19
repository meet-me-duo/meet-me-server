package com.meetme.server.submission.adapter.input.web

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import com.meetme.server.submission.application.port.input.SubmissionView
import com.meetme.server.submission.domain.ManualAvailability
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class ManualAvailabilityKind {
    DATED,
    WEEKLY,
}

@Schema(description = "방 전용 수동 가능 시간 구간")
data class ManualAvailabilityDto(
    @field:Schema(description = "명시 날짜 또는 주간 반복 구분")
    val kind: ManualAvailabilityKind,
    @field:Schema(description = "DATED 구간의 지역 날짜", nullable = true)
    val date: LocalDate? = null,
    @field:JsonProperty("day_of_week")
    @field:Schema(description = "WEEKLY 구간의 요일", nullable = true)
    val dayOfWeek: DayOfWeek? = null,
    @field:JsonProperty("start_time")
    @field:Schema(description = "포함 시작 지역 시각", example = "18:00")
    val startTime: LocalTime,
    @field:JsonProperty("end_time")
    @field:Schema(description = "제외 종료 지역 시각", example = "20:00")
    val endTime: LocalTime,
) {
    fun toDomain(): ManualAvailability {
        val time = LocalTimeRange.of(startTime, endTime)
        return when (kind) {
            ManualAvailabilityKind.DATED -> {
                require(date != null && dayOfWeek == null) { "DATED availability requires only date" }
                ManualAvailability.Dated(DatedTimeRange(date, time))
            }
            ManualAvailabilityKind.WEEKLY -> {
                require(date == null && dayOfWeek != null) { "WEEKLY availability requires only day_of_week" }
                ManualAvailability.Weekly(WeeklyTimeRange(dayOfWeek, time))
            }
        }
    }

    companion object {
        fun from(domain: ManualAvailability): ManualAvailabilityDto =
            when (domain) {
                is ManualAvailability.Dated ->
                    ManualAvailabilityDto(
                        ManualAvailabilityKind.DATED,
                        date = domain.range.date,
                        startTime = domain.range.time.startInclusive,
                        endTime = domain.range.time.endExclusive,
                    )
                is ManualAvailability.Weekly ->
                    ManualAvailabilityDto(
                        ManualAvailabilityKind.WEEKLY,
                        dayOfWeek = domain.range.dayOfWeek,
                        startTime = domain.range.time.startInclusive,
                        endTime = domain.range.time.endExclusive,
                    )
            }
    }
}

@Schema(description = "현재 익명 참여자의 조건 제출 또는 수정 요청")
data class SaveSubmissionRequest(
    @field:JsonProperty("raw_text")
    @field:Schema(description = "선택적인 자연어 시간·장소 조건", nullable = true, maxLength = 500)
    val rawText: String? = null,
    @field:Valid
    @field:Size(max = 256)
    @field:JsonProperty("manual_available_times")
    @field:Schema(description = "선택적인 방 전용 가능 시간 배열")
    val manualAvailableTimes: List<ManualAvailabilityDto> = emptyList(),
) {
    @JsonAnySetter
    fun rejectUnknownField(
        name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Nothing = throw IllegalArgumentException("Unsupported submission field: $name")
}

@Schema(description = "현재 익명 참여자의 최신 제출")
data class SubmissionResponse(
    @field:Schema(description = "불변 제출 revision")
    val revision: Int,
    @field:JsonProperty("raw_text")
    @field:Schema(description = "저장된 자연어 조건", nullable = true)
    val rawText: String?,
    @field:JsonProperty("manual_available_times")
    @field:Schema(description = "정렬·병합된 방 전용 가능 시간")
    val manualAvailableTimes: List<ManualAvailabilityDto>,
    @field:Schema(description = "BCP 47 제출 locale")
    val locale: String,
    @field:JsonProperty("created_at")
    @field:Schema(description = "현재 revision 저장 시각")
    val createdAt: Instant,
    @field:Schema(description = "현재 입력 수집 상태에서 수정 가능한지 여부")
    val editable: Boolean,
) {
    companion object {
        fun from(view: SubmissionView) =
            SubmissionResponse(
                view.revision,
                view.rawText,
                view.manualAvailability.map(ManualAvailabilityDto::from),
                view.locale,
                view.createdAt,
                view.editable,
            )
    }
}
