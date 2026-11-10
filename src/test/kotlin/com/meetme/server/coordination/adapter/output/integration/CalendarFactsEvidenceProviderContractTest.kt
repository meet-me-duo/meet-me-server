package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
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

/** Fixed calendar facts and contract boundaries, never a claim that a model interprets natural language correctly. */
class CalendarFactsEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val version = "server-calendar-facts-2"
    private val thirdResponse = "schedule-dsl-evidence-response-3"

    @Test
    fun `CFP01 clipped leap month rows describe the full calendar month and the following month independently`() {
        val f = Fixture()
        val request = mapper.readTree(CalendarFactsEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        assertEquals("schedule-dsl-evidence-request-3", request.path("request_contract_version").asText())
        assertEquals(thirdResponse, request.path("response_contract_version").asText())
        assertEquals(version, request.path("calendar_facts_version").asText())
        val calendar = request.path("calendar_context")
        assertEquals(version, calendar.path("version").asText())
        val expected =
            listOf(
                day("2028-02-26", "SATURDAY", 4, true, "TUESDAY", 29),
                day("2028-02-27", "SUNDAY", 4, true, "TUESDAY", 29),
                day("2028-02-28", "MONDAY", 4, true, "TUESDAY", 29),
                day("2028-02-29", "TUESDAY", 5, true, "TUESDAY", 29),
                day("2028-03-01", "WEDNESDAY", 1, false, "WEDNESDAY", 31),
                day("2028-03-02", "THURSDAY", 1, false, "WEDNESDAY", 31),
                day("2028-03-03", "FRIDAY", 1, false, "WEDNESDAY", 31),
            )
        assertEquals(mapper.valueToTree<JsonNode>(expected), calendar.path("search_days"))
    }

    @Test
    fun `CFP02 weekday occurrence is not a forced week row or an index inside the clipped search range`() {
        val f = Fixture(start = "2037-05-05", end = "2037-05-06")
        val request = mapper.readTree(CalendarFactsEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        assertEquals(
            mapper.valueToTree<JsonNode>(listOf(day("2037-05-05", "TUESDAY", 1, false, "FRIDAY", 31))),
            request.path("calendar_context").path("search_days"),
        )
    }

    @Test
    fun `CFP03 objective facts preserve native sources catalogue date authority and input reference facts`() {
        val f = Fixture()
        val old = mapper.readTree(SearchScopedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        val current = mapper.readTree(CalendarFactsEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.REMOTE))
        for (key in listOf(
            "inputs",
            "evidence_catalog",
            "relative_date_context",
            "meeting_date_scope",
            "evidence_catalog_version",
            "date_scope_version",
        )) {
            assertEquals(old.path(key), current.path(key), key)
        }
        for (key in listOf("time_zone", "search_end_exclusive", "reference_days")) {
            assertEquals(old.path("calendar_context").path(key), current.path("calendar_context").path(key), key)
        }
        val schema = mapper.valueToTree<JsonNode>(CalendarFactsEvidenceProviderContract(mapper).responseSchema(f.snapshot))
        assertEquals(
            listOf(version),
            schema
                .path("properties")
                .path("calendar_facts_version")
                .path("enum")
                .toList()
                .map { it.asText() },
        )
        assertTrue(schema.path("required").any { it.asText() == "calendar_facts_version" })
        assertEquals(old.path("schema").path("\$defs").path("meeting_date"), schema.path("\$defs").path("meeting_date"))
    }

    @Test
    fun `CFP04 strict generation three requires its calendar marker and preserves the declared valid time`() {
        val f = Fixture()
        val parser = StrictScheduleDslStructuralResponseParser(mapper)
        assertTrue(parser.supportsResponseContractVersion(thirdResponse))
        val actual = strictParse(parser, f, f.document(), thirdResponse)
        assertEquals(
            LocalDate.parse("2028-02-29"),
            assertNotNull(
                actual.directionalApplications
                    .single { it.submissionVersionId == H.ir.first }
                    .resolvedScope.start,
            ).toLocalDate(),
        )
        for (marker in listOf(null, "server-calendar-facts-unknown", "")) {
            val document = f.document()
            if (marker == null) document.remove("calendar_facts_version") else document["calendar_facts_version"] = marker
            invalid { strictParse(parser, f, document, thirdResponse) }
        }
    }

    @Test
    fun `CFP05 archived generation two request bytes and response reading do not acquire the new calendar marker`() {
        val f = Fixture()
        val old = SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints = true)
        val frozen = old.requestBytes(f.capsule, MeetingMode.REMOTE)
        CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints = true).requestBytes(f.capsule, MeetingMode.REMOTE)
        assertContentEquals(frozen, old.requestBytes(f.capsule, MeetingMode.REMOTE))
        val request = mapper.readTree(frozen)
        assertEquals("schedule-dsl-evidence-response-2", request.path("response_contract_version").asText())
        assertEquals("server-calendar-facts-1", request.path("calendar_context").path("version").asText())
        assertFalse(request.has("calendar_facts_version"))
        val document = f.document().also { it.remove("calendar_facts_version") }
        val parser = StrictScheduleDslStructuralResponseParser(mapper)
        val actual = strictParse(parser, f, document, "schedule-dsl-evidence-response-2")
        assertEquals(
            LocalDate.parse("2028-02-29"),
            assertNotNull(
                actual.directionalApplications
                    .single { it.submissionVersionId == H.ir.first }
                    .resolvedScope.start,
            ).toLocalDate(),
        )
        invalid { strictParse(parser, f, f.document(), "schedule-dsl-evidence-response-2") }
    }

    @Test
    fun `CFP06 calendar facts neither correct a model weekday choice nor turn unknown time into whole day`() {
        val f = Fixture()
        val contract = CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints = true)
        // This intentionally wrong weekday declaration is preserved as declared: calendar metadata is not a semantic repair engine.
        val declared = parse(contract, f, f.document(date = "2028-02-28"))
        assertEquals(
            LocalDate.parse("2028-02-28"),
            assertNotNull(
                declared.directionalApplications
                    .single { it.submissionVersionId == H.ir.first }
                    .resolvedScope.start,
            ).toLocalDate(),
        )
        val unknown = parse(contract, f, f.document(unknown = true))
        assertTrue(unknown.directionalApplications.none { it.submissionVersionId == H.ir.first })
        assertNull(
            unknown.calculationProjection.claims.results
                .single { it.submissionVersionId == H.ir.first }
                .conditions
                .single()
                .scope,
        )
    }

    @Test
    fun `CFP07 both provider wires share the objective facts instructions and date schema under the matched third contract`() {
        val f = Fixture()
        val neutral =
            CalendarFactsEvidenceProviderContract(
                mapper,
                allowPlaceConstraints = true,
            ).requestBytes(f.capsule, MeetingMode.REMOTE)
        val openAi = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, AnalysisProvider.OPENAI, "gpt-6-luna"))
        val gemini = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        val first = mapper.readTree(openAi.path("input").asText())
        val second =
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            )
        assertEquals(first, second)
        assertEquals(version, first.path("calendar_facts_version").asText())
        assertEquals("meeting-date-scope-1", first.path("date_scope_version").asText())
        assertEquals(
            openAi.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertFalse(openAi.path("instructions").asText().contains(f.raw))
        val openAiSchema = openAi.path("text").path("format").path("schema")
        val geminiSchema = gemini.path("generationConfig").path("responseJsonSchema")
        assertEquals(openAiSchema.path("\$defs").path("meeting_date"), geminiSchema.path("\$defs").path("meeting_date"))
        assertEquals(
            openAiSchema.path("properties").path("calendar_facts_version"),
            geminiSchema.path("properties").path("calendar_facts_version"),
        )
        for (mutate in listOf<(MutableMap<String, Any?>) -> Unit>(
            { it.remove("calendar_facts_version") },
            { it["calendar_facts_version"] = "server-calendar-facts-unknown" },
            { it["date_scope_version"] = "meeting-date-scope-unknown" },
            { it["request_contract_version"] = "schedule-dsl-evidence-request-2" },
            { it["response_contract_version"] = "schedule-dsl-evidence-response-2" },
        )) {
            @Suppress("UNCHECKED_CAST")
            val changed = mapper.readValue(neutral, Map::class.java) as MutableMap<String, Any?>
            mutate(changed)
            for ((provider, model) in mapOf(AnalysisProvider.OPENAI to "gpt-6-luna", AnalysisProvider.GEMINI to "gemini-3.8-flash")) {
                assertFailsWith<IllegalArgumentException> {
                    ServerEvidenceWire.bytes(mapper, mapper.writeValueAsBytes(changed), provider, model)
                }
            }
        }
    }

    @Test
    fun `CFP08 factual calendar rows never grant another input explicit outside date authority`() {
        val f = Fixture()
        invalid { parse(CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints = true), f, f.document(date = "2027-01-08")) }
    }

    private fun day(
        date: String,
        weekday: String,
        occurrence: Int,
        last: Boolean,
        first: String,
        length: Int,
    ): Map<String, Any> =
        mapOf(
            "local_date" to date,
            "weekday" to weekday,
            "weekday_occurrence_in_month" to occurrence,
            "is_last_weekday_in_month" to last,
            "month_first_weekday" to first,
            "days_in_month" to length,
        )

    private fun strictParse(
        parser: StrictScheduleDslStructuralResponseParser,
        f: Fixture,
        document: Map<String, Any?>,
        version: String,
    ) = parser.parse(
        mapper.writeValueAsString(document),
        f.snapshot,
        version,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun parse(
        contract: CalendarFactsEvidenceProviderContract,
        f: Fixture,
        document: Map<String, Any?>,
    ) = contract.parseProviderResponse(
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
        start: String = "2028-02-26",
        end: String = "2028-03-04",
    ) {
        val raw = "🙂 모임 기간 중 화요일 하루 전체 가능."
        private val input =
            H.input(raw).let { old ->
                old.copy(
                    policy = N.policy,
                    meetingMode = MeetingMode.REMOTE,
                    searchStart = LocalDate.parse(start),
                    searchEnd = LocalDate.parse(end),
                    inputs = old.inputs.mapIndexed { index, item -> if (index == 1) item.copy(rawText = "2027-01-08 하루 전체 가능.") else item },
                )
            }
        val snapshot = H.snapshot(input)
        val capsule = H.capsule(input)
        private val catalog = ServerEvidenceCatalog.create(snapshot)

        fun document(
            date: String = "2028-02-29",
            unknown: Boolean = false,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "date_scope_version" to "meeting-date-scope-1",
                "calendar_facts_version" to "server-calendar-facts-2",
                "place_constraints_version" to SCHEDULE_PLACE_CONSTRAINT_VERSION,
                "results" to listOf(result(H.ir.first, date, unknown), result(H.ir.second, "2027-01-08", false)),
            )

        private fun result(
            inputId: UUID,
            date: String,
            unknown: Boolean,
        ): Map<String, Any?> {
            val proof =
                catalog.entries
                    .single {
                        it.inputId == inputId &&
                            it.kind == ServerEvidenceKind.CONTEXT &&
                            it.text == snapshot.sourceText(inputId, "original")
                    }.id
            return mapOf(
                "input_ref" to inputId.toString(),
                "conditions" to
                    listOf(
                        mapOf(
                            "id" to "available",
                            "effect" to "AVAILABLE",
                            "interpretation" to "EXPLICIT",
                            "temporal_resolution" to "NON_PRECISE",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "temporal" to
                                if (unknown) {
                                    mapOf("kind" to "UNKNOWN")
                                } else {
                                    mapOf(
                                        "kind" to "WHOLE_DAY",
                                        "local_date" to date,
                                        "date_evidence" to listOf(proof),
                                    )
                                },
                        ),
                    ),
                "program" to
                    mapOf(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to
                            mapOf(
                                "operator" to "CLAIM_REF",
                                "claim_id" to "available",
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
                "place_constraints" to emptyList<Any>(),
            )
        }
    }
}
