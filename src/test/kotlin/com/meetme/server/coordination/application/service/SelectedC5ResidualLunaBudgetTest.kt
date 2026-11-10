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

/** Public coordinator deadlines, with synthetic time and port doubles; no transport, database or model call. */
class SelectedC5ResidualLunaBudgetTest {
    @Test
    fun `CRB01 new C5 timeout immediately permits forty Luna seconds without extending total sixty`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos = seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT, retryAfterMillis = 5_000)
        }
        f.lunaAction = {
            f.nanos += seconds(39)
            f.response(AnalysisProvider.OPENAI)
        }
        f.process(externalDeadline = seconds(90))
        assertEquals(listOf(Duration.ofSeconds(60)), f.admissionRemaining)
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(AnalysisProvider.OPENAI), f.additionalAdmissions)
        assertEquals(listOf(seconds(15), seconds(41)), f.deadlines.map { it.cutoff })
        assertEquals(listOf(Duration.ofSeconds(15), Duration.ofSeconds(40)), f.transportTimeouts)
        assertEquals(listOf(ParserFailureKind.TIMEOUT), f.failures)
        assertTrue(f.delays.isEmpty())
        assertTrue(f.jitterBounds.isEmpty())
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `CRB02 delayed admission return and request preparation consume the fixed admitted Luna window`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos = seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAdmissionReturnNanos = seconds(3)
        f.lunaPreparationNanos = seconds(5)
        f.lunaAction = {
            f.nanos += seconds(32) - 1
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(listOf(seconds(1)), f.lunaAdmissionAnchors)
        assertEquals(Deadline(AnalysisProvider.OPENAI, seconds(4), seconds(41)), f.deadlines.last())
        assertEquals(seconds(9), f.transportStarts.last())
        assertEquals(Duration.ofSeconds(32), f.transportTimeouts.last())
        assertEquals(seconds(41) - 1, f.nanos)
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `CRB03 forty second Luna cap does not shrink Gemini thirty second phase or completion reserve`() {
        val f = Fixture()
        f.geminiAction = { request ->
            f.nanos += request.timeout.toNanos()
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }
        f.lunaAction = {
            f.nanos = seconds(57) - 1
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(0L, seconds(15), seconds(30)), f.deadlines.map { it.requestStarted })
        assertEquals(listOf(seconds(15), seconds(30), seconds(57)), f.deadlines.map { it.cutoff })
        assertEquals(listOf(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(27)), f.transportTimeouts)
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `CRB04 external deadline and elapsed entry time cannot refresh provider or completion windows`() {
        val f = Fixture(totalDeadlineSeconds = 40)
        f.nanos = seconds(5)
        f.geminiAction = { request ->
            f.nanos += request.timeout.toNanos()
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAction = {
            f.nanos = seconds(37)
            f.response(AnalysisProvider.OPENAI)
        }
        f.process(externalDeadline = seconds(40))
        assertEquals(listOf(Duration.ofSeconds(35)), f.admissionRemaining)
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(seconds(5), seconds(10)), f.deadlines.map { it.requestStarted })
        assertEquals(listOf(seconds(10), seconds(37)), f.deadlines.map { it.cutoff })
        assertEquals(listOf(Duration.ofSeconds(5), Duration.ofSeconds(27)), f.transportTimeouts)
        assertEquals(listOf(ParserFailureKind.TIMEOUT), f.terminal)
        assertEquals(0, f.publicationEntries)
        assertEquals(0, f.completed)
    }

    @Test
    fun `CRB05 response at fixed Luna cap is timed out without publication or a second fallback`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos = seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAction = {
            f.nanos = seconds(41)
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(seconds(41), f.deadlines.last().cutoff)
        assertEquals(Duration.ofSeconds(40), f.transportTimeouts.last())
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(listOf(AnalysisProvider.OPENAI), f.additionalAdmissions)
        assertEquals(listOf(ParserFailureKind.TIMEOUT), f.terminal)
        assertEquals(0, f.publicationEntries)
        assertEquals(0, f.completed)
    }

    @Test
    fun `CRB06 persisted v1 C5 invocation retains twenty seven seconds despite its structural route`() {
        assertEquals("bounded-luna-v1", F.invocation().policyVersion)
        val f = Fixture(policyVersion = "bounded-luna-v1")
        f.geminiAction = {
            f.nanos = seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAction = {
            f.nanos = seconds(28) - 1
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(listOf(seconds(15), seconds(28)), f.deadlines.map { it.cutoff })
        assertEquals(Duration.ofSeconds(27), f.transportTimeouts.last())
        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), f.calls)
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
    }

    @Test
    fun `CRB07 retryable server failures still stop at four primary attempts and one forty second fallback`() {
        val f = Fixture()
        f.geminiAction = {
            f.nanos += seconds(1)
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }
        f.lunaAction = {
            f.nanos += seconds(39)
            f.response(AnalysisProvider.OPENAI)
        }
        f.process()
        assertEquals(List(4) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, f.calls)
        assertEquals(List(3) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, f.additionalAdmissions)
        assertEquals(List(4) { ParserFailureKind.SERVER }, f.failures)
        assertEquals(listOf(1001L, 2001L, 4001L), f.jitterBounds)
        assertEquals(List(3) { Duration.ZERO }, f.delays)
        assertEquals(listOf(seconds(15), seconds(16), seconds(17), seconds(18), seconds(44)), f.deadlines.map { it.cutoff })
        assertEquals(List(4) { Duration.ofSeconds(15) } + Duration.ofSeconds(40), f.transportTimeouts)
        assertEquals(1, f.completed)
        assertTrue(f.terminal.isEmpty())
    }

    private data class Deadline(
        val provider: AnalysisProvider,
        val requestStarted: Long,
        val cutoff: Long,
    )

    private class Fixture(
        policyVersion: String = "c5-residual-luna-v2",
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
                F.invocation().copy(deadlineAt = F.selectedAt.plusSeconds(totalDeadlineSeconds), policyVersion = policyVersion),
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
            `when`(persistence.admit(anyValue(), anyValue(), anyValue())).thenAnswer {
                admissionRemaining += it.getArgument<Duration>(2)
                SelectedScheduleDslInvocation(binding, attempt(AnalysisProvider.GEMINI))
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
                    0
                },
                MonotonicTimePort { nanos },
            ).processBounded(batch.id, externalDeadline)
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
