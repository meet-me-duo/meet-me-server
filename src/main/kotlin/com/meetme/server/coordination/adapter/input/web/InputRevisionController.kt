package com.meetme.server.coordination.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.coordination.application.port.input.AnalyzeRevisionCommand
import com.meetme.server.coordination.application.port.input.InputRevisionUseCase
import com.meetme.server.coordination.application.port.input.ReopenInputCommand
import com.meetme.server.coordination.domain.RevisionAnalysisOutcome
import com.meetme.server.meetingroom.adapter.input.web.RevisionRoundResponse
import com.meetme.server.meetingroom.adapter.input.web.RoomResponse
import com.meetme.server.shared.adapter.input.web.ApiProblemSchema
import com.meetme.server.shared.adapter.input.web.GuestCookie
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Schema(description = "현재 미확정 결과의 조건 수정 라운드를 여는 명령")
data class ReopenInputRequest(
    @field:JsonProperty("request_id")
    @field:Schema(description = "동일 요청 재시도에 유지할 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) val requestId: UUID,
    @field:JsonProperty("source_analysis_id")
    @field:Schema(description = "수정하려는 현재 활성 분석 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) val sourceAnalysisId:
        UUID,
    @field:JsonProperty("expected_generation")
    @field:Min(0)
    @field:Schema(
        description = "읽은 현재 revision_generation",
        minimum = "0",
        requiredMode = Schema.RequiredMode.REQUIRED,
    ) val expectedGeneration: Long,
)

@Schema(description = "저장된 최신 입력을 다시 조율하는 명령. 저장만으로 분석하지 않음")
data class AnalyzeRevisionRequest(
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "현재 OPEN 수정 라운드 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) val revisionRoundId:
        UUID,
    @field:JsonProperty("request_id")
    @field:Schema(description = "동일 요청 재시도에 유지할 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) val requestId: UUID,
    @field:JsonProperty("force_reparse")
    @field:Schema(
        description = "true면 입력 변경이 없어도 비용을 수반하는 새 분석 실행",
        defaultValue = "false",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val forceReparse: Boolean = false,
)

@Schema(description = "수정 라운드 operation 결과와 현재 방 snapshot")
data class ReopenedInputResponse(
    @field:Schema(description = "현재 방 snapshot") val room: RoomResponse,
    @field:Schema(description = "이 요청에 기록된 라운드. 재시도 시 현재 라운드와 다를 수 있음") val round: RevisionRoundResponse,
)

@Schema(description = "다시 조율 operation 결과와 현재 방 snapshot")
data class RevisionAnalysisResponse(
    @field:Schema(description = "QUEUED 새 분석 또는 REUSED 기존 결과") val outcome: RevisionAnalysisOutcome,
    @field:JsonProperty("analysis_id")
    @field:Schema(description = "이 요청이 생성하거나 재사용한 분석 UUID", format = "uuid") val analysisId: UUID,
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "이 요청이 소비한 라운드 UUID", format = "uuid") val revisionRoundId: UUID,
    @field:Schema(description = "현재 방 snapshot. operation 이후 새 라운드가 열렸을 수도 있음") val room: RoomResponse,
)

@RestController
@RequestMapping("/api/rooms/{inviteCode}")
class InputRevisionController(
    private val revisions: InputRevisionUseCase,
) {
    @PostMapping("/reopen")
    @Operation(summary = "주최자가 미확정 NO_MATCH 또는 PARTIAL 결과의 조건 수정 시작")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "새 라운드 또는 동일 요청 재시도"),
        ApiResponse(
            responseCode = "400",
            description = "요청 형식 오류",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "주최자 권한 또는 Origin 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "REVISION_CONFLICT: 상태 또는 요청 precondition 충돌",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "HOST 요청 제한. Retry-After 제공",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "요청 제한 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun reopen(
        @PathVariable inviteCode: String,
        @Valid @RequestBody request: ReopenInputRequest,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
    ): ReopenedInputResponse {
        val result =
            revisions.reopen(
                ReopenInputCommand(inviteCode, credential, request.requestId, request.sourceAnalysisId, request.expectedGeneration),
            )
        return ReopenedInputResponse(RoomResponse.from(result.room), RevisionRoundResponse.from(result.round))
    }

    @PostMapping("/analysis")
    @Operation(summary = "주최자가 현재 라운드의 최신 저장 입력으로 다시 조율")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "변경 없는 입력의 REUSED 결과",
            content = [Content(schema = Schema(implementation = RevisionAnalysisResponse::class))],
        ),
        ApiResponse(
            responseCode = "202",
            description = "새 QUEUED 분석 또는 같은 요청 재시도",
            content = [Content(schema = Schema(implementation = RevisionAnalysisResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "요청 형식 오류",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "주최자 권한 또는 Origin 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "REVISION_CONFLICT 또는 영구 CORRECTION_ANALYSIS_LIMIT_REACHED",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "HOST 요청 제한. Retry-After 제공",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "요청 제한 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun analyze(
        @PathVariable inviteCode: String,
        @Valid @RequestBody request: AnalyzeRevisionRequest,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
    ): ResponseEntity<RevisionAnalysisResponse> {
        val result =
            revisions.analyze(
                AnalyzeRevisionCommand(inviteCode, credential, request.revisionRoundId, request.requestId, request.forceReparse),
            )
        val status = if (result.outcome == RevisionAnalysisOutcome.QUEUED) HttpStatus.ACCEPTED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            RevisionAnalysisResponse(result.outcome, result.analysisId, result.revisionRoundId, RoomResponse.from(result.room)),
        )
    }
}
