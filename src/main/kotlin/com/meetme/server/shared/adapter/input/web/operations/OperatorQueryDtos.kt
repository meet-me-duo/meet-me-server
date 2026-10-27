package com.meetme.server.shared.adapter.input.web.operations

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.shared.application.port.input.operations.AttemptSummary
import com.meetme.server.shared.application.port.input.operations.InvocationSummary
import com.meetme.server.shared.application.port.input.operations.LocalClientCall
import com.meetme.server.shared.application.port.input.operations.ProviderUsage
import com.meetme.server.shared.application.port.input.operations.RevisionCounts
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "운영자 전용 RoomSummaryResponse. 원문 및 자격 증명 없음")
data class RoomSummaryResponse(
    @field:JsonProperty("id")
    @field:Schema(description = "내부 방 참조. 초대 코드가 아님")
    val id: UUID,
    @field:JsonProperty("created_at")
    @field:Schema(description = "방 생성 시각. 주최자 제출 시각이 아님")
    val createdAt: Instant,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "현재 방의 모임 방식")
    val meetingMode: String,
    @field:JsonProperty("time_zone_id")
    @field:Schema(description = "현재 방 시간대")
    val timeZoneId: String,
    @field:JsonProperty("search_start_date")
    @field:Schema(description = "현재 탐색 시작일 포함")
    val searchStartDate: LocalDate,
    @field:JsonProperty("search_end_date")
    @field:Schema(description = "현재 탐색 종료일 제외")
    val searchEndDate: LocalDate,
    @field:JsonProperty("expected_participants")
    @field:Schema(description = "예상 제출 인원. 미설정이면 null", nullable = true)
    val expectedParticipants: Int?,
    @field:JsonProperty("submission_deadline")
    @field:Schema(description = "설정된 제출 마감. 미설정이면 null", nullable = true)
    val submissionDeadline: Instant?,
    @field:JsonProperty("manual_only")
    @field:Schema(description = "수동 마감 전용 여부")
    val manualOnly: Boolean,
    @field:JsonProperty("collection_status")
    @field:Schema(description = "현재 입력 수집 상태")
    val collectionStatus: String,
    @field:JsonProperty("closure_reason")
    @field:Schema(description = "기록된 마감 사유. 마감 전이면 null", nullable = true)
    val closureReason: String?,
    @field:JsonProperty("closed_at")
    @field:Schema(description = "기록된 최초 마감 시각", nullable = true)
    val closedAt: Instant?,
    @field:JsonProperty("participant_count")
    @field:Schema(description = "현재 참여자 수. 개인정보는 포함하지 않음")
    val participantCount: Long,
    @field:JsonProperty("submitted_participant_count")
    @field:Schema(description = "현재 제출 head가 있는 참여자 수")
    val submittedParticipantCount: Long,
    @field:JsonProperty("host_submitted")
    @field:Schema(description = "현재 주최자의 저장된 제출 존재 여부")
    val hostSubmitted: Boolean,
    @field:JsonProperty("current_run_status")
    @field:Schema(description = "현재 활성 run 상태. 과거 실행 결과가 아님", nullable = true)
    val currentRunStatus: String?,
) {
    companion object {
        fun from(value: RoomSummary) =
            RoomSummaryResponse(
                value.id,
                value.createdAt,
                value.meetingMode,
                value.timeZoneId,
                value.searchStartDate,
                value.searchEndDate,
                value.expectedParticipants,
                value.submissionDeadline,
                value.manualOnly,
                value.collectionStatus,
                value.closureReason,
                value.closedAt,
                value.participantCount,
                value.submittedParticipantCount,
                value.hostSubmitted,
                value.currentRunStatus,
            )
    }
}

