package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.ResumeStage
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #96 / FR-008B,C: actual PostgreSQL, synthetic frozen input, and no provider calls.
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class)
class DurableAnalysisInvocationPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Autowired private lateinit var time: DurableClock

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `simultaneous delivery has one durable owner and cannot extend original deadline`() {
        val run = structuringRoom().second
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims =
                (1..2).map {
                    executor.submit<AnalysisInvocation?> {
                        check(start.await(5, TimeUnit.SECONDS))
                        persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60))
                    }
                }
            start.countDown()
            val claimed = claims.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, claimed.count { it != null })
            val original = assertNotNull(claimed.single { it != null })
            assertEquals(NOW, original.startedAt)
            assertEquals(NOW.plusSeconds(60), original.deadlineAt)
            time.now = NOW.plusSeconds(20)
            assertNull(persistence.claimInvocation(run, original.ownerToken, Duration.ofSeconds(60)))
            assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
            assertEquals(original, invocations.findByRunVersion(run.id, run.version))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `persisted counters enforce four Gemini calls and one Luna across stale snapshots`() {
        val run = structuringRoom().second
        val invocation = claim(run)
        for (number in 1..4) {
            assertTrue(
                persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, number)),
            )
        }
        assertFalse(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 5)))
        val luna = attempt(run, invocation, 5, AnalysisProvider.OPENAI)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, luna))
        assertFalse(
            persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, attempt(run, invocation, 6, AnalysisProvider.OPENAI)),
        )
        val stored = assertNotNull(invocations.findByRunVersion(run.id, run.version))
        assertEquals(4, stored.geminiAttempts)
        assertEquals(1, stored.lunaAttempts)
        assertEquals(5, count("coordination_attempts"))
        val metadata =
            jdbc.queryForMap(
                "SELECT provider, model, policy_version, invocation_id FROM coordination_attempts WHERE id = ?",
                luna.id,
            )
        assertEquals("OPENAI", metadata["provider"])
        assertEquals("gpt-6-luna", metadata["model"])
        assertEquals(AnalysisInvocation.POLICY_VERSION, metadata["policy_version"])
        assertEquals(invocation.id, metadata["invocation_id"])
    }

    @Test
    fun `transaction rollback removes invocation and attempt counters together`() {
        val run = structuringRoom().second
        TransactionTemplate(transactionManager).execute { transaction ->
            val invocation = claim(run)
            assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 1)))
            transaction.setRollbackOnly()
        }
        assertNull(invocations.findByRunVersion(run.id, run.version))
        assertEquals(0, count("coordination_attempts"))
        val invocation = claim(run)
        val first = attempt(run, invocation, 1)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        jdbc.execute(
            """
            CREATE FUNCTION issue96_fail_attempt_insert() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic attempt insert failure''; END'
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER issue96_fail_attempt BEFORE INSERT ON coordination_attempts " +
                "FOR EACH ROW EXECUTE FUNCTION issue96_fail_attempt_insert()",
        )
        try {
            assertThrows<RuntimeException> {
                persistence.claimAttempt(
                    run,
                    invocation,
                    AnalysisProvider.GEMINI,
                    attempt(run, invocation, 2),
                )
            }
        } finally {
            jdbc.execute("DROP TRIGGER issue96_fail_attempt ON coordination_attempts")
            jdbc.execute("DROP FUNCTION issue96_fail_attempt_insert()")
        }
        assertEquals(1, assertNotNull(invocations.findByRunVersion(run.id, run.version)).geminiAttempts)
        assertEquals(1, count("coordination_attempts"))
    }

    @Test
    fun `wrong owner and foreign attempt cannot consume calls or publish`() {
        val run = structuringRoom().second
        val invocation = claim(run)
        val wrongOwner = invocation.copy(ownerToken = UUID.randomUUID())
        assertFalse(persistence.claimAttempt(run, wrongOwner, AnalysisProvider.GEMINI, attempt(run, wrongOwner, 1)))
        val first = attempt(run, invocation, 1)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        assertFalse(persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), wrongOwner) { true })
        assertFalse(
            persistence.completeBounded(run, results(run), first.copy(id = UUID.randomUUID(), finishedAt = NOW), invocation) { true },
        )
        assertEquals(CoordinationStatus.STRUCTURING, runs.findById(run.id)?.status)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertNull(invocations.findByRunVersion(run.id, run.version)?.winnerAttemptId)
    }

    @Test
    fun `only one admitted attempt can win publication and cancellation leaves no results`() {
        val run = structuringRoom().second
        val invocation = claim(run)
        val first = attempt(run, invocation, 1)
        val second = attempt(run, invocation, 2)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, second))
        assertFalse(persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), invocation) { false })
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertTrue(persistence.completeBounded(run, results(run), second.copy(finishedAt = NOW), invocation) { true })
        assertFalse(persistence.completeBounded(run, results(run, "LATE_LOSER"), first.copy(finishedAt = NOW), invocation) { true })
        assertEquals(CoordinationStatus.MATCHING, runs.findById(run.id)?.status)
        assertEquals(results(run).toSet(), structured.findByBatch(run.batch.id).toSet())
        val stored = assertNotNull(invocations.findByRunVersion(run.id, run.version))
        assertEquals(second.id, stored.winnerAttemptId)
        assertEquals(NOW, stored.finishedAt)
    }

    @Test
    fun `deadline is rechecked after waiting for room lock before result publication`() {
        val (fixture, run) = structuringRoom()
        val invocation = claim(run)
        val first = attempt(run, invocation, 1)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        val result =
            whileRoomLocked(fixture.roomId, { time.now = invocation.deadlineAt }) {
                persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), invocation) { true }
            }
        assertFalse(result)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertNotEquals(CoordinationStatus.MATCHING, runs.findById(run.id)?.status)
        assertNull(invocations.findByRunVersion(run.id, run.version)?.winnerAttemptId)
        assertFalse(
            persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, attempt(run, invocation, 2, AnalysisProvider.OPENAI)),
        )
    }

    @Test
    fun `publication storage failure rolls back run winner and results atomically`() {
        val run = structuringRoom().second
        val invocation = claim(run)
        val first = attempt(run, invocation, 1)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        val invalid = results(run) + results(run).first().copy(submissionVersionId = SubmissionVersionId(UUID.randomUUID()))
        assertThrows<RuntimeException> { persistence.completeBounded(run, invalid, first.copy(finishedAt = NOW), invocation) { true } }
        assertEquals(CoordinationStatus.STRUCTURING, runs.findById(run.id)?.status)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertNull(invocations.findByRunVersion(run.id, run.version)?.winnerAttemptId)
        assertNull(invocations.findByRunVersion(run.id, run.version)?.finishedAt)
    }

    @Test
    fun `expired delivery and scheduler recovery delay original batch without reclaim or matching`() {
        val run = structuringRoom().second
        val versions = versionIds()
        val invocation = claim(run)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 1)))
        time.now = invocation.deadlineAt
        assertEquals(listOf(invocation.id), invocations.findExpired(time.now, 10).map { it.id })
        assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        persistence.expire(invocation)
        persistence.expire(invocation)
        val delayed = assertNotNull(runs.findById(run.id))
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, delayed.status)
        assertEquals(ResumeStage.STRUCTURING, delayed.resumeStage)
        assertEquals(run.batch, delayed.batch)
        assertEquals(versions, versionIds())
        assertEquals(emptyList(), delayed.candidates)
        assertNull(delayed.quality)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertEquals(1, count("coordination_attempts"))
        val stored = assertNotNull(invocations.findByRunVersion(run.id, run.version))
        assertEquals(invocation.deadlineAt, stored.deadlineAt)
        assertEquals(1, stored.geminiAttempts)
        assertNotNull(stored.finishedAt)
        assertEquals(emptyList(), invocations.findExpired(time.now, 10))
    }

    @Test
    fun `host retry retains frozen input but starts a new durable execution and fresh budget`() {
        val (fixture, run) = structuringRoom()
        val original = claim(run)
        assertTrue(persistence.claimAttempt(run, original, AnalysisProvider.GEMINI, attempt(run, original, 1)))
        persistence.delayBounded(run, original)
        mvc
            .perform(post("/api/rooms/{code}/analysis/retry", fixture.host.code).header("Origin", ORIGIN).cookie(fixture.host.cookie()))
            .andExpect(status().isAccepted)
        val retried = assertNotNull(runs.findById(run.id))
        val nextRun = assertNotNull(persistence.start(retried))
        time.now = NOW.plusSeconds(10)
        val next = claim(nextRun)
        assertNotEquals(original.id, next.id)
        assertTrue(next.runVersion > original.runVersion)
        assertEquals(run.batch, nextRun.batch)
        assertEquals(0, next.geminiAttempts)
        assertEquals(0, next.lunaAttempts)
        assertEquals(NOW.plusSeconds(70), next.deadlineAt)
        assertEquals(2, count("analysis_invocations"))
        assertEquals(next, invocations.findLatestByRun(run.id))
        assertNotNull(invocations.findByRunVersion(run.id, run.version)?.finishedAt)
    }

    @Test
    fun `stale version inactive pointer open revision and retained room cannot write`() {
        val (fixture, run) = structuringRoom()
        val stale =
            CoordinationRun.restore(
                run.id,
                run.roomId,
                run.batch,
                run.status,
                run.quality,
                run.candidates,
                run.confirmedCandidateId,
                run.confirmedAt,
                run.resumeStage,
                run.version - 1,
            )
        assertNull(persistence.claimInvocation(stale, UUID.randomUUID(), Duration.ofSeconds(60)))
        jdbc.update("UPDATE meeting_rooms SET active_run_id = NULL WHERE id = ?", fixture.roomId)
        assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        jdbc.update("UPDATE meeting_rooms SET active_run_id = ? WHERE id = ?", run.id.value, fixture.roomId)
        jdbc.update("UPDATE coordination_runs SET status = 'COMPLETED', candidate_quality = 'PARTIAL' WHERE id = ?", run.id.value)
        openRound(fixture)
        jdbc.update("UPDATE coordination_runs SET status = 'STRUCTURING', candidate_quality = NULL WHERE id = ?", run.id.value)
        assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
        assertEquals(1, retention.deleteExpiredRooms(NOW.plusSeconds(1), 10, NOW.plusSeconds(100)))
        assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        assertEquals(0, count("analysis_invocations"))
    }

    @Test
    fun `admitted worker is fenced when run changes or revision opens before publication`() {
        val (fixture, run) = structuringRoom()
        val invocation = claim(run)
        val first = attempt(run, invocation, 1)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, first))
        val admitted = assertNotNull(invocations.findByRunVersion(run.id, run.version))
        jdbc.update("UPDATE coordination_runs SET version = version + 1 WHERE id = ?", run.id.value)
        assertFalse(
            persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, attempt(run, invocation, 2, AnalysisProvider.OPENAI)),
        )
        assertFalse(persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), invocation) { true })
        persistence.delayBounded(run, invocation)
        assertEquals(CoordinationStatus.STRUCTURING, runs.findById(run.id)?.status)
        assertEquals(admitted, invocations.findByRunVersion(run.id, run.version))

        jdbc.update("UPDATE coordination_runs SET version = ? WHERE id = ?", run.version, run.id.value)
        jdbc.update("UPDATE meeting_rooms SET active_run_id = NULL WHERE id = ?", fixture.roomId)
        assertFalse(persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), invocation) { true })
        persistence.delayBounded(run, invocation)
        assertEquals(admitted, invocations.findByRunVersion(run.id, run.version))

        jdbc.update("UPDATE meeting_rooms SET active_run_id = ? WHERE id = ?", run.id.value, fixture.roomId)
        jdbc.update("UPDATE coordination_runs SET status = 'COMPLETED', candidate_quality = 'PARTIAL' WHERE id = ?", run.id.value)
        openRound(fixture)
        jdbc.update("UPDATE coordination_runs SET status = 'STRUCTURING', candidate_quality = NULL WHERE id = ?", run.id.value)
        assertFalse(persistence.completeBounded(run, results(run), first.copy(finishedAt = NOW), invocation) { true })
        time.now = invocation.deadlineAt
        persistence.expire(invocation)
        assertEquals(CoordinationStatus.STRUCTURING, runs.findById(run.id)?.status)
        assertEquals(admitted, invocations.findByRunVersion(run.id, run.version))
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertEquals(1, count("coordination_attempts"))
    }

    @Test
    fun `migration enforces one execution per version counters and cascade retention`() {
        val run = structuringRoom().second
        val invocation = claim(run)
        assertThrows<DataIntegrityViolationException> { invocations.insert(invocation.copy(id = UUID.randomUUID())) }
        for (invalid in listOf(
            invocation.copy(geminiAttempts = 5),
            invocation.copy(geminiAttempts = -1),
            invocation.copy(lunaAttempts = 2),
        )) {
            assertThrows<DataIntegrityViolationException> { invocations.update(invalid) }
        }
        assertEquals(invocation, invocations.findByRunVersion(run.id, run.version))
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 1)))
        assertEquals(1, retention.deleteExpiredRooms(NOW.plusSeconds(1), 10, NOW.plusSeconds(100)))
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
        assertEquals(0, count("coordination_runs"))
    }

    private fun structuringRoom(): Pair<CompletedCorrectionFixture, CoordinationRun> {
        val fixture = completedRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status = 'STRUCTURING', candidate_quality = NULL, version = version + 1 WHERE id = ?",
            fixture.sourceRun,
        )
        return fixture to assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun claim(run: CoordinationRun) = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))

    private fun attempt(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
        number: Int,
        provider: AnalysisProvider = AnalysisProvider.GEMINI,
    ) = CoordinationAttempt(
        UUID.randomUUID(),
        run.id,
        number,
        time.now,
        null,
        null,
        null,
        null,
        null,
        null,
        provider = provider,
        model = if (provider == AnalysisProvider.OPENAI) "gpt-6-luna" else "gemini-3.8-flash",
        policyVersion = AnalysisInvocation.POLICY_VERSION,
        invocationId = invocation.id,
    )

    private fun results(
        run: CoordinationRun,
        rejectionCode: String? = null,
    ) = run.batch.submissionVersionIds.map {
        StructuredSubmissionResult(
            it,
            listOf(
                StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    null,
                    DayOfWeek.WEDNESDAY,
                    LocalTime.of(19, 0),
                    LocalTime.of(21, 0),
                ),
            ),
            rejectionCode,
        )
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun <T : Any> whileRoomLocked(
        roomId: UUID,
        beforeRelease: () -> Unit,
        operation: () -> T,
    ): T {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val holder =
            executor.submit {
                TransactionTemplate(transactionManager).execute {
                    jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, roomId)
                    locked.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                }
            }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val worker =
                executor.submit<T> {
                    requireNotNull(
                        TransactionTemplate(transactionManager).execute {
                            pid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            started.countDown()
                            operation()
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var observed = false
            while (System.nanoTime() < until) {
                if (jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, pid.get()) == true) {
                    observed = true
                    break
                }
                Thread.sleep(15)
            }
            assertTrue(observed, "Publication never waited on the PostgreSQL room lock")
            beforeRelease()
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            return worker.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    class DurableClock : Clock() {
        @Volatile var now: Instant = NOW

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

        override fun instant(): Instant = now
    }

    @TestConfiguration
    class ControlledTime {
        @Bean
        @Primary
        fun durableClock() = DurableClock()
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
