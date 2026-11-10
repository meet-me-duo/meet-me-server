package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic, independently declared semantics. No saved private response or live-model assertion. */
class ScheduleDslConditionTermBindingContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `CB01 four date and meal bindings preserve separate original conditions and intervening gaps`() {
        val fixture = fourBindings()
        val raw = F.json(fixture.document)
        val envelope = fixture.parse()
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
        val claims = fixture.firstClaims(envelope)
        assertEquals(setOf("fri-lunch", "fri-evening", "sun-lunch", "sun-evening"), claims.map { it.id }.toSet())
        assertTrue(claims.all { it.effect == IrEffect.AVAILABLE && it.scope == null })
        assertTrue(
            claims.all { it.interpretation == IrInterpretation.EXPLICIT && it.temporalResolution == IrTemporalResolution.NON_PRECISE },
        )
        val terms = envelope.base.base.termApplications
        assertEquals(4, terms.size)
        assertEquals(4, terms.map { it.conditionId }.distinct().size)
        assertEquals(setOf("2027-01-08", "2027-01-10"), terms.map { it.localDate.toString() }.toSet())
        assertEquals(setOf("LUNCH", "EVENING"), terms.map { it.term.name }.toSet())
        assertTrue(terms.all { it.derivation == ApprovedTimeTermDerivation.SERVER_VOCABULARY && it.declaredScope == null })
        assertEquals(
            listOf(
                window("2027-01-08T11:00", "2027-01-08T15:00"),
                window("2027-01-08T17:00", "2027-01-08T22:00"),
                window("2027-01-10T11:00", "2027-01-10T15:00"),
                window("2027-01-10T17:00", "2027-01-10T22:00"),
            ),
            fixture.available(envelope),
        )
        assertEquals(raw, F.json(fixture.document), "Parsing does not rewrite the original wire")
    }

    @Test
    fun `CB02 attached terms retain negatives preference availability and explicit availability clipping`() {
        val negative =
            fixture(
                "2027-01-08 점심과 저녁 가능. 그날 두 번째 시간대는 불가하고 첫 번째 시간대는 선호해요.",
                listOf(
                    Spec("lunch", "LUNCH"),
                    Spec("evening", "EVENING"),
                    Spec("blocked", "EVENING", effect = IrEffect.UNAVAILABLE),
                    Spec("preferred", "LUNCH", effect = IrEffect.PREFERRED),
                ),
            )
        val checked = negative.parse()
        assertEquals(listOf(window("2027-01-08T11:00", "2027-01-08T15:00")), negative.available(checked))
        assertEquals(IrEffect.UNAVAILABLE, negative.firstClaims(checked).single { it.id == "blocked" }.effect)
        assertEquals(IrEffect.PREFERRED, negative.firstClaims(checked).single { it.id == "preferred" }.effect)
        val preferenceOnly = fixture("2027-01-08 저녁을 선호해요.", listOf(Spec("preferred", "EVENING", effect = IrEffect.PREFERRED)))
        assertEquals(
            listOf(window("2027-01-08T17:00", "2027-01-08T22:00")),
            preferenceOnly.available(preferenceOnly.parse()),
        )
        val clipped =
            fixture(
                "2027-01-08 18시부터 19시까지 가능하고 저녁을 선호해요.",
                listOf(
                    Spec("literal", null, scope = F.scope("2027-01-08T18:00", "2027-01-08T19:00")),
                    Spec("preferred", "EVENING", effect = IrEffect.PREFERRED),
                ),
            )
        val envelope = clipped.parse()
        val expected = listOf(window("2027-01-08T18:00", "2027-01-08T19:00"))
        assertEquals(expected, clipped.available(envelope))
        val prepared =
            ScheduleDslStructuralCalculator
                .prepare(envelope, F.snapshot(clipped.input), clipped.input.policy, MeetingMode.REMOTE)
                .preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
        assertEquals(expected, prepared.explicitPreferences.timeRanges)
    }

    @Test
    fun `CB03 mutually exclusive dates remain CHOICE and contextual estimates retain their original provenance`() {
        val choice =
            fixture(
                "점심은 2027-01-08 또는 2027-01-10 중 하루만 가능해요.",
                listOf(
                    Spec("fri", "LUNCH", alternative = "fri"),
                    Spec("sun", "LUNCH", day = "2027-01-10", alternative = "sun"),
                ),
            ) { input ->
                F.program(
                    listOf(
                        F.ref(input, "fri-ref", "fri"),
                        F.ref(input, "sun-ref", "sun"),
                        F
                            .node(input, "choice", ScheduleDslOperator.CHOICE, listOf("fri-ref", "sun-ref"))
                            .copy(choiceGroup = "date-choice", branchLabels = listOf("fri", "sun")),
                    ),
                    "choice",
                )
            }
        val unresolved = choice.parse()
        assertNotNull(choice.firstProgram(unresolved).program)
        assertTrue(
            choice
                .firstProgram(unresolved)
                .program!!
                .nodes
                .any { it.operator == ScheduleDslOperator.CHOICE },
        )
        assertEquals(2, unresolved.base.base.termApplications.size)
        assertTrue(choice.available(unresolved).isEmpty(), "Two possible dates do not become independent available dates")
        val context =
            fixture(
                "2027-01-08 저녁은 18시 반쯤부터 21시쯤까지 가능할 듯해요.",
                listOf(
                    Spec(
                        "context",
                        "EVENING",
                        interpretation = IrInterpretation.INFERRED,
                        scope = F.scope("2027-01-08T18:30", "2027-01-08T21:00"),
                    ),
                ),
            )
        val estimated = context.parse()
        val original = context.firstClaims(estimated).single()
        assertEquals(IrInterpretation.INFERRED, original.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, original.temporalResolution)
        assertEquals(F.scope("2027-01-08T18:30", "2027-01-08T21:00"), original.scope)
        assertEquals(
            ApprovedTimeTermDerivation.MODEL_CONTEXT,
            estimated.base.base.termApplications
                .single()
                .derivation,
        )
        assertEquals(listOf(window("2027-01-08T18:30", "2027-01-08T21:00")), context.available(estimated))
    }

    @Test
    fun `CB04 mixed duplicate malformed and misowned bindings fail closed without dropping a negative`() {
        val faults =
            listOf(
                "mixed-cohort",
                "mixed-flat",
                "duplicate-id",
                "binding-owner",
                "date",
                "source",
                "role",
                "negative-evidence",
                "calendar",
                "directional",
            )
        for (fault in faults) {
            val fixture = fourBindings()
            val conditions = fixture.conditions()
            val attached = fields(conditions.first()["approved_term"])
            when (fault) {
                "mixed-cohort" -> conditions.last().remove("approved_term")
                "mixed-flat" -> fixture.first()["approved_terms"] = listOf(attached + ("condition_id" to "fri-lunch"))
                "duplicate-id" -> conditions.last()["id"] = conditions.first()["id"]
                "binding-owner" -> attached["condition_id"] = "sun-evening"
                "date" -> attached["local_date"] = "2027-02-30"
                "source" -> fields(attached["term_evidence"])["source_id"] = "question:unavailable"
                "role" -> conditions.first()["effect"] = "UNAVAILABLE"
                "negative-evidence" -> {
                    conditions.last()["effect"] = "UNAVAILABLE"
                    val program = fields(fixture.first()["program"])
                    val availability = fields(program["availability_root"])

                    @Suppress("UNCHECKED_CAST")
                    val children = availability["children"] as List<Map<String, Any?>>
                    availability["children"] = children.dropLast(1)
                    program["exclusion_root"] = children.last()
                    fields(fields(conditions.last()["approved_term"])["term_evidence"])["quote"] = FOUR_DATES
                }
                "calendar" -> conditions.first()["scope"] = mapOf("dimension" to "TIME", "start" to "2027-01-08", "end" to null)
                else ->
                    fixture.first()["directional_times"] =
                        listOf(DateScopeInlineFixture.side("AFTER") + ("condition_id" to "fri-lunch"))
            }
            val raw = F.json(fixture.document)
            val result = runCatching { fixture.parse() }
            val envelope =
                if (fault in setOf("date", "source", "role", "negative-evidence")) result.getOrThrow() else result.getOrNull()
            if (envelope != null) {
                assertNull(fixture.firstProgram(envelope).program, "A bad binding must not select one date or discard an exclusion: $fault")
                assertTrue(fixture.available(envelope).isEmpty())
                assertNotNull(
                    envelope.base.base.declaration.programs
                        .single { it.submissionVersionId == F.ir.second }
                        .program,
                )
            }
            assertEquals(raw, F.json(fixture.document))
        }
        val duplicateKey = fourBindings()
        val raw =
            F.json(duplicateKey.document).replaceFirst(
                "\"approved_term\":null",
                "\"approved_term\":null,\"approved_term\":null",
            )
        assertFailsWith<NaturalLanguageParserException> {
            contract.parseProviderResponse(
                raw,
                F.snapshot(duplicateKey.input),
                "schedule-ir-product-6",
                "schedule-ast-1",
                "ko-time-terms-1",
            )
        }
    }

    @Test
    fun `CB05 legacy flat terms stay equivalent and date or unknown scopes retain their established meaning`() {
        val attached = fourBindings()
        val old = fourBindings()
        toLegacy(old)
        val raw = F.json(old.document)
        val current = attached.parse()
        val legacy = old.parse()
        assertEquals(current.base.base.declaration, legacy.base.base.declaration)
        assertEquals(current.base.base.termApplications, legacy.base.base.termApplications)
        assertEquals(attached.available(current), old.available(legacy))
        assertEquals(raw, F.json(old.document))
        @Suppress("UNCHECKED_CAST")
        val refs = old.first()["approved_terms"] as List<MutableMap<String, Any?>>
        refs.last()["condition_id"] = refs.first()["condition_id"]
        assertFailsWith<NaturalLanguageParserException> { old.parse() }
        val calendar = DateScopeInlineFixture.dateOnly()
        addNullBindings(calendar)
        val day = calendar.parse()
        assertEquals(4, day.directionalApplications.size)
        assertTrue(
            day.base.base.termApplications
                .isEmpty(),
        )
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-09T00:00")), calendar.available(day))
        val unknown = DateScopeInlineFixture.unknown()
        addNullBindings(unknown)
        val missing = unknown.parse()
        assertNull(unknown.firstClaims(missing).single().scope)
        assertTrue(missing.directionalApplications.isEmpty())
        assertTrue(unknown.available(missing).isEmpty())
    }

    @Test
    fun `CB06 producer temporal union owns one term binding while preserving C4 and native inputs`() {
        val capsule = N.capsule()
        val old = ScheduleDslContextModeProviderContract(mapper)
        val before = old.requestBytes(capsule, MeetingMode.EITHER)
        val beforeSchema = mapper.writeValueAsBytes(old.responseSchema())
        val previous = mapper.readTree(before)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("c5-owned-proof-choice-2", request.path("producer_schema_revision").asString())
        assertEquals("schedule-dsl-response-5", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-6", request.path("semantic_version").asString())
        assertEquals(previous.path("inputs"), request.path("inputs"))
        assertEquals(previous.path("context"), request.path("context"))
        val schema = contract.responseSchema()

        fun schemaAlternatives(value: Any?): List<Map<String, Any?>> {
            val objectSchema = map(value)
            val reference = objectSchema["\$ref"] as? String
            if (reference != null) {
                return schemaAlternatives(map(schema["\$defs"])[reference.substringAfterLast('/')])
            }
            val choices = objectSchema["anyOf"] as? List<*>
            return choices?.flatMap(::schemaAlternatives) ?: listOf(objectSchema)
        }
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = schemaAlternatives(item(result.getValue("conditions")))
        conditions.forEach { assertTrue("temporal" in strings(it["required"])) }
        val atoms = conditions.flatMap { schemaAlternatives(properties(it).getValue("temporal")) }
        val binding = atoms.single { "APPROVED_TERM" in strings(map(properties(it)["kind"])["enum"]) }
        assertEquals(setOf("kind", "term", "local_date", "term_evidence", "date_evidence", "model_scope"), properties(binding).keys)
        assertFalse("condition_id" in properties(binding))
        val termEvidence = properties(binding).getValue("term_evidence")
        assertEquals("string", map(termEvidence)["type"])
        conditions.forEach { condition ->
            val bank = map(item(properties(condition).getValue("proofs")))["anyOf"] as List<*>
            val termProof = bank.single { "enum" in map(properties(it)["quote"]) }
            assertEquals(setOf("id", "source_id", "revision", "quote"), properties(termProof).keys)
            assertEquals(
                setOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일"),
                strings(map(properties(termProof)["quote"])["enum"]),
            )
        }
        assertEquals(0, map(result["approved_terms"])["maxItems"])
        assertEquals(
            setOf("condition_id", "term", "local_date", "term_evidence", "date_evidence"),
            properties(item(result.getValue("approved_terms"))).keys,
        )
        objects(schema).filter { "properties" in it }.forEach {
            assertEquals(false, it["additionalProperties"])
            assertEquals(properties(it).keys, strings(it["required"]))
        }
        assertContentEquals(before, old.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(beforeSchema, mapper.writeValueAsBytes(old.responseSchema()))
    }

    private data class Spec(
        val id: String,
        val term: String?,
        val day: String = "2027-01-08",
        val effect: IrEffect = IrEffect.AVAILABLE,
        val interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        val scope: IrScope? = null,
        val alternative: String? = null,
    )

    private fun fourBindings() =
        fixture(
            FOUR_DATES,
            listOf(
                Spec("fri-lunch", "LUNCH"),
                Spec("fri-evening", "EVENING"),
                Spec("sun-lunch", "LUNCH", "2027-01-10"),
                Spec("sun-evening", "EVENING", "2027-01-10"),
            ),
        )

    private fun fixture(
        text: String,
        specs: List<Spec>,
        graph: ((ScheduleDslOfflineInput) -> ScheduleDslProgram<ProposedIrEvidence>)? = null,
    ): DateScopeInlineFixture.Fixture {
        val input = F.input(text).copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val claims =
            specs.map {
                F.claim(input, it.id, it.effect, it.scope).copy(
                    interpretation = it.interpretation,
                    temporalResolution =
                        if (it.scope == null || it.interpretation == IrInterpretation.INFERRED) {
                            IrTemporalResolution.NON_PRECISE
                        } else {
                            IrTemporalResolution.PRECISE_INTERVAL
                        },
                    alternativeGroup = it.alternative?.let { "date-choice" },
                    alternative = it.alternative,
                )
            }
        val program = graph?.invoke(input) ?: independent(input, specs)
        val document = fields(locators(F.document(F.proposal(input, claims, program, emptyList()))))
        val fixture = DateScopeInlineFixture.Fixture(input, document)
        document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        (document["results"] as List<MutableMap<String, Any?>>).forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
        addNullBindings(fixture)
        fixture.conditions().zip(specs).forEach { (condition, spec) ->
            condition["approved_term"] =
                spec.term?.let {
                    linkedMapOf(
                        "term" to it,
                        "local_date" to spec.day,
                        "term_evidence" to locator(if (it == "LUNCH") "점심" else "저녁"),
                        "date_evidence" to listOf(locator(spec.day)),
                    )
                }
        }
        inlineProgram(fixture)
        return fixture
    }

    private fun independent(
        input: ScheduleDslOfflineInput,
        specs: List<Spec>,
    ): ScheduleDslProgram<ProposedIrEvidence> {
        val nodes = specs.map { F.ref(input, "${it.id}-ref", it.id) }.toMutableList()

        fun root(effect: IrEffect): String? {
            val ids = specs.filter { it.effect == effect }.map { "${it.id}-ref" }
            if (ids.isEmpty()) return null
            if (ids.size == 1) return ids.single()
            val id = effect.name.lowercase() + "-root"
            nodes += F.node(input, id, ScheduleDslOperator.ANY_OF, ids)
            return id
        }
        val allow = root(IrEffect.AVAILABLE)
        val block = root(IrEffect.UNAVAILABLE)
        val prefer = root(IrEffect.PREFERRED)
        return F.program(nodes, allow, block, prefer)
    }

    private fun inlineProgram(fixture: DateScopeInlineFixture.Fixture) {
        val program = fields(fixture.first()["program"])

        @Suppress("UNCHECKED_CAST")
        val nodes = (program["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] as String }

        fun expression(id: String): Map<String, Any?> {
            val node = nodes.getValue(id)

            @Suppress("UNCHECKED_CAST")
            val children = node["children"] as List<String>
            return node.filterKeys { it != "id" } + ("children" to children.map(::expression))
        }
        for (key in listOf("availability_root", "exclusion_root", "preference_root")) {
            program[key] = (program[key] as? String)?.let(::expression)
        }
        program["nodes"] = emptyList<Any>()
    }

    private fun addNullBindings(fixture: DateScopeInlineFixture.Fixture) {
        @Suppress("UNCHECKED_CAST")
        (fixture.document["results"] as List<MutableMap<String, Any?>>).forEach { result ->
            @Suppress("UNCHECKED_CAST")
            (result["conditions"] as List<MutableMap<String, Any?>>).forEach { it["approved_term"] = null }
        }
    }

    private fun toLegacy(fixture: DateScopeInlineFixture.Fixture) {
        @Suppress("UNCHECKED_CAST")
        (fixture.document["results"] as List<MutableMap<String, Any?>>).forEach { result ->
            @Suppress("UNCHECKED_CAST")
            val conditions = result["conditions"] as List<MutableMap<String, Any?>>
            result["approved_terms"] =
                conditions.mapNotNull { condition ->
                    val binding = condition.remove("approved_term") as? Map<*, *>
                    binding?.let { LinkedHashMap(map(it) + ("condition_id" to condition["id"])) }
                }
        }
    }

    private fun locator(quote: String) = linkedMapOf<String, Any?>("source_id" to "original", "revision" to 3, "quote" to quote)

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun map(value: Any?) = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun properties(value: Any?) = map(map(value)["properties"])

    private fun item(value: Any?) = map(value)["items"]

    private fun strings(value: Any?) = (value as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun objects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(map(value)) + value.values.flatMap(::objects)
            is List<*> -> value.flatMap(::objects)
            else -> emptyList()
        }

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                if ("start_code_point" in value) {
                    value.entries
                        .filter { it.key in setOf("source_id", "revision", "quote") }
                        .associate { it.key.toString() to it.value }
                        .toMutableMap()
                } else {
                    value.entries.associate { it.key.toString() to locators(it.value) }.toMutableMap()
                }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }

    private fun window(
        start: String,
        end: String,
    ) = DateScopeInlineFixture.window(start, end)

    companion object {
        private const val FOUR_DATES = "2027-01-08과 2027-01-10은 점심과 저녁이 가능해요."
    }
}
