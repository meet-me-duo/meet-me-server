package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository.StoredDslDeclaration
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository.StoredContextModeEnvelope
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.DirectionalTimeApplication
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** Format7 remains readable; format8 records clock-free calendar-day derivations without raw quotes. */
class JdbcScheduleDslStructuralRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : ScheduleDslStructuralRepository {
    private val store = ScheduleIrJdbcDocumentStore(jdbc, mapper)
    private val reader =
        mapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
            .readerFor(StoredStructuralEnvelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publish(
        envelope: ScheduleDslStructuralEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        val context = envelope.context
        store.publish(
            context,
            envelope.semanticVersion,
            if (envelope.directionalApplications.any { it.declaration.direction == TemporalDirection.WHOLE_DAY }) 8 else 7,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleDslStructuralValidator().validateRestored(envelope, it) },
            findExisting = { find(context) },
            same = { it == envelope },
            encode = { mapper.writeValueAsString(StoredStructuralEnvelope.from(envelope)) },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleDslStructuralEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && stored.formatVersion in setOf(7, 8)) {
            "Unsupported stored structural version"
        }
        require(stored.fingerprint == store.fingerprint(context)) { "Structural fingerprint mismatch" }
        val envelope =
            try {
                reader.readValue<StoredStructuralEnvelope>(stored.json).toEnvelope()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored structural shape")
            }
        require(envelope.semanticVersion == stored.semanticVersion && envelope.context == context) { "Stored structural context mismatch" }
        require(
            (stored.formatVersion == 8) ==
                envelope.directionalApplications.any {
                    it.declaration.direction == TemporalDirection.WHOLE_DAY
                },
        ) { "Stored structural format does not match its derivations" }
        store.verifyReferences(context)
        return ScheduleDslStructuralValidator().validateRestored(envelope, store.loadSnapshot(context))
    }

    private data class StoredStructuralEnvelope(
        val semanticVersion: String,
        val astVersion: String,
        val context: ScheduleIrContext,
        val base: StoredContextModeEnvelope,
        val locatedEvidence: List<ScheduleDslLocatedEvidence>,
        val directionalApplications: List<DirectionalTimeApplication>,
        val calculationProjection: StoredDslDeclaration,
    ) {
        fun toEnvelope() =
            ScheduleDslStructuralEnvelope(
                semanticVersion,
                astVersion,
                context,
                base.toEnvelope(),
                locatedEvidence,
                directionalApplications,
                calculationProjection.toEnvelope(),
            )

        companion object {
            fun from(value: ScheduleDslStructuralEnvelope) =
                StoredStructuralEnvelope(
                    value.semanticVersion,
                    value.astVersion,
                    value.context,
                    StoredContextModeEnvelope.from(value.base),
                    value.locatedEvidence,
                    value.directionalApplications,
                    StoredDslDeclaration.from(value.calculationProjection),
                )
        }
    }
}
