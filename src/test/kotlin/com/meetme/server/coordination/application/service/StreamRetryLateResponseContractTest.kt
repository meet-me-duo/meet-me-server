package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.UUID
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** SR17: A completed queue delivery must explicitly terminate its expired analysis before ACK. */
class StreamRetryLateResponseContractTest {
    @Test
    fun `SR17 queue late response explicitly delays its analysis without attempting publication`() {
        val f = Fixture(queued = true)

        f.process()

        verify(f.persistence).delay(f.binding)
        verify(f.persistence, never()).completeDsl(anyValue(), anyValue(), anyValue(), anyValue())
    }

    @Test
    fun `SR13 direct C5 late response retains its existing ownership guard behavior`() {
        val f = Fixture(queued = false)

        f.process()

        verify(f.persistence, never()).delay(f.binding)
        verify(f.persistence, never()).completeDsl(anyValue(), anyValue(), anyValue(), anyValue())
    }

    private class Fixture(
        private val queued: Boolean,
    ) {
        val persistence = mock(SelectedScheduleDslProcessingPersistenceService::class.java)
        private val input = N.input()
        private val route =
            F.route(
                FinalAnalysisRouteChoice.DslStructuralV5(
                    IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", input.policy.version),
                    input.policy,
                    N.snapshot(input).context,
                    input.meetingMode,
                    "ko-time-terms-1",
                ),
            )
        val binding =
            FinalAnalysisAttemptBinding(
                route,
                F.invocation().copy(
                    deadlineAt = F.selectedAt.plusSeconds(60),
                    policyVersion = if (queued) AnalysisInvocation.STREAM_POLICY_VERSION else AnalysisInvocation.C5_POLICY_VERSION,
                    geminiAttempts = 1,
                ),
            )
        private val run = mock(CoordinationRun::class.java)
        private val batchId = SubmissionBatchId(route.batchId)
        private val attempt =
            CoordinationAttempt(
                UUID.randomUUID(),
                binding.invocation.runId,
                1,
                F.selectedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                provider = AnalysisProvider.GEMINI,
                model = "gemini-3.8-flash",
                policyVersion = binding.invocation.policyVersion,
                invocationId = binding.invocation.id,
            )
        private val request = mock(SelectedScheduleDslProviderRequest::class.java)
        private var nanos = 0L
        private val processor =
            SelectedScheduleDslBatchProcessor(
                persistence,
                SelectedScheduleDslProviderPort {
                    nanos = Duration.ofSeconds(61).toNanos()
                    ScheduleDslProviderResponse("{}", ParserUsage(10, 5, 2), AnalysisProvider.GEMINI, "gemini-3.8-flash")
                },
                SelectedScheduleDslProviderPort { error("No fallback after the execution deadline") },
                RetryDelayPort { error("No sleep after the execution deadline") },
                JitterPort { error("No retry scheduling after the execution deadline") },
                MonotonicTimePort { nanos },
            )

        init {
            val admitted = SelectedScheduleDslInvocation(binding, attempt)
            `when`(persistence.start(batchId)).thenReturn(run)
            `when`(persistence.admitQueued(anyValue(), anyValue(), anyValue())).thenReturn(admitted)
            `when`(persistence.admit(anyValue(), anyValue(), anyValue())).thenReturn(admitted)
            `when`(persistence.remainingBudget(binding)).thenReturn(Duration.ofSeconds(60))
            `when`(persistence.isCurrent(binding)).thenAnswer { nanos < Duration.ofSeconds(60).toNanos() }
            `when`(persistence.request(anyValue(), anyValue(), Mockito.anyLong())).thenReturn(request)
            `when`(request.timeout).thenReturn(Duration.ofSeconds(15))
        }

        fun process() {
            val deadline = Duration.ofSeconds(60).toNanos()
            if (queued) processor.processQueuedBounded(batchId, deadline) else processor.processBounded(batchId, deadline)
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
