package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
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
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Frozen synthetic date-scope expectations; no language-model weekday correctness is asserted here. */
class SearchScopedEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val version = "meeting-date-scope-1"

    @Test
    fun `SDP01 new request anchors relative month context to search while preserving actual input dates and sources`() {
        val f = Fixture()
        val contract = SearchScopedEvidenceProviderContract(mapper)
        val old = mapper.readTree(ServerEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        val current = mapper.readTree(contract.requestBytes(f.capsule, MeetingMode.REMOTE))
        assertEquals("schedule-dsl-evidence-request-2", current.path("request_contract_version").asText())
        assertEquals("schedule-dsl-evidence-response-2", current.path("response_contract_version").asText())
        assertEquals(version, current.path("date_scope_version").asText())
        assertEquals(old.path("inputs"), current.path("inputs"))
        assertEquals(old.path("evidence_catalog"), current.path("evidence_catalog"))
        val scope = current.path("meeting_date_scope")
        assertEquals(version, scope.path("version").asText())
        assertEquals("2037-05-04", scope.path("start_inclusive").asText())
        assertEquals("2037-05-11", scope.path("end_exclusive").asText())
        val first = scope.path("inputs").single { it.path("input_ref").asText() == H.ir.first.toString() }
        val peer = scope.path("inputs").single { it.path("input_ref").asText() == H.ir.second.toString() }
        assertEquals(f.searchDates, first.path("allowed_dates").toList().map { it.asText() })
        assertEquals(
            (f.searchDates + "2027-01-08").toSet(),
            peer
                .path("allowed_dates")
                .toList()
                .map { it.asText() }
                .toSet(),
        )
        val prompt = current.path("prompt").asText()
        assertTrue(prompt.contains("meeting", ignoreCase = true))
        assertTrue(prompt.contains("relative", ignoreCase = true))
        assertFalse(prompt.contains(f.raw))
    }

    @Test
    fun `SDP02 schema date definition bounds generation and survives the shared finite provider wire projection`() {
        val f = Fixture()
        val contract = SearchScopedEvidenceProviderContract(mapper)
        val schema = mapper.valueToTree<JsonNode>(contract.responseSchema(f.snapshot))
        val dates = schema.path("\$defs").path("meeting_date")
        assertEquals("string", dates.path("type").asText())
        assertEquals(
            (f.searchDates + "2027-01-08").toSet(),
            dates
                .path("enum")
                .toList()
                .map { it.asText() }
                .toSet(),
        )
        assertFalse(dates.path("enum").any { it.asText() == "2037-05-11" })
        var localDateFields = 0

        fun visit(node: JsonNode) {
            if (node.isObject) {
                node.path("properties").properties().forEach { (name, field) ->
                    if (name == "local_date") {
                        localDateFields++
                        assertEquals("#/\$defs/meeting_date", field.path("\$ref").asText())
                    }
                }
                node.properties().forEach { (_, child) -> visit(child) }
            } else if (node.isArray) {
                node.forEach(::visit)
            }
        }
        visit(schema)
        assertTrue(localDateFields > 0)
        val neutral = contract.requestBytes(f.capsule, MeetingMode.REMOTE)
        val openAi = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, AnalysisProvider.OPENAI, "gpt-6-luna"))
        val gemini = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        val firstSchema = openAi.path("text").path("format").path("schema")
        val secondSchema = gemini.path("generationConfig").path("responseJsonSchema")
        assertEquals(dates, firstSchema.path("\$defs").path("meeting_date"))
        assertEquals(dates, secondSchema.path("\$defs").path("meeting_date"))
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
    }

    @Test
    fun `SDP03 declared relative date within search is preserved instead of returning to the input reference month`() {
        val f = Fixture()
        val actual = parse(f, f.document())
        val application = actual.directionalApplications.single { it.submissionVersionId == H.ir.first }
        assertEquals(LocalDate.parse("2037-05-05"), assertNotNull(application.resolvedScope.start).toLocalDate())
        assertNotNull(
            actual.base.base.declaration.programs
                .single { it.submissionVersionId == H.ir.first }
                .program,
        )
        assertEquals(f.raw, f.snapshot.sourceText(H.ir.first, "original"))
    }

    @Test
    fun `SDP04 another participant explicit outside date cannot authorize relative only input in any scalar date atom`() {
        val f = Fixture()
        val proof = f.whole(H.ir.first)
        val term =
            f.catalog.entries
                .first { it.inputId == H.ir.first && it.kind == ServerEvidenceKind.TERM && it.text == "오후" }
                .id
        val clock =
            f.catalog.entries
                .first { it.inputId == H.ir.first && it.kind == ServerEvidenceKind.CLOCK && it.text == "18:00" }
                .id
        val atoms =
            listOf(
                mapOf("kind" to "WHOLE_DAY", "local_date" to "2027-01-08", "date_evidence" to listOf(proof)),
                mapOf(
                    "kind" to "BOUNDS",
                    "local_date" to "2027-01-08",
                    "date_evidence" to listOf(proof),
                    "lower" to null,
                    "upper" to mapOf("clock" to "18:00", "clock_evidence" to listOf(clock)),
                ),
                mapOf(
                    "kind" to "APPROVED_TERM",
                    "term" to "PM",
                    "local_date" to "2027-01-08",
                    "term_evidence" to term,
                    "date_evidence" to listOf(proof),
                    "model_scope" to null,
                ),
            )
        atoms.forEach { atom -> invalid { parse(f, f.document(atom)) } }
    }

    @Test
    fun `SDP05 RAW_SCOPE timestamps cannot bypass the per participant meeting date boundary`() {
        val f = Fixture()
        val inside = f.rawScope("2037-05-05T14:00", "2037-05-05T16:00")
        val valid = parse(f, f.document(inside))
        assertEquals(
            LocalDate.parse("2037-05-05"),
            assertNotNull(
                valid.base.base.declaration.claims.results
                    .single { it.submissionVersionId == H.ir.first }
                    .conditions
                    .single()
                    .scope!!
                    .start,
            ).toLocalDate(),
        )
        val outside = f.rawScope("2027-01-08T14:00", "2027-01-08T16:00")
        invalid { parse(f, f.document(outside)) }
    }

    @Test
    fun `SDP06 own explicit numerical absolute date outside search is retained without calendar remapping`() {
        val f = Fixture("🙂 2039-08-20 하루 전체 가능.")
        val request = mapper.readTree(SearchScopedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        val scope = request.path("meeting_date_scope").path("inputs").single { it.path("input_ref").asText() == H.ir.first.toString() }
        assertTrue(scope.path("allowed_dates").any { it.asText() == "2039-08-20" })
        val actual = parse(f, f.document(f.wholeDay("2039-08-20")))
        val application = actual.directionalApplications.single { it.submissionVersionId == H.ir.first }
        assertEquals(LocalDate.parse("2039-08-20"), assertNotNull(application.resolvedScope.start).toLocalDate())
        assertNotNull(
            actual.base.base.declaration.programs
                .single { it.submissionVersionId == H.ir.first }
                .program,
        )
    }

    @Test
    fun `SDP07 missing or unsupported date scope marker cannot select an unguarded legacy response reader`() {
        val f = Fixture()
        for (marker in listOf(null, "meeting-date-scope-unknown", "")) {
            val document = f.document()
            if (marker == null) document.remove("date_scope_version") else document["date_scope_version"] = marker
            invalid { parse(f, document) }
        }
    }

    @Test
    fun `SDP08 archived reference generation one remains byte stable and retains its original date acceptance`() {
        val f = Fixture()
        val old = ServerEvidenceProviderContract(mapper)
        val frozen = old.requestBytes(f.capsule, MeetingMode.REMOTE)
        SearchScopedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE)
        assertContentEquals(frozen, old.requestBytes(f.capsule, MeetingMode.REMOTE))
        val oldRequest = mapper.readTree(frozen)
        assertEquals("schedule-dsl-evidence-response-1", oldRequest.path("response_contract_version").asText())
        assertFalse(oldRequest.has("date_scope_version"))
        assertFalse(oldRequest.has("meeting_date_scope"))
        val document = f.document(f.wholeDay("2027-01-08")).also { it.remove("date_scope_version") }
        val actual =
            old.parseProviderResponse(
                mapper.writeValueAsString(document),
                f.snapshot,
                SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                APPROVED_KOREAN_TIME_TERM_VERSION,
            )
        val application = actual.directionalApplications.single { it.submissionVersionId == H.ir.first }
        assertEquals(LocalDate.parse("2027-01-08"), assertNotNull(application.resolvedScope.start).toLocalDate())
    }

    private fun parse(
        f: Fixture,
        document: Map<String, Any?>,
    ) = SearchScopedEvidenceProviderContract(mapper).parseProviderResponse(
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

    private class Fixture(
        val raw: String = "🙂 모임을 찾는 달 첫째 화요일 하루 전체 가능하고, 다음 수요일 오후는 18:00 이전 가능.",
    ) {
        private val input =
            H.input(raw).copy(
                policy = N.policy,
                meetingMode = MeetingMode.REMOTE,
                searchStart = LocalDate.parse("2037-05-04"),
                searchEnd = LocalDate.parse("2037-05-11"),
            )
        val snapshot: ScheduleEvidenceSnapshot = H.snapshot(input)
        val capsule = H.capsule(input)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val searchDates = (0L..6L).map { LocalDate.parse("2037-05-04").plusDays(it).toString() }

        fun whole(inputId: UUID): String =
            catalog.entries
                .single {
                    it.inputId == inputId && it.kind == ServerEvidenceKind.CONTEXT && it.text == snapshot.sourceText(inputId, "original")
                }.id

        fun wholeDay(date: String): Map<String, Any?> =
            mapOf("kind" to "WHOLE_DAY", "local_date" to date, "date_evidence" to listOf(whole(H.ir.first)))

        fun rawScope(
            start: String,
            end: String,
        ): Map<String, Any?> = mapOf("kind" to "RAW_SCOPE", "scope" to mapOf("dimension" to "TIME", "start" to start, "end" to end))

        fun document(atom: Map<String, Any?> = wholeDay("2037-05-05")): MutableMap<String, Any?> =
            linkedMapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "date_scope_version" to "meeting-date-scope-1",
                "results" to
                    listOf(
                        result(H.ir.first, "relative", atom),
                        result(H.ir.second, "peer", rawScope("2027-01-08T14:00", "2027-01-08T18:00")),
                    ),
            )

        private fun result(
            inputId: UUID,
            id: String,
            atom: Map<String, Any?>,
        ): Map<String, Any?> {
            val proof = whole(inputId)
            return mapOf(
                "input_ref" to inputId.toString(),
                "conditions" to
                    listOf(
                        mapOf(
                            "id" to id,
                            "effect" to "AVAILABLE",
                            "interpretation" to "EXPLICIT",
                            "temporal_resolution" to if (atom["kind"] == "RAW_SCOPE") "PRECISE_INTERVAL" else "NON_PRECISE",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "temporal" to atom,
                        ),
                    ),
                "program" to
                    mapOf(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to
                            mapOf(
                                "operator" to "CLAIM_REF",
                                "claim_id" to id,
                                "children" to emptyList<Any>(),
                                "interpretation" to "EXPLICIT",
                                "assertion" to "ASSERTED",
                                "evidence" to listOf(proof),
                            ),
                        "exclusion_root" to null,
                        "preference_root" to null,
                        "history_condition_ids" to emptyList<String>(),
                    ),
                "approved_terms" to emptyList<Any>(),
                "directional_times" to emptyList<Any>(),
                "mode_restrictions" to emptyList<Any>(),
            )
        }
    }
}
