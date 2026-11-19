package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.support.mappedPortReadyPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SR02/04/08/11: PostgreSQL is the durable retry clock and processing-authority fence. */
class StreamRetryOutboxPostgresContractTest {
    private val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    private val now = Instant.parse("2026-11-19T00:00:00Z")

    @BeforeEach
    fun recreateSchema() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
    }

    @Test
    fun `SR04 migration adds retry metadata without changing existing Outbox state or delivery counters`() {
        flyway("33").migrate()
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,published_at,delivery_count)
            VALUES (?,'CoordinationRun',?,'SubmissionBatchStructuringRequested','{}'::jsonb,'PUBLISHED',?,?,3)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            now.minusSeconds(5).atOffset(ZoneOffset.UTC),
            now.minusSeconds(1).atOffset(ZoneOffset.UTC),
        )

        flyway().migrate()

        assertMetadataColumns()
        val row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE id=?", id)
        assertEquals("PUBLISHED", row["status"])
        assertEquals(3, row["delivery_count"])
        assertNull(row["next_attempt_at"])
        assertNull(row["last_attempt_at"])
    }

    @Test
    fun `SR02 scheduled retry survives a repository restart and becomes claimable exactly at its due instant`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val first = assertNotNull(repository.claimProcessing(event.id, now, now.plusSeconds(120)))
        val due = now.plusMillis(750)

        assertTrue(repository.scheduleRetry(first, "RATE_LIMIT", due))

        val restarted = repository()
        val waiting = assertNotNull(restarted.findById(event.id))
        assertEquals(OutboxStatus.PUBLISHED, waiting.status)
        assertEquals(due, waiting.nextAttemptAt)
        assertEquals(now, waiting.lastAttemptAt)
        assertEquals("RATE_LIMIT", waiting.lastFailureKind)
        assertEquals(1, waiting.deliveryCount)
        assertNull(waiting.processingLeaseUntil)
        assertNull(restarted.claimProcessing(event.id, due.minusNanos(1_000), due.plusSeconds(120)))
        assertEquals(1, assertNotNull(restarted.findById(event.id)).deliveryCount)

        val second = assertNotNull(restarted.claimProcessing(event.id, due, due.plusSeconds(120)))
        assertEquals(2, second.deliveryCount)
        assertEquals(due, second.event.lastAttemptAt)
    }

    @Test
    fun `SR11 old claim cannot complete or reschedule after a later consumer has obtained the lease`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val first = assertNotNull(repository.claimProcessing(event.id, now, now.plusSeconds(1)))
        val secondAt = now.plusSeconds(1)
        val second = assertNotNull(repository.claimProcessing(event.id, secondAt, secondAt.plusSeconds(120)))

        assertFalse(repository.completeClaim(first, secondAt))
        assertFalse(repository.scheduleRetry(first, "STALE_OWNER", secondAt.plusSeconds(10)))

        val owned = assertNotNull(repository.findById(event.id))
        assertEquals(OutboxStatus.PUBLISHED, owned.status)
        assertEquals(second.deliveryCount, owned.deliveryCount)
        assertEquals(second.event.processingLeaseUntil, owned.processingLeaseUntil)
        assertNull(owned.nextAttemptAt)
        assertNull(owned.lastFailureKind)
        assertTrue(repository.completeClaim(second, secondAt.plusMillis(1)))
        assertEquals(OutboxStatus.PROCESSED, assertNotNull(repository.findById(event.id)).status)
        assertFalse(repository.scheduleRetry(second, "AFTER_COMPLETION", secondAt.plusSeconds(10)))
    }

    @Test
    fun `SR03 concurrent consumers cannot both obtain processing authority for the same event`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val claims =
                executor
                    .invokeAll(List(8) { Callable { repository.claimProcessing(event.id, now, now.plusSeconds(120)) } })
                    .map { it.get() }
            assertEquals(1, claims.count { it != null })
            assertEquals(1, assertNotNull(repository.findById(event.id)).deliveryCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `SR08 rolled back retry scheduling preserves the active lease and cannot expose an early retry`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val first = assertNotNull(repository.claimProcessing(event.id, now, now.plusSeconds(120)))
        val original = assertNotNull(repository.findById(event.id))
        val due = now.plusSeconds(1)
        // A rolled-back write represents a database failure before scheduling can commit.
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("UPDATE outbox_events SET next_attempt_at=?,processing_lease_until=NULL WHERE id=?").use {
                it.setObject(1, due.atOffset(ZoneOffset.UTC))
                it.setObject(2, event.id.value)
                assertEquals(1, it.executeUpdate())
            }
            connection.rollback()
        }

        val afterRollback = assertNotNull(repository.findById(event.id))
        assertEquals(original, afterRollback)
        assertNull(repository.claimProcessing(event.id, due, due.plusSeconds(120)))
        assertEquals(first.deliveryCount, afterRollback.deliveryCount)
    }

    @Test
    fun `SR07 a completed claim cannot admit another provider attempt through a duplicate delivery`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val first = assertNotNull(repository.claimProcessing(event.id, now, now.plusSeconds(120)))
        val due = now.plusSeconds(1)
        assertTrue(repository.scheduleRetry(first, "SERVER", due))
        val second = assertNotNull(repository.claimProcessing(event.id, due, due.plusSeconds(120)))

        assertTrue(repository.completeClaim(second, due.plusMillis(1)))

        assertNull(repository.claimProcessing(event.id, due.plusMillis(2), due.plusSeconds(120)))
        assertNull(assertNotNull(repository.findById(event.id)).nextAttemptAt)
        assertEquals(2, assertNotNull(repository.findById(event.id)).deliveryCount)
    }

    @Test
    fun `SR15 expected continuations do not consume the separate unexpected failure budget`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        var currentAt = now
        var claim = assertNotNull(repository.claimProcessing(event.id, currentAt, currentAt.plusSeconds(120)))
        repeat(6) {
            val due = currentAt.plusSeconds(1)
            assertTrue(repository.scheduleRetry(claim, "SERVER", due))
            currentAt = due
            claim = assertNotNull(repository.claimProcessing(event.id, currentAt, currentAt.plusSeconds(120)))
        }
        assertEquals(7, claim.deliveryCount)
        assertEquals(0, assertNotNull(repository.findById(event.id)).unexpectedFailures)

        for (expected in 1..5) {
            assertEquals(expected, repository.recordUnexpectedFailure(claim, "SyntheticInfrastructureFailure"))
            assertEquals(expected, assertNotNull(repository.findById(event.id)).unexpectedFailures)
            if (expected < 5) {
                val due = currentAt.plusSeconds(1)
                assertTrue(repository.scheduleRetry(claim, "SyntheticInfrastructureFailure", due))
                currentAt = due
                claim = assertNotNull(repository.claimProcessing(event.id, currentAt, currentAt.plusSeconds(120)))
            }
        }
        assertEquals(11, claim.deliveryCount)
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
    }

    @Test
    fun `SR11 stale claim cannot advance the current consumer's unexpected failure counter`() {
        val repository = migratedRepository()
        val event = publishedEvent()
        repository.insert(event)
        val first = assertNotNull(repository.claimProcessing(event.id, now, now.plusSeconds(1)))
        val second = assertNotNull(repository.claimProcessing(event.id, now.plusSeconds(1), now.plusSeconds(120)))

        assertNull(repository.recordUnexpectedFailure(first, "STALE_OWNER"))
        assertEquals(0, assertNotNull(repository.findById(event.id)).unexpectedFailures)
        assertEquals(1, repository.recordUnexpectedFailure(second, "SyntheticInfrastructureFailure"))
        assertEquals(1, assertNotNull(repository.findById(event.id)).unexpectedFailures)
    }

    private fun migratedRepository(): KomapperOutboxRepository {
        flyway().migrate()
        assertMetadataColumns()
        return repository()
    }

    private fun assertMetadataColumns() {
        val columns =
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='outbox_events'",
                String::class.java,
            )
        assertTrue(columns.containsAll(listOf("next_attempt_at", "last_attempt_at")), "Durable retry metadata is absent")
    }

    private fun repository() =
        KomapperOutboxRepository(
            JdbcDatabase(postgres.jdbcUrl, postgres.username, postgres.password),
            jdbc,
        )

    private fun flyway(target: String? = null): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .apply {
                if (target != null) target(target)
            }.load()

    private fun publishedEvent() =
        OutboxEvent(
            OutboxEventId(UUID.randomUUID()),
            "CoordinationRun",
            UUID.randomUUID(),
            "SubmissionBatchStructuringRequested",
            "{}",
            OutboxStatus.PUBLISHED,
            now.minus(Duration.ofSeconds(5)),
            now.minusSeconds(1),
        )

    companion object {
        private val postgres = mappedPortReadyPostgres().also { it.start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
