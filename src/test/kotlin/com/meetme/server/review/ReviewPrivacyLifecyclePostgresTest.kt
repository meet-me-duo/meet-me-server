package com.meetme.server.review

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RV13/RV15: only synthetic values; HTTP, storage rollback, retention, and actual application log boundaries. */
@ExtendWith(OutputCaptureExtension::class)
class ReviewPrivacyLifecyclePostgresTest : RecommendationPostgresFixture() {
    @MockitoBean private lateinit var reviewClock: Clock

    @Autowired private lateinit var currentAccount: CurrentAccountPort

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @BeforeEach
    fun prepareReviewSchemaAndClock() {
        if (jdbc.queryForObject("SELECT to_regclass('public.review_user_policies') IS NULL", Boolean::class.java) == true) {
            jdbc.execute(Files.readString(Path.of("docs/drafts/review-schema.sql")))
        }
        `when`(reviewClock.instant()).thenReturn(START)
        `when`(reviewClock.zone).thenReturn(ZoneOffset.UTC)
    }

    @Test
    fun `RV15 successful claim submit and close are no-store acknowledgments without answer text`(output: CapturedOutput) {
        val fixture = confirmed()
        val claimed =
            mvc
                .perform(claimRequest(fixture.host))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertAck(claimed)
        val prompt = document(claimed).id()
        val submitted =
            mvc
                .perform(submission(fixture.host, prompt, listOf(5), PRIVATE_TEXT))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertAck(submitted)
        val memberPrompt = claim(fixture.member).id()
        val closed =
            mvc
                .perform(closeRequest(fixture.member, memberPrompt))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertAck(closed)
        assertPrivateLogs(output, fixture)
    }

