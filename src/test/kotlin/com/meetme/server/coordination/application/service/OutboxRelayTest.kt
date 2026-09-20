package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.shared.adapter.output.observability.MicrometerApplicationMetrics
import com.meetme.server.shared.domain.OutboxEventId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class OutboxRelayTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")

    @Test
    fun `Pending Outbox를 발행한 뒤에만 PUBLISHED로 기록한다`() {
        val event = pendingEvent()
        val repository = FakeRepository(mutableListOf(event))
        val queue = FakeQueue()
        val relay = relay(repository, queue)

        assertEquals(1, relay.relayPending())

        assertEquals(listOf(event.id), queue.published)
        assertEquals(listOf(event.id), repository.published)
    }

    @Test
    fun `Redis 유실을 가정해 오래된 PUBLISHED 이벤트를 같은 참조로 재발행한다`() {
        val event =
            pendingEvent().copy(
                status = com.meetme.server.coordination.application.port.output.OutboxStatus.PUBLISHED,
                publishedAt = now.minusSeconds(180),
            )
        val repository = FakeRepository(mutableListOf(event))
        val queue = FakeQueue()
        val relay = relay(repository, queue)

        assertEquals(1, relay.recoverPublished())

        assertEquals(listOf(event.id), queue.published)
        assertEquals(listOf(event.id), repository.republished)
    }

    private fun relay(
        repository: OutboxRepository,
        queue: CoordinationWorkQueuePort,
    ) = OutboxRelay(
        repository,
        queue,
        ReliabilityProperties(recoveryAge = Duration.ofMinutes(2)),
        Clock.fixed(now, ZoneOffset.UTC),
        MicrometerApplicationMetrics(SimpleMeterRegistry()),
    )

    private fun pendingEvent() =
        OutboxEvent(
            OutboxEventId(UUID.randomUUID()),
            "CoordinationRun",
            UUID.randomUUID(),
            "SubmissionBatchStructuringRequested",
            "{\"submission_batch_id\":\"${UUID.randomUUID()}\"}",
            occurredAt = now.minusSeconds(300),
        )

    private class FakeQueue : CoordinationWorkQueuePort {
        val published = mutableListOf<OutboxEventId>()

        override fun publish(event: OutboxEvent) {
            published += event.id
        }

        override fun readNew(limit: Int) = emptyList<CoordinationWorkMessage>()

        override fun claimStale(
            minIdleTime: Duration,
            limit: Int,
        ) = emptyList<CoordinationWorkMessage>()

        override fun acknowledge(streamRecordId: String) = Unit

        override fun deadLetter(message: DeadLetterMessage) = Unit
    }

    private class FakeRepository(
        private val events: MutableList<OutboxEvent>,
    ) : OutboxRepository {
        val published = mutableListOf<OutboxEventId>()
        val republished = mutableListOf<OutboxEventId>()

        override fun insert(event: OutboxEvent) {
            events += event
        }

        override fun findById(id: OutboxEventId) = events.firstOrNull { it.id == id }

        override fun existsPending(
            aggregateId: UUID,
            eventType: String,
        ) = false

        override fun findPendingForUpdate(limit: Int) = events.filter { it.status.name == "PENDING" }.take(limit)

        override fun markPublished(
            eventId: OutboxEventId,
            at: Instant,
        ) {
            published += eventId
        }

        override fun findRecoverablePublished(
            publishedBefore: Instant,
            limit: Int,
        ) = events.filter { it.status.name == "PUBLISHED" && it.publishedAt!! <= publishedBefore }.take(limit)

        override fun markRepublished(
            eventId: OutboxEventId,
            at: Instant,
        ) {
            republished += eventId
        }

        override fun claimProcessing(
            eventId: OutboxEventId,
            now: Instant,
            leaseUntil: Instant,
        ): OutboxProcessingClaim? = null

        override fun markProcessed(
            eventId: OutboxEventId,
            at: Instant,
        ) = Unit

        override fun releaseAfterFailure(
            eventId: OutboxEventId,
            failureKind: String,
        ) = Unit

        override fun markDeadLettered(
            eventId: OutboxEventId,
            at: Instant,
            failureKind: String,
        ) = Unit

        override fun requeue(eventId: OutboxEventId) = Unit
    }
}
