package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Public delivery contract: one bounded provider call, persisted continuation, no worker sleep. */
class SelectedStreamRetryProcessorTest {
    @Test
    fun `Gemini billing failure makes Luna due immediately with the original failure`() {
        assertImmediateLuna(ParserFailureKind.BILLING)
    }

    @Test
    fun `Gemini exhausted quota makes Luna due immediately even with Retry After`() {
        assertImmediateLuna(ParserFailureKind.QUOTA)
    }

    private fun assertImmediateLuna(kind: ParserFailureKind) {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(kind, retryAfterMillis = 10_000) }
        val continuation = kotlin.test.assertFailsWith<CoordinationRetryScheduled> { f.process() }

        assertEquals(kind.name, continuation.failureKind)
        assertEquals(F.selectedAt, continuation.nextAttemptAt)
        assertEquals(listOf(kind), f.scheduledFailures)
        assertEquals(listOf(AnalysisProvider.OPENAI to Duration.ZERO), f.scheduled)
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertTrue(f.terminal.isEmpty())
        assertTrue(f.additionalAdmissions.isEmpty())
        assertTrue(f.jitterBounds.isEmpty())
        assertTrue(f.delays.isEmpty())
    }

    @Test
    fun `Gemini short rate limit with Retry After stays on Gemini`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.RATE_LIMIT, retryAfterMillis = 500) }
        kotlin.test.assertFailsWith<CoordinationRetryScheduled> { f.process() }
        assertEquals(listOf(AnalysisProvider.GEMINI to Duration.ofMillis(500)), f.scheduled)
        assertTrue(f.jitterBounds.isEmpty())
    }

    @Test
    fun `Luna billing or quota failure ends its single attempt`() {
        for (kind in listOf(ParserFailureKind.BILLING, ParserFailureKind.QUOTA)) {
            val f = Fixture(initialProvider = AnalysisProvider.OPENAI)
            f.lunaAction = { throw NaturalLanguageParserException(kind) }
            f.process()
            assertEquals(listOf(kind), f.terminal)
            assertEquals(listOf(AnalysisProvider.OPENAI), f.calls)
            assertTrue(f.scheduled.isEmpty())
            assertTrue(f.additionalAdmissions.isEmpty())
        }
    }

    @Test
    fun `SR01 retryable failures schedule one continuation with one call and no sleep`() {
        for ((count, bound) in listOf(1 to 1001L, 2 to 2001L, 3 to 4001L)) {
            val f = Fixture(attemptCount = count)
            f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
            val scheduled =
                kotlin.test.assertFailsWith<com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled> {
                    f.process()
                }
            assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
            assertEquals(listOf(bound), f.jitterBounds)
            assertEquals(listOf(AnalysisProvider.GEMINI to Duration.ofMillis(bound - 1)), f.scheduled)
            assertEquals(F.selectedAt.plusMillis(bound - 1), scheduled.nextAttemptAt)
            assertTrue(f.delays.isEmpty())
            assertTrue(f.additionalAdmissions.isEmpty())
        }
    }

    @Test
    fun `SR05 Retry After wins and over budget reservation selects fallback without sleeping`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.RATE_LIMIT, retryAfterMillis = 45_000) }
        kotlin.test.assertFailsWith<com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled> { f.process() }
        assertEquals(listOf(AnalysisProvider.OPENAI to Duration.ZERO), f.scheduled)
        assertTrue(f.delays.isEmpty())
        assertEquals(1, f.calls.size)
    }

    @Test
    fun `SR05 timeout and fourth failure schedule fallback on a later delivery`() {
        for ((count, kind) in listOf(1 to ParserFailureKind.TIMEOUT, 4 to ParserFailureKind.NETWORK)) {
            val f = Fixture(attemptCount = count)
            f.geminiAction = { throw NaturalLanguageParserException(kind) }
            kotlin.test.assertFailsWith<com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled> { f.process() }
            assertEquals(listOf(AnalysisProvider.OPENAI to Duration.ZERO), f.scheduled)
            assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
            assertTrue(f.jitterBounds.isEmpty())
            assertTrue(f.delays.isEmpty())
        }
    }

    @Test
    fun `SR06 permanent failure terminates without fallback or retry`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.AUTHENTICATION) }
        f.process()
        assertEquals(listOf(ParserFailureKind.AUTHENTICATION), f.terminal)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
    }

    @Test
    fun `SR04 resumed fallback uses remaining original sixty seconds without fresh Gemini`() {
        val f = Fixture(attemptCount = 4, initialProvider = AnalysisProvider.OPENAI)
        f.nanos = seconds(35)
        f.process()
        assertEquals(listOf(AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(Duration.ofSeconds(22)), f.transportTimeouts)
        assertEquals(1, f.completed)
        assertTrue(f.scheduled.isEmpty())
    }

    private data class Deadline(
        val provider: AnalysisProvider,
        val requestStarted: Long,
        val cutoff: Long,
    )

    private class Fixture(
        attemptCount: Int = 1,
        initialProvider: AnalysisProvider = AnalysisProvider.GEMINI,
        totalDeadlineSeconds: Long = 60,
    ) {
        private val persistence = mock(SelectedScheduleDslProcessingPersistenceService::class.java)
        private val input = N.input()
        private val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", "schedule-context-mode-1"),
                input.policy,
                N.snapshot(input).context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        private val binding =
            FinalAnalysisAttemptBinding(
                F.route(choice),
                F.invocation().copy(
                    deadlineAt = F.selectedAt.plusSeconds(totalDeadlineSeconds),
                    policyVersion = "c5-stream-retry-v3",
                    geminiAttempts = attemptCount,
                    lunaAttempts =
                        if (initialProvider ==
                            AnalysisProvider.OPENAI
                        ) {
                            1
                        } else {
                            0
                        },
                ),
            )
        private val batch =
            SubmissionBatch(
                SubmissionBatchId(input.batchId),
                MeetingRoomId(input.roomId),
                input.inputs.map { SubmissionVersionId(it.submissionVersionId) },
                input.batchFixedAt,
            )
        private val run = CoordinationRun.queued(CoordinationRunId(input.runId), batch).startStructuring()
        var nanos = 0L
        var lunaAdmissionReturnNanos = 0L
        var lunaPreparationNanos = 0L
        var publicationEntries = 0
        var completed = 0
        val calls = mutableListOf<AnalysisProvider>()
        val scheduled = mutableListOf<Pair<AnalysisProvider, Duration>>()
        val scheduledFailures = mutableListOf<ParserFailureKind>()
        val additionalAdmissions = mutableListOf<AnalysisProvider>()
        val admissionRemaining = mutableListOf<Duration>()
        val deadlines = mutableListOf<Deadline>()
        val lunaAdmissionAnchors = mutableListOf<Long>()
        val transportStarts = mutableListOf<Long>()
        val transportTimeouts = mutableListOf<Duration>()
        val delays = mutableListOf<Duration>()
        val jitterBounds = mutableListOf<Long>()
        val failures = mutableListOf<ParserFailureKind>()
        val terminal = mutableListOf<ParserFailureKind>()
        private var ordinal = 0
        var geminiAction: (SelectedScheduleDslProviderRequest) -> ScheduleDslProviderResponse = { response(AnalysisProvider.GEMINI) }
        var lunaAction: (SelectedScheduleDslProviderRequest) -> ScheduleDslProviderResponse = { response(AnalysisProvider.OPENAI) }

        init {
            `when`(persistence.start(batch.id)).thenReturn(run)
            `when`(persistence.admitQueued(anyValue(), anyValue(), anyValue())).thenAnswer {
                admissionRemaining += it.getArgument<Duration>(2)
                SelectedScheduleDslInvocation(binding, attempt(initialProvider))
            }
            `when`(persistence.remainingBudget(anyValue())).thenAnswer { Duration.ofNanos(seconds(60) - nanos) }
            `when`(persistence.scheduleAttempt(anyValue(), anyValue(), anyValue(), anyValue(), anyValue(), anyValue())).thenAnswer {
                scheduledFailures += it.getArgument<ParserFailureKind>(2)
                scheduled += it.getArgument<AnalysisProvider>(3) to it.getArgument<Duration>(4)
                F.selectedAt.plusNanos(nanos).plus(it.getArgument<Duration>(4))
            }
            `when`(persistence.isCurrent(anyValue())).thenReturn(true)
            `when`(persistence.admitAttempt(anyValue(), anyValue())).thenAnswer {
                val provider = it.getArgument<AnalysisProvider>(1)
                additionalAdmissions += provider
                val admitted = attempt(provider)
                if (provider == AnalysisProvider.OPENAI) {
                    lunaAdmissionAnchors += nanos
                    nanos += lunaAdmissionReturnNanos
                }
                admitted
            }
            `when`(persistence.request(anyValue(), anyValue(), Mockito.anyLong())).thenAnswer {
                assertEquals(binding, it.getArgument(0))
                val attempt = it.getArgument<CoordinationAttempt>(1)
                val cutoff = it.getArgument<Long>(2)
                assertTrue(cutoff > nanos, "No dispatch request after the selected provider cutoff")
                deadlines += Deadline(attempt.provider, nanos, cutoff)
                if (attempt.provider == AnalysisProvider.OPENAI) nanos += lunaPreparationNanos
                // This port double only reports remaining time. It does not calculate a policy cap or phase deadline.
                mock(SelectedScheduleDslProviderRequest::class.java).also { request ->
                    `when`(request.binding).thenReturn(binding)
                    `when`(request.attempt).thenReturn(attempt)
                    `when`(request.timeout).thenReturn(Duration.ofNanos((cutoff - nanos).coerceAtLeast(0)))
                }
            }
            Mockito
                .doAnswer {
                    failures += it.getArgument<ParserFailureKind>(2)
                    null
                }.`when`(persistence)
                .failAttempt(anyValue(), anyValue(), anyValue(), anyValue())
            Mockito
                .doAnswer {
                    terminal += it.getArgument<ParserFailureKind>(2)
                    null
                }.`when`(persistence)
                .fail(anyValue(), anyValue(), anyValue(), anyValue())
            `when`(persistence.completeDsl(anyValue(), anyValue(), anyValue(), anyValue())).thenAnswer {
                publicationEntries++
                val canPublish = it.getArgument<() -> Boolean>(3)
                if (canPublish()) {
                    completed++
                    true
                } else {
                    false
                }
            }
        }

        fun response(provider: AnalysisProvider) =
            ScheduleDslProviderResponse(
                "{}",
                ParserUsage(10, 5, 2),
                provider,
                if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna",
            )

        private fun attempt(provider: AnalysisProvider): CoordinationAttempt {
            ordinal++
            return CoordinationAttempt(
                UUID(0, 9700L + ordinal),
                run.id,
                ordinal,
                F.selectedAt.plusNanos(nanos),
                null,
                null,
                null,
                null,
                null,
                null,
                provider = provider,
                model = if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna",
                invocationId = binding.invocation.id,
            )
        }

        fun process(externalDeadline: Long = seconds(60)) {
            SelectedScheduleDslBatchProcessor(
                persistence,
                port(AnalysisProvider.GEMINI) { geminiAction(it) },
                port(AnalysisProvider.OPENAI) { lunaAction(it) },
                RetryDelayPort {
                    delays += it
                    nanos += it.toNanos()
                },
                JitterPort {
                    jitterBounds += it
                    it - 1
                },
                MonotonicTimePort { nanos },
            ).processQueuedBounded(batch.id, externalDeadline)
        }

        private fun port(
            provider: AnalysisProvider,
            action: (SelectedScheduleDslProviderRequest) -> ScheduleDslProviderResponse,
        ) = SelectedScheduleDslProviderPort { request ->
            assertEquals(binding, request.binding)
            assertEquals(provider, request.attempt.provider)
            calls += provider
            transportStarts += nanos
            transportTimeouts += request.timeout
            action(request)
        }
    }

    companion object {
        private fun seconds(value: Long) = Duration.ofSeconds(value).toNanos()

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
