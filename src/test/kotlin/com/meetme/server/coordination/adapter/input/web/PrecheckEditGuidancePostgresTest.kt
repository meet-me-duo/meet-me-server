package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.precheck.domain.PrecheckChoice
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent guidance publication/privacy/quota contract. Real PostgreSQL, no provider calls. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class)
class PrecheckEditGuidancePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var processing: PrecheckProcessingService

    @Autowired private lateinit var checks: PrecheckRepository

    @Autowired private lateinit var time: DurableAnalysisInvocationPostgresTest.DurableClock

    private val original = "수요일은 수업이 끝난 뒤부터 가능해요."
    private val guidance = "수요일에 수업이 끝나는 시각을 적어 주세요."
    private val excerpt = "수업이 끝난 뒤"

    @BeforeEach
    fun resetTime() {
        time.now = java.time.Instant.parse("2026-10-07T00:00:00Z")
    }

    @Test
    fun `EGP01 EDIT guidance survives publication and reload privately without an AB question`() {
        val fixture = room()
        val queued = create(fixture, original)
        publish(queued, edit())
        val view = current(fixture, fixture.owner)
        assertEquals("NEEDS_EDIT", view.path("check").path("status").asText())
        assertEquals(guidance, view.path("check").path("edit_guidance").asText())
        assertTrue(view.path("check").path("question").isNull)
        assertTrue(view.path("accepted").isNull)
        assertFalse(view.path("submission_complete").asBoolean())
        assertEquals(original, view.path("candidate").path("raw_text").asText())
        val id = UUID.fromString(view.path("check").path("id").asText())
        val stored = assertNotNull(checks.find(id))
        val question = assertNotNull(stored.question)
        assertEquals(guidance, question.prompt)
        assertEquals(excerpt, question.sourceExcerpt)
        assertTrue(question.choices.isEmpty())
        val storedJson =
            mapper.readTree(
                assertNotNull(jdbc.queryForObject("SELECT question::text FROM precheck_checks WHERE id=?", String::class.java, id)),
            )
        assertEquals(setOf("id", "prompt", "sourceExcerpt", "choices"), storedJson.propertyNames().toSet())
        assertEquals(0, storedJson.path("choices").size())
        assertEquals(0, checks.questionCount(fixture.id, fixture.participant))
        assertEquals(view.path("check"), current(fixture, fixture.owner).path("check"))
        val hostView = current(fixture, fixture.host).toString()
        for (secret in listOf(original, guidance, excerpt)) assertFalse(hostView.contains(secret))
        val publicView =
            mvc
                .perform(get("/api/rooms/{code}", fixture.code))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        for (secret in listOf(original, guidance, excerpt)) assertFalse(publicView.contains(secret))
        val anonymous =
            mvc.perform(get("/api/rooms/{code}/submission/checks/current", fixture.code)).andExpect(status().is4xxClientError).andReturn()
        assertFalse(anonymous.response.contentAsString.contains(guidance))
    }

    @Test
    fun `EGP02 guidance does not authorize a forged choice and direct editing still requires recheck`() {
        val fixture = room()
        publish(create(fixture, original), edit())
        val asked = current(fixture, fixture.owner)
        val id = UUID.fromString(asked.path("check").path("id").asText())
        val hiddenId = assertNotNull(checks.find(id)?.question).id
        val body =
            mutableMapOf<String, Any?>(
                "request_id" to UUID.randomUUID(),
                "expected_revision" to asked.path("candidate").path("revision").asInt(),
                "expected_check_version" to asked.path("check").path("version").asLong(),
                "question_id" to hiddenId,
                "choice_id" to "A",
            )
        mvc
            .perform(answerRequest(fixture, id, body, fixture.owner))
            .andExpect(status().isConflict)
        val denied = mvc.perform(answerRequest(fixture, id, body, fixture.host)).andExpect(status().is4xxClientError).andReturn()
        assertFalse(denied.response.contentAsString.contains(guidance))
        body.remove("choice_id")
        body.remove("question_id")
        body["request_id"] = UUID.randomUUID()
        body["direct_text"] = "수요일 오후 6시부터 8시까지 가능해요."
        val revised =
            mapper.readTree(
                mvc
                    .perform(
                        answerRequest(fixture, id, body, fixture.owner),
                    ).andExpect(status().isAccepted)
                    .andReturn()
                    .response.contentAsString,
            )
        assertEquals("QUEUED", revised.path("check").path("status").asText())
        assertTrue(revised.path("check").path("edit_guidance").isNull)
        assertFalse(revised.path("submission_complete").asBoolean())
        assertTrue(revised.path("accepted").isNull)
        publish(revised, PrecheckProviderResult(PrecheckVerdict.CLEAR))
        val accepted = current(fixture, fixture.owner)
        assertEquals("CLEAR", accepted.path("check").path("status").asText())
        assertTrue(accepted.path("check").path("edit_guidance").isNull)
        assertTrue(accepted.path("check").path("question").isNull)
        assertTrue(accepted.path("submission_complete").asBoolean())
    }

    @Test
    fun `EGP03 stored guidance consumes no AB quota and the existing three question limit remains enforced`() {
        val fixture = room()
        repeat(2) { revision ->
            publish(create(fixture, original, revision), edit())
            assertNotNull(checks.find(UUID.fromString(current(fixture, fixture.owner).path("check").path("id").asText()))?.question)
        }
        assertEquals(0, checks.questionCount(fixture.id, fixture.participant))
        repeat(3) { index ->
            val raw = "금요일 ${index + 5}시부터 ${index + 7}시까지 가능해요."
            publish(create(fixture, raw, index + 2), choice(raw))
            val view = current(fixture, fixture.owner)
            assertEquals("NEEDS_CHOICE", view.path("check").path("status").asText())
            assertEquals(
                2,
                view
                    .path("check")
                    .path("question")
                    .path("options")
                    .size(),
            )
            assertTrue(view.path("check").path("edit_guidance").isNull)
            assertEquals(index + 1, checks.questionCount(fixture.id, fixture.participant))
        }
        val raw = "목요일 6시부터 8시까지 가능해요."
        publish(create(fixture, raw, 5), choice(raw))
        val limited = current(fixture, fixture.owner)
        assertEquals("NEEDS_EDIT", limited.path("check").path("status").asText())
        assertEquals("QUESTION_LIMIT_REACHED", limited.path("check").path("failure_code").asText())
        assertTrue(limited.path("check").path("question").isNull)
        assertTrue(
            limited
                .path("check")
                .path("edit_guidance")
                .asText()
                .isNotBlank(),
        )
        assertEquals(3, checks.questionCount(fixture.id, fixture.participant))
        assertFalse(limited.path("submission_complete").asBoolean())
    }

    @Test
    fun `EGP04 legacy EDIT uses bounded generic server guidance without fabricating a model diagnosis`() {
        val fixture = room()
        publish(create(fixture, "가능 시간을 확인해 주세요."), PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT))
        val view = current(fixture, fixture.owner)
        assertEquals("NEEDS_EDIT", view.path("check").path("status").asText())
        val fallback = view.path("check").path("edit_guidance").asText()
        assertTrue(fallback.isNotBlank())
        assertTrue(fallback.codePointCount(0, fallback.length) <= 160)
        assertFalse(fallback.contains("수업"))
        assertFalse(fallback.contains("퇴근"))
        assertTrue(view.path("check").path("question").isNull)
        assertTrue(assertNotNull(checks.find(UUID.fromString(view.path("check").path("id").asText()))).question == null)
        assertEquals(0, checks.questionCount(fixture.id, fixture.participant))
    }

    @Test
    fun `EGP05 stale EDIT completion cannot publish guidance onto a newer candidate`() {
        val fixture = room()
        val queued = create(fixture, original)
        val oldId = UUID.fromString(queued.path("check").path("id").asText())
        val claim = assertNotNull(processing.claim(oldId))
        val newer = create(fixture, "수요일 오후 6~8시 가능해요.", 1)
        assertEquals("SUPERSEDED", processing.publish(claim, edit(), null).name)
        assertEquals(newer.path("candidate"), current(fixture, fixture.owner).path("candidate"))
        assertTrue(current(fixture, fixture.owner).path("check").path("edit_guidance").isNull)
        assertTrue(checks.find(oldId)?.question == null)
        publish(newer, PrecheckProviderResult(PrecheckVerdict.CLEAR))
        val latest = current(fixture, fixture.owner)
        assertTrue(latest.path("submission_complete").asBoolean())
        assertFalse(latest.toString().contains(guidance))
    }

    @Test
    fun `EGP06 generated OpenAPI advertises nullable edit guidance with maximum 160 characters`() {
        val document =
            mapper.readTree(
                mvc
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val property =
            document
                .path("components")
                .path("schemas")
                .path("PrecheckStateResponse")
                .path("properties")
                .path("edit_guidance")
        assertFalse(property.isMissingNode)
        assertEquals(160, property.path("maxLength").asInt())
        assertTrue(
            property.path("nullable").asBoolean() ||
                property.path("type").values().any { it.asText() == "null" } ||
                property.path("anyOf").values().any { it.path("type").asText() == "null" },
        )
    }

    private fun edit() = PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT, guidance, excerpt)

    private fun choice(raw: String) =
        PrecheckProviderResult(
            PrecheckVerdict.NEEDS_CHOICE,
            "가능한 시각은 오전인가요, 오후인가요?",
            raw,
            listOf(PrecheckChoice("A", "오전", "오전 조건: $raw"), PrecheckChoice("B", "오후", "오후 조건: $raw")),
        )

    private fun publish(
        queued: JsonNode,
        result: PrecheckProviderResult,
    ) {
        val claim = assertNotNull(processing.claim(UUID.fromString(queued.path("check").path("id").asText())))
        processing.publish(claim, result, null)
    }

    private fun room(): Fixture {
        val host = accounts.newAccount("host")
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(host)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic guidance","meeting_mode":"REMOTE","manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
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
        val id = assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        val participant =
            assertNotNull(jdbc.queryForObject("SELECT id FROM participants WHERE room_id=? AND role='MEMBER'", UUID::class.java, id))
        return Fixture(id, code, host, owner, participant)
    }

    private fun create(
        fixture: Fixture,
        raw: String,
        revision: Int = 0,
    ): JsonNode =
        mapper.readTree(
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
                                    "expected_revision" to revision,
                                    "raw_text" to raw,
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andReturn()
                .response.contentAsString,
        )

    private fun current(
        fixture: Fixture,
        actor: Cookie,
    ): JsonNode =
        mapper.readTree(
            mvc
                .perform(get("/api/rooms/{code}/submission/checks/current", fixture.code).cookie(actor))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn()
                .response.contentAsString,
        )

    private fun answerRequest(
        fixture: Fixture,
        id: UUID,
        body: Map<String, Any?>,
        actor: Cookie,
    ) = post("/api/rooms/{code}/submission/checks/{id}/answer", fixture.code, id)
        .cookie(actor)
        .header("Origin", ORIGIN)
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))

    private data class Fixture(
        val id: UUID,
        val code: String,
        val host: Cookie,
        val owner: Cookie,
        val participant: UUID,
    )

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopFixture() {
            postgres.stop()
        }
    }
}