@Schema(description = "운영자 전용 InvocationSummaryResponse. 원문 및 자격 증명 없음")
data class InvocationSummaryResponse(
    @field:JsonProperty("id")
    @field:Schema(description = "영속 논리 실행 참조")
    val id: UUID,
    @field:JsonProperty("run_id")
    @field:Schema(description = "소속 run 참조")
    val runId: UUID,
    @field:JsonProperty("run_version")
    @field:Schema(description = "STRUCTURING 시점에 고정된 실행 버전")
    val runVersion: Long,
    @field:JsonProperty("started_at")
    @field:Schema(description = "영속 실행 시작 시각")
    val startedAt: Instant,
    @field:JsonProperty("deadline_at")
    @field:Schema(description = "최초 실행 제한 시각")
    val deadlineAt: Instant,
    @field:JsonProperty("finished_at")
    @field:Schema(description = "영속 구조화 종결 시각. 최종 후보 성공을 보증하지 않음", nullable = true)
    val finishedAt: Instant?,
    @field:JsonProperty("winner_attempt_id")
    @field:Schema(description = "구조화 승자 참조. 후보 게시 성공을 보증하지 않음", nullable = true)
    val winnerAttemptId: UUID?,
    @field:JsonProperty("gemini_admitted_attempts")
    @field:Schema(description = "Gemini 승인 시도 수. HTTP 건수가 아님")
    val geminiAdmittedAttempts: Int,
    @field:JsonProperty("luna_admitted_attempts")
    @field:Schema(description = "OpenAI 폴백 승인 시도 수. HTTP 건수가 아님")
    val lunaAdmittedAttempts: Int,
    @field:JsonProperty("execution_ordinal")
    @field:Schema(description = "같은 run 안의 논리 실행 순번")
    val executionOrdinal: Long,
    @field:JsonProperty("revision_linked")
    @field:Schema(description = "QUEUED 수정 라운드와 연결된 run인지 여부")
    val revisionLinked: Boolean,
    @field:JsonProperty("recorded_elapsed_millis")
    @field:Schema(description = "저장된 벽시계 시각 차이. HTTP 시간이 아니며 미완료는 null", nullable = true)
    val recordedElapsedMillis: Long?,
    @field:JsonProperty("parser_dispatch_count")
    @field:Schema(description = "모든 영속 승인 관측이 있을 때만 파서 진입 합계. 없거나 부분이면 null", nullable = true)
    val parserDispatchCount: Long?,
    @field:JsonProperty("http_request_count")
    @field:Schema(description = "실제 HTTP 횟수를 확인할 증거가 없어 null", nullable = true)
    val httpRequestCount: Long?,
    @field:JsonProperty("client_call_count")
    @field:Schema(description = "모든 승인 관측이 있을 때 로컬 client call 수. 수신·청구 건수가 아님", nullable = true)
    val clientCallCount: Long?,
    @field:JsonProperty("known_client_call_count")
    @field:Schema(description = "관측된 로컬 호출만의 소계. 미관측 시도는 포함하지 않음")
    val knownClientCallCount: Long,
    @field:JsonProperty("transport_observed_attempt_count")
    @field:Schema(description = "완결된 로컬 transport 관측이 기록된 승인 시도 수")
    val transportObservedAttemptCount: Long,
    @field:JsonProperty("transport_unknown_attempt_count")
    @field:Schema(description = "로컬 transport 관측이 없는 승인 수. 진행·중단·저장 실패 등을 구분할 수 없음")
    val transportUnknownAttemptCount: Long,
) {
    companion object {
        fun from(value: InvocationSummary) =
            InvocationSummaryResponse(
                value.id,
                value.runId,
                value.runVersion,
                value.startedAt,
                value.deadlineAt,
                value.finishedAt,
                value.winnerAttemptId,
                value.geminiAdmittedAttempts,
                value.lunaAdmittedAttempts,
                value.executionOrdinal,
                value.revisionLinked,
                value.recordedElapsedMillis,
                value.parserDispatchCount,
                value.httpRequestCount,
                value.clientCallCount,
                value.knownClientCallCount,
                value.transportObservedAttemptCount,
                value.transportUnknownAttemptCount,
            )
    }
}

