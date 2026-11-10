package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Raw model bytes and bounded server-owned expansion are different public resource boundaries. */
class ServerEvidenceExpansionBoundaryTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val rawLimit = 262_144
    private val expansionLimit = 2_097_152

    @Test
    fun `SEB01 valid small reference response survives native quote expansion above the raw model byte limit`() {
        val f = Fixture()
        val json = mapper.writeValueAsString(f.document())
        assertTrue(json.toByteArray(Charsets.UTF_8).size < rawLimit)
        assertTrue(f.nativeQuoteByteLowerBound() > rawLimit)
        assertTrue(f.nativeQuoteByteLowerBound() < expansionLimit)
        val actual = parse(f, json)
        assertEquals(4, actual.base.base.declaration.programs.size)
        actual.base.base.declaration.programs.forEach { participant ->
            val program = assertNotNull(participant.program)
            assertEquals(
                24,
                program.nodes
                    .single { it.id == program.availabilityRoot }
                    .children.size,
            )
            assertTrue(participant.problems.isEmpty())
        }
        assertEquals(ServerEvidenceCatalog.VERSION, actual.evidenceCatalogVersion)
    }

    @Test
    fun `SEB02 the raw model response limit stays exact even when the additional bytes are legal JSON whitespace`() {
        val f = Fixture()
        val json = mapper.writeValueAsString(f.document())
        val remaining = rawLimit - json.toByteArray(Charsets.UTF_8).size
        assertTrue(remaining > 0)
        val exact = json + " ".repeat(remaining)
        assertEquals(rawLimit, exact.toByteArray(Charsets.UTF_8).size)
        assertEquals(
            4,
            parse(f, exact)
                .base.base.declaration.programs.size,
        )
        invalid { parse(f, exact + " ") }
    }

    @Test
    fun `SEB03 excessive trusted expansion is rejected explicitly without increasing the raw response allowance`() {
        val f = Fixture(referencesPerCondition = 16)
        val json = mapper.writeValueAsString(f.document())
        assertTrue(json.toByteArray(Charsets.UTF_8).size < rawLimit)
        assertTrue(f.nativeQuoteByteLowerBound() > expansionLimit)
        invalid { parse(f, json) }
    }

    private fun parse(
        fixture: Fixture,
        json: String,
    ) = ServerEvidenceProviderContract(mapper).parseProviderResponse(
        json,
        fixture.snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    private class Fixture(
        private val referencesPerCondition: Int = 1,
    ) {
        private val participants = 4
        private val conditions = 24
        private val prefix = "2027-01-08 10:00~12:00 가능. "
        private val raw = prefix + "가".repeat(500 - prefix.codePointCount(0, prefix.length))
        private val original = H.snapshot(H.input().copy(policy = N.policy))
        private val bindings =
            (1..participants).map { index ->
                val base = original.context.inputs.first()
                base.copy(
                    submissionVersionId = UUID.fromString("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
                    participantId = UUID.fromString("30000000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
                    contentSha256 = IrEvidenceBindings.sha256("Synthetic expansion input $index"),
                    sources =
                        listOf(
                            base.sources.single().copy(
                                codePointLength = raw.codePointCount(0, raw.length),
                                contentSha256 = IrEvidenceBindings.sha256(raw),
                            ),
                        ),
                )
            }
        val snapshot =
            ScheduleEvidenceSnapshot(
                original.context.copy(inputs = bindings),
                bindings.associate { it.submissionVersionId to mapOf("original" to raw) },
            )
        private val catalog = ServerEvidenceCatalog.create(snapshot)

        fun nativeQuoteByteLowerBound(): Int =
            participants * (conditions * referencesPerCondition + conditions + 1) * raw.toByteArray(Charsets.UTF_8).size

        fun document(): Map<String, Any?> =
            mapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "results" to
                    bindings.map { input ->
                        val proof =
                            catalog.entries
                                .single {
                                    it.inputId == input.submissionVersionId && it.kind == ServerEvidenceKind.CONTEXT && it.text == raw
                                }.id
                        mapOf(
                            "input_ref" to input.submissionVersionId.toString(),
                            "conditions" to
                                (1..conditions).map { index ->
                                    mapOf(
                                        "id" to "c$index",
                                        "effect" to "AVAILABLE",
                                        "interpretation" to "EXPLICIT",
                                        "temporal_resolution" to "PRECISE_INTERVAL",
                                        "evidence" to List(referencesPerCondition) { proof },
                                        "assertion" to "ASSERTED",
                                        "supersedes" to emptyList<String>(),
                                        "temporal" to
                                            mapOf(
                                                "kind" to "RAW_SCOPE",
                                                "scope" to
                                                    mapOf(
                                                        "dimension" to "TIME",
                                                        "start" to "2027-01-08T10:00",
                                                        "end" to "2027-01-08T12:00",
                                                    ),
                                            ),
                                    )
                                },
                            "program" to
                                mapOf(
                                    "nodes" to emptyList<Any>(),
                                    "availability_root" to
                                        expression(
                                            "ANY_OF",
                                            proof,
                                            children = (1..conditions).map { expression("CLAIM_REF", proof, "c$it") },
                                        ),
                                    "exclusion_root" to null,
                                    "preference_root" to null,
                                    "history_condition_ids" to emptyList<String>(),
                                ),
                            "approved_terms" to emptyList<Any>(),
                            "directional_times" to emptyList<Any>(),
                            "mode_restrictions" to emptyList<Any>(),
                        )
                    },
            )

        private fun expression(
            operator: String,
            proof: String,
            claim: String? = null,
            children: List<Map<String, Any?>> = emptyList(),
        ) = mapOf(
            "operator" to operator,
            "claim_id" to claim,
            "children" to children,
            "interpretation" to "EXPLICIT",
            "assertion" to "ASSERTED",
            "evidence" to listOf(proof),
        )
    }
}
