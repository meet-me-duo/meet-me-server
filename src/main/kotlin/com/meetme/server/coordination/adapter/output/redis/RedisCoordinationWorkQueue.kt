package com.meetme.server.coordination.adapter.output.redis

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.CoordinationQueueMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.MalformedCoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.QueueBacklog
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.springframework.data.domain.Range
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Component
class RedisCoordinationWorkQueue(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: ReliabilityProperties,
    private val clock: Clock,
) : CoordinationWorkQueuePort {
    override fun publish(event: OutboxEvent) {
        ensureConsumerGroup()
        val payload = objectMapper.readTree(event.payload)
        val batchId = requireNotNull(payload.path(BATCH_ID).stringValue()) { "Outbox batch reference is missing" }
        stream().add(
            properties.streamKey,
            mapOf(
                EVENT_ID to event.id.value.toString(),
                BATCH_ID to batchId,
                EVENT_TYPE to event.eventType,
            ),
        )
    }

    override fun readNew(limit: Int): List<CoordinationQueueMessage> {
        ensureConsumerGroup()
        return stream()
            .read(
                Consumer.from(properties.consumerGroup, properties.consumerName),
                StreamReadOptions.empty().count(limit.toLong()).block(Duration.ofMillis(100)),
                StreamOffset.create(properties.streamKey, ReadOffset.lastConsumed()),
            ).orEmpty()
            .map { record -> record.value.toMessage(record.id.value, 1) }
    }

    override fun claimStale(
        minIdleTime: Duration,
        limit: Int,
    ): List<CoordinationQueueMessage> {
        ensureConsumerGroup()
        val pending =
            stream().pending(
                properties.streamKey,
                properties.consumerGroup,
                Range.unbounded<String>(),
                limit.toLong(),
                minIdleTime,
            )
        if (pending.isEmpty) return emptyList()
        val deliveries = pending.associate { it.idAsString to (it.totalDeliveryCount + 1).toInt() }
        val ids: Array<RecordId> =
            pending
                .iterator()
                .asSequence()
                .map { it.id }
                .toList()
                .toTypedArray()
        return stream()
            .claim(properties.streamKey, properties.consumerGroup, properties.consumerName, minIdleTime, *ids)
            .map { record -> record.value.toMessage(record.id.value, deliveries[record.id.value] ?: 1) }
    }

    override fun acknowledge(streamRecordId: String) {
        stream().acknowledge(properties.streamKey, properties.consumerGroup, streamRecordId)
        stream().delete(properties.streamKey, streamRecordId)
    }

    override fun deadLetter(message: DeadLetterMessage) {
        val body =
            linkedMapOf(
                FAILURE_KIND to message.failureKind,
                DELIVERY_COUNT to message.deliveryCount.toString(),
            ).apply {
                message.eventId?.let { put(EVENT_ID, it.value.toString()) }
                message.batchId?.let { put(BATCH_ID, it.value.toString()) }
            }
        stream().add(
            properties.deadLetterStreamKey,
            body,
        )
        val cutoffId = RecordId.of("${clock.instant().minus(properties.dlqRetention).toEpochMilli()}-0")
        stream().trim(
            properties.deadLetterStreamKey,
            RedisStreamCommands.XTrimOptions.of(
                RedisStreamCommands.TrimOptions.minId(cutoffId).approximate(),
            ),
        )
        stream().trim(properties.deadLetterStreamKey, properties.dlqMaxEntries, true)
    }

    override fun backlog(): QueueBacklog {
        ensureConsumerGroup()
        val summary = stream().pending(properties.streamKey, properties.consumerGroup)
        if (summary.totalPendingMessages == 0L) return QueueBacklog(0, 0)
        val oldest =
            stream()
                .pending(properties.streamKey, properties.consumerGroup, Range.unbounded<String>(), 1)
                .firstOrNull()
                ?.elapsedTimeSinceLastDelivery
                ?.seconds
                ?: 0
        return QueueBacklog(summary.totalPendingMessages, oldest)
    }

    private fun ensureConsumerGroup() {
        if (redisTemplate.hasKey(properties.streamKey) != true) {
            val bootstrap = stream().add(properties.streamKey, mapOf("bootstrap" to "true"))
            createGroupIgnoringExisting()
            bootstrap?.let { stream().delete(properties.streamKey, it) }
            return
        }
        createGroupIgnoringExisting()
    }

    private fun createGroupIgnoringExisting() {
        try {
            stream().createGroup(properties.streamKey, ReadOffset.from("0-0"), properties.consumerGroup)
        } catch (exception: RedisSystemException) {
            val busyGroup = generateSequence<Throwable>(exception) { it.cause }.any { it.message.orEmpty().contains("BUSYGROUP") }
            if (!busyGroup) throw exception
        }
    }

    private fun Map<String, String>.toMessage(
        streamRecordId: String,
        deliveryCount: Int,
    ): CoordinationQueueMessage {
        val eventId = this[EVENT_ID]?.let { runCatching { OutboxEventId(UUID.fromString(it)) }.getOrNull() }
        val batchId = this[BATCH_ID]?.let { runCatching { SubmissionBatchId(UUID.fromString(it)) }.getOrNull() }
        val eventType = this[EVENT_TYPE]?.takeIf(String::isNotBlank)
        if (eventId == null || batchId == null || eventType == null) {
            return MalformedCoordinationWorkMessage(
                streamRecordId,
                eventId,
                batchId,
                DESERIALIZATION_FAILURE,
                deliveryCount,
            )
        }
        return CoordinationWorkMessage(streamRecordId, eventId, batchId, eventType, deliveryCount)
    }

    private fun stream() = redisTemplate.opsForStream<String, String>()

    private companion object {
        const val EVENT_ID = "event_id"
        const val BATCH_ID = "submission_batch_id"
        const val EVENT_TYPE = "event_type"
        const val FAILURE_KIND = "failure_kind"
        const val DELIVERY_COUNT = "delivery_count"
        const val DESERIALIZATION_FAILURE = "DESERIALIZATION_FAILURE"
    }
}
