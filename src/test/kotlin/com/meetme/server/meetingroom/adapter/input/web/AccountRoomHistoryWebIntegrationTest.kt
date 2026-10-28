package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FR-109C/D: account history public HTTP contract; fixtures use only synthetic data. */
@SpringBootTest(properties = ["meetme.data-retention.enabled=false", "meetme.reliability.enabled=false"])
@AutoConfigureMockMvc
@Import(AccountRoomHistoryWebIntegrationTest.HistoryClockConfiguration::class)
class AccountRoomHistoryWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var account: CurrentAccountPort
    private var caller: AccountPrincipal? = null

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller = null
        `when`(account.currentAccount()).thenAnswer { caller }
    }

    @Test
    fun `all is default and roles expose only current account ownership with no guest adoption`() {
        val owner = login()
        val host = room(owner, "HOST")
        val member = room(owner, "MEMBER")
        val foreign = room(login(), "HOST")
        caller = owner
        val guestRoom = room(null, "HOST")
        val result =
            mvc
                .perform(get(PATH).cookie(Cookie("meet_me_guest", "synthetic-guest-proof")))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.rooms.length()").value(2))
                .andReturn()
                .response.contentAsString
        val rooms = mapper.readTree(result)["rooms"]
        assertEquals(setOf(host, member), rooms.toList().map { it["invite_code"].asText() }.toSet())
        assertEquals(setOf("HOST", "MEMBER"), rooms.toList().map { it["role"].asText() }.toSet())
        assertFalse(result.contains(foreign))
        assertFalse(result.contains(guestRoom))
        assertFalse(mapper.readTree(result)["has_more"].asBoolean())
        assertTrue(mapper.readTree(result)["next_cursor"].isNull)
        for ((role, invite) in listOf("HOST" to host, "MEMBER" to member)) {
            mvc
                .perform(get(PATH).param("role", role))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].invite_code").value(invite))
                .andExpect(jsonPath("$.rooms[0].role").value(role))
        }
        caller = login()
        mvc.perform(get(PATH)).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
        caller = null
        mvc.perform(get(PATH).cookie(Cookie("meet_me_guest", "synthetic-guest-proof"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `creation dates use inclusive KST days independently of closed retention anchor`() {
        val owner = login()
        room(owner, "HOST", Instant.parse("2026-10-14T14:59:59.999999Z"))
        val start = room(owner, "MEMBER", Instant.parse("2026-10-14T15:00:00Z"))
        val end = room(owner, "HOST", Instant.parse("2026-10-15T14:59:59.999999Z"))
        room(owner, "MEMBER", Instant.parse("2026-10-15T15:00:00Z"))
        val result = response("created_from" to "2026-10-15", "created_to" to "2026-10-15")
        assertEquals(listOf(end, start), invites(result))
        assertEquals(3, invites(response("created_from" to "2026-10-15")).size)
        assertEquals(3, invites(response("created_to" to "2026-10-15")).size)
        val oldClosed = room(owner, "HOST", NOW.minus(Duration.ofDays(45)), NOW.minus(Duration.ofDays(1)))
        assertEquals(listOf(oldClosed), invites(response("created_to" to "2026-09-05")))
    }

    @Test
    fun `existing thirty day boundaries include equality and keep closed and collecting anchors distinct`() {
        val owner = login()
        val cutoff = NOW.minus(Duration.ofDays(30))
        val collectingBoundary = room(owner, "MEMBER", cutoff)
        room(owner, "HOST", cutoff.minusNanos(1000))
        val closedBoundary = room(owner, "HOST", cutoff.minus(Duration.ofDays(5)), cutoff)
        room(owner, "MEMBER", cutoff.minus(Duration.ofDays(5)), cutoff.minusNanos(1000))
        val closedRecent = room(owner, "MEMBER", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(2)))
        val rows = response()["rooms"]
        assertEquals(setOf(collectingBoundary, closedBoundary, closedRecent), rows.toList().map { it["invite_code"].asText() }.toSet())
        assertEquals(NOW.toString(), rows.first { it["invite_code"].asText() == collectingBoundary }["expires_at"].asText())
        assertEquals(NOW.toString(), rows.first { it["invite_code"].asText() == closedBoundary }["expires_at"].asText())
        assertEquals(
            NOW.plus(Duration.ofDays(28)).toString(),
            rows.first { it["invite_code"].asText() == closedRecent }["expires_at"].asText(),
        )
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM meeting_rooms", Int::class.java))
    }

    @Test
    fun `name search is trimmed case insensitive literal substring combined with role and date`() {
        val owner = login()
        val target = room(owner, "HOST", purpose = "Tea 100%_PLAN\\done")
        room(owner, "MEMBER", purpose = "Tea 100%_PLAN\\done")
        room(owner, "HOST", purpose = "Tea 100xxPLAN\\done")
        room(owner, "HOST", purpose = "unrelated")
        assertEquals(listOf(target), invites(response("query" to "  100%_plan\\done  ", "role" to "HOST", "created_from" to "2026-10-17")))
        assertEquals(4, invites(response("query" to "  ")).size)
        assertEquals(emptyList(), invites(response("query" to "' OR 1=1 --")))
    }

    @Test
    fun `cursor pages resolve creation ties without duplicates when newer room arrives`() {
        val owner = login()
        val ids =
            listOf("00000000-0000-0000-0000-000000000001", "70000000-0000-0000-0000-000000000001", "f0000000-0000-0000-0000-000000000001")
        val expected = ids.map { room(owner, "MEMBER", id = UUID.fromString(it)) }
        val first = response("limit" to "2")
        assertEquals(expected.take(2), invites(first))
        assertTrue(first["has_more"].asBoolean())
        val cursor = first["next_cursor"].asText()
        assertTrue(cursor.isNotBlank())
        room(owner, "HOST", NOW.minusSeconds(1))
        val second = response("limit" to "2", "cursor" to cursor)
        assertEquals(expected.drop(2), invites(second))
        assertFalse(second["has_more"].asBoolean())
        assertTrue(second["next_cursor"].isNull)
        caller = login()
        assertEquals(emptyList(), invites(response("cursor" to cursor)))
    }

    @Test
    fun `default page limit is twenty and explicit maximum is one hundred`() {
        val owner = login()
        repeat(21) { room(owner, "HOST") }
        val first = response()
        assertEquals(20, first["rooms"].size())
        assertTrue(first["has_more"].asBoolean())
        assertEquals(21, response("limit" to "100")["rooms"].size())
    }

    @Test
    fun `invalid role dates search limits and cursor return validation problem`() {
        login()
        val invalid =
            listOf(
                listOf("role" to "ADMIN"),
                listOf("role" to "host"),
                listOf("created_from" to "2026-02-30"),
                listOf("created_to" to "tomorrow"),
                listOf("created_from" to "2026-10-17", "created_to" to "2026-10-16"),
                listOf("query" to "x".repeat(101)),
                listOf("limit" to "0"),
                listOf("limit" to "101"),
                listOf("limit" to "NaN"),
                listOf("cursor" to "not-a-valid-cursor"),
                listOf("cursor" to "x".repeat(1025)),
            )
        for (parameters in invalid) {
            val request = get(PATH)
            parameters.forEach { (key, value) -> request.param(key, value) }
            mvc.perform(request).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
    }

    @Test
    fun `OpenAPI documents query contract roles paging and validation response`() {
        val result =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.paths['/api/account/rooms'].get.responses['400']").exists())
                .andExpect(jsonPath("$.components.schemas.AccountRoomResponse.properties.role").exists())
                .andExpect(jsonPath("$.components.schemas.AccountRoomsResponse.properties.has_more").exists())
                .andExpect(jsonPath("$.components.schemas.AccountRoomsResponse.properties.next_cursor").exists())
                .andReturn()
                .response.contentAsString
        val parameters =
            mapper
                .readTree(result)["paths"][PATH]["get"]["parameters"]
                .toList()
                .map { it["name"].asText() }
                .toSet()
        assertTrue(parameters.containsAll(setOf("role", "created_from", "created_to", "query", "limit", "cursor")))
    }

    private fun response(vararg parameters: Pair<String, String>): JsonNode {
        val request = get(PATH)
        parameters.forEach { (key, value) -> request.param(key, value) }
        return mapper.readTree(
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
    }

    private fun invites(result: JsonNode): List<String> = result["rooms"].toList().map { it["invite_code"].asText() }

    private fun login(): AccountPrincipal {
        val principal = AccountPrincipal(UUID.randomUUID(), "synthetic profile")
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,? ,now())", principal.accountId, principal.displayName)
        caller = principal
        return principal
    }

    private fun room(
        owner: AccountPrincipal?,
        role: String,
        createdAt: Instant = NOW.minusSeconds(30),
        closedAt: Instant? = null,
        purpose: String = "synthetic meeting",
        id: UUID = UUID.randomUUID(),
    ): String {
        val invite =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer
                    .allocate(16)
                    .putLong(id.mostSignificantBits)
                    .putLong(id.leastSignificantBits)
                    .array(),
            )
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at)
            VALUES (?,?,?,'EITHER','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,?,?,?,?)""",
            id,
            invite,
            purpose,
            if (closedAt == null) "COLLECTING" else "CLOSED",
            if (closedAt == null) null else "MANUAL",
            closedAt?.let(Timestamp::from),
            Timestamp.from(createdAt),
        )
        val guest = if (owner == null) UUID.randomUUID() else null
        if (guest != null) {
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES (?,?,?,?)",
                guest,
                guest.toString(),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(86400)),
            )
        }
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,'synthetic profile',now())",
            UUID.randomUUID(),
            id,
            guest,
            owner?.accountId,
            role,
        )
        return invite
    }

    @TestConfiguration
    class HistoryClockConfiguration {
        @Bean
        @Primary
        fun historyClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private const val PATH = "/api/account/rooms"
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
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
