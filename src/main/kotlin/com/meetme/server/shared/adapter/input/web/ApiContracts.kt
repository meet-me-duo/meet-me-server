package com.meetme.server.shared.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

object GuestCookie {
    const val NAME = "meet_me_guest"
}

@Schema(description = "RFC 9457 기반 공통 오류 응답")
data class ApiProblemSchema(
    @field:Schema(description = "오류 유형 URI")
    val type: String,
    @field:Schema(description = "HTTP 오류 제목")
    val title: String,
    @field:Schema(description = "HTTP 상태 코드")
    val status: Int,
    @field:Schema(description = "locale에 맞춘 사용자 표시 설명")
    val detail: String,
    @field:Schema(description = "오류가 발생한 요청 경로")
    val instance: String,
    @field:Schema(description = "locale과 무관한 안정적 오류 코드")
    val code: String,
    @field:JsonProperty("field_errors")
    @field:Schema(description = "필드별 검증 오류", nullable = true)
    val fieldErrors: Map<String, String>? = null,
    @field:JsonProperty("submitted_participants")
    @field:Schema(description = "조기 마감 확인 시 현재 제출 참여자 수", nullable = true)
    val submittedParticipants: Int? = null,
    @field:JsonProperty("expected_participants")
    @field:Schema(description = "조기 마감 확인 시 목표 참여자 수", nullable = true)
    val expectedParticipants: Int? = null,
    @field:JsonProperty("submission_deadline")
    @field:Schema(description = "조기 마감 확인 시 제출 마감", nullable = true)
    val submissionDeadline: Instant? = null,
    @field:Schema(description = "길이·개수 검증의 실제 값", nullable = true)
    val actual: Int? = null,
    @field:Schema(description = "길이·개수 검증의 최대 허용 값", nullable = true)
    val maximum: Int? = null,
    @field:Schema(description = "세부 검증 거부 사유", nullable = true)
    val reason: String? = null,
    @field:JsonProperty("retry_after_seconds")
    @field:Schema(description = "요청 제한 해제까지 남은 초", nullable = true)
    val retryAfterSeconds: Long? = null,
)
