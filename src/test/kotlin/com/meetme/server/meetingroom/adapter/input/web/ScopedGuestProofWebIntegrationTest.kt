package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import jakarta.servlet.http.Cookie
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals

/** #109/#108 integration: HTTP operations must pass separate read/replace/claim scopes. */
@SpringBootTest
@AutoConfigureMockMvc
class ScopedGuestProofWebIntegrationTest {
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
            search_range_source,manual_only,collection_status,created_at,submission_protocol)
            VALUES (?,?,'scoped proof','EITHER','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,'COLLECTING',now(),'LEGACY')""",
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
    fun `replace only proof uses blind endpoint and rejects ordinary submission write and private read`() {
        proof(GuestProofLevel.REPLACE_ONLY)
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"replacement supplied by participant"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        mvc
            .perform(get("/api/rooms/$invite/submission").cookie(cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        assertEquals(
            "private previous input",
            jdbc.queryForObject("SELECT raw_text FROM submission_versions", String::class.java),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
    }

    @Test
    fun `replacement only trusted context proof without cookie still rejects ordinary write and read`() {
        `when`(proofs.resolve(room, null, false)).thenReturn(GuestOwnershipProof(room, participant, GuestProofLevel.REPLACE_ONLY))
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"replacement without cookie"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        mvc
            .perform(get("/api/rooms/$invite/submission"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        assertEquals("private previous input", jdbc.queryForObject("SELECT raw_text FROM submission_versions", String::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
    }

    @Test
    fun `verified trusted context proof without legacy cookie keeps private read and ordinary replacement`() {
        `when`(proofs.resolve(room, null, false)).thenReturn(GuestOwnershipProof(room, participant, GuestProofLevel.VERIFIED_GUEST))
        mvc
            .perform(get("/api/rooms/$invite/submission"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("private previous input"))
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"verified context replacement without cookie"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        assertEquals(
            "verified context replacement without cookie",
            jdbc.queryForObject("SELECT raw_text FROM submission_versions WHERE revision=2", String::class.java),
        )
    }

    @Test
    fun `replace only proof cannot claim even after authentication`() {
        proof(GuestProofLevel.REPLACE_ONLY)
        login()
        mvc
            .perform(post("/api/rooms/$invite/claim").header("Origin", ORIGIN).cookie(cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        assertEquals(
            session,
            jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, participant.value),
        )
        assertEquals(null, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, participant.value))
        assertEquals("private previous input", jdbc.queryForObject("SELECT raw_text FROM submission_versions", String::class.java))
    }

    @Test
    fun `replace only proof never grants host authority even for legacy host row`() {
        proof(GuestProofLevel.REPLACE_ONLY)
        jdbc.update("UPDATE participants SET role='HOST' WHERE id=?", participant.value)
        mvc
            .perform(
                post("/api/rooms/$invite/close")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isUnauthorized)
        assertEquals(
            "COLLECTING",
            jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, room.value),
        )
    }

    @Test
    fun `proof port rejection overrides an otherwise valid legacy cookie`() {
        mvc.perform(get("/api/rooms/$invite/submission").cookie(cookie())).andExpect(status().isUnauthorized)
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"must not save"}"""),
            ).andExpect(status().isUnauthorized)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
    }

    @Test
    fun `verified guest proof keeps ordinary private read and replacement`() {
        proof(GuestProofLevel.VERIFIED_GUEST)
        mvc
            .perform(get("/api/rooms/$invite/submission").cookie(cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("private previous input"))
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"verified replacement"}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `wrong room proof cannot read or replace even with valid cookie`() {
        val bad = GuestOwnershipProof(MeetingRoomId(UUID.randomUUID()), participant, GuestProofLevel.VERIFIED_GUEST)
        `when`(proofs.resolve(room, RAW, false)).thenReturn(bad)
        mvc
            .perform(get("/api/rooms/$invite/submission").cookie(cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        mvc
            .perform(
                put("/api/rooms/$invite/submission")
                    .header("Origin", ORIGIN)
                    .cookie(cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"wrong room replacement"}"""),
            ).andExpect(status().isForbidden)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
    }

    @Test
    fun `proof that now points to account owned row cannot recover stale guest read authority`() {
        proof(GuestProofLevel.VERIFIED_GUEST)
        val owner = login()
        jdbc.update("UPDATE participants SET guest_session_id=NULL,user_id=? WHERE id=?", owner.accountId, participant.value)
        `when`(account.currentAccount()).thenReturn(null)
        mvc
            .perform(get("/api/rooms/$invite/submission").cookie(cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
    }

    private fun proof(level: GuestProofLevel) {
        val value = GuestOwnershipProof(room, participant, level)
        `when`(proofs.resolve(room, RAW, false)).thenReturn(value)
        `when`(proofs.resolve(room, RAW, true)).thenReturn(value)
    }

    private fun login(): AccountPrincipal {
        val value = AccountPrincipal(UUID.randomUUID(), "account")
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", value.accountId, value.displayName)
        `when`(account.currentAccount()).thenReturn(value)
        return value
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
