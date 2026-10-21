package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.AnalysisExecutionObservation
import com.meetme.server.shared.application.port.output.AnalysisExecutionOutcome
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.application.port.output.ObservedAnalysisProvider
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #110: observations describe admitted attempts and local dispatch, never provider billing. */
class GeminiBatchProcessorObservationTest {
    @Test
    fun `single completion reports one execution and preserves known usage and existing price`() {
        val fixture = Fixture()
        fixture.gemini = {
            fixture.elapsed += Duration.ofMillis(7)
            fixture.result(100, 50)
        }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.COMPLETED, execution.outcome)
        assertEquals(Duration.ofMillis(7), execution.elapsed)
        val attempt = execution.attempts.single()
        assertEquals(ObservedAnalysisProvider.GEMINI, attempt.provider)
        assertTrue(attempt.parserDispatched)
        assertEquals(100L, attempt.inputTokens)
        assertEquals(50L, attempt.outputTokens)
        assertCost("0.00026250", attempt.estimatedCostUsd)
        assertCost("0.00026250", fixture.legacyCosts.single())
    }

    @Test
    fun `late discarded response usage contributes alongside the eventual winner`() {
        val fixture = Fixture()
        var calls = 0
        fixture.gemini = { request ->
            calls++
            if (calls == 1) fixture.elapsed += request.callTimeout
            fixture.result(100, 50)
        }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.COMPLETED, execution.outcome)
        assertEquals(2, execution.attempts.size)
        execution.attempts.forEach {
            assertTrue(it.parserDispatched)
            assertEquals(100L, it.inputTokens)
            assertCost("0.00026250", it.estimatedCostUsd)
        }
        assertCost("0.00052500", fixture.legacyCosts.single())
    }

    @Test
    fun `unknown failed attempt cost prevents winner cost being advertised as the whole execution`() {
        val fixture = Fixture()
        var calls = 0
        fixture.gemini = {
            if (++calls == 1) throw NaturalLanguageParserException(ParserFailureKind.NETWORK)
            fixture.result(100, 50)
        }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.COMPLETED, execution.outcome)
        assertEquals(2, execution.attempts.size)
        assertTrue(execution.attempts.first().parserDispatched)
        assertNull(execution.attempts.first().inputTokens)
        assertNull(execution.attempts.first().outputTokens)
        assertNull(execution.attempts.first().estimatedCostUsd)
        assertCost("0.00026250", execution.attempts.last().estimatedCostUsd)
        assertNull(fixture.legacyCosts.single())
    }

    @Test
    fun `fallback keeps providers separate and never labels a Gemini subtotal as mixed execution cost`() {
        val fixture = Fixture()
        var calls = 0
        fixture.gemini = { request ->
            if (++calls == 1) {
                fixture.elapsed += request.callTimeout
                fixture.result(100, 50)
            } else {
                throw NaturalLanguageParserException(ParserFailureKind.SERVER)
            }
        }
        fixture.fallback = { fixture.result(500, 700) }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.COMPLETED, execution.outcome)
        assertEquals(4, execution.attempts.count { it.provider == ObservedAnalysisProvider.GEMINI })
        val fallback = execution.attempts.single { it.provider == ObservedAnalysisProvider.OPENAI }
        assertTrue(fallback.parserDispatched)
        assertEquals(500L, fallback.inputTokens)
        assertEquals(700L, fallback.outputTokens)
        assertNull(fallback.estimatedCostUsd)
        assertCost("0.00026250", execution.attempts.first().estimatedCostUsd)
        assertNull(fixture.legacyCosts.single())
    }

    @Test
    fun `admission that consumes the deadline is counted without claiming parser dispatch or zero cost`() {
        val fixture = Fixture()
        fixture.onAdmission = { fixture.elapsed = Duration.ofSeconds(60) }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.ANALYSIS_DELAYED, execution.outcome)
        val admitted = execution.attempts.single()
        assertEquals(ObservedAnalysisProvider.GEMINI, admitted.provider)
        assertFalse(admitted.parserDispatched)
        assertNull(admitted.inputTokens)
        assertNull(admitted.outputTokens)
        assertNull(admitted.estimatedCostUsd)
        assertTrue(fixture.dispatches.isEmpty())
        assertNull(fixture.legacyCosts.single())
    }

    @Test
    fun `all failed dispatches produce one delayed execution with unknown usage rather than zeros`() {
        val fixture = Fixture()
        fixture.gemini = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.ANALYSIS_DELAYED, execution.outcome)
        assertEquals(4, execution.attempts.size)
        execution.attempts.forEach {
            assertTrue(it.parserDispatched)
            assertNull(it.inputTokens)
            assertNull(it.outputTokens)
            assertNull(it.estimatedCostUsd)
        }
        assertNull(fixture.legacyCosts.single())
    }

    @Test
    fun `delayed execution still preserves known usage from a response after the total deadline`() {
        val fixture = Fixture()
        fixture.gemini = {
            fixture.elapsed = Duration.ofSeconds(60)
            fixture.result(100, 50)
        }

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.ANALYSIS_DELAYED, execution.outcome)
        assertCost("0.00026250", execution.attempts.single().estimatedCostUsd)
        assertCost("0.00026250", fixture.legacyCosts.single())
    }

    @Test
    fun `zero dispatch execution does not manufacture a known zero legacy cost`() {
        val fixture = Fixture()
        `when`(fixture.persistence.isCurrent(fixture.run)).thenReturn(false)

        fixture.process()

        val execution = fixture.observations.single()
        assertEquals(AnalysisExecutionOutcome.ANALYSIS_DELAYED, execution.outcome)
        assertTrue(execution.attempts.isEmpty())
        assertTrue(fixture.dispatches.isEmpty())
        assertNull(fixture.legacyCosts.single())
    }

    private class Fixture {
        var elapsed: Duration = Duration.ZERO
        var onAdmission: () -> Unit = {}
        var gemini: (NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult = { result(100, 50) }
        var fallback: ((NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult)? = null
        val observations = mutableListOf<AnalysisExecutionObservation>()
        val legacyCosts = mutableListOf<BigDecimal?>()
        val dispatches = mutableListOf<ObservedAnalysisProvider>()
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        private val runs = mock(CoordinationRunRepository::class.java)
        private val submissions = mock(SubmissionRepository::class.java)
        private val attempts = mock(CoordinationAttemptRepository::class.java)
        private val metrics = mock(ApplicationMetricsPort::class.java)
        private val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "synthetic observation room",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12)),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        private val inputs =
            (1..2).map {
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    room.id,
                    ParticipantId(UUID.randomUUID()),
                    SubmissionVersionId(UUID.randomUUID()),
                    "synthetic private input excluded from observations",
                    emptyList(),
                    Locale.KOREAN,
                    NOW,
                )
            }
        private val queued =
            CoordinationRun.queued(
                CoordinationRunId(UUID.randomUUID()),
                SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, inputs.map { it.latest.id }, NOW),
            )
        val run = queued.startStructuring()

        init {
            `when`(runs.findByBatchId(run.batch.id)).thenReturn(queued)
            `when`(persistence.start(queued)).thenReturn(run)
            `when`(persistence.isCurrent(run)).thenReturn(true)
            `when`(persistence.room(room.id)).thenReturn(room)
            `when`(submissions.findLatestByRoom(room.id)).thenReturn(inputs)
            doAnswer {
                onAdmission()
                null
            }.`when`(attempts).insert(anyValue())
            doAnswer {
                observations += it.getArgument<AnalysisExecutionObservation>(0)
                null
            }.`when`(metrics).analysisExecution(anyValue())
            doAnswer {
                legacyCosts += it.getArgument<BigDecimal?>(3)
                null
            }.`when`(metrics).geminiBatch(anyValue(), anyValue(), Mockito.anyInt(), anyValue())
        }

        fun result(
            input: Long?,
            output: Long?,
        ) = NaturalLanguageBatchResult(
            inputs.map {
                StructuredSubmissionResult(it.latest.id, listOf(StructuredCondition.TravelConstraint("synthetic area")), null)
            },
            ParserUsage(input, output, 512),
        )

        fun process() {
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = NOW.plus(elapsed)
                }
            GeminiBatchProcessor(
                runs,
                submissions,
                mock(StructuredSubmissionRepository::class.java),
                attempts,
                NaturalLanguageParserPort {
                    dispatches += ObservedAnalysisProvider.GEMINI
                    gemini(it)
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { elapsed += it },
                JitterPort { 0 },
                MonotonicTimePort { elapsed.toNanos() },
                clock,
                persistence,
                metrics = metrics,
                fallbackParser =
                    fallback?.let { parse ->
                        NaturalLanguageParserPort {
                            dispatches += ObservedAnalysisProvider.OPENAI
                            parse(it)
                        }
                    },
            ).process(run.batch.id)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-07T00:00:00Z")

        fun assertCost(
            expected: String,
            actual: BigDecimal?,
        ) {
            assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
