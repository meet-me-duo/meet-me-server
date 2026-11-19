package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Actual Worker and dispatchers, with provider orchestration mocked at its public boundary. */
class StreamRetryWorkerWiringTest {
    @Test
    fun `SR16 new V5 Worker forwards its exact Outbox claim through the production dispatch chain`() {
        val f = Fixture()

        f.worker.process(f.message)

        verify(f.typed).processQueuedDeliveryBounded(f.batch.id, f.deadlineNanos, f.claim)
        verify(f.typed, never()).processBounded(f.batch.id, f.deadlineNanos)
        verify(f.legacy, never()).process(f.batch.id)
        verify(f.matching, never()).process(f.batch.id)
    }

    @Test
    fun `SR13 existing C5 invocation retains its original orchestration after the Worker receives a claim`() {
        val f = Fixture()
        `when`(f.invocations.findByRunVersion(f.run.id, f.run.version)).thenReturn(
            F.invocation().copy(runId = f.run.id, runVersion = f.run.version, policyVersion = AnalysisInvocation.C5_POLICY_VERSION),
        )

        f.worker.process(f.message)

        verify(f.typed).processBounded(f.batch.id, f.deadlineNanos)
        verify(f.typed, never()).processQueuedDeliveryBounded(f.batch.id, f.deadlineNanos, f.claim)
        verify(f.legacy, never()).process(f.batch.id)
        verify(f.matching, never()).process(f.batch.id)
    }

    private class Fixture {
        private val input = N.input()
        val batch =
            SubmissionBatch(
                SubmissionBatchId(input.batchId),
                MeetingRoomId(input.roomId),
                input.inputs.map { SubmissionVersionId(it.submissionVersionId) },
                input.batchFixedAt,
            )
        val run = CoordinationRun.queued(CoordinationRunId(input.runId), batch).startStructuring()
        private val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", input.policy.version),
                input.policy,
                N.snapshot(input).context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        private val route = FrozenFinalAnalysisRoute(run.id.value, batch.id.value, batch.roomId.value, choice, F.selectedAt)
        private val runs = mock(CoordinationRunRepository::class.java)
        private val routes = mock(FinalAnalysisRouteRegistry::class.java)
        private val outbox = mock(OutboxRepository::class.java)
        private val queue = mock(CoordinationWorkQueuePort::class.java)
        val invocations = mock(AnalysisInvocationRepository::class.java)
        val typed = mock(SelectedScheduleDslBatchProcessor::class.java)
        val legacy = mock(GeminiBatchProcessor::class.java)
        val matching = mock(MatchingProcessor::class.java)
        val deadlineNanos = Duration.ofSeconds(60).toNanos()
        private val eventId = OutboxEventId(UUID.randomUUID())
        private val eventType = "SubmissionBatchStructuringRequested"
        private val event =
            OutboxEvent(
                eventId,
                "CoordinationRun",
                run.id.value,
                eventType,
                """{"event_id":"${eventId.value}","submission_batch_id":"${batch.id.value}"}""",
                OutboxStatus.PUBLISHED,
                F.selectedAt.minusSeconds(5),
                F.selectedAt.minusSeconds(1),
            )
        val claim = OutboxProcessingClaim(event.copy(deliveryCount = 1, processingLeaseUntil = F.selectedAt.plusSeconds(120)), 1)
        val message = CoordinationWorkMessage("1-0", eventId, batch.id, eventType)
        private val clock = Clock.fixed(F.selectedAt, ZoneOffset.UTC)
        private val dispatcher =
            CoordinationEventDispatcher(
                legacy,
                matching,
                SelectedFinalAnalysisDispatchService(
                    runs,
                    routes,
                    typed,
                    invocations = invocations,
                    clock = clock,
                    monotonicTime = MonotonicTimePort { 0L },
                ),
            )
        val worker =
            CoordinationWorker(
                outbox,
                queue,
                dispatcher,
                DeadLetterPersistence {
                    _,
                    _,
                    _,
                    ->
                    error("Unexpected dead letter")
                },
                clock,
            )

        init {
            `when`(runs.findByBatchId(batch.id)).thenReturn(run)
            `when`(runs.findById(run.id)).thenReturn(run)
            `when`(routes.find(run.id.value)).thenReturn(route)
            `when`(outbox.findById(eventId)).thenReturn(event)
            `when`(outbox.claimProcessing(eventId, F.selectedAt, F.selectedAt.plusSeconds(120))).thenReturn(claim)
            `when`(outbox.completeClaim(claim, F.selectedAt)).thenReturn(true)
        }
    }
}
