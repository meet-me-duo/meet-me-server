package com.meetme.server.precheck.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.precheck.application.port.input.CreatePrecheckCommand
import com.meetme.server.precheck.application.port.input.PreSubmissionCheckUseCase
import com.meetme.server.precheck.application.port.input.PrecheckAcceptedView
import com.meetme.server.precheck.application.port.input.PrecheckMutationCommand
import com.meetme.server.precheck.application.port.input.PrecheckSnapshot
import com.meetme.server.precheck.application.port.input.PrecheckTextView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.Locale
import java.util.UUID

@Schema(
    description = "원문 보존과 비동기 확인 요청",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = ["request_id", "expected_revision", "raw_text"],
)
data class CreatePrecheckRequest(
    @field:JsonProperty("request_id") @field:Schema(description = "멱등 UUID") val requestId: UUID,
    @field:JsonProperty("expected_revision") @field:Schema(description = "현재 candidate revision, 최초 0") val expectedRevision: Int,
    @field:JsonProperty("raw_text")
    @field:Schema(
        description = "trim 후 1~500 Unicode 코드포인트",
        type = "string",
        implementation = String::class,
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val rawText: JsonNode? = null,
) {
    fun naturalText(): String? = strictText(rawText)
}

@Schema(
    description = "검사 상태 CAS 명령, 답변은 choice 또는 direct_text 중 하나",
    requiredProperties = ["request_id", "expected_revision", "expected_check_version"],
)
data class PrecheckMutationRequest(
    @field:JsonProperty("request_id") @field:Schema(description = "멱등 UUID") val requestId: UUID,
    @field:JsonProperty("expected_revision") @field:Schema(description = "현재 candidate revision") val expectedRevision: Int,
    @field:JsonProperty("expected_check_version") @field:Schema(description = "읽은 check version") val expectedCheckVersion: Long,
    @field:JsonProperty("question_id") @field:Schema(description = "답변할 질문 UUID", nullable = true) val questionId: UUID? = null,
    @field:JsonProperty("choice_id") @field:Schema(description = "명시 선택 ID", nullable = true) val choiceId: String? = null,
    @field:JsonProperty("direct_text")
    @field:Schema(description = "직접 수정한 전체 원문", nullable = true, type = "string", implementation = String::class)
    val directText: JsonNode? = null,
)

@RestController
@RequestMapping("/api/rooms/{inviteCode}/submission/checks")
@ApiResponses(
    ApiResponse(
        responseCode = "400",
        description = "입력 계약 위반",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
    ApiResponse(
        responseCode = "401",
        description = "인증 누락 또는 무효 Bearer",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
    ApiResponse(
        responseCode = "403",
        description = "본인 소유권 또는 검증된 proof 없음",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
    ApiResponse(
        responseCode = "404",
        description = "방 또는 본인 검사 없음",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
    ApiResponse(
        responseCode = "429",
        description = "기존 제출 요청 제한 초과",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
    ApiResponse(
        responseCode = "409",
        description = "revision·멱등 키·protocol·마감 충돌",
        content = [
            Content(
                mediaType = "application/problem+json",
                schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class),
            ),
        ],
    ),
)
class PrecheckController(
    private val useCase: PreSubmissionCheckUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "본인 원문 보존 후 모호성 확인 접수")
    @ApiResponse(responseCode = "202", description = "저장 결과와 현재 snapshot. 확인 대기는 제출 완료 아님")
    fun create(
        @PathVariable inviteCode: String,
        @RequestBody request: CreatePrecheckRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): PrecheckResponse =
        useCase
            .create(
                CreatePrecheckCommand(inviteCode, credential, request.requestId, request.expectedRevision, request.naturalText(), locale),
            ).wire()

    @GetMapping("/current")
    @Operation(summary = "본인 현재 candidate·질문·완료본 조회")
    @ApiResponse(responseCode = "200", description = "private 현재 snapshot, 최초 저장 전 candidate null")
    fun current(
        @PathVariable inviteCode: String,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): PrecheckResponse = useCase.current(inviteCode, credential).wire()

    @PostMapping("/{checkId}/answer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "선택 또는 직접 수정의 명시 확정 후 재검사")
    @ApiResponse(responseCode = "202", description = "파생 원문 저장과 재검사 접수")
    fun answer(
        @PathVariable inviteCode: String,
        @PathVariable checkId: UUID,
        @RequestBody request: PrecheckMutationRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): PrecheckResponse = useCase.answer(request.command(inviteCode, checkId, credential)).wire()

    @PostMapping("/{checkId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "허용된 실패 검사의 명시 재시도")
    @ApiResponse(responseCode = "202", description = "새 execution 접수, 최초 outcome과 최신 snapshot 분리")
    fun retry(
        @PathVariable inviteCode: String,
        @PathVariable checkId: UUID,
        @RequestBody request: PrecheckMutationRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): PrecheckResponse = useCase.retry(request.command(inviteCode, checkId, credential)).wire()

    @PostMapping("/{checkId}/cancel")
    @Operation(summary = "본인 진행 중 검사 취소, 원문과 이전 완료본 보존")
    @ApiResponse(responseCode = "200", description = "취소 결과와 현재 snapshot")
    fun cancel(
        @PathVariable inviteCode: String,
        @PathVariable checkId: UUID,
        @RequestBody request: PrecheckMutationRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): PrecheckResponse = useCase.cancel(request.command(inviteCode, checkId, credential)).wire()
}

private fun PrecheckMutationRequest.command(
    inviteCode: String,
    checkId: UUID,
    credential: String?,
) = PrecheckMutationCommand(
    inviteCode,
    credential,
    checkId,
    requestId,
    expectedRevision,
    expectedCheckVersion,
    questionId,
    choiceId,
    strictText(directText),
)

@Schema(
    description = "본인 원문과 완료본을 분리한 비공개 snapshot",
    requiredProperties = ["candidate", "accepted", "check", "room", "submission_complete", "applied_to_result", "operation_outcome"],
)
data class PrecheckResponse(
    @field:Schema(description = "현재 저장 원문. 저장 전 null", nullable = true) val candidate: PrecheckTextResponse?,
    @field:Schema(description = "마지막 확인 완료본. 최초 확인 전 null", nullable = true) val accepted: PrecheckAcceptedResponse?,
    @field:Schema(description = "현재 원문의 검사", nullable = true) val check: PrecheckStateResponse?,
    @field:Schema(description = "현재 방 상태") val room: PrecheckRoomResponse,
    @field:JsonProperty(
        "submission_complete",
    ) @field:Schema(description = "현재 candidate가 CLEAR로 수용됨. 이전 완료본만 있으면 false") val submissionComplete: Boolean,
    @field:JsonProperty("applied_to_result") @field:Schema(description = "마감된 활성 batch가 accepted를 포함함. 매칭 성공 보장은 아님") val appliedToResult:
        Boolean,
    @field:JsonProperty(
        "operation_outcome",
    ) @field:Schema(
        description = "해당 멱등 명령의 최초 outcome. 현재 상태는 상위 snapshot 사용",
        oneOf = [PrecheckOutcomeResponse::class],
        nullable = true,
    ) val operationOutcome: PrecheckOutcomeResponse?,
)

@Schema(
    description = "마지막 수용본과 실제 수용 경로",
    requiredProperties = ["revision", "raw_text", "created_at", "interpretation_reference_at", "confirmation_source"],
)
data class PrecheckAcceptedResponse(
    @field:Schema(description = "수용된 제출 revision") val revision: Int,
    @field:JsonProperty("raw_text") @field:Schema(description = "수용한 전체 원문") val rawText: String,
    @field:JsonProperty("created_at") @field:Schema(description = "실제 원문 저장 시각") val createdAt: java.time.Instant,
    @field:JsonProperty("interpretation_reference_at") @field:Schema(description = "최종 파싱에 전달되는 의미 기준 시각") val referenceAt:
        java.time.Instant,
    @field:JsonProperty(
        "confirmation_source",
    ) @field:Schema(
        description = "실제 version 연결로 판정한 수용 경로",
        allowableValues = ["PRECHECK", "LEGACY_CORRECTION"],
        requiredMode = Schema.RequiredMode.REQUIRED,
    ) val confirmationSource: String,
)

private fun PrecheckAcceptedView.wire() = PrecheckAcceptedResponse(revision, rawText, createdAt, referenceAt, confirmationSource)

@Schema(description = "저장 원문과 의미 기준 시각", requiredProperties = ["revision", "raw_text", "created_at", "interpretation_reference_at"])
data class PrecheckTextResponse(
    @field:Schema(description = "candidate 또는 accepted의 독립 revision") val revision: Int,
    @field:JsonProperty("raw_text") @field:Schema(description = "보존된 전체 원문") val rawText: String,
    @field:JsonProperty("created_at") @field:Schema(description = "실제 저장 시각") val createdAt: java.time.Instant,
    @field:JsonProperty("interpretation_reference_at") @field:Schema(description = "A/B 선택은 source 기준 시각을 상속") val referenceAt:
        java.time.Instant,
)

@Schema(
    description = "비동기 검사 상태",
    requiredProperties = ["id", "status", "version", "question", "retry_allowed", "failure_code", "edit_guidance"],
)
data class PrecheckStateResponse(
    @field:Schema(description = "검사 UUID") val id: UUID,
    @field:Schema(description = "검사 상태. CLEAR만 현재 원문의 제출 완료") val status: com.meetme.server.precheck.domain.PrecheckStatus,
    @field:Schema(description = "명령 CAS에 필요한 단조 증가 검사 버전") val version: Long,
    @field:Schema(description = "본인 선택 질문", nullable = true) val question: PrecheckQuestionResponse?,
    @field:JsonProperty("retry_allowed") @field:Schema(description = "현재 명시 재시도가 허용되는지") val retryAllowed: Boolean,
    @field:JsonProperty("failure_code") @field:Schema(description = "기술 실패 코드, 미확인은 null", nullable = true) val failureCode: String?,
    @field:JsonProperty("edit_guidance")
    @field:Schema(
        description = "NEEDS_EDIT에서 본인에게만 제공하는 직접 수정 안내. 일반 텍스트로 표시하며 자동 반영하지 않음. 다른 상태는 null",
        nullable = true,
        maxLength = 160,
    )
    val editGuidance: String? = null,
)

@Schema(description = "원문을 자동 변경하지 않는 선택 질문", requiredProperties = ["id", "text", "source_excerpt", "options"])
data class PrecheckQuestionResponse(
    @field:Schema(description = "질문 UUID") val id: UUID,
    @field:Schema(description = "질문 본문, 최대 160 코드포인트") val text: String,
    @field:JsonProperty("source_excerpt") @field:Schema(description = "원문에 실제 존재하는 모호 구절") val sourceExcerpt: String,
    @field:Schema(description = "명시 확정 가능한 두 전체 수정안") val options: List<PrecheckOptionResponse>,
)

@Schema(description = "참여자가 확인 후 선택하는 전체 원문 제안", requiredProperties = ["id", "text", "proposed_text"])
data class PrecheckOptionResponse(
    @field:Schema(description = "서로 다른 선택 식별자") val id: String,
    @field:Schema(description = "선택 표시 문구") val text: String,
    @field:JsonProperty("proposed_text") @field:Schema(description = "선택 시 보존할 전체 원문. 최대 500 코드포인트") val proposedText: String,
)

@Schema(description = "방의 실제 현재 snapshot", requiredProperties = ["collection_status", "state_version", "submission_deadline"])
data class PrecheckRoomResponse(
    @field:JsonProperty("collection_status") @field:Schema(description = "COLLECTING 또는 CLOSED") val collectionStatus: String,
    @field:JsonProperty("state_version") @field:Schema(description = "방의 단조 증가 상태 버전") val stateVersion: Long,
    @field:JsonProperty("submission_deadline") @field:Schema(description = "대기 때문에 연장되지 않는 마감", nullable = true) val submissionDeadline:
        java.time.Instant?,
)

@Schema(description = "재생해도 변경되지 않는 최초 명령 outcome", requiredProperties = ["request_id", "candidate_revision", "check_id", "status"])
data class PrecheckOutcomeResponse(
    @field:JsonProperty("request_id") @field:Schema(description = "멱등 UUID") val requestId: UUID,
    @field:JsonProperty("candidate_revision") @field:Schema(description = "최초 명령이 저장한 candidate revision") val candidateRevision: Int,
    @field:JsonProperty("check_id") @field:Schema(description = "최초 명령 대상 검사 UUID") val checkId: UUID,
    @field:Schema(description = "최초 명령 당시 검사 상태") val status: com.meetme.server.precheck.domain.PrecheckStatus,
)

private fun PrecheckTextView.wire() = PrecheckTextResponse(revision, rawText, createdAt, referenceAt)

private fun PrecheckSnapshot.wire() =
    PrecheckResponse(
        candidate?.wire(),
        accepted?.wire(),
        check?.let { c ->
            PrecheckStateResponse(
                c.id,
                c.status,
                c.version,
                c.question?.takeUnless { c.status == com.meetme.server.precheck.domain.PrecheckStatus.NEEDS_EDIT }?.let { q ->
                    PrecheckQuestionResponse(
                        q.id,
                        q.prompt,
                        q.sourceExcerpt,
                        q.choices.map { PrecheckOptionResponse(it.id, it.label, it.proposedText) },
                    )
                },
                c.retryAllowed,
                c.failureCode,
                if (c.status == com.meetme.server.precheck.domain.PrecheckStatus.NEEDS_EDIT) {
                    c.question?.prompt ?: editGuidanceFallback(c.failureCode)
                } else {
                    null
                },
            )
        },
        PrecheckRoomResponse(room.collectionStatus, room.stateVersion, room.submissionDeadline),
        submissionComplete,
        appliedToResult,
        outcome?.let { PrecheckOutcomeResponse(it.requestId, it.candidateRevision, it.checkId, it.checkStatus) },
    )

private fun editGuidanceFallback(code: String?): String =
    when (code) {
        "SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED" -> "전체 입력 길이 제한을 넘었어요. 이번 입력을 줄인 뒤 다시 제출해 주세요."
        "QUESTION_LIMIT_REACHED" -> "선택 질문 한도에 도달했어요. 가능한 날짜·시간과 예외 조건을 원문에 직접 적어 주세요."
        "RECHECK_REQUIRES_EDIT" -> "선택한 보완문에도 확인할 부분이 남았어요. 가능한 날짜·시간과 예외 조건을 원문에 직접 적어 주세요."
        else -> "구체적인 보완 안내를 확인할 수 없어요. 가능한 날짜·시간과 예외 조건을 원문에 직접 적어 다시 제출해 주세요."
    }

private fun strictText(node: JsonNode?): String? {
    require(node == null || node.isNull || node.isString) { "Invalid submission field type" }
    return node?.takeUnless { it.isNull }?.stringValue()
}
