package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.application.port.output.IdGenerator
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Synthetic representative SDK failure at the client factory: no client is built and no network/model call occurs.
class GeminiHighDemandBoundaryTest {
    @Test
    fun `public adapter classifies 503 high demand as retryable server failure with two second retry after`() {
        val request =
            NaturalLanguageBatchRequest(
                java.time.ZoneId.of("Asia/Seoul"),
                NaturalLanguageOnlyFixture.DATE,
                NaturalLanguageOnlyFixture.DATE.plusDays(2),
                listOf(
                    NaturalLanguageInput(UUID(0, 1).toString(), "목요일 오후 7시부터 9시", Locale.KOREAN, NaturalLanguageOnlyFixture.DATE),
                ),
            )
        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenThrow(highDemand())
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter().parse(request) }

            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertTrue(failure.retryable)
            assertEquals(2_000L, failure.retryAfterMillis)
            factory.verify({ Client.builder() }, times(1))
        }
    }

    @Test
    fun `public adapter preserves fractional provider retry delay for server failure`() {
        val fixture = NaturalLanguageOnlyFixture(listOf("original host text", "original member text"))
        val request =
            NaturalLanguageBatchRequest(
                fixture.room.timeZone.value,
                fixture.room.searchRange.startInclusive,
                fixture.room.searchRange.endExclusive,
                fixture.submissions.map {
                    NaturalLanguageInput(
                        it.latest.id.value
                            .toString(),
                        requireNotNull(it.latest.rawText),
                        it.latest.locale,
                        NaturalLanguageOnlyFixture.DATE,
                    )
                },
            )
        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenThrow(ApiException(503, "UNAVAILABLE", "high demand; retryDelay: 1.5s"))
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter().parse(request) }

            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertTrue(failure.retryable)
            assertEquals(1_500L, failure.retryAfterMillis)
            factory.verify({ Client.builder() }, times(1))
        }
    }

    @Test
    fun `four high demand failures delay original batch and retry extends attempt ledger without semantic rejection`() {
        val fixture = NaturalLanguageOnlyFixture(listOf("original host text", "original member text"))
        fixture.run = CoordinationRun.queued(fixture.run.id, fixture.batch)
        val originalInputs = fixture.submissions.toList()
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val ledger = linkedMapOf<UUID, CoordinationAttempt>()
        val requests = mutableListOf<NaturalLanguageBatchRequest>()
        val delays = mutableListOf<Duration>()
        val jitterBounds = mutableListOf<Long>()
        `when`(attempts.countByRun(fixture.run.id)).thenAnswer { ledger.size }
        doAnswer {
            val attempt = it.getArgument<CoordinationAttempt>(0)
            ledger[attempt.id] = attempt
            null
        }.`when`(attempts).insert(anyValue())
        doAnswer {
            val attempt = it.getArgument<CoordinationAttempt>(0)
            ledger[attempt.id] = attempt
            null
        }.`when`(attempts).update(anyValue())
        val adapter = adapter()
        val processor =
            GeminiBatchProcessor(
                fixture.runRepository,
                fixture.submissionRepository,
                fixture.structuredRepository,
                attempts,
                NaturalLanguageParserPort { request ->
                    requests += request
                    adapter.parse(request)
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { delays += it },
                JitterPort { bound ->
                    jitterBounds += bound
                    0
                },
                MonotonicTimePort { 0 },
                Clock.fixed(NaturalLanguageOnlyFixture.NOW, ZoneOffset.UTC),
                GeminiProcessingPersistenceService(
                    fixture.roomRepository,
                    fixture.runRepository,
                    fixture.structuredRepository,
                    attempts,
                    Clock.fixed(NaturalLanguageOnlyFixture.NOW, ZoneOffset.UTC),
                ),
            )
        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenThrow(highDemand())
            processor.process(fixture.batch.id)

            assertEquals(CoordinationStatus.ANALYSIS_DELAYED, fixture.run.status)
            assertEquals(fixture.batch, fixture.run.batch)
            assertEquals(originalInputs, fixture.submissions)
            assertTrue(fixture.structured.isEmpty(), "Provider outage cannot fabricate AMBIGUOUS_TIME or any structured result")
            assertTrue(fixture.run.candidates.isEmpty())
            assertNull(fixture.run.quality)
            assertEquals(listOf(1, 2, 3, 4), ledger.values.map { it.attemptNumber })
            assertEquals(List(3) { Duration.ofSeconds(2) }, delays)
            assertTrue(jitterBounds.isEmpty(), "Explicit Retry-After takes precedence over jitter")
            assertEquals(4, requests.size)
            assertTrue(requests.all { it == requests.first() })
            assertTrue(ledger.values.all { it.failureKind == "SERVER" && it.finishedAt != null })
            factory.verify({ Client.builder() }, times(4))

            // Existing real HTTP tests separately enforce HOST-only and duplicate retry request idempotency.
            fixture.run = fixture.run.retryAnalysis()
            processor.process(fixture.batch.id)

            assertEquals(CoordinationStatus.ANALYSIS_DELAYED, fixture.run.status)
            assertEquals(fixture.batch, fixture.run.batch)
            assertEquals(originalInputs, fixture.submissions)
            assertTrue(fixture.structured.isEmpty())
            assertTrue(fixture.run.candidates.isEmpty())
            assertEquals((1..8).toList(), ledger.values.map { it.attemptNumber })
            assertTrue(ledger.values.all { it.coordinationRunId == fixture.run.id && it.failureKind == "SERVER" })
            assertEquals(List(6) { Duration.ofSeconds(2) }, delays)
            assertEquals(8, requests.size)
            assertTrue(requests.all { it == requests.first() })
            factory.verify({ Client.builder() }, times(8))
        }
    }

    private fun adapter() =
        GeminiNaturalLanguageParserAdapter(
            GeminiProperties(apiKey = "synthetic-placeholder-never-used-by-client"),
            JsonMapper.builder().build(),
        )

    // Issue #96 uses SDK status/code, never user-controlled message text, to classify a failure.
    private fun highDemand() = ApiException(503, "UNAVAILABLE", "high demand. Retry-After: 2")

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
