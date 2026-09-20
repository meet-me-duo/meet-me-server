package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals

class MeetingDurationRemovalMigrationTest {
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
    fun `V5는 기존 방을 보존하고 소요 시간 컬럼을 제거한다`() {
        flyway("4").migrate()
        connection().use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    INSERT INTO meeting_rooms(
                        id, invite_code, purpose, duration_minutes, meeting_mode, time_zone_id,
                        search_start_date, search_end_date, search_range_source, expected_participants,
                        submission_deadline, manual_only, collection_status, closure_reason, closed_at, created_at, version
                    ) VALUES (
                        '${UUID.randomUUID()}', 'abcdefghijklmnopqrstuv', '기존 방', 60, 'EITHER', 'Asia/Seoul',
                        DATE '2026-09-20', DATE '2026-09-27', 'HOST_SPECIFIED', NULL,
                        NULL, TRUE, 'COLLECTING', NULL, NULL, TIMESTAMPTZ '2026-09-19 00:00:00Z', 0
                    )
                    """.trimIndent(),
                )
            }
        }

        val latest = flyway("5")
        latest.migrate()

        assertEquals(
            "5",
            latest
                .info()
                .current()
                .version.version,
        )
        assertEquals(1, queryInt("SELECT count(*) FROM meeting_rooms"))
        assertEquals(
            0,
            queryInt(
                "SELECT count(*) FROM information_schema.columns " +
                    "WHERE table_name = 'meeting_rooms' AND column_name = 'duration_minutes'",
            ),
        )
        latest.validate()
    }

    private fun queryInt(sql: String): Int =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getInt(1)
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
        fun stopContainer() {
            postgres.stop()
        }
    }
}
