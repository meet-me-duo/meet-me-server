package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository.StoredApprovedTermsEnvelope
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository.StoredDslDeclaration
import com.meetme.server.submission.application.port.output.ScheduleDslContextModeRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslModelContextApplication
import com.meetme.server.submission.domain.dsl.ScheduleModeRestriction
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class JdbcScheduleDslContextModeRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : ScheduleDslContextModeRepository {
    private val store = ScheduleIrJdbcDocumentStore(jdbc, mapper)
    private val reader =
        mapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
            .readerFor(StoredContextModeEnvelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publish(
        envelope: ScheduleDslContextModeEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        val context = envelope.base.declaration.context
        store.publish(
            context,
            envelope.semanticVersion,
            6,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleDslContextModeValidator().validateRestored(envelope, it) },
            findExisting = { find(context) },
            same = { it == envelope },
            encode = { mapper.writeValueAsString(StoredContextModeEnvelope.from(envelope)) },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleDslContextModeEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION && stored.formatVersion == 6) {
            "Unsupported stored context-mode version"
        }
        require(stored.fingerprint == store.fingerprint(context)) { "Context-mode fingerprint mismatch" }
        val envelope =
            try {
                reader.readValue<StoredContextModeEnvelope>(stored.json).toEnvelope()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored context-mode shape")
            }
        require(
            envelope.semanticVersion == stored.semanticVersion &&
                envelope.base.declaration.context == context &&
                envelope.calculationProjection.context == context,
        ) {
            "Stored context-mode context mismatch"
        }
        store.verifyReferences(context)
        return ScheduleDslContextModeValidator().validateRestored(envelope, store.loadSnapshot(context))
    }

    /** Version6 reuses explicit C3 declaration shapes, never derived context getters or raw quotes. */
    internal data class StoredContextModeEnvelope(
        val semanticVersion: String,
        val astVersion: String,
        val base: StoredApprovedTermsEnvelope,
        val calculationProjection: StoredDslDeclaration,
        val contextApplications: List<ScheduleDslModelContextApplication>,
        val modeRestrictions: List<ScheduleModeRestriction>,
    ) {
        fun toEnvelope() =
            ScheduleDslContextModeEnvelope(
                semanticVersion,
                astVersion,
                base.toEnvelope(),
                calculationProjection.toEnvelope(),
                contextApplications,
                modeRestrictions,
            )

        companion object {
            fun from(envelope: ScheduleDslContextModeEnvelope) =
                StoredContextModeEnvelope(
                    envelope.semanticVersion,
                    envelope.astVersion,
                    StoredApprovedTermsEnvelope.from(envelope.base),
                    StoredDslDeclaration.from(envelope.calculationProjection),
                    envelope.contextApplications,
                    envelope.modeRestrictions,
                )
        }
    }
}
