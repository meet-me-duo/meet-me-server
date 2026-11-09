package com.meetme.server.precheck.application.port.output

import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.domain.InputEvidence
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PrecheckProviderRequest(
    val checkId: UUID,
    val rawText: String,
    val referenceAt: Instant,
    val timeZone: ZoneId,
    val locale: String,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val timeout: Duration,
    val maximumOutputTokens: Int = 2048,
    val roomId: UUID? = null,
    val executionGeneration: Int? = null,
    val dispatchContext: AiProviderDispatchContext? = null,
    val evidence: InputEvidence? = null,
)

enum class PrecheckVerdict { CLEAR, NEEDS_CHOICE, NEEDS_EDIT, UNRESOLVED }

/** Selected by trusted provider wiring, never by a field in a provider response. */
enum class PrecheckResponseSizeContract(
    val maximumEnvelopeBytes: Int,
    val maximumPayloadBytes: Int,
) {
    LEGACY_16_KIB(16_384, 16_384),
    LUNA_64_KIB_ENVELOPE_16_KIB_PAYLOAD(65_536, 16_384),
}

data class PrecheckProviderResult(
    val verdict: PrecheckVerdict,
    val prompt: String? = null,
    val sourceExcerpt: String? = null,
    val choices: List<PrecheckChoice> = emptyList(),
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** Complete decoded HTTP envelope size; never the size of the extracted question. */
    val responseBytes: Int? = null,
    val provider: String? = null,
    val model: String? = null,
    val unresolvedReason: String? = null,
    /** UTF-8 size of the same single output_text validated by the trusted adapter. */
    val responsePayloadBytes: Int? = null,
)

fun interface PreSubmissionCheckProvider {
    fun check(request: PrecheckProviderRequest): PrecheckProviderResult

    fun responseSizeContract(): PrecheckResponseSizeContract = PrecheckResponseSizeContract.LEGACY_16_KIB
}

class PrecheckProviderException(
    val failureCode: String,
) : RuntimeException(failureCode)
