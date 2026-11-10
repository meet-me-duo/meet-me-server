package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.dsl.IndexedEvidenceSelection
import com.meetme.server.submission.domain.dsl.MeetingDateScopePolicy
import com.meetme.server.submission.domain.dsl.MeetingMonthCalendarFacts
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

/** Raw model bytes, resolved native identities and native quote expansion have independent budgets. */
class IndexedEvidenceExpansionBoundaryTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val rawLimit = 262_144

    @Test
    fun `IEB01 a small indexed response over the resolved identity budget reports the exact safe boundary code`() {
        val f = Fixture(referencesPerField = 32)
        val document = f.document()
        val wire = mapper.writeValueAsString(document)
        val resolved = mapper.writeValueAsString(f.resolve(document, nativeQuotes = false))
        val native = mapper.writeValueAsString(f.resolve(document, nativeQuotes = true))
        assertTrue(wire.toByteArray(Charsets.UTF_8).size < rawLimit)
        assertTrue(resolved.toByteArray(Charsets.UTF_8).size > rawLimit)
        assertTrue(native.toByteArray(Charsets.UTF_8).size < 2_097_152)
        assertTrue(f.referenceCount > 5_000)
        val failure = assertFailsWith<NaturalLanguageParserException> { parse(f, wire) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertEquals("INDEXED_EVIDENCE_RESOLVED_BYTE_LIMIT", failure.cause?.message)
    }

    @Test
    fun `IEB02 the same valid owned graph under all budgets resolves normally without losing branches`() {
        val f = Fixture(referencesPerField = 1)
        val document = f.document()
        val wire = mapper.writeValueAsString(document)
        assertTrue(wire.toByteArray(Charsets.UTF_8).size < rawLimit)
        assertTrue(mapper.writeValueAsBytes(f.resolve(document, nativeQuotes = false)).size < rawLimit)
        val actual = parse(f, wire)
        assertEquals(4, actual.base.base.declaration.programs.size)
        actual.base.base.declaration.programs.forEach { participant ->
            val program = assertNotNull(participant.program)
            assertTrue(participant.problems.isEmpty())
            assertEquals(
                24,
                program.nodes
                    .single { it.id == program.availabilityRoot }
                    .children.size,
            )
        }
    }

    @Test
    fun `IEB03 model whitespace counts toward the unchanged raw limit and retains its original error code`() {
        val f = Fixture(referencesPerField = 1)
        val wire = mapper.writeValueAsString(f.document())
        val exact = wire + " ".repeat(rawLimit - wire.toByteArray(Charsets.UTF_8).size)
        assertEquals(rawLimit, exact.toByteArray(Charsets.UTF_8).size)
        assertEquals(
            4,
            parse(f, exact)
                .base.base.declaration.programs.size,
        )
        val failure = assertFailsWith<NaturalLanguageParserException> { parse(f, exact + " ") }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertEquals("INDEXED_EVIDENCE_RAW_BYTE_LIMIT", failure.cause?.message)
    }

    private fun parse(
        f: Fixture,
        json: String,
    ) = IndexedEvidenceProviderContract(mapper).parseProviderResponse(
        json,
        f.snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private class Fixture(
        private val referencesPerField: Int,
    ) {
        private val participants = 4
        private val conditions = 24
        private val raw = "2027-01-08 10:00~12:00 가능."
        private val original = H.snapshot(H.input().copy(policy = N.policy))
        private val bindings =
            (1..participants).map { index ->
                val base = original.context.inputs.first()
                base.copy(
                    submissionVersionId = UUID.fromString("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
                    participantId = UUID.fromString("30000000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
                    contentSha256 = IrEvidenceBindings.sha256("Synthetic indexed boundary input $index"),
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
        val referenceCount = participants * (conditions * 2 + 1) * referencesPerField

        fun document(): Map<String, Any?> =
            mapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "date_scope_version" to MeetingDateScopePolicy.VERSION,
                "calendar_facts_version" to MeetingMonthCalendarFacts.VERSION,
                "evidence_selection_version" to IndexedEvidenceSelection.VERSION,
                "results" to
                    bindings.map { input ->
                        val selection =
                            catalog.entries.indexOfFirst {
                                it.inputId == input.submissionVersionId && it.kind == ServerEvidenceKind.CONTEXT && it.text == raw
                            }
                        check(selection >= 0)
                        mapOf(
                            "input_ref" to input.submissionVersionId.toString(),
                            "conditions" to
                                (1..conditions).map { index ->
                                    mapOf(
                                        "id" to "c$index",
                                        "effect" to "AVAILABLE",
                                        "interpretation" to "EXPLICIT",
                                        "temporal_resolution" to "PRECISE_INTERVAL",
                                        "evidence" to List(referencesPerField) { selection },
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
                                            selection,
                                            children = (1..conditions).map { expression("CLAIM_REF", selection, "c$it") },
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

        /** Independent public catalog lookup for measuring each representation, not an implementation parser. */
        fun resolve(
            document: Map<String, Any?>,
            nativeQuotes: Boolean,
        ): Any? {
            fun proof(value: Any?): Any {
                val entry = catalog.entries[value as Int]
                return if (nativeQuotes) {
                    mapOf(
                        "source_id" to entry.span.sourceId,
                        "revision" to entry.span.revision,
                        "quote" to entry.text,
                        "catalog_id" to entry.id,
                    )
                } else {
                    entry.id
                }
            }

            fun visit(value: Any?): Any? =
                when (value) {
                    is List<*> -> value.map(::visit)
                    is Map<*, *> ->
                        value.entries.associate { (key, field) ->
                            key to if (key == "evidence") (field as List<*>).map(::proof) else visit(field)
                        }
                    else -> value
                }
            return visit(document - "evidence_selection_version")
        }

        private fun expression(
            operator: String,
            selection: Int,
            claim: String? = null,
            children: List<Map<String, Any?>> = emptyList(),
        ) = mapOf(
            "operator" to operator,
            "claim_id" to claim,
            "children" to children,
            "interpretation" to "EXPLICIT",
            "assertion" to "ASSERTED",
            "evidence" to List(referencesPerField) { selection },
        )
    }
}
