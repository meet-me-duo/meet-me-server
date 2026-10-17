package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals

/** #109/#108 public view and join must not promote replacement-only or rejected proof. */
@SpringBootTest
@AutoConfigureMockMvc
class ScopedGuestViewerJoinWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var credentials: GuestCredentialPort

    @MockitoBean private lateinit var account: CurrentAccountPort

    @MockitoBean private lateinit var proofs: GuestOwnershipProofPort
    private var room = MeetingRoomId(UUID.randomUUID())
    private var participant = ParticipantId(UUID.randomUUID())
    private lateinit var session: UUID
    private lateinit var invite: String

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        `when`(account.currentAccount()).thenReturn(null)
        room = MeetingRoomId(UUID.randomUUID())
        participant = ParticipantId(UUID.randomUUID())
        session = UUID.randomUUID()
        invite = "abcdefghijklmnopqrstuv"
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,created_at)
            VALUES (?,?,'scoped proof','EITHER','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,'COLLECTING',now())""",
            room.value,
            invite,
        )
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES (?,?,now(),now()+interval '30 days')",
            session,
            credentials.digest(RAW),
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER','guest',now())",
            participant.value,
            room.value,
            session,
        )
        val head = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, room.value, participant.value)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,'private previous input','ko-KR',now())",
            version,
            head,
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
    }

    @Test
    fun `replacement only proof keeps public polling available without revealing existing participation`() {
        proof(GuestProofLevel.REPLACE_ONLY)
        mvc
            .perform(get("/api/rooms/$invite").cookie(cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(false))
            .andExpect(jsonPath("$.viewer.display_name").value(nullValue()))
            .andExpect(jsonPath("$.viewer.context_id").value(nullValue()))
            .andExpect(jsonPath("$.viewer.participant_id").value(nullValue()))
            .andExpect(jsonPath("$.viewer.raw_text").doesNotExist())
        assertEquals("private previous input", jdbc.queryForObject("SELECT raw_text FROM submission_versions", String::class.java))
    }

    @Test
    fun `rejected proof with active cookie exposes only public room data`() {
        mvc
            .perform(get("/api/rooms/$invite").cookie(cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(false))
            .andExpect(jsonPath("$.viewer.context_id").doesNotExist())
            .andExpect(jsonPath("$.viewer.display_name").doesNotExist())
    }

    @Test
    fun `replacement only proof cannot recover existing participation through idempotent join`() {
        proof(GuestProofLevel.REPLACE_ONLY)
        mvc
            .perform(
                post("/api/rooms/$invite/participants")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"untrusted replacement"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM participants", Int::class.java))
        assertEquals(
            "guest",
            jdbc.queryForObject("SELECT display_name FROM participants WHERE id=?", String::class.java, participant.value),
        )
    }

    @Test
    fun `rejected proof never reuses old guest participation when joining as a new guest`() {
        val response =
            mvc
                .perform(
                    post("/api/rooms/$invite/participants")
                        .header("Origin", ORIGIN)
                        .cookie(cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"display_name":"new guest"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.viewer.display_name").value("new guest"))
                .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header()
                        .exists("Set-Cookie"),
                ).andReturn()
                .response
        kotlin.test.assertFalse(response.contentAsString.contains(participant.value.toString()))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM participants", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM guest_browser_sessions", Int::class.java))
        assertEquals(
            session,
            jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, participant.value),
        )
        assertEquals("private previous input", jdbc.queryForObject("SELECT raw_text FROM submission_versions", String::class.java))
    }

    private fun proof(level: GuestProofLevel) {
        val value = GuestOwnershipProof(room, participant, level)
        `when`(proofs.resolve(room, RAW, false)).thenReturn(value)
        `when`(proofs.resolve(room, RAW, true)).thenReturn(value)
    }

    private fun cookie() = Cookie("meet_me_guest", RAW)

    companion object {
        private const val RAW = "synthetic-valid-cookie"
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
        }
    }
}
