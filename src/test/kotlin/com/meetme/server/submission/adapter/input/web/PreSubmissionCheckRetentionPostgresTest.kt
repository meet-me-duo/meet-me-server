package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent PS29: actual PG, real RS256 and VERIFIED guest issuance; synthetic provider only.
 * Oracles come from PS29, the accepted retention contract and public SQL relations, not service implementation.
 */
@SpringBootTest(
    properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false", "meetme.operations.cleanup-enabled=false"],
)
@AutoConfigureMockMvc
@Import(PreSubmissionCheckRetentionPostgresTest.IsolatedSecurity::class)
class PreSubmissionCheckRetentionPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var retention: RoomDataRetentionPort
    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        removeFault()
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        RESPONSE.set(PrecheckProviderResult(PrecheckVerdict.CLEAR))
        CALLS.set(0)
    }

    @Test
    fun `R01 PS29 closed anchor removes every private precheck row without extending retention to recent answers`() {
        val expired = fullHistory(close = true)
        val fresh = fullHistory(close = true)
        jdbc.update("UPDATE meeting_rooms SET created_at=?,closed_at=? WHERE id=?", time(32), time(31), expired.room.id)
        jdbc.update("UPDATE meeting_rooms SET created_at=?,closed_at=? WHERE id=?", time(40), time(29), fresh.room.id)
        val freshBefore = roomRows(fresh.room)
        assertEquals(listOf(3, 3, 3, 4), newRowCounts(expired.room))
        assertTrue(
            jdbc
                .queryForObject(
                    "SELECT max(created_at) FROM precheck_candidates WHERE room_id=?",
                    Timestamp::class.java,
                    expired.room.id,
                )!!
                .toInstant() >
                START.minus(Duration.ofDays(30)),
        )
        val calls = CALLS.get()
        assertEquals(1, retention.deleteExpiredRooms(START.minus(Duration.ofDays(30)), 100, START))
        assertEquals(listOf(0, 0, 0, 0), newRowCounts(expired.room))
        assertEquals(0, roomCount(expired.room))
        assertEquals(freshBefore, roomRows(fresh.room), "a neighboring unexpired room must retain exact raw/question/answer provenance")
        assertEquals(listOf(3, 3, 3, 4), newRowCounts(fresh.room))
        assertEquals(calls, CALLS.get(), "retention cannot invoke any provider")
        assertNoOrphans()
    }

    @Test
    fun `R02 PS29 collecting rooms retain the original created anchor and preserve the fresh neighboring room`() {
        val expired = fullHistory(close = false)
        val fresh = fullHistory(close = false)
        jdbc.update("UPDATE meeting_rooms SET created_at=? WHERE id=?", time(31), expired.room.id)
        jdbc.update("UPDATE meeting_rooms SET created_at=? WHERE id=?", time(29), fresh.room.id)
        val before = roomRows(fresh.room)
        assertEquals(1, retention.deleteExpiredRooms(START.minus(Duration.ofDays(30)), 100, START))
        assertEquals(0, roomCount(expired.room))
        assertEquals(listOf(0, 0, 0, 0), newRowCounts(expired.room))
        assertEquals(before, roomRows(fresh.room))
        assertNoOrphans()
    }

    @Test
    fun `R03 PS29 failure after execution insert rolls back candidate check execution and operation together`() {
        val neighbor = fullHistory(close = false)
        val room = room()
        val owner = verifiedGuest(room)
        val before = stored()
        val key = UUID.randomUUID()
        withFault("precheck_executions", "INSERT") {
            assertHttpFailure(createRequest(room, owner, RAW, key = key))
            assertEquals(before, stored())
            assertEquals(0, newRowCounts(room).sum())
            assertEquals(listOf(3, 3, 3, 4), newRowCounts(neighbor.room))
        }
        val recovered = create(room, owner, RAW, key = key)
        assertEquals("QUEUED", recovered.path("check").path("status").stringValue())
        assertEquals(listOf(1, 1, 1, 1), newRowCounts(room))
        assertEquals(2, CALLS.get(), "failed persistence and recovery must not make model calls")
        assertNoOrphans()
    }

    @Test
    fun `R04 PS29 operation persistence failure does not consume the CREATE idempotency key or leave a head`() {
        val room = room()
        val owner = verifiedGuest(room)
        val before = stored()
        val key = UUID.randomUUID()
        withFault("precheck_operations", "INSERT") {
            assertHttpFailure(createRequest(room, owner, RAW, key = key))
            assertEquals(before, stored())
            assertEquals(0, newRowCounts(room).sum())
        }
        val first = create(room, owner, RAW, key = key)
        val replay = create(room, owner, RAW, key = key)
        assertEquals(first.path("candidate"), replay.path("candidate"))
        assertEquals(first.path("check").path("id"), replay.path("check").path("id"))
        assertEquals(listOf(1, 1, 1, 1), newRowCounts(room))
        assertEquals(0, CALLS.get())
    }

    @Test
    fun `R05 PS29 failed answer transaction retains the original question and rolls back every derived provenance row`() {
        val room = room()
        val owner = verifiedGuest(room)
        RESPONSE.set(question())
        val original = create(room, owner, AMBIGUOUS)
        assertTrue(worker.runOne())
        val asked = current(room, owner)
        assertEquals("NEEDS_CHOICE", asked.path("check").path("status").stringValue())
        val before = stored()
        val key = UUID.randomUUID()
        withFault("precheck_operations", "INSERT") {
            assertHttpFailure(mutation(room, owner, asked, "answer", key, answerFields(asked)))
            assertEquals(before, stored(), "question, original raw, operations, source links and accepted state must roll back atomically")
            assertEquals(asked, current(room, owner))
            assertEquals(listOf(1, 1, 1, 1), newRowCounts(room))
        }
        val answered = perform(mutation(room, owner, asked, "answer", key, answerFields(asked)), 202)
        assertProvenance(original, asked, answered)
        val replay = perform(mutation(room, owner, asked, "answer", key, answerFields(asked)), 202)
        assertEquals(answered.path("candidate"), replay.path("candidate"))
        assertEquals(listOf(2, 2, 2, 2), newRowCounts(room))
        assertEquals(1, CALLS.get(), "answer acceptance/replay only queues checking")
    }

    @Test
    fun `R06 PS29 failure after accepted pointer update cannot commit a version or false completion`() {
        val neighbor = fullHistory(close = false)
        val neighborBefore = roomRows(neighbor.room)
        val room = room()
        val owner = verifiedGuest(room)
        RESPONSE.set(PrecheckProviderResult(PrecheckVerdict.CLEAR))
        val saved = create(room, owner, RAW)
        withFault(
            "precheck_candidates",
            "UPDATE",
            "WHEN (NEW.accepted_version_id IS NOT NULL AND OLD.accepted_version_id IS DISTINCT FROM NEW.accepted_version_id)",
        ) {
            invokeFault { worker.runOne() }
            val current = current(room, owner)
            assertEquals(saved.path("check").path("id"), current.path("check").path("id"))
            assertEquals(saved.path("candidate").path("revision"), current.path("candidate").path("revision"))
            assertEquals(RAW, current.path("candidate").path("raw_text").stringValue())
            assertTrue(current.path("accepted").isNull)
            assertFalse(current.path("submission_complete").booleanValue())
            assertTrue(current.path("check").path("status").stringValue() != "CLEAR")
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM submission_heads WHERE room_id=?", Int::class.java, room.id))
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id WHERE h.room_id=?",
                    Int::class.java,
                    room.id,
                ),
            )
            assertEquals(neighborBefore, roomRows(neighbor.room))
            assertEquals(listOf(1, 1, 1, 1), newRowCounts(room))
        }
        assertEquals(3, CALLS.get(), "the admitted failed publication remains a single real synthetic provider attempt")
        assertNoOrphans()
    }

    @Test
    fun `R07 PS29 foreign keys reject missing parents and a participant from another room without mutating neighbors`() {
        val one = fullHistory(close = false)
        val two = fullHistory(close = false)
        val before = stored()
        val candidate =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM precheck_candidates WHERE room_id=? ORDER BY revision LIMIT 1",
                    UUID::class.java,
                    one.room.id,
                ),
            )
        val check = requireNotNull(jdbc.queryForObject("SELECT id FROM precheck_checks WHERE candidate_id=?", UUID::class.java, candidate))
        val updates =
            listOf(
                Triple("UPDATE precheck_candidates SET room_id=? WHERE id=?", two.room.id, candidate),
                Triple("UPDATE precheck_candidates SET source_candidate_id=? WHERE id=?", UUID.randomUUID(), candidate),
                Triple("UPDATE precheck_candidates SET accepted_version_id=? WHERE id=?", UUID.randomUUID(), candidate),
                Triple("UPDATE precheck_checks SET candidate_id=? WHERE id=?", UUID.randomUUID(), check),
                Triple("UPDATE precheck_executions SET check_id=? WHERE check_id=?", UUID.randomUUID(), check),
                Triple("UPDATE precheck_operations SET participant_id=? WHERE participant_id=?", UUID.randomUUID(), one.owner.id),
            )
        for ((sql, value, id) in updates) {
            val failure = runCatching { jdbc.update(sql, value, id) }.exceptionOrNull()
            assertTrue(failure is DataAccessException, "database must reject the invalid relation: $sql")
            assertTrue(
                generateSequence<Throwable>(failure) {
                    it.cause
                }.filterIsInstance<SQLException>().any { it.sqlState == "23503" },
                "must be an actual FK rejection",
            )
            assertEquals(before, stored())
        }
        assertNoOrphans()
    }

    @Test
    fun `R08 PS29 failure during cascade retention rolls back the room and every private child before safe retry`() {
        val expired = fullHistory(close = true)
        val fresh = fullHistory(close = false)
        jdbc.update("UPDATE meeting_rooms SET created_at=?,closed_at=? WHERE id=?", time(32), time(31), expired.room.id)
        val before = stored()
        val freshBefore = roomRows(fresh.room)
        withFault("precheck_candidates", "DELETE") {
            invokeFault { retention.deleteExpiredRooms(START.minus(Duration.ofDays(30)), 100, START) }
            assertEquals(before, stored(), "a partial privacy cleanup is not a successful atomic retention operation")
            assertEquals(freshBefore, roomRows(fresh.room))
            assertEquals(listOf(3, 3, 3, 4), newRowCounts(expired.room))
        }
        assertEquals(1, retention.deleteExpiredRooms(START.minus(Duration.ofDays(30)), 100, START))
        assertEquals(0, roomCount(expired.room))
        assertEquals(listOf(0, 0, 0, 0), newRowCounts(expired.room))
        assertEquals(freshBefore, roomRows(fresh.room))
        assertNoOrphans()
    }

    private fun fullHistory(close: Boolean): History {
        val room = room()
        val owner = verifiedGuest(room)
        RESPONSE.set(question())
        val original = create(room, owner, AMBIGUOUS)
        assertTrue(worker.runOne())
        val asked = current(room, owner)
        assertEquals("NEEDS_CHOICE", asked.path("check").path("status").stringValue())
        val answer = perform(mutation(room, owner, asked, "answer", UUID.randomUUID(), answerFields(asked)), 202)
        assertProvenance(original, asked, answer)
        RESPONSE.set(PrecheckProviderResult(PrecheckVerdict.CLEAR))
        assertTrue(worker.runOne())
        assertTrue(current(room, owner).path("submission_complete").booleanValue())
        val pending = create(room, owner, "아직 적용되지 않은 새 합성 수정", revision = 2)
        perform(mutation(room, owner, pending, "cancel", UUID.randomUUID()), 200)
        if (close) {
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/close")
                        .header(HttpHeaders.AUTHORIZATION, room.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm_early\":true}"),
                ).andExpect(status().isOk)
        }
        assertEquals(listOf(3, 3, 3, 4), newRowCounts(room))
        return History(room, owner)
    }

    private fun assertProvenance(
        original: JsonNode,
        asked: JsonNode,
        derived: JsonNode,
    ) {
        val row =
            jdbc.queryForMap(
                "SELECT source_candidate_id,source_question_id,selected_choice_id FROM precheck_candidates WHERE id=?",
                candidateId(derived),
            )
        assertEquals(candidateId(original), row["source_candidate_id"])
        assertEquals(
            UUID.fromString(
                asked
                    .path("check")
                    .path("question")
                    .path("id")
                    .stringValue(),
            ),
            row["source_question_id"],
        )
        assertEquals("AM", row["selected_choice_id"])
        assertEquals(
            AMBIGUOUS,
            jdbc.queryForObject("SELECT raw_text FROM precheck_candidates WHERE id=?", String::class.java, row["source_candidate_id"]),
        )
        val questionText =
            jdbc.queryForObject(
                "SELECT question::text FROM precheck_checks WHERE candidate_id=?",
                String::class.java,
                row["source_candidate_id"],
            )
        assertNotNull(questionText)
        assertTrue(questionText.contains(row["source_question_id"].toString()))
    }

    private fun candidateId(snapshot: JsonNode): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT candidate_id FROM precheck_checks WHERE id=?",
                UUID::class.java,
                UUID.fromString(snapshot.path("check").path("id").stringValue()),
            ),
        )

    private fun stored(): Map<String, List<String>> =
        TABLES.associateWith { table -> rows("SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text") }

    private fun roomRows(room: Room): Map<String, List<String>> =
        mapOf(
            "room" to rows("SELECT to_jsonb(t)::text FROM meeting_rooms t WHERE id=?", room.id),
            "participants" to rows("SELECT to_jsonb(t)::text FROM participants t WHERE room_id=? ORDER BY id", room.id),
            "grants" to rows("SELECT to_jsonb(t)::text FROM guest_reaccess_grants t WHERE room_id=? ORDER BY guest_session_id", room.id),
            "passwords" to
                rows(
                    "SELECT to_jsonb(t)::text FROM guest_participant_passwords t WHERE participant_id IN (SELECT id FROM participants WHERE room_id=?) ORDER BY participant_id",
                    room.id,
                ),
            "candidates" to rows("SELECT to_jsonb(t)::text FROM precheck_candidates t WHERE room_id=? ORDER BY id", room.id),
            "checks" to
                rows(
                    "SELECT to_jsonb(t)::text FROM precheck_checks t WHERE candidate_id IN (SELECT id FROM precheck_candidates WHERE room_id=?) ORDER BY id",
                    room.id,
                ),
            "executions" to
                rows(
                    "SELECT to_jsonb(t)::text FROM precheck_executions t WHERE check_id IN (SELECT c.id FROM precheck_checks c JOIN precheck_candidates p ON p.id=c.candidate_id WHERE p.room_id=?) ORDER BY check_id,generation",
                    room.id,
                ),
            "operations" to
                rows(
                    "SELECT to_jsonb(t)::text FROM precheck_operations t WHERE participant_id IN (SELECT id FROM participants WHERE room_id=?) ORDER BY participant_id,kind,request_id",
                    room.id,
                ),
            "heads" to rows("SELECT to_jsonb(t)::text FROM submission_heads t WHERE room_id=? ORDER BY id", room.id),
            "versions" to
                rows(
                    "SELECT to_jsonb(t)::text FROM submission_versions t WHERE submission_id IN (SELECT id FROM submission_heads WHERE room_id=?) ORDER BY id",
                    room.id,
                ),
        )

    private fun rows(
        sql: String,
        vararg args: Any,
    ): List<String> = jdbc.queryForList(sql, String::class.java, *args).map { requireNotNull(it) }

    private fun newRowCounts(room: Room): List<Int> =
        roomRows(room).let { r ->
            listOf("candidates", "checks", "executions", "operations").map {
                requireNotNull(r[it]).size
            }
        }

    private fun roomCount(room: Room): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM meeting_rooms WHERE id=?", Int::class.java, room.id))

    private fun assertNoOrphans() {
        val queries =
            listOf(
                "SELECT count(*) FROM precheck_candidates c LEFT JOIN participants p ON p.id=c.participant_id AND p.room_id=c.room_id WHERE p.id IS NULL",
                "SELECT count(*) FROM precheck_checks c LEFT JOIN precheck_candidates p ON p.id=c.candidate_id WHERE p.id IS NULL",
                "SELECT count(*) FROM precheck_executions e LEFT JOIN precheck_checks c ON c.id=e.check_id WHERE c.id IS NULL",
                "SELECT count(*) FROM precheck_operations o LEFT JOIN participants p ON p.id=o.participant_id WHERE p.id IS NULL",
                "SELECT count(*) FROM precheck_candidates c LEFT JOIN precheck_candidates s ON s.id=c.source_candidate_id WHERE c.source_candidate_id IS NOT NULL AND s.id IS NULL",
                "SELECT count(*) FROM precheck_candidates c LEFT JOIN submission_versions v ON v.id=c.accepted_version_id WHERE c.accepted_version_id IS NOT NULL AND v.id IS NULL",
            )
        queries.forEach { sql -> assertEquals(0, jdbc.queryForObject(sql, Int::class.java), sql) }
    }

    private fun withFault(
        table: String,
        event: String,
        condition: String = "",
        body: () -> Unit,
    ) {
        jdbc.execute("CREATE SEQUENCE ps29_fault_seen")
        jdbc.execute(
            "CREATE FUNCTION ps29_injected_failure() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM nextval(''ps29_fault_seen''); RAISE EXCEPTION ''synthetic_ps29_failure''; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER ps29_injected_failure AFTER $event ON $table FOR EACH ROW $condition EXECUTE FUNCTION ps29_injected_failure()",
        )
        try {
            body()
        } finally {
            val reached = jdbc.queryForObject("SELECT is_called FROM ps29_fault_seen", Boolean::class.java)
            removeFault()
            assertEquals(true, reached, "failure must actually reach the injected write; a setup failure is not rollback evidence")
        }
    }

    private fun removeFault() {
        jdbc.execute("DROP FUNCTION IF EXISTS ps29_injected_failure() CASCADE")
        jdbc.execute("DROP SEQUENCE IF EXISTS ps29_fault_seen")
    }

    private fun assertHttpFailure(request: MockHttpServletRequestBuilder) {
        var result: MvcResult? = null
        val failure = runCatching { result = mvc.perform(request).andReturn() }.exceptionOrNull()
        if (failure ==
            null
        ) {
            assertTrue(
                requireNotNull(result).response.status in 500..599,
                "injected storage failure must not be acknowledged as accepted",
            )
        } else {
            assertInjected(failure)
        }
    }

    private fun invokeFault(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let(::assertInjected)
    }

    private fun assertInjected(failure: Throwable) {
        assertTrue(
            generateSequence<Throwable>(failure) {
                it.cause
            }.any { it.message?.contains("synthetic_ps29_failure") == true },
            "unexpected failure instead of injected storage fault: $failure",
        )
    }

    private fun answerFields(snapshot: JsonNode): Map<String, Any> =
        mapOf(
            "question_id" to
                snapshot
                    .path("check")
                    .path("question")
                    .path("id")
                    .stringValue(),
            "choice_id" to "AM",
        )

    private fun mutation(
        room: Room,
        owner: Guest,
        snapshot: JsonNode,
        action: String,
        key: UUID,
        extra: Map<String, Any> = emptyMap(),
    ): MockHttpServletRequestBuilder =
        post("${path(room)}/${snapshot.path("check").path("id").stringValue()}/$action")
            .cookie(owner.cookie)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    mapOf<String, Any>(
                        "request_id" to key,
                        "expected_revision" to snapshot.path("candidate").path("revision").intValue(),
                        "expected_check_version" to snapshot.path("check").path("version").longValue(),
                    ) +
                        extra,
                ),
            )

    private fun create(
        room: Room,
        owner: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): JsonNode = perform(createRequest(room, owner, text, revision, key), 202)

    private fun createRequest(
        room: Room,
        owner: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletRequestBuilder =
        post(path(room))
            .cookie(owner.cookie)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("request_id" to key, "expected_revision" to revision, "raw_text" to text)))

    private fun current(
        room: Room,
        owner: Guest,
    ): JsonNode = perform(get("${path(room)}/current").cookie(owner.cookie), 200)

    private fun path(room: Room): String = "/api/rooms/${room.invite}/submission/checks"

    private fun time(daysAgo: Long): Timestamp = Timestamp.from(START.minus(Duration.ofDays(daysAgo)))

    private fun perform(
        request: MockHttpServletRequestBuilder,
        expected: Int,
    ): JsonNode {
        val result = mvc.perform(request).andReturn()
        assertEquals(expected, result.response.status, result.response.contentAsString)
        return json.readTree(result.response.contentAsByteArray)
    }

    private fun room(): Room {
        val principal = AccountPrincipal(UUID.randomUUID(), "PS29 합성 계정")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            Timestamp.from(START),
        )
        val bearer = "Bearer ${tokens.issue(principal)}"
        val response =
            perform(
                post("/api/rooms")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .header("Origin", ORIGIN)
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("{\"purpose\":\"PS29 독립검증\",\"meeting_mode\":\"EITHER\",\"manual_only\":true}"),
                201,
            )
        val invite = response.path("invite_code").stringValue()
        return Room(
            requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite)),
            invite,
            bearer,
        )
    }

    private fun verifiedGuest(room: Room): Guest {
        val joined =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/participants")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapOf("display_name" to "PS29 합성 게스트", "password" to PASSWORD))),
                ).andExpect(status().isCreated)
                .andReturn()
        val originalCookie = cookie(joined)
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT p.id FROM participants p JOIN guest_browser_sessions s ON s.id=p.guest_session_id WHERE p.room_id=? AND s.credential_digest=?",
                    UUID::class.java,
                    room.id,
                    credentials.digest(originalCookie.value),
                ),
            )
        val restored =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/guest-records/reaccess")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapOf("participant_id" to id, "password" to PASSWORD))),
                ).andExpect(status().isOk)
                .andReturn()
        assertEquals("VERIFIED_GUEST", json.readTree(restored.response.contentAsByteArray).path("level").stringValue())
        return Guest(id, cookie(restored))
    }

    private fun cookie(result: MvcResult): Cookie =
        Cookie("meet_me_guest", requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter('=').substringBefore(';'))

    private data class Room(
        val id: UUID,
        val invite: String,
        val bearer: String,
    )

    private data class Guest(
        val id: UUID,
        val cookie: Cookie,
    )

    private data class History(
        val room: Room,
        val owner: Guest,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IsolatedSecurity {
        @Bean @Primary
        fun ps29SyntheticProvider(): PreSubmissionCheckProvider =
            PreSubmissionCheckProvider {
                CALLS.incrementAndGet()
                RESPONSE.get()
            }

        @Bean @Primary
        fun ps29Clock(): Clock = Clock.fixed(START, ZoneOffset.UTC)

        @Bean @Primary
        fun ps29Tokens(clock: Clock): AccessTokenPort =
            Rs256AccessTokenAdapter(KEYS.private, mapOf("ps29" to KEYS.public), "ps29", clock, "https://synthetic.invalid", "meetme-web")

        @Bean @Primary
        fun ps29Limiter(): RateLimitPort = RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) }
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val PASSWORD = "synthetic-ps29-password"
        private const val RAW = "PS29 외부 유출 금지 합성 원문"
        private const val AMBIGUOUS = "내일 8시 온라인 가능"
        private val START = Instant.parse("2026-10-17T12:00:00Z")
        private val RESPONSE = AtomicReference(PrecheckProviderResult(PrecheckVerdict.CLEAR))
        private val CALLS = AtomicInteger()
        private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val TABLES =
            listOf(
                "meeting_rooms",
                "participants",
                "guest_browser_sessions",
                "guest_reaccess_grants",
                "guest_participant_passwords",
                "precheck_candidates",
                "precheck_checks",
                "precheck_executions",
                "precheck_operations",
                "submission_heads",
                "submission_versions",
                "submission_batches",
                "submission_batch_items",
                "coordination_runs",
                "outbox_events",
            )

        private fun question(): PrecheckProviderResult =
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                "오전과 오후 중 어느 뜻인가요?",
                "8시",
                listOf(PrecheckChoice("AM", "오전", "내일 오전 8시 온라인 가능"), PrecheckChoice("PM", "오후", "내일 오후 8시 온라인 가능")),
            )

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
