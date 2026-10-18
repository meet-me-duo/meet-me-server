package com.meetme.server.precheck.application.port.output

import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
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
)

enum class PrecheckVerdict { CLEAR, NEEDS_CHOICE, NEEDS_EDIT }

data class PrecheckProviderResult(
    val verdict: PrecheckVerdict,
    val prompt: String? = null,
    val sourceExcerpt: String? = null,
    val choices: List<PrecheckChoice> = emptyList(),
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val responseBytes: Int? = null,
    val provider: String? = null,
    val model: String? = null,
)

fun interface PreSubmissionCheckProvider {
    fun check(request: PrecheckProviderRequest): PrecheckProviderResult
}

class PrecheckProviderException(
    val failureCode: String,
) : RuntimeException(failureCode)
