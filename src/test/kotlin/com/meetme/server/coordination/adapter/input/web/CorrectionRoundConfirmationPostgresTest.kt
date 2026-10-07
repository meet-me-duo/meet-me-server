package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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

// Issue #92 confirmation vs reopen: two forced real PostgreSQL commit orders, never a fake StatefulResultPort.
class CorrectionRoundConfirmationPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `confirmation commits first and waiting reopen cannot alter immutable confirmed result`() {
        val fixture = completedRoom()
        val candidate = addPartialCandidate(fixture)
        val codes = ordered(fixture, confirm(fixture, candidate), reopen(fixture))

        assertEquals(200 to 409, codes)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds", Int::class.java))
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
        assertEquals(false, child(room(fixture.host), "capabilities")["can_open_revision"])
        mvc.perform(confirm(fixture, candidate)).andReturn().also { assertEquals(200, it.response.status) }
    }

    @Test
    fun `reopen commits first and waiting confirmation cannot confirm old candidate`() {
        val fixture = completedRoom()
        val candidate = addPartialCandidate(fixture)
        val codes = ordered(fixture, reopen(fixture), confirm(fixture, candidate))

        assertEquals(200 to 409, codes)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds WHERE status = 'OPEN'", Int::class.java))
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
        assertEquals(true, child(room(fixture.host), "capabilities")["can_analyze_revision"])
        assertEquals(false, child(room(fixture.host), "capabilities")["can_confirm"])
    }

    private fun addPartialCandidate(fixture: CompletedCorrectionFixture): UUID {
        val candidate = UUID.randomUUID()
        val participant =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM participants WHERE room_id = ? AND role = 'HOST'",
                    UUID::class.java,
                    fixture.roomId,
                ),
            )
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) " +
                "VALUES (?, ?, 1, 'PLAN_B', 'REMOTE', 1, 2)",
            candidate,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id, range_order, start_at, end_at) " +
                "VALUES (?, 0, '2026-10-07T10:00:00Z', '2026-10-07T11:00:00Z')",
            candidate,
        )
        jdbc.update(
            "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id) VALUES (?, ?, ?, ?)",
            candidate,
            participant,
            fixture.sourceRun,
            fixture.roomId,
        )
        return candidate
    }

    private fun confirm(
        fixture: CompletedCorrectionFixture,
        candidate: UUID,
    ) = post("/api/rooms/{code}/candidates/{candidate}/confirmation", fixture.host.code, candidate)
        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
        .cookie(fixture.host.cookie())

    private fun ordered(
        fixture: CompletedCorrectionFixture,
        first: MockHttpServletRequestBuilder,
        second: MockHttpServletRequestBuilder,
    ): Pair<Int, Int> {
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstPid = AtomicInteger()
        val secondPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val firstTask =
            executor.submit<Int> {
                requireNotNull(
                    transaction().execute {
                        firstPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, fixture.roomId)
                        val status =
                            mvc
                                .perform(first)
                                .andReturn()
                                .response.status
                        written.countDown()
                        check(release.await(8, TimeUnit.SECONDS)) { "First transaction release timed out" }
                        status
                    },
                )
            }
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS), "First request did not reach held commit")
            val secondTask =
                executor.submit<Int> {
                    requireNotNull(
                        transaction().execute { enclosing ->
                            secondPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            secondStarted.countDown()
                            val status =
                                mvc
                                    .perform(second)
                                    .andReturn()
                                    .response.status
                            if (status >= 400) enclosing.setRollbackOnly()
                            status
                        },
                    )
                }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "Second transaction did not start")
            assertRoomBlocked(firstPid.get(), secondPid.get())
            release.countDown()
            return firstTask.get(10, TimeUnit.SECONDS) to secondTask.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent requests failed to terminate")
        }
    }

    private fun assertRoomBlocked(
        firstPid: Int,
        secondPid: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            val blocked =
                jdbc.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                        "(SELECT 1 FROM pg_locks WHERE pid = ? AND relation = 'meeting_rooms'::regclass AND locktype = 'tuple')",
                    Boolean::class.java,
                    firstPid,
                    secondPid,
                    secondPid,
                )
            if (blocked == true) return
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        assertTrue(false, "Second request never waited on the first room row lock before commit")
    }

    private fun transaction() =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 15
        }

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
