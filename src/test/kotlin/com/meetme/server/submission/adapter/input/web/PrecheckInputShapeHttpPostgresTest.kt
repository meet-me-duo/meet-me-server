package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PS01/09/10/13/16-20/27: public HTTP behavior, real PG, actual RSA and guest proof issuance.
 * Synthetic payloads only. No provider, OAuth, Redis, or production network calls.
 */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(PrecheckInputShapeHttpPostgresTest.IsolatedSecurity::class)
class PrecheckInputShapeHttpPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "true", "[]", "null", "{}", "MISSING"])
    fun `PS20 raw_text는 JSON 문자열만 허용하고 강제 변환으로 candidate를 만들지 않는다`(literal: String) {
        val room = room()
        val owner = join(room)
        val rawPart = if (literal == "MISSING") "" else ",\"raw_text\":$literal"
        val payload = "{\"request_id\":\"${UUID.randomUUID()}\",\"expected_revision\":0$rawPart}"
        mvc
            .perform(
                post(path(room))
                    .cookie(owner.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
        assertEquals(0, count("precheck_candidates"))
        assertEquals(0, count("precheck_checks"))
        assertEquals(0, count("submission_versions"))
        assertTrue(current(room, owner).path("candidate").isNull)
    }

    @Test
    fun `PS20 숫자로 보이는 명시 문자열은 의미 추정 없이 원문으로 보존한다`() {
        val room = room()
        val owner = join(room)
        assertPending(create(room, owner, "123"), "123", 1)
        assertEquals("123", current(room, owner).path("candidate").path("raw_text").stringValue())
        assertEquals(1, count("precheck_candidates"))
        assertEquals(0, count("submission_versions"))
    }

    private fun assertPending(
        node: JsonNode,
        raw: String,
        revision: Int,
    ) {
        assertEquals(raw, node.path("candidate").path("raw_text").stringValue())
        assertEquals(revision, node.path("candidate").path("revision").intValue())
        assertTrue(node.path("accepted").isNull)
        assertFalse(node.path("submission_complete").booleanValue())
        assertEquals("QUEUED", node.path("check").path("status").stringValue())
    }

    private fun account(): Account {
        val principal = AccountPrincipal(UUID.randomUUID(), "합성 계정")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            Timestamp.from(NOW.get()),
        )
        return Account("Bearer ${tokens.issue(principal)}")
    }

    private fun room(expected: Int? = null): Room {
        val host = account()
        val payload = linkedMapOf<String, Any>("purpose" to "독립 제출전 확인", "meeting_mode" to "EITHER", "manual_only" to (expected == null))
        if (expected != null) payload["expected_participants"] = expected
        val result =
            mvc
                .perform(
                    post("/api/rooms")
                        .header(HttpHeaders.AUTHORIZATION, host.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andExpect(status().isCreated)
                .andReturn()
        val invite = tree(result).path("invite_code").stringValue()
        val id = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite))
        return Room(id, invite, host)
    }

    private fun join(
        room: Room,
        name: String = "합성 게스트",
        password: String? = null,
    ): Guest {
        val payload = linkedMapOf<String, Any>("display_name" to name)
        if (password != null) payload["password"] = password
        val result =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/participants")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andExpect(status().isCreated)
                .andReturn()
        val cookie = cookie(result)
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT p.id FROM participants p JOIN guest_browser_sessions s ON p.guest_session_id=s.id WHERE p.room_id=? AND s.credential_digest=?",
                    UUID::class.java,
                    room.id,
                    credentials.digest(cookie.value),
                ),
            )
        return Guest(id, cookie)
    }

    private fun reaccess(
        room: Room,
        owner: Guest,
        password: String?,
        level: String,
    ): Guest {
        val result =
            mvc
                .perform(reaccessRequest(room, owner, password))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.level").value(level))
                .andReturn()
        return Guest(owner.id, cookie(result))
    }

    private fun reaccessRequest(
        room: Room,
        owner: Guest,
        password: String?,
    ): MockHttpServletRequestBuilder {
        val payload = linkedMapOf<String, Any>("participant_id" to owner.id)
        if (password != null) payload["password"] = password
        return post("/api/rooms/${room.invite}/guest-records/reaccess")
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload))
    }

    private fun create(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): JsonNode =
        tree(
            assertDoesNotThrow<MvcResult> {
                mvc
                    .perform(
                        createRequest(room, guest, text, revision, key),
                    ).andExpect(status().isAccepted)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andReturn()
            },
        )

    private fun performCreate(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): MvcResult = mvc.perform(createRequest(room, guest, text, revision, key)).andReturn()

    private fun createRequest(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletRequestBuilder =
        post(path(room))
            .cookie(guest.cookie)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("request_id" to key, "expected_revision" to revision, "raw_text" to text)))

    private fun current(
        room: Room,
        guest: Guest,
    ): JsonNode =
        tree(
            assertDoesNotThrow<MvcResult> {
                mvc
                    .perform(
                        get("${path(room)}/current").cookie(guest.cookie),
                    ).andExpect(status().isOk)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andReturn()
            },
        )

    private fun roomView(room: Room) = mvc.perform(get("/api/rooms/${room.invite}").header(HttpHeaders.AUTHORIZATION, room.host.bearer))

    private fun path(room: Room) = "/api/rooms/${room.invite}/submission/checks"

    private fun tree(result: MvcResult): JsonNode = json.readTree(result.response.contentAsByteArray)

    private fun cookie(result: MvcResult): Cookie {
        val header = requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE))
        return Cookie("meet_me_guest", header.substringAfter('=').substringBefore(';'))
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun concurrently(
        first: () -> MvcResult,
        second: () -> MvcResult,
    ): List<MvcResult> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf(first, second).map { action ->
                    executor.submit<MvcResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        action()
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private data class Account(
        val bearer: String,
    )

    private data class Room(
        val id: UUID,
        val invite: String,
        val host: Account,
    )

    private data class Guest(
        val id: UUID,
        val cookie: Cookie,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IsolatedSecurity {
        @Bean @Primary
        fun precheckTestClock(): Clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = NOW.get()
            }

        @Bean @Primary
        fun precheckTestTokens(clock: Clock): AccessTokenPort =
            Rs256AccessTokenAdapter(
                KEYS.private,
                mapOf("precheck-test" to KEYS.public),
                "precheck-test",
                clock,
                "https://synthetic.invalid",
                "meetme-web",
            )

        @Bean @Primary
        fun precheckTestLimiter(): RateLimitPort = RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) }
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val RAW = "외부 노출 금지 합성 원문 107"
        private const val PASSWORD = "synthetic-password-107"
        private val START = Instant.parse("2026-10-17T12:00:00Z")
        private val NOW = AtomicReference(START)
        private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.anonymous.cookie-secure") { true }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
