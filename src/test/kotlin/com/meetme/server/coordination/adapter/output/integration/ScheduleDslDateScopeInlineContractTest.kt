package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.DirectionalTimeDeclaration
import com.meetme.server.submission.domain.dsl.ProposedLiteralQuoteLocator
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralNormalizer
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.StructuralTimeDerivation
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic wire/meaning contracts. These tests make no model-quality or live-provider assertion. */
class ScheduleDslDateScopeInlineContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `DI01 date scopes retain all effects and provenance while only the projection gets civil bounds`() {
        val fixture = DateScopeInlineFixture.dateOnly()
        fixture.conditions().first()["evidence"] =
            listOf(
                mapOf(
                    "source_id" to "original",
                    "revision" to 3,
                    "quote" to
                        fixture.input.inputs
                            .first()
                            .rawText,
                ),
                mapOf("source_id" to "original", "revision" to 3, "quote" to "선호해요."),
            )
        val envelope = fixture.parse()
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
        val declared = fixture.firstClaims(envelope)
        assertEquals(setOf("day", "other-day", "blocked", "preferred"), declared.map { it.id }.toSet())
        assertTrue(declared.all { it.scope == null && it.temporalResolution == IrTemporalResolution.NON_PRECISE })
        assertEquals(IrEffect.UNAVAILABLE, declared.single { it.id == "blocked" }.effect)
        assertEquals(IrEffect.PREFERRED, declared.single { it.id == "preferred" }.effect)
        assertEquals(IrInterpretation.INFERRED, declared.single { it.id == "preferred" }.interpretation)
        val applications = envelope.directionalApplications.filter { it.submissionVersionId == F.ir.first }
        assertEquals(4, applications.size)
        applications.forEach { application ->
            val original = declared.single { it.id == application.declaration.conditionId }
            assertEquals(TemporalDirection.WHOLE_DAY, application.declaration.direction)
            assertNull(application.declaration.clock)
            assertTrue(application.declaration.clockEvidence.isEmpty())
            assertEquals(original.evidence, application.declaration.dateEvidence.map { it.span })
            assertEquals(original.interpretation, application.declaration.interpretation)
            assertEquals(StructuralTimeDerivation.SERVER_CALENDAR_DAY, application.derivation)
            assertEquals(original.assertion, fixture.projected(envelope).single { it.id == original.id }.assertion)
        }
        assertTrue(fixture.projected(envelope).all { it.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL })
        assertTrue(
            envelope.base.base.termApplications
                .isEmpty(),
            "Date-only scope must not fabricate ALL_DAY evidence",
        )
        assertTrue(envelope.base.contextApplications.isEmpty(), "Civil-day expansion is not a model numeric estimate")
        assertEquals(listOf(DateScopeInlineFixture.window("2027-01-08T00:00", "2027-01-09T00:00")), fixture.available(envelope))
        val day = applications.single { it.declaration.conditionId == "day" }
        assertEquals(2, day.declaration.dateEvidence.size)
        val narrowed = day.copy(declaration = day.declaration.copy(dateEvidence = day.declaration.dateEvidence.take(1)))
        val tampered = envelope.copy(directionalApplications = envelope.directionalApplications.map { if (it == day) narrowed else it })
        assertFails { ScheduleDslStructuralValidator().validateRestored(tampered, F.snapshot(fixture.input)) }
    }

    @Test
    fun `DI02 unknown scopes stay null and legacy directional bounds still require actual clocks`() {
        val unknown = DateScopeInlineFixture.unknown()
        val envelope = unknown.parse()
        assertNull(unknown.firstClaims(envelope).single().scope)
        assertNull(unknown.projected(envelope).single().scope)
        assertTrue(envelope.directionalApplications.isEmpty())
        assertTrue(unknown.available(envelope).isEmpty())
        for (direction in listOf("AFTER", "BEFORE")) {
            val fixture = DateScopeInlineFixture.directional(direction)
            val parsed = fixture.parse()
            val application = parsed.directionalApplications.single()
            assertEquals(TemporalDirection.valueOf(direction), application.declaration.direction)
            assertEquals(LocalTime.of(16, 0), application.declaration.clock)
            assertEquals(StructuralTimeDerivation.SERVER_DATE_SCOPE, application.derivation)
            assertNull(fixture.firstClaims(parsed).single().scope)
            val expected =
                if (direction == "AFTER") {
                    DateScopeInlineFixture.window("2027-01-08T16:00", "2027-01-09T00:00")
                } else {
                    DateScopeInlineFixture.window("2027-01-08T00:00", "2027-01-08T16:00")
                }
            assertEquals(listOf(expected), fixture.available(parsed))
            fixture.first()["directional_times"] =
                listOf(DateScopeInlineFixture.side(direction).apply { this["clock"] = null })
            assertFails { fixture.parse() }
        }
    }

    @Test
    fun `DI03 whole days follow the frozen zone across DST and reject a skipped civil day`() {
        for ((date, hours) in listOf("2027-03-14" to 23L, "2027-11-07" to 25L)) {
            val input = DateScopeInlineFixture.zonedInput(date, "America/Los_Angeles")
            val snapshot = F.snapshot(input)
            val proof = ScheduleDslStructuralNormalizer.locate(F.ir.first, ProposedLiteralQuoteLocator("original", 3, date), snapshot)
            val declaration =
                DirectionalTimeDeclaration("day", TemporalDirection.WHOLE_DAY, LocalDate.parse(date), null, listOf(proof), emptyList())
            val application = ScheduleDslStructuralNormalizer.resolve(F.ir.first, declaration, snapshot)
            assertEquals(StructuralTimeDerivation.SERVER_CALENDAR_DAY, application.derivation)
            assertEquals(LocalDate.parse(date).atStartOfDay(), application.resolvedScope.start)
            assertEquals(LocalDate.parse(date).plusDays(1).atStartOfDay(), application.resolvedScope.end)
            val zone = ZoneId.of(input.timeZone)
            assertEquals(
                hours,
                Duration
                    .between(
                        requireNotNull(application.resolvedScope.start).atZone(zone).toInstant(),
                        requireNotNull(application.resolvedScope.end).atZone(zone).toInstant(),
                    ).toHours(),
            )
            assertEquals(application, ScheduleDslStructuralNormalizer.restoreTime(F.ir.first, application, snapshot))
        }
        val input = DateScopeInlineFixture.zonedInput("2011-12-30", "Pacific/Apia")
        val snapshot = F.snapshot(input)
        val proof = ScheduleDslStructuralNormalizer.locate(F.ir.first, ProposedLiteralQuoteLocator("original", 3, "2011-12-30"), snapshot)
        assertFails {
            ScheduleDslStructuralNormalizer.resolve(
                F.ir.first,
                DirectionalTimeDeclaration(
                    "day",
                    TemporalDirection.WHOLE_DAY,
                    LocalDate.of(2011, 12, 30),
                    null,
                    listOf(proof),
                    emptyList(),
                ),
                snapshot,
            )
        }
    }

    @Test
    fun `DI04 inline and legacy programs calculate equally with deterministic reused server node IDs`() {
        val legacy = DateScopeInlineFixture.numeric(inline = false)
        val inline = DateScopeInlineFixture.numeric(inline = true)
        val oldEnvelope = legacy.parse()
        val envelope = inline.parse()
        val program = assertNotNull(inline.firstProgram(envelope).program)
        assertEquals(3, program.nodes.size, "The identical BLOCK expression is reused across EXCEPT and exclusion_root")
        assertEquals(
            program.nodes.size,
            program.nodes
                .map { it.id }
                .toSet()
                .size,
        )
        assertTrue(program.nodes.all { it.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,63}")) })
        assertEquals(program.exclusionRoot, program.nodes.single { it.id == program.availabilityRoot }.children[1])
        assertEquals(program, inline.firstProgram(inline.parse()).program)
        assertEquals(legacy.available(oldEnvelope), inline.available(envelope))
        assertEquals(
            listOf(
                DateScopeInlineFixture.window("2027-01-08T14:00", "2027-01-08T16:00"),
                DateScopeInlineFixture.window("2027-01-08T17:00", "2027-01-08T20:00"),
            ),
            inline.available(envelope),
        )
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(inline.input)))
        assertTrue(
            legacy
                .firstProgram(oldEnvelope)
                .program!!
                .nodes
                .any { it.id == "allow-ref" },
            "Legacy IDs remain intact",
        )
    }

    @Test
    fun `DI05 inline coverage and role failures retain rejection instead of dropping live negatives`() {
        for (fault in listOf("omitted-negative", "wrong-role", "omitted-unresolved")) {
            val fixture = DateScopeInlineFixture.numeric(inline = true, subtract = false)
            val program = DateScopeInlineFixture.fields(fixture.first()["program"])
            when (fault) {
                "omitted-negative" -> program["exclusion_root"] = null
                "wrong-role" -> {
                    val allow = program["availability_root"]
                    program["availability_root"] = program["exclusion_root"]
                    program["exclusion_root"] = allow
                }
                else -> {
                    val claims = fixture.conditions()
                    claims +=
                        LinkedHashMap(claims.first()).apply {
                            this["id"] = "unresolved"
                            this["effect"] = "UNRESOLVED"
                            this["interpretation"] = "UNRESOLVED"
                            this["scope"] = null
                            this["temporal_resolution"] = "NON_PRECISE"
                        }
                }
            }
            val original = F.json(fixture.document)
            val envelope = fixture.parse()
            val rejected = fixture.firstProgram(envelope)
            assertNull(rejected.program)
            assertTrue((if (fault == "wrong-role") ScheduleDslProblem.ROLE else ScheduleDslProblem.REFERENCE) in rejected.problems)
            assertTrue(fixture.available(envelope).isEmpty())
            assertNotNull(
                envelope.base.base.declaration.programs
                    .single { it.submissionVersionId == F.ir.second }
                    .program,
            )
            assertEquals(original, F.json(fixture.document))
        }
    }

    @Test
    fun `DI06 malformed date markers mixed program styles and mismatched native locators are never accepted`() {
        val faults =
            listOf(
                "date-end",
                "date-extra",
                "date-missing-end",
                "precise-date",
                "unresolved-date",
                "mixed-nodes",
                "mixed-roots",
                "extra-field",
                "revision",
            )
        for (fault in faults) {
            val fixture = DateScopeInlineFixture.dateOnly()
            when (fault) {
                "date-end" -> DateScopeInlineFixture.fields(fixture.conditions().first()["scope"])["end"] = "2027-01-09"
                "date-extra" -> DateScopeInlineFixture.fields(fixture.conditions().first()["scope"])["unexpected"] = true
                "date-missing-end" -> DateScopeInlineFixture.fields(fixture.conditions().first()["scope"]).remove("end")
                "precise-date" -> fixture.conditions().first()["temporal_resolution"] = "PRECISE_INTERVAL"
                "unresolved-date" -> fixture.conditions().first()["effect"] = "UNRESOLVED"
                "mixed-nodes" ->
                    DateScopeInlineFixture.fields(fixture.first()["program"])["nodes"] =
                        listOf(mapOf("id" to "provider-id"))
                "mixed-roots" -> DateScopeInlineFixture.fields(fixture.first()["program"])["exclusion_root"] = "provider-id"
                "extra-field" ->
                    DateScopeInlineFixture.fields(
                        DateScopeInlineFixture.fields(fixture.first()["program"])["availability_root"],
                    )["id"] = "provider-id"
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val evidence = fixture.conditions().first()["evidence"] as List<MutableMap<String, Any?>>
                    evidence.single()["revision"] = 99
                }
            }
            val outcome = runCatching { fixture.parse() }
            if (outcome.isSuccess) {
                val envelope = outcome.getOrThrow()
                assertNull(fixture.firstProgram(envelope).program, "A malformed declaration cannot produce a usable program: $fault")
                assertTrue(fixture.available(envelope).isEmpty())
            }
        }
        val stale = DateScopeInlineFixture.dateOnly()

        @Suppress("UNCHECKED_CAST")
        val evidence = stale.conditions().first()["evidence"] as List<MutableMap<String, Any?>>
        evidence.single()["quote"] = "absent native evidence"
        val result = runCatching { stale.parse() }
        if (result.isSuccess) assertTrue(stale.available(result.getOrThrow()).isEmpty())
    }

    @Test
    fun `DI07 inline expansion obeys depth eight and node count 128 before calculation`() {
        for ((depth, accepted) in listOf(8 to true, 9 to false)) {
            val fixture = DateScopeInlineFixture.bounded(depth = depth)
            val result = runCatching { fixture.parse() }
            if (accepted) {
                assertNotNull(fixture.firstProgram(result.getOrThrow()).program)
            } else if (result.isSuccess) {
                assertNull(fixture.firstProgram(result.getOrThrow()).program)
                assertTrue(fixture.available(result.getOrThrow()).isEmpty())
            }
        }
        for ((count, accepted) in listOf(128 to true, 129 to false)) {
            val fixture = DateScopeInlineFixture.bounded(count = count)
            val result = runCatching { fixture.parse() }
            if (accepted) {
                assertEquals(count, assertNotNull(fixture.firstProgram(result.getOrThrow()).program).nodes.size)
            } else if (result.isSuccess) {
                assertNull(fixture.firstProgram(result.getOrThrow()).program)
                assertTrue(fixture.available(result.getOrThrow()).isEmpty())
            }
        }
    }

    @Test
    fun `DI08 producer grammar exposes typed dates and inline roots without changing native context or C4 bytes`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val oldRequest = c4.requestBytes(capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        val previous = mapper.readTree(oldRequest)
        assertEquals("c5-owned-proof-choice-2", request.path("producer_schema_revision").asString())
        assertEquals("schedule-dsl-response-5", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-6", request.path("semantic_version").asString())
        assertEquals(previous.path("inputs"), request.path("inputs"))
        assertEquals(previous.path("context"), request.path("context"))
        val schema = contract.responseSchema()
        val results = properties(item(properties(schema).getValue("results")))
        assertEquals(setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions", "directional_times"), results.keys)
        val conditions = variants(item(results.getValue("conditions")), schema)
        val atoms = conditions.flatMap { variants(properties(it).getValue("temporal"), schema) }
        val date =
            atoms.single {
                "WHOLE_DAY" in strings(fields(properties(it)["kind"])["enum"]) ||
                    fields(properties(it)["kind"])["const"] == "WHOLE_DAY"
            }
        assertEquals(setOf("kind", "local_date", "date_evidence"), properties(date).keys)
        val localDate = fields(properties(date)["local_date"])
        val pattern = localDate["pattern"] as? String
        assertTrue(
            localDate["format"] == "date" ||
                (pattern != null && Regex(pattern).matches("2027-01-08") && !Regex(pattern).matches("2027-01-08T00:00")),
        )
        val program = properties(results.getValue("program"))
        assertEquals(setOf("nodes", "availability_root", "exclusion_root", "preference_root", "history_condition_ids"), program.keys)
        assertEquals(0, fields(program["nodes"])["maxItems"])
        val roots = variants(program.getValue("availability_root"), schema)
        assertTrue(roots.any { strings(it["type"]) == setOf("null") })
        val expression = roots.single { "CLAIM_REF" in strings(fields(properties(it)["operator"])["enum"]) }
        assertEquals(
            setOf("operator", "claim_id", "children", "interpretation", "assertion", "evidence"),
            properties(expression).keys,
        )
        assertTrue(fields(properties(expression)["children"])["items"] is Map<*, *>)
        assertEquals(0, fields(properties(expression)["children"])["minItems"])
        val term = properties(item(results.getValue("approved_terms")))
        val termEvidence = properties(term.getValue("term_evidence"))
        assertEquals(
            setOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일"),
            strings(fields(termEvidence["quote"])["enum"]),
        )
        objects(schema).filter { "properties" in it }.forEach {
            assertEquals(false, it["additionalProperties"])
            assertEquals(properties(it).keys, strings(it["required"]))
            if ("quote" in properties(it)) {
                val locatorKeys = setOf("source_id", "revision", "quote")
                assertEquals(if ("id" in properties(it)) locatorKeys + "id" else locatorKeys, properties(it).keys)
            }
        }
        assertFalse(F.json(schema).contains("start_code_point"))
        assertContentEquals(oldRequest, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(c4.responseSchema()))
    }

    private fun fields(value: Any?) = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun properties(value: Any?) = fields(fields(value)["properties"])

    private fun item(value: Any?) = fields(value)["items"]

    private fun strings(value: Any?): Set<String> =
        if (value is String) setOf(value) else (value as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun variants(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = fields(value)
        val reference = schema["\$ref"] as? String
        if (reference != null) return variants(fields(root["\$defs"])[reference.substringAfterLast('/')], root)
        val alternatives = schema["anyOf"] ?: schema["oneOf"]
        return if (alternatives is List<*>) alternatives.flatMap { variants(it, root) } else listOf(schema)
    }

    private fun objects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(fields(value)) + value.values.flatMap(::objects)
            is List<*> -> value.flatMap(::objects)
            else -> emptyList()
        }
}

/** Small shared synthetic wire fixture for this contract and its two persistence cases. */
object DateScopeInlineFixture {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private const val TEXT = "2027-01-08 가능. 2027-01-09도 가능하지만 그날은 불가. 2027-01-08을 선호해요."
    private const val NUMERIC = "2027-01-08 14:00부터 20:00 가능. 16:00부터 17:00 불가."

    data class Fixture(
        val input: ScheduleDslOfflineInput,
        val document: MutableMap<String, Any?>,
    ) {
        fun first() = F.firstResult(document)

        @Suppress("UNCHECKED_CAST")
        fun conditions() = first()["conditions"] as MutableList<MutableMap<String, Any?>>

        fun parse() =
            ScheduleDslStructuralProviderContract(mapper).parseProviderResponse(
                F.json(document),
                F.snapshot(input),
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )

        fun firstClaims(envelope: ScheduleDslStructuralEnvelope) =
            envelope.base.base.declaration.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions

        fun projected(envelope: ScheduleDslStructuralEnvelope) =
            envelope.calculationProjection.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions

        fun firstProgram(envelope: ScheduleDslStructuralEnvelope) =
            envelope.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.first }

        fun available(envelope: ScheduleDslStructuralEnvelope) =
            ScheduleDslStructuralCalculator
                .calculate(envelope, F.snapshot(input), input.policy, MeetingMode.REMOTE)
                .preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
    }

    fun dateOnly(): Fixture {
        val fixture = base(TEXT)
        fixture.first()["conditions"] =
            mutableListOf(
                claim(fixture, "day", "AVAILABLE", "2027-01-08"),
                claim(fixture, "other-day", "AVAILABLE", "2027-01-09"),
                claim(fixture, "blocked", "UNAVAILABLE", "2027-01-09"),
                claim(fixture, "preferred", "PREFERRED", "2027-01-08").apply { this["interpretation"] = "INFERRED" },
            )
        fixture.first()["program"] =
            program(
                expression(
                    fixture,
                    "ANY_OF",
                    children = listOf(expression(fixture, claim = "day"), expression(fixture, claim = "other-day")),
                ),
                expression(fixture, claim = "blocked"),
                expression(fixture, claim = "preferred"),
            )
        return fixture
    }

    fun unknown(): Fixture =
        base("행사가 끝나면 가능해요.").apply {
            first()["conditions"] = mutableListOf(claim(this, "unknown", "AVAILABLE", null))
            first()["program"] = program(expression(this, claim = "unknown"))
        }

    fun directional(direction: String): Fixture =
        base("2027-01-08 16:00 ${if (direction == "AFTER") "이후" else "이전"} 가능.").apply {
            first()["conditions"] = mutableListOf(claim(this, "bound", "AVAILABLE", null))
            first()["program"] = program(expression(this, claim = "bound"))
            first()["directional_times"] = listOf(side(direction))
        }

    fun side(direction: String): MutableMap<String, Any?> =
        linkedMapOf(
            "condition_id" to "bound",
            "direction" to direction,
            "local_date" to "2027-01-08",
            "clock" to "16:00",
            "date_evidence" to listOf(locator("2027-01-08")),
            "clock_evidence" to listOf(locator("16:00")),
        )

    fun numeric(
        inline: Boolean,
        subtract: Boolean = true,
    ): Fixture {
        val input = base(NUMERIC).input
        val proposal =
            F.proposal(
                input,
                listOf(
                    F.claim(input, "allow", scope = F.scope("2027-01-08T14:00", "2027-01-08T20:00")),
                    F.claim(input, "block", IrEffect.UNAVAILABLE, F.scope("2027-01-08T16:00", "2027-01-08T17:00")),
                ),
                F.program(
                    listOf(F.ref(input, "allow-ref", "allow"), F.ref(input, "block-ref", "block")) +
                        if (subtract) {
                            listOf(F.node(input, "difference", ScheduleDslOperator.EXCEPT, listOf("allow-ref", "block-ref")))
                        } else {
                            emptyList()
                        },
                    if (subtract) "difference" else "allow-ref",
                    "block-ref",
                ),
                emptyList(),
            )
        val fixture = Fixture(input, fields(locators(F.document(proposal))))
        complete(fixture)
        if (inline) {
            val allow = expression(fixture, claim = "allow")
            val block = expression(fixture, claim = "block")
            fixture.first()["program"] =
                program(
                    if (subtract) expression(fixture, "EXCEPT", children = listOf(allow, block)) else allow,
                    block,
                )
        }
        return fixture
    }

    fun bounded(
        depth: Int? = null,
        count: Int? = null,
    ): Fixture {
        val labels = (0..70).map { "<n${it.toString().padStart(2, '0')}>" }
        val text = NUMERIC + " " + labels.joinToString(" ")
        require(text.length <= 500) { "Synthetic original source must fit the native source contract" }
        val fixture = base(text)
        val condition = claim(fixture, "allow", "AVAILABLE", null)
        condition["scope"] = linkedMapOf("dimension" to "TIME", "start" to "2027-01-08T14:00", "end" to "2027-01-08T20:00")
        condition["temporal_resolution"] = "PRECISE_INTERVAL"
        fixture.first()["conditions"] = mutableListOf(condition)
        var next = 0

        fun leaf() = expression(fixture, claim = "allow").apply { this["evidence"] = listOf(locator(labels[next++])) }

        fun tree(level: Int): MutableMap<String, Any?> =
            if (level == 1) leaf() else expression(fixture, "ANY_OF", children = listOf(tree(level - 1), tree(level - 1)))

        fun chain(level: Int): MutableMap<String, Any?> =
            if (level == 1) leaf() else expression(fixture, "ANY_OF", children = listOf(chain(level - 1), leaf()))
        val root =
            if (depth != null) {
                chain(depth)
            } else {
                expression(fixture, "ANY_OF", children = listOf(tree(6), tree(6)) + List(requireNotNull(count) - 127) { leaf() })
            }
        fixture.first()["program"] = program(root)
        return fixture
    }

    fun zonedInput(
        date: String,
        zone: String,
    ): ScheduleDslOfflineInput =
        F.input("$date 가능.").copy(
            timeZone = zone,
            searchStart = LocalDate.parse(date).minusDays(1),
            searchEnd = LocalDate.parse(date).plusDays(2),
        )

    private fun base(text: String): Fixture {
        val input = F.input(text).copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val proposal =
            F.proposal(
                input,
                listOf(F.claim(input, "placeholder")),
                F.program(listOf(F.ref(input, "r", "placeholder")), "r"),
                emptyList(),
            )
        return Fixture(input, fields(locators(F.document(proposal)))).also(::complete)
    }

    private fun complete(fixture: Fixture) {
        fixture.document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        (fixture.document["results"] as List<MutableMap<String, Any?>>).forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
    }

    private fun claim(
        fixture: Fixture,
        id: String,
        effect: String,
        day: String?,
    ): MutableMap<String, Any?> =
        fields(F.ir.wire(F.claim(fixture.input, id))).apply {
            this["effect"] = effect
            this["scope"] = day?.let { linkedMapOf("dimension" to "TIME", "start" to it, "end" to null) }
            this["evidence"] =
                listOf(
                    locator(
                        fixture.input.inputs
                            .first()
                            .rawText,
                    ),
                )
        }

    private fun expression(
        fixture: Fixture,
        operator: String = "CLAIM_REF",
        claim: String? = null,
        children: List<Map<String, Any?>> = emptyList(),
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "operator" to operator,
            "claim_id" to claim,
            "children" to children,
            "choice_group" to null,
            "branch_labels" to emptyList<String>(),
            "interpretation" to "EXPLICIT",
            "assertion" to "ASSERTED",
            "evidence" to
                listOf(
                    locator(
                        fixture.input.inputs
                            .first()
                            .rawText,
                    ),
                ),
        )

    private fun program(
        allow: Map<String, Any?>?,
        block: Map<String, Any?>? = null,
        prefer: Map<String, Any?>? = null,
    ) = linkedMapOf<String, Any?>(
        "nodes" to emptyList<Any>(),
        "availability_root" to allow,
        "exclusion_root" to block,
        "preference_root" to prefer,
        "history_condition_ids" to emptyList<String>(),
    )

    private fun locator(quote: String) = linkedMapOf<String, Any?>("source_id" to "original", "revision" to 3, "quote" to quote)

    @Suppress("UNCHECKED_CAST")
    fun fields(value: Any?) = value as MutableMap<String, Any?>

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

    fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )
}
