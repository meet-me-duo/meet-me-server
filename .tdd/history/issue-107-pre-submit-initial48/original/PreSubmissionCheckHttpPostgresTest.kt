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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
@Import(PreSubmissionCheckHttpPostgresTest.IsolatedSecurity::class)
class PreSubmissionCheckHttpPostgresTest {
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

    @Test
    fun `PS01 raw 저장은 accepted 제출과 인원 마감이 아니며 GET은 쓰기를 만들지 않는다`() {
        val room = room(expected = 2)
        val first = join(room)
        val second = join(room)
        val one = create(room, first, RAW)
        val two = create(room, second, "다른 합성 원문")
        assertPending(one, RAW, 1)
        assertPending(two, "다른 합성 원문", 1)
        assertEquals(0, count("submission_versions"), "accepted-only history must stay empty until CLEAR publication")
        assertEquals(0, count("submission_heads"))
        assertEquals(2, count("precheck_candidates"), "both raw inputs must be durable before checking")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM precheck_candidates WHERE raw_text=?", Int::class.java, RAW))
        assertEquals(0, count("submission_batch_items"))
        assertEquals(0, count("coordination_runs"))
        repeat(3) { assertPending(current(room, first), RAW, 1) }
        roomView(room).andExpect(jsonPath("$.collection_status").value("COLLECTING"))
    }

    @Test
    fun `PS09 응답 유실 후 같은 key body는 candidate와 check 하나로 재생한다`() {
        val room = room()
        val guest = join(room)
        val key = UUID.randomUUID()
        val first = create(room, guest, RAW, key = key)
        val replay = create(room, guest, RAW, key = key)
        assertEquals(first.path("candidate"), replay.path("candidate"))
        assertEquals(first.path("check").path("id"), replay.path("check").path("id"))
        assertPending(current(room, guest), RAW, 1)
        assertEquals(1, count("precheck_candidates"))
        assertEquals(1, count("precheck_checks"))
    }

    @Test
    fun `PS09 동시 같은 request는 202 두 응답과 동일 check 하나로 수렴한다`() {
        val room = room()
        val guest = join(room)
        val key = UUID.randomUUID()
        val results = concurrently({ performCreate(room, guest, RAW, key = key) }, { performCreate(room, guest, RAW, key = key) })
        assertEquals(listOf(202, 202), results.map { it.response.status })
        assertEquals(tree(results[0]).path("check").path("id"), tree(results[1]).path("check").path("id"))
        assertPending(current(room, guest), RAW, 1)
    }

    @Test
    fun `PS10 동일 key 다른 body는 409이며 최초 원문을 보존한다`() {
        val room = room()
        val guest = join(room)
        val key = UUID.randomUUID()
        create(room, guest, RAW, key = key)
        val result = performCreate(room, guest, "다른 탭 원문", key = key)
        assertEquals(409, result.response.status)
        assertPending(current(room, guest), RAW, 1)
    }

    @Test
    fun `PS10 같은 candidate revision의 두 탭은 하나만 쓰고 패자는 409다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        val results = concurrently({ performCreate(room, guest, "탭 A", revision = 1) }, { performCreate(room, guest, "탭 B", revision = 1) })
        assertEquals(listOf(202, 409), results.map { it.response.status }.sorted())
        val snapshot = current(room, guest)
        assertEquals(2, snapshot.path("candidate").path("revision").intValue())
        assertTrue(snapshot.path("candidate").path("raw_text").stringValue() in setOf("탭 A", "탭 B"))
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS10 과거 request 재생은 최신 candidate를 되돌리지 않는다`() {
        val room = room()
        val guest = join(room)
        val key = UUID.randomUUID()
        create(room, guest, RAW, key = key)
        create(room, guest, "최신 원문", revision = 1)
        val replay = create(room, guest, RAW, key = key)
        assertEquals("최신 원문", replay.path("candidate").path("raw_text").stringValue())
        assertEquals(2, replay.path("candidate").path("revision").intValue())
        assertEquals("최신 원문", current(room, guest).path("candidate").path("raw_text").stringValue())
    }

