package com.meetme.server.review

import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Independent RV database defenses and deliberate failing-write atomicity. */
class ReviewPersistenceDefensePostgresTest : RecommendationPostgresFixture() {
    @BeforeEach
    fun installDraftSchema() {
        if (jdbc.queryForObject("SELECT to_regclass('public.review_user_policies') IS NULL", Boolean::class.java) == true) {
            jdbc.execute(Files.readString(Path.of("docs/drafts/review-schema.sql")))
        }
    }

    @Test
    fun `RV13 database rejects duplicate selections incompatible satisfaction and unsubmitted free text`() {
        val fixture = recommendedRoom(days = 1)
        val participant = participant(fixture.host)
        val user = owner(participant)
        val key = "ACCOUNT:$user"
        jdbc.update("INSERT INTO review_user_policies(owner_key,user_id) VALUES (?,?)", key, user)
        for (invalid in listOf("{2,2}", "{1,5}", "{}", "{6}", "{2,NULL}")) {
            assertFailsWith<DataIntegrityViolationException> {
                jdbc.update(
                    "INSERT INTO review_prompts(id,participant_id,room_id,owner_key,request_id,state,choices,created_at,completed_at) " +
                        "VALUES (?,?,?,?,?,'SUBMITTED',?::integer[],now(),now())",
                    UUID.randomUUID(),
                    participant,
                    fixture.roomId,
                    key,
                    UUID.randomUUID(),
                    invalid,
                )
            }
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO review_prompts(id,participant_id,room_id,owner_key,request_id,state,other_text,created_at) " +
                    "VALUES (?,?,?,?,?,'PENDING',?,now())",
                UUID.randomUUID(),
                participant,
                fixture.roomId,
                key,
                UUID.randomUUID(),
                "unsubmitted private draft",
            )
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_prompts", Int::class.java))
    }

    @Test
    fun `RV13 owner key cannot impersonate another account and duplicate room participant is rejected`() {
        val fixture = recommendedRoom(days = 1)
        val participant = participant(fixture.host)
        val user = owner(participant)
        val key = "ACCOUNT:$user"
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO review_user_policies(owner_key,user_id) VALUES (?,?)", "ACCOUNT:${UUID.randomUUID()}", user)
        }
        jdbc.update("INSERT INTO review_user_policies(owner_key,user_id) VALUES (?,?)", key, user)
        val statement =
            "INSERT INTO review_prompts(id,participant_id,room_id,owner_key,request_id,state,created_at) " +
                "VALUES (?,?,?,?,?,'PENDING',now())"
        jdbc.update(statement, UUID.randomUUID(), participant, fixture.roomId, key, UUID.randomUUID())
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(statement, UUID.randomUUID(), participant, fixture.roomId, key, UUID.randomUUID())
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM review_prompts", Int::class.java))
    }

    @Test
    fun `RV13 failed policy write rolls submitted answer back and does not echo database private detail`() {
        val fixture = recommendedRoom(days = 1)
        mvc.perform(selection(fixture, items(recommendations(fixture.host), "options").first())).andExpect(status().isOk)
        val claim =
            document(
                mvc
                    .perform(
                        post("/api/rooms/${fixture.host.code}/review-prompt")
                            .cookie(fixture.host.cookie())
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"request_id":"${UUID.randomUUID()}"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val prompt = UUID.fromString(claim["prompt_id"].toString())
        jdbc.execute(
            """
            CREATE FUNCTION review_test_reject_policy() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'synthetic-review-storage-private-detail'; END; ${'$'}${'$'};
            CREATE TRIGGER review_test_reject_policy BEFORE UPDATE ON review_user_policies
            FOR EACH ROW EXECUTE FUNCTION review_test_reject_policy();
            """.trimIndent(),
        )
        try {
            val response =
                mvc
                    .perform(
                        post("/api/rooms/${fixture.host.code}/review-prompt/$prompt/submission")
                            .cookie(fixture.host.cookie())
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"choices":[5],"other_text":"synthetic-private-feedback"}"""),
                    ).andExpect(status().isServiceUnavailable)
                    .andReturn()
                    .response
            assertFalse(response.contentAsString.contains("synthetic-private"))
            assertFalse(response.contentAsString.contains("synthetic-review-storage-private-detail"))
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM review_prompts WHERE id=?", String::class.java, prompt))
            assertNull(jdbc.queryForObject("SELECT other_text FROM review_prompts WHERE id=?", String::class.java, prompt))
            assertNull(
                jdbc.queryForObject(
                    "SELECT hidden_until FROM review_user_policies WHERE user_id=?",
                    java.sql.Timestamp::class.java,
                    owner(participant(fixture.host)),
                ),
            )
        } finally {
            jdbc.execute("DROP TRIGGER review_test_reject_policy ON review_user_policies; DROP FUNCTION review_test_reject_policy()")
        }
    }

    private fun participant(actor: CorrectionSession): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM participants WHERE room_id=(SELECT id FROM meeting_rooms WHERE invite_code=?) AND role='HOST'",
                UUID::class.java,
                actor.code,
            ),
        )

    private fun owner(participant: UUID): UUID =
        requireNotNull(jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, participant))

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
