package com.meetme.server.meetingroom.adapter.input.web

import com.jayway.jsonpath.JsonPath
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** #197: hiding is an account-list preference, never participant removal or room deactivation. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountRoomHideWebIntegrationTest.HideClockConfiguration::class)
class AccountRoomHideWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @MockitoBean private lateinit var account: CurrentAccountPort
    private val caller = ThreadLocal<AccountPrincipal?>()

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller.remove()
        `when`(account.currentAccount()).thenAnswer { caller.get() }
    }

    @Test
    fun `host hide returns empty uncached response and preserves member list direct link and submission ownership`() {
        val host = login("host")
        val room = room()
        val hostId = participant(room, host, "HOST")
        val member = login("member")
        participant(room, member)
        val version = submission(room, hostId)
        val originalRoom = jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id=?", room.id)
        caller.set(host)

        mvc
            .perform(hide(room))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
        mvc
            .perform(get("/api/rooms/${room.invite}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(true))
            .andExpect(jsonPath("$.viewer.role").value("HOST"))
        mvc
            .perform(get("/api/rooms/${room.invite}/submission"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("preserved original"))
        caller.set(member)
        mvc
            .perform(get("/api/account/rooms"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rooms[0].invite_code").value(room.invite))
        assertEquals(originalRoom, jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id=?", room.id))
        assertEquals(host.accountId, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, hostId))
        assertEquals(
            version,
            jdbc.queryForObject("SELECT latest_version_id FROM submission_heads WHERE participant_id=?", UUID::class.java, hostId),
        )
        assertEquals(2, count("participants"))
    }

    @Test
    fun `member can hide own relationship and another device with same account still sees it hidden`() {
        val member = login()
        val room = room()
        participant(room, member)
        mvc.perform(hide(room)).andExpect(status().isNoContent)
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.role").value("MEMBER"))
        caller.remove()
        caller.set(AccountPrincipal(member.accountId, "same account on another device"))
        mvc
            .perform(get("/api/account/rooms").param("role", "MEMBER").param("query", "meeting"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rooms.length()").value(0))
        mvc
            .perform(get("/api/rooms/${room.invite}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.role").value("MEMBER"))
        assertEquals(1, count("participants"))
    }

    @Test
    fun `anonymous and guest-only requests cannot hide even with the owning guest cookie`() {
        val room = room()
        val proof = guest()
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'HOST','guest',?)",
            UUID.randomUUID(),
            room.id,
            proof.first,
            Timestamp.from(NOW.minusSeconds(60)),
        )
        mvc.perform(hide(room)).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("ACCOUNT_REQUIRED"))
        mvc
            .perform(hide(room).cookie(Cookie("meet_me_guest", proof.second)))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("ACCOUNT_REQUIRED"))
        login("unrelated")
        mvc
            .perform(hide(room).cookie(Cookie("meet_me_guest", proof.second)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
        assertEquals(1, count("participants"))
    }

    @Test
    fun `other accounts nonexistent invalid and expired rooms share the same not-found contract`() {
        val owner = login("owner")
        val ownRoom = room()
        participant(ownRoom, owner)
        val expired = room(NOW.minus(Duration.ofDays(30)).minusNanos(1000), NOW.minusSeconds(10))
        participant(expired, owner)
        val stranger = login("stranger")
        mvc.perform(hide(ownRoom)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
        caller.set(owner)
        for (invite in listOf("AAAAAAAAAAAAAAAAAAAAAA", "invalid!", expired.invite)) {
            mvc
                .perform(post("/api/account/rooms/$invite/hide").header("Origin", ORIGIN))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
        }
        mvc
            .perform(get("/api/account/rooms"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rooms.length()").value(1))
            .andExpect(jsonPath("$.rooms[0].invite_code").value(ownRoom.invite))
        caller.set(stranger)
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
    }

    @Test
    fun `missing and untrusted origins cannot hide and exact trusted POST preflight remains supported`() {
        val owner = login()
        val room = room()
        participant(room, owner)
        mvc
            .perform(post("/api/account/rooms/${room.invite}/hide"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"))
        mvc
            .perform(post("/api/account/rooms/${room.invite}/hide").header("Origin", "https://untrusted.example"))
            .andExpect(status().isForbidden)
        mvc
            .perform(
                options("/api/account/rooms/${room.invite}/hide")
                    .header("Origin", ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(1))
    }

    @Test
    fun `repeated concurrent hides all succeed without changing room or participation identity`() {
        val owner = login()
        val room = room()
        val participant = participant(room, owner)
        val original = jdbc.queryForMap("SELECT id,room_id,user_id,role,joined_at FROM participants WHERE id=?", participant)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (1..4).map {
                    executor.submit<Int> {
                        caller.set(owner)
                        try {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            mvc
                                .perform(hide(room))
                                .andReturn()
                                .response.status
                        } finally {
                            caller.remove()
                        }
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(204, 204, 204, 204), futures.map { it.get(20, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        mvc.perform(hide(room)).andExpect(status().isNoContent).andExpect(content().string(""))
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
        assertEquals(original, jdbc.queryForMap("SELECT id,room_id,user_id,role,joined_at FROM participants WHERE id=?", participant))
        assertEquals(1, count("meeting_rooms"))
    }

    @Test
    fun `hidden entries stay excluded from search role dates and an already issued cursor`() {
        val owner = login()
        val rooms = (0..5).map { index -> room(NOW.minusSeconds(100L + index)).also { participant(it, owner, "HOST") } }
        val firstPage =
            mvc
                .perform(get("/api/account/rooms").param("limit", "2"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.has_more").value(true))
                .andReturn()
                .response.contentAsString
        val cursor = JsonPath.read<String>(firstPage, "$.next_cursor")
        mvc.perform(hide(rooms[1])).andExpect(status().isNoContent)
        mvc.perform(hide(rooms[2])).andExpect(status().isNoContent)
        mvc
            .perform(get("/api/account/rooms").param("limit", "2").param("cursor", cursor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rooms.length()").value(2))
            .andExpect(jsonPath("$.rooms[0].invite_code").value(rooms[3].invite))
            .andExpect(jsonPath("$.rooms[1].invite_code").value(rooms[4].invite))
        val filtered =
            mvc
                .perform(
                    get("/api/account/rooms")
                        .param("query", "meeting")
                        .param("role", "HOST")
                        .param("created_from", "2026-10-17")
                        .param("created_to", "2026-10-17"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.rooms.length()").value(4))
                .andReturn()
                .response.contentAsString
        assertFalse(filtered.contains(rooms[1].invite))
        assertFalse(filtered.contains(rooms[2].invite))
    }

    @Test
    fun `closed and collecting archive expiry use creation and exact 30 day boundary is retained`() {
        val owner = login()
        val cutoff = NOW.minus(Duration.ofDays(30))
        val openBoundary = room(cutoff)
        val closedBoundary = room(cutoff, NOW.minusSeconds(1))
        val expiredClosed = room(cutoff.minusNanos(1000), NOW.minusSeconds(1))
        val recentClosed = room(NOW.minus(Duration.ofDays(3)), NOW.minusSeconds(1))
        for (room in listOf(openBoundary, closedBoundary, expiredClosed, recentClosed)) participant(room, owner)
        val result =
            mvc
                .perform(get("/api/account/rooms"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.rooms.length()").value(3))
                .andReturn()
                .response.contentAsString
        assertFalse(result.contains(expiredClosed.invite))
        assertEquals(
            listOf(recentClosed.createdAt.plus(Duration.ofDays(30)).toString(), NOW.toString(), NOW.toString()),
            JsonPath.read<List<String>>(result, "$.rooms[*].expires_at"),
        )
        mvc.perform(hide(closedBoundary)).andExpect(status().isNoContent)
        mvc.perform(hide(openBoundary)).andExpect(status().isNoContent)
        assertEquals(
            cutoff,
            jdbc.queryForObject("SELECT created_at FROM meeting_rooms WHERE id=?", Timestamp::class.java, closedBoundary.id)?.toInstant(),
        )
    }

    @Test
    fun `retention deletes hidden and recently closed old rooms with dependencies but retains exact creation boundary`() {
        val owner = login()
        val hidden = room()
        val hiddenParticipant = participant(hidden, owner)
        submission(hidden, hiddenParticipant)
        mvc.perform(hide(hidden)).andExpect(status().isNoContent)
        val cutoff = NOW.minus(Duration.ofDays(30))
        jdbc.update("UPDATE meeting_rooms SET created_at=? WHERE id=?", Timestamp.from(cutoff.minusSeconds(1)), hidden.id)
        val closed = room(cutoff.minusSeconds(1), NOW.minusSeconds(1))
        submission(closed, participant(closed, owner))
        val openBoundary = room(cutoff)
        val closedBoundary = room(cutoff, NOW.minusSeconds(1))
        participant(openBoundary, owner)
        participant(closedBoundary, owner)
        mvc.perform(hide(closedBoundary)).andExpect(status().isNoContent)
        val originalBoundary = jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id=?", closedBoundary.id)

        assertEquals(2, retention.deleteExpiredRooms(cutoff, 100, NOW))
        assertEquals(2, count("meeting_rooms"))
        assertEquals(2, count("participants"))
        assertEquals(0, count("submission_heads"))
        assertEquals(0, count("submission_versions"))
        assertEquals(originalBoundary, jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id=?", closedBoundary.id))
        assertEquals(0, retention.deleteExpiredRooms(cutoff, 100, NOW))
    }

    @Test
    fun `OpenAPI documents empty 204 hide response and relevant security and not-found contracts`() {
        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['204']").exists())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['204'].content").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['401']").exists())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['403']").exists())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['404']").exists())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.responses['503']").exists())
            .andExpect(jsonPath("$.paths['/api/account/rooms/{inviteCode}/hide'].post.requestBody").doesNotExist())
    }

    private fun login(name: String = "member"): AccountPrincipal {
        val owner = AccountPrincipal(UUID.randomUUID(), name)
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)", owner.accountId, name, Timestamp.from(NOW))
        caller.set(owner)
        return owner
    }

    private fun room(
        createdAt: Instant = NOW.minusSeconds(30),
        closedAt: Instant? = null,
    ): RoomFixture {
        val id = UUID.randomUUID()
        val invite =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(UUID.randomUUID().toString().toByteArray())
                .take(22)
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at)
                VALUES (?,?,'meeting','EITHER','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,?,?,?,?)""",
            id,
            invite,
            if (closedAt == null) "COLLECTING" else "CLOSED",
            if (closedAt == null) null else "MANUAL",
            closedAt?.let(Timestamp::from),
            Timestamp.from(createdAt),
        )
        return RoomFixture(id, invite, createdAt)
    }

    private fun participant(
        room: RoomFixture,
        owner: AccountPrincipal,
        role: String = "MEMBER",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
            id,
            room.id,
            owner.accountId,
            role,
            owner.displayName,
            Timestamp.from(NOW.minusSeconds(20)),
        )
        return id
    }

    private fun submission(
        room: RoomFixture,
        participant: UUID,
    ): UUID {
        val head = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, room.id, participant)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,'preserved original','ko-KR',?)",
            version,
            head,
            Timestamp.from(NOW),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
        return version
    }

    private fun guest(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES (?,?,?,?)",
            id,
            credentials.digest(raw),
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW.plus(Duration.ofDays(29))),
        )
        return id to raw
    }

    private fun hide(room: RoomFixture) = post("/api/account/rooms/${room.invite}/hide").header("Origin", ORIGIN)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private data class RoomFixture(
        val id: UUID,
        val invite: String,
        val createdAt: Instant,
    )

    @TestConfiguration
    class HideClockConfiguration {
        @Bean
        @Primary
        fun hideClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()

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
