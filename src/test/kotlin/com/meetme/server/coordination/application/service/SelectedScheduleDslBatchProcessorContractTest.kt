package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
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

/** Public orchestration boundary only; persistence supplies fencing and strict parser decisions. */
class SelectedScheduleDslBatchProcessorContractTest {
    @Test
    fun `SFB01 first Gemini success publishes once without fallback or extra attempt`() {
        val f = Fixture()
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertEquals(1, f.completed)
        assertTrue(f.failures.isEmpty())
        assertTrue(f.terminal.isEmpty())
        assertTrue(f.additionalAdmissions.isEmpty())
    }

    @Test
    fun `SFB02 four typed Gemini server failures fall back once to typed Luna and stop`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
        f.process()
        assertEquals(List(4) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, f.calls)
        assertEquals(List(4) { ParserFailureKind.SERVER }, f.failures)
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
        assertEquals(List(3) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, f.additionalAdmissions)
        assertTrue(f.deadlines.all { it.deadline <= seconds(57) })
    }

    @Test
    fun `SFB03 elapsed Gemini calls preserve Luna budget and completion reserve inside sixty seconds`() {
        val f = Fixture()
        f.geminiAction = { request ->
            f.nanos += request.timeout.toNanos()
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }
        f.lunaAction = { request ->
            // A successful response must arrive strictly before its timeout/deadline.
            f.nanos += request.timeout.toNanos() - 1
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertTrue(f.deadlines.filter { it.provider == AnalysisProvider.GEMINI }.all { it.deadline <= seconds(30) })
        assertTrue(f.deadlines.filter { it.provider == AnalysisProvider.OPENAI }.all { it.deadline <= seconds(57) })
        assertTrue(f.deadlines.all { it.deadline <= seconds(57) })
        assertEquals(seconds(57) - 1, f.nanos)
        assertEquals(1, f.completed)
    }

    @Test
    fun `SFB04 strict parser invalid response is terminal without fallback or retry`() {
        val f = Fixture()
        f.publicationFailure = NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertEquals(0, f.completed)
        assertEquals(listOf(ParserFailureKind.INVALID_RESPONSE), f.terminal)
        assertTrue(f.additionalAdmissions.isEmpty())
    }

    @Test
    fun `SFB05 failed Gemini chain and failed Luna never create a sixth physical call`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
        f.lunaAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
        f.process()
        assertEquals(List(4) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, f.calls)
        assertEquals(0, f.completed)
        assertEquals(listOf(ParserFailureKind.SERVER), f.terminal)
        assertEquals(1, f.calls.count { it == AnalysisProvider.OPENAI })
    }

    @Test
    fun `SFB06 stale ownership after failed call stops further physical attempts`() {
        val f = Fixture()
        f.geminiAction = {
            f.current = false
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertEquals(0, f.completed)
        assertTrue(f.terminal.isEmpty())
        assertTrue(f.additionalAdmissions.isEmpty())
    }

    @Test
    fun `SFB07 request preparation does not consume the returned physical transport timeout twice`() {
        val f = Fixture()
        f.requestPreparationNanos = seconds(5)
        val observedTimeouts = mutableListOf<Duration>()
        f.geminiAction = { request ->
            observedTimeouts += request.timeout
            f.nanos += seconds(7)
            f.response(AnalysisProvider.GEMINI)
        }
        f.process()
        assertEquals(listOf(Duration.ofSeconds(10)), observedTimeouts)
        assertEquals(seconds(12), f.nanos)
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertEquals(1, f.completed)
        assertTrue(f.additionalAdmissions.isEmpty())
        assertTrue(f.failures.isEmpty())
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `SFB08 currentness database delay cannot start publication inside completion reserve`() {
        val f = Fixture()
        f.geminiAction = { request ->
            f.nanos += request.timeout.toNanos()
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }
        f.lunaAction = {
            f.nanos += Duration.ofMillis(26_900).toNanos()
            f.response(AnalysisProvider.OPENAI)
        }
        // Model a DB currentness check crossing the provider budget only after response arrival.
        f.currentnessDelayAtNanos = Duration.ofMillis(56_900).toNanos()
        f.currentnessDelayUntilNanos = seconds(58)
        f.process()
        assertEquals(seconds(58), f.nanos)
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(0, f.publicationEntries)
        assertEquals(0, f.completed)
        assertTrue(f.terminal.isNotEmpty() || f.delayed > 0)
    }

    @Test
    fun `ITF03 selected timeout has no Gemini readmission retry delay or jitter before existing Luna`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos += seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT, retryAfterMillis = 5_000)
        }
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(AnalysisProvider.OPENAI), f.additionalAdmissions)
        assertEquals(listOf(ParserFailureKind.TIMEOUT), f.failures)
        assertTrue(f.delays.isEmpty())
        assertTrue(f.jitterBounds.isEmpty())
        assertEquals(listOf(0L, seconds(1)), f.deadlines.map { it.started })
        assertEquals(seconds(28), f.deadlines.last().deadline)
        assertEquals(1, f.completed)
        assertEquals(0, f.delayed)
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `ITF04 selected timeout after total budget exhaustion cannot admit or call Luna`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos = seconds(60)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI), f.calls)
        assertTrue(f.additionalAdmissions.isEmpty())
        assertEquals(listOf(ParserFailureKind.TIMEOUT), f.failures)
        assertEquals(0, f.publicationEntries)
        assertEquals(0, f.completed)
        assertEquals(1, f.delayed)
        assertTrue(f.delays.isEmpty())
        assertTrue(f.jitterBounds.isEmpty())
        assertEquals(seconds(60), f.nanos)
    }

    private data class Deadline(
        val provider: AnalysisProvider,
        val started: Long,
        val deadline: Long,
    )

    private class Fixture {
        val persistence = mock(SelectedScheduleDslProcessingPersistenceService::class.java)
        private val binding = FinalAnalysisAttemptBinding(F.route(), F.invocation().copy(deadlineAt = F.selectedAt.plusSeconds(60)))
        private val batch =
            SubmissionBatch(
                SubmissionBatchId(F.ir.batch),
                MeetingRoomId(F.ir.room),
                listOf(SubmissionVersionId(F.ir.first), SubmissionVersionId(F.ir.second)),
                F.selectedAt,
            )
        private val run = CoordinationRun.queued(CoordinationRunId(F.ir.run), batch).startStructuring()
        var nanos = 0L
        var current = true
        var completed = 0
        var publicationEntries = 0
        var delayed = 0
        var requestPreparationNanos = 0L
        var currentnessDelayAtNanos: Long? = null
        var currentnessDelayUntilNanos = 0L
        var publicationFailure: NaturalLanguageParserException? = null
        val calls = mutableListOf<AnalysisProvider>()
        val delays = mutableListOf<Duration>()
        val jitterBounds = mutableListOf<Long>()
        val deadlines = mutableListOf<Deadline>()
        val failures = mutableListOf<ParserFailureKind>()
        val terminal = mutableListOf<ParserFailureKind>()
        val additionalAdmissions = mutableListOf<AnalysisProvider>()
        private var ordinal = 0
        var geminiAction: (SelectedScheduleDslProviderRequest) -> ScheduleDslProviderResponse = { response(AnalysisProvider.GEMINI) }
        var lunaAction: (SelectedScheduleDslProviderRequest) -> ScheduleDslProviderResponse = { response(AnalysisProvider.OPENAI) }

        init {
            val first = attempt(AnalysisProvider.GEMINI)
            `when`(persistence.start(batch.id)).thenReturn(run)
            `when`(persistence.admit(anyValue(), anyValue(), anyValue())).thenReturn(SelectedScheduleDslInvocation(binding, first))
            `when`(persistence.isCurrent(anyValue())).thenAnswer {
                val threshold = currentnessDelayAtNanos
                if (threshold != null && nanos >= threshold) {
                    nanos = maxOf(nanos, currentnessDelayUntilNanos)
                    currentnessDelayAtNanos = null
                }
                current
            }
            Mockito
                .doAnswer {
                    delayed++
                    null
                }.`when`(persistence)
                .delay(anyValue())
            `when`(persistence.admitAttempt(anyValue(), anyValue())).thenAnswer {
                val provider = it.getArgument<AnalysisProvider>(1)
                additionalAdmissions += provider
                if (current) attempt(provider) else null
            }
            `when`(persistence.request(anyValue(), anyValue(), Mockito.anyLong())).thenAnswer {
                assertEquals(binding, it.getArgument(0))
                val attempt = it.getArgument<CoordinationAttempt>(1)
                val deadline = it.getArgument<Long>(2)
                assertTrue(current, "Dispatch request must be fenced before provider I/O")
                assertTrue(deadline > nanos, "No request after its provider deadline")
                deadlines += Deadline(attempt.provider, nanos, deadline)
                nanos += requestPreparationNanos
                mock(SelectedScheduleDslProviderRequest::class.java).also { request ->
                    `when`(request.binding).thenReturn(binding)
                    `when`(request.attempt).thenReturn(attempt)
                    val cap = if (attempt.provider == AnalysisProvider.GEMINI) Duration.ofSeconds(15) else Duration.ofSeconds(27)
                    `when`(request.timeout).thenReturn(minOf(cap, Duration.ofNanos(deadline - nanos)))
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
                publicationFailure?.let { failure -> throw failure }
                val canPublish = it.getArgument<() -> Boolean>(3)
                if (current && canPublish()) {
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
                UUID(0, 9000L + ordinal),
                run.id,
                ordinal,
                F.selectedAt,
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

        fun process() {
            SelectedScheduleDslBatchProcessor(
                persistence,
                SelectedScheduleDslProviderPort { request ->
                    calls += AnalysisProvider.GEMINI
                    assertEquals(binding, request.binding)
                    geminiAction(request)
                },
                SelectedScheduleDslProviderPort { request ->
                    calls += AnalysisProvider.OPENAI
                    assertEquals(binding, request.binding)
                    lunaAction(request)
                },
                RetryDelayPort {
                    delays += it
                    nanos += it.toNanos()
                },
                JitterPort {
                    jitterBounds += it
                    0
                },
                MonotonicTimePort { nanos },
            ).process(batch.id)
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
