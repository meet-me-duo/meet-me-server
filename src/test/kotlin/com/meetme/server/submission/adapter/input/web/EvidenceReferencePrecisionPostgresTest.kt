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
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchProfiles
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
@Import(EvidenceReferencePrecisionPostgresTest.IsolatedSecurity::class)
class EvidenceReferencePrecisionPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var checks: PrecheckRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var finalProcessor: GeminiBatchProcessor

    @MockitoBean private lateinit var parser: NaturalLanguageParserPort

    @Autowired private lateinit var dispatchGuard: AiRuntimeDispatchGuardPort
    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        PHYSICAL.set(false)
        REQUESTS.clear()
        ACTION.set { question() }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `EX-PRECISION original reference survives PostgreSQL microseconds without crossing KST midnight`() {
        val wall = Instant.parse("2026-10-29T14:59:59.999999999Z")
        NOW.set(wall)
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        val stored = assertNotNull(checks.current(guest.id)).candidate
        val expected = wall.truncatedTo(ChronoUnit.MICROS)
        assertEquals(expected, stored.createdAt)
        assertEquals(expected, stored.referenceAt)
        assertEquals(stored.referenceAt, stored.evidence?.referenceAt)
        assertEquals(LocalDate.of(2026, 10, 29), stored.referenceAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
        ACTION.set { request ->
            NOW.set(wall.plusMillis(1))
            assertEquals(expected, request.referenceAt)
            assertEquals(expected, request.evidence?.referenceAt)
            val context = assertNotNull(request.dispatchContext)
            assertEquals(AiDispatchFingerprints.personalSource(request.rawText, request.evidence), context.execution.sourceFingerprint)
            val grant =
                dispatchGuard.authorize(
                    AiBudgetReserveRequest(
                        context.execution,
                        listOf(
                            AiPhysicalCallSpec(
                                AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                                1,
                                "1".repeat(64),
                                AiRuntimeDispatchProfiles.fingerprints.getValue(AiPhysicalOperation.PRECHECK_CLASSIFICATION),
                            ),
                        ),
                    ),
                    NOW.get(),
                )
            assertNotNull(grant, "Exact persisted source evidence must authorize the current provider claim")
            PrecheckProviderResult(PrecheckVerdict.CLEAR)
        }
        PHYSICAL.set(true)
        assertTrue(worker.runOne())
        assertEquals("CLEAR", current(room, guest).path("check").path("status").stringValue())
        assertEquals(expected, assertNotNull(submissions.findByParticipant(ParticipantId(guest.id))).latest.evidence?.referenceAt)
    }

    @Test
    fun `EX-PRECISION direct answer timestamps use the same persisted microsecond precision`() {
        val wall = Instant.parse("2026-10-29T14:59:59.999999999Z")
        NOW.set(wall)
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val before = current(room, guest)
        val answerWall = wall.plusSeconds(1)
        NOW.set(answerWall)
        mutate(
            room,
            guest,
            before,
            "answer",
            mapOf(
                "question_id" to
                    before
                        .path("check")
                        .path("question")
                        .path("id")
                        .stringValue(),
                "direct_text" to "오후입니다",
            ),
        )
        val stored = assertNotNull(checks.current(guest.id)).candidate
        val supplement = assertNotNull(stored.evidence?.supplement)
        assertEquals(wall.truncatedTo(ChronoUnit.MICROS), stored.referenceAt)
        assertEquals(wall.truncatedTo(ChronoUnit.MICROS), stored.evidence?.referenceAt)
        assertEquals(answerWall.truncatedTo(ChronoUnit.MICROS), stored.createdAt)
        assertEquals(stored.createdAt, supplement.answeredAt)
        assertEquals(stored.createdAt, supplement.referenceAt)
        assertEquals(RAW, stored.rawText)
    }

    @Test
    fun `EX-PRECISION post-close revision preserves the pre-midnight reference at microsecond precision`() {
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
        close(room)
        Mockito.`when`(parser.parse(anyValue())).thenAnswer { invocation ->
            val request = invocation.getArgument<NaturalLanguageBatchRequest>(0)
            NaturalLanguageBatchResult(
                request.inputs.map { input ->
                    StructuredSubmissionResult(
                        SubmissionVersionId(UUID.fromString(input.inputRef)),
                        if (input.rawText == RAW) {
                            emptyList()
                        } else {
                            listOf(
                                StructuredCondition.TimeWindow(
                                    TimePolarity.AVAILABLE,
                                    LocalDate.of(2026, 10, 30),
                                    null,
                                    LocalTime.of(19, 0),
                                    LocalTime.of(21, 0),
                                ),
                            )
                        },
                        if (input.rawText == RAW) "AMBIGUOUS_TIME_CONSTRAINT" else null,
                    )
                },
                ParserUsage(10, 10, 100),
            )
        }
        processActive(room)
        val initialRun = activeAnalysis(room)
        val oldIds = frozenIds(initialRun)
        val oldFrozen = submissions.findFrozenByVersionIds(MeetingRoomId(room.id), oldIds)
        val oldFirst = oldFrozen.single { it.participantId == ParticipantId(first.id) }.latest
        assertNotNull(oldFirst.evidence?.supplement)
        val round = openCorrection(room, initialRun)
        val newReference = Instant.parse("2026-10-29T14:59:59.999999999Z")
        NOW.set(newReference)
        val revised = "다음 주 금요일 19시부터 21시 가능"
        val saved = correction(room, first, revised, round, oldFirst.revision)
        assertEquals(200, saved.response.status, saved.response.contentAsString)

        val accepted = assertNotNull(submissions.findByParticipant(ParticipantId(first.id))).latest
        assertEquals(oldFirst.revision + 1, accepted.revision)
        assertEquals(revised, accepted.rawText)
        val evidence = assertNotNull(accepted.evidence)
        assertEquals(accepted.revision, evidence.originalRevision)
        assertEquals(newReference.truncatedTo(ChronoUnit.MICROS), evidence.referenceAt)
        assertEquals(evidence.referenceAt, accepted.createdAt)
        assertEquals(InputInterpretation.UNRESOLVED, evidence.interpretation)
        assertEquals("POST_CLOSE_REVISION", evidence.unresolvedReason)
        assertNull(evidence.supplement, "the old question was scoped to a different original")
        val restoredFrozen = submissions.findFrozenByVersionIds(MeetingRoomId(room.id), oldIds)
        assertEquals(oldFrozen.size, restoredFrozen.size)
        assertEquals(
            oldFrozen.associateBy { it.latest.id },
            restoredFrozen.associateBy { it.latest.id },
        )
        val nextRun = analyzeCorrection(room, round)
        assertNotEquals(initialRun, nextRun)
        val nextFrozen = submissions.findFrozenByVersionIds(MeetingRoomId(room.id), frozenIds(nextRun))
        assertEquals(accepted, nextFrozen.single { it.participantId == ParticipantId(first.id) }.latest)
        assertEquals(1, checks.questionCount(room.id, first.id))
        assertEquals(3, REQUESTS.size, "post-close revisions must not create another personal question or check")
        assertFalse(worker.runOne())
    }

    private fun frozenIds(run: UUID): List<SubmissionVersionId> =
        jdbc
            .queryForList(
                "SELECT i.submission_version_id FROM submission_batch_items i JOIN coordination_runs r ON r.batch_id=i.batch_id WHERE r.id=?",
                UUID::class.java,
                run,
            ).map { SubmissionVersionId(requireNotNull(it)) }

    private fun <T> anyValue(): T = Mockito.any<T>()

    private fun openCorrection(
        room: Room,
        source: UUID,
    ): UUID {
        val result =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/reopen")
                        .header(HttpHeaders.AUTHORIZATION, room.host.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "source_analysis_id" to source,
                                    "expected_generation" to publicRoom(room).path("revision_generation").longValue(),
                                ),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andReturn()
        return UUID.fromString(tree(result).path("round").path("id").stringValue())
    }

    private fun correction(
        room: Room,
        guest: Guest,
        raw: String,
        round: UUID? = null,
        expected: Int? = null,
    ): MvcResult {
        val payload = mutableMapOf<String, Any>("raw_text" to raw)
        round?.let { payload["revision_round_id"] = it }
        expected?.let { payload["expected_revision"] = it }
        return mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/rooms/${room.invite}/submission")
                    .cookie(guest.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)),
            ).andReturn()
    }

    private fun analyzeCorrection(
        room: Room,
        round: UUID,
        expectedStatus: Int = 202,
    ): UUID {
        val result =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/analysis")
                        .header(HttpHeaders.AUTHORIZATION, room.host.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "revision_round_id" to round,
                                    "request_id" to UUID.randomUUID(),
                                    "force_reparse" to false,
                                ),
                            ),
                        ),
                ).andReturn()
        assertEquals(expectedStatus, result.response.status, result.response.contentAsString)
        assertEquals("CLOSED", publicRoom(room).path("collection_status").stringValue())
        return activeAnalysis(room)
    }

    private fun publicRoom(room: Room): JsonNode =
        tree(
            mvc
                .perform(get("/api/rooms/${room.invite}").header(HttpHeaders.AUTHORIZATION, room.host.bearer))
                .andExpect(status().isOk)
                .andReturn(),
        )

    private fun activeAnalysis(room: Room): UUID = UUID.fromString(publicRoom(room).path("analysis_id").stringValue())

    private fun processActive(room: Room) {
        val batch =
            requireNotNull(jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id=?", UUID::class.java, activeAnalysis(room)))
        finalProcessor.process(SubmissionBatchId(batch))
    }

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
        fun precisionDispatchMode(): AiDispatchModePort = AiDispatchModePort { PHYSICAL.get() }

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
        private val PHYSICAL = AtomicBoolean(false)

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
        private val START = Instant.parse("2026-10-29T14:59:58Z")
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
