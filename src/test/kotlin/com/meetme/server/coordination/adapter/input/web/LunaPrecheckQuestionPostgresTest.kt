package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.adapter.output.provider.LunaPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.shared.adapter.output.aihttp.LunaPrecheckLoopback
import com.meetme.server.shared.adapter.output.aihttp.lunaPersonalEnvelope
import com.meetme.server.shared.adapter.output.aihttp.lunaPersonalQuestion
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Luna response parsing, worker and PostgreSQL; private synthetic inputs, no live provider or price authority. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class, LunaPrecheckQuestionPostgresTest.LocalProvider::class)
class LunaPrecheckQuestionPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var time: DurableAnalysisInvocationPostgresTest.DurableClock

    private val original = "내일 8시 온라인 가능하고 강남역은 제외해요."
    private val chosen = "2026년 10월 8일 오전 8시 온라인 가능하고 강남역은 제외해요."

    @BeforeEach
    fun resetLocalProvider() {
        time.now = NOW
        responses.clear()
        endpoint.requests.clear()
    }

    @Test
    fun `Luna question is stored privately and an explicit answer is rechecked before becoming accepted`() {
        val fixture = collectingRoom()
        responses += lunaPersonalEnvelope(lunaPersonalQuestion(original, "2026년 10월 8일"))
        responses += lunaPersonalEnvelope()
        submit(fixture, original)

        assertTrue(worker.runOne())

        val asked = current(fixture, fixture.owner)
        assertEquals("NEEDS_CHOICE", asked.path("check").path("status").asText())
        assertEquals(original, asked.path("candidate").path("raw_text").asText())
        assertTrue(asked.path("accepted").isNull)
        assertFalse(asked.path("submission_complete").asBoolean())
        val question = asked.path("check").path("question")
        assertEquals(2, question.path("options").size())
        assertEquals(
            chosen,
            question
                .path("options")
                .first { it.path("id").asText() == "A" }
                .path("proposed_text")
                .asText(),
        )
        val storedQuestion =
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT question::text FROM precheck_checks WHERE id=?",
                    String::class.java,
                    UUID.fromString(asked.path("check").path("id").asText()),
                ),
            )
        assertTrue(storedQuestion.contains("가능한 8시는 오전인가요, 오후인가요?"))
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("coordination_runs"))
        val hostView = current(fixture, fixture.host).toString()
        for (privateText in listOf(original, chosen, "가능한 8시는 오전인가요, 오후인가요?")) assertFalse(hostView.contains(privateText))

        val initialCandidate =
            assertNotNull(jdbc.queryForObject("SELECT id FROM precheck_candidates WHERE room_id=?", UUID::class.java, fixture.room))
        val referenceAt = asked.path("candidate").path("interpretation_reference_at").asText()
        time.now = NOW.plus(Duration.ofDays(1))
        val answerKey = UUID.randomUUID()
        val answered = answer(fixture, asked, answerKey)
        assertEquals("QUEUED", answered.path("check").path("status").asText())
        assertEquals(chosen, answered.path("candidate").path("raw_text").asText())
        assertEquals(referenceAt, answered.path("candidate").path("interpretation_reference_at").asText())
        assertEquals(time.now, Instant.parse(answered.path("candidate").path("created_at").asText()))
        assertFalse(answered.path("submission_complete").asBoolean())
        assertEquals(0, count("submission_versions"))
        assertEquals(1, endpoint.requests.size, "Answer acceptance must only queue a new check")
        val replay = answer(fixture, asked, answerKey)
        assertEquals(answered.path("candidate"), replay.path("candidate"))
        assertEquals(2, count("precheck_candidates"))
        assertEquals(1, endpoint.requests.size)

        assertTrue(worker.runOne())

        val accepted = current(fixture, fixture.owner)
        assertEquals("CLEAR", accepted.path("check").path("status").asText())
        assertTrue(accepted.path("submission_complete").asBoolean())
        assertEquals(chosen, accepted.path("accepted").path("raw_text").asText())
        assertEquals(referenceAt, accepted.path("accepted").path("interpretation_reference_at").asText())
        assertEquals(1, count("submission_versions"))
        assertEquals(
            original,
            jdbc.queryForObject("SELECT raw_text FROM precheck_candidates WHERE id=?", String::class.java, initialCandidate),
        )
        val derived =
            jdbc.queryForMap(
                "SELECT source_candidate_id,source_question_id,selected_choice_id FROM precheck_candidates WHERE source_candidate_id=?",
                initialCandidate,
            )
        assertEquals(initialCandidate, derived["source_candidate_id"])
        assertEquals(UUID.fromString(question.path("id").asText()), derived["source_question_id"])
        assertEquals("A", derived["selected_choice_id"])
        assertEquals(2, endpoint.requests.size)
        assertTrue(endpoint.requests.all { it.method == "POST" && it.path == "/v1/responses" })
        val secondInput = mapper.readTree(mapper.readTree(endpoint.requests.last().body)["input"].asText())
        assertEquals(chosen, secondInput["raw_text"].asText())
        assertEquals(referenceAt, secondInput["reference_at"].asText())
        assertEquals(0, count("coordination_runs"), "Personal question/answer checks cannot replace final whole-room analysis")
    }

    @Test
    fun `invalid Luna completion preserves candidate without creating a question answer or accepted submission`() {
        val fixture = collectingRoom()
        responses +=
            lunaPersonalEnvelope(lunaPersonalQuestion(original, "2026년 10월 8일"))
                .replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")
        submit(fixture, original)

        assertTrue(worker.runOne())

        val failed = current(fixture, fixture.owner)
        assertEquals("FAILED", failed.path("check").path("status").asText())
        assertEquals("INVALID_RESPONSE", failed.path("check").path("failure_code").asText())
        assertTrue(failed.path("check").path("question").isNull)
        assertTrue(failed.path("accepted").isNull)
        assertFalse(failed.path("submission_complete").asBoolean())
        assertEquals(original, failed.path("candidate").path("raw_text").asText())
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("coordination_runs"))
        assertEquals(1, endpoint.requests.size)
    }

    private fun collectingRoom(): Fixture {
        val host = accounts.newAccount("host")
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(host)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic Luna question","meeting_mode":"REMOTE","manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        val owner = accounts.newAccount("owner")
        mvc
            .perform(
                post("/api/rooms/{code}/participants", code)
                    .cookie(owner)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"owner"}"""),
            ).andExpect(status().isCreated)
        return Fixture(
            assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code)),
            code,
            host,
            owner,
        )
    }

    private fun submit(
        fixture: Fixture,
        raw: String,
    ) {
        mvc
            .perform(
                post("/api/rooms/{code}/submission/checks", fixture.code)
                    .cookie(fixture.owner)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            mapOf(
                                "request_id" to UUID.randomUUID(),
                                "expected_revision" to 0,
                                "raw_text" to raw,
                                "locale" to "ko-KR",
                            ),
                        ),
                    ),
            ).andExpect(status().isAccepted)
    }

    private fun current(
        fixture: Fixture,
        actor: Cookie,
    ): JsonNode =
        mapper.readTree(
            mvc
                .perform(
                    get("/api/rooms/{code}/submission/checks/current", fixture.code).cookie(actor),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun answer(
        fixture: Fixture,
        asked: JsonNode,
        requestId: UUID,
    ): JsonNode =
        mapper.readTree(
            mvc
                .perform(
                    post("/api/rooms/{code}/submission/checks/{check}/answer", fixture.code, asked.path("check").path("id").asText())
                        .cookie(fixture.owner)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "request_id" to requestId,
                                    "expected_revision" to asked.path("candidate").path("revision").asInt(),
                                    "expected_check_version" to asked.path("check").path("version").asLong(),
                                    "question_id" to
                                        asked
                                            .path("check")
                                            .path("question")
                                            .path("id")
                                            .asText(),
                                    "choice_id" to "A",
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andReturn()
                .response.contentAsString,
        )

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private data class Fixture(
        val room: UUID,
        val code: String,
        val host: Cookie,
        val owner: Cookie,
    )

    @TestConfiguration
    class LocalProvider {
        @Bean @Primary
        fun actualLocalLunaProvider(mapper: ObjectMapper): PreSubmissionCheckProvider =
            LunaPreSubmissionCheckProvider.runtime(
                OpenAiProperties(apiKey = "synthetic-luna-personal-key"),
                mapper,
                PrecheckProviderRuntimeProperties(true),
                endpoint = endpoint.uri,
            )
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val responses = ConcurrentLinkedQueue<String>()
        private val endpoint =
            LunaPrecheckLoopback { _, _ ->
                200 to
                    checkNotNull(responses.poll()) { "No synthetic provider response queued" }
            }
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopFixture() {
            endpoint.close()
            postgres.stop()
        }
    }
}
