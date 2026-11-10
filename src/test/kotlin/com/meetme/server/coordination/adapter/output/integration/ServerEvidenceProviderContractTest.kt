package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.SchedulePlaceVerification
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceEntry
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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

/** Independent synthetic wire expectations. No model output is replaced in a live evaluation. */
class ServerEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `SEP01 request preserves immutable sources and gives each catalogue entry its original owner and exact text`() {
        val f = Fixture()
        val contract = ServerEvidenceProviderContract(mapper)
        val request = mapper.readTree(contract.requestBytes(f.capsule, MeetingMode.REMOTE))
        val prior = mapper.readTree(ScheduleDslStructuralProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        assertEquals(prior.path("inputs"), request.path("inputs"))
        assertEquals("schedule-dsl-evidence-request-1", request.path("request_contract_version").asText())
        assertEquals("schedule-dsl-evidence-response-1", request.path("response_contract_version").asText())
        assertEquals(ServerEvidenceCatalog.VERSION, request.path("evidence_catalog_version").asText())
        val catalog = request.path("evidence_catalog")
        assertEquals(ServerEvidenceCatalog.VERSION, catalog.path("version").asText())
        val entries = catalog.path("entries").toList()
        assertEquals(f.catalog.entries.size, entries.size)
        for (entry in f.catalog.entries) {
            val wire = entries.single { it.path("id").asText() == entry.id }
            assertEquals(entry.inputId.toString(), wire.path("input_ref").asText())
            assertEquals(entry.span.sourceId, wire.path("source_id").asText())
            assertEquals(entry.span.revision, wire.path("revision").asInt())
            assertEquals(entry.text, wire.path("text").asText())
        }
        assertContentEquals(f.raw.toByteArray(), requireNotNull(f.snapshot.sourceText(H.ir.first, "original")).toByteArray())
    }

    @Test
    fun `SEP02 schema uses server references for all evidence without model quotes offsets or a generated proof bank`() {
        for (places in listOf(false, true)) {
            val schema = mapper.valueToTree<JsonNode>(ServerEvidenceProviderContract(mapper, places).responseSchema())
            val properties = mutableSetOf<String>()
            val evidenceFields = mutableListOf<Pair<String, JsonNode>>()

            fun visit(node: JsonNode) {
                if (node.isObject) {
                    node.path("properties").properties().forEach { (name, field) ->
                        properties += name
                        if (name in setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")) {
                            evidenceFields += name to field
                        }
                    }
                    node.properties().forEach { (_, child) -> visit(child) }
                } else if (node.isArray) {
                    node.forEach(::visit)
                }
            }
            visit(schema)
            assertTrue("evidence_catalog_version" in properties)
            assertFalse(properties.any { it in setOf("quote", "start_code_point", "end_code_point", "proofs") })
            assertTrue(evidenceFields.any { it.first == "evidence" })
            assertTrue(evidenceFields.any { it.first == "date_evidence" })
            assertTrue(evidenceFields.any { it.first == "clock_evidence" })
            assertTrue(evidenceFields.any { it.first == "term_evidence" })
            evidenceFields.forEach { (name, field) ->
                if (name in setOf("evidence", "date_evidence", "clock_evidence") && field.path("type").asText() == "array") {
                    assertEquals("string", field.path("items").path("type").asText())
                }
            }
        }
    }

