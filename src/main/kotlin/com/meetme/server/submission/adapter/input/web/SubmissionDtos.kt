package com.meetme.server.submission.adapter.input.web

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import com.meetme.server.submission.application.port.input.SubmissionView
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant

@Schema(
    description = "현재 익명 참여자의 자연어 제출 또는 수정 요청",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
)
data class SaveSubmissionRequest(
    @field:JsonProperty("raw_text")
    @field:Schema(
        description = "필수 자연어. ECMAScript trim 후 1~500 Unicode 코드포인트이며 내부 공백은 보존",
        implementation = String::class,
        type = "string",
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 1,
        maxLength = 500,
    )
    val rawText: JsonNode? = null,
    @field:JsonProperty("manual_available_times")
    @field:ArraySchema(
        arraySchema =
            Schema(
                description = "종료된 수동 입력의 호환 필드. 생략·빈 배열·null만 허용하며 nonempty 배열은 지원 종료 오류",
                nullable = true,
                deprecated = true,
            ),
        maxItems = 0,
    )
    val manualAvailableTimes: JsonNode? = null,
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "조건 수정 중 필수인 현재 라운드 UUID", nullable = true, format = "uuid")
    val revisionRoundId: java.util.UUID? = null,
    @field:JsonProperty("expected_revision")
    @field:Schema(description = "조건 수정 중 필수인 읽은 자기 입력 revision", nullable = true, minimum = "1")
    val expectedRevision: Int? = null,
) {
    fun naturalText(): String? {
        val manual = manualAvailableTimes
        require(manual == null || manual.isNull || manual.isArray) { "Invalid submission field type" }
        if (manual != null && manual.isArray && manual.size() > 0) {
            throw SubmissionException(SubmissionErrorCode.SUBMISSION_MANUAL_AVAILABILITY_UNSUPPORTED)
        }
        require(rawText == null || rawText.isNull || rawText.isString) { "Invalid submission field type" }
        return rawText?.takeUnless { it.isNull }?.stringValue()
    }

    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Nothing = throw IllegalArgumentException("Unsupported submission field")
}

@Schema(description = "현재 익명 참여자의 최신 제출")
data class SubmissionResponse(
    @field:Schema(description = "불변 제출 revision")
    val revision: Int,
    @field:JsonProperty("raw_text")
    @field:Schema(description = "저장된 자연어 조건", nullable = true)
    val rawText: String?,
    @field:JsonProperty("manual_available_times")
    @field:ArraySchema(
        arraySchema = Schema(description = "호환용으로 항상 빈 배열. 구 수동 원본은 노출하지 않음", deprecated = true),
        maxItems = 0,
    )
    val manualAvailableTimes: List<Any>,
    @field:Schema(description = "BCP 47 제출 locale")
    val locale: String,
    @field:JsonProperty("created_at")
    @field:Schema(description = "현재 revision 저장 시각")
    val createdAt: Instant,
    @field:Schema(description = "현재 입력 수집 상태에서 수정 가능한지 여부")
    val editable: Boolean,
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "수정 가능할 때의 현재 조건 수정 라운드", nullable = true, format = "uuid")
    val revisionRoundId: java.util.UUID? = null,
    @field:JsonProperty("state_version")
    @field:Schema(description = "응답 snapshot의 방 조율 상태 버전", minimum = "0")
    val stateVersion: Long = 0,
) {
    companion object {
        fun from(view: SubmissionView) =
            SubmissionResponse(
                view.revision,
                view.rawText,
                emptyList(),
                view.locale,
                view.createdAt,
                view.editable,
                view.revisionRoundId,
                view.stateVersion,
            )
    }
}

@Schema(description = "성공적으로 저장한 자연어 제출. 원문은 항상 non-null")
data class SavedSubmissionResponse(
    @field:Schema(description = "불변 제출 revision")
    val revision: Int,
    @field:JsonProperty("raw_text")
    @field:Schema(description = "ECMAScript trim 후 저장된 필수 자연어", requiredMode = Schema.RequiredMode.REQUIRED)
    val rawText: String,
    @field:JsonProperty("manual_available_times")
    @field:ArraySchema(arraySchema = Schema(description = "호환용으로 항상 빈 배열", deprecated = true), maxItems = 0)
    val manualAvailableTimes: List<Any>,
    @field:Schema(description = "BCP 47 제출 locale")
    val locale: String,
    @field:JsonProperty("created_at")
    @field:Schema(description = "현재 revision 저장 시각")
    val createdAt: Instant,
    @field:Schema(description = "현재 입력 수집 상태에서 수정 가능한지 여부")
    val editable: Boolean,
    @field:JsonProperty("revision_round_id")
    @field:Schema(description = "수정 가능할 때의 현재 조건 수정 라운드", nullable = true, format = "uuid")
    val revisionRoundId: java.util.UUID? = null,
    @field:JsonProperty("state_version")
    @field:Schema(description = "응답 snapshot의 방 조율 상태 버전", minimum = "0")
    val stateVersion: Long = 0,
) {
    companion object {
        fun from(view: SubmissionView) =
            SavedSubmissionResponse(
                view.revision,
                requireNotNull(view.rawText),
                emptyList(),
                view.locale,
                view.createdAt,
                view.editable,
                view.revisionRoundId,
                view.stateVersion,
            )
    }
}