    @Test
    fun `PS13 scheduler 전 deadline 정확 같은 시각에는 같은 원문 재제출도 거부한다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        jdbc.update(
            "UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE id=?",
            Timestamp.from(START.plusSeconds(60)),
            room.id,
        )
        NOW.set(START.plusSeconds(60))
        assertEquals(409, performCreate(room, guest, RAW, revision = 1).response.status)
        assertEquals(1, current(room, guest).path("candidate").path("revision").intValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS20 ECMAScript trim과 내부공백 U0085는 원문을 임의 변환하지 않는다`() {
        val room = room()
        val guest = join(room)
        val original = "\uFEFF\u00A0\t😀  내부\u0085 공백\u3000"
        val expected = "😀  내부\u0085 공백"
        assertPending(create(room, guest, original), expected, 1)
        assertEquals(expected, current(room, guest).path("candidate").path("raw_text").stringValue())
    }

    @Test
    fun `PS20 emoji 500개 허용과 501개 거부는 UTF16 길이가 아닌 코드포인트 기준이다`() {
        val room = room()
        val guest = join(room)
        val boundary = "😀".repeat(500)
        assertPending(create(room, guest, boundary), boundary, 1)
        val rejected = performCreate(room, guest, "😀".repeat(501), revision = 1)
        assertEquals(400, rejected.response.status)
        assertEquals("SUBMISSION_TEXT_TOO_LONG", tree(rejected).path("code").stringValue())
        assertPending(current(room, guest), boundary, 1)
    }

    @Test
    fun `PS20 빈 원문 거부는 candidate나 accepted를 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        val result = performCreate(room, guest, " \uFEFF\u3000\n")
        assertEquals(400, result.response.status)
        assertEquals("SUBMISSION_INPUT_REQUIRED", tree(result).path("code").stringValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS20 최신 candidate 합계 10000 허용 10001 거부와 수정 교체 합산을 유지한다`() {
        val room = room()
        val owners = (1..21).map { join(room, name = "합성 $it") }
        owners.take(20).forEach { create(room, it, "가".repeat(500)) }
        val result = performCreate(room, owners.last(), "나")
        assertEquals(409, result.response.status)
        assertEquals("SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED", tree(result).path("code").stringValue())
        create(room, owners.first(), "다".repeat(499), revision = 1)
        create(room, owners.last(), "나")
        assertEquals(0, count("submission_versions"))
        roomView(room).andExpect(jsonPath("$.collection_status").value("COLLECTING"))
    }

