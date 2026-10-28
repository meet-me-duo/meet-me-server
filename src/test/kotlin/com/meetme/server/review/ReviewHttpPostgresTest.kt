package com.meetme.server.review

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent RV public-contract tests. Synthetic PostgreSQL/HTTP only; no external model calls. */
class ReviewHttpPostgresTest : RecommendationPostgresFixture() {
    @MockitoBean private lateinit var reviewClock: Clock

    @Autowired private lateinit var currentAccount: CurrentAccountPort

    @BeforeEach
    fun prepareReviewSchemaAndClock() {
        if (jdbc.queryForObject("SELECT to_regclass('public.review_user_policies') IS NULL", Boolean::class.java) == true) {
            jdbc.execute(Files.readString(Path.of("docs/drafts/review-schema.sql")))
        }
        at(START)
    }

    @Test
    fun `RV01 HOST and MEMBER each claim once only after confirmation`() {
        val fixture = recommendedRoom(days = 1)
        mvc.perform(claimRequest(fixture.host)).andExpect(status().isConflict)
        assertEquals(0, count("review_prompts"))
        confirm(fixture)
        val host = claim(fixture.host)
        val member = claim(fixture.member)
        assertEquals("PENDING", host["state"])
        assertEquals("PENDING", member["state"])
        assertNotNull(host["prompt_id"])
        assertNotNull(member["prompt_id"])
        assertTrue(host["prompt_id"] != member["prompt_id"])
        assertEquals(2, count("review_prompts"))
    }

