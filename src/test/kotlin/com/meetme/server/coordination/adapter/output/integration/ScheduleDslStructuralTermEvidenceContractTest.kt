package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** C5 producer/evidence contracts use synthetic text only; no model accuracy or live provider is exercised. */
class ScheduleDslStructuralTermEvidenceContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `STE01 C5 term evidence quote admits exactly all twelve approved lexemes for the eleven term identifiers`() {
        val terms = properties(item(resultProperties(contract.responseSchema()).getValue("approved_terms")))
        assertEquals(approvedLexemes.keys, strings(fields(terms["term"])["enum"]))
        val evidence = fields(terms["term_evidence"])
        assertLocator(evidence)
        val quote = fields(properties(evidence)["quote"])
        assertEquals("string", quote["type"])
        assertEquals(approvedLexemes.values.flatten().toSet(), strings(quote["enum"]))
        assertEquals(12, (quote["enum"] as List<*>).size)
        listOf("2027-01-08 오후 가능.", "오후에", "심야 / 한밤중", "ALL_DAY", "토요일 점심과 일요일 저녁이 편해요").forEach {
            assertFalse(it in strings(quote["enum"]))
        }
    }

    @Test
    fun `STE02 condition date clock and graph evidence retain broad closed native quote locators without new offsets or fields`() {
        val schema = contract.responseSchema()

        fun schemaAlternatives(value: Any?): List<Map<String, Any?>> {
            val objectSchema = fields(value)
            val reference = objectSchema["\$ref"] as? String
            if (reference != null) {
                return schemaAlternatives(fields(schema["\$defs"])[reference.substringAfterLast('/')])
            }
            val choices = objectSchema["anyOf"] as? List<*>
            return choices?.flatMap(::schemaAlternatives) ?: listOf(objectSchema)
        }
        val result = resultProperties(schema)
        assertEquals(
            setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions", "directional_times"),
            result.keys,
        )
        val terms = properties(item(result.getValue("approved_terms")))
        assertEquals(setOf("condition_id", "term", "local_date", "term_evidence", "date_evidence"), terms.keys)
        val directional = properties(item(result.getValue("directional_times")))
        assertEquals(setOf("condition_id", "direction", "local_date", "clock", "date_evidence", "clock_evidence"), directional.keys)
        val conditions = schemaAlternatives(item(result.getValue("conditions")))
        val program = properties(fields(result["program"]))
        val node = properties(item(program.getValue("nodes")))
        val mode = properties(item(result.getValue("mode_restrictions")))
        conditions.forEach { conditionSchema ->
            val condition = properties(conditionSchema)
            assertEquals("string", fields(item(condition.getValue("evidence")))["type"])
            val bank = fields(item(condition.getValue("proofs")))["anyOf"] as List<*>
            val generalProof = bank.single { "enum" !in fields(properties(fields(it))["quote"]) }
            assertEquals(setOf("id", "source_id", "revision", "quote"), properties(fields(generalProof)).keys)
            val generalQuote = fields(properties(fields(generalProof))["quote"])
            listOf("enum", "const", "pattern", "format", "oneOf", "anyOf", "allOf").forEach {
                assertFalse(it in generalQuote)
            }
        }
        listOf(
            item(terms.getValue("date_evidence")),
            item(directional.getValue("date_evidence")),
            item(directional.getValue("clock_evidence")),
            item(node.getValue("evidence")),
            item(mode.getValue("evidence")),
        ).forEach { evidence ->
            assertLocator(evidence)
            val quote = fields(properties(evidence)["quote"])
            assertEquals("string", quote["type"])
            listOf("enum", "const", "pattern", "format", "oneOf", "anyOf", "allOf").forEach {
                assertFalse(it in quote, "Broad native quote must not be restricted by $it")
            }
        }
        schemaObjects(schema).filter { "properties" in it }.forEach { objectSchema ->
            assertEquals(false, objectSchema["additionalProperties"])
            assertEquals(properties(objectSchema).keys, strings(objectSchema["required"]))
        }
        assertFalse(F.json(schema).contains("start_code_point"))
        assertFalse(F.json(schema).contains("end_code_point"))
    }

    @Test
    fun `STE03 producer distinguishes exact word term evidence from broad context and forbids widening an ambiguous repeated lexeme`() {
        val request = mapper.readTree(contract.requestBytes(N.capsule(), MeetingMode.EITHER))
        val prompt = requireNotNull(request.path("prompt").asString())
        requiredClauses.forEach { clause -> assertTrue(prompt.contains(clause), "Missing C5 term-evidence instruction: $clause") }
        assertFalse(
            prompt.contains(
                "Choose a phrase with exactly one occurrence in that source. Include enough surrounding literal text to disambiguate duplicates.",
            ),
            "An unconditional widening instruction conflicts with word-only term evidence",
        )
        approvedLexemes.values.flatten().forEach { assertTrue(prompt.contains(it)) }
    }

    @Test
    fun `STE04 exact native term succeeds while a whole-sentence term quote is rejected without automatic narrowing`() {
        val input = F.input().copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)

        fun nativeQuote(span: IrEvidenceSpan): String {
            val source = requireNotNull(F.snapshot(input).sourceText(F.ir.first, span.sourceId))
            return source.substring(source.offsetByCodePoints(0, span.startCodePoint), source.offsetByCodePoints(0, span.endCodePoint))
        }

        fun document(): MutableMap<String, Any?> {
            val document = mutableFields(locators(F.document(F.proposal(input))))
            document["semantic_version"] = "schedule-ir-product-6"
            @Suppress("UNCHECKED_CAST")
            val results = document["results"] as List<MutableMap<String, Any?>>
            results.forEach {
                it["mode_restrictions"] = emptyList<Any>()
                it["directional_times"] = emptyList<Any>()
            }
            return document
        }

        fun parse(document: MutableMap<String, Any?>): ScheduleDslStructuralEnvelope =
            contract.parseProviderResponse(
                F.json(document),
                F.snapshot(input),
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )

        val valid = parse(document())
        val term =
            valid.base.base.termApplications
                .single()
        assertEquals("오후", nativeQuote(term.termEvidence))
        assertNotNull(
            valid.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.first }
                .program,
        )
        assertEquals(
            F.FIRST,
            nativeQuote(
                valid.base.base.declaration.claims.results
                    .single { it.submissionVersionId == F.ir.first }
                    .conditions
                    .single()
                    .evidence
                    .single(),
            ),
        )

        val broad = document()
        mutableFields(F.firstTerm(broad)["term_evidence"])["quote"] = F.FIRST
        val rejected = parse(broad)
        assertTrue(
            rejected.base.base.termApplications
                .isEmpty(),
        )
        assertNull(
            rejected.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.first }
                .program,
        )
        assertNotNull(
            rejected.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.second }
                .program,
        )
        assertEquals(F.FIRST, F.snapshot(input).sourceText(F.ir.first, "original"))
    }

    @Test
    fun `STE05 C5 construction preserves C4 and legacy requests while only C5 term locators receive a vocabulary restriction`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val legacy = ScheduleDslProviderContract(mapper)
        val c4Bytes = c4.requestBytes(capsule, MeetingMode.EITHER)
        val c4Schema = mapper.writeValueAsBytes(c4.responseSchema())
        val legacyBytes = legacy.requestBytes(capsule.snapshot, "schedule-dsl-response-1", "schedule-ir-product-3", SCHEDULE_AST_VERSION)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        val oldRequest = mapper.readTree(c4Bytes)
        assertEquals(oldRequest.path("inputs"), request.path("inputs"))
        assertEquals(oldRequest.path("context"), request.path("context"))
        val oldTerms = properties(item(resultProperties(c4.responseSchema()).getValue("approved_terms")))
        val oldEvidence = properties(fields(oldTerms["term_evidence"]))
        assertEquals(setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"), oldEvidence.keys)
        assertFalse("enum" in fields(oldEvidence["quote"]))
        val newTerms = properties(item(resultProperties(contract.responseSchema()).getValue("approved_terms")))
        val newEvidence = properties(fields(newTerms["term_evidence"]))
        assertTrue("enum" in fields(newEvidence["quote"]))
        assertContentEquals(c4Bytes, ScheduleDslContextModeProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(c4Schema, mapper.writeValueAsBytes(ScheduleDslContextModeProviderContract(mapper).responseSchema()))
        assertContentEquals(
            legacyBytes,
            legacy.requestBytes(capsule.snapshot, "schedule-dsl-response-1", "schedule-ir-product-3", SCHEDULE_AST_VERSION),
        )
    }

    private fun resultProperties(schema: Map<String, Any?>) = properties(item(properties(schema).getValue("results")))

    private fun item(value: Any?) = fields(fields(value)["items"])

    private fun properties(value: Map<String, Any?>) = fields(value["properties"])

    private fun assertLocator(value: Map<String, Any?>) {
        assertEquals(setOf("source_id", "revision", "quote"), properties(value).keys)
        assertEquals(properties(value).keys, strings(value["required"]))
        assertEquals(false, value["additionalProperties"])
    }

    private fun strings(value: Any?) = requireNotNull(value as? List<*>).map { requireNotNull(it as? String) }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = requireNotNull(value as? Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private fun mutableFields(value: Any?) = requireNotNull(value as? MutableMap<String, Any?>)

    private fun schemaObjects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(fields(value)) + value.values.flatMap(::schemaObjects)
            is List<*> -> value.flatMap(::schemaObjects)
            else -> emptyList()
        }

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val fields = fields(value)
                if ("start_code_point" in fields) {
                    fields.filterKeys { it in setOf("source_id", "revision", "quote") }.toMutableMap()
                } else {
                    fields.mapValues { locators(it.value) }.toMutableMap()
                }
            }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }

    private val approvedLexemes =
        linkedMapOf(
            "AM" to listOf("오전"),
            "PM" to listOf("오후"),
            "DAWN" to listOf("새벽"),
            "MORNING" to listOf("아침"),
            "LUNCH" to listOf("점심"),
            "DAYTIME" to listOf("낮"),
            "MIDDAY" to listOf("한낮"),
            "EVENING" to listOf("저녁"),
            "NIGHT" to listOf("밤"),
            "LATE_NIGHT" to listOf("심야", "한밤중"),
            "ALL_DAY" to listOf("하루종일"),
        )

    private val requiredClauses =
        listOf(
            "For term proof entries (id starts with t:), quote only the exact approved lexeme, with no date, particle, surrounding phrase or sentence.",
            "General proof quotes may use broader exact native quotations to retain context and uniquely locate the source.",
            "If an approved lexeme occurs more than once in the same source, do not broaden a term proof quote or choose the first occurrence; preserve the unresolved evidence failure instead.",
        )
}
