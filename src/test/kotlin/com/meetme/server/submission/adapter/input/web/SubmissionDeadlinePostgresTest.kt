package com.meetme.server.submission.adapter.input.web

import com.meetme.server.coordination.adapter.input.web.CorrectionRoundPostgresFixture
import com.meetme.server.meetingroom.application.service.DeadlineClosureScheduler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Independent regression specification: PRD FR-008A/FR-010/Rule 4 and ADR-046.
// Real PostgreSQL; public HTTP commands; synthetic data; no provider execution.
@Import(SubmissionDeadlinePostgresTest.DeadlineClockConfiguration::class)
class SubmissionDeadlinePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var clock: DeadlineClock

    @Autowired private lateinit var scheduler: DeadlineClosureScheduler

    @BeforeEach
    fun resetDeadlineClock() {
        clock.set(START)
    }

    @ParameterizedTest(name = "{0}, deadline offset {1}ns, expected participant policy {2}")
    @MethodSource("boundaryCases")
    fun `initial collection accepts only instants strictly before deadline without scheduler assistance`(
        operation: String,
        offsetNanos: Long,
        withExpectedCount: Boolean,
    ) {
        val host = createDeadlineRoom(if (withExpectedCount) 4 else null)
        if (operation != "FIRST") mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        val before = databaseFingerprint()
        clock.set(DEADLINE.plusNanos(offsetNanos))
        val text =
            when (operation) {
                "FIRST" -> ORIGINAL
                "EDIT" -> REVISED
                else -> " \t$ORIGINAL\n"
            }

        if (offsetNanos < 0) {
            mvc
                .perform(save(host, text))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.revision").value(if (operation == "EDIT") 2 else 1))
            assertEquals(1, count("submission_heads"))
            assertEquals(if (operation == "EDIT") 2 else 1, count("submission_versions"))
            if (operation == "SAME") assertEquals(before, databaseFingerprint())
        } else {
            rejectUnchanged(save(host, text), before)
        }
        assertEquals("COLLECTING", roomRow()["collection_status"])
        assertEquals(0, count("submission_batches"))
        assertEquals(0, count("coordination_runs"))
        assertEquals(0, count("outbox_events"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `scheduler freezes only timely versions and counts zero one or two submitted members`(timelyMembers: Int) {
        val host = createDeadlineRoom(expected = 4)
        val members = (0..2).map { join(host.code, "synthetic member $it") }
        repeat(timelyMembers) { index ->
            mvc.perform(save(members[index], ORIGINAL)).andExpect(status().isOk)
        }
        if (timelyMembers > 0) mvc.perform(save(members[0], REVISED)).andExpect(status().isOk)
        val timelyVersions = latestIds()
        clock.set(DEADLINE)
        val before = databaseFingerprint()
        rejectUnchanged(save(host, "late first HOST input"), before)
        rejectUnchanged(save(members[2], "late first MEMBER input"), before)
        if (timelyMembers > 0) {
            rejectUnchanged(save(members[0], "late revision"), before)
            rejectUnchanged(save(members[0], " \t$REVISED\n"), before)
        }

        scheduler.closeDueRooms()

        assertDeadlineClosed(timelyMembers, timelyVersions, host)
        val after = databaseFingerprint()
        scheduler.closeDueRooms()
        assertEquals(after, databaseFingerprint(), "Repeated scans must not create another batch/run/outbox")
        rejectUnchanged(save(host, "still too late"), after)
    }

    @Test
    fun `timely Nth unique submission closes by expected participants and freezes exactly N versions`() {
        val host = createDeadlineRoom(expected = 2)
        val member = join(host.code, "timely Nth member")
        val lateMember = join(host.code, "extra member")
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        clock.set(DEADLINE.minusNanos(1_000))
        mvc.perform(save(member, REVISED)).andExpect(status().isOk)
        assertEquals("CLOSED", roomRow()["collection_status"])
        assertEquals("EXPECTED_PARTICIPANTS", roomRow()["closure_reason"])
        assertEquals(latestIds(), frozenIds())
        assertEquals(2, frozenIds().size)
        assertEquals(1, count("submission_batches"))
        assertEquals(1, count("coordination_runs"))
        assertEquals(1, count("outbox_events"))
        clock.set(DEADLINE)
        rejectUnchanged(save(lateMember, "late extra input"), databaseFingerprint())
    }

    @Test
    fun `late Nth submission cannot bypass deadline by satisfying expected participant count`() {
        val host = createDeadlineRoom(expected = 2)
        val member = join(host.code, "late Nth member")
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        clock.set(DEADLINE)
        rejectUnchanged(save(member, REVISED), databaseFingerprint())
        assertEquals("COLLECTING", roomRow()["collection_status"])
        scheduler.closeDueRooms()
        assertDeadlineClosed(1, latestIds(), host)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `rooms without deadline retain manual and expected participant submission behavior`(withExpectedCount: Boolean) {
        val host = createDeadlineRoom(if (withExpectedCount) 4 else null, deadline = null)
        clock.set(DEADLINE.plusSeconds(1))
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        mvc
            .perform(save(host, REVISED))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        assertEquals(1, count("submission_heads"))
        assertEquals(2, count("submission_versions"))
        assertEquals("COLLECTING", roomRow()["collection_status"])
        assertEquals(0, count("submission_batches"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "EXPIRED", "REVOKED", "OTHER_ROOM", "BAD_INVITE"])
    fun `deadline rejection preserves existing authentication authorization and room error precedence`(case: String) {
        val host = createDeadlineRoom(expected = 4)
        val request =
            when (case) {
                "MISSING" ->
                    put("/api/rooms/{code}/submission", host.code)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("raw_text" to ORIGINAL)))
                "INVALID" -> save(CorrectionSession(host.code, "synthetic-invalid-credential"), ORIGINAL)
                "EXPIRED" -> {
                    jdbc.update("UPDATE guest_browser_sessions SET expires_at = ?", DEADLINE.minusNanos(1_000).atOffset(ZoneOffset.UTC))
                    save(host, ORIGINAL)
                }
                "REVOKED" -> {
                    jdbc.update("UPDATE guest_browser_sessions SET revoked_at = ?", START.atOffset(ZoneOffset.UTC))
                    save(host, ORIGINAL)
                }
                "OTHER_ROOM" -> {
                    val other = createDeadlineRoom(expected = 4)
                    save(CorrectionSession(host.code, other.credential), ORIGINAL)
                }
                else -> save(CorrectionSession("malformed", host.credential), ORIGINAL)
            }
        val expected =
            when (case) {
                "MISSING" -> 401 to "GUEST_SESSION_REQUIRED"
                "INVALID", "EXPIRED", "REVOKED" -> 401 to "GUEST_SESSION_INVALID"
                "OTHER_ROOM" -> 403 to "PARTICIPANT_REQUIRED"
                else -> 404 to "ROOM_NOT_FOUND"
            }
        clock.set(DEADLINE)
        val before = databaseFingerprint()
        mvc.perform(request).andExpect(status().`is`(expected.first)).andExpect(jsonPath("$.code").value(expected.second))
        assertEquals(before, databaseFingerprint())
    }

    @ParameterizedTest
    @ValueSource(strings = ["FIRST", "EDIT", "SAME"])
    fun `a request queued on the PostgreSQL room lock must recheck time after acquiring it`(operation: String) {
        val host = createDeadlineRoom(expected = 4)
        if (operation != "FIRST") mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        val before = databaseFingerprint()
        val roomId = roomId(host)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val requestPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val blocker =
            executor.submit {
                newTransaction().execute {
                    lockRoom(roomId)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Room-lock release timed out" }
                }
            }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS), "Room lock was not acquired")
            clock.set(DEADLINE.minusNanos(1_000))
            val result =
                executor.submit<Int> {
                    requireNotNull(
                        newTransaction().execute { transaction ->
                            requestPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            started.countDown()
                            val text =
                                when (operation) {
                                    "EDIT" -> REVISED
                                    "SAME" -> " \t$ORIGINAL\n"
                                    else -> ORIGINAL
                                }
                            val response = mvc.perform(save(host, text)).andReturn().response
                            if (response.status >= 400) transaction.setRollbackOnly()
                            assertEquals("ROOM_CLOSED", document(response)["code"])
                            response.status
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS), "PUT did not start its PostgreSQL transaction")
            assertBlocked(requestPid.get())
            clock.set(DEADLINE)
            release.countDown()
            assertEquals(409, result.get(10, TimeUnit.SECONDS))
            blocker.get(10, TimeUnit.SECONDS)
            assertEquals(before, databaseFingerprint())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `scheduler skips a room locked by a rejected late PUT and closes it on the next scan`() {
        val host = createDeadlineRoom(expected = 4)
        val member = join(host.code, "timely member")
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        mvc.perform(save(member, REVISED)).andExpect(status().isOk)
        val timelyVersions = latestIds()
        val before = databaseFingerprint()
        clock.set(DEADLINE)
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responseCode = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val pending =
            executor.submit {
                newTransaction().execute { transaction ->
                    lockRoom(roomId(host))
                    val response = mvc.perform(save(host, "late revision under held lock")).andReturn().response
                    responseCode.set(response.status)
                    written.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Late PUT transaction release timed out" }
                    transaction.setRollbackOnly()
                }
            }
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS), "Late PUT did not complete under the held room lock")
            assertEquals(409, responseCode.get())
            scheduler.closeDueRooms()
            assertEquals(before, databaseFingerprint(), "SKIP LOCKED scan must not mutate this held room")
            release.countDown()
            pending.get(10, TimeUnit.SECONDS)
            scheduler.closeDueRooms()
            assertDeadlineClosed(2, timelyVersions, host)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `scheduler closing first rejects a queued PUT and preserves the already frozen timely versions`() {
        val host = createDeadlineRoom(expected = 4)
        val member = join(host.code, "timely member")
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        mvc.perform(save(member, REVISED)).andExpect(status().isOk)
        val timelyVersions = latestIds()
        clock.set(DEADLINE)
        val closed = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val requestPid = AtomicInteger()
        val frozenState = AtomicReference<Map<String, String>>()
        val executor = Executors.newFixedThreadPool(2)
        val closure =
            executor.submit {
                newTransaction().execute {
                    scheduler.closeDueRooms()
                    frozenState.set(databaseFingerprint())
                    closed.countDown()
                    check(commit.await(10, TimeUnit.SECONDS)) { "Scheduler transaction release timed out" }
                }
            }
        try {
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Scheduler did not reach held commit")
            val late =
                executor.submit<Int> {
                    requireNotNull(
                        newTransaction().execute { transaction ->
                            requestPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            requestStarted.countDown()
                            val response = mvc.perform(save(host, "late revision after scheduler")).andReturn().response
                            if (response.status >= 400) transaction.setRollbackOnly()
                            assertEquals("ROOM_CLOSED", document(response)["code"])
                            response.status
                        },
                    )
                }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "Concurrent PUT did not start")
            assertBlocked(requestPid.get())
            commit.countDown()
            closure.get(10, TimeUnit.SECONDS)
            assertEquals(409, late.get(10, TimeUnit.SECONDS))
            assertEquals(frozenState.get(), databaseFingerprint())
            assertDeadlineClosed(2, timelyVersions, host)
        } finally {
            commit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `expired initial deadline does not reject a valid CLOSED correction round or weaken its revision checks`() {
        val host = createDeadlineRoom(expected = 4)
        val member = join(host.code, "correction member")
        mvc.perform(save(host, ORIGINAL)).andExpect(status().isOk)
        mvc.perform(save(member, REVISED)).andExpect(status().isOk)
        clock.set(DEADLINE)
        scheduler.closeDueRooms()
        val roomId = roomId(host)
        val runId = requireNotNull(jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id = ?", UUID::class.java, roomId))
        completePartial(runId)
        val fixture = CompletedCorrectionFixture(host, member, roomId, runId)
        val roundId = openRound(fixture)
        val frozenVersions = frozenIds()
        val work = workCounts()
        val closedAt = closedAt(fixture)
        clock.set(DEADLINE.plusSeconds(1))

        mvc
            .perform(save(host, "valid post-deadline correction", roundId, 1))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        assertEquals("CLOSED", roomRow()["collection_status"])
        assertEquals(closedAt, closedAt(fixture))
        assertEquals(frozenVersions, frozenIds())
        assertEquals(work, workCounts(), "Correction storage must not start another analysis")
        val corrected = databaseFingerprint()
        mvc.perform(save(host, " \tvalid post-deadline correction\n", roundId, 2)).andExpect(status().isOk)
        assertEquals(corrected, databaseFingerprint())
        mvc
            .perform(save(host, "stale correction", roundId, 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(corrected, databaseFingerprint())
        mvc
            .perform(save(host, "missing round"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(corrected, databaseFingerprint())
    }

    private fun createDeadlineRoom(
        expected: Int?,
        deadline: Instant? = DEADLINE,
    ): CorrectionSession {
        val response =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "purpose" to "synthetic deadline regression",
                                    "meeting_mode" to "REMOTE",
                                    "host_display_name" to "synthetic host",
                                    "expected_participants" to expected,
                                    "submission_deadline" to deadline?.toString(),
                                    "manual_only" to (expected == null && deadline == null),
                                ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val credential =
            requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE))
                .substringAfter("meet_me_guest=")
                .substringBefore(';')
        return CorrectionSession(document(response).getValue("invite_code").toString(), credential)
    }

    private fun join(
        code: String,
        name: String,
    ): CorrectionSession {
        val response =
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", code)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("display_name" to name))),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val credential =
            requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE))
                .substringAfter("meet_me_guest=")
                .substringBefore(';')
        return CorrectionSession(code, credential)
    }

    private fun rejectUnchanged(
        request: MockHttpServletRequestBuilder,
        before: Map<String, String>,
    ) {
        mvc.perform(request).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ROOM_CLOSED"))
        assertEquals(before, databaseFingerprint(), "Deadline rejection must leave all persisted state unchanged")
    }

    private fun assertDeadlineClosed(
        timelyCount: Int,
        timelyVersions: List<UUID>,
        host: CorrectionSession,
    ) {
        assertEquals("CLOSED", roomRow()["collection_status"])
        assertEquals("DEADLINE", roomRow()["closure_reason"])
        assertEquals(timelyCount, count("submission_heads"))
        assertEquals(timelyVersions, latestIds())
        assertEquals(if (timelyCount < 2) "INSUFFICIENT_PARTICIPANTS" else "ANALYZING", room(host)["public_status"])
        assertEquals(if (timelyCount < 2) 0 else 1, count("submission_batches"))
        assertEquals(if (timelyCount < 2) 0 else 1, count("coordination_runs"))
        assertEquals(if (timelyCount < 2) 0 else 1, count("outbox_events"))
        assertEquals(if (timelyCount < 2) emptyList() else timelyVersions, frozenIds())
        assertEquals(0, count("coordination_attempts"))
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("candidates"))
    }

    private fun roomRow() = jdbc.queryForMap("SELECT collection_status, closure_reason FROM meeting_rooms")

    private fun roomId(host: CorrectionSession): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, host.code))

    private fun lockRoom(id: UUID) {
        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, id)
    }

    private fun latestIds(): List<UUID> =
        jdbc
            .queryForList("SELECT latest_version_id FROM submission_heads ORDER BY latest_version_id", UUID::class.java)
            .map { requireNotNull(it) { "Every committed submission head must point to an immutable version" } }

    private fun frozenIds(): List<UUID> =
        jdbc
            .queryForList("SELECT submission_version_id FROM submission_batch_items ORDER BY submission_version_id", UUID::class.java)
            .map { requireNotNull(it) { "Every frozen batch item must identify an immutable version" } }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    // Fingerprint entire synthetic rows, including timestamps, latest pointers,
    // room versions and frozen items, without printing text or credential data.
    private fun databaseFingerprint(): Map<String, String> =
        listOf(
            "guest_browser_sessions",
            "participants",
            "meeting_rooms",
            "submission_heads",
            "submission_versions",
            "manual_availability_intervals",
            "submission_batches",
            "submission_batch_items",
            "coordination_runs",
            "outbox_events",
            "coordination_attempts",
            "analysis_invocations",
            "candidates",
            "input_revision_rounds",
        ).associateWith { table ->
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text) FROM $table t",
                    String::class.java,
                ),
            )
        }

    private fun newTransaction() =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 15
        }

    private fun assertBlocked(pid: Int) {
        val stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            val blocked =
                jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? " +
                        "AND wait_event_type = 'Lock' AND cardinality(pg_blocking_pids(pid)) > 0)",
                    Boolean::class.java,
                    pid,
                )
            if (blocked == true) return
            Thread.sleep(10)
        } while (System.nanoTime() < stop)
        assertTrue(false, "PostgreSQL never observed this PUT waiting for the room lock")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DeadlineClockConfiguration {
        @Bean
        @Primary
        fun submissionDeadlineClock() = DeadlineClock()
    }

    class DeadlineClock private constructor(
        private val value: AtomicReference<Instant>,
        private val zone: ZoneId,
    ) : Clock() {
        constructor() : this(AtomicReference(START), ZoneOffset.UTC)

        fun set(now: Instant) = value.set(now)

        override fun instant(): Instant = value.get()

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = DeadlineClock(value, zone)
    }

    companion object {
        private val START = Instant.parse("2026-10-17T12:00:00Z")
        private val DEADLINE = START.plusSeconds(10)
        private const val ORIGINAL = "synthetic original available Friday evening online"
        private const val REVISED = "synthetic revised available Saturday evening online"
        private val postgres =
            PostgreSQLContainer(
                DockerImageName
                    .parse("postgres@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
                    .asCompatibleSubstituteFor("postgres"),
            ).apply { start() }

        @JvmStatic
        fun boundaryCases(): Stream<Arguments> =
            listOf("FIRST", "EDIT", "SAME")
                .flatMap { operation ->
                    listOf(-1_000L, 0L, 1_000L).flatMap { nanos ->
                        listOf(false, true).map { expected -> Arguments.of(operation, nanos, expected) }
                    }
                }.stream()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.submission.deadline-scan-delay-ms") { 86_400_000 }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
