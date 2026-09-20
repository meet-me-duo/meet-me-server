package com.meetme.server.coordination.adapter.output.redis

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.MalformedCoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
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
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RedisCoordinationWorkQueueTest {
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
    fun `Outbox 참조만 Stream으로 전달하고 ACK 뒤 원본 메시지를 제거한다`() {
        val event = event()

        queue.publish(event)
        val messages = queue.readNew(10)

        assertEquals(1, messages.size)
        val message = assertIs<CoordinationWorkMessage>(messages.single())
        assertEquals(event.id, message.eventId)
        assertEquals(batchId, message.batchId)
        val streamBody =
            template
                .opsForStream<String, String>()
                .range(properties.streamKey, Range.unbounded())
                .single()
                .value
        assertFalse(streamBody.values.any { it.contains("민감한 원문") })

        queue.acknowledge(messages.single().streamRecordId)
        assertEquals(0, template.opsForStream<String, String>().size(properties.streamKey))
    }

    @Test
    fun `DLQ에는 이벤트 배치 실패 분류와 전달 횟수만 저장한다`() {
        queue.deadLetter(DeadLetterMessage(eventId, batchId, "InvariantViolation", 5))

        val body =
            template
                .opsForStream<String, String>()
                .range(properties.deadLetterStreamKey, Range.unbounded())
                .single()
                .value
        assertEquals(
            setOf("event_id", "submission_batch_id", "failure_kind", "delivery_count"),
            body.keys,
        )
        assertFalse(body.values.any { it.contains("민감한 원문") })
    }

    @Test
    fun `ACK되지 않은 Pending 작업은 유휴 시간 뒤 새 consumer가 회수한다`() {
        queue.publish(event())
        val delivered = assertIs<CoordinationWorkMessage>(queue.readNew(10).single())
        Thread.sleep(20)

        val claimed = queue.claimStale(Duration.ofMillis(1), 10)

        assertEquals(delivered.eventId, assertIs<CoordinationWorkMessage>(claimed.single()).eventId)
        queue.acknowledge(claimed.single().streamRecordId)
    }

    @Test
    fun `UUID를 역직렬화할 수 없는 Stream entry를 poison message로 분류한다`() {
        queue.publish(event())
        queue.readNew(10).forEach { queue.acknowledge(it.streamRecordId) }
        template.opsForStream<String, String>().add(
            properties.streamKey,
            mapOf(
                "event_id" to "not-a-uuid",
                "submission_batch_id" to batchId.value.toString(),
                "event_type" to "SubmissionBatchStructuringRequested",
            ),
        )

        val malformed = assertIs<MalformedCoordinationWorkMessage>(queue.readNew(10).single())

        assertEquals("DESERIALIZATION_FAILURE", malformed.failureKind)
        assertEquals(null, malformed.eventId)
        assertEquals(batchId, malformed.batchId)
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
