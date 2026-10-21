package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestProofProvenance
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent PS02/03/05-12/19/21-24/31 and approved D1/D2, real PG + HTTP + deterministic synthetic provider. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(PrecheckDefensiveBoundaryPostgresTest.IsolatedSecurity::class)
class PrecheckDefensiveBoundaryPostgresTest {
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

    @Test
    fun `PS08 source 원문과 같은 제안은 opaque 선택ID가 유효해도 INVALID_RESPONSE다`() {
        val room = room()
        val owner = join(room)
        ACTION.set {
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                "오전과 오후 중 어느 뜻인가요?",
                "8시",
                listOf(
                    PrecheckChoice("AM", "원문 그대로", AMBIGUOUS),
                    PrecheckChoice("PM", "오후 8시", "내일 오후 8시 온라인 가능"),
                ),
            )
        }
        create(room, owner, AMBIGUOUS)
        worker.runOne()
        val after = current(room, owner)
        assertEquals("FAILED", after.path("check").path("status").stringValue())
        assertEquals("INVALID_RESPONSE", after.path("check").path("failure_code").stringValue())
        assertTrue(after.path("check").path("question").isNull)
        assertEquals(AMBIGUOUS, after.path("candidate").path("raw_text").stringValue())
        assertFalse(after.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS19 VERIFIED 라벨만 있고 provenance 없는 trusted proof는 신규 쓰기를 승인하지 않는다`() {
        val room = room()
        val owner = join(room)
        val incomplete = GuestOwnershipProof(MeetingRoomId(room.id), ParticipantId(owner.id), GuestProofLevel.VERIFIED_GUEST)
        stubProof(room, owner.cookie.value, incomplete)
        val rejected = performCreate(room, owner, RAW)
        assertEquals(403, rejected.response.status)
        assertFalse(rejected.response.contentAsString.contains(RAW))
        assertEquals(0, count("precheck_candidates"))
        assertEquals(0, count("submission_versions"))
        assertEquals(0, REQUESTS.size)
    }

    @Test
    fun `PS19 expiry와 grant 계보 없는 옛 guest binding은 owner가 같아도 active가 아니다`() {
        val room = room()
        val owner = join(room)
        val participant = requireNotNull(participants.findById(ParticipantId(owner.id)))
        val session = requireNotNull(participant.guestSessionId)
        assertFalse(bindings.isActive(participant, "G|${session.value}", START))
    }

    @Test
    fun `PS17 raw credential 없는 verified context도 실제 session expiry provenance가 있으면 허용한다`() {
        val room = room()
        val owner = join(room)
        val participant = requireNotNull(participants.findById(ParticipantId(owner.id)))
        val session = requireNotNull(participant.guestSessionId)
        val expiry =
            requireNotNull(
                jdbc.queryForObject("SELECT expires_at FROM guest_browser_sessions WHERE id=?", Timestamp::class.java, session.value),
            ).toInstant()
        stubProof(
            room,
            null,
            GuestOwnershipProof(
                MeetingRoomId(room.id),
                ParticipantId(owner.id),
                GuestProofLevel.VERIFIED_GUEST,
                GuestProofProvenance(session, expiry),
            ),
        )
        val result =
            mvc
                .perform(
                    post(path(room))
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "expected_revision" to 0,
                                    "raw_text" to RAW,
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andReturn()
        assertEquals(RAW, tree(result).path("candidate").path("raw_text").stringValue())
        val binding = bindings.capture(participant, null)
        assertTrue(bindings.isActive(participant, binding, START))
    }

    @Test
    fun `PS19 실제 account Bearer는 guest provenance와 분리된 본인 쓰기 권한을 유지한다`() {
        val room = room()
        val owner = join(room)
        stubProof(
            room,
            owner.cookie.value,
            GuestOwnershipProof(MeetingRoomId(room.id), ParticipantId(owner.id), GuestProofLevel.VERIFIED_GUEST),
        )
        val result =
            mvc
                .perform(createRequest(room, owner, RAW).header(HttpHeaders.AUTHORIZATION, room.host.bearer))
                .andExpect(status().isAccepted)
                .andReturn()
        assertEquals(RAW, tree(result).path("candidate").path("raw_text").stringValue())
        val participant = requireNotNull(participants.findByRoomAndUser(MeetingRoomId(room.id), room.host.id))
        val binding = bindings.capture(participant, null)
        assertTrue(bindings.isActive(participant, binding, START))
    }

    @ParameterizedTest
    @ValueSource(strings = ["answer", "cancel", "retry"])
    fun `PS10 현재 checkID와 candidate revision도 옛 checkVersion이면 상태 비용 결과를 바꾸지 못한다`(action: String) {
        val room = room()
        val owner = join(room)
        ACTION.set { if (action == "retry") throw PrecheckProviderException("CONNECTION_FAILED") else question() }
        create(room, owner, AMBIGUOUS)
        worker.runOne()
        val before = current(room, owner)
        val currentVersion = before.path("check").path("version").longValue()
        assertTrue(currentVersion > 1, "the completed provider transition must advance check version")
        val checkId = UUID.fromString(before.path("check").path("id").stringValue())
        val callCount = checks.callCount(room.id, owner.id)
        val questionCount = checks.questionCount(room.id, owner.id)
        val payload =
            mutableMapOf<String, Any>(
                "request_id" to UUID.randomUUID(),
                "expected_revision" to before.path("candidate").path("revision").intValue(),
                "expected_check_version" to currentVersion - 1,
            )
        if (action == "answer") {
            payload["question_id"] =
                before
                    .path("check")
                    .path("question")
                    .path("id")
                    .stringValue()
            payload["choice_id"] = "AM"
        }
        val rejected =
            mvc
                .perform(
                    post("${path(room)}/$checkId/$action")
                        .cookie(owner.cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andReturn()
        assertEquals(409, rejected.response.status)
        val after = current(room, owner)
        assertEquals(before.path("candidate"), after.path("candidate"))
        assertEquals(before.path("accepted"), after.path("accepted"))
        assertEquals(before.path("check"), after.path("check"))
        assertEquals(before.path("operation_outcome"), after.path("operation_outcome"))
        assertEquals(callCount, checks.callCount(room.id, owner.id))
        assertEquals(questionCount, checks.questionCount(room.id, owner.id))
        assertEquals(1, count("precheck_candidates"))
        assertEquals(1, REQUESTS.size)
    }

    @Test
    fun `PS19 accepted SQL 대기 중 session expiry가 지나면 완료 집계 마감과 accepted link를 함께 rollback한다`() {
        val room = room(expected = 2)
        val stable = join(room)
        create(room, stable, "안정된 합성 완료 조건")
        worker.runOne()
        val expiring = join(room)
        val participant = requireNotNull(participants.findById(ParticipantId(expiring.id)))
        val session = requireNotNull(participant.guestSessionId)
        jdbc.update("UPDATE guest_browser_sessions SET expires_at=? WHERE id=?", Timestamp.from(START.plusSeconds(1)), session.value)
        val queued = create(room, expiring, RAW)
        val checkId = UUID.fromString(queued.path("check").path("id").stringValue())
        val executor = Executors.newSingleThreadExecutor()
        val blocker = requireNotNull(jdbc.dataSource).connection
        var future: java.util.concurrent.Future<Boolean>? = null
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR, inputTokens = 7, outputTokens = 3, responseBytes = 20) }
        try {
            blocker.createStatement().use { it.execute("SELECT pg_advisory_lock(19770407)") }
            jdbc.execute(
                """CREATE FUNCTION test_precheck_expiry_gate() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN PERFORM pg_advisory_lock(19770407); PERFORM pg_advisory_unlock(19770407); RETURN NEW; END; ${'$'}${'$'}""",
            )
            jdbc.execute(
                "CREATE TRIGGER test_precheck_expiry_gate BEFORE INSERT ON submission_versions FOR EACH ROW EXECUTE FUNCTION test_precheck_expiry_gate()",
            )
            future = executor.submit<Boolean> { worker.runOne() }
            val pollDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var reachedAcceptedWrite = false
            while (System.nanoTime() < pollDeadline) {
                val waiting =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=19770407 AND NOT granted",
                        Int::class.java,
                    )
                        ?: 0
                if (waiting == 1) {
                    reachedAcceptedWrite = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue(reachedAcceptedWrite, "expiry must advance after the real accepted INSERT reaches the trigger barrier")
            NOW.set(START.plusSeconds(2))
            blocker.createStatement().use { it.execute("SELECT pg_advisory_unlock(19770407)") }
            assertTrue(requireNotNull(future).get(10, TimeUnit.SECONDS))
            assertEquals(1, count("submission_heads"))
            assertEquals(1, count("submission_versions"))
            assertEquals(0, count("submission_batches"))
            assertEquals(0, count("coordination_runs"))
            roomView(room).andExpect(jsonPath("$.collection_status").value("COLLECTING"))
            assertEquals(
                null,
                jdbc.queryForObject(
                    "SELECT accepted_version_id FROM precheck_candidates WHERE participant_id=?",
                    UUID::class.java,
                    expiring.id,
                ),
            )
            assertEquals(
                RAW,
                jdbc.queryForObject("SELECT raw_text FROM precheck_candidates WHERE participant_id=?", String::class.java, expiring.id),
            )
            val terminal = requireNotNull(checks.find(checkId))
            assertTrue(terminal.status.name in setOf("SUPERSEDED", "FAILED"), "expired proof must have a terminal non-complete outcome")
            assertEquals(1, checks.callCount(room.id, expiring.id))
            val observation =
                jdbc.queryForMap(
                    "SELECT input_tokens,output_tokens,response_bytes,finished_at FROM precheck_executions WHERE check_id=?",
                    checkId,
                )
            assertEquals(7L, observation["input_tokens"])
            assertEquals(3L, observation["output_tokens"])
            assertEquals(20, observation["response_bytes"])
            assertNotNull(observation["finished_at"])
            mvc.perform(get("${path(room)}/current").cookie(expiring.cookie)).andExpect(status().isUnauthorized)
        } finally {
            blocker.createStatement().use { it.execute("SELECT pg_advisory_unlock(19770407)") }
            blocker.close()
            future?.cancel(true)
            executor.shutdownNow()
            jdbc.execute("DROP TRIGGER IF EXISTS test_precheck_expiry_gate ON submission_versions")
            jdbc.execute("DROP FUNCTION IF EXISTS test_precheck_expiry_gate()")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CREATE", "DIRECT", "CHOICE"])
    fun `PS31 admission 대기 중 KST 자정을 넘으면 새입력과 DIRECT만 새시각 anchor를 쓴다`(mode: String) {
        val beforeMidnight = Instant.parse("2026-10-17T14:59:59Z")
        val afterMidnight = Instant.parse("2026-10-17T15:00:01Z")
        NOW.set(beforeMidnight)
        val room = room()
        val owner = join(room)
        var source: JsonNode? = null
        if (mode != "CREATE") {
            ACTION.set { question() }
            create(room, owner, AMBIGUOUS)
            worker.runOne()
            source = current(room, owner)
        }
        val blocker = requireNotNull(jdbc.dataSource).connection
        val executor = Executors.newSingleThreadExecutor()
        var future: java.util.concurrent.Future<MvcResult>? = null
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(710715016)") }
            future =
                executor.submit<MvcResult> {
                    if (mode == "CREATE") {
                        performCreate(room, owner, RAW)
                    } else {
                        val snapshot = requireNotNull(source)
                        val extra =
                            if (mode == "DIRECT") {
                                mapOf<String, Any>("direct_text" to "내일 저녁 온라인 가능")
                            } else {
                                mapOf(
                                    "question_id" to
                                        snapshot
                                            .path("check")
                                            .path("question")
                                            .path("id")
                                            .stringValue(),
                                    "choice_id" to "AM",
                                )
                            }
                        mutateResult(room, owner, snapshot, "answer", extra)
                    }
                }
            val pollDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var reachedAdmission = false
            while (System.nanoTime() < pollDeadline) {
                val waiting =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=710715016 AND NOT granted",
                        Int::class.java,
                    )
                        ?: 0
                if (waiting == 1) {
                    reachedAdmission = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue(reachedAdmission, "the command must reach the real PG admission barrier before the clock crosses midnight")
            NOW.set(afterMidnight)
            blocker.commit()
            val result = requireNotNull(future).get(10, TimeUnit.SECONDS)
            assertEquals(202, result.response.status, result.response.contentAsString)
            val candidate = tree(result).path("candidate")
            val createdAt = Instant.parse(candidate.path("created_at").stringValue())
            val referenceAt = Instant.parse(candidate.path("interpretation_reference_at").stringValue())
            assertEquals(afterMidnight, createdAt)
            assertEquals(if (mode == "CHOICE") beforeMidnight else createdAt, referenceAt)
            if (source !=
                null
            ) {
                assertEquals(
                    beforeMidnight,
                    Instant.parse(requireNotNull(source).path("candidate").path("interpretation_reference_at").stringValue()),
                )
            }
            assertEquals(if (mode == "CREATE") 0 else 1, REQUESTS.size)
        } finally {
            blocker.rollback()
            blocker.close()
            future?.cancel(true)
            executor.shutdownNow()
        }
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
