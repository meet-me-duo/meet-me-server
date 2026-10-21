package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredSubmissionResult
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
@Import(PreSubmissionPrivacyQueuePostgresTest.IsolatedSecurity::class)
class PreSubmissionPrivacyQueuePostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var finalProcessor: GeminiBatchProcessor

    @MockitoBean private lateinit var parser: NaturalLanguageParserPort

    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        REQUESTS.clear()
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `PS16 새 protocol HOST 미반영 조회도 타인 질문과 선택 후 accepted 원문을 노출하지 않는다`() {
        val room = room()
        val owner = join(room)
        ACTION.set { question() }
        create(room, owner, AMBIGUOUS)
        worker.runOne()
        val question = current(room, owner)
        val chosenText =
            question
                .path("check")
                .path("question")
                .path("options")
                .first()
                .path("proposed_text")
                .stringValue()
        mutate(
            room,
            owner,
            question,
            "answer",
            mapOf(
                "question_id" to
                    question
                        .path("check")
                        .path("question")
                        .path("id")
                        .stringValue(),
                "choice_id" to "AM",
            ),
        )
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        worker.runOne()
        mvc
            .perform(createRequest(room, owner, HOST_RAW).header(HttpHeaders.AUTHORIZATION, room.host.bearer))
            .andExpect(status().isAccepted)
        worker.runOne()
        close(room)
        rejectFrozenBatch(room)
        val result = unapplied(room)
        assertFalse(result.contains(AMBIGUOUS))
        assertFalse(result.contains(chosenText), "HOST is not authorized to see a participant's answer-derived raw text")
        assertFalse(result.contains("오전과 오후 중 어느 뜻인가요?"))
        assertTrue(result.contains(HOST_RAW), "the HOST may still inspect their own unapplied input")
    }

    @Test
    fun `PS27 LEGACY HOST 미반영 원문 공개의 기존 계약은 보존한다`() {
        val room = room()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE id=?", room.id)
        val owner = join(room)
        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/rooms/${room.invite}/submission")
                    .cookie(owner.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("raw_text" to RAW))),
            ).andExpect(status().isOk)
        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/rooms/${room.invite}/submission")
                    .header(HttpHeaders.AUTHORIZATION, room.host.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("raw_text" to HOST_RAW))),
            ).andExpect(status().isOk)
        close(room)
        rejectFrozenBatch(room)
        val result = unapplied(room)
        assertTrue(result.contains(RAW))
        assertTrue(result.contains(HOST_RAW))
    }

    @Test
    fun `PS22 전역 대기100 포화에도 101번째 원문은 FAILED_QUEUE로 보존하고 한자리 뒤 명시retry만 접수한다`() {
        var lastRoom: Room? = null
        var lastOwner: Guest? = null
        var last: JsonNode? = null
        var created = 0
        repeat(3) {
            val room = room()
            repeat(34) {
                if (created < 101) {
                    val owner = join(room)
                    last = create(room, owner, "큐 포화 합성 $created")
                    lastRoom = room
                    lastOwner = owner
                    created++
                }
            }
        }
        assertEquals(101, count("precheck_candidates"))
        assertEquals(0, REQUESTS.size)
        val failed = requireNotNull(last)
        assertEquals("FAILED_QUEUE", failed.path("check").path("status").stringValue())
        assertFalse(failed.path("submission_complete").booleanValue())
        val room = requireNotNull(lastRoom)
        val owner = requireNotNull(lastOwner)
        assertEquals("큐 포화 합성 100", current(room, owner).path("candidate").path("raw_text").stringValue())
        assertEquals(0, count("submission_versions"))
        worker.runOne()
        assertEquals(1, REQUESTS.size)
        val retry = mutate(room, owner, current(room, owner), "retry")
        assertEquals("QUEUED", retry.path("check").path("status").stringValue())
        assertEquals(101, count("precheck_candidates"), "retry is a new execution of the same source, not a new raw candidate")
    }

    @Test
    fun `PS20 pending 수정이 candidate 합계를 줄여도 accepted 10000 초과 CLEAR 승격은 거부한다`() {
        val room = room()
        val owners = (1..21).map { join(room, "합성 $it") }
        owners.take(20).forEach { owner ->
            create(room, owner, "가".repeat(500))
            worker.runOne()
        }
        assertEquals(20, count("submission_versions"))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT) }
        owners.take(20).forEach { owner ->
            create(room, owner, "나", revision = 1)
            worker.runOne()
        }
        assertEquals(20, count("submission_versions"), "pending candidates must not replace previously accepted text")
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        create(room, owners.last(), "다".repeat(500))
        worker.runOne()
        val snapshot = current(room, owners.last())
        assertEquals("다".repeat(500), snapshot.path("candidate").path("raw_text").stringValue())
        assertTrue(snapshot.path("accepted").isNull)
        assertEquals("NEEDS_EDIT", snapshot.path("check").path("status").stringValue())
        assertEquals("SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED", snapshot.path("check").path("failure_code").stringValue())
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertEquals(20, count("submission_versions"))
    }

    private fun rejectFrozenBatch(room: Room) {
        Mockito.`when`(parser.parse(anyValue())).thenAnswer { invocation ->
            val request = invocation.getArgument<NaturalLanguageBatchRequest>(0)
            NaturalLanguageBatchResult(
                request.inputs.map { input ->
                    StructuredSubmissionResult(
                        SubmissionVersionId(UUID.fromString(input.inputRef)),
                        emptyList(),
                        "UNSUPPORTED_CONDITIONAL_CONSTRAINT",
                    )
                },
                ParserUsage(10, 10, 100),
            )
        }
        val batch = requireNotNull(jdbc.queryForObject("SELECT id FROM submission_batches WHERE room_id=?", UUID::class.java, room.id))
        finalProcessor.process(SubmissionBatchId(batch))
    }

    private fun unapplied(room: Room): String =
        mvc
            .perform(
                get("/api/rooms/${room.invite}/candidates/unapplied-inputs")
                    .header(HttpHeaders.AUTHORIZATION, room.host.bearer),
            ).andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

    private fun <T> anyValue(): T = Mockito.any<T>()

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
            assertTrue(future.get(10, TimeUnit.SECONDS))
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
        private const val HOST_RAW = "주최자 본인만 볼 수 있는 합성 원문"
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
