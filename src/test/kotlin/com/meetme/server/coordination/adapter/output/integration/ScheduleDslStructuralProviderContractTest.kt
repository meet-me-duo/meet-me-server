package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.StructuralTimeDerivation
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Explicit C5 synthetic contracts; existing math is real, natural-language/model accuracy is not asserted. */
class ScheduleDslStructuralProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)
    private val windows =
        listOf(
            window("2027-01-08T20:00", "2027-01-09T00:00"),
            window("2027-01-10T00:00", "2027-01-10T17:00"),
        )

    @Test
    fun `SA01 explicit C5 request retains native context while every provider evidence schema is a closed literal locator`() {
        val capsule = N.capsule()
        val old = mapper.readTree(ScheduleDslContextModeProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER))
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("schedule-dsl-request-5", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-5", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-6", request.path("semantic_version").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        assertEquals(old.path("inputs"), request.path("inputs"))
        assertEquals(old.path("context"), request.path("context"))
        assertEquals(1, capsule.questions.size)
        val prompt = requireNotNull(request.path("prompt").asString())
        listOf("ANY_OF", "CHOICE", "AFTER", "BEFORE", "NON_PRECISE").forEach { assertTrue(prompt.contains(it)) }
        val schema = contract.responseSchema()
        assertClosed(schema)
        val properties = fields(schema["properties"])
        assertEquals(setOf("semantic_version", "ast_version", "vocabulary_version", "results"), properties.keys)
        val result = fields(fields(properties["results"])["items"])
        assertClosed(result)
        val resultProperties = fields(result["properties"])
        assertEquals(
            setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions", "directional_times"),
            resultProperties.keys,
        )
        val direction = fields(fields(resultProperties["directional_times"])["items"])
        assertClosed(direction)
        assertEquals(
            setOf("condition_id", "direction", "local_date", "clock", "date_evidence", "clock_evidence"),
            fields(direction["properties"]).keys,
        )

        fun conditionObjects(
            value: Any?,
            path: List<String>,
        ): List<Pair<List<String>, Map<String, Any?>>> {
            val objectSchema = fields(value)
            val reference = objectSchema["\$ref"] as? String
            if (reference != null) {
                val name = reference.substringAfterLast('/')
                return conditionObjects(fields(schema["\$defs"])[name], listOf("\$defs", name))
            }
            val choices = objectSchema["anyOf"] as? List<*>
            return choices?.flatMapIndexed { index, child ->
                conditionObjects(child, path + listOf("anyOf", index.toString()))
            } ?: listOf(path to objectSchema)
        }
        val conditionPath = listOf("properties", "results", "items", "properties", "conditions", "items")
        val conditions = conditionObjects(fields(resultProperties["conditions"])["items"], conditionPath)
        val bankPaths =
            conditions
                .flatMap { (path, condition) ->
                    val conditionProperties = fields(condition["properties"])
                    assertTrue("proofs" in conditionProperties, "P7 conditions must declare their owned proof bank")
                    val bank = fields(conditionProperties["proofs"])
                    val bankArms = fields(bank["items"])["anyOf"] as? List<*>
                    assertEquals(2, bankArms?.size)
                    val bankPath = path + listOf("properties", "proofs", "items", "anyOf")
                    listOf(bankPath + "0", bankPath + "1")
                }.toSet()

        fun evidenceObjects(
            value: Any?,
            path: List<String>,
        ): List<Pair<List<String>, Map<String, Any?>>> =
            when (value) {
                is Map<*, *> -> {
                    val objectSchema = fields(value)
                    val current =
                        if ("source_id" in fieldsOrEmpty(objectSchema["properties"])) listOf(path to objectSchema) else emptyList()
                    current + objectSchema.entries.flatMap { (key, child) -> evidenceObjects(child, path + key) }
                }
                is List<*> -> value.flatMapIndexed { index, child -> evidenceObjects(child, path + index.toString()) }
                else -> emptyList()
            }
        val evidenceSchemas = evidenceObjects(schema, emptyList())
        assertTrue(evidenceSchemas.isNotEmpty())
        assertEquals(bankPaths, evidenceSchemas.map { it.first }.filter { it in bankPaths }.toSet())
        assertTrue(evidenceSchemas.any { it.first !in bankPaths })
        evidenceSchemas.forEach { (path, evidenceSchema) ->
            assertClosed(evidenceSchema)
            val locatorKeys = setOf("source_id", "revision", "quote")
            assertEquals(if (path in bankPaths) locatorKeys + "id" else locatorKeys, fields(evidenceSchema["properties"]).keys)
        }
        assertFalse(F.json(schema).contains("start_code_point"))
        assertFalse(F.json(schema).contains("end_code_point"))
        val terms = fields(fields(fields(resultProperties["approved_terms"])["items"])["properties"])
        assertEquals(
            setOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
            strings(fields(terms["term"])["enum"]),
        )
    }

    @Test
    fun `SA02 known one-sided dates retain original declarations and reach the shared matcher as two separate windows`() {
        val f = fixture()
        val envelope = parse(f)
        assertEquals("schedule-ir-product-6", envelope.semanticVersion)
        assertEquals(F.snapshot(f.input).context, envelope.context)
        val originals =
            envelope.base.base.declaration.claims.results
                .first()
                .conditions
        assertEquals(setOf("first", "second"), originals.map { it.id }.toSet())
        originals.forEach {
            assertNull(it.scope)
            assertEquals(IrInterpretation.EXPLICIT, it.interpretation)
            assertEquals(IrTemporalResolution.NON_PRECISE, it.temporalResolution)
        }
        val applications = envelope.directionalApplications.associateBy { it.declaration.conditionId }
        assertEquals(setOf("first", "second"), applications.keys)
        assertEquals(TemporalDirection.AFTER, applications.getValue("first").declaration.direction)
        assertEquals(LocalDate.of(2027, 1, 8), applications.getValue("first").declaration.localDate)
        assertEquals(LocalTime.of(20, 0), applications.getValue("first").declaration.clock)
        assertEquals(TemporalDirection.BEFORE, applications.getValue("second").declaration.direction)
        assertEquals(LocalTime.of(17, 0), applications.getValue("second").declaration.clock)
        val scopes = listOf(F.scope("2027-01-08T20:00", "2027-01-09T00:00"), F.scope("2027-01-10T00:00", "2027-01-10T17:00"))
        listOf("first", "second").zip(scopes).forEach { (id, expectedScope) ->
            val application = applications.getValue(id)
            assertEquals(F.ir.first, application.submissionVersionId)
            assertEquals(expectedScope, application.resolvedScope)
            assertEquals(expectedScope, application.searchSupport)
            assertEquals(StructuralTimeDerivation.SERVER_DATE_SCOPE, application.derivation)
            assertEquals(IrTemporalResolution.NON_PRECISE, application.declaration.temporalResolution)
            val projected =
                envelope.calculationProjection.claims.results
                    .first()
                    .conditions
                    .single { it.id == id }
            assertEquals(expectedScope, projected.scope)
            assertEquals(IrInterpretation.EXPLICIT, projected.interpretation)
        }
        assertTrue(envelope.locatedEvidence.any { it.submissionVersionId == F.ir.first })
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(f.input)))
        val result = calculate(f, envelope)
        assertEquals(windows, available(result, first))
        assertEquals(windows, available(result, peer))
        val candidate = result.candidates.candidates.single()
        assertEquals(setOf(first, peer), candidate.participantIds.toSet())
        assertEquals(windows, candidate.timeRanges)
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertNull(candidate.representativePlace)
        assertEquals(
            windows.toSet(),
            result.recommendations.options
                .map { it.window }
                .toSet(),
        )
        val prepared = ScheduleDslStructuralCalculator.prepare(envelope, F.snapshot(f.input), f.input.policy, f.input.meetingMode)
        assertEquals(
            windows,
            prepared.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(FIRST, F.snapshot(f.input).sourceText(F.ir.first, "original"))
    }

    @Test
    fun `SA03 invalid or ambiguous condition locators quarantine evidence without default availability or peer loss`() {
        listOf("ambiguous", "missing", "stale").forEach { damage ->
            val f = fixture(precise = true)
            val evidence = firstEvidence(firstCondition(f.document))
            if (damage == "stale") evidence["revision"] = 999 else evidence["quote"] = if (damage == "ambiguous") "가능" else "ABSENT_LITERAL"
            val envelope = parse(f)
            val rejected =
                envelope.base.base.declaration.claims.results
                    .first()
            assertTrue(rejected.quarantined.any { it.conditionId == "first" && it.problem == IrProblem.EVIDENCE })
            assertFalse(rejected.conditions.any { it.id == "first" })
            val result = calculate(f, envelope)
            assertTrue(available(result, first).isEmpty())
            assertEquals(windows, available(result, peer))
            assertFalse(result.candidates.candidates.any { first in it.participantIds })
        }
    }

    @Test
    fun `SA04 malformed directional associations and mixed evidence shapes are closed document failures`() {
        listOf(
            "missing-array",
            "unknown-id",
            "foreign-id",
            "duplicate",
            "precise-target",
            "direction",
            "mixed-offset",
            "identity",
        ).forEach { damage ->
            val f = fixture()
            val result = F.firstResult(f.document)
            val sides = directions(f.document)
            when (damage) {
                "missing-array" -> result.remove("directional_times")
                "unknown-id" -> sides.first()["condition_id"] = "missing-condition"
                "foreign-id" -> sides.first()["condition_id"] = "peer-first"
                "duplicate" -> sides += LinkedHashMap(sides.first())
                "precise-target" -> firstCondition(f.document)["temporal_resolution"] = "PRECISE_INTERVAL"
                "direction" -> sides.first()["direction"] = "DURING"
                "mixed-offset" -> firstEvidence(firstCondition(f.document))["start_code_point"] = 0
                "identity" -> result["input_ref"] = UUID(0, 9999).toString()
            }
            invalid { parse(f) }
        }
        val f = fixture()
        val duplicate = F.json(f.document).replaceFirst("\"semantic_version\":", "\"semantic_version\":\"duplicate\",\"semantic_version\":")
        assertFalse(duplicate == F.json(f.document))
        invalid { parse(f, duplicate) }
    }

    @Test
    fun `SA05 a positive unknown event without a directional declaration never invents a clock or default whole search`() {
        val f = fixture(unknown = true)
        val envelope = parse(f)
        assertTrue(envelope.directionalApplications.isEmpty())
        val unknown =
            envelope.base.base.declaration.claims.results
                .first()
                .conditions
                .single { it.id == "first" }
        assertNull(unknown.scope)
        assertEquals(IrTemporalResolution.NON_PRECISE, unknown.temporalResolution)
        assertNull(
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "first" }
                .scope,
        )
        val result = calculate(f, envelope)
        assertEquals(listOf(windows[1]), available(result, first))
        assertEquals(windows, available(result, peer))
        assertTrue(
            result.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .evidence
                .getValue(first)
                .unresolvedTime,
        )
    }

    @Test
    fun `SA06 independent ANY_OF windows stay united while genuine unselected AM PM CHOICE never becomes both definite`() {
        val union = fixture(precise = true)
        assertEquals(windows, available(calculate(union, parse(union)), first))
        val unresolved = fixture(choice = true)
        val unresolvedResult = calculate(unresolved, parse(unresolved))
        assertTrue(available(unresolvedResult, first).isEmpty())
        val selected = fixture(choice = true, selection = "pm")
        val selectedResult = calculate(selected, parse(selected))
        assertEquals(listOf(window("2027-01-08T19:00", "2027-01-08T20:00")), available(selectedResult, first))
        assertEquals(
            listOf(window("2027-01-08T07:00", "2027-01-08T08:00"), window("2027-01-08T19:00", "2027-01-08T20:00")),
            available(selectedResult, peer),
        )
    }

    @Test
    fun `SA07 both preparation and calculation fully restore located evidence directional witnesses and numeric projection`() {
        val f = fixture()
        val envelope = parse(f)
        val projection = envelope.calculationProjection
        val forgedProjection =
            projection.copy(
                claims =
                    projection.claims.copy(
                        results =
                            projection.claims.results.map { result ->
                                if (result.submissionVersionId != F.ir.first) {
                                    result
                                } else {
                                    result.copy(
                                        conditions =
                                            result.conditions.map {
                                                it.copy(
                                                    scope = F.scope("2027-01-04T00:00", "2027-01-11T00:00"),
                                                )
                                            },
                                    )
                                }
                            },
                    ),
            )
        val proof = envelope.locatedEvidence.first()
        val forgedEvidence = proof.copy(evidence = proof.evidence.copy(span = proof.evidence.span.copy(contentSha256 = "0".repeat(64))))
        val forgeries =
            listOf(
                envelope.copy(calculationProjection = forgedProjection),
                envelope.copy(directionalApplications = envelope.directionalApplications.drop(1)),
                envelope.copy(locatedEvidence = emptyList()),
                envelope.copy(
                    locatedEvidence =
                        listOf(forgedEvidence) + envelope.locatedEvidence.drop(1),
                ),
                envelope.copy(context = envelope.context.copy(batchId = UUID(0, 9999))),
            )
        forgeries.forEach { forged ->
            assertFailsWith<IllegalArgumentException> {
                ScheduleDslStructuralCalculator.prepare(forged, F.snapshot(f.input), f.input.policy, f.input.meetingMode)
            }
            assertFailsWith<IllegalArgumentException> { calculate(f, forged) }
        }
        val stale = f.input.copy(inputs = f.input.inputs.map { it.copy(evidence = requireNotNull(it.evidence).copy(originalRevision = 4)) })
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslStructuralCalculator.calculate(envelope, F.snapshot(stale), stale.policy, stale.meetingMode)
        }
    }

    @Test
    fun `SA08 C5 does not mutate C4 requests or teach the old strict decoder the new evidence and directional fields`() {
        val old = ScheduleDslContextModeProviderContract(mapper)
        val capsule = N.capsule()
        val before = old.requestBytes(capsule, MeetingMode.EITHER)
        contract.requestBytes(capsule, MeetingMode.EITHER)
        parse(fixture())
        assertContentEquals(before, old.requestBytes(capsule, MeetingMode.EITHER))
        val f = fixture()
        val oldDecoder = StrictScheduleDslContextModeResponseParser(mapper)
        assertFailsWith<IllegalArgumentException> {
            oldDecoder.parse(
                F.json(f.document),
                F.snapshot(f.input),
                "schedule-dsl-response-5",
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                VOCAB,
            )
        }
        f.document["semantic_version"] = "schedule-ir-product-5"
        invalid {
            oldDecoder.parse(
                F.json(f.document),
                F.snapshot(f.input),
                "schedule-dsl-response-4",
                "schedule-ir-product-5",
                SCHEDULE_AST_VERSION,
                VOCAB,
            )
        }
        invalid { parse(f) }
    }

    private data class Fixture(
        val input: ScheduleDslOfflineInput,
        val document: MutableMap<String, Any?>,
    )

    private fun fixture(
        precise: Boolean = false,
        unknown: Boolean = false,
        choice: Boolean = false,
        selection: String? = null,
    ): Fixture {
        val text =
            if (choice) {
                CHOICE
            } else if (unknown) {
                UNKNOWN
            } else {
                FIRST
            }
        val choices = selection?.let { listOf(IrAlternativeChoice(F.ir.first, "time-choice", it)) }.orEmpty()
        val policy = N.policy.copy(alternativeChoices = choices)
        val input =
            F.input(text).let { original ->
                original.copy(
                    policy = policy,
                    meetingMode = MeetingMode.REMOTE,
                    inputs =
                        original.inputs.mapIndexed { index, item ->
                            if (index == 0) item else item.copy(rawText = if (choice) PEER_CHOICE else PEER)
                        },
                )
            }
        val scopes =
            if (choice) {
                listOf(F.scope("2027-01-08T07:00", "2027-01-08T08:00"), F.scope("2027-01-08T19:00", "2027-01-08T20:00"))
            } else {
                listOf(F.scope("2027-01-08T20:00", "2027-01-09T00:00"), F.scope("2027-01-10T00:00", "2027-01-10T17:00"))
            }
        val claims =
            listOf("first", "second").mapIndexed { index, id ->
                F.claim(input, id, scope = if (precise || choice || (unknown && index == 1)) scopes[index] else null).let {
                    if (choice) it.copy(alternativeGroup = "time-choice", alternative = if (index == 0) "am" else "pm") else it
                }
            }
        val root =
            F.node(input, "both", if (choice) ScheduleDslOperator.CHOICE else ScheduleDslOperator.ANY_OF, listOf("a", "b")).let {
                if (choice) it.copy(choiceGroup = "time-choice", branchLabels = listOf("am", "pm")) else it
            }
        val proposal =
            F
                .proposal(
                    input,
                    claims,
                    F.program(listOf(F.ref(input, "a", "first"), F.ref(input, "b", "second"), root), "both"),
                    emptyList(),
                ).let { original ->
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
                                                    scopes.mapIndexed { index, scope ->
                                                        val id = if (index == 0) "peer-first" else "peer-second"
                                                        F.claim(input, id, scope = scope, index = 1)
                                                    },
                                                ),
                                            ),
                                    ),
                                programs =
                                    listOf(
                                        original.base.programs.first(),
                                        ProposedParticipantScheduleProgram(
                                            F.ir.second,
                                            F.program(
                                                listOf(
                                                    F.ref(input, "pa", "peer-first", 1),
                                                    F.ref(input, "pb", "peer-second", 1),
                                                    F.node(input, "peer-both", ScheduleDslOperator.ANY_OF, listOf("pa", "pb"), index = 1),
                                                ),
                                                "peer-both",
                                            ),
                                        ),
                                    ),
                            ),
                    )
                }
        val document = fieldsMutable(locators(F.document(proposal)))
        document["semantic_version"] = "schedule-ir-product-6"
        results(document).forEachIndexed { index, result ->
            result["mode_restrictions"] = mutableListOf<Any>()
            result["directional_times"] =
                if (index == 0 && !precise && !unknown && !choice) {
                    mutableListOf(direction("first", "AFTER", "2027-01-08", "20:00"), direction("second", "BEFORE", "2027-01-10", "17:00"))
                } else {
                    mutableListOf<Any>()
                }
        }
        return Fixture(input, document)
    }

    private fun direction(
        id: String,
        direction: String,
        date: String,
        clock: String,
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "condition_id" to id,
            "direction" to direction,
            "local_date" to date,
            "clock" to clock,
            "date_evidence" to listOf(linkedMapOf("source_id" to "original", "revision" to 3, "quote" to date)),
            "clock_evidence" to listOf(linkedMapOf("source_id" to "original", "revision" to 3, "quote" to clock)),
        )

    private fun parse(
        f: Fixture,
        json: String = F.json(f.document),
    ): ScheduleDslStructuralEnvelope =
        contract.parseProviderResponse(json, F.snapshot(f.input), "schedule-ir-product-6", SCHEDULE_AST_VERSION, VOCAB)

    private fun calculate(
        f: Fixture,
        envelope: ScheduleDslStructuralEnvelope,
    ) = ScheduleDslStructuralCalculator.calculate(envelope, F.snapshot(f.input), f.input.policy, f.input.meetingMode)

    private fun available(
        result: ScheduleDslContextModeCalculation,
        participant: ParticipantId,
    ) = result.preparedByMode
        .getValue(MeetingMode.REMOTE)
        .participants
        .single { it.participantId == participant }
        .availableTimes

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains(FIRST))
    }

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val fields = value.entries.associate { it.key as String to it.value }
                if ("start_code_point" in fields) {
                    require(fields.keys == setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"))
                    linkedMapOf("source_id" to fields["source_id"], "revision" to fields["revision"], "quote" to fields["quote"])
                } else {
                    fields.mapValues { locators(it.value) }.toMutableMap()
                }
            }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }

    private fun schemaObjects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(fields(value)) + value.values.flatMap(::schemaObjects)
            is List<*> -> value.flatMap(::schemaObjects)
            else -> emptyList()
        }

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = requireNotNull(value as? Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private fun fieldsMutable(value: Any?) = requireNotNull(value as? MutableMap<String, Any?>)

    private fun fieldsOrEmpty(value: Any?) = if (value is Map<*, *>) fields(value) else emptyMap()

    private fun strings(value: Any?): Set<String> = (value as List<*>).map { requireNotNull(it as? String) }.toSet()

    private fun assertClosed(value: Map<String, Any?>) {
        assertEquals(false, value["additionalProperties"])
        assertEquals(fields(value["properties"]).keys, strings(value["required"]))
    }

    @Suppress("UNCHECKED_CAST")
    private fun results(document: MutableMap<String, Any?>) = document["results"] as MutableList<MutableMap<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun directions(document: MutableMap<String, Any?>) =
        F.firstResult(document)["directional_times"] as MutableList<MutableMap<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun firstCondition(document: MutableMap<String, Any?>) =
        (F.firstResult(document)["conditions"] as List<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun firstEvidence(condition: MutableMap<String, Any?>) = (condition["evidence"] as List<MutableMap<String, Any?>>).first()

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    companion object {
        private const val VOCAB = "ko-time-terms-1"
        private const val FIRST = "🙂 2027-01-08 20:00 이후 가능. 2027-01-10 17:00 이전 가능."
        private const val PEER = "2027-01-08 20:00부터 2027-01-09 00:00, 2027-01-10 00:00부터 17:00까지 가능."
        private const val UNKNOWN = "행사가 끝나면 가능. 2027-01-10 00:00부터 17:00까지 가능."
        private const val CHOICE = "2027-01-08 07:00부터 08:00 또는 19:00부터 20:00 중 하나 가능."
        private const val PEER_CHOICE = "2027-01-08 07:00부터 08:00, 19:00부터 20:00 두 구간 모두 가능."
    }
}