@Schema(description = "운영자 전용 AttemptSummaryResponse. 원문 및 자격 증명 없음")
data class AttemptSummaryResponse(
    @field:JsonProperty("id")
    @field:Schema(description = "승인 시도 참조")
    val id: UUID,
    @field:JsonProperty("attempt_number")
    @field:Schema(description = "run 내 승인 시도 번호")
    val attemptNumber: Int,
    @field:JsonProperty("provider")
    @field:Schema(description = "GEMINI 또는 OPENAI")
    val provider: String,
    @field:JsonProperty("model")
    @field:Schema(description = "기록된 제한된 모델 이름")
    val model: String,
    @field:JsonProperty("policy_version")
    @field:Schema(description = "기록된 실행 정책 버전")
    val policyVersion: String,
    @field:JsonProperty("started_at")
    @field:Schema(description = "승인 시도 시작 기록")
    val startedAt: Instant,
    @field:JsonProperty("finished_at")
    @field:Schema(description = "미완료이면 null", nullable = true)
    val finishedAt: Instant?,
    @field:JsonProperty("failure_kind")
    @field:Schema(description = "제한된 실패 분류. 임의 문자열은 OTHER. null은 성공 증명이 아님", nullable = true)
    val failureKind: String?,
    @field:JsonProperty("input_tokens")
    @field:Schema(description = "알려진 입력 토큰. 미확인은 null", nullable = true)
    val inputTokens: Long?,
    @field:JsonProperty("output_tokens")
    @field:Schema(description = "알려진 출력 토큰. 미확인은 null", nullable = true)
    val outputTokens: Long?,
    @field:JsonProperty("estimated_cost_usd")
    @field:Schema(description = "알려진 USD 추정 비용의 십진 문자열. 미확인은 null", nullable = true)
    val estimatedCostUsd: String?,
    @field:JsonProperty("recorded_elapsed_millis")
    @field:Schema(description = "저장된 시각 차이. 승인 대기 등을 포함하며 HTTP 시간이 아님", nullable = true)
    val recordedElapsedMillis: Long?,
    @field:JsonProperty("role")
    @field:Schema(description = "승인 순서의 INITIAL, RETRY 또는 FALLBACK")
    val role: String,
    @field:JsonProperty("parser_dispatched")
    @field:Schema(description = "기록된 파서 진입 여부. 관측 부재는 null", nullable = true)
    val parserDispatched: Boolean?,
    @field:JsonProperty("client_calls")
    @field:Schema(description = "완결된 로컬 호출들. 관측 부재 null, 확인된 무호출은 빈 배열", nullable = true)
    val clientCalls: List<LocalClientCallResponse>?,
) {
    companion object {
        fun from(value: AttemptSummary) =
            AttemptSummaryResponse(
                value.id,
                value.attemptNumber,
                value.provider,
                value.model,
                value.policyVersion,
                value.startedAt,
                value.finishedAt,
                value.failureKind,
                value.inputTokens,
                value.outputTokens,
                value.estimatedCostUsd,
                value.recordedElapsedMillis,
                value.role,
                value.parserDispatched,
                value.clientCalls?.map(LocalClientCallResponse::from),
            )
    }
}

@Schema(description = "로컬 OkHttp call lifecycle. 원문·URL·헤더·오류 메시지를 포함하지 않음")
data class LocalClientCallResponse(
    @field:JsonProperty("call_number")
    @field:Schema(description = "이 승인 시도에서 callStart 순번", minimum = "1", maximum = "256")
    val callNumber: Int,
    @field:JsonProperty("elapsed_nanos")
    @field:Schema(description = "단조 시계로 잰 로컬 call 경과 나노초", minimum = "0")
    val elapsedNanos: Long,
    @field:JsonProperty("outcome")
    @field:Schema(description = "COMPLETED 또는 FAILED. HTTP 오류도 COMPLETED일 수 있음", allowableValues = ["COMPLETED", "FAILED"])
    val outcome: String,
    @field:JsonProperty("http_status")
    @field:Schema(description = "관측된 HTTP 상태. 응답 헤더가 없으면 null", nullable = true, minimum = "100", maximum = "599")
    val httpStatus: Int?,
) {
    companion object {
        fun from(value: LocalClientCall) = LocalClientCallResponse(value.callNumber, value.elapsedNanos, value.outcome, value.httpStatus)
    }
}

