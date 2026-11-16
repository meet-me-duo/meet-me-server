package com.meetme.server.shared.adapter.input.web.operations

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.shared.application.port.input.operations.OperatorAnalysis
import com.meetme.server.shared.application.port.input.operations.OperatorInput
import com.meetme.server.shared.application.port.input.operations.OperatorInputCheck
import com.meetme.server.shared.application.port.input.operations.OperatorPrecheckAttempt
import com.meetme.server.shared.application.port.input.operations.OperatorQuestion
import com.meetme.server.shared.application.port.input.operations.OperatorResult
import com.meetme.server.shared.application.port.input.operations.OperatorRoomDiagnostics
import com.meetme.server.shared.application.port.input.operations.OperatorStoredCondition
import com.meetme.server.shared.application.port.input.operations.OperatorStoredInterpretation
import com.meetme.server.shared.application.port.input.operations.OperatorTimelineEvent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorAnalysisResponse(
    @field:JsonProperty("id")
    @field:Schema(description = "분석 참조")
    val id: UUID,
    @field:JsonProperty("status")
    @field:Schema(description = "기록된 상태")
    val status: String,
    @field:JsonProperty("created_at")
    @field:Schema(description = "원본 기록 시각. 접수·결과 게시 시각과 다름")
    val createdAt: Instant,
    @field:JsonProperty("fixed_at")
    @field:Schema(description = "분석 배치 고정 시각")
    val fixedAt: Instant,
    @field:JsonProperty("active")
    @field:Schema(description = "현재 활성 분석 여부")
    val active: Boolean,
    @field:JsonProperty("contract")
    @field:Schema(description = "선택된 분석 경로 종류. 응답 계약의 버전과 별개")
    val contract: String,
) {
    companion object {
        fun from(value: OperatorAnalysis) =
            OperatorAnalysisResponse(
                value.id,
                value.status,
                value.createdAt,
                value.fixedAt,
                value.active,
                value.contract,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorQuestionResponse(
    @field:JsonProperty("prompt")
    @field:Schema(description = "연락처를 가린 실제 질문")
    val prompt: String,
    @field:JsonProperty("excerpt")
    @field:Schema(description = "연락처를 가린 질문 근거", nullable = true)
    val excerpt: String?,
    @field:JsonProperty("answer")
    @field:Schema(description = "실제 연결된 답변. 없으면 null", nullable = true)
    val answer: String?,
    @field:JsonProperty("answered_at")
    @field:Schema(description = "기록된 답변 시각", nullable = true)
    val answeredAt: Instant?,
    @field:JsonProperty("consumed")
    @field:Schema(description = "선택한 입력에 답변이 포함되는지 여부")
    val consumed: Boolean,
    @field:JsonProperty("choices")
    @field:Schema(description = "연락처를 가린 저장된 선택지")
    val choices: List<String>,
    @field:JsonProperty("source_raw_text")
    @field:Schema(description = "연락처를 가린 질문의 실제 원문", nullable = true)
    val sourceRawText: String?,
) {
    companion object {
        fun from(value: OperatorQuestion) =
            OperatorQuestionResponse(
                value.prompt,
                value.excerpt,
                value.answer,
                value.answeredAt,
                value.consumed,
                value.choices,
                value.sourceRawText,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorPrecheckAttemptResponse(
    @field:JsonProperty("generation")
    @field:Schema(description = "사전검사 시도 세대")
    val generation: Int,
    @field:JsonProperty("started_at")
    @field:Schema(description = "기록된 실행 시작 시각", nullable = true)
    val startedAt: Instant?,
    @field:JsonProperty("finished_at")
    @field:Schema(description = "기록된 실행 종료 시각", nullable = true)
    val finishedAt: Instant?,
    @field:JsonProperty("failure_code")
    @field:Schema(description = "기록된 실패 분류. null은 실패 여부를 확정하지 않음", nullable = true)
    val failureCode: String?,
    @field:JsonProperty("classification")
    @field:Schema(description = "실제 저장된 첫 분류 결과. 과거 미기록이면 null", nullable = true)
    val classification: OperatorClassificationResponse?,
) {
    companion object {
        fun from(value: OperatorPrecheckAttempt) =
            OperatorPrecheckAttemptResponse(
                value.generation,
                value.startedAt,
                value.finishedAt,
                value.failureCode,
                value.classification?.let(OperatorClassificationResponse::from),
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorInputCheckResponse(
    @field:JsonProperty("raw_text")
    @field:Schema(description = "연락처를 가린 원문")
    val rawText: String,
    @field:JsonProperty("created_at")
    @field:Schema(description = "원본 기록 시각. 접수·결과 게시 시각과 다름")
    val createdAt: Instant,
    @field:JsonProperty("reference_at")
    @field:Schema(description = "해석 기준 시각")
    val referenceAt: Instant,
    @field:JsonProperty("current_status")
    @field:Schema(description = "현재 사전검사 상태. 과거 Jev 판정이 아님")
    val currentStatus: String,
    @field:JsonProperty("question")
    @field:Schema(description = "실제 연결된 보완 질문과 답변", nullable = true)
    val question: OperatorQuestionResponse?,
    @field:JsonProperty("attempts")
    @field:Schema(description = "기록된 사전검사 시도. 최대 8건")
    val attempts: List<OperatorPrecheckAttemptResponse>,
    @field:JsonProperty("attempts_truncated")
    @field:Schema(description = "추가 시도 생략 여부")
    val attemptsTruncated: Boolean,
) {
    companion object {
        fun from(value: OperatorInputCheck) =
            OperatorInputCheckResponse(
                value.rawText,
                value.createdAt,
                value.referenceAt,
                value.currentStatus,
                value.question?.let(OperatorQuestionResponse::from),
                value.attempts.map(OperatorPrecheckAttemptResponse::from),
                value.attemptsTruncated,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorInputResponse(
    @field:JsonProperty("alias")
    @field:Schema(description = "방 안에서 안정적인 참가자 표시 별칭")
    val alias: String,
    @field:JsonProperty("display_name")
    @field:Schema(description = "첫 글자만 남긴 방의 참가자 이름")
    val displayName: String,
    @field:JsonProperty("revision")
    @field:Schema(description = "선택한 입력 리비전. 미접수이면 null", nullable = true)
    val revision: Int?,
    @field:JsonProperty("raw_text")
    @field:Schema(description = "연락처를 가린 원문", nullable = true)
    val rawText: String?,
    @field:JsonProperty("created_at")
    @field:Schema(description = "원본 기록 시각. 접수·결과 게시 시각과 다름", nullable = true)
    val createdAt: Instant?,
    @field:JsonProperty("reference_at")
    @field:Schema(description = "해석 기준 시각", nullable = true)
    val referenceAt: Instant?,
    @field:JsonProperty("interpretation")
    @field:Schema(description = "저장된 입력 해석 상태. Jev 최초 판정과 별개", nullable = true)
    val interpretation: String?,
    @field:JsonProperty("unresolved_reason")
    @field:Schema(description = "저장된 미해결 사유", nullable = true)
    val unresolvedReason: String?,
    @field:JsonProperty("supplement")
    @field:Schema(description = "고정 입력에 포함된 실제 보완 답변", nullable = true)
    val supplement: OperatorQuestionResponse?,
    @field:JsonProperty("checks")
    @field:Schema(description = "정확한 소유권으로 연결한 사전검사 계보. 최대 8건")
    val checks: List<OperatorInputCheckResponse>,
    @field:JsonProperty("checks_truncated")
    @field:Schema(description = "계보 깊이 제한·순환으로 생략 여부")
    val checksTruncated: Boolean,
    @field:JsonProperty("legacy_questions")
    @field:Schema(description = "명시적 입력 버전 계보로 연결된 이전 방식 질문·답변")
    val legacyQuestions: List<OperatorQuestionResponse>,
    @field:JsonProperty("stored_interpretation")
    @field:Schema(description = "엄격하게 복원한 저장 선언. 적용·가용성 재계산 결과가 아님", nullable = true)
    val storedInterpretation: OperatorStoredInterpretationResponse?,
) {
    companion object {
        fun from(value: OperatorInput) =
            OperatorInputResponse(
                value.alias,
                value.displayName,
                value.revision,
                value.rawText,
                value.createdAt,
                value.referenceAt,
                value.interpretation,
                value.unresolvedReason,
                value.supplement?.let(OperatorQuestionResponse::from),
                value.checks.map(OperatorInputCheckResponse::from),
                value.checksTruncated,
                value.legacyQuestions.map(OperatorQuestionResponse::from),
                value.storedInterpretation?.let(OperatorStoredInterpretationResponse::from),
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorTimelineEventResponse(
    @field:JsonProperty("kind")
    @field:Schema(description = "기록 단계 종류")
    val kind: String,
    @field:JsonProperty("at")
    @field:Schema(description = "실제 기록된 단계 시각. 없으면 null", nullable = true)
    val at: Instant?,
    @field:JsonProperty("status")
    @field:Schema(description = "기록된 상태")
    val status: String,
) {
    companion object {
        fun from(value: OperatorTimelineEvent) =
            OperatorTimelineEventResponse(
                value.kind,
                value.at,
                value.status,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorResultResponse(
    @field:JsonProperty("start_at")
    @field:Schema(description = "추천 시작 시각 포함")
    val startAt: Instant,
    @field:JsonProperty("end_at")
    @field:Schema(description = "추천 종료 시각 제외")
    val endAt: Instant,
    @field:JsonProperty("meeting_mode")
    @field:Schema(description = "추천 모임 방식")
    val meetingMode: String,
    @field:JsonProperty("place_name")
    @field:Schema(description = "연락처를 가린 장소 설명", nullable = true)
    val placeName: String?,
    @field:JsonProperty("place_unverified")
    @field:Schema(description = "장소 조건 미검증 여부")
    val placeUnverified: Boolean,
    @field:JsonProperty("available_aliases")
    @field:Schema(description = "저장된 추천에 포함된 참가자 별칭. 나머지 참가자의 불가를 뜻하지 않음")
    val availableAliases: List<String>,
) {
    companion object {
        fun from(value: OperatorResult) =
            OperatorResultResponse(
                value.startAt,
                value.endAt,
                value.meetingMode,
                value.placeName,
                value.placeUnverified,
                value.availableAliases,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorRoomDiagnosticsResponse(
    @field:JsonProperty("room_id")
    @field:Schema(description = "대상 모임 참조")
    val roomId: UUID,
    @field:JsonProperty("scope")
    @field:Schema(description = "CURRENT_COLLECTION 또는 FROZEN_ANALYSIS")
    val scope: String,
    @field:JsonProperty("analysis")
    @field:Schema(description = "선택한 고정 분석. 현재 입력 조회이면 null", nullable = true)
    val analysis: OperatorAnalysisResponse?,
    @field:JsonProperty("inputs")
    @field:Schema(description = "입력과 보완 이력. 최대 50명")
    val inputs: List<OperatorInputResponse>,
    @field:JsonProperty("inputs_truncated")
    @field:Schema(description = "추가 참가자 생략 여부")
    val inputsTruncated: Boolean,
    @field:JsonProperty("invocations")
    @field:Schema(description = "기록된 전체 AI 실행과 시도. 최대 20건")
    val invocations: List<OperatorInvocationDetailResponse>,
    @field:JsonProperty("invocations_truncated")
    @field:Schema(description = "추가 AI 실행 생략 여부")
    val invocationsTruncated: Boolean,
    @field:JsonProperty("timeline")
    @field:Schema(description = "기록된 배치·큐·실패 단계. 최대 100건")
    val timeline: List<OperatorTimelineEventResponse>,
    @field:JsonProperty("timeline_truncated")
    @field:Schema(description = "추가 단계 생략 여부")
    val timelineTruncated: Boolean,
    @field:JsonProperty("results")
    @field:Schema(description = "저장된 추천. 최대 20건")
    val results: List<OperatorResultResponse>,
    @field:JsonProperty("results_truncated")
    @field:Schema(description = "추가 추천 생략 여부")
    val resultsTruncated: Boolean,
) {
    companion object {
        fun from(value: OperatorRoomDiagnostics) =
            OperatorRoomDiagnosticsResponse(
                value.roomId,
                value.scope,
                value.analysis?.let(OperatorAnalysisResponse::from),
                value.inputs.map(OperatorInputResponse::from),
                value.inputsTruncated,
                value.invocations.map {
                    OperatorInvocationDetailResponse(
                        it.roomId,
                        InvocationSummaryResponse.from(it.invocation),
                        it.attempts.map(AttemptSummaryResponse::from),
                        it.usageByProvider.map(ProviderUsageResponse::from),
                    )
                },
                value.invocationsTruncated,
                value.timeline.map(OperatorTimelineEventResponse::from),
                value.timelineTruncated,
                value.results.map(OperatorResultResponse::from),
                value.resultsTruncated,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorStoredConditionResponse(
    @field:JsonProperty("effect")
    @field:Schema(description = "저장된 가능·불가·선호 효과")
    val effect: String,
    @field:JsonProperty("interpretation")
    @field:Schema(description = "저장된 입력 해석 상태. Jev 최초 판정과 별개")
    val interpretation: String,
    @field:JsonProperty("temporal_resolution")
    @field:Schema(description = "저장된 시간 정밀도")
    val temporalResolution: String,
    @field:JsonProperty("dimension")
    @field:Schema(description = "시간 또는 장소 조건 차원", nullable = true)
    val dimension: String?,
    @field:JsonProperty("start")
    @field:Schema(description = "저장된 로컬 시작 시각", nullable = true)
    val start: String?,
    @field:JsonProperty("end")
    @field:Schema(description = "저장된 로컬 종료 시각", nullable = true)
    val end: String?,
) {
    companion object {
        fun from(value: OperatorStoredCondition) =
            OperatorStoredConditionResponse(
                value.effect,
                value.interpretation,
                value.temporalResolution,
                value.dimension,
                value.start,
                value.end,
            )
    }
}

@Schema(description = "운영자 전용 저장 기록 투영")
data class OperatorStoredInterpretationResponse(
    @field:JsonProperty("coverage")
    @field:Schema(description = "STORED_DECLARATION. 전체 원문 적용을 뜻하지 않음")
    val coverage: String,
    @field:JsonProperty("conditions")
    @field:Schema(description = "conditions")
    val conditions: List<OperatorStoredConditionResponse>,
    @field:JsonProperty("problems")
    @field:Schema(description = "저장된 조건 보류·프로그램 검증 사유")
    val problems: List<String>,
    @field:JsonProperty("truncated")
    @field:Schema(description = "추가 조건 또는 사유 생략 여부")
    val truncated: Boolean,
) {
    companion object {
        fun from(value: OperatorStoredInterpretation) =
            OperatorStoredInterpretationResponse(
                value.coverage,
                value.conditions.map(OperatorStoredConditionResponse::from),
                value.problems,
                value.truncated,
            )
    }
}

@Schema(description = "저장된 첫 분류와 실제 서버 분기. 없던 판정을 복원하지 않음")
data class OperatorClassificationResponse(
    @field:JsonProperty("assessment")
    @field:Schema(description = "분류 어댑터의 정규화된 최초 판정")
    val assessment: String,
    @field:JsonProperty("reason")
    @field:Schema(description = "최초 판정의 기록된 사유", nullable = true)
    val reason: String?,
    @field:JsonProperty("confidence")
    @field:Schema(description = "모델 점수. 정확도 보장 또는 서버 최종 판정이 아님", nullable = true)
    val confidence: Double?,
    @field:JsonProperty("semantic_assessment")
    @field:Schema(description = "공급자의 최초 의미 선택. 정규화 판정과 다를 수 있음", nullable = true)
    val semanticAssessment: String?,
    @field:JsonProperty("effective_assessment")
    @field:Schema(description = "실제 서버 분기에 사용한 판정. 분기 전 실패하면 null", nullable = true)
    val effectiveAssessment: String?,
    @field:JsonProperty("provider")
    @field:Schema(description = "실제 최초 분류 공급자")
    val provider: String,
    @field:JsonProperty("model")
    @field:Schema(description = "실제 최초 분류 모델")
    val model: String,
    @field:JsonProperty("probabilities")
    @field:Schema(description = "공급자가 반환한 라벨별 점수. 최대 3개")
    val probabilities: Map<String, Double>,
) {
    companion object {
        fun from(value: com.meetme.server.precheck.application.port.output.PrecheckClassificationSnapshot) =
            OperatorClassificationResponse(
                value.assessment.name,
                value.reason?.name,
                value.confidence,
                value.semanticAssessment?.name,
                value.effectiveAssessment?.name,
                value.provider,
                value.model,
                value.probabilities.mapKeys { it.key.name },
            )
    }
}

@Schema(description = "분석 이력 페이지. 시도가 없는 실행도 포함")
data class OperatorAnalysisPageResponse(
    @field:Schema(description = "분석 목록")
    val items: List<OperatorAnalysisResponse>,
    @field:JsonProperty("next_cursor")
    @field:Schema(description = "다음 페이지 커서", nullable = true)
    val nextCursor: String?,
)
