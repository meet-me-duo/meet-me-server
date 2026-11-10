package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as F

/** Native occurrences can be shared; condition-local provenance authority cannot be borrowed. */
class ScopedTermEvidenceOwnershipTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    private fun parse(document: MutableMap<String, Any?> = F.document()): ScheduleDslStructuralEnvelope =
        StrictScheduleDslStructuralResponseParser(mapper, allowScopedTermEvidence = true).parse(
            H.json(document),
            F.snapshot(),
            "schedule-dsl-response-5",
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    private fun twoOwners(): ScheduleDslStructuralEnvelope {
        val document = F.document()
        val result = F.results(document).first()

        @Suppress("UNCHECKED_CAST")
        val original = (result["conditions"] as List<MutableMap<String, Any?>>).single()
        val sibling = copy(original)
        sibling["id"] = "sibling"
        sibling["evidence"] = listOf(locator(F.NARROW_CONTEXT))
        result["conditions"] = listOf(original, sibling)
        val term = F.firstTerm(document)
        result["approved_terms"] = listOf(term, copy(term).also { it["condition_id"] = "sibling" })
        val program = F.fields(result["program"])

        @Suppress("UNCHECKED_CAST")
        val reference = (program["nodes"] as List<MutableMap<String, Any?>>).single()
        val first = copy(reference).also { it["id"] = "half-reference" }
        val second =
            copy(reference).also {
                it["id"] = "sibling-reference"
                it["claim_id"] = "sibling"
            }
        val root =
            copy(reference).also {
                it["operator"] = "ANY_OF"
                it["claim_id"] = null
                it["children"] = listOf("half-reference", "sibling-reference")
            }
        program["nodes"] = listOf(first, second, root)
        return parse(document)
    }

    @Test
    fun `STO01 shared native occurrence remains valid when each term owner declares and records its own context`() {
        val envelope = twoOwners()
        assertEquals(
            2,
            envelope.base.base.termApplications
                .count { it.submissionVersionId == F.first },
        )
        val contexts =
            envelope.locatedEvidence
                .filter { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE }
                .map { it.evidence.contextSpan }
                .toSet()
        assertEquals(setOf(F.span(F.CONTEXT, 9, 26), F.span(F.NARROW_CONTEXT, 9, 22)), contexts)
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot()))
    }

    @Test
    fun `STO02 sibling application cannot borrow another owners witness for the same native term span`() {
        val envelope = twoOwners()
        val forged =
            envelope.copy(
                locatedEvidence =
                    envelope.locatedEvidence.filterNot {
                        it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE &&
                            it.evidence.contextSpan == F.span(F.NARROW_CONTEXT, 9, 22)
                    },
            )
        assertTrue(forged.locatedEvidence.any { it.evidence.span == F.span("오후", 20, 22) })
        assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralValidator().validateRestored(forged, F.snapshot()) }
    }

    @Test
    fun `STO03 approved term witness cannot authorize an ambiguous graph proof outside its term owning role`() {
        val envelope = parse()
        val term =
            envelope.base.base.termApplications
                .single()
                .termEvidence

        fun changeGraph(view: ScheduleDslEnvelope): ScheduleDslEnvelope =
            view.copy(
                programs =
                    view.programs.map { result ->
                        if (result.submissionVersionId != F.first) {
                            result
                        } else {
                            result.copy(
                                program =
                                    requireNotNull(result.program).let { graph ->
                                        graph.copy(nodes = graph.nodes.map { it.copy(evidence = listOf(term)) })
                                    },
                            )
                        }
                    },
            )

        val approved =
            envelope.base.base.copy(
                declaration = changeGraph(envelope.base.base.declaration),
                calculationProjection = changeGraph(envelope.base.base.calculationProjection),
            )
        val contextMode =
            envelope.base.copy(
                base = approved,
                calculationProjection = changeGraph(envelope.base.calculationProjection),
            )
        val forged = envelope.copy(base = contextMode, calculationProjection = changeGraph(envelope.calculationProjection))
        assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralValidator().validateRestored(forged, F.snapshot()) }
    }

    private fun locator(quote: String): Map<String, Any> = mapOf("source_id" to "original", "revision" to 3, "quote" to quote)

    @Suppress("UNCHECKED_CAST")
    private fun copy(value: Map<String, Any?>): MutableMap<String, Any?> =
        mapper.readValue(H.json(value), Map::class.java) as MutableMap<String, Any?>
}
