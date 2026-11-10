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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Public wire/reader composition: frozen contract identity selects the new date guard, not source wording. */
class SearchScopedEvidenceWireRouteTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val firstVersion = "schedule-dsl-evidence-response-1"
    private val secondVersion = "schedule-dsl-evidence-response-2"

    @Test
    fun `SDW01 shared wire accepts matched generation two contract pairs and rejects cross generation mixtures`() {
        val f = Fixture()
        val scoped = SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints = true).requestBytes(f.capsule, MeetingMode.REMOTE)
        for ((provider, model) in providers()) {
            val actual = mapper.readTree(ServerEvidenceWire.bytes(mapper, scoped, provider, model))
            val data = data(actual, provider)
            assertEquals("schedule-dsl-evidence-request-2", data.path("request_contract_version").asText())
            assertEquals(secondVersion, data.path("response_contract_version").asText())
            assertEquals("meeting-date-scope-1", data.path("date_scope_version").asText())
            for ((request, response) in listOf(
                "schedule-dsl-evidence-request-2" to firstVersion,
                "schedule-dsl-evidence-request-1" to secondVersion,
            )) {
                @Suppress("UNCHECKED_CAST")
                val mixed = mapper.readValue(scoped, Map::class.java) as MutableMap<String, Any?>
                mixed["request_contract_version"] = request
                mixed["response_contract_version"] = response
                assertFailsWith<IllegalArgumentException> {
                    ServerEvidenceWire.bytes(mapper, mapper.writeValueAsBytes(mixed), provider, model)
                }
            }
            val old = ServerEvidenceProviderContract(mapper, allowPlaceConstraints = true).requestBytes(f.capsule, MeetingMode.REMOTE)
            val archived = data(mapper.readTree(ServerEvidenceWire.bytes(mapper, old, provider, model)), provider)
            assertEquals("schedule-dsl-evidence-request-1", archived.path("request_contract_version").asText())
            assertEquals(firstVersion, archived.path("response_contract_version").asText())
            assertFalse(archived.has("date_scope_version"))
        }
    }

    @Test
    fun `SDW02 strict reader selects guarded dates only for the new frozen response version and rejects marker mismatch`() {
        val f = Fixture()
        val parser = StrictScheduleDslStructuralResponseParser(mapper)
        assertTrue(parser.supportsResponseContractVersion(firstVersion))
        assertTrue(parser.supportsResponseContractVersion(secondVersion))
        val valid = parse(parser, f, f.document("2038-04-10"), secondVersion)
        assertEquals(
            LocalDate.parse("2038-04-10"),
            assertNotNull(
                valid.directionalApplications
                    .single { it.submissionVersionId == H.ir.first }
                    .resolvedScope.start,
            ).toLocalDate(),
        )
        invalid { parse(parser, f, f.document("2027-01-08"), secondVersion) }
        invalid { parse(parser, f, f.document("2038-04-10").also { it.remove("date_scope_version") }, secondVersion) }
        invalid { parse(parser, f, f.document("2038-04-10"), firstVersion) }
        val oldDocument = f.document("2027-01-08").also { it.remove("date_scope_version") }
        val archived = parse(parser, f, oldDocument, firstVersion)
        assertEquals(
            LocalDate.parse("2027-01-08"),
            assertNotNull(
                archived.directionalApplications
                    .single { it.submissionVersionId == H.ir.first }
                    .resolvedScope.start,
            ).toLocalDate(),
        )
    }

    @Test
    fun `SDW03 both effective finite schemas keep the shared calendar definition and every scalar date reference`() {
        val f = Fixture()
        val neutral = SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints = true).requestBytes(f.capsule, MeetingMode.REMOTE)
        for ((provider, model) in providers()) {
            val wire = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, provider, model))
            val schema =
                if (provider == AnalysisProvider.OPENAI) {
                    wire.path("text").path("format").path("schema")
                } else {
                    wire.path("generationConfig").path("responseJsonSchema")
                }
            val definitions = schema.path("\$defs").properties().associate { it.key to it.value }
            val dates = assertNotNull(definitions["meeting_date"])
            assertEquals(
                setOf("2027-01-08", "2038-04-10", "2038-04-11"),
                dates
                    .path("enum")
                    .toList()
                    .map { it.asText() }
                    .toSet(),
            )
            var dateReferences = 0

            fun references(node: JsonNode): Set<String> {
                val found = mutableSetOf<String>()
                if (node.isObject) {
                    node.path("properties").properties().forEach { (name, field) ->
                        if (name == "local_date") {
                            dateReferences++
                            assertEquals("#/\$defs/meeting_date", field.path("\$ref").asText())
                        }
                    }
                    if (node.has("\$ref")) {
                        val target = node.path("\$ref").asText()
                        assertTrue(target.startsWith("#/\$defs/"))
                        found += target.removePrefix("#/\$defs/")
                    }
                    node.properties().forEach { (_, child) -> found += references(child) }
                } else if (node.isArray) {
                    node.forEach { found += references(it) }
                }
                return found
            }
            val graph = definitions.mapValues { (_, node) -> references(node) }
            references(schema)
            assertTrue(dateReferences > 0)

            fun visit(
                name: String,
                path: Set<String>,
            ) {
                assertTrue(name in definitions, "Missing definition $name")
                assertFalse(name in path, "Recursive definition $name")
                graph.getValue(name).forEach { visit(it, path + name) }
            }
            definitions.keys.forEach { visit(it, emptySet()) }
        }
    }

    private fun parse(
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

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    private fun providers() = mapOf(AnalysisProvider.OPENAI to "gpt-6-luna", AnalysisProvider.GEMINI to "gemini-3.8-flash")

    private fun data(
        wire: JsonNode,
        provider: AnalysisProvider,
    ) = mapper.readTree(
        if (provider ==
            AnalysisProvider.OPENAI
        ) {
            wire.path("input").asText()
        } else {
            wire
                .path("contents")
                .path(0)
                .path("parts")
                .path(0)
                .path("text")
                .asText()
        },
    )

    private class Fixture {
        private val input =
            H.input("🙂 모임 검색 기간 첫날 하루 전체 가능.").let { old ->
                old.copy(
                    policy = N.policy,
                    meetingMode = MeetingMode.REMOTE,
                    searchStart = LocalDate.parse("2038-04-10"),
                    searchEnd = LocalDate.parse("2038-04-12"),
                    inputs =
                        old.inputs.mapIndexed { index, item ->
                            if (index == 1) item.copy(rawText = "2027-01-08 하루 전체 가능.") else item
                        },
                )
            }
        val snapshot = H.snapshot(input)
        val capsule = H.capsule(input)
        private val catalog = ServerEvidenceCatalog.create(snapshot)

        fun document(date: String): MutableMap<String, Any?> =
            linkedMapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to ServerEvidenceCatalog.VERSION,
                "date_scope_version" to "meeting-date-scope-1",
                "place_constraints_version" to SCHEDULE_PLACE_CONSTRAINT_VERSION,
                "results" to listOf(result(H.ir.first, date), result(H.ir.second, "2027-01-08")),
            )

        private fun result(
            inputId: UUID,
            date: String,
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
                            "id" to "availability",
                            "effect" to "AVAILABLE",
                            "interpretation" to "EXPLICIT",
                            "temporal_resolution" to "NON_PRECISE",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "temporal" to mapOf("kind" to "WHOLE_DAY", "local_date" to date, "date_evidence" to listOf(proof)),
                        ),
                    ),
                "program" to
                    mapOf(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to
                            mapOf(
                                "operator" to "CLAIM_REF",
                                "claim_id" to "availability",
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
