package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.MalformedCoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class CoordinationWorkerTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val batchId = SubmissionBatchId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
    private val eventId = OutboxEventId(UUID.fromString("00000000-0000-0000-0000-000000000102"))

    @Test
    fun `동일 이벤트의 중복 Stream 전달은 처리기를 한 번만 실행한다`() {
        val repository = FakeOutboxRepository(event())
        val queue = FakeWorkQueue()
        var calls = 0
        val worker = worker(repository, queue) { _, _ -> calls++ }

        worker.process(message("1-0"))
        worker.process(message("2-0"))

        assertEquals(1, calls)
        assertEquals(listOf("1-0", "2-0"), queue.acknowledged)
        assertEquals(OutboxStatus.PROCESSED, repository.event.status)
    }

    @Test
    fun `ANALYSIS_DELAYED로 정상 종결된 작업도 처리 완료로 기록하고 ACK한다`() {
        val repository = FakeOutboxRepository(event())
        val queue = FakeWorkQueue()
        val worker = worker(repository, queue) { _, _ -> Unit }

        worker.process(message("1-0"))

        assertEquals(OutboxStatus.PROCESSED, repository.event.status)
        assertEquals(listOf("1-0"), queue.acknowledged)
        assertEquals(emptyList(), queue.deadLetters)
    }

    @Test
    fun `poison message는 다섯 번째 실패에서 영구 실패 기록 뒤 참조형 DLQ로 이동한다`() {
        val repository = FakeOutboxRepository(event())
        val sequence = mutableListOf<String>()
        val queue = FakeWorkQueue(sequence)
        val worker =
            CoordinationWorker(
                repository,
                queue,
                CoordinationEventProcessor { _, _ -> throw IllegalStateException("broken invariant") },
                DeadLetterPersistence { id, failure, _ ->
                    repository.markDeadLettered(id, now, failure)
                    sequence += "postgres"
                },
                Clock.fixed(now, ZoneOffset.UTC),
            )

        repeat(5) { index -> worker.process(message("${index + 1}-0")) }

        assertEquals(listOf("postgres", "redis", "ack"), sequence)
        assertEquals(OutboxStatus.DEAD_LETTERED, repository.event.status)
        assertEquals(
            listOf(DeadLetterMessage(eventId, batchId, "IllegalStateException", 5)),
            queue.deadLetters,
        )
        assertEquals(listOf("5-0"), queue.acknowledged)
    }

    @Test
    fun `역직렬화할 수 없는 메시지도 다섯 번째 전달에 PostgreSQL 기록 뒤 DLQ로 이동한다`() {
        val repository = FakeOutboxRepository(event())
        val sequence = mutableListOf<String>()
        val queue = FakeWorkQueue(sequence)
        val worker =
            CoordinationWorker(
                repository,
                queue,
                CoordinationEventProcessor { _, _ -> error("must not execute") },
                DeadLetterPersistence { _, _, _ -> error("valid persistence must not execute") },
                Clock.fixed(now, ZoneOffset.UTC),
                malformedDeadLetterPersistence =
                    MalformedDeadLetterPersistence { _, _, _, _, _ -> sequence += "postgres" },
            )

        worker.process(
            MalformedCoordinationWorkMessage("9-0", null, batchId, "DESERIALIZATION_FAILURE", 5),
        )

        assertEquals(listOf("postgres", "redis", "ack"), sequence)
        assertEquals(
            listOf(DeadLetterMessage(null, batchId, "DESERIALIZATION_FAILURE", 5)),
            queue.deadLetters,
        )
    }

    private fun worker(
        repository: FakeOutboxRepository,
        queue: FakeWorkQueue,
        processor: CoordinationEventProcessor,
    ) = CoordinationWorker(
        repository,
        queue,
        processor,
        DeadLetterPersistence { _, _, _ -> },
        Clock.fixed(now, ZoneOffset.UTC),
        Duration.ofMinutes(2),
    )

    private fun event() =
        OutboxEvent(
            id = eventId,
            aggregateType = "CoordinationRun",
            aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000103"),
            eventType = "SubmissionBatchStructuringRequested",
            payload = "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${batchId.value}\"}",
            status = OutboxStatus.PUBLISHED,
            occurredAt = now.minusSeconds(10),
            publishedAt = now.minusSeconds(5),
        )

    private fun message(recordId: String) = CoordinationWorkMessage(recordId, eventId, batchId, "SubmissionBatchStructuringRequested")

    private class FakeWorkQueue(
        val sequence: MutableList<String> = mutableListOf(),
    ) : CoordinationWorkQueuePort {
        val acknowledged = mutableListOf<String>()
        val deadLetters = mutableListOf<DeadLetterMessage>()

        override fun publish(event: OutboxEvent) = Unit

        override fun readNew(limit: Int) = emptyList<CoordinationWorkMessage>()

        override fun claimStale(
            minIdleTime: Duration,
            limit: Int,
        ) = emptyList<CoordinationWorkMessage>()

        override fun acknowledge(streamRecordId: String) {
            acknowledged += streamRecordId
            sequence += "ack"
        }

        override fun deadLetter(message: DeadLetterMessage) {
            deadLetters += message
            sequence += "redis"
        }
    }

    private class FakeOutboxRepository(
        initial: OutboxEvent,
    ) : OutboxRepository {
        var event = initial

        override fun insert(event: OutboxEvent) {
            this.event = event
        }

        override fun findById(id: OutboxEventId) = event.takeIf { it.id == id }

        override fun existsPending(
            aggregateId: UUID,
            eventType: String,
        ) = false

        override fun findPendingForUpdate(limit: Int) = emptyList<OutboxEvent>()

        override fun markPublished(
            eventId: OutboxEventId,
            at: Instant,
        ) = Unit

        override fun findRecoverablePublished(
            publishedBefore: Instant,
            limit: Int,
        ) = emptyList<OutboxEvent>()

        override fun markRepublished(
            eventId: OutboxEventId,
            at: Instant,
        ) = Unit

        override fun claimProcessing(
            eventId: OutboxEventId,
            now: Instant,
            leaseUntil: Instant,
        ): OutboxProcessingClaim? {
            if (event.status != OutboxStatus.PUBLISHED || event.processingLeaseUntil?.isAfter(now) == true) return null
            event = event.copy(processingLeaseUntil = leaseUntil, deliveryCount = event.deliveryCount + 1)
            return OutboxProcessingClaim(event, event.deliveryCount)
        }

        override fun markProcessed(
            eventId: OutboxEventId,
            at: Instant,
        ) {
            event = event.processed(at)
        }

        override fun releaseAfterFailure(
            eventId: OutboxEventId,
            failureKind: String,
        ) {
            event = event.failed(failureKind)
        }

        override fun markDeadLettered(
            eventId: OutboxEventId,
            at: Instant,
            failureKind: String,
        ) {
            event = event.deadLettered(at, failureKind)
        }

        override fun requeue(eventId: OutboxEventId) = Unit
    }
}
