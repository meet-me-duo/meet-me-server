package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent #128 public HTTP cancellation contract; synthetic provider, actual PostgreSQL, no external calls. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(Issue128CancelledQuestionHttpPostgresTest.IsolatedSecurity::class)
class Issue128CancelledQuestionHttpPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker
    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        ACTION.set { question() }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `CQ01 cancelled question is absent from mutation and reload while private history remains`() {
        val room = room()
        val guest = join(room)
        val pending = awaitingQuestion(room, guest)
        val response = mutate(room, guest, pending, "cancel")
        assertEquals(200, response.response.status)
        val cancelled = tree(response)
        assertEquals("CANCELLED", cancelled.path("check").path("status").stringValue())
        assertEquals(1, historyQuestions(guest))
        assertEquals(0, count("submission_versions"))
        assertFalse(cancelled.path("submission_complete").booleanValue())
        assertTrue(cancelled.path("accepted").isNull)
        assertEquals(RAW, cancelled.path("candidate").path("raw_text").stringValue())
        assertTrue(cancelled.path("check").path("question").isNull, "CANCELLED must not expose an actionable current question")
        assertTrue(current(room, guest).path("check").path("question").isNull, "reload must retain the cancelled projection")
    }

    @Test
    fun `CQ02 fresh CREATE after question cancellation completes independently of the retained old question`() {
        val room = room()
        val guest = join(room)
        val old = awaitingQuestion(room, guest)
        assertEquals(200, mutate(room, guest, old, "cancel").response.status)
        val next = create(room, guest, "토요일 오전 10시 온라인 가능", revision = 1)
        assertEquals(2, next.path("candidate").path("revision").intValue())
        assertEquals("QUEUED", next.path("check").path("status").stringValue())
        assertTrue(next.path("check").path("question").isNull)
        assertFalse(next.path("submission_complete").booleanValue())
        assertEquals(1, historyQuestions(guest))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        assertTrue(worker.runOne())
        val accepted = current(room, guest)
        assertTrue(accepted.path("submission_complete").booleanValue())
        assertEquals("토요일 오전 10시 온라인 가능", accepted.path("accepted").path("raw_text").stringValue())
        assertEquals(1, count("submission_versions"))
        assertEquals(
            409,
            mutate(
                room,
                guest,
                old,
                "answer",
                mapOf(
                    "question_id" to
                        old
                            .path("check")
                            .path("question")
                            .path("id")
                            .stringValue(),
                    "choice_id" to "AM",
                ),
            ).response.status,
        )
    }

    @Test
    fun `CQ03 identical cancel replay is idempotent and cannot cancel a newer CREATE`() {
        val room = room()
        val guest = join(room)
        val old = awaitingQuestion(room, guest)
        val key = UUID.randomUUID()
        assertEquals(200, mutate(room, guest, old, "cancel", key = key).response.status)
        val first = current(room, guest)
        assertEquals(200, mutate(room, guest, old, "cancel", key = key).response.status)
        assertEquals(first.path("check").path("version"), current(room, guest).path("check").path("version"))
        create(room, guest, "새 원문 토요일 오후 2시", revision = 1)
        val replay = mutate(room, guest, old, "cancel", key = key)
        assertEquals(200, replay.response.status)
        val latest = tree(replay)
        assertEquals("QUEUED", latest.path("check").path("status").stringValue())
        assertEquals(2, latest.path("candidate").path("revision").intValue())
        assertEquals("CANCELLED", latest.path("operation_outcome").path("status").stringValue())
        assertEquals(2, count("precheck_candidates"))
        assertEquals(1, historyQuestions(guest))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM precheck_operations WHERE participant_id=? AND kind='CANCEL'",
                Int::class.java,
                guest.id,
            ),
        )
    }

    @Test
    fun `CQ04 cancellation never refunds the three question publication limit or its stored history`() {
        val room = room()
        val guest = join(room)
        repeat(3) { revision ->
            create(room, guest, "$RAW $revision", revision)
            assertTrue(worker.runOne())
            val pending = current(room, guest)
            assertEquals("NEEDS_CHOICE", pending.path("check").path("status").stringValue())
            assertEquals(200, mutate(room, guest, pending, "cancel").response.status)
            assertEquals(revision + 1, historyQuestions(guest))
        }
        create(room, guest, "$RAW 마지막", 3)
        assertTrue(worker.runOne())
        val last = current(room, guest)
        assertEquals("NEEDS_EDIT", last.path("check").path("status").stringValue())
        assertEquals("QUESTION_LIMIT_REACHED", last.path("check").path("failure_code").stringValue())
        assertTrue(last.path("check").path("question").isNull)
        assertEquals(3, historyQuestions(guest))
        assertEquals(4, count("precheck_executions"))
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `CQ05 another participant cannot cancel or replay the owners question`() {
        val room = room()
        val guest = join(room)
        val other = join(room, "다른 합성 참여자")
        val pending = awaitingQuestion(room, guest)
        val key = UUID.randomUUID()
        val denied = mutate(room, other, pending, "cancel", key = key)
        assertEquals(409, denied.response.status)
        assertFalse(denied.response.contentAsString.contains(RAW))
        assertFalse(denied.response.contentAsString.contains("오전과 오후"))
        assertEquals("NEEDS_CHOICE", current(room, guest).path("check").path("status").stringValue())
        assertEquals(200, mutate(room, guest, pending, "cancel", key = key).response.status)
        assertEquals(409, mutate(room, other, pending, "cancel", key = key).response.status)
        assertTrue(current(room, other).path("candidate").isNull)
        assertEquals(1, historyQuestions(guest))
    }

    @Test
    fun `CQ06 stale revision or check version cannot cancel the current question`() {
        val room = room()
        val guest = join(room)
        val pending = awaitingQuestion(room, guest)
        listOf(mapOf("expected_revision" to 0), mapOf("expected_check_version" to 1L)).forEach { stale ->
            assertEquals(409, mutate(room, guest, pending, "cancel", stale).response.status)
            val unchanged = current(room, guest)
            assertEquals(pending.path("check"), unchanged.path("check"))
            assertEquals(1, historyQuestions(guest))
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM precheck_operations WHERE kind='CANCEL'", Int::class.java))
    }

    @Test
    fun `CQ07 room closure fences cancellation and hides an unanswered question without deleting history`() {
        val room = room()
        val guest = join(room)
        val pending = awaitingQuestion(room, guest)
        mvc
            .perform(
                post("/api/rooms/${room.invite}/close")
                    .header(HttpHeaders.AUTHORIZATION, room.host.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        assertEquals(409, mutate(room, guest, pending, "cancel").response.status)
        val closed = current(room, guest)
        assertEquals("CLOSED", closed.path("room").path("collection_status").stringValue())
        assertTrue(closed.path("check").path("question").isNull)
        assertFalse(closed.path("submission_complete").booleanValue())
        assertEquals(1, historyQuestions(guest))
        assertEquals(0, count("submission_versions"))
    }

    private fun awaitingQuestion(
        room: Room,
        guest: Guest,
    ): JsonNode {
        create(room, guest, RAW)
        assertTrue(worker.runOne())
        return current(room, guest).also {
            assertEquals("NEEDS_CHOICE", it.path("check").path("status").stringValue())
            assertFalse(it.path("check").path("question").isNull)
        }
    }

    private fun historyQuestions(guest: Guest): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM precheck_checks k JOIN precheck_candidates c ON c.id=k.candidate_id WHERE c.participant_id=? AND k.question IS NOT NULL",
                Int::class.java,
                guest.id,
            ),
        )

    private fun mutate(
        room: Room,
        guest: Guest,
        snapshot: JsonNode,
        action: String,
        extra: Map<String, Any> = emptyMap(),
        key: UUID = UUID.randomUUID(),
    ): MvcResult {
        val payload =
            mapOf<String, Any>(
                "request_id" to key,
                "expected_revision" to snapshot.path("candidate").path("revision").intValue(),
                "expected_check_version" to snapshot.path("check").path("version").longValue(),
            ) + extra
        return mvc
            .perform(
                post("${path(room)}/${snapshot.path("check").path("id").stringValue()}/$action")
                    .cookie(guest.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)),
            ).andReturn()
    }

    private fun question() =
        PrecheckProviderResult(
            PrecheckVerdict.NEEDS_CHOICE,
            "오전과 오후 중 어느 뜻인가요?",
            "8시",
            listOf(
                PrecheckChoice("AM", "오전 8시", "토요일 오전 8시 온라인 가능"),
                PrecheckChoice("PM", "오후 8시", "토요일 오후 8시 온라인 가능"),
            ),
        )

    private fun account(): Account {
        val principal = AccountPrincipal(UUID.randomUUID(), "합성 계정")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            Timestamp.from(NOW.get()),
        )
        return Account(principal.accountId, "Bearer ${tokens.issue(principal)}")
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

    private fun path(room: Room) = "/api/rooms/${room.invite}/submission/checks"

    private fun tree(result: MvcResult): JsonNode = json.readTree(result.response.contentAsByteArray)

    private fun cookie(result: MvcResult): Cookie {
        val header = requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE))
        return Cookie("meet_me_guest", header.substringAfter('=').substringBefore(';'))
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private data class Account(
        val id: UUID,
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
        fun precheckSyntheticProvider(): PreSubmissionCheckProvider =
            PreSubmissionCheckProvider { request ->
                ACTION.get().invoke(request)
            }

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
        private const val RAW = "토요일 8시 온라인 가능"
        private val ACTION =
            AtomicReference<
                (
                    PrecheckProviderRequest,
                ) -> PrecheckProviderResult,
            >({ PrecheckProviderResult(PrecheckVerdict.CLEAR) })
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
