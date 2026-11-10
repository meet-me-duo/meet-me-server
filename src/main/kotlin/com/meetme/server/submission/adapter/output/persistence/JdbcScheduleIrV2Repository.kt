package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.application.port.output.ScheduleIrV2Repository
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** Explicit version-two codec; not registered in an operational component scan. */
class JdbcScheduleIrV2Repository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ScheduleIrV2Repository {
    private val store = ScheduleIrJdbcDocumentStore(jdbcTemplate, objectMapper)
    private val strictMapper =
        objectMapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
    private val envelopeReader =
        strictMapper
            .readerFor(ScheduleIrV2Envelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publishV2(
        envelope: ScheduleIrV2Envelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        store.publish(
            envelope.context,
            envelope.semanticVersion,
            2,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleIrV2Validator().validateRestored(envelope, it) },
            findExisting = { findV2(envelope.context) },
            same = { it == envelope },
            encode = { objectMapper.writeValueAsString(envelope) },
        )
    }

    override fun findV2(context: ScheduleIrContext): ScheduleIrV2Envelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_IR_V2_VERSION && stored.formatVersion == 2) { "Unsupported stored IR version" }
        require(stored.fingerprint == store.fingerprint(context)) { "IR context fingerprint mismatch" }
        val envelope =
            try {
                envelopeReader.readValue<ScheduleIrV2Envelope>(stored.json)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored IR shape")
            }
        require(envelope.semanticVersion == stored.semanticVersion && envelope.context == context) { "Stored IR context mismatch" }
        store.verifyReferences(context)
        return ScheduleIrV2Validator().validateRestored(envelope, store.loadSnapshot(context))
    }
}
