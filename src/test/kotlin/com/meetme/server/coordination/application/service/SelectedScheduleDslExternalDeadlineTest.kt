package com.meetme.server.coordination.application.service

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

/** Synthetic clock/public processor boundary; no persistence implementation or network behavior duplicated. */
class SelectedScheduleDslExternalDeadlineTest {
    @Test
    fun `SED01 five seconds consumed before selected processing leave fifty five seconds for admission`() {
        val f = Fixture(seconds(5))
        f.processor.processBounded(f.batch.id, seconds(60))
        assertEquals(listOf(Duration.ofSeconds(55)), f.admissionRemaining)
        assertEquals(1, f.providerCalls)
        assertEquals(1, f.publications)
    }

    @Test
    fun `SED02 an already elapsed external entry deadline cannot dispatch a paid provider call`() {
        val f = Fixture(seconds(60))
        f.processor.processBounded(f.batch.id, seconds(60))
        assertEquals(0, f.providerCalls)
        assertEquals(0, f.publications)
        // Start/admission may perform a locked terminal transition, but cannot receive a fresh budget.
        assertTrue(f.admissionRemaining.all { it.isZero })
    }

    private class Fixture(
        val nanos: Long,
    ) {
        private val persistence = mock(SelectedScheduleDslProcessingPersistenceService::class.java)
        val batch =
            SubmissionBatch(
                SubmissionBatchId(F.ir.batch),
                MeetingRoomId(F.ir.room),
                listOf(SubmissionVersionId(F.ir.first), SubmissionVersionId(F.ir.second)),
                F.selectedAt,
            )
        private val run = CoordinationRun.queued(CoordinationRunId(F.ir.run), batch).startStructuring()
        private val binding = FinalAnalysisAttemptBinding(F.route(), F.invocation().copy(deadlineAt = F.selectedAt.plusSeconds(60)))
        private val attempt =
            CoordinationAttempt(
                UUID(0, 9931),
                run.id,
                1,
                F.selectedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                provider = AnalysisProvider.GEMINI,
                invocationId = binding.invocation.id,
            )
        val admissionRemaining = mutableListOf<Duration>()
        var providerCalls = 0
        var publications = 0
        val processor: SelectedScheduleDslBatchProcessor

        init {
            `when`(persistence.start(batch.id)).thenReturn(run)
            `when`(persistence.admit(anyValue(), anyValue(), anyValue())).thenAnswer {
                admissionRemaining += it.getArgument<Duration>(2)
                SelectedScheduleDslInvocation(binding, attempt)
            }
            `when`(persistence.isCurrent(anyValue())).thenReturn(true)
            `when`(persistence.request(anyValue(), anyValue(), Mockito.anyLong())).thenAnswer {
                val cutoff = it.getArgument<Long>(2)
                mock(SelectedScheduleDslProviderRequest::class.java).also { request ->
                    `when`(request.binding).thenReturn(binding)
                    `when`(request.attempt).thenReturn(attempt)
                    `when`(request.timeout).thenReturn(minOf(Duration.ofSeconds(15), Duration.ofNanos((cutoff - nanos).coerceAtLeast(0))))
                }
            }
            `when`(persistence.completeDsl(anyValue(), anyValue(), anyValue(), anyValue())).thenAnswer {
                val canPublish = it.getArgument<() -> Boolean>(3)
                if (canPublish()) {
                    publications++
                    true
                } else {
                    false
                }
            }
            processor =
                SelectedScheduleDslBatchProcessor(
                    persistence,
                    SelectedScheduleDslProviderPort {
                        providerCalls++
                        ScheduleDslProviderResponse("{}", ParserUsage(1, 1, 2), AnalysisProvider.GEMINI, "gemini-3.8-flash")
                    },
                    SelectedScheduleDslProviderPort {
                        providerCalls++
                        error("No fallback expected in external deadline success or expired actor")
                    },
                    RetryDelayPort { error("No retry expected") },
                    JitterPort { 0 },
                    MonotonicTimePort { nanos },
                )
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
