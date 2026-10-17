package com.meetme.server.submission.adapter.input.web

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.shared.adapter.input.web.ApiProblemSchema
import com.meetme.server.shared.adapter.input.web.GuestCookie
import com.meetme.server.submission.application.port.input.AnswerClarificationCommand
import com.meetme.server.submission.application.port.input.ClarificationChoice
import com.meetme.server.submission.application.port.input.ClarificationQuestionView
import com.meetme.server.submission.application.port.input.ClarificationUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.Locale
import java.util.UUID

@Schema(description = "본인 모호성 질문의 명시적 답변. 저장만 수행하며 HOST 재분석이 별도로 필요")
data class AnswerClarificationRequest(
    @field:JsonProperty("request_id")
    @field:Schema(description = "같은 질문의 동일 답변 재시도에 유지할 UUID", requiredMode = Schema.RequiredMode.REQUIRED) val requestId: UUID,
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "현재 OPEN 수정 라운드 UUID", requiredMode = Schema.RequiredMode.REQUIRED) val revisionRoundId: UUID,
    @field:JsonProperty("expected_revision")
    @field:Schema(
        description = "질문의 source_revision. 최신 값과 다르면 409",
        type = "integer",
        format = "int32",
        requiredMode = Schema.RequiredMode.REQUIRED,
    ) val expectedRevision: JsonNode,
    @field:Schema(
        description = "명시적 선택",
        allowableValues = ["A", "B", "DIRECT", "CANCEL"],
        requiredMode = Schema.RequiredMode.REQUIRED,
    ) val choice: String,
    @field:JsonProperty("direct_text")
    @field:Schema(
        description = "DIRECT에서만 전체 대체 원문. ECMAScript trim 후 1~500 Unicode 코드포인트",
        type = "string",
        nullable = true,
    ) val directText: JsonNode? = null,
) {
    fun revision(): Int {
        require(expectedRevision.isInt)
        return expectedRevision.intValue()
    }

    fun selection(): ClarificationChoice =
        ClarificationChoice.entries.find { it.name == choice } ?: throw IllegalArgumentException("Invalid choice")

    fun replacement(): String? {
        if (selection() != ClarificationChoice.DIRECT) {
            require(directText == null)
            return null
        }
        require(directText?.isString == true)
        return directText.stringValue()
    }

    @JsonAnySetter
    fun rejectUnknown(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Nothing = throw IllegalArgumentException("Unsupported clarification field")
}

@Schema(description = "현재 본인 제출에 속한 미응답 질문. 타인의 질문과 원문은 포함하지 않음")
data class ClarificationQuestionResponse(
    @field:JsonProperty("question_id")
    @field:Schema(description = "원본 버전에 고정된 질문 UUID") val questionId: UUID,
    @field:JsonProperty("source_revision")
    @field:Schema(description = "질문을 만든 불변 원문 revision") val sourceRevision: Int,
    @field:Schema(description = "본인 원문에 존재하는 모호한 구절, 최대200 코드포인트") val excerpt: String,
    @field:Schema(description = "최대160 코드포인트의 질문") val question: String,
    @field:JsonProperty("option_a")
    @field:Schema(description = "A 선택 시 저장할 전체 수정본, 최대500 코드포인트") val optionA: String,
    @field:JsonProperty("option_b")
    @field:Schema(description = "B 선택 시 저장할 전체 수정본, 최대500 코드포인트") val optionB: String,
    @field:Schema(description = "현재 OPEN 라운드에서 본인이 답변할 수 있는지") val editable: Boolean,
) {
    companion object {
        fun from(v: ClarificationQuestionView) =
            ClarificationQuestionResponse(v.questionId, v.sourceRevision, v.excerpt, v.question, v.optionA, v.optionB, v.editable)
    }
}

@Schema(description = "본인의 현재 미응답 질문 목록. 명확한 입력에는 빈 배열")
data class ClarificationListResponse(
    @field:Schema(description = "질문 최대1개") val questions: List<ClarificationQuestionResponse>,
    @field:JsonProperty("state_version")
    @field:Schema(description = "현재 방 snapshot 버전") val stateVersion: Long,
)

@Schema(description = "영속 답변 결과. 동일 요청의 재시도는 최초 응답을 재생")
data class ClarificationAnswerResponse(
    @field:Schema(description = "새 revision 저장 또는 질문 취소", allowableValues = ["ANSWERED", "CANCELLED"]) val status: String,
    @field:Schema(description = "저장한 새 revision. CANCELLED이면 null", nullable = true) val submission: SubmissionResponse?,
)

@RestController
@RequestMapping("/api/rooms/{inviteCode}/submission/clarifications")
@ApiResponses(
    ApiResponse(
        responseCode = "400",
        description = "입력 형식·선택·직접 원문 검증 실패",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "401",
        description = "본인 세션 누락·무효",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "403",
        description = "방 참여권한 또는 정확 Origin 없음",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
    ApiResponse(responseCode = "404", description = "방 없음", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(
        responseCode = "409",
        description = "REVISION_CONFLICT 또는 배치 원문 합계 제한 초과",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "429",
        description = "답변 요청 제한",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "요청 제한 저장소 사용 불가",
        content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
    ),
)
class ClarificationController(
    private val useCase: ClarificationUseCase,
) {
    @GetMapping
    @Operation(summary = "본인의 모호한 조건 보완 질문 조회")
    @ApiResponse(responseCode = "200", description = "본인에게만 공개하는 현재 미응답 질문")
    fun get(
        @PathVariable inviteCode: String,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
    ): ClarificationListResponse {
        val view = useCase.get(inviteCode, credential)
        return ClarificationListResponse(view.questions.map(ClarificationQuestionResponse::from), view.stateVersion)
    }

    @PostMapping("/{questionId}/answer")
    @Operation(summary = "본인 보완 답변을 새 revision으로 저장하거나 취소. 자동 분석 없음")
    @ApiResponse(responseCode = "200", description = "새 revision, 취소 또는 동일 작업 재생")
    fun answer(
        @PathVariable inviteCode: String,
        @PathVariable questionId: UUID,
        @RequestBody request: AnswerClarificationRequest,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): ClarificationAnswerResponse {
        val view =
            useCase.answer(
                AnswerClarificationCommand(
                    inviteCode,
                    credential,
                    questionId,
                    request.requestId,
                    request.revisionRoundId,
                    request.revision(),
                    request.selection(),
                    request.replacement(),
                    locale,
                ),
            )
        return ClarificationAnswerResponse(view.status, view.submission?.let(SubmissionResponse::from))
    }
}
