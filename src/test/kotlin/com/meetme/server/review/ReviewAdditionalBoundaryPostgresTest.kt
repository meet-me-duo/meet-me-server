package com.meetme.server.review

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Independently derived RV02/RV11/RV12 acceptance gaps; expectations come from the frozen policy. */
class ReviewAdditionalBoundaryPostgresTest : RecommendationPostgresFixture() {
    @MockitoBean private lateinit var reviewClock: Clock

    @MockitoSpyBean private lateinit var proofs: GuestOwnershipProofPort

    @Autowired private lateinit var currentAccount: CurrentAccountPort

    @BeforeEach
    fun prepareReviewSchemaAndClock() {
        if (jdbc.queryForObject("SELECT to_regclass('public.review_user_policies') IS NULL", Boolean::class.java) == true) {
            jdbc.execute(Files.readString(Path.of("docs/drafts/review-schema.sql")))
        }
        at(START)
    }

    @Test
    fun `RV02 prompt from another room stays private even for the same proven account`() {
        val first = confirmed()
        val prompt = claim(first.host).id()
        val second = confirmed()
        val sameOwnerInOtherRoom = asSameAccount(second.host, first.host)
        mvc.perform(submission(sameOwnerInOtherRoom, prompt, listOf(1))).andExpect(status().isNotFound)
        mvc.perform(closeRequest(sameOwnerInOtherRoom, prompt, true)).andExpect(status().isNotFound)
        assertEquals("PENDING", state(prompt))
        assertNull(hiddenUntil(first.host))
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV02 replacement only guest proof cannot recover or terminate an existing review prompt`() {
        val fixture = confirmedGuest()
        val request = UUID.randomUUID()
        val prompt = claim(fixture.member, request).id()
        val participant =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM participants WHERE room_id=? AND role='MEMBER'",
                    UUID::class.java,
                    fixture.roomId,
                ),
            )
        val room = MeetingRoomId(fixture.roomId)
        val replacement = GuestOwnershipProof(room, ParticipantId(participant), GuestProofLevel.REPLACE_ONLY)
        doReturn(replacement).`when`(proofs).resolve(room, fixture.member.credential, false)
        doReturn(replacement).`when`(proofs).resolve(room, fixture.member.credential, true)
        mvc.perform(claimRequest(fixture.member, request)).andExpect(status().isForbidden)
        mvc.perform(submission(fixture.member, prompt, listOf(1))).andExpect(status().isForbidden)
        mvc.perform(closeRequest(fixture.member, prompt, true)).andExpect(status().isForbidden)
        assertEquals("PENDING", state(prompt))
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV11 older pending prompt cannot bypass a later dissatisfaction cooldown`() {
        val first = confirmed()
        val firstPrompt = claim(first.host).id()
        val second = confirmed()
        val sameOwner = asSameAccount(second.host, first.host)
        val secondRequest = UUID.randomUUID()
        val secondPrompt = claim(sameOwner, secondRequest).id()
        submit(first.host, firstPrompt, listOf(2))
        val originalUntil = hiddenUntil(first.host)
        at(START.plusSeconds(86400))
        assertEquals("SUPPRESSED", claim(sameOwner, secondRequest)["state"])
        mvc.perform(submission(sameOwner, secondPrompt, listOf(1))).andExpect(status().isConflict)
        assertEquals(originalUntil, hiddenUntil(first.host))
        assertEquals("PENDING", state(secondPrompt))
        assertEquals(1, submittedCount())
    }

    @Test
    fun `RV12 checked guest close keeps its deadline after verified account claim without linking another guest`() {
        val first = confirmedGuest()
        close(first.member, claim(first.member).id(), true)
        at(START.plusSeconds(86400))
        val claimed = verifiedAccountClaim(first.member)
        assertEquals(START.plusSeconds(WEEK_SECONDS), hiddenUntil(claimed))
        val second = confirmed()
        assertEquals("SUPPRESSED", claim(asSameAccount(second.member, claimed))["state"])
        val unrelatedGuest = confirmedGuest()
        assertEquals("PENDING", claim(unrelatedGuest.member)["state"])
        at(START.plusSeconds(WEEK_SECONDS))
        val afterDeadline = confirmed()
        assertEquals("PENDING", claim(asSameAccount(afterDeadline.member, claimed))["state"])
        assertEquals(0, submittedCount())
    }

    @Test
    fun `RV12 verified account claim transfers a pending prompt and preserves the original request identity`() {
        val fixture = confirmedGuest()
        val request = UUID.randomUUID()
        val original = claim(fixture.member, request)
        val claimed = verifiedAccountClaim(fixture.member)
        assertEquals(original, claim(claimed, request))
        assertEquals("SUPPRESSED", claim(claimed)["state"])
        val receipt = submit(claimed, original.id(), listOf(1))
        assertEquals("SUBMITTED", receipt["state"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM review_prompts", Int::class.java))
        assertEquals(1, submittedCount())
    }

    private fun at(instant: Instant) {
        `when`(reviewClock.instant()).thenReturn(instant)
        `when`(reviewClock.zone).thenReturn(ZoneOffset.UTC)
    }

    private fun confirmed(): CompletedCorrectionFixture = recommendedRoom(days = 1).also(::confirm)

    private fun confirmedGuest(): CompletedCorrectionFixture =
        completedRoom(memberAccount = false).also {
            publish(it.sourceRun, days = 1)
            confirm(it)
        }

    private fun confirm(fixture: CompletedCorrectionFixture) {
        mvc.perform(selection(fixture, items(recommendations(fixture.host), "options").first())).andExpect(status().isOk)
    }

    private fun verifiedAccountClaim(guest: CorrectionSession): CorrectionSession {
        val cookie = accounts.newAccount("claimed member")
        val userId = UUID.fromString(cookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        `when`(currentAccount.currentAccount()).thenReturn(AccountPrincipal(userId, "claimed member"))
        try {
            mvc
                .perform(post("/api/rooms/${guest.code}/claim").cookie(guest.cookie()).header("Origin", ORIGIN))
                .andExpect(status().isOk)
        } finally {
            accounts.install()
        }
        return CorrectionSession(guest.code, cookie.value)
    }

    private fun asSameAccount(
        target: CorrectionSession,
        owner: CorrectionSession,
    ): CorrectionSession {
        val userId = UUID.fromString(owner.credential.removePrefix(AccountPrincipalTestFixture.PREFIX))
        val targetUserId = UUID.fromString(target.credential.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update(
            "UPDATE participants SET user_id=? WHERE room_id=(SELECT id FROM meeting_rooms WHERE invite_code=?) AND user_id=?",
            userId,
            target.code,
            targetUserId,
        )
        return CorrectionSession(target.code, owner.credential)
    }

    private fun claimRequest(
        actor: CorrectionSession,
        request: UUID,
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
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt/$prompt/submission")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("choices" to choices)))

    private fun submit(
        actor: CorrectionSession,
        prompt: UUID,
        choices: List<Int>,
    ): Map<String, Any?> =
        document(
            mvc
                .perform(submission(actor, prompt, choices))
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

    private fun hiddenUntil(actor: CorrectionSession): Instant? =
        jdbc
            .queryForObject(
                "SELECT hidden_until FROM review_user_policies WHERE user_id=?",
                java.sql.Timestamp::class.java,
                UUID.fromString(actor.credential.removePrefix(AccountPrincipalTestFixture.PREFIX)),
            )?.toInstant()

    private fun state(prompt: UUID): String =
        requireNotNull(jdbc.queryForObject("SELECT state FROM review_prompts WHERE id=?", String::class.java, prompt))

    private fun submittedCount(): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM review_prompts WHERE state='SUBMITTED'", Int::class.java))

    private fun Map<String, Any?>.id(): UUID = UUID.fromString(getValue("prompt_id").toString())

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
