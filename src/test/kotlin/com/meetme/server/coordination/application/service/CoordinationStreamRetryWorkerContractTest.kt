package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** SR14: A delivery which cannot obtain durable processing authority is not completed. */
class CoordinationStreamRetryWorkerContractTest {
    private val now = Instant.parse("2026-11-19T00:00:00Z")
    private val eventId = OutboxEventId(UUID.randomUUID())
    private val batchId = SubmissionBatchId(UUID.randomUUID())
    private val eventType = "SubmissionBatchStructuringRequested"
    private val message = CoordinationWorkMessage("1-0", eventId, batchId, eventType)
    private val outbox = mock(OutboxRepository::class.java)
    private val queue = mock(CoordinationWorkQueuePort::class.java)
    private val processor = mock(CoordinationEventProcessor::class.java, org.mockito.Mockito.CALLS_REAL_METHODS)
    private val worker =
        CoordinationWorker(
            outbox,
            queue,
            processor,
            DeadLetterPersistence { _, _, _ -> error("No processing authority") },
            Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `SR14 active lease delivery remains unacknowledged and never invokes the processor`() {
        `when`(outbox.findById(eventId)).thenReturn(event().copy(processingLeaseUntil = now.plusSeconds(120)))

        worker.process(message)

        verify(queue, never()).acknowledge(message.streamRecordId)
        verify(processor, never()).process(eventType, batchId)
        verify(outbox, never()).markProcessed(eventId, now)
    }

    @Test
    fun `SR14 early Redis delivery before Relay commits publication remains unacknowledged`() {
        `when`(outbox.findById(eventId)).thenReturn(event().copy(status = OutboxStatus.PENDING, publishedAt = null))

        worker.process(message)

        verify(queue, never()).acknowledge(message.streamRecordId)
        verify(processor, never()).process(eventType, batchId)
        verify(outbox, never()).markProcessed(eventId, now)
    }

    @Test
    fun `SR01 known provider retry persists its due time and leaves the delivery unacknowledged`() {
        val claim = claim()
        val due = now.plusSeconds(1)
        doThrow(CoordinationRetryScheduled(due, "SERVER")).`when`(processor).process(eventType, batchId)
        `when`(outbox.scheduleRetry(claim, "SERVER", due)).thenReturn(true)

        worker.process(message)

        verify(outbox).scheduleRetry(claim, "SERVER", due)
        verify(queue, never()).acknowledge(message.streamRecordId)
        verify(outbox, never()).markProcessed(eventId, now)
        verify(outbox, never()).completeClaim(claim, now)
    }

    @Test
    fun `SR15 expected retry on fifth delivery does not become a poison message`() {
        val claim = claim(deliveryCount = 5)
        val due = now.plusSeconds(1)
        doThrow(CoordinationRetryScheduled(due, "RATE_LIMIT")).`when`(processor).process(eventType, batchId)
        `when`(outbox.scheduleRetry(claim, "RATE_LIMIT", due)).thenReturn(true)

        worker.process(message.copy(deliveryCount = 5))

        verify(outbox).scheduleRetry(claim, "RATE_LIMIT", due)
        verify(outbox, never()).recordUnexpectedFailure(claim, "RATE_LIMIT")
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    @Test
    fun `SR15 one unexpected failure after expected continuations is not five poison failures`() {
        val claim = claim(deliveryCount = 5)
        doThrow(IllegalStateException("synthetic processing failure")).`when`(processor).process(eventType, batchId)
        `when`(outbox.recordUnexpectedFailure(claim, "IllegalStateException")).thenReturn(1)

        worker.process(message.copy(deliveryCount = 5))

        verify(outbox).recordUnexpectedFailure(claim, "IllegalStateException")
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    @Test
    fun `SR11 stale unexpected failure cannot advance another consumer's failure counter or acknowledge`() {
        val claim = claim(deliveryCount = 5)
        doThrow(IllegalStateException("synthetic processing failure")).`when`(processor).process(eventType, batchId)
        `when`(outbox.recordUnexpectedFailure(claim, "IllegalStateException")).thenReturn(null)

        worker.process(message.copy(deliveryCount = 5))

        verify(outbox).recordUnexpectedFailure(claim, "IllegalStateException")
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    @Test
    fun `SR08 retry persistence failure cannot acknowledge the message`() {
        val claim = claim()
        val due = now.plusSeconds(1)
        doThrow(CoordinationRetryScheduled(due, "SERVER")).`when`(processor).process(eventType, batchId)
        `when`(outbox.scheduleRetry(claim, "SERVER", due)).thenThrow(IllegalStateException("synthetic database failure"))

        runCatching { worker.process(message) }

        verify(outbox).scheduleRetry(claim, "SERVER", due)
        verify(queue, never()).acknowledge(message.streamRecordId)
        verify(outbox, never()).markProcessed(eventId, now)
    }

    @Test
    fun `SR11 rejected retry ownership cannot acknowledge the message`() {
        val claim = claim()
        val due = now.plusSeconds(1)
        doThrow(CoordinationRetryScheduled(due, "SERVER")).`when`(processor).process(eventType, batchId)
        `when`(outbox.scheduleRetry(claim, "SERVER", due)).thenReturn(false)

        worker.process(message)

        verify(outbox).scheduleRetry(claim, "SERVER", due)
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    @Test
    fun `SR07 acknowledged success follows durable fenced completion`() {
        val claim = claim()
        `when`(outbox.completeClaim(claim, now)).thenReturn(true)

        worker.process(message)

        inOrder(outbox, queue).apply {
            verify(outbox).completeClaim(claim, now)
            verify(queue).acknowledge(message.streamRecordId)
        }
    }

    @Test
    fun `SR11 stale successful worker cannot acknowledge a newer owner's delivery`() {
        val claim = claim()
        `when`(outbox.completeClaim(claim, now)).thenReturn(false)

        worker.process(message)

        verify(outbox).completeClaim(claim, now)
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    @Test
    fun `SR07 completion write failure cannot acknowledge the message`() {
        val claim = claim()
        `when`(outbox.completeClaim(claim, now)).thenThrow(IllegalStateException("synthetic database failure"))

        runCatching { worker.process(message) }

        verify(outbox).completeClaim(claim, now)
        verify(queue, never()).acknowledge(message.streamRecordId)
    }

    private fun claim(deliveryCount: Int = 1): OutboxProcessingClaim {
        val event = event().copy(deliveryCount = deliveryCount, processingLeaseUntil = now.plusSeconds(120), lastAttemptAt = now)
        val claim = OutboxProcessingClaim(event, deliveryCount)
        `when`(outbox.findById(eventId)).thenReturn(event)
        `when`(outbox.claimProcessing(eventId, now, now.plusSeconds(120))).thenReturn(claim)
        return claim
    }

    private fun event() =
        OutboxEvent(
            eventId,
            "CoordinationRun",
            UUID.randomUUID(),
            eventType,
            """{"event_id":"${eventId.value}","submission_batch_id":"${batchId.value}"}""",
            OutboxStatus.PUBLISHED,
            now.minusSeconds(5),
            now.minusSeconds(1),
        )
}
