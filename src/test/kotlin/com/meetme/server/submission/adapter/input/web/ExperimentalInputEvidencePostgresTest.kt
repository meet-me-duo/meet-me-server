package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent V2 contract; synthetic provider, actual HTTP ownership and PostgreSQL transactions. */
@SpringBootTest(
    properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false", "meetme.precheck.evidence-enabled=true"],
)
@AutoConfigureMockMvc
@Import(ExperimentalInputEvidencePostgresTest.IsolatedSecurity::class)
class ExperimentalInputEvidencePostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var checks: PrecheckRepository

    @Autowired private lateinit var submissions: SubmissionRepository
    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        REQUESTS.clear()
        ACTION.set { question() }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `EX-ORIGINAL choice keeps original and stores the targeted supplement durably`() {
        val room = room()
        val guest = join(room)
        assertEquals("PRE_SUBMIT_V2", checks.protocol(room.id))
        create(room, guest, RAW)
        assertTrue(worker.runOne())
        val question = current(room, guest)
        val sourceId =
            question
                .path("check")
                .path("question")
                .path("id")
                .stringValue()
        NOW.set(START.plusSeconds(60))
        mutate(room, guest, question, "answer", mapOf("question_id" to sourceId, "choice_id" to "PM"))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        assertTrue(worker.runOne())

        val accepted = assertNotNull(submissions.findByParticipant(ParticipantId(guest.id))).latest
        assertEquals(RAW, accepted.rawText)
        val evidence = assertNotNull(accepted.evidence)
        assertEquals(1, evidence.originalRevision)
        assertEquals(START, evidence.referenceAt)
        assertEquals("오후", evidence.supplement?.answerText)
        assertEquals(START, evidence.supplement?.referenceAt)
        assertEquals(InputInterpretation.CONFIRMED, evidence.interpretation)
        assertEquals(RAW, REQUESTS.last().rawText)
        assertEquals(evidence.supplement, REQUESTS.last().evidence?.supplement)
        assertEquals(2, REQUESTS.size)
        assertEquals(1, checks.questionCount(room.id, guest.id))
    }

    @Test
    fun `EX-RECEIPT unresolved answer is received for final analysis but is never CLEAR`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        answer(room, guest, before)
        ACTION.set { question() }
        worker.runOne()

        val after = current(room, guest)
        assertEquals("RECEIVED", after.path("check").path("status").stringValue())
        assertTrue(after.path("submission_complete").booleanValue())
        val accepted = assertNotNull(submissions.findByParticipant(ParticipantId(guest.id))).latest
        assertEquals(RAW, accepted.rawText)
        assertEquals(InputInterpretation.UNRESOLVED, accepted.evidence?.interpretation)
        assertNotNull(accepted.evidence?.supplement)
        assertEquals(1, checks.questionCount(room.id, guest.id))
        assertFalse(worker.runOne(), "an unresolved answer must not enqueue a third interpretation")
        assertEquals(2, REQUESTS.size)
    }

    @Test
    fun `EX-QUESTION new revisions do not reset the room participant question budget`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val first = current(room, guest)
        answer(room, guest, first)
        worker.runOne()
        val received = current(room, guest)
        create(room, guest, "다음 주 7시 가능", received.path("candidate").path("revision").intValue())
        worker.runOne()

        assertEquals(1, checks.questionCount(room.id, guest.id))
        assertNotEquals("CLEAR", current(room, guest).path("check").path("status").stringValue())
        assertEquals(RAW, assertNotNull(submissions.findByParticipant(ParticipantId(guest.id))).latest.rawText)
    }

    @Test
    fun `EX-IDEMPOTENCY same answer key is replayed without an extra revision or recheck`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        val key = UUID.randomUUID()
        val extra = answerFields(before)
        val first = mutate(room, guest, before, "answer", extra, key)
        val replay = mutate(room, guest, before, "answer", extra, key)
        assertEquals(first.path("outcome"), replay.path("outcome"))
        assertEquals(2, current(room, guest).path("candidate").path("revision").intValue())
        assertEquals(409, mutateResult(room, guest, before, "answer", extra + ("choice_id" to "AM"), key).response.status)
        worker.runOne()
        assertEquals(2, REQUESTS.size)
        assertFalse(worker.runOne())
    }

    @Test
    fun `EX-REVISION concurrent different answers allow one transition and preserve one original`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        val result =
            concurrently(
                { mutateResult(room, guest, before, "answer", answerFields(before)) },
                { mutateResult(room, guest, before, "answer", answerFields(before) + ("choice_id" to "AM")) },
            )
        assertEquals(listOf(202, 409), result.map { it.response.status }.sorted())
        assertEquals(RAW, current(room, guest).path("candidate").path("raw_text").stringValue())
        assertEquals(2, current(room, guest).path("candidate").path("revision").intValue())
        assertEquals(1, checks.questionCount(room.id, guest.id))
    }

    @Test
    fun `EX-QUESTION direct answer requires the actual question identity`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        val missing = mutateResult(room, guest, before, "answer", mapOf("direct_text" to "오후입니다"))
        assertEquals(400, missing.response.status)
        assertEquals(1, current(room, guest).path("candidate").path("revision").intValue())
        assertEquals(
            409,
            mutateResult(
                room,
                guest,
                before,
                "answer",
                mapOf("question_id" to UUID.randomUUID(), "direct_text" to "오후입니다"),
            ).response.status,
        )
    }

    @Test
    fun `EX-QUESTION text-only question accepts a scoped direct answer without rewriting original`() {
        val room = room()
        val guest = join(room)
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.NEEDS_CHOICE, "7시의 오전 또는 오후를 알려주세요", "7시") }
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        val questionId =
            before
                .path("check")
                .path("question")
                .path("id")
                .stringValue()
        assertTrue(questionId.isNotBlank())
        NOW.set(START.plusSeconds(60))
        mutate(room, guest, before, "answer", mapOf("question_id" to questionId, "direct_text" to "오후입니다"))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        worker.runOne()

        val accepted = assertNotNull(submissions.findByParticipant(ParticipantId(guest.id))).latest
        assertEquals(RAW, accepted.rawText)
        assertEquals("오후입니다", accepted.evidence?.supplement?.answerText)
        assertEquals(START, accepted.evidence?.referenceAt)
        assertEquals(NOW.get(), accepted.evidence?.supplement?.referenceAt)
        assertEquals(1, checks.questionCount(room.id, guest.id))
        assertEquals(2, REQUESTS.size)
    }

    @Test
    fun `EX-PRIVACY another guest cannot answer or learn the owners question`() {
        val room = room()
        val owner = join(room, "원문 소유자")
        val stranger = join(room, "다른 참여자")
        create(room, owner, RAW)
        worker.runOne()
        val before = current(room, owner)
        val result = mutateResult(room, stranger, before, "answer", answerFields(before))
        assertTrue(result.response.status in setOf(403, 404, 409))
        assertFalse(result.response.contentAsString.contains(RAW))
        assertFalse(result.response.contentAsString.contains("오전인가요"))
        assertEquals(1, current(room, owner).path("candidate").path("revision").intValue())
    }

    @Test
    fun `EX-FREEZE closure freezes original answer and unresolved status as one version`() {
        val room = room()
        val first = join(room, "첫 참여자")
        val second = join(room, "둘째 참여자")
        create(room, first, RAW)
        worker.runOne()
        answer(room, first, current(room, first))
        worker.runOne()
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        create(room, second, "목요일 오후 7시부터 9시 가능")
        worker.runOne()
        val before = assertNotNull(submissions.findByParticipant(ParticipantId(first.id))).latest

        mvc
            .perform(
                post("/api/rooms/${room.invite}/close")
                    .header(HttpHeaders.AUTHORIZATION, room.host.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirm_early\":true}"),
            ).andExpect(status().isOk)

        val ids =
            jdbc.queryForList("SELECT submission_version_id FROM submission_batch_items", UUID::class.java).map {
                SubmissionVersionId(requireNotNull(it))
            }
        val frozen = submissions.findFrozenByVersionIds(MeetingRoomId(room.id), ids)
        val firstFrozen = frozen.single { it.participantId == ParticipantId(first.id) }.latest
        assertEquals(before.id, firstFrozen.id)
        assertEquals(RAW, firstFrozen.rawText)
        assertEquals(before.evidence, firstFrozen.evidence)
        assertEquals(InputInterpretation.UNRESOLVED, firstFrozen.evidence?.interpretation)
        assertEquals("오후", firstFrozen.evidence?.supplement?.answerText)
        val afterClose = current(room, first)
        val rejected = performCreate(room, first, "금요일 오후 9시 가능", afterClose.path("candidate").path("revision").intValue())
        assertEquals(409, rejected.response.status)
        assertEquals(frozen, submissions.findFrozenByVersionIds(MeetingRoomId(room.id), ids))
    }

    @Test
    fun `EX-QUESTION cancellation cannot mint a second room question`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        mutate(room, guest, before, "cancel", expectedStatus = 200)
        create(room, guest, "다음 주 7시 가능", before.path("candidate").path("revision").intValue())
        worker.runOne()

        assertEquals(1, checks.questionCount(room.id, guest.id))
        assertNotEquals("CLEAR", current(room, guest).path("check").path("status").stringValue())
        assertNull(submissions.findByParticipant(ParticipantId(guest.id)))
    }

    private fun answer(
        room: Room,
        guest: Guest,
        snapshot: JsonNode,
    ) = mutate(room, guest, snapshot, "answer", answerFields(snapshot))

    private fun answerFields(snapshot: JsonNode): Map<String, Any> =
        mapOf(
            "question_id" to
                snapshot
                    .path("check")
                    .path("question")
                    .path("id")
                    .stringValue(),
            "choice_id" to "PM",
        )

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
                    "SELECT p.id FROM participants p JOIN guest_browser_sessions s ON p.guest_session_id=s.id " +
                        "WHERE p.room_id=? AND s.credential_digest=?",
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
        private fun question() =
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                "7시는 오전인가요, 오후인가요?",
                "7시",
                listOf(PrecheckChoice("AM", "오전", "오전"), PrecheckChoice("PM", "오후", "오후")),
            )

        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val RAW = "목요일 7시 이후 가능. 금요일은 불가."
        private val REQUESTS = CopyOnWriteArrayList<PrecheckProviderRequest>()
        private val ACTION =
            AtomicReference<
                (
                    PrecheckProviderRequest,
                ) -> PrecheckProviderResult,
            >({ PrecheckProviderResult(PrecheckVerdict.CLEAR) })
        private val START = Instant.parse("2026-10-29T03:00:00Z")
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
