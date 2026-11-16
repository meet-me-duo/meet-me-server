package com.meetme.server.shared.adapter.input.web.operations

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.input.OperatorReanalysisUseCase
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as ApiRequestBody

@Schema(
    description = "운영자가 현재 분석을 명시해 재분석을 요청하는 값",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
)
data class OperatorReanalysisRequestBody(
    @field:JsonProperty("request_id")
    @field:Schema(description = "접수 확인과 재전송에 동일하게 사용하는 요청 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val requestId: UUID,
    @field:JsonProperty("source_analysis_id")
    @field:Schema(description = "현재 활성 분석 UUID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val sourceAnalysisId: UUID,
    @field:JsonProperty("expected_state_version")
    @field:Schema(description = "운영자 요약에서 읽은 모임 상태 버전", minimum = "0", format = "int64", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedStateVersion: Long,
)

@Schema(description = "비동기 재분석의 불변 접수 기록. 실행의 현재 상태가 아님")
data class OperatorReanalysisReceiptResponse(
    @field:JsonProperty(
        "request_id",
    ) @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "접수된 요청 UUID", format = "uuid") val requestId: UUID,
    @field:JsonProperty(
        "source_analysis_id",
    ) @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "이력을 보존한 원본 분석 UUID", format = "uuid") val sourceAnalysisId:
        UUID,
    @field:JsonProperty(
        "analysis_id",
    ) @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "새 활성 분석 UUID", format = "uuid") val analysisId: UUID,
    @field:JsonProperty(
        "accepted_state_version",
    ) @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "접수 시 새 모임 상태 버전",
        format = "int64",
    ) val acceptedStateVersion: Long,
    @field:JsonProperty(
        "accepted_at",
    ) @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "접수 시각", format = "date-time") val acceptedAt: Instant,
    @field:JsonProperty(
        "analysis_contract_version",
    ) @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "새 실행에 동결된 분석 생성 계약") val analysisContractVersion: String,
) {
    companion object {
        fun from(value: OperatorReanalysisReceipt) =
            OperatorReanalysisReceiptResponse(
                value.requestId,
                value.sourceAnalysisId,
                value.analysisId,
                value.acceptedStateVersion,
                value.acceptedAt,
                value.analysisContractVersion,
            )
    }
}

@RestController
class OperatorReanalysisController(
    private val service: OperatorReanalysisUseCase,
    private val mapper: ObjectMapper,
    private val origins: AnonymousAccessProperties,
) {
    @PostMapping("/api/operations/rooms/{roomId}/analyses")
    @Operation(summary = "운영자 권한으로 현재 모임을 최신 분석 로직으로 다시 실행")
    @ApiRequestBody(
        required = true,
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = OperatorReanalysisRequestBody::class),
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "새 분석 또는 동일 요청의 최초 접수 기록",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = OperatorReanalysisReceiptResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "요청 형식 오류",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "회원 인증 필요",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "운영자 권한 또는 Origin 불일치",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "모임 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "대상/상태 충돌 또는 지원하지 않는 입력",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "429",
            description = "운영 요청 제한. Retry-After 제공",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증/감사/요청 제한/저장 처리 불가",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = OperatorProblemResponse::class),
                ),
            ],
        ),
    )
    fun reanalyze(
        @PathVariable roomId: String,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorReanalysisReceiptResponse> {
        request.setAttribute(OperatorNoStoreFilter.AUDIT_HANDLED_ATTRIBUTE, true)
        val receipt = service.request(roomId) { decode(request) }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(OperatorReanalysisReceiptResponse.from(receipt))
    }

    private fun decode(request: HttpServletRequest): OperatorReanalysisRequest {
        val values = request.getHeaders("Origin").toList()
        if (values.size != 1 || values.single() !in origins.allowedOrigins) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_FORBIDDEN)
        }
        try {
            if (request.contentType == null ||
                MediaType.parseMediaType(request.contentType).type != "application" ||
                MediaType.parseMediaType(request.contentType).subtype != "json"
            ) {
                invalid()
            }
            if (request.contentLengthLong > 8192) invalid()
            val bytes = request.inputStream.readNBytes(8193)
            if (bytes.size > 8192) invalid()
            val root =
                mapper
                    .readerFor(Map::class.java)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue<Map<String, Any?>>(bytes)
            if (root.keys != setOf("request_id", "source_analysis_id", "expected_state_version")) invalid()
            val requestId = OperationsCursorCodec.uuidOrNull(root["request_id"] as? String) ?: invalid()
            val sourceId = OperationsCursorCodec.uuidOrNull(root["source_analysis_id"] as? String) ?: invalid()
            val version =
                when (val value = root["expected_state_version"]) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> invalid()
                }
            if (version < 0) invalid()
            return OperatorReanalysisRequest(requestId, sourceId, version)
        } catch (failure: OperatorQueryException) {
            throw failure
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
}
