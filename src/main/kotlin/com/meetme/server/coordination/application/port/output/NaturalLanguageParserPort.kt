package com.meetme.server.coordination.application.port.output

import com.meetme.server.submission.domain.StructuredSubmissionResult
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class NaturalLanguageInput(
    val inputRef: String,
    val rawText: String,
    val locale: Locale,
    val referenceDate: LocalDate,
)

data class NaturalLanguageBatchRequest(
    val timeZone: ZoneId,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val inputs: List<NaturalLanguageInput>,
    val callTimeout: Duration = Duration.ofSeconds(15),
)

data class ParserUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val responseBytes: Int?,
)

data class NaturalLanguageBatchResult(
    val results: List<StructuredSubmissionResult>,
    val usage: ParserUsage,
)

enum class ParserFailureKind {
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    SERVER,
    INVALID_RESPONSE,
    CONFIGURATION,
    AUTHENTICATION,
    PERMISSION,
    BILLING,
    QUOTA,
    INVALID_REQUEST,
}

class NaturalLanguageParserException(
    val kind: ParserFailureKind,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(kind.name, cause) {
    val retryable: Boolean
        get() = kind in setOf(ParserFailureKind.NETWORK, ParserFailureKind.TIMEOUT, ParserFailureKind.RATE_LIMIT, ParserFailureKind.SERVER)
}

fun interface NaturalLanguageParserPort {
    fun parse(request: NaturalLanguageBatchRequest): NaturalLanguageBatchResult
}
