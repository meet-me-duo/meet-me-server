package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.StructuralTimeDerivation
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Public synthetic contracts, never an actual-model or general JSON Schema validator. */
class ScheduleDslWholeDaySchemaParityContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `WD01 whole-day generation declares exactly six metadata combinations out of thirty`() {
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val candidates = alternatives(fields(result.getValue("conditions")).getValue("items"), schema)
        val declared = mutableSetOf<Triple<String, String, String>>()
        candidates.forEach { condition ->
            assertClosed(condition, CONDITION_KEYS)
            val props = properties(condition)
            val atoms = alternatives(props.getValue("temporal"), schema)
            if (atoms.any { "WHOLE_DAY" in enumValues(properties(it).getValue("kind"), schema) }) {
                for (effect in enumValues(props.getValue("effect"), schema)) {
                    for (interpretation in enumValues(props.getValue("interpretation"), schema)) {
                        for (resolution in enumValues(props.getValue("temporal_resolution"), schema)) {
                            declared += Triple(effect, interpretation, resolution)
                        }
                    }
                }
            }
        }
        val expected =
            setOf(IrEffect.AVAILABLE, IrEffect.UNAVAILABLE, IrEffect.PREFERRED)
                .flatMap { effect ->
                    setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED).map { interpretation ->
                        Triple(effect.name, interpretation.name, IrTemporalResolution.NON_PRECISE.name)
                    }
                }.toSet()
        val all =
            IrEffect.entries.flatMap { e ->
                IrInterpretation.entries.flatMap { i -> IrTemporalResolution.entries.map { r -> Triple(e.name, i.name, r.name) } }
            }
        assertEquals(30, all.size)
        assertEquals(6, expected.size)
        assertEquals(expected, declared, "WHOLE_DAY producer metadata must match its existing supported contract")
        all.forEach { assertEquals(it in expected, it in declared, "Metadata tuple $it") }
    }

    @Test
    fun `WD02 six supported whole-day declarations preserve original metadata and complete date proof`() {
        for (effect in listOf(IrEffect.AVAILABLE, IrEffect.UNAVAILABLE, IrEffect.PREFERRED)) {
            for (interpretation in listOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED)) {
                val meaning =
                    when (effect) {
                        IrEffect.AVAILABLE -> "하루 가능해요."
                        IrEffect.UNAVAILABLE -> "하루 불가해요."
                        IrEffect.PREFERRED -> "하루를 선호해요."
                        else -> error("Only the three supported date-scoped effects belong to this fixture")
                    }
                val text = "2027-01-08 $meaning"
                val fixture = fixture(text, listOf(Spec("day", effect, whole(), text, interpretation)))
                val before = F.json(fixture.document)
                val envelope = fixture.parse()
                val condition = firstClaims(envelope).single()
                assertEquals(effect, condition.effect)
                assertEquals(interpretation, condition.interpretation)
                assertEquals(IrTemporalResolution.NON_PRECISE, condition.temporalResolution)
                assertNull(condition.scope)
                val application = envelope.directionalApplications.single()
                assertEquals(StructuralTimeDerivation.SERVER_CALENDAR_DAY, application.derivation)
                assertEquals(TemporalDirection.WHOLE_DAY, application.declaration.direction)
                assertEquals(F.day, application.declaration.localDate)
                assertNull(application.declaration.clock)
                assertTrue(application.declaration.clockEvidence.isEmpty())
                assertEquals(condition.evidence, application.declaration.dateEvidence.map { it.span })
                val projected =
                    envelope.calculationProjection.claims.results
                        .single { it.submissionVersionId == F.ir.first }
                        .conditions
                        .single()
                assertEquals(effect, projected.effect)
                assertEquals(interpretation, projected.interpretation)
                assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projected.temporalResolution)
                assertEquals(F.scope("2027-01-08T00:00", "2027-01-09T00:00"), projected.scope)
                assertEquals(before, F.json(fixture.document))
            }
        }
    }

    @Test
    fun `WD03 independent dates negative priority and whole-day preference clipping keep their meanings`() {
        val available = "2027-01-08 12:00부터 18:00까지 가능."
        val other = "2027-01-09 가능."
        val blocked = "2027-01-09 하루 내내 불가."
        val preferred = "2027-01-08 하루를 선호해요."
        val fixture =
            fixture(
                "$available $other $blocked $preferred",
                listOf(
                    Spec("literal", IrEffect.AVAILABLE, bounds("12:00", "18:00"), available, precise = true),
                    Spec("other", IrEffect.AVAILABLE, whole("2027-01-09"), other),
                    Spec("blocked", IrEffect.UNAVAILABLE, whole("2027-01-09"), blocked),
                    Spec("preferred", IrEffect.PREFERRED, whole(), preferred),
                ),
            )
        val envelope = fixture.parse()
        assertEquals(setOf("literal", "other", "blocked", "preferred"), firstClaims(envelope).map { it.id }.toSet())
        assertEquals(IrEffect.UNAVAILABLE, firstClaims(envelope).single { it.id == "blocked" }.effect)
        assertEquals(IrEffect.PREFERRED, firstClaims(envelope).single { it.id == "preferred" }.effect)
        val prepared = fixture.prepared(envelope)
        val clipped = listOf(window("2027-01-08T12:00", "2027-01-08T18:00"))
        assertEquals(clipped, prepared.availableTimes)
        assertEquals(clipped, prepared.explicitPreferences.timeRanges)
        val only = fixture(preferred, listOf(Spec("preferred", IrEffect.PREFERRED, whole(), preferred)))
        val onlyEnvelope = only.parse()
        assertEquals(listOf(IrEffect.PREFERRED), firstClaims(onlyEnvelope).map { it.effect })
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-09T00:00")), only.prepared(onlyEnvelope).availableTimes)
        val stale = fixture(other, listOf(Spec("stale", IrEffect.AVAILABLE, whole("2027-01-09"), other, revision = 2)))
        val rejected = stale.parse()
        assertTrue(firstClaims(rejected).isEmpty())
        assertEquals(IrProblem.EVIDENCE, firstResult(rejected).quarantined.single().problem)
        assertTrue(stale.prepared(rejected).availableTimes.isEmpty())
        assertEquals(
            "peer",
            rejected.base.base.declaration.claims.results
                .single { it.submissionVersionId == F.ir.second }
                .conditions
                .single()
                .id,
        )
    }

    @Test
    fun `WD04 the other four temporal arms keep their public shapes and supported reader behavior`() {
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = alternatives(fields(result.getValue("conditions")).getValue("items"), schema)
        val atoms = conditions.flatMap { alternatives(properties(it).getValue("temporal"), schema) }
        val expectedKeys =
            mapOf(
                "APPROVED_TERM" to setOf("kind", "term", "local_date", "term_evidence", "date_evidence", "model_scope"),
                "BOUNDS" to setOf("kind", "local_date", "date_evidence", "lower", "upper"),
                "UNKNOWN" to setOf("kind"),
                "RAW_SCOPE" to setOf("kind", "scope"),
            )
        assertEquals(expectedKeys.keys + "WHOLE_DAY", atoms.flatMap { enumValues(properties(it).getValue("kind"), schema) }.toSet())
        atoms.forEach { atom ->
            val kind = enumValues(properties(atom).getValue("kind"), schema).single()
            expectedKeys[kind]?.let { assertClosed(atom, it) }
        }
        val lunchText = "2027-01-08 점심 가능."
        val lunch = fixture(lunchText, listOf(Spec("lunch", IrEffect.AVAILABLE, term(), lunchText)))
        assertEquals(listOf(window("2027-01-08T11:00", "2027-01-08T15:00")), lunch.prepared(lunch.parse()).availableTimes)
        val literal = "2027-01-08 13:00부터 16:00까지 가능."
        val fixed = fixture(literal, listOf(Spec("fixed", IrEffect.AVAILABLE, bounds("13:00", "16:00"), literal, precise = true)))
        assertEquals(listOf(window("2027-01-08T13:00", "2027-01-08T16:00")), fixed.prepared(fixed.parse()).availableTimes)
        val approximate = "2027-01-08 13시쯤부터 16시쯤 가능."
        val inferred =
            fixture(
                approximate,
                listOf(Spec("context", IrEffect.AVAILABLE, bounds("13:00", "16:00"), approximate, IrInterpretation.INFERRED)),
            )
        val estimated = inferred.parse()
        assertEquals(IrInterpretation.INFERRED, firstClaims(estimated).single().interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, firstClaims(estimated).single().temporalResolution)
        assertEquals(1, estimated.base.contextApplications.size)
        assertEquals(listOf(window("2027-01-08T13:00", "2027-01-08T16:00")), inferred.prepared(estimated).availableTimes)
        val missingText = "개인 일정이 끝나면 가능하지만 끝나는 시각은 몰라요."
        val missing = fixture(missingText, listOf(Spec("unknown", IrEffect.AVAILABLE, mapOf("kind" to "UNKNOWN"), missingText)))
        val unknown = missing.parse()
        assertNull(firstClaims(unknown).single().scope)
        assertTrue(unknown.directionalApplications.isEmpty())
        assertTrue(missing.prepared(unknown).availableTimes.isEmpty())
        val rawText = "2027-01-08 09:00부터 10:00까지 가능."
        val raw =
            fixture(
                rawText,
                listOf(Spec("raw", IrEffect.AVAILABLE, rawScope("2027-01-08T09:00", "2027-01-08T10:00"), rawText, precise = true)),
            )
        assertEquals(listOf(window("2027-01-08T09:00", "2027-01-08T10:00")), raw.prepared(raw.parse()).availableTimes)
    }

    @Test
    fun `WD05 unsupported date-scoped unconstrained remains rejected while genuine global unconstrained survives`() {
        val text = "2027-01-08에는 시간 제한 없이 가능."
        val bad = fixture(text, listOf(Spec("wrong-day", IrEffect.UNCONSTRAINED, whole(), text)))
        val rejected = bad.parse()
        assertTrue(firstClaims(rejected).isEmpty())
        assertEquals(IrProblem.SHAPE, firstResult(rejected).quarantined.single().problem)
        assertTrue(
            ScheduleDslProblem.REFERENCE in
                rejected.base.base.declaration.programs
                    .single { it.submissionVersionId == F.ir.first }
                    .problems,
        )
        assertTrue(bad.prepared(rejected).availableTimes.isEmpty())
        val globalText = "검색 기간 전체에 아무런 시간 제한이 없어요."
        val input = F.input(globalText).copy(policy = N.policy)
        val start = input.searchStart.atStartOfDay().toString()
        val end = input.searchEnd.atStartOfDay().toString()
        val global = fixture(globalText, listOf(Spec("global", IrEffect.UNCONSTRAINED, rawScope(start, end), globalText, precise = true)))
        val accepted = global.parse()
        assertEquals(IrEffect.UNCONSTRAINED, firstClaims(accepted).single().effect)
        assertEquals(listOf(window(start, end)), global.prepared(accepted).availableTimes)
    }

    @Test
    fun `WD06 native request input calendar instructions graph and C4 isolation remain protected`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val oldBytes = c4.requestBytes(capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val previous = fields(mapper.readValue(oldBytes, Map::class.java))
        val current = fields(mapper.readValue(contract.requestBytes(capsule, MeetingMode.EITHER), Map::class.java))
        assertEquals(previous.getValue("inputs"), current.getValue("inputs"))
        assertEquals(previous.getValue("context"), current.getValue("context"))
        assertEquals("c5-owned-proof-choice-2", current["producer_schema_revision"])
        assertEquals("server-calendar-facts-1", fields(current["calendar_context"])["version"])
        val prompt = current.getValue("prompt") as String
        assertEquals(1, prompt.split(NEW_DATE_POLICY).size - 1)
        assertEquals(1, prompt.split(NEW_EVENT_POLICY).size - 1)
        val legacyPrompt = prompt.replace(NEW_DATE_POLICY, OLD_DATE_POLICY).replace(NEW_EVENT_POLICY, OLD_EVENT_POLICY)
        assertEquals(P12_PROMPT_SHA, sha256(legacyPrompt.toByteArray()), "Only the two approved temporal-policy substitutions may change")
        val schema = contract.responseSchema()
        assertClosed(schema, setOf("semantic_version", "ast_version", "vocabulary_version", "results"))
        val result = item(properties(schema).getValue("results"))
        assertClosed(result, setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions", "directional_times"))
        val props = properties(result)
        val graph = fields(props.getValue("program"))
        assertClosed(graph, setOf("nodes", "availability_root", "exclusion_root", "preference_root", "history_condition_ids"))
        assertEquals(0, fields(properties(graph).getValue("nodes"))["maxItems"])
        assertFalse(F.json(schema).contains("start_code_point"))
        assertFalse(F.json(schema).contains("end_code_point"))
        assertContentEquals(oldBytes, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(c4.responseSchema()))
    }

    private data class Spec(
        val id: String,
        val effect: IrEffect,
        val temporal: Map<String, Any?>,
        val quote: String,
        val interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        val precise: Boolean = false,
        val revision: Int = 3,
    )

    private data class Wire(
        val input: ScheduleDslOfflineInput,
        val document: Map<String, Any?>,
    ) {
        fun parse() =
            ScheduleDslStructuralProviderContract(JsonMapper.builder().findAndAddModules().build())
                .parseProviderResponse(F.json(document), F.snapshot(input), "schedule-ir-product-6", "schedule-ast-1", "ko-time-terms-1")

        fun prepared(envelope: ScheduleDslStructuralEnvelope) =
            ScheduleDslStructuralCalculator
                .calculate(envelope, F.snapshot(input), input.policy, MeetingMode.REMOTE)
                .preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
    }

    private fun fixture(
        text: String,
        specs: List<Spec>,
    ): Wire {
        val input = F.input(text).copy(policy = N.policy)
        val peer = Spec("peer", IrEffect.AVAILABLE, bounds("14:00", "18:00"), input.inputs[1].rawText, precise = true)
        val results =
            listOf(specs, listOf(peer)).mapIndexed { index, claims ->
                mapOf(
                    "input_ref" to input.inputs[index].submissionVersionId.toString(),
                    "conditions" to claims.map(::condition),
                    "program" to program(claims),
                    "approved_terms" to emptyList<Any>(),
                    "directional_times" to emptyList<Any>(),
                    "mode_restrictions" to emptyList<Any>(),
                )
            }
        return Wire(
            input,
            mapOf(
                "semantic_version" to "schedule-ir-product-6",
                "ast_version" to "schedule-ast-1",
                "vocabulary_version" to "ko-time-terms-1",
                "results" to results,
            ),
        )
    }

    private fun condition(spec: Spec): Map<String, Any?> {
        val proofs =
            listOf(locator(spec.quote, spec.revision) + ("id" to "p:q")) +
                if (spec.temporal["kind"] == "APPROVED_TERM") {
                    listOf(locator("점심", spec.revision) + ("id" to "t:term"))
                } else {
                    emptyList()
                }
        return mapOf(
            "id" to spec.id,
            "effect" to spec.effect.name,
            "interpretation" to spec.interpretation.name,
            "temporal_resolution" to if (spec.precise) "PRECISE_INTERVAL" else "NON_PRECISE",
            "assertion" to "ASSERTED",
            "supersedes" to emptyList<String>(),
            "proofs" to proofs,
            "evidence" to listOf("p:q"),
            "temporal" to spec.temporal,
        )
    }

    private fun program(specs: List<Spec>): Map<String, Any?> {
        fun root(effects: Set<IrEffect>): Map<String, Any?>? {
            val selected = specs.filter { it.effect in effects }
            if (selected.isEmpty()) return null
            val children = selected.map { spec -> expression("CLAIM_REF", spec.id, emptyList(), listOf(spec)) }
            return if (children.size == 1) children.single() else expression("ANY_OF", null, children, selected)
        }
        return mapOf(
            "nodes" to emptyList<Any>(),
            "history_condition_ids" to emptyList<String>(),
            "availability_root" to root(setOf(IrEffect.AVAILABLE, IrEffect.UNCONSTRAINED)),
            "exclusion_root" to root(setOf(IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED)),
            "preference_root" to root(setOf(IrEffect.PREFERRED)),
        )
    }

    private fun expression(
        op: String,
        claim: String?,
        children: List<Map<String, Any?>>,
        specs: List<Spec>,
    ) = mapOf(
        "operator" to op,
        "claim_id" to claim,
        "children" to children,
        "interpretation" to if (specs.any { it.interpretation == IrInterpretation.INFERRED }) "INFERRED" else "EXPLICIT",
        "assertion" to "ASSERTED",
        "evidence" to specs.map { locator(it.quote, it.revision) }.distinct(),
    )

    private fun whole(date: String = "2027-01-08") =
        mapOf(
            "kind" to "WHOLE_DAY",
            "local_date" to date,
            "date_evidence" to listOf("p:q"),
        )

    private fun bounds(
        start: String,
        end: String,
    ) = mapOf(
        "kind" to "BOUNDS",
        "local_date" to "2027-01-08",
        "date_evidence" to listOf("p:q"),
        "lower" to mapOf("clock" to start, "clock_evidence" to listOf("p:q")),
        "upper" to mapOf("clock" to end, "clock_evidence" to listOf("p:q")),
    )

    private fun term() =
        mapOf(
            "kind" to "APPROVED_TERM",
            "term" to "LUNCH",
            "local_date" to "2027-01-08",
            "date_evidence" to listOf("p:q"),
            "term_evidence" to "t:term",
            "model_scope" to null,
        )

    private fun rawScope(
        start: String,
        end: String,
    ) = mapOf(
        "kind" to "RAW_SCOPE",
        "scope" to mapOf("dimension" to "TIME", "start" to start, "end" to end),
    )

    private fun locator(
        quote: String,
        revision: Int,
    ) = mapOf("source_id" to "original", "revision" to revision, "quote" to quote)

    private fun firstResult(envelope: ScheduleDslStructuralEnvelope) =
        envelope.base.base.declaration.claims.results
            .single { it.submissionVersionId == F.ir.first }

    private fun firstClaims(envelope: ScheduleDslStructuralEnvelope) = firstResult(envelope).conditions

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    /** Schema contract projection only. Unhandled constraints fail; no instance is declared schema-valid here. */
    private fun alternatives(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = fields(value)
        val reference = schema["\$ref"] as? String
        if (reference != null) {
            assertTrue(schema.keys.all { it in setOf("\$ref", "description") }, "Unhandled siblings of a schema reference")
            assertTrue(reference.startsWith("#/\$defs/"))
            return alternatives(fields(root.getValue("\$defs")).getValue(reference.removePrefix("#/\$defs/")), root)
        }
        val union = schema["anyOf"]
        if (union != null) {
            assertTrue(schema.keys.all { it in setOf("anyOf", "description") }, "Unhandled conjunctive union siblings")
            return (union as List<*>).flatMap { alternatives(it, root) }
        }
        assertTrue(schema.keys.none { it in setOf("allOf", "oneOf", "not", "if", "then", "else", "dependentSchemas") })
        return listOf(schema)
    }

    private fun enumValues(
        value: Any?,
        root: Map<String, Any?>,
    ): Set<String> =
        alternatives(value, root)
            .flatMap {
                assertTrue(it.keys.all { key -> key in setOf("type", "enum", "description") }, "Unhandled metadata constraint")
                assertEquals("string", it["type"])
                (it.getValue("enum") as List<*>).map { item -> item as String }
            }.toSet()

    private fun assertClosed(
        schema: Map<String, Any?>,
        keys: Set<String>,
    ) {
        assertTrue(
            schema.keys.all { it in setOf("type", "properties", "required", "additionalProperties", "description", "\$defs") },
            "Unhandled object constraint in this bounded schema-contract inspection",
        )
        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])
        assertEquals(keys, properties(schema).keys)
        assertEquals(keys, (schema.getValue("required") as List<*>).toSet())
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    private fun properties(value: Map<String, Any?>) = fields(value.getValue("properties"))

    private fun item(value: Any?) = fields(fields(value).getValue("items"))

    private fun sha256(bytes: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val CONDITION_KEYS =
            setOf(
                "id",
                "effect",
                "interpretation",
                "temporal_resolution",
                "proofs",
                "evidence",
                "assertion",
                "supersedes",
                "temporal",
            )
        private const val P12_PROMPT_SHA = "760aa7496e28ed98b2573ff391a2226d91e3d27eb9dcaec2447442202f5c8eeb"
        private const val OLD_DATE_POLICY =
            "A resolved date without a time restriction uses WHOLE_DAY. Missing clock numbers alone do not imply UNKNOWN. " +
                "Retain NON_PRECISE and date evidence without inventing clocks or ALL_DAY evidence."
        private const val NEW_DATE_POLICY =
            "Choose the temporal atom after determining the asserted effect and its restrictions. " +
                "Use WHOLE_DAY only when that effect applies to the entire resolved date; " +
                "a date-only availability answer without another restriction covers that date. " +
                "Retain NON_PRECISE and date evidence without inventing clocks or ALL_DAY evidence."
        private const val OLD_EVENT_POLICY =
            "An unsupported personal event clock stays UNKNOWN. Missing personal event clocks stay unknown."
        private const val NEW_EVENT_POLICY =
            "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; " +
                "retain their date and event context in complete condition evidence without inventing clocks or whole-day availability."
    }
}
