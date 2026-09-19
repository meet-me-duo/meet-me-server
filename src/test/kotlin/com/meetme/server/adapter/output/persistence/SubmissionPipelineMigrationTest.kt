package com.meetme.server.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SubmissionPipelineMigrationTest {
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
    fun `데이터가 있는 V2를 V3로 올리고 제출 파이프라인 제약을 적용한다`() {
        flyway("2").migrate()
        insertRoom(50)

        val latest = flyway()
        latest.migrate()

        assertEquals(
            "3",
            latest
                .info()
                .current()
                .version.version,
        )
        latest.validate()
        assertNotNull(query("SELECT to_regclass('public.structured_submission_results')"))
        val failure = runCatching { insertRoom(51) }.exceptionOrNull()
        assertNotNull(failure)
    }

    private fun insertRoom(expectedParticipants: Int) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO meeting_rooms (
                        id, invite_code, purpose, duration_minutes, meeting_mode, time_zone_id,
                        search_start_date, search_end_date, search_range_source,
                        expected_participants, submission_deadline, manual_only,
                        collection_status, closure_reason, closed_at, created_at, version
                    ) VALUES (?, ?, '마이그레이션 방', 60, 'EITHER', 'Asia/Seoul',
                        DATE '2026-09-20', DATE '2026-09-27', 'HOST_SPECIFIED',
                        ?, NULL, FALSE, 'COLLECTING', NULL, NULL,
                        TIMESTAMPTZ '2026-09-19 00:00:00Z', 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(
                        2,
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(22),
                    )
                    statement.setInt(3, expectedParticipants)
                    statement.executeUpdate()
                }
        }
    }

    private fun query(sql: String): String? =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private fun flyway(target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        target?.let(configuration::target)
        return configuration.load()
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
