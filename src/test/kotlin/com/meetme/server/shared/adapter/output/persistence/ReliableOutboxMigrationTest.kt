package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ReliableOutboxMigrationTest {
    @BeforeEach
    fun recreateSchema() {
        connection().use { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA public CASCADE")
                it.execute("CREATE SCHEMA public")
            }
        }
    }

    @Test
    fun `V6는 Outbox 처리 lease와 영구 실패 이력을 PostgreSQL에 보존한다`() {
        val flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load()
        flyway.migrate()
        val eventId = UUID.randomUUID()
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO outbox_events(
                        id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at,
                        published_at, processing_lease_until, delivery_count
                    ) VALUES (?, 'CoordinationRun', ?, 'SubmissionBatchStructuringRequested', '{}'::jsonb,
                        'PUBLISHED', now(), now(), now() + interval '2 minutes', 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, UUID.randomUUID())
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    UPDATE outbox_events
                    SET status = 'DEAD_LETTERED', processing_lease_until = NULL,
                        delivery_count = 5, last_failure_kind = 'InvariantViolation', dead_lettered_at = now()
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, eventId)
                    assertEquals(1, statement.executeUpdate())
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO coordination_worker_failures(
                        stream_record_id, event_id, submission_batch_id, failure_kind, delivery_count, failed_at
                    ) VALUES ('1-0', ?, ?, 'InvariantViolation', 5, now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, UUID.randomUUID())
                    assertEquals(1, statement.executeUpdate())
                }
        }
    }

    @Test
    fun `처리 완료 Outbox는 published와 processed 시각을 모두 요구한다`() {
        val flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load()
        flyway.migrate()
        assertFails {
            connection().use { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        INSERT INTO outbox_events(
                            id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at
                        ) VALUES (
                            '${UUID.randomUUID()}', 'CoordinationRun', '${UUID.randomUUID()}',
                            'SubmissionBatchStructuringRequested', '{}'::jsonb, 'PROCESSED', now()
                        )
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
