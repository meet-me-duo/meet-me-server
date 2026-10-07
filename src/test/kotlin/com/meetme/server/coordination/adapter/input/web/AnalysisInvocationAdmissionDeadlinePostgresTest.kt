package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.input.web.DurableAnalysisInvocationPostgresTest.ControlledTime
import com.meetme.server.coordination.adapter.input.web.DurableAnalysisInvocationPostgresTest.DurableClock
import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.shared.domain.CoordinationRunId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96: lock admission consumes the caller's original budget instead of restarting it. */
@Import(ControlledTime::class)
class AnalysisInvocationAdmissionDeadlinePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var time: DurableClock

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `room lock waiting consumes five seconds without extending original invocation deadline`() {
        val run = structuringRun()

        val invocation = assertNotNull(claimAfterLockWait(run, Duration.ofSeconds(5)))

        assertEquals(NOW.plusSeconds(60), invocation.deadlineAt)
        assertEquals(invocation, invocations.findByRunVersion(run.id, run.version))
        assertEquals(Duration.ofSeconds(55), Duration.between(time.now, invocation.deadlineAt))
    }

    @Test
    fun `fully exhausted budget while waiting for room lock cannot admit an invocation`() {
        val run = structuringRun()

        assertNull(claimAfterLockWait(run, Duration.ofSeconds(60)))

        assertNull(invocations.findByRunVersion(run.id, run.version))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    private fun structuringRun(): CoordinationRun {
        val fixture = completedRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status = 'STRUCTURING', candidate_quality = NULL, version = version + 1 WHERE id = ?",
            fixture.sourceRun,
        )
        return assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun claimAfterLockWait(
        run: CoordinationRun,
        wait: Duration,
    ): AnalysisInvocation? {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val holder =
            executor.submit {
                TransactionTemplate(transactionManager).execute {
                    jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, run.roomId.value)
                    locked.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                }
            }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val claimant =
                executor.submit<AnalysisInvocation?> {
                    TransactionTemplate(transactionManager).execute {
                        pid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                        started.countDown()
                        persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60))
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertBlocked(pid.get())
            time.now = NOW.plus(wait)
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            return claimant.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertBlocked(pid: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, pid) == true) return
            Thread.sleep(15)
        } while (System.nanoTime() < until)
        assertTrue(false, "Invocation admission never waited on the PostgreSQL room lock")
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
