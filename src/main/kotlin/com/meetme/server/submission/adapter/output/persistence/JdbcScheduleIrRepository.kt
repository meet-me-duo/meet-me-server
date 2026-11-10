package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.application.port.output.ScheduleIrRepository
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrValidator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** Version-one codec and wire shape are preserved; SQL mechanics are shared explicitly. */
@Repository
class JdbcScheduleIrRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ScheduleIrRepository {
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
            .readerFor(ScheduleIrEnvelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publish(
        envelope: ScheduleIrEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        store.publish(
            envelope.context,
            envelope.semanticVersion,
            1,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleIrValidator().validateRestored(envelope, it) },
            findExisting = { find(envelope.context) },
            same = { it == envelope },
            encode = { objectMapper.writeValueAsString(envelope) },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleIrEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_IR_VERSION && stored.formatVersion == 1) { "Unsupported stored IR version" }
        require(stored.fingerprint == store.fingerprint(context)) { "IR context fingerprint mismatch" }
        val envelope =
            try {
                envelopeReader.readValue<ScheduleIrEnvelope>(stored.json)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored IR shape")
            }
        require(envelope.semanticVersion == stored.semanticVersion && envelope.context == context) { "Stored IR context mismatch" }
        store.verifyReferences(context)
        return ScheduleIrValidator().validateRestored(envelope, store.loadSnapshot(context))
    }
}
