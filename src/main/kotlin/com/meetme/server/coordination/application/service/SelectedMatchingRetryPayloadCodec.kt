package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedMatchingRetryAuthorization
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Closed server payload. The current DB event and history must still be verified by its consumer. */
class SelectedMatchingRetryPayloadCodec(
    private val mapper: ObjectMapper,
) {
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun encode(value: SelectedMatchingRetryAuthorization): String =
        checked {
            require(value.matchingRunVersion >= 0 && value.routeFingerprint.matches(FINGERPRINT))
            mapper.writeValueAsString(
                linkedMapOf(
                    "event_id" to value.eventId.value.toString(),
                    "submission_batch_id" to value.batchId.value.toString(),
                    "matching_run_version" to value.matchingRunVersion,
                    "source_invocation_id" to value.sourceInvocationId.toString(),
                    "source_winner_attempt_id" to value.sourceWinnerAttemptId.toString(),
                    "route_fingerprint" to value.routeFingerprint,
                ),
            )
        }

    fun decode(event: OutboxEvent): SelectedMatchingRetryAuthorization =
        checked {
            require(
                event.status == OutboxStatus.PUBLISHED &&
                    event.aggregateType == "CoordinationRun" &&
                    event.eventType == CollectionClosureService.MATCHING_REQUESTED,
            )
            require(event.payload.toByteArray(Charsets.UTF_8).size <= 4096)
            val fields = reader.readValue<Map<*, *>>(event.payload)
            require(fields.keys == FIELDS)
            val version = fields["matching_run_version"]
            require(version is Int || version is Long)
            val number = (version as Number).toLong()
            require(number >= 0)
            val eventId = OutboxEventId(uuid(fields["event_id"]))
            require(eventId == event.id)
            val fingerprint = fields["route_fingerprint"] as? String ?: invalid()
            require(fingerprint.matches(FINGERPRINT))
            SelectedMatchingRetryAuthorization(
                eventId,
                CoordinationRunId(event.aggregateId),
                SubmissionBatchId(uuid(fields["submission_batch_id"])),
                number,
                uuid(fields["source_invocation_id"]),
                uuid(fields["source_winner_attempt_id"]),
                fingerprint,
                event.occurredAt,
            )
        }

    private fun uuid(value: Any?): UUID {
        val text = value as? String ?: invalid()
        return UUID.fromString(text).also { require(it.toString() == text) }
    }

    private fun <T> checked(block: () -> T): T =
        try {
            block()
        } catch (_: RuntimeException) {
            invalid()
        }

    private fun invalid(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)

    companion object {
        private val FINGERPRINT = Regex("[a-f0-9]{64}")
        private val FIELDS =
            setOf(
                "event_id",
                "submission_batch_id",
                "matching_run_version",
                "source_invocation_id",
                "source_winner_attempt_id",
                "route_fingerprint",
            )
    }
}
