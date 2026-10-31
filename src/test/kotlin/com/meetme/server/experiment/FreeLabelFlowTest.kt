package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.shared.adapter.output.aihttp.toProviderEvidence
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.domain.InputEvidence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
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
import java.nio.file.StandardOpenOption
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EX-LABEL/ONE-QUESTION/FREEZE/HOST: a connected application contract, not model semantic evaluation. */
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FreeLabelFlowApplication::class)
@DisabledIfEnvironmentVariable(named = "MEETME_BLIND20_MODE", matches = ".+")
class FreeLabelFlowTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Test
    fun `one clarification and unresolved supplement survive whole-room analysis exclusion and host selection`() {
        val state = FreeLabelFlowState
        var completed = false
        var flow: Map<String, Any?> = emptyMap()
        try {
            val input =
                json.valueToTree<JsonNode>(
                    mapOf(
                        "title" to "무료 합성 라벨 연결 계약",
                        "startDate" to "2026-11-12",
                        "endDate" to "2026-11-13",
                        "participants" to
                            listOf(
                                mapOf("id" to "question", "rawInput" to state.QUESTION_RAW),
                                mapOf("id" to "luna-clear", "rawInput" to state.LUNA_CLEAR_RAW),
                                mapOf("id" to "direct-clear", "rawInput" to state.DIRECT_CLEAR_RAW),
                            ),
                    ),
                )
            flow =
                BlindFlowHttpFlow(
                    port,
                    jdbc,
                    tokens,
                    json,
                    state.output,
                    answer = { sourceId, question ->
                        assertEquals("question", sourceId)
                        assertEquals(state.PROMPT, question.path("text").asText())
                        assertEquals(state.EXCERPT, question.path("source_excerpt").asText())
                        assertEquals("TEXT_ONLY", question.path("answer_mode").asText())
                        assertTrue(question.path("options").isEmpty)
                        json.valueToTree<JsonNode>(mapOf("questionId" to question.path("id").asText(), "directText" to state.ANSWER))
                    },
                    checkpoint = {},
                ).run(input, MeetingMode.IN_PERSON)

            assertEquals(true, flow["analysisCompleted"])
            assertEquals(1, flow["questionCount"])
            assertEquals(1, flow["answerCount"])
            assertEquals(2, flow["appliedSubmissions"])
            assertEquals(3, flow["totalSubmissions"])
            assertEquals(1, flow["unappliedCount"])
            assertTrue((flow["recommendationCount"] as Int) in 1..3)
            assertEquals(true, flow["selectionPersisted"])
            assertEquals("CONFIRMED", flow["confirmedPublicStatus"])
            validatePersonalStates()
            validateFinalEvidence()
            validateHostAndPersistence()
            validateSourceAndMockBoundary()
            completed = true
        } finally {
            // All exported payloads are newly authored synthetic data. No key, header or environment is read.
            Files.writeString(
                state.output.resolve("free-contract-results.json"),
                json.writeValueAsString(
                    mapOf(
                        "mode" to "FREE_SYNTHETIC_APPLICATION_CONTRACT",
                        "completed" to completed,
                        "flow" to flow,
                        "syntheticPrimary503Attempts" to state.primaryCalls.get(),
                        "syntheticHttpResponses" to state.captured.groupingBy { it.first.name }.eachCount(),
                        "originalJdbcSourceChecks" to state.sourceChecks.size,
                        "sourceDenials" to state.sourceDenials.get(),
                        "providerHeaderAttempts" to state.headerAttempts.get(),
                        "providerDnsAttempts" to state.dnsAttempts.get(),
                        "providerConnectionAttempts" to state.connectionAttempts.get(),
                        "placeSearchAttempts" to state.placeSearchAttempts.get(),
                        "actualProviderRequests" to 0,
                        "modelUsageObserved" to false,
                        "billedCostObserved" to false,
                        "limitations" to
                            listOf(
                                "Jev and both Luna response bytes are deterministic test doubles; actual adapters and router parse them.",
                                "Gemini503 is a parser test double; its HTTP client and normal success are not exercised.",
                                "Source authorization and immutable request binding remain original JDBC code; " +
                                    "monetary budget enforcement is off.",
                                "A direct-text question is covered; A/B semantic quality and real-model latency, usage and blind holdout " +
                                    "remain unverified.",
                            ),
                    ),
                ),
                StandardOpenOption.CREATE_NEW,
            )
        }
    }

    private fun file(name: String): JsonNode = json.readTree(Files.readString(FreeLabelFlowState.output.resolve(name)))

    private fun validatePersonalStates() {
        val state = FreeLabelFlowState
        val question = file("participant-question.json").path("current")
        assertTrue(question.path("submission_complete").asBoolean())
        assertEquals("RECEIVED", question.path("check").path("status").asText())
        assertFalse(question.path("check").path("question").isObject)
        val accepted = question.path("accepted")
        assertEquals(state.QUESTION_RAW, accepted.path("raw_text").asText())
        assertEquals("UNRESOLVED", accepted.path("evidence").path("interpretation").asText())
        assertEquals("CLASSIFIER_MODEL_UNCERTAIN", accepted.path("evidence").path("unresolved_reason").asText())
        assertEquals(
            state.ANSWER,
            accepted
                .path("evidence")
                .path("supplement")
                .path("answer_text")
                .asText(),
        )
        assertTrue(question.path("candidate").path("revision").asInt() > accepted.path("evidence").path("original_revision").asInt())
        for (id in listOf("luna-clear", "direct-clear")) {
            val current = file("participant-$id.json").path("current")
            assertEquals("CLEAR", current.path("check").path("status").asText())
            assertEquals(
                "CONFIRMED",
                current
                    .path("accepted")
                    .path("evidence")
                    .path("interpretation")
                    .asText(),
            )
            assertFalse(
                current
                    .path("accepted")
                    .path("evidence")
                    .path("supplement")
                    .isObject,
            )
        }
        val classified = state.classifications.toList()
        assertEquals(4, classified.size)
        assertEquals(
            listOf(
                AmbiguityAssessment.NEEDS_REVIEW,
                AmbiguityAssessment.INDETERMINATE,
                AmbiguityAssessment.INDETERMINATE,
                AmbiguityAssessment.CLEAR,
            ),
            classified.map {
                it.semanticAssessment
            },
        )
        assertTrue(classified.all { it.confidence == 0.4 })
        assertEquals(classified.map { it.semanticAssessment }, classified.map { it.assessment })
    }

    private fun validateFinalEvidence() {
        val state = FreeLabelFlowState
        val inputs = state.finalInputs()
        val unresolved = inputs.single { it.path("raw_text").asText() == state.QUESTION_RAW }
        val accepted = file("participant-question.json").path("current").path("accepted")
        // Byte-independent JSON equality detects loss of source scope, either time anchor, answer or unresolved observation.
        assertEquals(accepted.path("evidence"), unresolved.path("evidence"))
        val supplement = unresolved.path("evidence").path("supplement")
        assertEquals(state.EXCERPT, supplement.path("source_excerpt").asText())
        assertEquals(state.PROMPT, supplement.path("prompt").asText())
        assertEquals(supplement.path("answered_at"), supplement.path("reference_at"))
        val recheck =
            state.captured
                .filter { it.first == AiPhysicalOperation.PRECHECK_CLASSIFICATION }
                .map { it.second.path("state") }
                .single { it.path("evidence").path("supplement").isObject }
        assertEquals(state.QUESTION_RAW, recheck.path("raw_text").asText())
        assertEquals(supplement, recheck.path("evidence").path("supplement"))
        assertEquals(accepted.path("evidence").path("reference_at"), recheck.path("evidence").path("reference_at"))
        val frozen =
            jdbc.queryForList(
                "SELECT v.id,v.raw_text,v.input_evidence::text AS evidence FROM submission_batch_items b " +
                    "JOIN submission_versions v ON v.id=b.submission_version_id",
            )
        assertEquals(3, frozen.size)
        assertEquals(inputs.map { it.path("input_ref").asText() }.toSet(), frozen.map { it["id"].toString() }.toSet())
        for (row in frozen) {
            val sent = inputs.single { it.path("input_ref").asText() == row["id"].toString() }
            assertEquals(row["raw_text"], sent.path("raw_text").asText())
            val persisted = json.readValue(row["evidence"] as String, InputEvidence::class.java)
            assertEquals(json.valueToTree<JsonNode>(persisted.toProviderEvidence()), sent.path("evidence"))
        }
    }

    private fun validateHostAndPersistence() {
        val state = FreeLabelFlowState
        for (name in listOf("guest-unapplied.json", "anonymous-unapplied.json")) {
            val denied = file(name)
            assertEquals("ACCOUNT_REQUIRED", denied.path("code").asText())
            val encoded = json.writeValueAsString(denied)
            for (privateText in listOf(state.QUESTION_RAW, state.LUNA_CLEAR_RAW, state.DIRECT_CLEAR_RAW, state.ANSWER, state.PROMPT)) {
                assertFalse(encoded.contains(privateText))
            }
        }
        val unapplied = file("host-unapplied.json")
        assertEquals(1, unapplied.size())
        val rejected = unapplied.path(0)
        assertEquals("Synthetic question", rejected.path("participant_display_name").asText())
        assertEquals(state.QUESTION_RAW, rejected.path("raw_text").asText())
        assertEquals(state.ANSWER, rejected.path("finalized_answer").asText())
        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", rejected.path("reason").asText())
        assertEquals("TIME", rejected.path("failure_scope").asText())
        assertEquals(
            setOf("participant_display_name", "raw_text", "reason", "finalized_answer", "failure_scope"),
            rejected.propertyNames().toSet(),
        )
        assertFalse(json.writeValueAsString(unapplied).contains(state.PROMPT))
        assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM structured_submission_results", Long::class.java))
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM structured_submission_results WHERE rejection_code='AMBIGUOUS_TIME_CONSTRAINT'",
                Long::class.java,
            ),
        )
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT count(*) FROM structured_submission_results WHERE rejection_code IS NULL", Long::class.java),
        )
        val recommendations = file("host-recommendations.json")
        val options = recommendations.path("options").values().toList()
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { option -> option.path("variants").values().all { it.path("attendance_count").asInt() == 2 } })
        assertTrue(options.all { option -> option.path("variants").values().all { it.path("meeting_mode").asText() == "IN_PERSON" } })
        val selected = file("host-result.json").path("selection")
        val stored = jdbc.queryForMap("SELECT coordination_run_id,option_id,variant_id,start_at,end_at FROM recommendation_selections")
        assertEquals(selected.path("analysis_id").asText(), stored["coordination_run_id"].toString())
        assertEquals(selected.path("option_id").asText(), stored["option_id"].toString())
        assertEquals(selected.path("variant_id").asText(), stored["variant_id"].toString())
        assertEquals(Instant.parse(selected.path("start_at").asText()), instant(checkNotNull(stored["start_at"])))
        assertEquals(Instant.parse(selected.path("end_at").asText()), instant(checkNotNull(stored["end_at"])))
        assertEquals(Instant.parse("2026-11-12T09:00:00Z"), instant(checkNotNull(stored["start_at"])))
        assertEquals(Instant.parse("2026-11-12T11:00:00Z"), instant(checkNotNull(stored["end_at"])))
    }

    private fun instant(value: Any): Instant =
        when (value) {
            is Timestamp -> value.toInstant()
            is OffsetDateTime -> value.toInstant()
            is Instant -> value
            else -> error("Unsupported timestamp representation")
        }

    private fun validateSourceAndMockBoundary() {
        val state = FreeLabelFlowState
        assertEquals(1, state.primaryCalls.get())
        val expected =
            mapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to 4,
                AiPhysicalOperation.PRECHECK_GENERATION to 2,
                AiPhysicalOperation.FINAL_FALLBACK to 1,
            )
        assertEquals(expected, state.captured.groupingBy { it.first }.eachCount())
        assertEquals(
            expected,
            state.sourceChecks
                .distinct()
                .groupingBy { it.calls.single().operation }
                .eachCount(),
        )
        assertEquals(0, state.sourceDenials.get())
        assertEquals(0, state.headerAttempts.get())
        assertEquals(0, state.dnsAttempts.get())
        assertEquals(0, state.connectionAttempts.get())
        assertEquals(0, state.placeSearchAttempts.get())
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = FreeLabelFlowState.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}
