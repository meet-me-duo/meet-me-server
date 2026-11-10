package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Manually declared synthetic semantics. No private input, live model, or full-service quality oracle. */
class ScheduleDslOwnedProofChoiceContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `PC01 four synthetic participants intersect only in a preferred meal with scoped QA and inferred clock`() {
        val fixture = four()
        val raw = F.json(fixture.document)
        val envelope = fixture.parse()
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
        assertEquals(
            4,
            envelope.base.base.declaration.programs
                .count { it.program != null && it.problems.isEmpty() },
        )
        assertTrue(
            envelope.base.base.declaration.claims.results
                .all { it.quarantined.isEmpty() },
        )
        assertEquals(IrEffect.UNAVAILABLE, fixture.claims(envelope, 0).single { it.id == "blocked-day" }.effect)
        val preferred = fixture.claims(envelope, 1)
        assertEquals(4, preferred.size)
        assertTrue(preferred.all { it.effect == IrEffect.PREFERRED && it.temporalResolution == IrTemporalResolution.NON_PRECISE })
        assertTrue(preferred.all { it.alternativeGroup == null && it.alternative == null })
        assertNull(fixture.program(envelope, 1).program!!.availabilityRoot)
        val clock = envelope.directionalApplications.single { it.declaration.conditionId == "context" }
        assertEquals(LocalTime.of(16, 0), clock.declaration.clock)
        assertEquals(IrInterpretation.INFERRED, clock.declaration.interpretation)
        assertEquals(
            setOf("original", SUPPLEMENT_SOURCE),
            fixture
                .claims(envelope, 2)
                .single()
                .evidence
                .map { it.sourceId }
                .toSet(),
        )
        assertFalse(
            fixture.input.inputs[2]
                .rawText
                .contains("16"),
        )
        assertFalse(
            fixture.input.inputs[2]
                .evidence!!
                .supplement!!
                .answerText
                .contains("16"),
        )
        val calculation = fixture.calculate(envelope)
        val participants = calculation.preparedByMode.getValue(MeetingMode.REMOTE).participants
        val common = participants.map { it.availableTimes }.reduce(TimeRangeMatcher::intersect)
        val expected = window("2027-01-08T17:00", "2027-01-08T22:00")
        assertEquals(listOf(expected), common)
        assertTrue(calculation.recommendations.options.any { it.window == expected && it.variants.any { v -> v.participantIds.size == 4 } })
        assertEquals(raw, F.json(fixture.document))
    }

    @Test
    fun `PC02 local proof banks reject ambiguous unused unowned and missing meaning references`() {
        val faults =
            listOf(
                "duplicate-id",
                "unknown",
                "cross-condition",
                "unused",
                "unused-bad-source",
                "empty",
                "over-limit",
                "p-term",
                "t-sentence",
            )
        for (fault in faults) {
            val fixture = four()
            val condition = fixture.conditions(1).first()
            val bank = fixture.proofs(condition)
            val atom = fields(condition["temporal"])
            when (fault) {
                "duplicate-id" -> bank += LinkedHashMap(bank.first()).apply { this["quote"] = "좋아요." }
                "unknown" -> condition["evidence"] = listOf("p:absent")
                "cross-condition" -> {
                    val other = fixture.conditions(1)[1]
                    fixture.proofs(other) += proof("p:elsewhere", "저녁만")
                    other["evidence"] = listOf("p:meaning", "p:elsewhere")
                    condition["evidence"] = listOf("p:elsewhere")
                }
                "unused" -> bank += proof("p:unused", "저녁만")
                "unused-bad-source" -> bank += proof("p:unused", "없는 근거", "question:not-native")
                "empty" -> condition["evidence"] = emptyList<String>()
                "over-limit" -> {
                    val source = fixture.input.inputs[1].rawText
                    condition["proofs"] =
                        (
                            (1..32).map { proof("p:meaning$it", source.takeLast(it)) } +
                                proof("p:date", "2027-01-08")
                        ).toMutableList()
                    condition["evidence"] = (1..32).map { "p:meaning$it" }
                    condition["temporal"] =
                        linkedMapOf(
                            "kind" to "WHOLE_DAY",
                            "local_date" to "2027-01-08",
                            "date_evidence" to listOf("p:date"),
                        )
                }
                "p-term" -> {
                    bank.single { it["id"] == "t:word" }["id"] = "p:word"
                    atom["term_evidence"] = "p:word"
                }
                else -> bank.single { it["id"] == "t:word" }["quote"] = FOUR_PREFERRED
            }
            assertFailsWith<NaturalLanguageParserException>(fault) { fixture.parse() }
        }
    }

    @Test
    fun `PC03 invalid native proof or term association withholds its participant and preserves independent peers`() {
        for (fault in listOf("source", "revision", "term-mismatch", "ambiguous-word", "borrowed-answer")) {
            var fixture = four()
            val condition = fixture.conditions(1).first()
            val word = fixture.proofs(condition).single { it["id"] == "t:word" }
            when (fault) {
                "source" -> word["source_id"] = "question:not-native"
                "revision" -> word["revision"] = 99
                "term-mismatch" -> word["quote"] = "저녁"
                "ambiguous-word" ->
                    fixture =
                        fixture.copy(
                            input =
                                fixture.input.copy(
                                    inputs =
                                        fixture.input.inputs.mapIndexed { i, item ->
                                            if (i == 1) item.copy(rawText = item.rawText + " 추가 점심.") else item
                                        },
                                ),
                        )
                else -> {
                    word["source_id"] = SUPPLEMENT_SOURCE
                    word["quote"] = "점심"
                }
            }
            val raw = F.json(fixture.document)
            val envelope = fixture.parse()
            assertNull(fixture.program(envelope, 1).program)
            assertTrue(fixture.available(envelope, 1).isEmpty())
            for (index in listOf(0, 2, 3)) assertNotNull(fixture.program(envelope, index).program)
            if (fault in setOf("source", "revision", "ambiguous-word", "borrowed-answer")) {
                assertTrue(fixture.result(envelope, 1).quarantined.any { it.problem == IrProblem.EVIDENCE })
            }
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `PC04 explicit branch ownership retains genuine and nested choices without guessing an innermost pair`() {
        for (nested in listOf(false, true)) {
            val fixture = choice(nested)
            val raw = F.json(fixture.document)
            val envelope = fixture.parse()
            assertNotNull(fixture.program(envelope, 0).program)
            assertTrue(fixture.available(envelope, 0).isEmpty())
            val pairs = fixture.claims(envelope, 0).associate { it.id to (it.alternativeGroup to it.alternative) }
            assertEquals("outer" to "left", pairs.getValue("left"))
            assertEquals("outer" to "right", pairs.getValue("right"))
            if (nested) {
                assertEquals("inner" to "early", pairs.getValue("early"))
                assertEquals("inner" to "late", pairs.getValue("late"))
            }
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `PC05 branch binding conflicts outside reuse hybrids and invalid arity are rejected without graph repair`() {
        val faults =
            listOf(
                "outside-owner",
                "conflict",
                "outside-use",
                "missing-witness",
                "duplicate-label",
                "foreign-id",
                "old-alt",
                "one-child",
            )
        for (fault in faults) {
            val fixture = choice()
            val root = fields(fixture.programWire(0)["availability_root"])

            @Suppress("UNCHECKED_CAST")
            val branches = root["branches"] as List<MutableMap<String, Any?>>
            when (fault) {
                "outside-owner" -> branches.first()["condition_ids"] = listOf("right")
                "conflict" -> {
                    branches.last()["condition_ids"] = listOf("left")
                    branches.last()["expression"] =
                        ref(
                            "left",
                            fixture.input.inputs
                                .first()
                                .rawText,
                        )
                }
                "outside-use" ->
                    fixture.programWire(0)["availability_root"] =
                        composite(
                            "ANY_OF",
                            listOf(
                                root,
                                ref(
                                    "left",
                                    fixture.input.inputs
                                        .first()
                                        .rawText,
                                ),
                            ),
                            fixture.input.inputs
                                .first()
                                .rawText,
                        )
                "missing-witness" -> branches.first()["condition_ids"] = emptyList<String>()
                "duplicate-label" -> branches.last()["label"] = "left"
                "foreign-id" -> branches.first()["condition_ids"] = listOf("peer")
                "old-alt" -> fixture.conditions(0).first()["alternative_group"] = null
                else -> {
                    fixture.resultWire(0)["conditions"] = mutableListOf(fixture.conditions(0).first())
                    fixture.programWire(0)["availability_root"] =
                        composite(
                            "ANY_OF",
                            listOf(
                                ref(
                                    "left",
                                    fixture.input.inputs
                                        .first()
                                        .rawText,
                                ),
                            ),
                            fixture.input.inputs
                                .first()
                                .rawText,
                        )
                }
            }
            val raw = F.json(fixture.document)
            val result = runCatching { fixture.parse() }
            if (result.isSuccess) {
                assertNull(fixture.program(result.getOrThrow(), 0).program, fault)
                assertTrue(fixture.available(result.getOrThrow(), 0).isEmpty())
            }
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `PC06 an old atomic wire keeps erroneous alternative metadata and original evidence instead of being repaired`() {
        val fixture = four()
        legacy(fixture)
        fixture.conditions(1).forEachIndexed { index, condition ->
            condition["alternative_group"] = "old-meal"
            condition["alternative"] = if (index % 2 == 0) "lunch" else "evening"
        }
        val raw = F.json(fixture.document)
        val envelope = fixture.parse()
        assertNull(fixture.program(envelope, 1).program)
        assertTrue(fixture.available(envelope, 1).isEmpty())
        assertEquals(setOf("old-meal"), fixture.claims(envelope, 1).mapNotNull { it.alternativeGroup }.toSet())
        for (index in listOf(0, 2, 3)) assertNotNull(fixture.program(envelope, index).program)
        assertEquals(raw, F.json(fixture.document))
    }

    @Test
    fun `PC07 owned proof expansion preserves negative precedence and an unknown exclusion cannot become a default`() {
        val fixture = simple()
        val text =
            fixture.input.inputs
                .first()
                .rawText
        val conditions =
            mutableListOf(
                rawClaim("available", text, "2027-01-08T14:00", "2027-01-08T20:00"),
                rawClaim("blocked", text, "2027-01-08T16:00", "2027-01-08T18:00", "UNAVAILABLE"),
            )
        fixture.resultWire(0)["conditions"] = conditions
        fixture.resultWire(0)["program"] = independent(conditions, text)
        val envelope = fixture.parse()
        assertEquals(
            listOf(window("2027-01-08T14:00", "2027-01-08T16:00"), window("2027-01-08T18:00", "2027-01-08T20:00")),
            fixture.available(envelope, 0),
        )
        conditions.last()["temporal"] = linkedMapOf("kind" to "UNKNOWN")
        conditions.last()["temporal_resolution"] = "NON_PRECISE"
        val unknown = fixture.parse()
        assertEquals(IrEffect.UNAVAILABLE, fixture.claims(unknown, 0).single { it.id == "blocked" }.effect)
        assertNull(fixture.claims(unknown, 0).single { it.id == "blocked" }.scope)
        assertTrue(fixture.available(unknown, 0).isEmpty())
    }

    @Test
    fun `PC08 typed proof namespaces and six operator arms preserve native context C4 bytes and existing arity limits`() {
        val capsule = N.capsule()
        val old = ScheduleDslContextModeProviderContract(mapper)
        val before = old.requestBytes(capsule, MeetingMode.EITHER)
        val beforeSchema = mapper.writeValueAsBytes(old.responseSchema())
        val previous = mapper.readTree(before)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("c5-owned-proof-choice-2", request.path("producer_schema_revision").asString())
        assertEquals(previous.path("inputs"), request.path("inputs"))
        assertEquals(previous.path("context"), request.path("context"))
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = variants(item(result.getValue("conditions")), schema)
        conditions.forEach { conditionSchema ->
            val condition = properties(conditionSchema)
            assertFalse("alternative_group" in condition)
            assertFalse("alternative" in condition)
            val bank = map(condition["proofs"])
            assertEquals(1, bank["minItems"])
            assertEquals(32, bank["maxItems"])
            val entries = variants(bank["items"], schema)
            assertEquals(2, entries.size)
            val termEntry = entries.single { "enum" in map(properties(it)["quote"]) }
            val generalEntry = entries.single { "enum" !in map(properties(it)["quote"]) }
            assertEquals(LEXEMES, strings(map(properties(termEntry)["quote"])["enum"]))
            assertEquals(setOf("id", "source_id", "revision", "quote"), properties(termEntry).keys)
            assertEquals(properties(termEntry).keys, properties(generalEntry).keys)
            val termId = map(properties(termEntry)["id"])
            val generalId = map(properties(generalEntry)["id"])
            assertTrue(Regex(termId.getValue("pattern") as String).matches("t:word"))
            assertFalse(Regex(termId.getValue("pattern") as String).matches("p:word"))
            assertTrue(Regex(generalId.getValue("pattern") as String).matches("p:meaning"))
            assertFalse(Regex(generalId.getValue("pattern") as String).matches("t:meaning"))
        }
        val atoms = conditions.flatMap { variants(properties(it).getValue("temporal"), schema) }
        val termAtom = atoms.single { "term_evidence" in properties(it) }
        assertTrue(Regex(map(properties(termAtom)["term_evidence"]).getValue("pattern") as String).matches("t:word"))
        assertFalse(Regex(map(properties(termAtom)["term_evidence"]).getValue("pattern") as String).matches("p:word"))
        val roots = properties(result.getValue("program"))
        val arms = variants(roots.getValue("availability_root"), schema).filter { "operator" in properties(it) }
        assertEquals(6, arms.size)
        val expected =
            mapOf(
                "CLAIM_REF" to (0 to 0),
                "ALL_OF" to (2 to 32),
                "ANY_OF" to (2 to 32),
                "EXCEPT" to (2 to 2),
                "PREFER" to (2 to 2),
            )
        expected.forEach { (operator, arity) ->
            val arm = arms.single { operator in strings(map(properties(it)["operator"])["enum"]) }
            assertEquals(setOf("operator", "claim_id", "children", "interpretation", "assertion", "evidence"), properties(arm).keys)
            assertEquals(arity.first, map(properties(arm)["children"])["minItems"])
            assertEquals(arity.second, map(properties(arm)["children"])["maxItems"])
        }
        val choice = arms.single { "CHOICE" in strings(map(properties(it)["operator"])["enum"]) }
        assertEquals(setOf("operator", "choice_group", "branches", "interpretation", "assertion", "evidence"), properties(choice).keys)
        assertEquals(2, map(properties(choice)["branches"])["minItems"])
        assertEquals(8, map(properties(choice)["branches"])["maxItems"])
        assertEquals(setOf("label", "condition_ids", "expression"), properties(item(properties(choice).getValue("branches"))).keys)
        objects(schema).filter { "properties" in it }.forEach {
            assertEquals(false, it["additionalProperties"])
            assertEquals(properties(it).keys, strings(it["required"]))
        }
        assertContentEquals(before, old.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(beforeSchema, mapper.writeValueAsBytes(old.responseSchema()))
    }

    @Test
    fun `PA01 exact used and unused locator aliases preserve the complete canonical envelope`() {
        for (referenced in listOf(false, true)) {
            val fixture = four()
            val baseline = fixture.parse()
            for (index in fixture.input.inputs.indices) {
                fixture.conditions(index).forEach { condition ->
                    val bank = fixture.proofs(condition)
                    bank += LinkedHashMap(bank.first()).apply { this["id"] = "p:alias" }
                    if (referenced) {
                        @Suppress("UNCHECKED_CAST")
                        val meaning = condition["evidence"] as List<String>
                        condition["evidence"] = meaning + "p:alias"
                    }
                }
            }
            val raw = F.json(fixture.document)
            val aliased = fixture.parse()
            assertEquals(baseline, aliased)
            assertEquals(aliased, ScheduleDslStructuralValidator().validateRestored(aliased, F.snapshot(fixture.input)))
            for (index in fixture.input.inputs.indices) {
                assertEquals(fixture.available(baseline, index), fixture.available(aliased, index))
            }
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `PA02 term and general IDs may share an exact locator without bypassing the term namespace`() {
        val fixture = four()
        val condition = fixture.conditions(1).first()
        condition["evidence"] = listOf("p:meaning", "p:date", "t:word")
        val baseline = fixture.parse()
        val bank = fixture.proofs(condition)
        val word = bank.single { it["id"] == "t:word" }
        bank += LinkedHashMap(word).apply { this["id"] = "p:word" }
        bank += LinkedHashMap(word).apply { this["id"] = "t:shadow" }
        condition["evidence"] = listOf("p:meaning", "p:date", "p:word")
        val atom = fields(condition["temporal"])
        atom["term_evidence"] = "t:shadow"
        val raw = F.json(fixture.document)
        val shared = fixture.parse()
        assertEquals(baseline, shared)
        assertEquals(4, shared.base.base.termApplications.size)
        assertTrue(fixture.claims(shared, 1).all { it.effect == IrEffect.PREFERRED && it.scope == null })
        assertEquals(raw, F.json(fixture.document))
        atom["term_evidence"] = "p:word"
        assertFailsWith<NaturalLanguageParserException> { fixture.parse() }
    }

    @Test
    fun `PA03 unused identities cannot merge across source revision quotation condition or participant`() {
        for (fault in listOf("source", "revision", "quote", "condition-owner", "participant-owner")) {
            val fixture = four()
            val condition = fixture.conditions(1).first()
            val bank = fixture.proofs(condition)
            val alias = LinkedHashMap(bank.first()).apply { this["id"] = "p:unused" }
            when (fault) {
                "source" -> alias["source_id"] = SUPPLEMENT_SOURCE
                "revision" -> alias["revision"] = 4
                "quote" -> alias["quote"] = "저녁만"
                "condition-owner" -> {
                    val other = fixture.conditions(1)[1]
                    fixture.proofs(other) += proof("p:elsewhere", "저녁만")
                    other["evidence"] = listOf("p:meaning", "p:elsewhere")
                    alias["quote"] = "저녁만"
                }
                else ->
                    alias["quote"] =
                        fixture.input.inputs
                            .first()
                            .rawText
            }
            bank += alias
            val raw = F.json(fixture.document)
            assertFailsWith<NaturalLanguageParserException>(fault) { fixture.parse() }
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `PA04 alias sharing preserves declared limits unique IDs closed entries and nonempty owned references`() {
        val faults =
            listOf(
                "entries33",
                "refs33",
                "empty",
                "duplicate-id",
                "namespace",
                "term-literal",
                "extra-field",
                "missing-field",
                "unknown-ref",
            )
        for (fault in faults) {
            val fixture = four()
            val condition = fixture.conditions(1).first()
            val bank = fixture.proofs(condition)
            val alias = LinkedHashMap(bank.first()).apply { this["id"] = "p:alias" }
            when (fault) {
                "entries33" -> while (bank.size < 33) {
                    bank += LinkedHashMap(bank.first()).apply { this["id"] = "p:alias${bank.size}" }
                }
                "refs33" -> condition["evidence"] = List(33) { "p:meaning" }
                "empty" -> condition["evidence"] = emptyList<String>()
                "duplicate-id" -> bank += LinkedHashMap(bank.first())
                "namespace" -> bank += alias.apply { this["id"] = "x:alias" }
                "term-literal" -> bank += alias.apply { this["id"] = "t:alias" }
                "extra-field" -> bank += alias.apply { this["owner"] = "original" }
                "missing-field" -> bank += alias.apply { remove("source_id") }
                else -> condition["evidence"] = listOf("p:absent")
            }
            assertFailsWith<NaturalLanguageParserException>(fault) { fixture.parse() }
        }
    }

    @Test
    fun `PA05 aliases retain native evidence failures participant isolation and genuine unresolved choice`() {
        for (fault in listOf("source", "revision", "other-person", "ambiguous-word")) {
            var fixture = four()
            val condition = fixture.conditions(1).first()
            val bank = fixture.proofs(condition)
            val word = bank.single { it["id"] == "t:word" }
            when (fault) {
                "source" -> word["source_id"] = "not-native"
                "revision" -> word["revision"] = 99
                "other-person" -> word["source_id"] = SUPPLEMENT_SOURCE
                else ->
                    fixture =
                        fixture.copy(
                            input =
                                fixture.input.copy(
                                    inputs =
                                        fixture.input.inputs.mapIndexed { index, item ->
                                            if (index == 1) item.copy(rawText = item.rawText + " 추가 점심.") else item
                                        },
                                ),
                        )
            }
            bank += LinkedHashMap(word).apply { this["id"] = "t:alias" }
            fields(condition["temporal"])["term_evidence"] = "t:alias"
            val raw = F.json(fixture.document)
            val envelope = fixture.parse()
            assertNull(fixture.program(envelope, 1).program, fault)
            assertTrue(fixture.available(envelope, 1).isEmpty(), fault)
            assertTrue(fixture.result(envelope, 1).quarantined.any { it.problem == IrProblem.EVIDENCE }, fault)
            for (index in listOf(0, 2, 3)) assertNotNull(fixture.program(envelope, index).program, fault)
            assertEquals(raw, F.json(fixture.document))
        }
        val fixture = choice(nested = true)
        val baseline = fixture.parse()
        fixture.conditions(0).forEach { condition ->
            val bank = fixture.proofs(condition)
            bank += LinkedHashMap(bank.first()).apply { this["id"] = "p:alias" }
        }
        val aliased = fixture.parse()
        assertEquals(baseline, aliased)
        assertNotNull(fixture.program(aliased, 0).program)
        assertTrue(fixture.available(aliased, 0).isEmpty())
    }

    private data class Fixture(
        val input: ScheduleDslOfflineInput,
        val document: MutableMap<String, Any?>,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun resultWire(index: Int) = (document["results"] as List<MutableMap<String, Any?>>)[index]

        @Suppress("UNCHECKED_CAST")
        fun conditions(index: Int) = resultWire(index)["conditions"] as MutableList<MutableMap<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        fun proofs(condition: Map<String, Any?>) = condition["proofs"] as MutableList<MutableMap<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        fun programWire(index: Int) = resultWire(index)["program"] as MutableMap<String, Any?>

        fun parse() =
            ScheduleDslStructuralProviderContract(JsonMapper.builder().findAndAddModules().build()).parseProviderResponse(
                F.json(document),
                F.snapshot(input),
                "schedule-ir-product-6",
                "schedule-ast-1",
                "ko-time-terms-1",
            )

        fun result(
            envelope: ScheduleDslStructuralEnvelope,
            index: Int,
        ) = envelope.base.base.declaration.claims.results
            .single { it.submissionVersionId == input.inputs[index].submissionVersionId }

        fun claims(
            envelope: ScheduleDslStructuralEnvelope,
            index: Int,
        ) = result(envelope, index).conditions

        fun program(
            envelope: ScheduleDslStructuralEnvelope,
            index: Int,
        ) = envelope.base.base.declaration.programs
            .single { it.submissionVersionId == input.inputs[index].submissionVersionId }

        fun calculate(envelope: ScheduleDslStructuralEnvelope) =
            ScheduleDslStructuralCalculator.calculate(envelope, F.snapshot(input), input.policy, MeetingMode.REMOTE)

        fun available(
            envelope: ScheduleDslStructuralEnvelope,
            index: Int,
        ) = calculate(envelope)
            .preparedByMode
            .getValue(MeetingMode.REMOTE)
            .participants
            .single { it.participantId.value == input.inputs[index].participantId }
            .availableTimes
    }

    private fun four(): Fixture {
        val texts =
            listOf(
                "2027-01-08은 가능하고 2027-01-10은 불가예요.",
                FOUR_PREFERRED,
                "2027-01-08 늦은 오후부터 가능할 듯해요.",
                "2027-01-08은 가능해요.",
            )
        val original = F.input(texts.first())
        val answer =
            InputSupplement(
                QUESTION,
                3,
                "늦은 오후",
                texts[2].indexOf("늦은 오후"),
                "그 날짜의 시간대를 확인해 주세요.",
                ANSWER,
                null,
                F.ir.reference.plusSeconds(60),
                F.ir.reference.plusSeconds(60),
            )
        val input =
            original.copy(
                policy = N.policy,
                inputs =
                    texts.mapIndexed { index, text ->
                        original.inputs[index % 2].copy(
                            submissionId = if (index < 2) original.inputs[index].submissionId else UUID(0, 8100L + index),
                            submissionVersionId = if (index < 2) original.inputs[index].submissionVersionId else UUID(0, 8200L + index),
                            participantId = if (index < 2) original.inputs[index].participantId else UUID(0, 8300L + index),
                            rawText = text,
                            evidence = InputEvidence(3, F.ir.reference, if (index == 2) answer else null),
                        )
                    },
            )
        val context =
            claim(
                "context",
                "AVAILABLE",
                linkedMapOf(
                    "kind" to "BOUNDS",
                    "local_date" to "2027-01-08",
                    "date_evidence" to listOf("p:date"),
                    "lower" to linkedMapOf("clock" to "16:00", "clock_evidence" to listOf("p:clock")),
                    "upper" to null,
                ),
                mutableListOf(
                    proof("p:meaning", ANSWER, SUPPLEMENT_SOURCE),
                    proof("p:date", "2027-01-08"),
                    proof("p:clock", "늦은 오후", SUPPLEMENT_SOURCE),
                ),
            ).apply { this["interpretation"] = "INFERRED" }
        val groups =
            listOf(
                mutableListOf(day("day", "2027-01-08", texts[0]), day("blocked-day", "2027-01-10", texts[0], "UNAVAILABLE")),
                mutableListOf(
                    meal("fri-lunch", "2027-01-08", "LUNCH"),
                    meal("fri-evening", "2027-01-08", "EVENING"),
                    meal("sun-lunch", "2027-01-10", "LUNCH"),
                    meal("sun-evening", "2027-01-10", "EVENING"),
                ),
                mutableListOf(context),
                mutableListOf(day("whole", "2027-01-08", texts[3])),
            )
        return fixture(input, groups)
    }

    private fun simple(): Fixture {
        val text = "2027-01-08 14:00부터 20:00까지 가능하고 16:00부터 18:00까지 불가예요."
        val input = F.input(text).copy(policy = N.policy)
        return fixture(
            input,
            listOf(
                mutableListOf(rawClaim("left", text)),
                mutableListOf(rawClaim("peer", input.inputs[1].rawText, end = "2027-01-08T18:00")),
            ),
        )
    }

    private fun choice(nested: Boolean = false): Fixture {
        val text =
            if (nested) {
                "2027-01-08 14:00부터 20:00 구간에서 14:00부터 16:00 또는 18:00부터 20:00 중 하나. " +
                    "아니면 2027-01-10 14:00부터 20:00까지 가능해요."
            } else {
                "2027-01-08 14:00부터 20:00 또는 2027-01-10 14:00부터 20:00 중 하루만 가능해요."
            }
        val input = F.input(text).copy(policy = N.policy)
        val conditions = mutableListOf(rawClaim("left", text), rawClaim("right", text, "2027-01-10T14:00", "2027-01-10T20:00"))
        val fixture =
            fixture(
                input,
                listOf(
                    conditions,
                    mutableListOf(rawClaim("peer", input.inputs[1].rawText, end = "2027-01-08T18:00")),
                ),
            )
        val left =
            if (nested) {
                conditions += rawClaim("early", text, "2027-01-08T14:00", "2027-01-08T16:00")
                conditions += rawClaim("late", text, "2027-01-08T18:00", "2027-01-08T20:00")
                composite(
                    "ALL_OF",
                    listOf(
                        ref("left", text),
                        choiceExpression(
                            "inner",
                            listOf(branch("early", "early", ref("early", text)), branch("late", "late", ref("late", text))),
                            text,
                        ),
                    ),
                    text,
                )
            } else {
                ref("left", text)
            }
        fixture.resultWire(0)["conditions"] = conditions
        fixture.programWire(0)["availability_root"] =
            choiceExpression(
                "outer",
                listOf(branch("left", "left", left), branch("right", "right", ref("right", text))),
                text,
            )
        return fixture
    }

    private fun fixture(
        input: ScheduleDslOfflineInput,
        groups: List<List<Map<String, Any?>>>,
    ): Fixture =
        Fixture(
            input,
            linkedMapOf(
                "semantic_version" to "schedule-ir-product-6",
                "ast_version" to "schedule-ast-1",
                "vocabulary_version" to "ko-time-terms-1",
                "results" to
                    groups
                        .mapIndexed { i, conditions ->
                            linkedMapOf<String, Any?>(
                                "input_ref" to input.inputs[i].submissionVersionId.toString(),
                                "conditions" to conditions,
                                "program" to independent(conditions, input.inputs[i].rawText),
                                "approved_terms" to emptyList<Any>(),
                                "directional_times" to emptyList<Any>(),
                                "mode_restrictions" to emptyList<Any>(),
                            )
                        }.toMutableList(),
            ),
        )

    private fun day(
        id: String,
        date: String,
        text: String,
        effect: String = "AVAILABLE",
    ) = claim(
        id,
        effect,
        linkedMapOf("kind" to "WHOLE_DAY", "local_date" to date, "date_evidence" to listOf("p:date")),
        mutableListOf(proof("p:meaning", text), proof("p:date", date)),
    )

    private fun meal(
        id: String,
        date: String,
        term: String,
    ) = claim(
        id,
        "PREFERRED",
        linkedMapOf(
            "kind" to "APPROVED_TERM",
            "term" to term,
            "local_date" to date,
            "term_evidence" to "t:word",
            "date_evidence" to listOf("p:date"),
            "model_scope" to null,
        ),
        mutableListOf(proof("p:meaning", "좋아요."), proof("p:date", date), proof("t:word", if (term == "LUNCH") "점심" else "저녁")),
    )

    private fun rawClaim(
        id: String,
        text: String,
        start: String = "2027-01-08T14:00",
        end: String = "2027-01-08T20:00",
        effect: String = "AVAILABLE",
    ) = claim(
        id,
        effect,
        linkedMapOf("kind" to "RAW_SCOPE", "scope" to mapOf("dimension" to "TIME", "start" to start, "end" to end)),
        mutableListOf(proof("p:meaning", text)),
    ).apply { this["temporal_resolution"] = "PRECISE_INTERVAL" }

    private fun claim(
        id: String,
        effect: String,
        atom: Map<String, Any?>,
        bank: MutableList<MutableMap<String, Any?>>,
    ) = linkedMapOf<String, Any?>(
        "id" to id,
        "effect" to effect,
        "interpretation" to "EXPLICIT",
        "temporal_resolution" to "NON_PRECISE",
        "temporal" to atom,
        "proofs" to bank,
        "evidence" to listOf("p:meaning"),
        "assertion" to "ASSERTED",
        "supersedes" to emptyList<String>(),
    )

    private fun proof(
        id: String,
        quote: String,
        source: String = "original",
    ) = linkedMapOf<String, Any?>("id" to id, "source_id" to source, "revision" to 3, "quote" to quote)

    private fun ref(
        id: String,
        text: String,
    ) = composite("CLAIM_REF", emptyList(), text).apply { this["claim_id"] = id }

    private fun composite(
        operator: String,
        children: List<Map<String, Any?>>,
        text: String,
    ) = linkedMapOf<String, Any?>(
        "operator" to operator,
        "claim_id" to null,
        "children" to children,
        "interpretation" to "EXPLICIT",
        "assertion" to "ASSERTED",
        "evidence" to listOf(mapOf("source_id" to "original", "revision" to 3, "quote" to text)),
    )

    private fun branch(
        label: String,
        owner: String,
        expression: Map<String, Any?>,
    ) = linkedMapOf<String, Any?>("label" to label, "condition_ids" to listOf(owner), "expression" to expression)

    private fun choiceExpression(
        group: String,
        branches: List<Map<String, Any?>>,
        text: String,
    ) = linkedMapOf<String, Any?>(
        "operator" to "CHOICE",
        "choice_group" to group,
        "branches" to branches,
        "interpretation" to "EXPLICIT",
        "assertion" to "ASSERTED",
        "evidence" to listOf(mapOf("source_id" to "original", "revision" to 3, "quote" to text)),
    )

    private fun independent(
        conditions: List<Map<String, Any?>>,
        text: String,
    ): MutableMap<String, Any?> {
        fun root(effect: String): Map<String, Any?>? {
            val refs = conditions.filter { it["effect"] == effect }.map { ref(it["id"] as String, text) }
            return when (refs.size) {
                0 -> null
                1 -> refs.single()
                else -> composite("ANY_OF", refs, text)
            }
        }
        return linkedMapOf(
            "nodes" to emptyList<Any>(),
            "availability_root" to root("AVAILABLE"),
            "exclusion_root" to root("UNAVAILABLE"),
            "preference_root" to root("PREFERRED"),
            "history_condition_ids" to emptyList<String>(),
        )
    }

    private fun legacy(fixture: Fixture) {
        for (i in fixture.input.inputs.indices) {
            fixture.conditions(i).forEach { condition ->
                val bank = fixture.proofs(condition).associateBy { it["id"] as String }

                fun expand(value: Any?): Any? =
                    when (value) {
                        is String -> bank[value]?.filterKeys { it != "id" } ?: value
                        is List<*> -> value.map(::expand)
                        is Map<*, *> -> value.entries.associate { it.key.toString() to expand(it.value) }.toMutableMap()
                        else -> value
                    }
                condition["evidence"] = expand(condition["evidence"])
                condition["temporal"] = expand(condition["temporal"])
                condition.remove("proofs")
                condition["alternative_group"] = null
                condition["alternative"] = null
            }

            fun oldExpression(value: Any?): Any? {
                if (value == null) return null
                val node = fields(value)

                @Suppress("UNCHECKED_CAST")
                val children = node["children"] as List<Any?>
                node["children"] = children.map(::oldExpression)
                node["choice_group"] = null
                node["branch_labels"] = emptyList<String>()
                return node
            }
            listOf("availability_root", "exclusion_root", "preference_root").forEach { key ->
                fixture.programWire(i)[key] = oldExpression(fixture.programWire(i)[key])
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun map(value: Any?) = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun properties(value: Any?) = map(map(value)["properties"])

    private fun item(value: Any?) = map(value)["items"]

    private fun strings(value: Any?) = (value as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun variants(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = map(value)
        val reference = schema["\$ref"] as? String
        if (reference != null) return variants(map(root["\$defs"])[reference.substringAfterLast('/')], root)
        val alternatives = schema["anyOf"] ?: schema["oneOf"]
        return if (alternatives is List<*>) alternatives.flatMap { variants(it, root) } else listOf(schema)
    }

    private fun objects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(map(value)) + value.values.flatMap(::objects)
            is List<*> -> value.flatMap(::objects)
            else -> emptyList()
        }

    private fun window(
        start: String,
        end: String,
    ) = DateScopeInlineFixture.window(start, end)

    companion object {
        private const val FOUR_PREFERRED = "2027-01-08과 2027-01-10의 점심과 저녁만 좋아요."
        private val QUESTION = UUID(0, 9111)
        private val SUPPLEMENT_SOURCE = "supplement:$QUESTION"
        private const val ANSWER = "그 날짜의 늦은 오후부터 가능하다는 뜻이에요."
        private val LEXEMES = setOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일")
    }
}
