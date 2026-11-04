package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Submission progress contract; FR-109B, FR-197A and the agreed current submitted-head semantics. */
class RoomSubmittedParticipantCountWebIntegrationTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var currentAccount: CurrentAccountPort

    @Test
    fun `SP01 room creation and public detail return integer zero without inventing an expected participant goal`() {
        val created = create()
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 0)
        val response = document(created.response)
        assertCount(response, 0)
        assertEquals(null, response["expected_participants"])
        assertEquals(null, response["submission_deadline"])
        assertEquals(true, response["manual_only"])
        assertEquals(1, rows("participants"))
        assertEquals(0, rows("submission_heads"))
    }

    @Test
    fun `SP02 submitted host member and guest each count once across repeated updates and joins`() {
        val created = create()
        mvc.perform(save(created.host, "private submitted host input")).andExpect(status().isOk)
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 1)
        val member = join(created.host.code, accounts.newAccount("count member"), expectedCount = 1)
        mvc.perform(save(member, "private submitted member input")).andExpect(status().isOk)
        count(mvc.perform(get("/api/rooms/${member.code}")), 2)
        val guest = join(created.host.code, expectedCount = 2)
        mvc.perform(save(guest, "private submitted guest input")).andExpect(status().isOk)
        count(mvc.perform(get("/api/rooms/${guest.code}")), 3)
        mvc.perform(save(guest, "private updated input")).andExpect(status().isOk)
        mvc.perform(save(guest, "private updated input")).andExpect(status().isOk)
        for (actor in listOf(created.host, member, guest)) {
            count(mvc.perform(joinRequest(actor.code).cookie(actor.cookie()).content("{}")), 3)
        }
        assertEquals(3, rows("participants"))
        assertEquals(3, rows("submission_heads"))
        assertEquals(4, rows("submission_versions"))
        assertCount(room(guest), 3)
    }

    @Test
    fun `SP03 joining a draft precheck and a head without a latest version do not count as submitted`() {
        val created = create()
        val guest = join(created.host.code)
        val roomId = roomId(created.host.code)
        val guestId = participantId(guest)
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V1' WHERE id=?", roomId)
        jdbc.update(
            "INSERT INTO submission_heads(id,room_id,participant_id,latest_version_id) VALUES (?,?,?,NULL)",
            UUID.randomUUID(),
            roomId,
            guestId,
        )
        jdbc.update(
            """
            INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,
              interpretation_reference_at,owner_binding)
            VALUES (?,?,?,1,'private draft before acceptance','ko-KR',now(),now(),'synthetic-owner-binding')
            """.trimIndent(),
            UUID.randomUUID(),
            roomId,
            guestId,
        )
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 0)
        assertEquals(2, rows("participants"))
        assertEquals(1, rows("submission_heads"))
        assertEquals(1, rows("precheck_candidates"))
        assertEquals(0, rows("submission_versions"))
    }

    @Test
    fun `SP04 public counts are isolated by room without revealing another participant to anonymous invalid or unrelated viewers`() {
        val created = create()
        val guest = join(created.host.code)
        mvc.perform(save(guest, "private-count-input-never-disclose")).andExpect(status().isOk)
        val foreignId = participantId(guest).toString()
        val unrelated = accounts.newAccount("unrelated account")
        val otherRoom = create()
        for (cookie in listOf(null, Cookie("meet_me_guest", "invalid-count-proof"), unrelated)) {
            val request = get("/api/rooms/${created.host.code}")
            cookie?.let { request.cookie(it) }
            val response =
                count(mvc.perform(request), 1)
                    .andExpect(jsonPath("$.viewer.joined").value(false))
                    .andExpect(jsonPath("$.viewer.context_id").isEmpty)
                    .andExpect(jsonPath("$.viewer.participant_id").isEmpty)
                    .andReturn()
                    .response
            val value = document(response)
            assertEquals(PUBLIC_FIELDS, value.keys)
            assertFalse(response.contentAsString.contains("count guest"))
            assertFalse(response.contentAsString.contains("private-count-input-never-disclose"))
            assertFalse(response.contentAsString.contains(foreignId))
        }
        count(mvc.perform(get("/api/rooms/${otherRoom.host.code}")), 0)
        mvc
            .perform(get("/api/rooms/${created.host.code}/submission"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("GUEST_SESSION_REQUIRED"))
        mvc
            .perform(
                post("/api/rooms/${created.host.code}/close")
                    .header("Origin", ORIGIN)
                    .cookie(unrelated)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PARTICIPANT_REQUIRED"))
    }

    @Test
    fun `SP05 explicit guest claim hide and account rejoin preserve the same submitted participant`() {
        val created = create()
        val guest = join(created.host.code)
        mvc.perform(save(guest, "claim retains this private submission")).andExpect(status().isOk)
        val participant = participantId(guest)
        val version =
            jdbc.queryForObject(
                "SELECT latest_version_id FROM submission_heads WHERE participant_id=?",
                UUID::class.java,
                participant,
            )
        val accountCookie = accounts.newAccount("claim account")
        val accountId = UUID.fromString(accountCookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        `when`(currentAccount.currentAccount()).thenReturn(AccountPrincipal(accountId, "claim account"))
        try {
            count(
                mvc.perform(post("/api/rooms/${guest.code}/claim").header("Origin", ORIGIN).cookie(guest.cookie())),
                1,
            )
        } finally {
            accounts.install()
        }
        mvc
            .perform(post("/api/account/rooms/${guest.code}/hide").header("Origin", ORIGIN).cookie(accountCookie))
            .andExpect(status().isNoContent)
        mvc
            .perform(get("/api/account/rooms").cookie(accountCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rooms.length()").value(0))
        count(mvc.perform(joinRequest(guest.code).cookie(accountCookie).content("{}")), 1)
        count(mvc.perform(get("/api/rooms/${guest.code}").cookie(guest.cookie())), 1)
            .andExpect(jsonPath("$.viewer.joined").value(false))
        assertEquals(participant, participantId(CorrectionSession(guest.code, accountCookie.value)))
        assertEquals(
            version,
            jdbc.queryForObject("SELECT latest_version_id FROM submission_heads WHERE participant_id=?", UUID::class.java, participant),
        )
        assertEquals(2, rows("participants"))
        assertEquals(1, rows("submission_heads"))
    }

    @Test
    fun `SP06 time only goals stay null and a stored count above the goal is never clamped to its denominator`() {
        val deadline =
            Instant
                .now()
                .truncatedTo(ChronoUnit.SECONDS)
                .plusSeconds(86400)
                .toString()
        val timeOnly = create(mapOf("manual_only" to false, "submission_deadline" to deadline))
        val response = document(timeOnly.response)
        assertCount(response, 0)
        assertEquals(null, response["expected_participants"])
        assertEquals(deadline, response["submission_deadline"])
        assertEquals(false, response["manual_only"])
        val created = create()
        val actors = listOf(created.host, join(created.host.code), join(created.host.code))
        actors.forEachIndexed { index, actor -> mvc.perform(save(actor, "stored count input $index")).andExpect(status().isOk) }
        // A historical over-target row is representable; the response must expose its actual stored count.
        jdbc.update("UPDATE meeting_rooms SET manual_only=false,expected_participants=2 WHERE invite_code=?", created.host.code)
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 3)
            .andExpect(jsonPath("$.expected_participants").value(2))
        assertEquals(3, rows("submission_heads"))
    }

    @Test
    fun `SP07 first and repeated close and analysis retry carry the same current submitted count`() {
        val created = create()
        val member = join(created.host.code)
        mvc.perform(save(created.host, "host input before first close")).andExpect(status().isOk)
        mvc.perform(save(member, "member input before first close")).andExpect(status().isOk)
        mvc
            .perform(get("/api/rooms/${created.host.code}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.collection_status").value("COLLECTING"))
        repeat(2) {
            count(
                mvc.perform(
                    post("/api/rooms/${created.host.code}/close")
                        .header("Origin", ORIGIN)
                        .cookie(created.host.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"confirm_early":true}"""),
                ),
                2,
            ).andExpect(jsonPath("$.collection_status").value("CLOSED"))
        }
        val sourceRun =
            requireNotNull(
                jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id=?", UUID::class.java, roomId(created.host.code)),
            )
        completePartial(sourceRun)
        jdbc.update(
            "UPDATE coordination_runs SET status='ANALYSIS_DELAYED',candidate_quality=NULL,version=version+1 WHERE id=?",
            sourceRun,
        )
        count(
            mvc.perform(post("/api/rooms/${created.host.code}/analysis/retry").header("Origin", ORIGIN).cookie(created.host.cookie())),
            2,
            httpStatus = 202,
        )
        assertCount(room(created.host), 2)
        assertEquals(2, rows("submission_heads"))
    }

    @Test
    fun `SP08 opening and partially resubmitting a correction round do not reset the count to its resubmitted cohort`() {
        val fixture = completedRoom()
        val opened =
            count(mvc.perform(reopen(fixture)), 2, prefix = "$.room")
                .andExpect(jsonPath("$.room.public_status").value("COLLECTING"))
                .andReturn()
                .response
        val roundId = UUID.fromString(child(document(opened), "round").getValue("id").toString())
        assertCount(room(fixture.host), 2)
        mvc.perform(save(fixture.host, "only host corrected", roundId, 1)).andExpect(status().isOk)
        assertCount(room(fixture.member), 2)
        count(mvc.perform(analyze(fixture, roundId)), 2, httpStatus = 202, prefix = "$.room")
        assertCount(room(fixture.host), 2)
        assertEquals(2, rows("submission_heads"))
        assertEquals(3, rows("submission_versions"))
    }

    @Test
    fun `SP09 a frozen analysis batch does not replace the current stored head count`() {
        val fixture = completedRoom()
        val versions = frozenVersions(fixture.sourceRun)
        assertEquals(2, versions.size)
        // Synthetic persistence boundary: an existing version can remain frozen while a head has no latest submission.
        jdbc.update("UPDATE submission_heads SET latest_version_id=NULL WHERE participant_id=?", participantId(fixture.member))
        count(mvc.perform(get("/api/rooms/${fixture.host.code}")), 1)
        assertEquals(versions, frozenVersions(fixture.sourceRun))
        assertEquals(2, rows("submission_versions"))
    }

    @Test
    fun `SP10 native schema requires a nonnull nonnegative integer count and exports actual springdoc bytes`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.components.schemas.RoomResponse.required").value(hasItem("submitted_participant_count")))
                .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.submitted_participant_count.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.submitted_participant_count.minimum").value(0))
                .andReturn()
                .response
        val native = mapper.readTree(response.contentAsByteArray)
        val field = native["components"]["schemas"]["RoomResponse"]["properties"]["submitted_participant_count"]
        assertFalse(field.path("nullable").asBoolean(false))
        assertTrue(field["description"].asText().isNotBlank())
        assertTrue(
            native["components"]["schemas"]["RoomResponse"]["properties"]["expected_participants"]["type"].toString().contains("null"),
        )
        val target = Path.of("build/room-submitted-participant-count.openapi.json")
        Files.createDirectories(target.parent)
        Files.write(target, response.contentAsByteArray)
        assertTrue(Files.readAllBytes(target).contentEquals(response.contentAsByteArray))
    }

    @Test
    fun `SP11 participant target still closes by OR before the future deadline using only submitted heads`() {
        val deadline =
            Instant
                .now()
                .truncatedTo(ChronoUnit.SECONDS)
                .plusSeconds(86400)
                .toString()
        val created =
            create(mapOf("manual_only" to false, "expected_participants" to 2, "submission_deadline" to deadline))
        val members = listOf(join(created.host.code), join(created.host.code))
        mvc.perform(save(members[0], "first actual submission")).andExpect(status().isOk)
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 1)
            .andExpect(jsonPath("$.collection_status").value("COLLECTING"))
        mvc.perform(save(members[1], "second actual submission")).andExpect(status().isOk)
        count(mvc.perform(get("/api/rooms/${created.host.code}")), 2)
            .andExpect(jsonPath("$.expected_participants").value(2))
            .andExpect(jsonPath("$.submission_deadline").value(deadline))
            .andExpect(jsonPath("$.closure_reason").value("EXPECTED_PARTICIPANTS"))
            .andExpect(jsonPath("$.collection_status").value("CLOSED"))
        assertEquals(3, rows("participants"))
        assertEquals(2, rows("submission_heads"))
    }

    @Test
    fun `SP12 confirmation and replay preserve submissions and expose the same count in confirmed detail`() {
        val fixture = recommendedRoom()
        val option = items(recommendations(fixture.host), "options").first()
        repeat(2) {
            mvc.perform(selection(fixture, option)).andExpect(status().isOk)
            count(mvc.perform(get("/api/rooms/${fixture.host.code}")), 2)
                .andExpect(jsonPath("$.public_status").value("CONFIRMED"))
        }
        assertEquals(2, rows("submission_heads"))
        assertEquals(2, rows("submission_versions"))
        assertEquals(1, rows("recommendation_selections"))
        assertEquals(0, rows("final_confirmations"))
    }

    private fun create(policy: Map<String, Any> = emptyMap()): Created {
        val host = accounts.newAccount("count host")
        val body = linkedMapOf<String, Any>("purpose" to "synthetic count contract", "meeting_mode" to "REMOTE", "manual_only" to true)
        body.putAll(policy)
        val response =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(host)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(response).getValue("invite_code").toString()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        return Created(CorrectionSession(code, host.value), response)
    }

    private fun join(
        code: String,
        account: Cookie? = null,
        expectedCount: Int? = null,
    ): CorrectionSession {
        val response =
            mvc
                .perform(joinRequest(code).apply { account?.let { cookie(it) } }.content("""{"display_name":"count guest"}"""))
                .andExpect(status().isCreated)
                .andReturn()
                .response
        expectedCount?.let { assertCount(document(response), it) }
        val raw =
            account?.value
                ?: requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';')
        return CorrectionSession(code, raw)
    }

    private fun joinRequest(code: String) =
        post("/api/rooms/$code/participants").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)

    private fun count(
        result: ResultActions,
        expected: Int,
        httpStatus: Int = 200,
        prefix: String = "$",
    ): ResultActions =
        result
            .andExpect(status().`is`(httpStatus))
            .andExpect(jsonPath("$prefix.submitted_participant_count").isNumber)
            .andExpect(jsonPath("$prefix.submitted_participant_count").value(expected))

    private fun assertCount(
        value: Map<String, Any?>,
        expected: Int,
    ) {
        assertTrue(value["submitted_participant_count"] is Int, "Submitted count is a required JSON integer")
        assertEquals(expected, value["submitted_participant_count"])
    }

    private fun roomId(code: String): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))

    private fun participantId(actor: CorrectionSession): UUID =
        UUID.fromString(child(room(actor), "viewer").getValue("context_id").toString())

    private fun rows(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private data class Created(
        val host: CorrectionSession,
        val response: MockHttpServletResponse,
    )

    companion object {
        private val PUBLIC_FIELDS =
            setOf(
                "invite_code",
                "expires_at",
                "purpose",
                "meeting_mode",
                "time_zone_id",
                "search_start_date",
                "search_end_date",
                "search_range_source",
                "expected_participants",
                "submitted_participant_count",
                "submission_deadline",
                "manual_only",
                "collection_status",
                "closure_reason",
                "closed_at",
                "public_status",
                "viewer",
                "input_disclosure_policy",
                "analysis_id",
                "revision_generation",
                "revision_round",
                "capabilities",
                "remaining_correction_analyses",
                "state_version",
                "recommendation_protocol",
                "clarification_protocol",
                "submission_protocol",
                "review_prompt_supported",
            )
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
