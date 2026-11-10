package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.IndexedEvidenceSelection
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.SchedulePlaceVerification
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Independent numeric-selection contract expectations; long native identities remain server-owned. */
class IndexedEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `IEP01 request issues exact zero based selections and both wires retain their bounds without exposing native IDs`() {
        val f = Fixture()
        val contract = IndexedEvidenceProviderContract(mapper, allowPlaceConstraints = true)
        val neutral = contract.requestBytes(f.capsule, MeetingMode.EITHER)
        val request = mapper.readTree(neutral)
        val old = mapper.readTree(CalendarFactsEvidenceProviderContract(mapper, true).requestBytes(f.capsule, MeetingMode.EITHER))
        assertEquals("schedule-dsl-evidence-request-4", request.path("request_contract_version").asText())
        assertEquals("schedule-dsl-evidence-response-4", request.path("response_contract_version").asText())
        assertEquals(IndexedEvidenceSelection.VERSION, request.path("evidence_selection_version").asText())
        for (key in listOf("inputs", "calendar_context", "meeting_date_scope", "relative_date_context")) {
            assertEquals(old.path(key), request.path(key), key)
        }
        val entries = request.path("evidence_catalog").path("entries").toList()
        assertEquals(f.catalog.entries.size, entries.size)
        entries.forEachIndexed { index, entry ->
            val native = f.catalog.entries[index]
            assertFalse(entry.has("id"))
            assertTrue(entry.path("selection").isIntegralNumber)
            assertEquals(index, entry.path("selection").asInt())
            assertEquals(native.inputId.toString(), entry.path("input_ref").asText())
            assertEquals(native.span.sourceId, entry.path("source_id").asText())
            assertEquals(native.span.revision, entry.path("revision").asInt())
            assertEquals(native.kind.name, entry.path("kind").asText())
            assertEquals(native.text, entry.path("text").asText())
        }
        val encoded = neutral.toString(Charsets.UTF_8)
        assertTrue(f.catalog.entries.none { encoded.contains(it.id) })
        val openAi = wire(neutral, AnalysisProvider.OPENAI)
        val gemini = wire(neutral, AnalysisProvider.GEMINI)
        assertEquals(
            mapper.readTree(openAi.path("input").asText()),
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            ),
        )
        assertEquals(
            openAi.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        for (body in listOf(openAi, gemini)) assertTrue(f.catalog.entries.none { body.toString().contains(it.id) })
        val firstSchema = openAi.path("text").path("format").path("schema")
        val secondSchema = gemini.path("generationConfig").path("responseJsonSchema")
        assertIntegerEvidence(firstSchema, entries.size)
        assertIntegerEvidence(secondSchema, entries.size)
        assertEquals(firstSchema.path("\$defs").path("meeting_date"), secondSchema.path("\$defs").path("meeting_date"))
    }

    @Test
    fun `IEP02 response schema bounds every evidence position to actual issued selections and requires its version`() {
        val f = Fixture()
        for (places in listOf(false, true)) {
            val schema = mapper.valueToTree<JsonNode>(IndexedEvidenceProviderContract(mapper, places).responseSchema(f.snapshot))
            assertIntegerEvidence(schema, f.catalog.entries.size)
            assertTrue(schema.path("required").any { it.asText() == "evidence_selection_version" })
            assertEquals(
                listOf(IndexedEvidenceSelection.VERSION),
                schema
                    .path("properties")
                    .path("evidence_selection_version")
                    .path("enum")
                    .toList()
                    .map { it.asText() },
            )
        }
    }

    @Test
    fun `IEP03 integer condition date clock and relation references resolve to exactly the unchanged native declaration`() {
        val f = Fixture()
        val document = f.document()
        val frozen = mapper.writeValueAsBytes(document)
        val expected = legacyParse(f, f.legacy(document))
        val actual = parse(f, document)
        assertEquals(expected, actual)
        val program =
            assertNotNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == H.ir.first }
                    .program,
            )
        assertEquals(
            2,
            program.nodes
                .single { it.id == program.availabilityRoot }
                .children.size,
        )
        val first =
            actual.calculationProjection.claims.results
                .single { it.submissionVersionId == H.ir.first }
        assertEquals(setOf("day", "clock"), first.conditions.map { it.id }.toSet())
        assertEquals(H.scope("2028-03-01T14:00", "2028-03-01T16:00"), first.conditions.single { it.id == "clock" }.scope)
        assertTrue(
            actual.locatedEvidence.all { proof ->
                f.catalog.entries.any {
                    it.id == proof.evidence.catalogId &&
                        it.inputId == proof.submissionVersionId
                }
            },
        )
        assertContentEquals(frozen, mapper.writeValueAsBytes(document))
        assertEquals(f.raw, f.snapshot.sourceText(H.ir.first, "original"))
    }

    @Test
    fun `IEP04 repeated approved terms select an exact position rather than the first matching native word`() {
        val raw = "🙂 안내 오후.\n2028-03-01 오후 가능.\n끝 오후."
        val f = Fixture(raw)
        val context = f.selection("2028-03-01 오후 가능.")
        val start = raw.codePointCount(0, raw.indexOf("오후", raw.indexOf("2028-03-01")))
        val term =
            f.catalog.entries.indexOfFirst {
                it.inputId == H.ir.first &&
                    it.kind == ServerEvidenceKind.TERM &&
                    it.text == "오후" &&
                    it.span.startCodePoint == start
            }
        assertTrue(term >= 0)
        val atom =
            mapOf(
                "kind" to "APPROVED_TERM",
                "term" to "PM",
                "local_date" to "2028-03-01",
                "term_evidence" to term,
                "date_evidence" to listOf(context),
                "model_scope" to null,
            )
        val actual = parse(f, f.document(listOf(f.claim("available", atom, context)), f.ref("available", context)))
        val application =
            actual.base.base.termApplications
                .single { it.submissionVersionId == H.ir.first }
        assertEquals(f.catalog.entries[term].span, application.termEvidence)
        assertEquals(start, application.termEvidence.startCodePoint)
        assertEquals(H.scope("2028-03-01T12:00", "2028-03-02T00:00"), application.resolvedScope)
    }

    @Test
    fun `IEP05 place and mode evidence use numeric selections while keeping duration ownership and unverified travel`() {
        val expression = "광명역에서 35분 이내인 곳."
        val f = Fixture("🙂 2028-03-01 오후 대면만 가능.\n$expression")
        val time = f.selection("🙂 2028-03-01 오후 대면만 가능.")
        val term = f.selection("오후", kind = ServerEvidenceKind.TERM)
        val atom =
            mapOf(
                "kind" to "APPROVED_TERM",
                "term" to "PM",
                "local_date" to "2028-03-01",
                "term_evidence" to term,
                "date_evidence" to listOf(time),
                "model_scope" to null,
            )
        val document = f.document(listOf(f.claim("available", atom, time)), f.ref("available", time), places = emptyList())
        val result = firstResult(document)
        result["place_constraints"] =
            listOf(
                mapOf(
                    "id" to "travel",
                    "kind" to "TRAVEL_TIME_LIMIT",
                    "reference_kind" to "PUBLIC_NAMED_PLACE",
                    "place_evidence" to f.selection("광명역", kind = ServerEvidenceKind.PLACE),
                    "evidence" to f.selection(expression),
                    "max_minutes" to 35,
                    "travel_mode" to null,
                    "time_scope" to "ALL_AVAILABLE_TIMES",
                ),
            )
        result["mode_restrictions"] =
            listOf(
                mapOf(
                    "id" to "mode",
                    "scope" to mapOf("dimension" to "TIME", "start" to "2028-03-01T12:00", "end" to "2028-03-02T00:00"),
                    "permitted_modes" to listOf("IN_PERSON"),
                    "mode_interpretation" to "EXPLICIT",
                    "time_interpretation" to "EXPLICIT",
                    "temporal_resolution" to "PRECISE_INTERVAL",
                    "evidence" to listOf(time),
                    "assertion" to "ASSERTED",
                    "supersedes" to emptyList<String>(),
                    "alternative_group" to null,
                    "alternative" to null,
                ),
            )
        val actual = parse(f, document, places = true)
        val place = actual.placeConstraints.single()
        assertEquals(35, place.maxMinutes)
        assertNull(place.travelMode)
        assertEquals(SchedulePlaceVerification.UNVERIFIED, place.verification)
        assertEquals(f.catalog.entries[f.selection(expression)].span, place.evidence.span)
        assertEquals(
            f.catalog.entries[time].span,
            actual.base.modeRestrictions
                .single()
                .evidence
                .single(),
        )
        assertEquals(legacyParse(f, f.legacy(document), places = true), actual)
    }

    @Test
    fun `IEP06 invalid numeric types bounds and foreign ownership cannot be repaired with another native entry`() {
        val f = Fixture()
        val foreign = f.whole(H.ir.second)
        val ownId = f.catalog.entries[f.whole()].id
        val invalidValues: List<Any?> =
            listOf(
                -1,
                f.catalog.entries.size,
                Int.MAX_VALUE,
                Long.MAX_VALUE,
                "0",
                ownId,
                0.5,
                1.0,
                true,
                null,
                mapOf(
                    "selection" to f.whole(),
                ),
            )
        for (value in invalidValues) {
            val document = f.document()
            firstClaim(document)["evidence"] = listOf(value)
            invalid { parse(f, document) }
        }
        for (mutate in listOf<(MutableMap<String, Any?>) -> Unit>(
            { firstClaim(it)["evidence"] = listOf(foreign) },
            { firstAtom(it)["date_evidence"] = listOf(foreign) },
            { firstRoot(it)["evidence"] = listOf(foreign) },
        )) {
            val document = f.document()
            mutate(document)
            invalid { parse(f, document) }
        }
    }

    @Test
    fun `IEP07 required markers mixed syntax and native source mutation remain fail closed`() {
        val f = Fixture()
        for (mutate in listOf<(MutableMap<String, Any?>) -> Unit>(
            { it.remove("evidence_selection_version") },
            { it["evidence_selection_version"] = "server-evidence-selection-unknown" },
            { it["calendar_facts_version"] = "server-calendar-facts-unknown" },
            { it["date_scope_version"] = "meeting-date-scope-unknown" },
            { it["evidence_catalog_version"] = "server-evidence-catalog-unknown" },
            { firstClaim(it)["quote"] = f.raw },
            { firstClaim(it)["proofs"] = emptyList<Any>() },
        )) {
            val document = f.document()
            mutate(document)
            invalid { parse(f, document) }
        }
        val texts =
            f.snapshot.context.inputs
                .associate { input ->
                    input.submissionVersionId to
                        input.sources.associate { source ->
                            source.sourceId to
                                assertNotNull(f.snapshot.sourceText(input.submissionVersionId, source.sourceId))
                        }
                }.toMutableMap()
        texts[H.ir.first] = mapOf("original" to f.raw.replace("가능", "불가능"))
        invalid { parse(f, f.document(), snapshot = ScheduleEvidenceSnapshot(f.snapshot.context, texts)) }
    }

    @Test
    fun `IEP08 archived generation three remains unchanged and never accepts numeric evidence as long native IDs`() {
        val f = Fixture()
        val old = CalendarFactsEvidenceProviderContract(mapper)
        val before = old.requestBytes(f.capsule, MeetingMode.EITHER)
        IndexedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        assertContentEquals(before, old.requestBytes(f.capsule, MeetingMode.EITHER))
        assertFalse(mapper.readTree(before).has("evidence_selection_version"))
        assertEquals("schedule-dsl-evidence-response-3", mapper.readTree(before).path("response_contract_version").asText())
        val canonical = legacyParse(f, f.legacy(f.document()))
        assertEquals(
            2,
            canonical.calculationProjection.claims.results
                .single { it.submissionVersionId == H.ir.first }
                .conditions.size,
        )
        invalid { legacyParse(f, f.document()) }
        invalid { parse(f, f.legacy(f.document())) }
    }

    private fun assertIntegerEvidence(
        schema: JsonNode,
        count: Int,
    ) {
        var evidenceFieldCount = 0

        fun bounded(value: JsonNode) {
            val definition =
                when {
                    value.path("type").asText() == "array" -> value.path("items")
                    value.has("anyOf") -> value.path("anyOf").single { it.path("type").asText() != "null" }
                    else -> value
                }
            assertEquals("integer", definition.path("type").asText())
            assertTrue(definition.has("minimum"))
            assertTrue(definition.has("maximum"))
            assertEquals(0, definition.path("minimum").asInt())
            assertEquals(count - 1, definition.path("maximum").asInt())
        }

        fun visit(node: JsonNode) {
            if (node.isObject) {
                node.path("properties").properties().forEach { (name, field) ->
                    assertFalse(name in setOf("quote", "proofs", "start_code_point", "end_code_point"))
                    if (name in evidenceFields()) {
                        evidenceFieldCount++
                        bounded(field)
                    }
                }
                node.properties().forEach { (_, child) -> visit(child) }
            } else if (node.isArray) {
                node.forEach(::visit)
            }
        }
        visit(schema)
        assertTrue(evidenceFieldCount > 0)
    }

    private fun wire(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): JsonNode {
        val model = if (provider == AnalysisProvider.OPENAI) "gpt-6-luna" else "gemini-3.8-flash"
        return mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, provider, model))
    }

    private fun parse(
        f: Fixture,
        document: Map<String, Any?>,
        places: Boolean = false,
        snapshot: ScheduleEvidenceSnapshot = f.snapshot,
    ) = IndexedEvidenceProviderContract(
        mapper,
        places,
    ).parseProviderResponse(
        mapper.writeValueAsString(document),
        snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun legacyParse(
        f: Fixture,
        document: Map<String, Any?>,
        places: Boolean = false,
    ) = CalendarFactsEvidenceProviderContract(
        mapper,
        places,
    ).parseProviderResponse(
        mapper.writeValueAsString(document),
        f.snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstResult(document: MutableMap<String, Any?>) = (document["results"] as List<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun firstClaim(document: MutableMap<String, Any?>) =
        (firstResult(document)["conditions"] as List<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun firstAtom(document: MutableMap<String, Any?>) = firstClaim(document)["temporal"] as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun firstRoot(document: MutableMap<String, Any?>) =
        (firstResult(document)["program"] as MutableMap<String, Any?>)["availability_root"] as MutableMap<String, Any?>

    private fun evidenceFields() = setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")

    private class Fixture(
        val raw: String = "🙂 모임 기간의 화요일은 하루 전체 가능하고, 수요일 오후 2시부터 4시까지 가능.",
    ) {
        private val input =
            H.input(raw).let { old ->
                old.copy(
                    policy = N.policy,
                    meetingMode = MeetingMode.EITHER,
                    searchStart = LocalDate.parse("2028-02-26"),
                    searchEnd = LocalDate.parse("2028-03-04"),
                    inputs =
                        old.inputs.mapIndexed { index, item ->
                            if (index == 1) item.copy(rawText = "2027-01-08 하루 전체 가능.") else item
                        },
                )
            }
        val snapshot = H.snapshot(input)
        val capsule = H.capsule(input)
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun selection(
            text: String,
            owner: UUID = H.ir.first,
            kind: ServerEvidenceKind = ServerEvidenceKind.CONTEXT,
        ): Int = catalog.entries.indexOfFirst { it.inputId == owner && it.kind == kind && it.text == text }.also { require(it >= 0) }

        fun whole(owner: UUID = H.ir.first) = selection(assertNotNull(snapshot.sourceText(owner, "original")), owner)

        fun claim(
            id: String,
            atom: Map<String, Any?>,
            proof: Int = whole(),
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "id" to id,
                "effect" to "AVAILABLE",
                "interpretation" to "EXPLICIT",
                "temporal_resolution" to if (atom["kind"] == "BOUNDS") "PRECISE_INTERVAL" else "NON_PRECISE",
                "evidence" to listOf(proof),
                "assertion" to "ASSERTED",
                "supersedes" to emptyList<String>(),
                "temporal" to atom.toMutableMap(),
            )

        fun ref(
            id: String,
            proof: Int = whole(),
        ) = expression("CLAIM_REF", proof, claim = id)

        private fun expression(
            operator: String,
            proof: Int,
            children: List<Map<String, Any?>> = emptyList(),
            claim: String? = null,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "operator" to operator,
                "claim_id" to claim,
                "children" to children,
                "interpretation" to "EXPLICIT",
                "assertion" to "ASSERTED",
                "evidence" to listOf(proof),
            )

        private fun defaults(): List<MutableMap<String, Any?>> =
            listOf(
                claim("day", mapOf("kind" to "WHOLE_DAY", "local_date" to "2028-02-29", "date_evidence" to listOf(whole()))),
                claim(
                    "clock",
                    mapOf(
                        "kind" to "BOUNDS",
                        "local_date" to "2028-03-01",
                        "date_evidence" to listOf(whole()),
                        "lower" to
                            mapOf("clock" to "14:00", "clock_evidence" to listOf(selection("오후 2시", kind = ServerEvidenceKind.CLOCK))),
                        "upper" to mapOf("clock" to "16:00", "clock_evidence" to listOf(selection("4시", kind = ServerEvidenceKind.CLOCK))),
                    ),
                ),
            )

        fun document(
            claims: List<MutableMap<String, Any?>> = defaults(),
            root: MutableMap<String, Any?> = expression("ANY_OF", whole(), listOf(ref("day"), ref("clock"))),
            places: List<Map<String, Any?>>? = null,
        ): MutableMap<String, Any?> {
            val peer = whole(H.ir.second)
            val doc: MutableMap<String, Any?> =
                linkedMapOf(
                    "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                    "ast_version" to SCHEDULE_AST_VERSION,
                    "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                    "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                    "date_scope_version" to "meeting-date-scope-1",
                    "calendar_facts_version" to "server-calendar-facts-2",
                    "evidence_selection_version" to IndexedEvidenceSelection.VERSION,
                    "results" to
                        listOf(
                            result(H.ir.first, claims, root),
                            result(
                                H.ir.second,
                                listOf(
                                    claim(
                                        "peer",
                                        mapOf(
                                            "kind" to "WHOLE_DAY",
                                            "local_date" to "2027-01-08",
                                            "date_evidence" to listOf(peer),
                                        ),
                                        peer,
                                    ),
                                ),
                                ref("peer", peer),
                            ),
                        ),
                )
            if (places != null) {
                doc["place_constraints_version"] = SCHEDULE_PLACE_CONSTRAINT_VERSION
                @Suppress("UNCHECKED_CAST")
                (doc["results"] as List<MutableMap<String, Any?>>).forEachIndexed { index, result ->
                    result["place_constraints"] =
                        if (index == 0) places else emptyList<Any>()
                }
            }
            return doc
        }

        private fun result(
            input: UUID,
            claims: List<MutableMap<String, Any?>>,
            root: MutableMap<String, Any?>,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "input_ref" to input.toString(),
                "conditions" to claims,
                "program" to
                    linkedMapOf(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to root,
                        "exclusion_root" to null,
                        "preference_root" to null,
                        "history_condition_ids" to emptyList<String>(),
                    ),
                "approved_terms" to emptyList<Any>(),
                "mode_restrictions" to emptyList<Any>(),
                "directional_times" to emptyList<Any>(),
            )

        fun legacy(document: Map<String, Any?>): Map<String, Any?> {
            fun convert(value: Any?): Any? =
                when (value) {
                    is List<*> -> value.map(::convert)
                    is Map<*, *> ->
                        value.entries.filter { it.key != "evidence_selection_version" }.associate { (key, child) ->
                            val name = key as String
                            name to
                                if (name in setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")) {
                                    when (child) {
                                        is List<*> -> child.map { catalog.entries[it as Int].id }
                                        null -> null
                                        else -> catalog.entries[child as Int].id
                                    }
                                } else {
                                    convert(child)
                                }
                        }
                    else -> value
                }
            @Suppress("UNCHECKED_CAST")
            return convert(document) as Map<String, Any?>
        }
    }
}
