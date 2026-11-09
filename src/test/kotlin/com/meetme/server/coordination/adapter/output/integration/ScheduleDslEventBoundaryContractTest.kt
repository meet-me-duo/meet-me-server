package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralNormalizer
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic server controls and prompt wording contracts, never evidence of actual-model meaning accuracy. */
class ScheduleDslEventBoundaryContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `EB01 generated instructions determine effect before whole-day or unknown representation`() {
        val request = mapper.readTree(contract.requestBytes(N.capsule(), MeetingMode.EITHER))
        val prompt = requireNotNull(request.path("prompt").asString())
        listOf(EFFECT_FIRST, WHOLE_DAY_RULE, UNKNOWN_RULE).forEach {
            assertTrue(prompt.contains(it), "Missing general temporal responsibility contract: $it")
        }
        assertFalse(prompt.contains("A resolved date without a time restriction uses WHOLE_DAY."))
        assertFalse(prompt.contains("An unsupported personal event clock stays UNKNOWN. Missing personal event clocks stay unknown."))
        assertTrue(
            prompt.contains("Known availability with a genuinely unknown restriction stays AVAILABLE/NON_PRECISE with temporal.UNKNOWN"),
        )
    }

    @Test
    fun `EB02 format guidance agrees with effect-first policy while UNKNOWN remains the same closed atom`() {
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val conditionSchema = fields(item(result.getValue("conditions")))
        val arms = alternatives(conditionSchema, schema).flatMap { alternatives(properties(it).getValue("temporal"), schema) }
        val unknown = arms.single { enumValues(properties(it).getValue("kind")) == setOf("UNKNOWN") }
        assertEquals(setOf("kind"), properties(unknown).keys)
        assertEquals(setOf("kind"), strings(unknown["required"]))
        assertEquals(false, unknown["additionalProperties"])
        assertEquals(
            setOf("WHOLE_DAY", "APPROVED_TERM", "BOUNDS", "UNKNOWN", "RAW_SCOPE"),
            arms.flatMap { enumValues(properties(it).getValue("kind")) }.toSet(),
        )
        val description = requireNotNull(conditionSchema["description"] as? String)
        assertTrue(description.contains("Missing personal event clocks stay unknown."))
        assertTrue(description.contains("WHOLE_DAY requires a complete-date effect; a known date alone is insufficient."))
    }

    @Test
    fun `EB03 dated unknown event keeps complete native proof inside or outside search without fabricated windows`() {
        for (date in listOf("2027-01-08", "2027-03-04")) {
            val text = "$date 개인 업무가 끝난 뒤에만 가능하고 종료 시각은 아직 몰라요."
            val fixture = fixture(text, listOf(Spec("event", IrEffect.AVAILABLE, unknown(), text)))
            val before = F.json(fixture.document)
            val envelope = fixture.parse()
            val claim = fixture.firstClaims(envelope).single()
            assertEquals(IrEffect.AVAILABLE, claim.effect)
            assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
            assertNull(claim.scope)
            assertNull(fixture.projected(envelope).single().scope)
            assertTrue(envelope.directionalApplications.isEmpty())
            assertTrue(fixture.available(envelope).isEmpty())
            val span = claim.evidence.single()
            assertEquals("original", span.sourceId)
            assertEquals(3, span.revision)
            assertEquals(0, span.startCodePoint)
            assertEquals(text.codePointCount(0, text.length), span.endCodePoint)
            assertEquals(F.snapshot(fixture.input).sourceText(F.ir.first, "original"), text)
            assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
            assertEquals(before, F.json(fixture.document))
            // The date survives in native proof, not in a newly invented dated-UNKNOWN field or numeric scope.
            assertEquals(setOf("kind"), fields(fixture.conditions().single()["temporal"]).keys)
        }
    }

    @Test
    fun `EB04 genuine date-only and explicit whole-day declarations retain legal inferred and explicit meanings`() {
        for (text in listOf("2027-01-08 가능해요.", "2027-01-08 하루 내내 가능해요.")) {
            for (interpretation in listOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED)) {
                val fixture = fixture(text, listOf(Spec("day", IrEffect.AVAILABLE, whole("2027-01-08"), text, interpretation)))
                val envelope = fixture.parse()
                assertEquals(interpretation, fixture.firstClaims(envelope).single().interpretation)
                assertEquals(listOf(DateScopeInlineFixture.window("2027-01-08T00:00", "2027-01-09T00:00")), fixture.available(envelope))
                assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
            }
        }
    }

    @Test
    fun `EB05 literal windows independent dates negatives and preferences are not broadened by calendar-day policy`() {
        val window = "2027-01-08 12:00부터 18:00까지 가능."
        val other = "2027-01-09 가능."
        val negative = "2027-01-09 하루 내내 불가."
        val preference = "2027-01-08 하루를 선호해요."
        val fixture =
            fixture(
                "$window $other $negative $preference",
                listOf(
                    Spec("window", IrEffect.AVAILABLE, bounds(), window, precise = true),
                    Spec("other", IrEffect.AVAILABLE, whole("2027-01-09"), other),
                    Spec("negative", IrEffect.UNAVAILABLE, whole("2027-01-09"), negative),
                    Spec("preference", IrEffect.PREFERRED, whole("2027-01-08"), preference),
                ),
            )
        val envelope = fixture.parse()
        assertEquals(setOf("window", "other", "negative", "preference"), fixture.firstClaims(envelope).map { it.id }.toSet())
        val expected = listOf(DateScopeInlineFixture.window("2027-01-08T12:00", "2027-01-08T18:00"))
        assertEquals(expected, fixture.available(envelope))
        val prepared =
            com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
                .calculate(envelope, F.snapshot(fixture.input), fixture.input.policy, MeetingMode.REMOTE)
                .preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId.value == F.ir.firstParticipant }
        assertEquals(expected, prepared.explicitPreferences.timeRanges)
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
    }

    @Test
    fun `EB06 unknown boundaries do not weaken owner revision digest or required native quotation checks`() {
        val text = "2027-01-08 개인 업무가 끝난 뒤 가능하지만 종료 시각은 미정이에요."
        val fixture = fixture(text, listOf(Spec("event", IrEffect.AVAILABLE, unknown(), text)))
        val envelope = fixture.parse()
        val snapshot = F.snapshot(fixture.input)
        val located = envelope.locatedEvidence.first { it.submissionVersionId == F.ir.first }.evidence
        assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralNormalizer.restoreEvidence(F.ir.second, located, snapshot) }
        val corruptions =
            listOf(
                located.copy(span = located.span.copy(revision = 4)),
                located.copy(span = located.span.copy(contentSha256 = "0".repeat(64))),
            )
        for (forged in corruptions) {
            assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralNormalizer.restoreEvidence(F.ir.first, forged, snapshot) }
        }
        val damaged = fixture(text, listOf(Spec("event", IrEffect.AVAILABLE, unknown(), text)))
        val bank = fields((damaged.conditions().single()["proofs"] as List<*>).single())
        bank.remove("quote")
        assertFailsWith<NaturalLanguageParserException> { damaged.parse() }
    }

    private data class Spec(
        val id: String,
        val effect: IrEffect,
        val temporal: Map<String, Any?>,
        val quote: String,
        val interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        val precise: Boolean = false,
    )

    private fun fixture(
        text: String,
        specs: List<Spec>,
    ): DateScopeInlineFixture.Fixture {
        val input = F.input(text).copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val peer = Spec("peer", IrEffect.AVAILABLE, rawScope(), input.inputs[1].rawText, precise = true)
        val results =
            listOf(specs, listOf(peer)).mapIndexed { index, selected ->
                linkedMapOf<String, Any?>(
                    "input_ref" to input.inputs[index].submissionVersionId.toString(),
                    "conditions" to selected.map(::condition).toMutableList(),
                    "program" to program(selected),
                    "approved_terms" to emptyList<Any>(),
                    "directional_times" to emptyList<Any>(),
                    "mode_restrictions" to emptyList<Any>(),
                )
            }
        return DateScopeInlineFixture.Fixture(
            input,
            linkedMapOf(
                "semantic_version" to "schedule-ir-product-6",
                "ast_version" to "schedule-ast-1",
                "vocabulary_version" to "ko-time-terms-1",
                "results" to results,
            ),
        )
    }

    private fun condition(spec: Spec): MutableMap<String, Any?> =
        linkedMapOf(
            "id" to spec.id,
            "effect" to spec.effect.name,
            "interpretation" to spec.interpretation.name,
            "temporal_resolution" to if (spec.precise) "PRECISE_INTERVAL" else "NON_PRECISE",
            "assertion" to "ASSERTED",
            "supersedes" to emptyList<String>(),
            "proofs" to listOf(locator(spec.quote) + ("id" to "p:meaning")),
            "evidence" to listOf("p:meaning"),
            "temporal" to spec.temporal,
        )

    private fun program(specs: List<Spec>): Map<String, Any?> {
        fun root(effects: Set<IrEffect>): Map<String, Any?>? {
            val selected = specs.filter { it.effect in effects }
            val children = selected.map { expression("CLAIM_REF", it.id, emptyList(), listOf(it)) }
            return when (children.size) {
                0 -> null
                1 -> children.single()
                else -> expression("ANY_OF", null, children, selected)
            }
        }
        return mapOf(
            "nodes" to emptyList<Any>(),
            "history_condition_ids" to emptyList<String>(),
            "availability_root" to root(setOf(IrEffect.AVAILABLE)),
            "exclusion_root" to root(setOf(IrEffect.UNAVAILABLE)),
            "preference_root" to root(setOf(IrEffect.PREFERRED)),
        )
    }

    private fun expression(
        operator: String,
        claim: String?,
        children: List<Map<String, Any?>>,
        specs: List<Spec>,
    ) = mapOf(
        "operator" to operator,
        "claim_id" to claim,
        "children" to children,
        "interpretation" to if (specs.any { it.interpretation == IrInterpretation.INFERRED }) "INFERRED" else "EXPLICIT",
        "assertion" to "ASSERTED",
        "evidence" to specs.map { locator(it.quote) }.distinct(),
    )

    private fun whole(date: String) = mapOf("kind" to "WHOLE_DAY", "local_date" to date, "date_evidence" to listOf("p:meaning"))

    private fun unknown() = mapOf("kind" to "UNKNOWN")

    private fun bounds() =
        mapOf(
            "kind" to "BOUNDS",
            "local_date" to "2027-01-08",
            "date_evidence" to listOf("p:meaning"),
            "lower" to mapOf("clock" to "12:00", "clock_evidence" to listOf("p:meaning")),
            "upper" to mapOf("clock" to "18:00", "clock_evidence" to listOf("p:meaning")),
        )

    private fun rawScope() =
        mapOf("kind" to "RAW_SCOPE", "scope" to mapOf("dimension" to "TIME", "start" to "2027-01-08T14:00", "end" to "2027-01-08T18:00"))

    private fun locator(quote: String) = linkedMapOf<String, Any?>("source_id" to "original", "revision" to 3, "quote" to quote)

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun properties(value: Any?) = fields(fields(value)["properties"])

    private fun item(value: Any?) = fields(value)["items"]

    private fun strings(value: Any?) = (value as List<*>).map { it.toString() }.toSet()

    private fun enumValues(value: Any?) = strings(fields(value)["enum"])

    private fun alternatives(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = fields(value)
        val ref = schema["\$ref"] as? String
        if (ref != null) return alternatives(fields(root["\$defs"])[ref.substringAfterLast('/')], root)
        val choices = schema["anyOf"] as? List<*>
        return choices?.flatMap { alternatives(it, root) } ?: listOf(schema)
    }

    companion object {
        private const val EFFECT_FIRST = "Choose the temporal atom after determining the asserted effect and its restrictions."
        private const val WHOLE_DAY_RULE =
            "Use WHOLE_DAY only when that effect applies to the entire resolved date; " +
                "a date-only availability answer without another restriction covers that date."
        private const val UNKNOWN_RULE =
            "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; " +
                "retain their date and event context in complete condition evidence without inventing clocks or whole-day availability."
    }
}
