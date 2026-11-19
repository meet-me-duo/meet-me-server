package com.meetme.server.coordination.adapter.output.redis

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class RedisPendingFairnessTest {
    private val template = redisTemplate()
    private val suffix = UUID.randomUUID().toString()
    private val properties =
        ReliabilityProperties(
            streamKey = "test:coordination:$suffix",
            consumerGroup = "test-workers-$suffix",
            consumerName = "test-consumer",
            deadLetterStreamKey = "test:coordination:dlq:$suffix",
        )
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val queue = RedisCoordinationWorkQueue(template, ObjectMapper(), properties, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `SR10 pending scan reaches later messages even when earlier unACKed messages stay pending`() {
        repeat(25) { queue.publish(event().copy(id = OutboxEventId(UUID.randomUUID()))) }
        val original = queue.readNew(30).map { it.streamRecordId }.toSet()
        assertEquals(25, original.size)
        val seen = mutableSetOf<String>()
        repeat(3) {
            Thread.sleep(20)
            seen += queue.claimStale(Duration.ofMillis(1), 10).map { it.streamRecordId }
        }
        assertEquals(original, seen, "One bounded sweep must reach all PEL pages without ACKing earlier entries")
        assertEquals(25, queue.backlog().pendingCount)
    }

    private fun event() =
        OutboxEvent(
            id = eventId,
            aggregateType = "CoordinationRun",
            aggregateId = UUID.randomUUID(),
            eventType = "SubmissionBatchStructuringRequested",
            payload =
                """{"event_id":"${eventId.value}","submission_batch_id":"${batchId.value}","ignored":"민감한 원문"}""",
            status = OutboxStatus.PENDING,
            occurredAt = now,
        )

    private fun redisTemplate(): StringRedisTemplate {
        val factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
        factory.afterPropertiesSet()
        return StringRedisTemplate(factory).also { it.afterPropertiesSet() }
    }

    companion object {
        private val eventId = OutboxEventId(UUID.fromString("00000000-0000-0000-0000-000000000201"))
        private val batchId = SubmissionBatchId(UUID.fromString("00000000-0000-0000-0000-000000000202"))
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379)

        init {
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = redis.stop()
    }
}