    @Test
    fun `PS27 신규방은 PRE_SUBMIT_V1이며 구 PUT으로 확인을 우회하지 못한다`() {
        val room = room()
        val guest = join(room)
        roomView(room).andExpect(jsonPath("$.submission_protocol").value("PRE_SUBMIT_V1"))
        mvc
            .perform(
                put("/api/rooms/${room.invite}/submission")
                    .cookie(guest.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("raw_text" to RAW))),
            ).andExpect(status().isConflict)
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("coordination_runs"))
    }

    @Test
    fun `PS27 migration 이전 LEGACY 방은 기존 PUT 동작을 유지한다`() {
        val room = room()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE id=?", room.id)
        val guest = join(room)
        mvc
            .perform(
                put("/api/rooms/${room.invite}/submission")
                    .cookie(guest.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("raw_text" to RAW))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(1))
        assertEquals(1, count("submission_versions"))
        assertEquals(409, performCreate(room, guest, RAW).response.status)
    }

    @Test
    fun `PS16 HOST와 동명이인 공개 room은 타인 candidate와 질문을 조회하지 못한다`() {
        val room = room()
        val owner = join(room, name = "같은 이름")
        val stranger = join(room, name = "같은 이름")
        create(room, owner, RAW)
        val hostRead = mvc.perform(get("${path(room)}/current").header(HttpHeaders.AUTHORIZATION, room.host.bearer)).andReturn()
        val strangerRead = mvc.perform(get("${path(room)}/current").cookie(stranger.cookie)).andReturn()
        assertEquals(200, hostRead.response.status)
        assertTrue(tree(hostRead).path("candidate").isNull)
        assertEquals(200, strangerRead.response.status)
        assertTrue(tree(strangerRead).path("candidate").isNull)
        assertFalse(hostRead.response.contentAsString.contains(RAW))
        assertFalse(strangerRead.response.contentAsString.contains(RAW))
        val public =
            mvc
                .perform(get("/api/rooms/${room.invite}"))
                .andReturn()
                .response.contentAsString
        assertFalse(public.contains(RAW))
        assertFalse(public.contains("raw_text"))
    }

    @Test
    fun `PS17 실제 정답 암호로 발급한 VERIFIED proof는 본인 현재 candidate만 복원한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        create(room, owner, RAW)
        val restored = reaccess(room, owner, PASSWORD, "VERIFIED_GUEST")
        assertPending(current(room, restored), RAW, 1)
        val invalid = mvc.perform(reaccessRequest(room, owner, "틀린 암호")).andReturn()
        assertEquals(403, invalid.response.status)
        assertFalse(invalid.response.contentAsString.contains(RAW))
    }

    @Test
    fun `PS18 실제 REPLACE_ONLY proof는 모든 신규 확인 GET 명령 replay를 거부한다`() {
        val room = room()
        val owner = join(room)
        val snapshot = create(room, owner, RAW)
        val checkId = snapshot.path("check").path("id").stringValue()
        val restricted = reaccess(room, owner, null, "REPLACE_ONLY")
        val read = mvc.perform(get("${path(room)}/current").cookie(restricted.cookie)).andReturn()
        assertEquals(403, read.response.status)
        assertEquals(403, performCreate(room, restricted, "침범").response.status)
        listOf("answer", "retry", "cancel").forEach { action ->
            val result =
                mvc
                    .perform(
                        post("${path(room)}/$checkId/$action")
                            .cookie(restricted.cookie)
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(
                                    mapOf(
                                        "request_id" to UUID.randomUUID(),
                                        "expected_revision" to 1,
                                        "expected_check_version" to 1,
                                    ),
                                ),
                            ),
                    ).andReturn()
            assertEquals(403, result.response.status, action)
            assertFalse(result.response.contentAsString.contains(RAW))
        }
        assertPending(current(room, owner), RAW, 1)
    }

    @Test
    fun `PS18 무효 Bearer는 유효 게스트 쿠키나 idempotency replay보다 먼저 401이다`() {
        val room = room()
        val owner = join(room)
        val key = UUID.randomUUID()
        create(room, owner, RAW, key = key)
        val result =
            mvc
                .perform(createRequest(room, owner, RAW, key = key).header(HttpHeaders.AUTHORIZATION, "Bearer invalid.synthetic"))
                .andReturn()
        assertEquals(401, result.response.status)
        assertTrue(
            result.response
                .getHeader(HttpHeaders.CACHE_CONTROL)
                .orEmpty()
                .contains("no-store"),
        )
        val read =
            mvc
                .perform(get("${path(room)}/current").cookie(owner.cookie).header(HttpHeaders.AUTHORIZATION, "Bearer invalid.synthetic"))
                .andReturn()
        assertEquals(401, read.response.status)
        assertTrue(
            read.response
                .getHeader(HttpHeaders.CACHE_CONTROL)
                .orEmpty()
                .contains("no-store"),
        )
        assertFalse(read.response.contentAsString.contains(RAW))
    }

    @Test
    fun `PS16 다른 방 쿠키와 UUID 추측은 현재 원문을 노출하지 않는다`() {
        val target = room()
        val owner = join(target)
        val snapshot = create(target, owner, RAW)
        val unrelated = join(room())
        assertEquals(
            403,
            mvc
                .perform(get("${path(target)}/current").cookie(unrelated.cookie))
                .andReturn()
                .response.status,
        )
        val attack =
            mvc
                .perform(
                    post("${path(target)}/${snapshot.path("check").path("id").stringValue()}/cancel")
                        .cookie(unrelated.cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "expected_revision" to 1,
                                    "expected_check_version" to 1,
                                ),
                            ),
                        ),
                ).andReturn()
        assertEquals(403, attack.response.status)
        assertFalse(attack.response.contentAsString.contains(RAW))
    }

    @Test
    fun `PD06 실제 blind 대체는 신규방 accepted를 우회하지 않고 동일 grant 동시수정 하나만 허용한다`() {
        val room = room()
        val owner = join(room)
        create(room, owner, RAW)
        val restricted = reaccess(room, owner, null, "REPLACE_ONLY")

        fun blind(): MvcResult =
            mvc
                .perform(
                    put("/api/rooms/${room.invite}/guest-records/${owner.id}/submission")
                        .cookie(restricted.cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapOf("raw_text" to "새 blind 합성 원문"))),
                ).andReturn()
        val responses = concurrently(::blind, ::blind)
        assertEquals(listOf(200, 409), responses.map { it.response.status }.sorted())
        responses.forEach { assertFalse(it.response.contentAsString.contains(RAW)) }
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("coordination_runs"))
        assertPending(current(room, owner), "새 blind 합성 원문", 2)
        assertEquals(
            403,
            mvc
                .perform(get("${path(room)}/current").cookie(restricted.cookie))
                .andReturn()
                .response.status,
        )
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