    @Test
    fun `RV02 missing proof outsider and other participants prompt cannot mutate`() {
        val fixture = confirmed()
        val prompt = claim(fixture.host).id()
        mvc
            .perform(
                post("/api/rooms/${fixture.host.code}/review-prompt")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"request_id":"${UUID.randomUUID()}"}"""),
            ).andExpect(status().isUnauthorized)
        val outsider = CorrectionSession(fixture.host.code, accounts.newAccount("outsider").value)
        mvc.perform(claimRequest(outsider)).andExpect(status().isForbidden)
        mvc.perform(submission(fixture.member, prompt, listOf(1))).andExpect(status().isNotFound)
        mvc.perform(closeRequest(fixture.member, prompt, true)).andExpect(status().isNotFound)
        assertEquals("PENDING", state(prompt))
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV03 invalid selections never consume pending prompt and OTHER alone empty is valid`() {
        val fixture = confirmed()
        val prompt = claim(fixture.member).id()
        for (choices in listOf(emptyList(), listOf(0), listOf(6), listOf(1, 2), listOf(2, 2))) {
            mvc.perform(submission(fixture.member, prompt, choices)).andExpect(status().isBadRequest)
            assertEquals("PENDING", state(prompt))
        }
        mvc
            .perform(submission(fixture.member, prompt, listOf(2), "draft must not be accepted without OTHER"))
            .andExpect(status().isBadRequest)
        val response = submit(fixture.member, prompt, listOf(5), "")
        assertEquals("SUBMITTED", response["state"])
        assertEquals(1, submittedCount())
        assertNull(jdbc.queryForObject("SELECT other_text FROM review_prompts WHERE id = ?", String::class.java, prompt))
    }

    @Test
    fun `RV04 all dissatisfaction choices and optional plain text are private and bounded by code points`() {
        val fixture = confirmed()
        val prompt = claim(fixture.member).id()
        val marker = "<script>synthetic-sensitive-review</script>"
        val tooLong = "😀".repeat(2001)
        mvc.perform(submission(fixture.member, prompt, listOf(5), tooLong)).andExpect(status().isBadRequest)
        mvc.perform(submission(fixture.member, prompt, listOf(5), "invalid\u0000text")).andExpect(status().isBadRequest)
        val receipt = submit(fixture.member, prompt, listOf(2, 3, 4, 5), marker)
        assertEquals(setOf("prompt_id", "state"), receipt.keys)
        assertFalse(receipt.toString().contains(marker))
        assertEquals(marker, jdbc.queryForObject("SELECT other_text FROM review_prompts WHERE id = ?", String::class.java, prompt))
        val result = result(fixture.host)
        assertFalse(result.toString().contains(marker))
        val retry = claim(fixture.member)
        assertEquals("SUPPRESSED", retry["state"])
        assertNull(retry["prompt_id"])
        assertFalse(retry.toString().contains(marker))
    }

    @Test
    fun `RV05 same request recovers pending but fresh request never repeats same room`() {
        val fixture = confirmed()
        val request = UUID.randomUUID()
        val first = claim(fixture.member, request)
        assertEquals(first, claim(fixture.member, request))
        assertEquals("SUPPRESSED", claim(fixture.member)["state"])
        assertEquals(first, claim(fixture.member, request))
        assertEquals(1, count("review_prompts"))
    }

    @Test
    fun `RV06 satisfied permanently stops same user across rooms and leaves another user eligible`() {
        val first = confirmed()
        submit(first.host, claim(first.host).id(), listOf(1))
        at(START.plusSeconds(365L * 86400))
        val second = confirmed()
        val sameHost = asSameAccount(second.host, first.host)
        assertEquals("SUPPRESSED", claim(sameHost)["state"])
        assertEquals("PENDING", claim(second.member)["state"])
        assertEquals(1, submittedCount())
    }

    @Test
    fun `RV07 dissatisfied seven day boundary and suppressed room never wakes after cooldown`() {
        val first = confirmed()
        val firstPrompt = claim(first.host).id()
        submit(first.host, firstPrompt, listOf(2, 4))
        val second = confirmed()
        val sameHost = asSameAccount(second.host, first.host)
        at(START.plusSeconds(WEEK_SECONDS - 1))
        val suppressedRequest = UUID.randomUUID()
        assertEquals("SUPPRESSED", claim(sameHost, suppressedRequest)["state"])
        at(START.plusSeconds(WEEK_SECONDS))
        assertEquals("SUPPRESSED", claim(sameHost, suppressedRequest)["state"])
        assertEquals("SUPPRESSED", claim(first.host)["state"])
        val third = confirmed()
        assertEquals("PENDING", claim(asSameAccount(third.host, first.host))["state"])
    }

    @Test
    fun `RV08 ordinary close is unsubmitted and next room can ask immediately`() {
        val first = confirmed()
        val prompt = claim(first.host).id()
        close(first.host, prompt, false)
        assertEquals("CLOSED", state(prompt))
        assertEquals(0, submittedCount())
        assertNull(jdbc.queryForObject("SELECT other_text FROM review_prompts WHERE id = ?", String::class.java, prompt))
        assertEquals("SUPPRESSED", claim(first.host)["state"])
        val second = confirmed()
        assertEquals("PENDING", claim(asSameAccount(second.host, first.host))["state"])
    }

    @Test
    fun `RV09 checked close hides whole user for seven days and retry never extends deadline`() {
        val first = confirmed()
        val prompt = claim(first.host).id()
        val receipt = close(first.host, prompt, true)
        val originalUntil = hiddenUntil(first.host)
        assertEquals(START.plusSeconds(WEEK_SECONDS), originalUntil)
        at(START.plusSeconds(2 * 86400))
        assertEquals(receipt, close(first.host, prompt, true))
        assertEquals(originalUntil, hiddenUntil(first.host))
        val second = confirmed()
        assertEquals("SUPPRESSED", claim(asSameAccount(second.host, first.host))["state"])
        at(START.plusSeconds(WEEK_SECONDS))
        val third = confirmed()
        assertEquals("PENDING", claim(asSameAccount(third.host, first.host))["state"])
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV10 semantic replay remains successful without changing original timestamp and changed answer conflicts`() {
        val fixture = confirmed()
        val prompt = claim(fixture.member).id()
        val first = submit(fixture.member, prompt, listOf(2, 5), "  ")
        val deadline = hiddenUntil(fixture.member)
        at(START.plusSeconds(86400))
        assertEquals(first, submit(fixture.member, prompt, listOf(5, 2), null))
        assertEquals(deadline, hiddenUntil(fixture.member))
        mvc.perform(submission(fixture.member, prompt, listOf(1))).andExpect(status().isConflict)
        assertEquals("SUBMITTED", close(fixture.member, prompt, true)["state"])
        assertEquals(deadline, hiddenUntil(fixture.member))
        assertEquals(1, submittedCount())
    }

    @Test
    fun `RV10 closed prompt cannot become a submission and checked replay cannot change ordinary close`() {
        val fixture = confirmed()
        val prompt = claim(fixture.member).id()
        close(fixture.member, prompt, false)
        mvc.perform(submission(fixture.member, prompt, listOf(1))).andExpect(status().isConflict)
        close(fixture.member, prompt, true)
        assertEquals("CLOSED", state(prompt))
        assertNull(hiddenUntil(fixture.member))
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV11 pending other-room prompt cannot bypass satisfaction and checked late close preserves optout`() {
        val first = confirmed()
        val firstPrompt = claim(first.host).id()
        val second = confirmed()
        val sameHost = asSameAccount(second.host, first.host)
        val secondRequest = UUID.randomUUID()
        val secondPrompt = claim(sameHost, secondRequest).id()
        submit(first.host, firstPrompt, listOf(1))
        assertEquals("SUPPRESSED", claim(sameHost, secondRequest)["state"])
        mvc.perform(submission(sameHost, secondPrompt, listOf(2))).andExpect(status().isConflict)
        close(sameHost, secondPrompt, true)
        assertTrue(satisfied(first.host))
        assertNull(hiddenUntil(first.host))
        assertEquals(1, submittedCount())
    }

    @Test
    fun `RV12 guest satisfaction survives verified claim into account without linking unrelated guest`() {
        val first = completedRoom(memberAccount = false)
        publish(first.sourceRun, days = 1)
        confirm(first)
        submit(first.member, claim(first.member).id(), listOf(1))
        val accountCookie = accounts.newAccount("claimed member")
        val userId = UUID.fromString(accountCookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        `when`(currentAccount.currentAccount()).thenReturn(AccountPrincipal(userId, "claimed member"))
        mvc
            .perform(post("/api/rooms/${first.member.code}/claim").cookie(first.member.cookie()).header("Origin", ORIGIN))
            .andExpect(status().isOk)
        accounts.install()
        val claimed = CorrectionSession(first.member.code, accountCookie.value)
        assertEquals("SUPPRESSED", claim(claimed)["state"])
        val next = confirmed()
        val sameMember = asSameAccount(next.member, claimed)
        assertEquals("SUPPRESSED", claim(sameMember)["state"])
        assertEquals("PENDING", claim(next.host)["state"])
    }

    @Test
    fun `RV05 concurrent claims create one prompt and recover only winning request`() {
        val fixture = confirmed()
        val requests = listOf(UUID.randomUUID(), UUID.randomUUID())
        val responses = race(requests.map { request -> { claim(fixture.member, request) } })
        assertEquals(1, responses.count { it["state"] == "PENDING" })
        assertEquals(1, responses.count { it["state"] == "SUPPRESSED" })
        assertEquals(1, count("review_prompts"))
        requests.zip(responses).forEach { (request, response) -> assertEquals(response, claim(fixture.member, request)) }
    }

    @Test
    fun `RV10 concurrent close and submit preserve first terminal without duplicate answer`() {
        val fixture = confirmed()
        val prompt = claim(fixture.member).id()
        val responses =
            race(
                listOf(
                    {
                        mvc
                            .perform(submission(fixture.member, prompt, listOf(2)))
                            .andReturn()
                            .response.status
                    },
                    {
                        mvc
                            .perform(closeRequest(fixture.member, prompt, true))
                            .andReturn()
                            .response.status
                    },
                ),
            )
        assertTrue(responses.all { it == 200 || it == 409 })
        assertEquals(200, responses[1])
        val final = state(prompt)
        assertTrue(final in setOf("CLOSED", "SUBMITTED"))
        assertEquals(if (final == "SUBMITTED") 1 else 0, submittedCount())
        assertEquals(1, count("review_prompts"))
    }

    @Test
    fun `RV14 native schema contains review endpoints with no retrieval of stored answers`() {
        val spec =
            document(
                mvc
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val paths = child(spec, "paths")
        val reviewPaths = paths.filterKeys { it.contains("review-prompt") }
        assertEquals(3, reviewPaths.size)
        for (value in reviewPaths.values) {
            @Suppress("UNCHECKED_CAST")
            val operations = value as Map<String, Any?>
            assertEquals(setOf("post"), operations.keys)
        }
    }

    private fun at(instant: Instant) {
        `when`(reviewClock.instant()).thenReturn(instant)
        `when`(reviewClock.zone).thenReturn(ZoneOffset.UTC)
    }

    private fun confirmed(): CompletedCorrectionFixture = recommendedRoom(days = 1).also(::confirm)

    private fun confirm(fixture: CompletedCorrectionFixture) {
        mvc.perform(selection(fixture, items(recommendations(fixture.host), "options").first())).andExpect(status().isOk)
    }

    private fun claimRequest(
        actor: CorrectionSession,
        request: UUID = UUID.randomUUID(),
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("request_id" to request)))

    private fun claim(
        actor: CorrectionSession,
        request: UUID = UUID.randomUUID(),
    ): Map<String, Any?> =
        document(
            mvc
                .perform(claimRequest(actor, request))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun submission(
        actor: CorrectionSession,
        prompt: UUID,
        choices: List<Int>,
        text: String? = null,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt/$prompt/submission")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("choices" to choices, "other_text" to text)))

    private fun submit(
        actor: CorrectionSession,
        prompt: UUID,
        choices: List<Int>,
        text: String? = null,
    ): Map<String, Any?> =
        document(
            mvc
                .perform(submission(actor, prompt, choices, text))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun closeRequest(
        actor: CorrectionSession,
        prompt: UUID,
        hide: Boolean,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt/$prompt/close")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("hide_for_week" to hide)))

    private fun close(
        actor: CorrectionSession,
        prompt: UUID,
        hide: Boolean,
    ): Map<String, Any?> =
        document(
            mvc
                .perform(closeRequest(actor, prompt, hide))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun asSameAccount(
        target: CorrectionSession,
        owner: CorrectionSession,
    ): CorrectionSession {
        val userId = UUID.fromString(owner.credential.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update(
            "UPDATE participants SET user_id=? WHERE room_id=(SELECT id FROM meeting_rooms WHERE invite_code=?) AND role=?",
            userId,
            target.code,
            role(target),
        )
        return CorrectionSession(target.code, owner.credential)
    }

    private fun role(actor: CorrectionSession): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT role FROM participants WHERE room_id=(SELECT id FROM meeting_rooms WHERE invite_code=?) AND user_id=?",
                String::class.java,
                actor.code,
                UUID.fromString(actor.credential.removePrefix(AccountPrincipalTestFixture.PREFIX)),
            ),
        )

    private fun hiddenUntil(actor: CorrectionSession): Instant? =
        jdbc
            .queryForObject(
                "SELECT hidden_until FROM review_user_policies WHERE user_id=?",
                java.sql.Timestamp::class.java,
                UUID.fromString(actor.credential.removePrefix(AccountPrincipalTestFixture.PREFIX)),
            )?.toInstant()

    private fun satisfied(actor: CorrectionSession): Boolean =
        jdbc.queryForObject(
            "SELECT satisfied_at IS NOT NULL FROM review_user_policies WHERE user_id=?",
            Boolean::class.java,
            UUID.fromString(actor.credential.removePrefix(AccountPrincipalTestFixture.PREFIX)),
        ) == true

    private fun Map<String, Any?>.id(): UUID = UUID.fromString(requireNotNull(this["prompt_id"]).toString())

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun submittedCount(): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM review_prompts WHERE state='SUBMITTED'", Int::class.java))

    private fun state(prompt: UUID): String =
        requireNotNull(jdbc.queryForObject("SELECT state FROM review_prompts WHERE id=?", String::class.java, prompt))

    private fun <T> race(actions: List<() -> T>): List<T> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val start = CyclicBarrier(actions.size)
        return try {
            actions
                .map { action ->
                    executor.submit(
                        Callable {
                            start.await(10, TimeUnit.SECONDS)
                            action()
                        },
                    )
                }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val START = Instant.parse("2026-10-28T12:00:00Z")
        private const val WEEK_SECONDS = 7L * 86400
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
