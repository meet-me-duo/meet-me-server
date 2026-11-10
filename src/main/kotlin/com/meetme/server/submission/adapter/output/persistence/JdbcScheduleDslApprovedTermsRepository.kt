package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermApplication
import com.meetme.server.submission.domain.dsl.ParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.RejectedPreferenceWitness
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

class JdbcScheduleDslApprovedTermsRepository(
    jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : ScheduleDslApprovedTermsRepository {
    private val store = ScheduleIrJdbcDocumentStore(jdbc, mapper)
    private val strictMapper =
        mapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
    private val reader =
        strictMapper
            .readerFor(StoredApprovedTermsEnvelope::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun publish(
        envelope: ScheduleDslApprovedTermsEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    ) {
        val context = envelope.declaration.context
        store.publish(
            context,
            envelope.semanticVersion,
            5,
            expectedRunVersion,
            processedAt,
            validate = { ScheduleDslApprovedTermsValidator().validateRestored(envelope, it) },
            findExisting = { find(context) },
            same = { it == envelope },
            encode = { mapper.writeValueAsString(StoredApprovedTermsEnvelope.from(envelope)) },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleDslApprovedTermsEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION && stored.formatVersion == 5) {
            "Unsupported stored approved term version"
        }
        require(stored.fingerprint == store.fingerprint(context)) { "Approved term context fingerprint mismatch" }
        val envelope =
            try {
                reader.readValue<StoredApprovedTermsEnvelope>(stored.json).toEnvelope()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored approved term shape")
            }
        require(
            envelope.semanticVersion == stored.semanticVersion &&
                envelope.declaration.context == context &&
                envelope.calculationProjection.context == context,
        ) { "Stored approved term context mismatch" }
        store.verifyReferences(context)
        return ScheduleDslApprovedTermsValidator().validateRestored(envelope, store.loadSnapshot(context))
    }

    /** Explicit DTOs omit derived context accessors and never store raw evidence quotes. */
    internal data class StoredDslDeclaration(
        val semanticVersion: String,
        val astVersion: String,
        val claims: ScheduleIrV2Envelope,
        val programs: List<ParticipantScheduleProgram>,
        val rejectedPreferenceWitnesses: List<RejectedPreferenceWitness>,
    ) {
        fun toEnvelope() = ScheduleDslEnvelope(semanticVersion, astVersion, claims, programs, rejectedPreferenceWitnesses)

        companion object {
            fun from(envelope: ScheduleDslEnvelope) =
                StoredDslDeclaration(
                    envelope.semanticVersion,
                    envelope.astVersion,
                    envelope.claims,
                    envelope.programs,
                    envelope.rejectedPreferenceWitnesses,
                )
        }
    }

    internal data class StoredApprovedTermsEnvelope(
        val semanticVersion: String,
        val astVersion: String,
        val vocabularyVersion: String,
        val declaration: StoredDslDeclaration,
        val calculationProjection: StoredDslDeclaration,
        val termApplications: List<ApprovedTimeTermApplication>,
    ) {
        fun toEnvelope() =
            ScheduleDslApprovedTermsEnvelope(
                semanticVersion,
                astVersion,
                vocabularyVersion,
                declaration.toEnvelope(),
                calculationProjection.toEnvelope(),
                termApplications,
            )

        companion object {
            fun from(envelope: ScheduleDslApprovedTermsEnvelope) =
                StoredApprovedTermsEnvelope(
                    envelope.semanticVersion,
                    envelope.astVersion,
                    envelope.vocabularyVersion,
                    StoredDslDeclaration.from(envelope.declaration),
                    StoredDslDeclaration.from(envelope.calculationProjection),
                    envelope.termApplications,
                )
        }
    }
}
