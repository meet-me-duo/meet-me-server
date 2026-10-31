package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.coordination.adapter.output.integration.NaturalLanguageProviderContract
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SavedLunaReplayApplication::class)
class SavedLunaReplayTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Test
    fun `replayed response retains tentative in person time and persists unverified attendance after selection`() {
        val state = SavedLunaReplayState
        val cases = mutableListOf<Map<String, Any?>>()
        var completed = false
        try {
            for ((name, mode) in listOf(
                "in-person" to MeetingMode.IN_PERSON,
                "either-mode-counterfactual" to MeetingMode.EITHER,
            )) {
                val output = state.output.resolve(name)
                Files.createDirectory(output)
                val result =
                    BlindFlowHttpFlow(port, jdbc, tokens, json, output, answer = { source, question ->
                        assertEquals("p1", source)
                        assertEquals(state.question.path("text"), question.path("text"))
                        json.valueToTree<JsonNode>(mapOf("questionId" to question.path("id").asText(), "directText" to state.answer))
                    }, checkpoint = {}).run(state.sourceInput, mode)
                val binding = state.bindings.last()
                val request = state.requests.last()
                val roomId = checkNotNull(request.dispatchContext).execution.roomId
                val batch = checkNotNull(jdbc.queryForObject("SELECT id FROM submission_batches WHERE room_id=?", UUID::class.java, roomId))

                @Suppress("UNCHECKED_CAST")
                val mapping = binding["mapping"] as Map<String, String>
                val replay = state.json.readTree(state.remap(mapping))
                val text =
                    replay
                        .path("output")
                        .values()
                        .single {
                            it.path("type").asText() == "message"
                        }.path("content")
                        .values()
                        .single()
                        .path("text")
                        .asText()
                val expected = NaturalLanguageProviderContract(json).parseProviderResponse(text, request, strict = true)
                val stored = structured.findByBatch(SubmissionBatchId(batch))
                assertEquals(expected.associateBy { it.submissionVersionId }, stored.associateBy { it.submissionVersionId })
                assertEquals(3, stored.size)
                assertTrue(stored.all { it.rejectionCode == null })
                assertEquals(1, result["questionCount"])
                assertEquals(1, result["answerCount"])
                assertEquals(1, result["appliedSubmissions"])
                assertEquals(3, result["totalSubmissions"])
                assertEquals(2, result["unappliedCount"])
                assertEquals("PARTIAL", result["candidateQuality"])
                validatePlaceDiagnostics(output)
                val selections =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_selections WHERE room_id=?",
                        Long::class.java,
                        roomId,
                    )
                val record =
                    result +
                        mapOf(
                            "case" to name,
                            "counterfactualModeChange" to (mode == MeetingMode.EITHER),
                            "storedStructuredCount" to stored.size,
                            "persistedConditionFieldsEqualParserResult" to true,
                            "selectionRows" to selections,
                            "replayBinding" to binding,
                        )
                cases += record
                assertEquals("READY_WITH_WARNINGS", result["publicStatus"])
                assertEquals(if (mode == MeetingMode.IN_PERSON) 1 else 2, result["candidateCount"])
                assertTrue((result["recommendationCount"] as Int) > 0)
                assertEquals(true, result["selectionPersisted"])
                assertEquals("CONFIRMED", result["confirmedPublicStatus"])
                assertEquals(1L, selections)
                validateRecommendations(output, mode)
                validateSelection(output, roomId)
                val finalCandidate = json.readTree(Files.readAllBytes(output.resolve("host-result.json"))).path("candidate")
                assertEquals("IN_PERSON", finalCandidate.path("meeting_mode").asText())
                assertEquals(false, finalCandidate.path("attendance_confirmed").asBoolean())
                assertTrue(finalCandidate.path("summary").asText().contains("미확정"))
                val pendingCount =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM recommendation_variant_participants vp JOIN recommendation_selections s " +
                            "ON s.variant_id=vp.variant_id WHERE s.room_id=? AND vp.place_unverified",
                        Int::class.java,
                        roomId,
                    )
                assertEquals(2, pendingCount)
            }
            assertEquals(2, state.responseCount.get())
            assertEquals(2, state.primaryCount.get())
            assertEquals(0, state.headerAttempts.get())
            assertEquals(0, state.dnsAttempts.get())
            assertEquals(0, state.connectAttempts.get())
            assertEquals(0, state.placeAttempts.get())
            completed = true
        } finally {
            Files.writeString(
                state.output.resolve("replay-results.json"),
                json.writeValueAsString(
                    mapOf(
                        "completed" to completed,
                        "scope" to "${state.origin}_RESPONSE_REPLAY_NOT_REALTIME_PROVIDER_E2E",
                        "cases" to cases,
                        "fixtureResponseReplays" to state.responseCount.get(),
                        "sourceChecks" to state.sourceChecks.size,
                        "providerHeadersAttempted" to state.headerAttempts.get(),
                        "dnsAttempts" to state.dnsAttempts.get(),
                        "connectAttempts" to state.connectAttempts.get(),
                        "placeSearchAttempts" to state.placeAttempts.get(),
                        "actualProviderRequests" to 0,
                        "productionTimeoutChanged" to false,
                    ),
                ),
                StandardOpenOption.CREATE_NEW,
            )
        }
    }

    private fun validatePlaceDiagnostics(output: Path) {
        val diagnostics = json.readTree(Files.readAllBytes(output.resolve("host-unapplied.json"))).values().toList()
        val original = SavedLunaReplayState.originalInputs
        val travelRefs =
            SavedLunaReplayState
                .sourceResults()
                .values()
                .filter { result -> result.path("conditions").values().any { it.path("type").asText() == "TRAVEL_CONSTRAINT" } }
                .map { it.path("input_ref").asText() }
                .toSet()
        val expected = original.filter { it.inputRef in travelRefs }.associateBy { it.rawText }
        assertEquals(expected.keys, diagnostics.map { it.path("raw_text").asText() }.toSet())
        for (diagnostic in diagnostics) {
            assertEquals("UNSUPPORTED_TRAVEL_CONSTRAINT", diagnostic.path("reason").asText())
            assertEquals("PLACE", diagnostic.path("failure_scope").asText())
            val source = expected.getValue(diagnostic.path("raw_text").asText())
            val answer = diagnostic.path("finalized_answer").takeUnless { it.isNull || it.isMissingNode }?.asText()
            assertEquals(source.evidence?.supplement?.answerText, answer)
            assertTrue(diagnostic.path("question").isMissingNode)
            assertTrue(diagnostic.path("prompt").isMissingNode)
            assertTrue(diagnostic.path("input_evidence").isMissingNode)
        }
    }

    private fun validateRecommendations(
        output: Path,
        mode: MeetingMode,
    ) {
        val recommendations = json.readTree(Files.readAllBytes(output.resolve("host-recommendations.json")))
        val allThree = mutableListOf<Pair<Instant, Instant>>()
        for (option in recommendations.path("options").values()) {
            val range = option.path("time_range")
            val start = Instant.parse(range.path("start_at").asText())
            val end = Instant.parse(range.path("end_at").asText())
            for (variant in option.path("variants").values()) {
                val actualMode = variant.path("meeting_mode").asText()
                if (mode == MeetingMode.IN_PERSON) assertEquals("IN_PERSON", actualMode)
                if (actualMode == "IN_PERSON") {
                    assertEquals(false, variant.path("attendance_confirmed").asBoolean())
                    assertTrue(variant.path("summary").asText().contains("미확정"))
                } else {
                    assertEquals("REMOTE", actualMode)
                    assertEquals(true, variant.path("attendance_confirmed").asBoolean())
                }
                if (variant.path("attendance_count").asInt() == 3) allThree += start to end
            }
        }
        val expected =
            if (SavedLunaReplayState.origin == "SYNTHETIC") {
                // Independent oracle from the three synthetic participants' public time conditions.
                setOf(
                    Instant.parse("2026-12-03T10:30:00Z") to Instant.parse("2026-12-03T12:00:00Z"),
                    Instant.parse("2026-12-04T11:00:00Z") to Instant.parse("2026-12-04T12:30:00Z"),
                )
            } else {
                // Historical local-artifact oracle stays separate; default CI never opens those artifacts.
                setOf(
                    Instant.parse("2026-11-12T10:30:00Z") to Instant.parse("2026-11-12T12:00:00Z"),
                    Instant.parse("2026-11-13T11:00:00Z") to Instant.parse("2026-11-13T12:30:00Z"),
                )
            }
        assertEquals(expected, allThree.toSet())
    }

    private fun validateSelection(
        output: Path,
        roomId: UUID,
    ) {
        val selected = json.readTree(Files.readAllBytes(output.resolve("host-result.json"))).path("selection")
        val stored =
            jdbc.queryForMap(
                "SELECT coordination_run_id,option_id,variant_id,start_at,end_at FROM recommendation_selections WHERE room_id=?",
                roomId,
            )
        assertEquals(selected.path("analysis_id").asText(), stored["coordination_run_id"].toString())
        assertEquals(selected.path("option_id").asText(), stored["option_id"].toString())
        assertEquals(selected.path("variant_id").asText(), stored["variant_id"].toString())
        assertEquals(Instant.parse(selected.path("start_at").asText()), instant(checkNotNull(stored["start_at"])))
        assertEquals(Instant.parse(selected.path("end_at").asText()), instant(checkNotNull(stored["end_at"])))
    }

    private fun instant(value: Any): Instant =
        when (value) {
            is Timestamp -> value.toInstant()
            is OffsetDateTime -> value.toInstant()
            is Instant -> value
            else -> error("Unsupported timestamp representation")
        }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = SavedLunaReplayState.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}
