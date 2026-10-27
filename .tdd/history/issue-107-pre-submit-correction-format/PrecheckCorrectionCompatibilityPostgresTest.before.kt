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
import java.time.LocalTime
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
@Import(PrecheckCorrectionCompatibilityPostgresTest.IsolatedSecurity::class)
class PrecheckCorrectionCompatibilityPostgresTest {
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
    fun `PS27 CLOSED OPEN 구PUT 수정은 최신accepted만 바꾸고 새 active batch 전까지 미반영이다`() {
        val setup = initialResult()
        val room = setup.room
        val original = current(room, setup.first)
        val firstRun = activeAnalysis(room)
        val originalBatch = frozenVersions(firstRun)
        assertEquals(FIRST_RAW, original.path("accepted").path("raw_text").stringValue())
        assertTrue(original.path("applied_to_result").booleanValue())
        assertEquals("PRECHECK", original.path("accepted").path("confirmation_source").stringValue())
        assertEquals(409, correction(room, setup.first, SECOND_RAW).response.status)
        assertEquals(409, performCreate(room, setup.first, SECOND_RAW, revision = 1).response.status)
        val round = openCorrection(room, firstRun)
        assertEquals(409, correction(room, setup.first, SECOND_RAW, UUID.randomUUID(), 1).response.status)
        assertEquals(409, correction(room, setup.first, SECOND_RAW, round, 0).response.status)
        NOW.set(START.plusSeconds(60))
        val saved = correction(room, setup.first, SECOND_RAW, round, 1)
        assertEquals(200, saved.response.status, saved.response.contentAsString)
        val changed = current(room, setup.first)
        assertEquals(SECOND_RAW, changed.path("accepted").path("raw_text").stringValue())
        assertEquals("LEGACY_CORRECTION", changed.path("accepted").path("confirmation_source").stringValue())
        assertEquals(2, changed.path("accepted").path("revision").intValue())
        assertEquals(NOW.get(), Instant.parse(changed.path("accepted").path("created_at").stringValue()))
        assertEquals(NOW.get(), Instant.parse(changed.path("accepted").path("interpretation_reference_at").stringValue()))
        assertEquals(original.path("candidate"), changed.path("candidate"), "legacy correction must not impersonate a new precheck source")
        assertEquals(original.path("check"), changed.path("check"))
        assertFalse(
            changed.path("submission_complete").booleanValue(),
            "a correction accepted by the existing OPEN round has not passed a new precheck",
        )
        assertFalse(changed.path("applied_to_result").booleanValue(), "active initial analysis still freezes revision 1")
        assertTrue(current(room, setup.second).path("applied_to_result").booleanValue())
        val own =
            tree(mvc.perform(get("/api/rooms/${room.invite}/submission").cookie(setup.first.cookie)).andExpect(status().isOk).andReturn())
        assertEquals(SECOND_RAW, own.path("raw_text").stringValue())
        assertEquals(2, own.path("revision").intValue())
        assertEquals(originalBatch, frozenVersions(firstRun))
        val nextRun = analyzeCorrection(room, round)
        assertTrue(nextRun != firstRun)
        assertTrue(
            current(room, setup.first).path("applied_to_result").booleanValue(),
            "membership refers to the new frozen batch, before provider success",
        )
        processActive(room)
        assertEquals("READY", publicRoom(room).path("public_status").stringValue())
        val after = current(room, setup.first)
        assertEquals(SECOND_RAW, after.path("accepted").path("raw_text").stringValue())
        assertEquals(original.path("candidate"), after.path("candidate"))
        assertTrue(after.path("applied_to_result").booleanValue())
        val beforeCounts =
            listOf(count("submission_versions"), count("precheck_candidates"), count("precheck_checks"), count("coordination_runs"))
        val replay = create(room, setup.first, FIRST_RAW, key = setup.firstRequest)
        assertEquals(setup.firstRequest.toString(), replay.path("operation_outcome").path("request_id").stringValue())
        assertEquals(1, replay.path("operation_outcome").path("candidate_revision").intValue())
        assertEquals(SECOND_RAW, replay.path("accepted").path("raw_text").stringValue())
        assertEquals("LEGACY_CORRECTION", replay.path("accepted").path("confirmation_source").stringValue())
        assertEquals(2, replay.path("accepted").path("revision").intValue())
        assertTrue(replay.path("applied_to_result").booleanValue())
        assertEquals("CLOSED", replay.path("room").path("collection_status").stringValue())
        assertEquals(
            beforeCounts,
            listOf(count("submission_versions"), count("precheck_candidates"), count("precheck_checks"), count("coordination_runs")),
        )
        assertEquals(2, REQUESTS.size, "the explicit legacy correction and replay do not create personal provider checks")
        assertEquals(originalBatch, frozenVersions(firstRun))
    }

