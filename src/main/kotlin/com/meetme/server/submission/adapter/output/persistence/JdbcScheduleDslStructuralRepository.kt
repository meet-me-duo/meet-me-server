package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository.StoredDslDeclaration
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository.StoredContextModeEnvelope
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.DirectionalTimeApplication
import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslPlaceConstraint
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.dsl.WeeklyScheduleApplication
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRules
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** Formats7/8 keep their shape; format9 additionally stores exact condition-scoped term provenance. */
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
            format(envelope),
            expectedRunVersion,
            processedAt,
            validate = { ScheduleDslStructuralValidator().validateRestored(envelope, it) },
            findExisting = { find(context) },
            same = { it == envelope },
            encode = { encode(envelope) },
        )
    }

    override fun find(context: ScheduleIrContext): ScheduleDslStructuralEnvelope? {
        val stored = store.readMetadata(context.runId) ?: return null
        require(stored.semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && stored.formatVersion in setOf(7, 8, 9, 10, 11, 12)) {
            "Unsupported stored structural version"
        }
        require(stored.fingerprint == store.fingerprint(context)) { "Structural fingerprint mismatch" }
        val shape = mapper.readTree(stored.json)
        if (stored.formatVersion in setOf(11, 12)) {
            require(
                shape.path("evidenceCatalogVersion").stringValue() == ServerEvidenceCatalog.VERSION,
            ) { "Missing stored catalog version" }
        } else {
            require(!shape.has("evidenceCatalogVersion")) { "Catalog format downgrade" }
        }
        if (stored.formatVersion == 10 || (stored.formatVersion in setOf(11, 12) && shape.has("placeConstraintsVersion"))) {
            require(
                shape.path("placeConstraints").isArray &&
                    shape.path("placeConstraintsVersion").stringValue() == SCHEDULE_PLACE_CONSTRAINT_VERSION,
            ) { "Missing stored place declarations" }
        } else {
            require(!shape.has("placeConstraints") && !shape.has("placeConstraintsVersion")) { "Place format downgrade" }
        }
        if (stored.formatVersion == 12) {
            require(
                shape.path("weeklyRulesVersion").stringValue() == WeeklyScheduleRules.VERSION && shape.path("weeklyApplications").isArray,
            ) {
                "Missing stored weekly declarations"
            }
        } else {
            require(!shape.has("weeklyRulesVersion") && !shape.has("weeklyApplications")) { "Weekly format downgrade" }
        }
        val envelope =
            try {
                reader.readValue<StoredStructuralEnvelope>(stored.json).toEnvelope()
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored structural shape")
            }
        require(envelope.semanticVersion == stored.semanticVersion && envelope.context == context) { "Stored structural context mismatch" }
        require(stored.formatVersion == format(envelope)) { "Stored structural format does not match its derivations" }
        store.verifyReferences(context)
        return ScheduleDslStructuralValidator().validateRestored(envelope, store.loadSnapshot(context))
    }

    private fun format(envelope: ScheduleDslStructuralEnvelope): Int =
        when {
            envelope.weeklyRulesVersion != null -> 12
            envelope.evidenceCatalogVersion != null -> 11
            envelope.placeConstraintsVersion != null -> 10
            envelope.locatedEvidence.any { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE } -> 9
            envelope.directionalApplications.any { it.declaration.direction == TemporalDirection.WHOLE_DAY } -> 8
            else -> 7
        }

    private fun encode(envelope: ScheduleDslStructuralEnvelope): String {
        val tree = mapper.valueToTree<tools.jackson.databind.JsonNode>(StoredStructuralEnvelope.from(envelope))
        if (envelope.weeklyRulesVersion == null) {
            (tree as ObjectNode).remove("weeklyApplications")
            tree.remove("weeklyRulesVersion")
        }
        if (envelope.evidenceCatalogVersion == null) (tree as ObjectNode).remove("evidenceCatalogVersion")
        if (envelope.placeConstraintsVersion == null) {
            (tree as ObjectNode).remove("placeConstraints")
            tree.remove("placeConstraintsVersion")
        }

        fun omitAbsentContext(node: tools.jackson.databind.JsonNode) {
            if (node is ObjectNode) {
                if (node.path("contextSpan").isNull) node.remove("contextSpan")
                if (node.path("catalogId").isNull) node.remove("catalogId")
            }
            node.values().forEach(::omitAbsentContext)
        }
        // Preserve the original format7/8 witness fields for old readers, including nested directional evidence.
        omitAbsentContext(tree)
        return mapper.writeValueAsString(tree)
    }

    private data class StoredStructuralEnvelope(
        val semanticVersion: String,
        val astVersion: String,
        val context: ScheduleIrContext,
        val base: StoredContextModeEnvelope,
        val locatedEvidence: List<ScheduleDslLocatedEvidence>,
        val directionalApplications: List<DirectionalTimeApplication>,
        val calculationProjection: StoredDslDeclaration,
        val placeConstraints: List<ScheduleDslPlaceConstraint> = emptyList(),
        val placeConstraintsVersion: String? = null,
        val evidenceCatalogVersion: String? = null,
        val weeklyApplications: List<WeeklyScheduleApplication> = emptyList(),
        val weeklyRulesVersion: String? = null,
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
                placeConstraints,
                placeConstraintsVersion,
                evidenceCatalogVersion,
                weeklyApplications,
                weeklyRulesVersion,
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
                    value.placeConstraints,
                    value.placeConstraintsVersion,
                    value.evidenceCatalogVersion,
                    value.weeklyApplications,
                    value.weeklyRulesVersion,
                )
        }
    }
}
