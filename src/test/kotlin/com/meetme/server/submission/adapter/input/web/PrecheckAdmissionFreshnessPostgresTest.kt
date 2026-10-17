package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.MeetingRoomId
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent PS02/03/05-12/19/21-24/31 and approved D1/D2, real PG + HTTP + deterministic synthetic provider. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(PrecheckAdmissionFreshnessPostgresTest.IsolatedSecurity::class)
class PrecheckAdmissionFreshnessPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var finalProcessor: GeminiBatchProcessor

    @MockitoBean private lateinit var parser: NaturalLanguageParserPort

    @MockitoSpyBean private lateinit var proofs: GuestOwnershipProofPort

    @Autowired private lateinit var bindings: PrecheckOwnershipBindingPort

    @Autowired private lateinit var checks: PrecheckRepository

    @Autowired private lateinit var participants: ParticipantRepository

    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        REQUESTS.clear()
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @ParameterizedTest
    @ValueSource(strings = ["PROMPT", "LABEL", "ID"])
    fun `PS08 bounded question fields reject embedded NUL before publication`(field: String) {
        val room = room()
        val owner = join(room)
        ACTION.set {
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                if (field == "PROMPT") "오전\u0000오후?" else "오전과 오후 중 어느 뜻인가요?",
                "8시",
                listOf(
                    PrecheckChoice(
                        if (field ==
                            "ID"
                        ) {
                            "A\u0000M"
                        } else {
                            "AM"
                        },
                        if (field == "LABEL") "오전\u00008시" else "오전 8시",
                        "내일 오전 8시 온라인 가능",
                    ),
                    PrecheckChoice("PM", "오후 8시", "내일 오후 8시 온라인 가능"),
                ),
            )
        }
        create(room, owner, AMBIGUOUS)
        assertTrue(worker.runOne())
        val result = current(room, owner)
        assertEquals("FAILED", result.path("check").path("status").stringValue())
        assertEquals("INVALID_RESPONSE", result.path("check").path("failure_code").stringValue())
        assertTrue(result.path("check").path("question").isNull)
        assertEquals(AMBIGUOUS, result.path("candidate").path("raw_text").stringValue())
        assertFalse(result.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
        assertEquals(1, REQUESTS.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SESSION", "ROOM_DEADLINE"])
    fun `PS19 queued claim cannot start provider after admission wait crosses auth or room deadline`(boundary: String) {
        val room = room()
        val owner = join(room)
        if (boundary == "SESSION") {
            jdbc.update(
                "UPDATE guest_browser_sessions SET expires_at=? WHERE id=(SELECT guest_session_id FROM participants WHERE id=?)",
                Timestamp.from(START.plusSeconds(1)),
                owner.id,
            )
        } else {
            jdbc.update(
                "UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE id=?",
                Timestamp.from(START.plusSeconds(1)),
                room.id,
            )
        }
        val created = create(room, owner, RAW)
        val checkId = UUID.fromString(created.path("check").path("id").stringValue())
        val blocker = requireNotNull(jdbc.dataSource).connection
        val executor = Executors.newSingleThreadExecutor()
        var pending: java.util.concurrent.Future<Boolean>? = null
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(710715016)") }
            pending = executor.submit<Boolean> { worker.runOne() }
            awaitAdmission()
            NOW.set(START.plusSeconds(2))
            blocker.commit()
            requireNotNull(pending).get(10, TimeUnit.SECONDS)
            assertEquals(0, REQUESTS.size, "fresh permission and deadline checks must precede physical provider admission")
            assertEquals(
                null,
                jdbc.queryForObject("SELECT started_at FROM precheck_executions WHERE check_id=?", Timestamp::class.java, checkId),
            )
            assertEquals(0, checks.callCount(room.id, owner.id))
            assertEquals(0, count("submission_versions"))
            assertEquals(0, count("submission_heads"))
            assertEquals(
                RAW,
                jdbc.queryForObject("SELECT raw_text FROM precheck_candidates WHERE participant_id=?", String::class.java, owner.id),
            )
            val terminal = requireNotNull(checks.find(checkId))
            assertTrue(terminal.status.name in setOf("SUPERSEDED", "CLOSED_BEFORE_COMPLETION", "TIMED_OUT"))
            assertEquals(
                null,
                jdbc.queryForObject(
                    "SELECT accepted_version_id FROM precheck_candidates WHERE participant_id=?",
                    UUID::class.java,
                    owner.id,
                ),
            )
        } finally {
            blocker.rollback()
            blocker.close()
            pending?.cancel(true)
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SESSION", "ROOM_DEADLINE"])
    fun `PS19 create revalidates expired guest or closed deadline after admission wait`(boundary: String) {
        val room = room()
        val owner = join(room)
        if (boundary == "SESSION") {
            jdbc.update(
                "UPDATE guest_browser_sessions SET expires_at=? WHERE id=(SELECT guest_session_id FROM participants WHERE id=?)",
                Timestamp.from(START.plusSeconds(1)),
                owner.id,
            )
        } else {
            jdbc.update(
                "UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE id=?",
                Timestamp.from(START.plusSeconds(1)),
                room.id,
            )
        }
        val blocker = requireNotNull(jdbc.dataSource).connection
        val executor = Executors.newSingleThreadExecutor()
        var pending: java.util.concurrent.Future<MvcResult>? = null
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(710715016)") }
            pending = executor.submit<MvcResult> { performCreate(room, owner, RAW) }
            awaitAdmission()
            NOW.set(START.plusSeconds(2))
            blocker.commit()
            val response = requireNotNull(pending).get(10, TimeUnit.SECONDS)
            assertEquals(if (boundary == "SESSION") 401 else 409, response.response.status)
            if (boundary == "ROOM_DEADLINE") assertEquals("ROOM_CLOSED", tree(response).path("code").stringValue())
            assertEquals(0, REQUESTS.size)
            assertEquals(0, count("precheck_candidates"))
            assertEquals(0, count("precheck_checks"))
            assertEquals(0, count("precheck_operations"))
            assertEquals(0, count("submission_versions"))
        } finally {
            blocker.rollback()
            blocker.close()
            pending?.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun awaitAdmission() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            val waiters =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=710715016 AND NOT granted",
                    Int::class.java,
                )
                    ?: 0
            if (waiters == 1) return
            Thread.sleep(10)
        }
        error("actual command/worker did not reach the PG admission barrier before fake-clock change")
    }

    private fun stubProof(
        room: Room,
        credential: String?,
        value: GuestOwnershipProof,
    ) {
        Mockito.doReturn(value).`when`(proofs).resolve(MeetingRoomId(room.id), credential, false)
        Mockito.doReturn(value).`when`(proofs).resolve(MeetingRoomId(room.id), credential, true)
    }

    private fun question() =
        PrecheckProviderResult(
            PrecheckVerdict.NEEDS_CHOICE,
            "오전과 오후 중 어느 뜻인가요?",
            "8시",
            listOf(PrecheckChoice("AM", "오전 8시", "내일 오전 8시 온라인 가능"), PrecheckChoice("PM", "오후 8시", "내일 오후 8시 온라인 가능")),
        )

    private fun close(room: Room) {
        mvc
            .perform(
                post(
                    "/api/rooms/${room.invite}/close",
                ).header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(AccountPrincipal(room.host.id, "합성 계정"))}")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirm_early\":true}"),
            ).andExpect(status().isOk)
    }

    private fun frozenTexts(): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM submission_batch_items i JOIN submission_versions v ON v.id=i.submission_version_id",
                String::class.java,
            ).map { requireNotNull(it) }

    private fun mutate(
        room: Room,
        guest: Guest,
        snapshot: JsonNode,
        action: String,
        extra: Map<String, Any> = emptyMap(),
        key: UUID = UUID.randomUUID(),
        expectedStatus: Int = 202,
    ): JsonNode {
        val result = mutateResult(room, guest, snapshot, action, extra, key)
        assertEquals(expectedStatus, result.response.status, result.response.contentAsString)
        return tree(result)
    }

    private fun mutateResult(
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

    private fun withBlockedProvider(actionWhileProviderBlocked: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ACTION.set {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PrecheckProviderResult(PrecheckVerdict.CLEAR)
        }
        try {
            val future = executor.submit<Boolean> { worker.runOne() }
            check(entered.await(10, TimeUnit.SECONDS)) { "provider was not called before barrier timeout" }
            actionWhileProviderBlocked()
            release.countDown()
            assertTrue(requireNotNull(future).get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
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
                REQUESTS += request
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
        private const val AMBIGUOUS = "내일 8시 온라인 가능"
        private const val RAW = "외부 노출 금지 합성 원문 107"
        private const val PASSWORD = "synthetic-password-107"
        private val REQUESTS = CopyOnWriteArrayList<PrecheckProviderRequest>()
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
