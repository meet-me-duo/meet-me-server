package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Issue #96: synthetic SDK factory errors; no client or external network connection is created. */
class GeminiProviderFailureContractTest {
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "수요일 저녁", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )

    @ParameterizedTest
    @CsvSource(
        "401,UNAUTHENTICATED,AUTHENTICATION",
        "403,PERMISSION_DENIED,PERMISSION",
        "402,BILLING_ERROR,BILLING",
        "400,INVALID_ARGUMENT,INVALID_REQUEST",
        "429,RESOURCE_EXHAUSTED,QUOTA",
        "429,RATE_LIMIT_EXCEEDED,RATE_LIMIT",
        "503,UNAVAILABLE,SERVER",
    )
    fun `Gemini SDK typed code and status distinguish temporary failures from account and request errors`(
        code: Int,
        status: String,
        expected: ParserFailureKind,
    ) {
        val failure = invokeWith(ApiException(code, status, "synthetic provider detail"))
        assertEquals(expected, failure.kind)
        assertEquals(expected in setOf(ParserFailureKind.RATE_LIMIT, ParserFailureKind.SERVER), failure.retryable)
    }

    @Test
    fun `untyped message mentioning server timeout or connection is not evidence of transient failure`() {
        val failure = invokeWith(RuntimeException("503 UNAVAILABLE timeout connection reset Retry-After: 1"))
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertEquals(false, failure.retryable)
    }

    @Test
    fun `typed network cause remains eligible for bounded fallback`() {
        val failure = invokeWith(RuntimeException(IOException("synthetic network failure")))
        assertEquals(ParserFailureKind.NETWORK, failure.kind)
        assertEquals(true, failure.retryable)
    }

    private fun invokeWith(exception: RuntimeException): NaturalLanguageParserException {
        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenThrow(exception)
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    GeminiNaturalLanguageParserAdapter(
                        GeminiProperties(apiKey = "synthetic-placeholder-never-used"),
                        JsonMapper.builder().build(),
                    ).parse(request)
                }
            factory.verify({ Client.builder() }, times(1))
            return failure
        }
    }
}
