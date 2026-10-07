package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Real PostgreSQL locks: each order is held open until pg_blocking_pids proves the second request waits.
class CorrectionRoundConcurrencyPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `save committed first is included in analyze frozen batch`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val codes =
            orderedRequests(
                fixture,
                save(fixture.host, "H1 committed first", roundId, 1),
                analyze(fixture, roundId),
            )

        assertEquals(200 to 202, codes)
        assertEquals(listOf("H1 committed first", "M0 original"), activeTexts(fixture))
        assertEquals(listOf("H0 original", "M0 original"), sourceTexts(fixture))
        assertEquals(2, workCounts()["coordination_runs"])
    }

    @Test
    fun `analyze committed first prevents queued late save from changing frozen batch`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val versions = versionIds()
        val codes =
            orderedRequests(
                fixture,
                analyze(fixture, roundId, force = true),
                save(fixture.host, "H1 too late", roundId, 1),
            )

        assertEquals(202 to 409, codes)
        assertEquals(listOf("H0 original", "M0 original"), activeTexts(fixture))
        assertEquals(versions, versionIds())
        assertEquals("H0 original", owner(fixture.host)["raw_text"])
        assertEquals(2, workCounts()["coordination_runs"])
    }

    @Test
    fun `same analysis request waiting on committed peer replays exactly one fresh batch and outbox`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val requestId = UUID.randomUUID()
        val before = workCounts()
        val codes =
            orderedRequests(
                fixture,
                analyze(fixture, roundId, requestId, force = true),
                analyze(fixture, roundId, requestId, force = true),
            )

        assertEquals(202 to 202, codes)
        val after = workCounts()
        for (table in listOf("submission_batches", "coordination_runs", "outbox_events")) {
            assertEquals(before.getValue(table) + 1, after.getValue(table), table)
        }
        assertEquals(before["coordination_attempts"], after["coordination_attempts"])
        assertEquals(2, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
    }

    private fun orderedRequests(
        fixture: CompletedCorrectionFixture,
        first: MockHttpServletRequestBuilder,
        second: MockHttpServletRequestBuilder,
    ): Pair<Int, Int> {
        val firstWritten = CountDownLatch(1)
        val commitFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val firstTask =
            executor.submit<Int> {
                requireNotNull(
                    newTransaction().execute {
                        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, fixture.roomId)
                        val code =
                            mvc
                                .perform(first)
                                .andReturn()
                                .response.status
                        firstWritten.countDown()
                        check(commitFirst.await(8, TimeUnit.SECONDS)) { "First transaction release timed out" }
                        code
                    },
                )
            }
        try {
            assertTrue(firstWritten.await(5, TimeUnit.SECONDS), "First HTTP request did not reach held commit")
            val secondTask =
                executor.submit<Int> {
                    requireNotNull(
                        newTransaction().execute { transaction ->
                            secondPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            secondStarted.countDown()
                            val code =
                                mvc
                                    .perform(second)
                                    .andReturn()
                                    .response.status
                            // A handled domain rejection marks the enclosing test transaction rollback-only.
                            if (code >= 400) transaction.setRollbackOnly()
                            code
                        },
                    )
                }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "Second transaction did not start")
            assertBlocked(secondPid.get())
            commitFirst.countDown()
            return firstTask.get(10, TimeUnit.SECONDS) to secondTask.get(10, TimeUnit.SECONDS)
        } finally {
            commitFirst.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent requests did not terminate")
        }
    }

    private fun assertBlocked(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            val blocked =
                jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(?)) > 0",
                    Boolean::class.java,
                    pid,
                )
            if (blocked == true) return
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        assertTrue(false, "Second PostgreSQL transaction was never observed waiting on first transaction")
    }

    private fun newTransaction() =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 15
        }

    private fun activeTexts(fixture: CompletedCorrectionFixture) = texts(activeRun(fixture.host))

    private fun sourceTexts(fixture: CompletedCorrectionFixture) = texts(fixture.sourceRun)

    private fun texts(runId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM coordination_runs r JOIN submission_batch_items i ON i.batch_id = r.batch_id " +
                    "JOIN submission_versions v ON v.id = i.submission_version_id WHERE r.id = ? ORDER BY v.raw_text",
                String::class.java,
                runId,
            ).map { requireNotNull(it) }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