    @Test
    fun `RV15 malformed unauthorized forbidden absent and conflicting requests remain private no-store errors`(output: CapturedOutput) {
        val fixture = confirmed()
        val prompt = claim(fixture.host).id()
        val invalid = mvc.perform(submission(fixture.host, prompt, emptyList(), PRIVATE_TEXT)).andReturn().response
        assertProblem(invalid, 400, "VALIDATION_FAILED")
        val malformed =
            mvc
                .perform(
                    post("/api/rooms/${fixture.host.code}/review-prompt/not-a-uuid/close")
                        .cookie(fixture.host.cookie())
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"hide_for_week":false}"""),
                ).andReturn()
                .response
        assertProblem(malformed, 400)
        val missing =
            mvc
                .perform(
                    post("/api/rooms/${fixture.host.code}/review-prompt")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"request_id":"${UUID.randomUUID()}"}"""),
                ).andReturn()
                .response
        assertProblem(missing, 401, "GUEST_SESSION_REQUIRED")
        val outsider = CorrectionSession(fixture.host.code, accounts.newAccount("unrelated account").value)
        assertProblem(mvc.perform(claimRequest(outsider)).andReturn().response, 403, "PARTICIPANT_REQUIRED")
        assertProblem(
            mvc.perform(submission(fixture.member, prompt, listOf(5), PRIVATE_TEXT)).andReturn().response,
            404,
            "REVIEW_PROMPT_NOT_FOUND",
        )
        val unconfirmed = recommendedRoom(days = 1)
        assertProblem(mvc.perform(claimRequest(unconfirmed.host)).andReturn().response, 409, "REVIEW_NOT_CONFIRMED")
        assertPrivateLogs(output, fixture)
    }

    @Test
    fun `RV13 RV15 failed policy write rolls back private answer and exposes only sanitized 503`(output: CapturedOutput) {
        val fixture = confirmed()
        val prompt = claim(fixture.host).id()
        jdbc.execute(
            """
            CREATE FUNCTION review_privacy_reject_policy() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'synthetic-review-policy-private-error'; END; ${'$'}${'$'};
            CREATE TRIGGER review_privacy_reject_policy BEFORE UPDATE ON review_user_policies
            FOR EACH ROW EXECUTE FUNCTION review_privacy_reject_policy();
            """.trimIndent(),
        )
        try {
            val response = mvc.perform(submission(fixture.host, prompt, listOf(5), PRIVATE_TEXT)).andReturn().response
            assertProblem(response, 503, "REVIEW_STORAGE_UNAVAILABLE")
            assertFalse(response.contentAsString.contains("synthetic-review-policy-private-error"))
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM review_prompts WHERE id=?", String::class.java, prompt))
            assertNull(jdbc.queryForObject("SELECT other_text FROM review_prompts WHERE id=?", String::class.java, prompt))
            assertPrivateLogs(output, fixture)
            assertFalse(output.all.contains("synthetic-review-policy-private-error"))
        } finally {
            jdbc.execute("DROP TRIGGER review_privacy_reject_policy ON review_user_policies; DROP FUNCTION review_privacy_reject_policy()")
        }
    }

    @Test
    fun `RV12 RV13 verified guest claim failure rolls back participant policy and prompt ownership without private error leakage`(
        output: CapturedOutput,
    ) {
        val fixture = completedRoom(memberAccount = false)
        publish(fixture.sourceRun, days = 1)
        confirm(fixture)
        val prompt = claim(fixture.member).id()
        mvc.perform(submission(fixture.member, prompt, listOf(5), PRIVATE_TEXT)).andExpect(status().isOk)
        val participantBefore =
            jdbc.queryForMap(
                "SELECT guest_session_id,user_id FROM participants WHERE room_id=? AND role='MEMBER'",
                fixture.roomId,
            )
        val ownerBefore = jdbc.queryForObject("SELECT owner_key FROM review_prompts WHERE id=?", String::class.java, prompt)
        val policyBefore = jdbc.queryForMap("SELECT * FROM review_user_policies WHERE owner_key=?", ownerBefore)
        val account = accounts.newAccount("verified claimant")
        val userId = UUID.fromString(account.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.execute(
            """
            CREATE FUNCTION review_privacy_reject_transfer() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'synthetic-review-claim-private-error'; END; ${'$'}${'$'};
            CREATE TRIGGER review_privacy_reject_transfer BEFORE UPDATE OF owner_key ON review_prompts
            FOR EACH ROW EXECUTE FUNCTION review_privacy_reject_transfer();
            """.trimIndent(),
        )
        try {
            `when`(currentAccount.currentAccount()).thenReturn(AccountPrincipal(userId, "verified claimant"))
            val attempted =
                runCatching {
                    mvc
                        .perform(post("/api/rooms/${fixture.member.code}/claim").cookie(fixture.member.cookie()).header("Origin", ORIGIN))
                        .andReturn()
                        .response
                }
            // The shared account-claim API has no fixed review-storage status contract.
            assertTrue(attempted.isFailure || attempted.getOrThrow().status in 500..599)
            val publicFailure = attempted.exceptionOrNull()?.stackTraceToString() ?: attempted.getOrThrow().contentAsString
            assertFalse(publicFailure.contains(PRIVATE_TEXT))
            assertFalse(publicFailure.contains("synthetic-review-claim-private-error"))
            assertFalse(publicFailure.contains(fixture.member.credential))
            assertEquals(
                participantBefore,
                jdbc.queryForMap(
                    "SELECT guest_session_id,user_id FROM participants WHERE room_id=? AND role='MEMBER'",
                    fixture.roomId,
                ),
            )
            assertEquals(ownerBefore, jdbc.queryForObject("SELECT owner_key FROM review_prompts WHERE id=?", String::class.java, prompt))
            assertEquals(policyBefore, jdbc.queryForMap("SELECT * FROM review_user_policies WHERE owner_key=?", ownerBefore))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_user_policies WHERE user_id=?", Int::class.java, userId))
            assertPrivateLogs(output, fixture)
            assertFalse(output.all.contains("synthetic-review-claim-private-error"))
        } finally {
            jdbc.execute("DROP TRIGGER review_privacy_reject_transfer ON review_prompts; DROP FUNCTION review_privacy_reject_transfer()")
            accounts.install()
        }
        try {
            `when`(currentAccount.currentAccount()).thenReturn(AccountPrincipal(userId, "verified claimant"))
            mvc
                .perform(post("/api/rooms/${fixture.member.code}/claim").cookie(fixture.member.cookie()).header("Origin", ORIGIN))
                .andExpect(status().isOk)
            assertEquals(
                userId,
                jdbc.queryForObject(
                    "SELECT user_id FROM participants WHERE room_id=? AND role='MEMBER'",
                    UUID::class.java,
                    fixture.roomId,
                ),
            )
        } finally {
            accounts.install()
        }
    }

    @Test
    fun `RV13 room cleanup removes review answers while preserving user optout until account cleanup`() {
        val fixture = confirmed()
        val prompt = claim(fixture.host).id()
        mvc.perform(submission(fixture.host, prompt, listOf(1), null)).andExpect(status().isOk)
        val userId = UUID.fromString(fixture.host.credential.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update(
            "UPDATE meeting_rooms SET created_at=?,closed_at=? WHERE id=?",
            START.minus(32, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            START.minus(31, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            fixture.roomId,
        )
        assertEquals(1, retention.deleteExpiredRooms(START.minus(30, ChronoUnit.DAYS), 100, START))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_prompts WHERE room_id=?", Int::class.java, fixture.roomId))
        assertNotNull(
            jdbc.queryForObject("SELECT satisfied_at FROM review_user_policies WHERE user_id=?", java.sql.Timestamp::class.java, userId),
        )
        jdbc.update("DELETE FROM account_users WHERE id=?", userId)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_user_policies WHERE user_id=?", Int::class.java, userId))
    }

    private fun assertAck(response: MockHttpServletResponse) {
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(setOf("prompt_id", "state"), document(response).keys)
        assertFalse(response.contentAsString.contains(PRIVATE_TEXT))
    }

    private fun assertProblem(
        response: MockHttpServletResponse,
        expectedStatus: Int,
        expectedCode: String? = null,
    ) {
        assertEquals(expectedStatus, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(response.contentType?.startsWith("application/problem+json") == true)
        val body = document(response)
        assertEquals(expectedStatus, (body["status"] as? Number)?.toInt())
        assertNotNull(body["code"])
        expectedCode?.let { assertEquals(it, body["code"]) }
        assertFalse(response.contentAsString.contains(PRIVATE_TEXT))
        // RFC problem.instance can retain the existing request URI; it is not feedback text.
    }

    private fun assertPrivateLogs(
        output: CapturedOutput,
        fixture: CompletedCorrectionFixture,
    ) {
        for (value in listOf(PRIVATE_TEXT, fixture.host.code, fixture.host.credential, fixture.member.credential)) {
            assertFalse(output.all.contains(value), "Application logs must omit synthetic private data")
        }
    }

    private fun confirmed(): CompletedCorrectionFixture = recommendedRoom(days = 1).also(::confirm)

    private fun confirm(fixture: CompletedCorrectionFixture) {
        mvc.perform(selection(fixture, items(recommendations(fixture.host), "options").first())).andExpect(status().isOk)
    }

    private fun claimRequest(actor: CorrectionSession): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"request_id":"${UUID.randomUUID()}"}""")

    private fun claim(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(claimRequest(actor))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun submission(
        actor: CorrectionSession,
        prompt: UUID,
        choices: List<Int>,
        text: String?,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt/$prompt/submission")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("choices" to choices, "other_text" to text)))

    private fun closeRequest(
        actor: CorrectionSession,
        prompt: UUID,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt/$prompt/close")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"hide_for_week":false}""")

    private fun Map<String, Any?>.id(): UUID = UUID.fromString(getValue("prompt_id").toString())

    companion object {
        private const val PRIVATE_TEXT = "synthetic-review-private-feedback@example.test"
        private val START = Instant.parse("2026-10-28T12:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
