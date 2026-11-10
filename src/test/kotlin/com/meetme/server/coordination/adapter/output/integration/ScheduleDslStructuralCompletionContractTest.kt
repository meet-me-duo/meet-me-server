package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic producer contracts and strict replay controls; no live response or model-quality assertion. */
class ScheduleDslStructuralCompletionContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)

    @Test
    fun `SC01 compact C5 instructions preserve meaning time program and server responsibilities`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val oldBytes = c4.requestBytes(capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val oldRequest = mapper.readTree(oldBytes)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        val prompt = requireNotNull(request.path("prompt").asString())
        val sections =
            listOf(
                "Meaning and evidence" to
                    listOf(
                        "Preserve availability, unavailability and preference.",
                        "Quotes locate native evidence; they do not confirm meaning.",
                    ),
                "Temporal representation" to
                    listOf(
                        "Pure approved terms stay NON_PRECISE with model_scope:null.",
                        "AM/PM attached to a numeric clock qualifies that clock; " +
                            "it is not a separate time-term constraint.",
                        "Known one-sided date/clock bounds use temporal.BOUNDS.",
                        "Supported contextual scopes stay INFERRED/NON_PRECISE with MODEL_CONTEXT provenance.",
                        "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; " +
                            "retain their date and event context in complete condition evidence " +
                            "without inventing clocks or whole-day availability.",
                    ),
                "Program semantics" to
                    listOf(
                        "Choose roots before nodes and reuse CLAIM_REF.",
                        "Every active condition and node must be reachable in its actual role.",
                        "Independent dates/windows use ANY_OF; only mutually exclusive interpretations use CHOICE.",
                        "Independent exclusions use ANY_OF.",
                        "Related restrictions for the same date refine availability through ALL_OF.",
                    ),
                "Server boundary" to
                    listOf("The server locates evidence, applies approved baselines and computes matching."),
            )
        val positions = sections.map { prompt.indexOf(it.first) }
        assertTrue(positions.all { it >= 0 }, "Missing compact C5 responsibility section")
        assertEquals(positions.sorted(), positions)
        sections.forEachIndexed { index, (heading, clauses) ->
            val end = positions.getOrNull(index + 1) ?: prompt.indexOf("The complete approved vocabulary is ")
            val section = prompt.substring(positions[index], end)
            clauses.forEach { assertTrue(section.contains(it), "Missing $heading rule: $it") }
        }
        assertEquals(oldRequest.path("inputs"), request.path("inputs"))
        assertEquals(oldRequest.path("context"), request.path("context"))
        assertEquals(1, capsule.questions.size)
        val oldPrompt = requireNotNull(oldRequest.path("prompt").asString())
        val table =
            oldPrompt
                .substringAfter(
                    "The complete approved vocabulary is ",
                ).substringBefore("These are local calendar boundaries,")
                .trim()
        assertEquals(table, prompt.substringAfter("The complete approved vocabulary is ").trim())
        sections.forEach { assertFalse(oldPrompt.contains(it.first)) }
        assertContentEquals(oldBytes, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(c4.responseSchema()))
    }

    @Test
    fun `SC02 C5 format descriptions keep derivations reachability and closed response shape`() {
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
        val result = properties(item(properties(schema).getValue("results")))
        assertEquals(setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions", "directional_times"), result.keys)
        val program = fields(result["program"])
        val condition = item(result.getValue("conditions"))
        val programDescription = requireNotNull(program["description"] as? String)
        val conditionDescription = requireNotNull(condition["description"] as? String)
        assertTrue(programDescription.contains("Every active condition and node must be reachable in its actual role."))
        listOf("ALLOW", "BLOCK", "PREFER").forEach { assertTrue(programDescription.contains(it)) }
        listOf(
            "Pure approved terms stay NON_PRECISE with model_scope:null.",
            "EXPLICIT/PRECISE_INTERVAL",
            "INFERRED/NON_PRECISE",
            "MODEL_CONTEXT",
            "temporal.BOUNDS",
            "Missing personal event clocks stay unknown.",
        ).forEach { assertTrue(conditionDescription.contains(it), "Missing C5 temporal format: $it") }
        assertEquals(
            setOf("nodes", "availability_root", "exclusion_root", "preference_root", "history_condition_ids"),
            properties(program).keys,
        )
        schemaAlternatives(condition).forEach { conditionVariant ->
            assertEquals(
                setOf(
                    "id",
                    "effect",
                    "interpretation",
                    "temporal_resolution",
                    "temporal",
                    "proofs",
                    "evidence",
                    "assertion",
                    "supersedes",
                ),
                properties(conditionVariant).keys,
            )
        }
        val node = item(properties(program).getValue("nodes"))
        assertEquals(
            setOf("id", "operator", "claim_id", "children", "choice_group", "branch_labels", "interpretation", "assertion", "evidence"),
            properties(node).keys,
        )
        val term = properties(item(result.getValue("approved_terms")))
        val termEvidence = properties(fields(term["term_evidence"]))
        assertEquals(
            setOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일"),
            strings(fields(termEvidence["quote"])["enum"]),
        )
        schemaObjects(schema).filter { "properties" in it }.forEach { value ->
            assertEquals(false, value["additionalProperties"])
            assertEquals(properties(value).keys, strings(value["required"]))
            if ("quote" in properties(value)) {
                val locatorKeys = setOf("source_id", "revision", "quote")
                assertEquals(if ("id" in properties(value)) locatorKeys + "id" else locatorKeys, properties(value).keys)
            }
        }
        assertFalse(F.json(schema).contains("start_code_point"))
        assertFalse(F.json(schema).contains("end_code_point"))
    }

    @Test
    fun `SC03 inferred anchors date refinement and preference retain provenance and roots through replay`() {
        val fixture = fixture()
        val envelope = parse(fixture)
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
        val declared =
            envelope.base.base.declaration.claims.results
                .single {
                    it.submissionVersionId == F.ir.first
                }.conditions
                .associateBy { it.id }
        for (id in listOf("context", "date", "direction-day", "direction")) {
            assertEquals(IrInterpretation.INFERRED, declared.getValue(id).interpretation)
            assertEquals(IrTemporalResolution.NON_PRECISE, declared.getValue(id).temporalResolution)
        }
        assertEquals(F.scope("2027-01-08T18:30", "2027-01-08T21:00"), declared.getValue("context").scope)
        assertEquals(F.scope("2027-01-09T00:00", "2027-01-10T00:00"), declared.getValue("date").scope)
        assertNull(declared.getValue("direction").scope)
        assertEquals(IrEffect.PREFERRED, declared.getValue("preferred").effect)
        assertEquals(
            setOf("context", "date", "direction-day"),
            envelope.base.contextApplications
                .map { it.conditionId }
                .toSet(),
        )
        envelope.base.contextApplications.forEach { assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, it.derivation) }
        val direction = envelope.directionalApplications.single()
        assertEquals(LocalTime.of(16, 0), direction.declaration.clock)
        assertEquals(IrInterpretation.INFERRED, direction.declaration.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, direction.declaration.temporalResolution)
        val program =
            assertNotNull(
                envelope.base.base.declaration.programs
                    .single { it.submissionVersionId == F.ir.first }
                    .program,
            )
        assertEquals("available", program.availabilityRoot)
        assertEquals("preferred-ref", program.preferenceRoot)
        assertEquals(7, program.nodes.size)
        val refinedDay = program.nodes.single { it.id == "later-start" }
        assertEquals(ScheduleDslOperator.ALL_OF, refinedDay.operator)
        assertEquals(listOf("direction-day-ref", "direction-ref"), refinedDay.children)
        assertTrue(
            envelope.base.base.termApplications
                .isEmpty(),
            "A date-only day does not fabricate ALL_DAY evidence",
        )
        assertEquals(
            listOf(
                window("2027-01-08T18:30", "2027-01-08T21:00"),
                window("2027-01-09T00:00", "2027-01-10T00:00"),
                window("2027-01-10T16:00", "2027-01-11T00:00"),
            ),
            available(fixture, envelope, first),
        )
        assertEquals(listOf(searchWindow()), available(fixture, envelope, peer))
    }

    @Test
    fun `SC04 an extra orphan CLAIM_REF invalidates the participant program instead of being silently removed`() {
        val fixture = fixture()
        val program = mutableFields(F.firstResult(fixture.document)["program"])

        @Suppress("UNCHECKED_CAST")
        val nodes = program["nodes"] as MutableList<MutableMap<String, Any?>>
        nodes += LinkedHashMap(nodes.first()).apply { this["id"] = "orphan-context-ref" }
        val original = F.json(fixture.document)
        val envelope = parse(fixture)
        val rejected =
            envelope.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.first }
        assertNull(rejected.program)
        assertTrue(ScheduleDslProblem.REFERENCE in rejected.problems)
        assertTrue(available(fixture, envelope, first).isEmpty())
        assertEquals(listOf(searchWindow()), available(fixture, envelope, peer))
        assertEquals(original, F.json(fixture.document))
    }

    @Test
    fun `SC05 an unknown personal event finish stays null with no fabricated directional clock or search default`() {
        val fixture = fixture(missingClock = true)
        val envelope = parse(fixture)
        val declared =
            envelope.base.base.declaration.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions
                .single()
        val projected =
            envelope.calculationProjection.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, declared.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertNull(declared.scope)
        assertNull(projected.scope)
        assertTrue(envelope.directionalApplications.isEmpty())
        assertTrue(envelope.base.contextApplications.isEmpty())
        assertTrue(available(fixture, envelope, first).isEmpty())
        assertEquals(listOf(searchWindow()), available(fixture, envelope, peer))
        assertEquals(MISSING_CLOCK, F.snapshot(fixture.input).sourceText(F.ir.first, "original"))
    }

    private data class Fixture(
        val input: ScheduleDslOfflineInput,
        val document: MutableMap<String, Any?>,
    )

    private fun fixture(missingClock: Boolean = false): Fixture {
        val input =
            F.input(if (missingClock) MISSING_CLOCK else APPROXIMATE).let { input ->
                input.copy(
                    policy = N.policy,
                    meetingMode = MeetingMode.REMOTE,
                    inputs = input.inputs.mapIndexed { index, item -> if (index == 0) item else item.copy(rawText = PEER) },
                )
            }
        val claims =
            if (missingClock) {
                listOf(F.claim(input, "unknown"))
            } else {
                listOf(
                    F.claim(input, "context", scope = F.scope("2027-01-08T18:30", "2027-01-08T21:00")),
                    F.claim(input, "date", scope = F.scope("2027-01-09T00:00", "2027-01-10T00:00")),
                    F.claim(input, "direction-day", scope = F.scope("2027-01-10T00:00", "2027-01-11T00:00")),
                    F.claim(input, "direction"),
                ).map { it.copy(interpretation = IrInterpretation.INFERRED, temporalResolution = IrTemporalResolution.NON_PRECISE) } +
                    F.claim(input, "preferred", effect = IrEffect.PREFERRED, scope = F.scope("2027-01-08T19:00", "2027-01-08T20:00"))
            }
        val program =
            if (missingClock) {
                F.program(listOf(F.ref(input, "unknown-ref", "unknown")), "unknown-ref")
            } else {
                F.program(
                    listOf(
                        F.ref(input, "context-ref", "context"),
                        F.ref(input, "date-ref", "date"),
                        F.ref(input, "direction-day-ref", "direction-day"),
                        F.ref(input, "direction-ref", "direction"),
                        F.node(input, "later-start", ScheduleDslOperator.ALL_OF, listOf("direction-day-ref", "direction-ref")),
                        F.node(input, "available", ScheduleDslOperator.ANY_OF, listOf("context-ref", "date-ref", "later-start")),
                        F.ref(input, "preferred-ref", "preferred"),
                    ),
                    "available",
                    prefer = "preferred-ref",
                )
            }
        val proposal =
            F.proposal(input, claims, program, emptyList()).let { original ->
                original.copy(
                    base =
                        original.base.copy(
                            claims =
                                original.base.claims.copy(
                                    results =
                                        listOf(
                                            original.base.claims.results
                                                .first(),
                                            ProposedIrV2Result(
                                                F.ir.second,
                                                listOf(
                                                    F.claim(
                                                        input,
                                                        "peer",
                                                        scope = F.scope("2027-01-04T00:00", "2027-01-11T00:00"),
                                                        index = 1,
                                                    ),
                                                ),
                                            ),
                                        ),
                                ),
                        ),
                )
            }
        val document = mutableFields(locators(F.document(proposal)))
        document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        val results = document["results"] as List<MutableMap<String, Any?>>
        results.forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
        if (!missingClock) {
            F.firstResult(document)["directional_times"] =
                listOf(
                    mapOf(
                        "condition_id" to "direction",
                        "direction" to "AFTER",
                        "local_date" to "2027-01-10",
                        "clock" to "16:00",
                        "date_evidence" to listOf(locator("2027-01-10")),
                        "clock_evidence" to listOf(locator("16시쯤")),
                    ),
                )
        }
        return Fixture(input, document)
    }

    private fun parse(fixture: Fixture) =
        contract.parseProviderResponse(
            F.json(fixture.document),
            F.snapshot(fixture.input),
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    private fun available(
        fixture: Fixture,
        envelope: ScheduleDslStructuralEnvelope,
        participant: ParticipantId,
    ) = ScheduleDslStructuralCalculator
        .calculate(envelope, F.snapshot(fixture.input), fixture.input.policy, fixture.input.meetingMode)
        .preparedByMode
        .getValue(MeetingMode.REMOTE)
        .participants
        .single { it.participantId == participant }
        .availableTimes

    private fun locator(quote: String) = mapOf("source_id" to "original", "revision" to 3, "quote" to quote)

    private fun item(value: Any?) = fields(fields(value)["items"])

    private fun properties(value: Map<String, Any?>) = fields(value["properties"])

    private fun strings(value: Any?) = (value as List<*>).map { it as String }.toSet()

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

    private fun searchWindow() = window("2027-01-04T00:00", "2027-01-11T00:00")

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    companion object {
        private const val GRAPH_GUIDE =
            "Choose availability_root, exclusion_root and preference_root first. Reuse an existing " +
                "CLAIM_REF when the same condition participates in a composite node; do not emit an extra " +
                "standalone copy. Before returning, walk every root and child edge and verify every node and " +
                "live condition is reachable in its actual role."
        private const val TIME_GUIDE =
            "Preserve an actual approximate numeric time anchor as INFERRED and NON_PRECISE. Supply a " +
                "complete supported contextual TIME scope, or use directional_times for a known date and " +
                "one-sided clock. Leave scope:null when an actual event finish or other personal time is " +
                "missing; do not invent numeric endpoints."
        private const val REFINE_GUIDE =
            "On the same resolved date, a later-start restriction refines related date-only availability " +
                "through ALL_OF; use ANY_OF only for independently available dates or windows."
        private const val ANCHOR_GUIDE =
            "An approximate numeric clock stated by the participant is available evidence, not a missing " +
                "personal fact; retain supported contextual estimation with INFERRED/NON_PRECISE provenance."
        private const val APPROXIMATE =
            "2027-01-08은 18시 반쯤부터 21시쯤까지 가능. " +
                "2027-01-09는 가능. 2027-01-10은 가능하지만 16시쯤 이후여야 해. " +
                "2027-01-08 19시부터 20시가 좋아."
        private const val MISSING_CLOCK = "행사가 끝나면 가능해요."
        private const val PEER = "2027-01-04 00:00부터 2027-01-11 00:00까지 가능."
    }
}