    @Test
    fun `PS27 마감된 pending candidate와 구PUT accepted의 같은 revision 번호는 같은 검사를 뜻하지 않는다`() {
        val setup = initialResult(pendingEdit = true)
        val room = setup.room
        val original = current(room, setup.first)
        assertEquals(2, original.path("candidate").path("revision").intValue())
        assertEquals(PENDING_RAW, original.path("candidate").path("raw_text").stringValue())
        assertEquals(1, original.path("accepted").path("revision").intValue())
        assertTrue(original.path("applied_to_result").booleanValue())
        assertEquals("PRECHECK", original.path("accepted").path("confirmation_source").stringValue())
        val firstRun = activeAnalysis(room)
        val originalBatch = frozenVersions(firstRun)
        val round = openCorrection(room, firstRun)
        NOW.set(START.plusSeconds(60))
        assertEquals(200, correction(room, setup.first, SECOND_RAW, round, 1).response.status)
        val changed = current(room, setup.first)
        assertEquals(2, changed.path("candidate").path("revision").intValue())
        assertEquals(2, changed.path("accepted").path("revision").intValue())
        assertEquals(PENDING_RAW, changed.path("candidate").path("raw_text").stringValue())
        assertEquals(SECOND_RAW, changed.path("accepted").path("raw_text").stringValue())
        assertEquals("LEGACY_CORRECTION", changed.path("accepted").path("confirmation_source").stringValue())
        assertFalse(changed.path("submission_complete").booleanValue())
        assertFalse(changed.path("applied_to_result").booleanValue())
        worker.runOne()
        assertEquals(SECOND_RAW, current(room, setup.first).path("accepted").path("raw_text").stringValue())
        assertEquals(2, REQUESTS.size, "closed pending work must not overwrite the legacy correction or spend a call")
        analyzeCorrection(room, round)
        processActive(room)
        val after = current(room, setup.first)
        assertEquals(PENDING_RAW, after.path("candidate").path("raw_text").stringValue())
        assertEquals(SECOND_RAW, after.path("accepted").path("raw_text").stringValue())
        assertTrue(after.path("applied_to_result").booleanValue())
        assertFalse(after.path("submission_complete").booleanValue())
        assertEquals(originalBatch, frozenVersions(firstRun))
        assertFalse(frozenTexts().contains(PENDING_RAW))
    }

    @Test
    fun `PS27 최초 미확인 참여자는 OPEN correction으로 source cohort에 들어오지 못한다`() {
        val setup = initialResult(withUnconfirmed = true)
        val outsider = requireNotNull(setup.unconfirmed)
        val before = current(setup.room, outsider)
        val firstRun = activeAnalysis(setup.room)
        val originalBatch = frozenVersions(firstRun)
        val round = openCorrection(setup.room, firstRun)
        val rejected = correction(setup.room, outsider, SECOND_RAW, round, 1)
        assertEquals(403, rejected.response.status)
        assertFalse(rejected.response.contentAsString.contains(PENDING_RAW))
        val after = current(setup.room, outsider)
        assertEquals(before.path("candidate"), after.path("candidate"))
        assertTrue(after.path("accepted").isNull)
        assertFalse(after.path("submission_complete").booleanValue())
        assertFalse(after.path("applied_to_result").booleanValue())
        assertEquals(2, count("submission_versions"))
        assertEquals(originalBatch, frozenVersions(firstRun))
        assertEquals(firstRun, analyzeCorrection(setup.room, round, expectedStatus = 200))
        assertFalse(current(setup.room, outsider).path("applied_to_result").booleanValue())
        assertEquals(2, frozenVersions(activeAnalysis(setup.room)).size)
    }