    @Test
    fun `SEP03 instructions ask for issued references and contain no legacy exact quotation copying requirement`() {
        val f = Fixture()
        val request = mapper.readTree(ServerEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        val prompt = request.path("prompt").asText()
        assertTrue(prompt.contains("evidence", ignoreCase = true))
        assertTrue(Regex("(?i)(catalog|reference|issued)").containsMatchIn(prompt))
        assertFalse(prompt.contains("Use exact contiguous quotations"))
        assertFalse(prompt.contains("Write exact native source_id/revision/quote"))
        assertFalse(prompt.contains(f.raw))
        assertEquals(ServerEvidenceProviderContract(mapper).responseSchema(), mapper.convertValue(request.path("schema"), Map::class.java))
    }

    @Test
    fun `SEP04 inline relation evidence comes from server text with conjunctions preserved and both children retained`() {
        val f = Fixture()
        val document = f.document()
        val frozen = mapper.writeValueAsBytes(document)
        val actual = parse(f, document)
        assertEquals(ServerEvidenceCatalog.VERSION, actual.evidenceCatalogVersion)
        val program =
            assertNotNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == H.ir.first }
                    .program,
            )
        val root = program.nodes.single { it.id == program.availabilityRoot }
        assertEquals(ScheduleDslOperator.ANY_OF, root.operator)
        assertEquals(2, root.children.size)
        assertTrue(root.evidence.any { it == f.whole().span })
        assertTrue(actual.locatedEvidence.isNotEmpty())
        assertTrue(actual.locatedEvidence.all { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE })
        assertTrue(actual.locatedEvidence.all { it.evidence.catalogId != null })
        assertEquals(
            f.raw,
            f.catalog.entries
                .single { it.id == f.whole().id }
                .text,
        )
        assertContentEquals(frozen, mapper.writeValueAsBytes(document))
    }

    @Test
    fun `SEP05 referenced repeated term identifies the selected occurrence rather than the first native match`() {
        val raw = "🙂 안내 오후.\n2027-01-08 오후 가능.\n끝 오후."
        val f = Fixture(raw)
        val context = f.entry("2027-01-08 오후 가능.")
        val utf16 = raw.indexOf("오후", raw.indexOf("2027-01-08"))
        val expectedStart = raw.codePointCount(0, utf16)
        val term =
            f.catalog.entries.single {
                it.inputId == H.ir.first && it.kind == ServerEvidenceKind.TERM && it.text == "오후" && it.span.startCodePoint == expectedStart
            }
        val atom =
            linkedMapOf<String, Any?>(
                "kind" to "APPROVED_TERM",
                "term" to "PM",
                "local_date" to "2027-01-08",
                "term_evidence" to term.id,
                "date_evidence" to listOf(context.id),
                "model_scope" to null,
            )
        val document = f.document(listOf(f.claim("available", atom, context.id)), f.ref("available", context.id))
        val actual = parse(f, document)
        val application =
            actual.base.base.termApplications
                .single { it.submissionVersionId == H.ir.first }
        assertEquals(expectedStart, application.termEvidence.startCodePoint)
        assertEquals(term.span, application.termEvidence)
        assertNotNull(
            actual.base.base.declaration.programs
                .single { it.submissionVersionId == H.ir.first }
                .program,
        )
        assertEquals(H.scope("2027-01-08T12:00", "2027-01-09T00:00"), application.resolvedScope)
    }

    @Test
    fun `SEP06 known one sided clock uses a referenced native clock while retaining its declared bound`() {
        val f = Fixture("🙂 2027-01-08 18:00 이전 가능.")
        val clock = f.entry("18:00", kind = ServerEvidenceKind.CLOCK)
        val atom =
            linkedMapOf<String, Any?>(
                "kind" to "BOUNDS",
                "local_date" to "2027-01-08",
                "date_evidence" to listOf(f.whole().id),
                "lower" to null,
                "upper" to mapOf("clock" to "18:00", "clock_evidence" to listOf(clock.id)),
            )
        val actual = parse(f, f.document(listOf(f.claim("before", atom)), f.ref("before")))
        val application = actual.directionalApplications.single { it.submissionVersionId == H.ir.first }
        assertEquals("18:00", application.declaration.clock.toString())
        assertEquals(
            clock.span,
            application.declaration.clockEvidence
                .single()
                .span,
        )
        assertEquals(H.scope("2027-01-08T00:00", "2027-01-08T18:00"), application.resolvedScope)
    }

    @Test
    fun `SEP07 missing unknown or mixed legacy evidence markers cannot silently select the old reader`() {
        val f = Fixture()
        val mutations: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { it.remove("evidence_catalog_version") },
                { it["evidence_catalog_version"] = "synthetic-unsupported-catalogue" },
                { firstClaim(it)["evidence"] = listOf(mapOf("source_id" to "original", "revision" to 3, "quote" to f.raw)) },
                { firstClaim(it)["proofs"] = emptyList<Any>() },
                { firstClaim(it)["start_code_point"] = 0 },
            )
        mutations.forEach { mutate ->
            val document = f.document()
            mutate(document)
            invalid { parse(f, document) }
        }
    }

    @Test
    fun `SEP08 unknown foreign and missing references fail without replacing them with whole original evidence`() {
        val f = Fixture()
        val foreign = f.whole(H.ir.second).id
        val mutations: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { firstClaim(it)["evidence"] = listOf("ev:00000000000000000000000000000000") },
                { firstClaim(it)["evidence"] = listOf(foreign) },
                { firstClaim(it)["evidence"] = emptyList<String>() },
                { root(it)["evidence"] = listOf(foreign) },
                { root(it)["evidence"] = listOf("missing-server-entry") },
            )
        mutations.forEach { mutate ->
            val document = f.document()
            mutate(document)
            invalid { parse(f, document) }
        }
    }

    @Test
    fun `SEP09 same participant old source text references cannot authorize a changed native source`() {
        val original = Fixture()
        val changed = Fixture(original.raw + "\n추가된 합성 제한.")
        invalid { parse(changed, original.document()) }
        val texts =
            original.snapshot.context.inputs
                .associate { input ->
                    input.submissionVersionId to
                        input.sources.associate { source ->
                            source.sourceId to requireNotNull(original.snapshot.sourceText(input.submissionVersionId, source.sourceId))
                        }
                }.toMutableMap()
        texts[H.ir.first] = mapOf("original" to original.raw.replace("가능하고", "불가하고"))
        val tampered = ScheduleEvidenceSnapshot(original.snapshot.context, texts)
        invalid { parse(original, original.document(), tampered) }
    }

    @Test
    fun `SEP10 a valid evidence ID does not bypass a missing claim or availability role mismatch`() {
        val f = Fixture()
        val missing = f.document().also { root(it)["children"] = listOf(f.ref("undeclared"), f.ref("first")) }
        val wrongRole = f.document().also { firstClaim(it)["effect"] = "UNAVAILABLE" }
        for (document in listOf(missing, wrongRole)) {
            val actual = parse(f, document)
            val participant =
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == H.ir.first }
            assertNull(participant.program)
            assertTrue(participant.problems.isNotEmpty())
            assertNotNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == H.ir.second }
                    .program,
            )
        }
    }

    @Test
    fun `SEP11 place references retain native origin and duration without inventing a travel mode or confirmation`() {
        val place = "광명역에서 35분 이내인 곳."
        val f = Fixture("🙂 2027-01-08 가능.\n$place")
        val atom = mapOf("kind" to "WHOLE_DAY", "local_date" to "2027-01-08", "date_evidence" to listOf(f.entry("🙂 2027-01-08 가능.").id))
        val document = f.document(listOf(f.claim("available", atom, f.entry("🙂 2027-01-08 가능.").id)), f.ref("available"))
        document["place_constraints_version"] = SCHEDULE_PLACE_CONSTRAINT_VERSION
        results(document).forEachIndexed { index, result ->
            result["place_constraints"] =
                if (index == 0) {
                    listOf(
                        mapOf(
                            "id" to "origin-constraint",
                            "kind" to "TRAVEL_TIME_LIMIT",
                            "reference_kind" to "PUBLIC_NAMED_PLACE",
                            "place_evidence" to f.entry("광명역", kind = ServerEvidenceKind.PLACE).id,
                            "evidence" to f.entry(place).id,
                            "max_minutes" to 35,
                            "travel_mode" to null,
                            "time_scope" to "ALL_AVAILABLE_TIMES",
                        ),
                    )
                } else {
                    emptyList<Any>()
                }
        }
        invalid { parse(f, document) }
        val actual = parse(f, document, places = true)
        val constraint = actual.placeConstraints.single()
        assertEquals(35, constraint.maxMinutes)
        assertNull(constraint.travelMode)
        assertEquals(SchedulePlaceVerification.UNVERIFIED, constraint.verification)
        assertEquals(f.entry("광명역", kind = ServerEvidenceKind.PLACE).span, assertNotNull(constraint.placeEvidence).span)
        assertEquals(f.entry(place).span, constraint.evidence.span)
    }

    private fun parse(
        f: Fixture,
        document: MutableMap<String, Any?>,
        snapshot: ScheduleEvidenceSnapshot = f.snapshot,
        places: Boolean = false,
    ) = ServerEvidenceProviderContract(mapper, places).parseProviderResponse(
        mapper.writeValueAsString(document),
        snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Suppress("UNCHECKED_CAST")
    private fun results(document: MutableMap<String, Any?>) = document["results"] as List<MutableMap<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun firstClaim(document: MutableMap<String, Any?>) =
        (results(document).first()["conditions"] as List<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun root(document: MutableMap<String, Any?>) =
        (results(document).first()["program"] as Map<String, Any?>)["availability_root"] as MutableMap<String, Any?>

    private class Fixture(
        val raw: String = "🙂 2027-01-07 08:10~09:20 가능하고, 2027-01-08 15:30~16:40 가능.",
    ) {
        private val input = H.input(raw).copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val snapshot = H.snapshot(input)
        val capsule: ScheduleDslProviderInputCapsule = H.capsule(input)
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun entry(
            text: String,
            inputId: UUID = H.ir.first,
            kind: ServerEvidenceKind = ServerEvidenceKind.CONTEXT,
        ): ServerEvidenceEntry = catalog.entries.first { it.inputId == inputId && it.text == text && it.kind == kind }

        fun whole(inputId: UUID = H.ir.first) = entry(requireNotNull(snapshot.sourceText(inputId, "original")), inputId)

        fun claim(
            id: String,
            temporal: Map<String, Any?>,
            proof: String = whole().id,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "id" to id,
                "effect" to "AVAILABLE",
                "interpretation" to "EXPLICIT",
                "temporal_resolution" to if (temporal["kind"] == "RAW_SCOPE") "PRECISE_INTERVAL" else "NON_PRECISE",
                "evidence" to listOf(proof),
                "assertion" to "ASSERTED",
                "supersedes" to emptyList<String>(),
                "temporal" to temporal,
            )

        fun ref(
            id: String,
            proof: String = whole().id,
        ) = expression("CLAIM_REF", proof, claim = id)

        private fun expression(
            operator: String,
            proof: String,
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

        fun document(
            claims: List<MutableMap<String, Any?>> =
                listOf(
                    claim("first", rawScope("2027-01-07T08:10", "2027-01-07T09:20")),
                    claim("second", rawScope("2027-01-08T15:30", "2027-01-08T16:40")),
                ),
            root: MutableMap<String, Any?> = expression("ANY_OF", whole().id, listOf(ref("first"), ref("second"))),
        ): MutableMap<String, Any?> {
            val peer = whole(H.ir.second).id
            return linkedMapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "results" to
                    listOf(
                        result(H.ir.first, claims, root),
                        result(
                            H.ir.second,
                            listOf(claim("peer", rawScope("2027-01-08T14:00", "2027-01-08T18:00"), peer)),
                            ref("peer", peer),
                        ),
                    ),
            )
        }

        private fun result(
            id: UUID,
            claims: List<MutableMap<String, Any?>>,
            root: MutableMap<String, Any?>,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "input_ref" to id.toString(),
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
                "directional_times" to emptyList<Any>(),
                "mode_restrictions" to emptyList<Any>(),
            )

        private fun rawScope(
            start: String,
            end: String,
        ) = mapOf("kind" to "RAW_SCOPE", "scope" to mapOf("dimension" to "TIME", "start" to start, "end" to end))
    }
}