@Schema(description = "운영자 전용 ProviderUsageResponse. 원문 및 자격 증명 없음")
data class ProviderUsageResponse(
    @field:JsonProperty("provider")
    @field:Schema(description = "GEMINI 또는 OPENAI")
    val provider: String,
    @field:JsonProperty("recorded_attempt_count")
    @field:Schema(description = "집계 분모인 기록된 승인 시도 수")
    val recordedAttemptCount: Long,
    @field:JsonProperty("input_known_count")
    @field:Schema(description = "입력 토큰 확인 시도 수")
    val inputKnownCount: Long,
    @field:JsonProperty("output_known_count")
    @field:Schema(description = "출력 토큰 확인 시도 수")
    val outputKnownCount: Long,
    @field:JsonProperty("cost_known_count")
    @field:Schema(description = "추정 비용 확인 시도 수")
    val costKnownCount: Long,
    @field:JsonProperty("input_unknown_count")
    @field:Schema(description = "입력 토큰 미확인 시도 수")
    val inputUnknownCount: Long,
    @field:JsonProperty("output_unknown_count")
    @field:Schema(description = "출력 토큰 미확인 시도 수")
    val outputUnknownCount: Long,
    @field:JsonProperty("cost_unknown_count")
    @field:Schema(description = "추정 비용 미확인 시도 수")
    val costUnknownCount: Long,
    @field:JsonProperty("known_input_tokens")
    @field:Schema(description = "확인된 입력 토큰의 소계. 확인 시도가 없으면 null", nullable = true)
    val knownInputTokens: Long?,
    @field:JsonProperty("known_output_tokens")
    @field:Schema(description = "확인된 출력 토큰의 소계. 확인 시도가 없으면 null", nullable = true)
    val knownOutputTokens: Long?,
    @field:JsonProperty("known_cost_usd")
    @field:Schema(description = "확인된 USD 추정 비용의 소계. 전체 청구 비용이 아님", nullable = true)
    val knownCostUsd: String?,
    @field:JsonProperty("coverage_basis")
    @field:Schema(description = "RECORDED_ADMISSIONS. 실제 dispatch 집계 분모가 아님")
    val coverageBasis: String,
) {
    companion object {
        fun from(value: ProviderUsage) =
            ProviderUsageResponse(
                value.provider,
                value.recordedAttemptCount,
                value.inputKnownCount,
                value.outputKnownCount,
                value.costKnownCount,
                value.inputUnknownCount,
                value.outputUnknownCount,
                value.costUnknownCount,
                value.knownInputTokens,
                value.knownOutputTokens,
                value.knownCostUsd,
                value.coverageBasis,
            )
    }
}

@Schema(description = "운영자 전용 RevisionCountsResponse. 원문 및 자격 증명 없음")
data class RevisionCountsResponse(
    @field:JsonProperty("open")
    @field:Schema(description = "현재 열린 수정 라운드 수")
    val open: Long,
    @field:JsonProperty("queued")
    @field:Schema(description = "새 run 생성으로 처리된 수정 라운드 수")
    val queued: Long,
    @field:JsonProperty("reused")
    @field:Schema(description = "기존 결과 재사용으로 처리된 수정 라운드 수. 신규 호출이 아님")
    val reused: Long,
) {
    companion object {
        fun from(value: RevisionCounts) =
            RevisionCountsResponse(
                value.open,
                value.queued,
                value.reused,
            )
    }
}

@Schema(description = "운영자 전용 OperatorRoomPageResponse. 원문 및 자격 증명 없음")
data class OperatorRoomPageResponse(
    @field:JsonProperty("items")
    @field:Schema(description = "현재 페이지의 방 요약")
    val items: List<RoomSummaryResponse>,
    @field:JsonProperty("next_cursor")
    @field:Schema(description = "다음 페이지 참조. 더 없으면 null", nullable = true)
    val nextCursor: String?,
)

@Schema(description = "운영자 전용 OperatorRoomDetailResponse. 원문 및 자격 증명 없음")
data class OperatorRoomDetailResponse(
    @field:JsonProperty("room")
    @field:Schema(description = "현재 방 설정과 상태")
    val room: RoomSummaryResponse,
    @field:JsonProperty("invocations")
    @field:Schema(description = "과거 버전을 포함한 실행 페이지")
    val invocations: List<InvocationSummaryResponse>,
    @field:JsonProperty("next_cursor")
    @field:Schema(description = "다음 실행 페이지 참조", nullable = true)
    val nextCursor: String?,
    @field:JsonProperty("revision_counts")
    @field:Schema(description = "새 분석과 재사용을 구분한 수정 라운드 수")
    val revisionCounts: RevisionCountsResponse,
    @field:JsonProperty("legacy_attempt_count")
    @field:Schema(description = "invocation 연결이 없는 과거 시도 수. HTTP 건수가 아님")
    val legacyAttemptCount: Long,
)

@Schema(description = "운영자 전용 OperatorInvocationDetailResponse. 원문 및 자격 증명 없음")
data class OperatorInvocationDetailResponse(
    @field:JsonProperty("room_id")
    @field:Schema(description = "소속 방 내부 참조")
    val roomId: UUID,
    @field:JsonProperty("invocation")
    @field:Schema(description = "영속 구조화 실행 기록")
    val invocation: InvocationSummaryResponse,
    @field:JsonProperty("attempts")
    @field:Schema(description = "이 실행에만 소속된 승인 시도")
    val attempts: List<AttemptSummaryResponse>,
    @field:JsonProperty("usage_by_provider")
    @field:Schema(description = "공급자별 확인 범위와 알려진 소계")
    val usageByProvider: List<ProviderUsageResponse>,
)
