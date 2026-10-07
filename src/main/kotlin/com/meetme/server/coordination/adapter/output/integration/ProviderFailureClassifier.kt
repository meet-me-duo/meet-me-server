package com.meetme.server.coordination.adapter.output.integration

import com.fasterxml.jackson.core.JsonProcessingException
import com.google.genai.errors.ApiException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import tools.jackson.core.JacksonException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.util.Locale
import java.util.concurrent.TimeoutException

internal object ProviderFailureClassifier {
    fun classify(exception: Throwable): ParserFailureKind {
        val causes = generateSequence(exception) { it.cause }.take(16).toList()
        causes.filterIsInstance<NaturalLanguageParserException>().firstOrNull()?.let { return it.kind }
        causes.filterIsInstance<ApiException>().firstOrNull()?.let { return classifyStatus(it.code(), it.status()) }
        return when {
            causes.any { it is JsonProcessingException || it is JacksonException } -> ParserFailureKind.INVALID_RESPONSE
            causes.any { it is HttpTimeoutException || it is InterruptedIOException || it is TimeoutException } -> ParserFailureKind.TIMEOUT
            causes.any { it is IOException } -> ParserFailureKind.NETWORK
            else -> ParserFailureKind.INVALID_RESPONSE
        }
    }

    fun classifyStatus(
        httpStatus: Int,
        code: String?,
        type: String? = null,
    ): ParserFailureKind {
        val normalizedCode = code?.uppercase(Locale.ROOT)
        val normalizedType = type?.uppercase(Locale.ROOT)
        return when {
            httpStatus == 401 -> ParserFailureKind.AUTHENTICATION
            httpStatus == 403 -> ParserFailureKind.PERMISSION
            httpStatus == 402 -> ParserFailureKind.BILLING
            normalizedCode in BILLING_CODES -> ParserFailureKind.BILLING
            normalizedCode in QUOTA_CODES -> ParserFailureKind.QUOTA
            httpStatus in setOf(400, 404, 405, 409, 422) -> ParserFailureKind.INVALID_REQUEST
            normalizedCode in RATE_CODES || normalizedType == "RATE_LIMIT_ERROR" -> ParserFailureKind.RATE_LIMIT
            httpStatus == 429 -> ParserFailureKind.QUOTA
            httpStatus == 408 || httpStatus == 504 -> ParserFailureKind.TIMEOUT
            httpStatus in setOf(500, 502, 503) -> ParserFailureKind.SERVER
            httpStatus in 500..599 -> ParserFailureKind.INVALID_REQUEST
            httpStatus in 400..499 -> ParserFailureKind.INVALID_REQUEST
            else -> ParserFailureKind.INVALID_RESPONSE
        }
    }

    private val BILLING_CODES =
        setOf(
            "BILLING_ERROR",
            "BILLING_DISABLED",
            "CREDIT_BALANCE_EXHAUSTED",
            "ORGANIZATION_SPEND_LIMIT_EXCEEDED",
            "PROJECT_SPEND_LIMIT_EXCEEDED",
        )
    private val QUOTA_CODES =
        setOf("INSUFFICIENT_QUOTA", "QUOTA_EXCEEDED", "RESOURCE_EXHAUSTED", "ORGANIZATION_USAGE_LIMIT_EXCEEDED")
    private val RATE_CODES = setOf("RATE_LIMIT", "RATE_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS", "SLOW_DOWN")
}
