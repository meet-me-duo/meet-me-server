package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** Explicit version-three codec; not registered in an operational component scan. */
class JdbcScheduleDslRepository(
    jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ScheduleDslRepository {
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
            .readerFor(StoredDslEnvelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publish(
        envelope: ScheduleDslEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        store.publish(
            envelope.context,
            envelope.semanticVersion,
            3,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleDslValidator().validateRestored(envelope, it) },
            findExisting = { find(envelope.context) },
            same = { it == envelope },
            encode = {
                objectMapper.writeValueAsString(
                    StoredDslEnvelope(envelope.semanticVersion, envelope.astVersion, envelope.claims, envelope.programs),
                )
            },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleDslEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && stored.formatVersion == 3) { "Unsupported stored IR version" }
        require(stored.fingerprint == store.fingerprint(context)) { "IR context fingerprint mismatch" }
        val envelope =
            try {
                envelopeReader.readValue<StoredDslEnvelope>(stored.json).toEnvelope()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored IR shape")
            }
        require(envelope.semanticVersion == stored.semanticVersion && envelope.context == context) { "Stored IR context mismatch" }
        store.verifyReferences(context)
        return ScheduleDslValidator().validateRestored(envelope, store.loadSnapshot(context))
    }

    private data class StoredDslEnvelope(
        val semanticVersion: String,
        val astVersion: String,
        val claims: com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope,
        val programs: List<com.meetme.server.submission.domain.dsl.ParticipantScheduleProgram>,
    ) {
        fun toEnvelope() = ScheduleDslEnvelope(semanticVersion, astVersion, claims, programs)
    }
}