    @Test
    fun `PS27 여러 PRECHECK revision과 OPEN 동일원문 no-op은 LEGACY_CORRECTION으로 추정하지 않는다`() {
        val room = room()
        val first = join(room)
        val second = join(room)
        create(room, first, FIRST_RAW)
        worker.runOne()
        create(room, first, SECOND_RAW, revision = 1)
        worker.runOne()
        create(room, second, FIRST_RAW)
        worker.runOne()
        val done = current(room, first)
        assertEquals(2, done.path("accepted").path("revision").intValue())
        assertEquals("PRECHECK", done.path("accepted").path("confirmation_source").stringValue())
        assertTrue(done.path("submission_complete").booleanValue())
        close(room)
        installFinalParser()
        processActive(room)
        assertEquals("NO_MATCH", publicRoom(room).path("public_status").stringValue())
        val source = activeAnalysis(room)
        val round = openCorrection(room, source)
        val before = current(room, first)
        val originalCount = count("submission_versions")
        assertEquals(200, correction(room, first, SECOND_RAW, round, 2).response.status)
        val after = current(room, first)
        assertEquals(before.path("accepted"), after.path("accepted"))
        assertEquals(before.path("candidate"), after.path("candidate"))
        assertEquals("PRECHECK", after.path("accepted").path("confirmation_source").stringValue())
        assertTrue(after.path("submission_complete").booleanValue())
        assertTrue(after.path("applied_to_result").booleanValue())
        assertEquals(originalCount, count("submission_versions"))
        assertEquals(3, REQUESTS.size)
    }

    private fun initialResult(
        pendingEdit: Boolean = false,
        withUnconfirmed: Boolean = false,
    ): InitialResult {
        val room = room()
        val first = join(room)
        val second = join(room)
        val firstRequest = UUID.randomUUID()
        create(room, first, FIRST_RAW, key = firstRequest)
        create(room, second, SECOND_RAW)
        worker.runOne()
        worker.runOne()
        if (pendingEdit) create(room, first, PENDING_RAW, revision = 1)
        val unconfirmed = if (withUnconfirmed) join(room).also { create(room, it, PENDING_RAW) } else null
        close(room)
        installFinalParser()
        processActive(room)
        assertEquals("NO_MATCH", publicRoom(room).path("public_status").stringValue())
        assertEquals(2, REQUESTS.size)
        return InitialResult(room, first, second, firstRequest, unconfirmed)
    }

    private fun installFinalParser() {
        Mockito.`when`(parser.parse(anyValue())).thenAnswer { invocation ->
            val request = invocation.getArgument<NaturalLanguageBatchRequest>(0)
            NaturalLanguageBatchResult(
                request.inputs.map { input ->
                    val start =
                        when (input.rawText) {
                            FIRST_RAW -> 8
                            SECOND_RAW -> 10
                            else -> error("No synthetic final parser result was defined for this input")
                        }
                    StructuredSubmissionResult(
                        SubmissionVersionId(UUID.fromString(input.inputRef)),
                        listOf(
                            StructuredCondition.TimeWindow(
                                TimePolarity.AVAILABLE,
                                java.time.LocalDate.of(2026, 10, 18),
                                null,
                                LocalTime.of(start, 0),
                                LocalTime.of(start + 1, 0),
                            ),
                        ),
                        null,
                    )
                },
                ParserUsage(10, 10, 100),
            )
        }
    }

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
        val result = mvc
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

    private fun frozenVersions(run: UUID) =
        jdbc.queryForList(
            "SELECT v.id,v.raw_text FROM coordination_runs r JOIN submission_batch_items i ON i.batch_id=r.batch_id JOIN submission_versions v ON v.id=i.submission_version_id WHERE r.id=? ORDER BY v.id",
            run,
        )

    private data class InitialResult(
        val room: Room,
        val first: Guest,
        val second: Guest,
        val firstRequest: UUID,
        val unconfirmed: Guest?,
    )

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
        private const val FIRST_RAW = "10월 18일 오전 8시부터 9시 온라인 가능"
        private const val SECOND_RAW = "10월 18일 오전 10시부터 11시 온라인 가능"
        private const val PENDING_RAW = "10월 18일 오전 11시부터 12시 온라인 가능"
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
