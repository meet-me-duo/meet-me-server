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

// Independent #95: force both real PostgreSQL room-lock commit orders, including rollback on rejected command.
class RecommendationConfirmationPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `typed confirmation first makes waiting reopen fail and retains selected result`() {
        val fixture = recommendedRoom(partial = true)
        val option = items(recommendations(fixture.host), "options").first()
        assertEquals(200 to 409, ordered(fixture, selection(fixture, option), reopen(fixture)))
        assertEquals("CONFIRMED", room(fixture.host)["public_status"])
        assertEquals(option["option_id"], child(result(fixture.host), "selection")["option_id"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds", Int::class.java))
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
    }

    @Test
    fun `reopen first makes waiting typed confirmation fail without writing a selection`() {
        val fixture = recommendedRoom(partial = true)
        val option = items(recommendations(fixture.host), "options").first()
        assertEquals(200 to 409, ordered(fixture, reopen(fixture), selection(fixture, option)))
        assertEquals("COLLECTING", room(fixture.host)["public_status"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds WHERE status = 'OPEN'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(false, child(room(fixture.host), "capabilities")["can_confirm"])
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
    }

    private fun ordered(
        fixture: CompletedCorrectionFixture,
        first: MockHttpServletRequestBuilder,
        second: MockHttpServletRequestBuilder,
    ): Pair<Int, Int> {
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val firstPid = AtomicInteger()
        val secondPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val firstTask =
            executor.submit<Int> {
                requireNotNull(
                    transaction().execute {
                        firstPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, fixture.roomId)
                        val code =
                            mvc
                                .perform(first)
                                .andReturn()
                                .response.status
                        written.countDown()
                        check(release.await(8, TimeUnit.SECONDS)) { "First commit release timed out" }
                        code
                    },
                )
            }
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS), "First command must reach held commit")
            val secondTask =
                executor.submit<Int> {
                    requireNotNull(
                        transaction().execute { enclosing ->
                            secondPid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            started.countDown()
                            val code =
                                mvc
                                    .perform(second)
                                    .andReturn()
                                    .response.status
                            if (code >= 400) enclosing.setRollbackOnly()
                            code
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS), "Second command must start")
            assertRoomBlocked(firstPid.get(), secondPid.get())
            release.countDown()
            return firstTask.get(10, TimeUnit.SECONDS) to secondTask.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Requests must terminate")
        }
    }

    private fun assertRoomBlocked(
        firstPid: Int,
        secondPid: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (jdbc.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                        "(SELECT 1 FROM pg_locks WHERE pid = ? AND relation = 'meeting_rooms'::regclass AND locktype = 'tuple')",
                    Boolean::class.java,
                    firstPid,
                    secondPid,
                    secondPid,
                ) == true
            ) {
                return
            }
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        assertTrue(false, "Second command must wait on the exact room row lock before first commit")
    }

    private fun transaction() =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 15
        }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
