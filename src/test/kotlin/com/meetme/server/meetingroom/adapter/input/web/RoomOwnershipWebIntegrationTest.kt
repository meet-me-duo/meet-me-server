package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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

/** Issue #109 ownership boundary; account authentication itself is tested independently. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomOwnershipWebIntegrationTest.OwnershipClockConfiguration::class)
class RoomOwnershipWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var credentials: GuestCredentialPort

    @MockitoBean private lateinit var account: CurrentAccountPort
    private val caller = ThreadLocal<AccountPrincipal?>()

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller.remove()
        `when`(account.currentAccount()).thenAnswer { caller.get() }
    }

    @Test
    fun `host creation requires account even when guest proof and name are valid`() {
        mvc
            .perform(
                post("/api/rooms")
                    .header("Origin", ORIGIN)
                    .cookie(cookie(guest()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"purpose":"meeting","meeting_mode":"EITHER","manual_only":true,"host_display_name":"guest"}"""),
            ).andExpect(status().isUnauthorized)
        assertEquals(0, count("meeting_rooms"))
    }

    @Test
    fun `member host uses account profile without name or password and issues no guest credential`() {
        val owner = login()
        mvc
            .perform(
                post("/api/rooms")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"purpose":"meeting","meeting_mode":"EITHER","manual_only":true}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.role").value("HOST"))
            .andExpect(jsonPath("$.viewer.display_name").value(owner.displayName))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertEquals(owner.accountId, jdbc.queryForObject("SELECT user_id FROM participants", UUID::class.java))
        assertEquals(0, count("guest_browser_sessions"))
    }

    @Test
    fun `member joins without reentering name and account identity wins over guest cookie`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id)
        val member = login()
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .cookie(cookie(proof))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.display_name").value(member.displayName))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertEquals(2, count("participants"))
        assertEquals(null, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old))
        assertEquals(proof.id, jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, old))
    }

    @Test
    fun `guest participation remains available with valid display name`() {
        val room = room()
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"guest"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.display_name").value("guest"))
            .andExpect(header().exists(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `login and same display name never implicitly adopt guest records`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id, role = "HOST")
        login("same profile")
        mvc
            .perform(get("/api/rooms/${room.invite}").cookie(cookie(proof)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(false))
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
        assertEquals(null, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old))
    }

    @Test
    fun `explicit claim preserves participant and submission then removes guest ownership`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id, role = "HOST")
        val otherRoom = room()
        participant(otherRoom, guestId = proof.id)
        val version = submission(room, old)
        val owner = login()
        mvc.perform(claim(room, proof)).andExpect(status().isOk).andExpect(jsonPath("$.viewer.role").value("HOST"))
        assertEquals(owner.accountId, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old))
        assertEquals(null, jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, old))
        assertEquals(
            version,
            jdbc.queryForObject("SELECT latest_version_id FROM submission_heads WHERE participant_id=?", UUID::class.java, old),
        )
        mvc
            .perform(
                get("/api/rooms/${room.invite}/submission"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("preserved original"))
        caller.remove()
        mvc
            .perform(
                get("/api/rooms/${room.invite}").cookie(cookie(proof)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(false))
        mvc.perform(get("/api/rooms/${room.invite}/submission").cookie(cookie(proof))).andExpect(status().isForbidden)
        mvc
            .perform(
                get("/api/rooms/${otherRoom.invite}").cookie(cookie(proof)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.joined").value(true))
        assertEquals(2, count("participants"))
        assertEquals(1, count("submission_versions"))
    }

    @Test
    fun `claim needs both authenticated account and valid guest ownership proof`() {
        val room = room()
        val proof = guest()
        participant(room, guestId = proof.id)
        mvc.perform(claim(room, proof)).andExpect(status().isUnauthorized)
        login()
        mvc.perform(post("/api/rooms/${room.invite}/claim").header("Origin", ORIGIN)).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post("/api/rooms/${room.invite}/claim").header("Origin", ORIGIN).cookie(Cookie("meet_me_guest", "forged")),
            ).andExpect(status().isUnauthorized)
        mvc.perform(claim(room, guest())).andExpect(status().isForbidden)
        assertEquals(1, count("participants"))
    }

    @Test
    fun `expired and revoked proof cannot claim and failed claims do not mutate owner`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id)
        login()
        jdbc.update("UPDATE guest_browser_sessions SET expires_at=created_at+interval '1 microsecond' WHERE id=?", proof.id)
        mvc.perform(claim(room, proof)).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE guest_browser_sessions SET expires_at=created_at+interval '30 days',revoked_at=created_at WHERE id=?", proof.id)
        mvc.perform(claim(room, proof)).andExpect(status().isUnauthorized)
        assertEquals(proof.id, jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, old))
        assertEquals(null, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old))
    }

    @Test
    fun `claim with existing account participant conflicts without merging either record`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id)
        val oldVersion = submission(room, old)
        val owner = login()
        val existing = participant(room, accountId = owner.accountId)
        val memberVersion = submission(room, existing)
        mvc.perform(claim(room, proof)).andExpect(status().isConflict)
        assertEquals(2, count("participants"))
        assertEquals(proof.id, jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, old))
        assertEquals(owner.accountId, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, existing))
        assertEquals(
            setOf(oldVersion, memberVersion),
            jdbc.queryForList("SELECT latest_version_id FROM submission_heads", UUID::class.java).toSet(),
        )
    }

    @Test
    fun `competing accounts claim guest only once and losing account never receives ownership`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id, role = "HOST")
        val owners = listOf(login("first"), login("second"))
        caller.remove()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                owners.map { owner ->
                    executor.submit<Int> {
                        caller.set(owner)
                        try {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            mvc
                                .perform(claim(room, proof))
                                .andReturn()
                                .response.status
                        } finally {
                            caller.remove()
                        }
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == 200 })
            assertEquals(1, results.count { it in setOf(403, 409) })
            assertEquals(
                owners[results.indexOf(200)].accountId,
                jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old),
            )
            assertEquals(1, count("participants"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `simultaneous account join and guest claim either conflict or preserve the claimed participant`() {
        val room = room()
        val proof = guest()
        val old = participant(room, guestId = proof.id)
        val version = submission(room, old)
        val owner = login()
        caller.remove()
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            fun request(join: Boolean) =
                executor.submit<Int> {
                    caller.set(owner)
                    try {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        val action =
                            if (join) {
                                post("/api/rooms/${room.invite}/participants")
                                    .header("Origin", ORIGIN)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}")
                            } else {
                                claim(room, proof)
                            }
                        mvc
                            .perform(action)
                            .andReturn()
                            .response.status
                    } finally {
                        caller.remove()
                    }
                }
            val joining = request(true)
            val claiming = request(false)
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val joinedStatus = joining.get(20, TimeUnit.SECONDS)
            val claimStatus = claiming.get(20, TimeUnit.SECONDS)
            if (claimStatus == 200) {
                assertEquals(200, joinedStatus)
                assertEquals(1, count("participants"))
                assertEquals(owner.accountId, jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, old))
            } else {
                assertEquals(409, claimStatus)
                assertEquals(201, joinedStatus)
                assertEquals(2, count("participants"))
                assertEquals(proof.id, jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, old))
            }
            assertEquals(
                version,
                jdbc.queryForObject("SELECT latest_version_id FROM submission_heads WHERE participant_id=?", UUID::class.java, old),
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM participants WHERE room_id=? AND user_id=?",
                    Int::class.java,
                    room.id,
                    owner.accountId,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `guest host cannot close until explicit claim and unrelated logged in account cannot ride host proof`() {
        val room = room()
        val proof = guest()
        participant(room, guestId = proof.id, role = "HOST")
        mvc.perform(close(room, proof)).andExpect(status().isUnauthorized)
        login()
        mvc.perform(close(room, proof)).andExpect(status().isForbidden)
        mvc.perform(claim(room, proof)).andExpect(status().isOk)
        mvc.perform(close(room, proof)).andExpect(status().isOk).andExpect(jsonPath("$.collection_status").value("CLOSED"))
    }

    @Test
    fun `all host endpoints require account and reject unrelated account riding legacy host cookie`() {
        val room = room()
        val proof = guest()
        participant(room, guestId = proof.id, role = "HOST")
        val id = UUID.randomUUID()

        fun requests() =
            listOf(
                post("/api/rooms/${room.invite}/analysis/retry"),
                post("/api/rooms/${room.invite}/reopen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"request_id":"$id","source_analysis_id":"$id","expected_generation":0}"""),
                post("/api/rooms/${room.invite}/analysis")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"request_id":"$id","revision_round_id":"$id"}"""),
                post("/api/rooms/${room.invite}/candidates/$id/confirmation"),
                post("/api/rooms/${room.invite}/recommendations/$id/confirmation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"analysis_id":"$id","variant_id":"$id","start_at":"2026-10-18T00:00:00Z","end_at":"2026-10-18T01:00:00Z"}""",
                    ),
                get("/api/rooms/${room.invite}/candidates/unapplied-inputs"),
            )
        for (request in requests()) {
            mvc.perform(request.header("Origin", ORIGIN).cookie(cookie(proof))).andExpect(status().isUnauthorized)
        }
        login("unrelated")
        for (request in requests()) {
            mvc.perform(request.header("Origin", ORIGIN).cookie(cookie(proof))).andExpect(status().isForbidden)
        }
        assertEquals(
            "COLLECTING",
            jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, room.id),
        )
        assertEquals(0, count("coordination_runs"))
    }

    @Test
    fun `member submission uses account without guest cookie and does not expose other member input`() {
        val room = room()
        // Historical direct-submission/correction oracle: migrated rooms retain LEGACY.
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE id=?", room.id)
        val owner = login()
        participant(room, accountId = owner.accountId)
        mvc
            .perform(
                put("/api/rooms/${room.invite}/submission")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"member original"}"""),
            ).andExpect(status().isOk)
        mvc
            .perform(
                get("/api/rooms/${room.invite}/submission"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("member original"))
        login("other")
        mvc.perform(get("/api/rooms/${room.invite}/submission")).andExpect(status().isForbidden)
    }

    @Test
    fun `archive lists only own host rooms and exposes created or closed anchored 30 day expiry`() {
        val owner = login()
        val now = NOW
        val open = room(now.minus(Duration.ofDays(10)))
        val closed = room(now.minus(Duration.ofDays(35)), now.minus(Duration.ofDays(3)))
        val member = room()
        val other = room()
        val legacy = room()
        val expired = room(now.minus(Duration.ofDays(31)))
        val boundary = room(now.minus(Duration.ofDays(30)))
        participant(open, accountId = owner.accountId, role = "HOST")
        participant(closed, accountId = owner.accountId, role = "HOST")
        participant(member, accountId = owner.accountId)
        participant(other, accountId = login("other").accountId, role = "HOST")
        participant(legacy, guestId = guest().id, role = "HOST")
        participant(expired, accountId = owner.accountId, role = "HOST")
        participant(boundary, accountId = owner.accountId, role = "HOST")
        caller.set(owner)
        val result =
            mvc
                .perform(
                    get("/api/account/rooms").param("role", "HOST"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.rooms.length()").value(3))
                .andReturn()
                .response.contentAsString
        assertFalse(result.contains(member.invite))
        assertFalse(result.contains(other.invite))
        assertFalse(result.contains(legacy.invite))
        assertFalse(result.contains(expired.invite))
        for (expected in listOf(open.createdAt.plus(Duration.ofDays(30)), requireNotNull(closed.closedAt).plus(Duration.ofDays(30)))) {
            org.springframework.test.util
                .JsonPathExpectationsHelper(
                    "$.rooms[*].expires_at",
                ).assertValue(result, org.hamcrest.Matchers.hasItem(expected.toString()))
        }
        org.springframework.test.util
            .JsonPathExpectationsHelper(
                "$.rooms[*].invite_code",
            ).assertValue(result, org.hamcrest.Matchers.hasItem(boundary.invite))
        assertEquals(7, count("meeting_rooms"))
    }

    @Test
    fun `archive requires login and claim requires trusted origin`() {
        mvc.perform(get("/api/account/rooms")).andExpect(status().isUnauthorized)
        val room = room()
        val proof = guest()
        participant(room, guestId = proof.id)
        login()
        mvc.perform(post("/api/rooms/${room.invite}/claim").cookie(cookie(proof))).andExpect(status().isForbidden)
    }

    @Test
    fun `OpenAPI includes ownership archive and claim security error contracts`() {
        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/account/rooms'].get.responses['401']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/claim'].post.responses['409']").exists())
            .andExpect(jsonPath("$.components.schemas.AccountRoomResponse.properties.expires_at").exists())
    }

    private fun login(name: String = "same profile"): AccountPrincipal {
        val principal = AccountPrincipal(UUID.randomUUID(), name)
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", principal.accountId, name)
        caller.set(principal)
        return principal
    }

    private fun room(
        createdAt: Instant = NOW.minusSeconds(30),
        closedAt: Instant? = null,
    ): RoomFixture {
        val id = UUID.randomUUID()
        val invite =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
            )
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at)
            VALUES (?,?,'meeting','EITHER','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,?,?,?,?)""",
            id,
            invite,
            if (closedAt ==
                null
            ) {
                "COLLECTING"
            } else {
                "CLOSED"
            },
            if (closedAt ==
                null
            ) {
                null
            } else {
                "MANUAL"
            },
            closedAt?.let(Timestamp::from),
            Timestamp.from(createdAt),
        )
        return RoomFixture(id, invite, createdAt, closedAt)
    }

    private fun guest(): GuestFixture {
        val id = UUID.randomUUID()
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES (?,?,?,?)",
            id,
            credentials.digest(raw),
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW.plus(Duration.ofDays(29))),
        )
        return GuestFixture(id, raw)
    }

    private fun participant(
        room: RoomFixture,
        guestId: UUID? = null,
        accountId: UUID? = null,
        role: String = "MEMBER",
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,'same profile',now())",
            id,
            room.id,
            guestId,
            accountId,
            role,
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
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,'preserved original','ko-KR',now())",
            version,
            head,
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
        return version
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun cookie(proof: GuestFixture) = Cookie("meet_me_guest", proof.raw)

    private fun claim(
        room: RoomFixture,
        proof: GuestFixture,
    ) = post("/api/rooms/${room.invite}/claim").header("Origin", ORIGIN).cookie(cookie(proof))

    private fun close(
        room: RoomFixture,
        proof: GuestFixture,
    ) = post(
        "/api/rooms/${room.invite}/close",
    ).header("Origin", ORIGIN).cookie(cookie(proof)).contentType(MediaType.APPLICATION_JSON).content("""{"confirm_early":true}""")

    private data class GuestFixture(
        val id: UUID,
        val raw: String,
    )

    private data class RoomFixture(
        val id: UUID,
        val invite: String,
        val createdAt: Instant,
        val closedAt: Instant?,
    )

    @TestConfiguration
    class OwnershipClockConfiguration {
        @Bean
        @Primary
        fun ownershipClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic @AfterAll
        fun stopContainer() {
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
