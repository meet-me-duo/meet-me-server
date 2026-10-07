package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Issue #92 V7 -> V8 upgrade: persisted confirmations outrank newer unconfirmed runs; contradictions stop migration.
class InputRevisionLegacyMigrationTest {
    @BeforeEach
    fun resetSchema() {
        connection().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP SCHEMA public CASCADE")
                s.execute("CREATE SCHEMA public")
            }
        }
        flyway("7").migrate()
    }

    @Test
    fun `legacy confirmed analysis wins over newer unconfirmed analysis without changing anchor`() {
        val room = seedRoom()
        val old = seedRun(room, UUID(0, 1), "2026-09-02T00:00:00Z")
        seedRun(room, UUID(0, 2), "2026-09-03T00:00:00Z")
        confirm(old)
        flyway("8").migrate()
        assertEquals(old.toString(), scalar("SELECT active_run_id::text FROM meeting_rooms"))
        assertEquals("0", scalar("SELECT revision_generation::text FROM meeting_rooms"))
        assertEquals("0", scalar("SELECT correction_analysis_count::text FROM meeting_rooms"))
        assertEquals("1", scalar("SELECT count(*)::text FROM final_confirmations"))
        assertEquals("2026-09-02", scalar("SELECT closed_at::date::text FROM meeting_rooms"))
    }

    @Test
    fun `legacy unconfirmed runs choose latest created timestamp then id deterministically`() {
        val room = seedRoom()
        seedRun(room, UUID(0, 1), "2026-09-02T00:00:00Z")
        val larger = seedRun(room, UUID(0, 2), "2026-09-02T00:00:00Z")
        flyway("8").migrate()
        assertEquals(larger.toString(), scalar("SELECT active_run_id::text FROM meeting_rooms"))
    }

    @Test
    fun `contradictory legacy confirmations fail migration and preserve V7 data atomically`() {
        val room = seedRoom()
        confirm(seedRun(room, UUID(0, 1), "2026-09-02T00:00:00Z"))
        confirm(seedRun(room, UUID(0, 2), "2026-09-03T00:00:00Z"))
        assertFailsWith<FlywayException> { flyway("8").migrate() }
        assertEquals("2", scalar("SELECT count(*)::text FROM final_confirmations"))
        assertEquals("2", scalar("SELECT count(*)::text FROM coordination_runs"))
        assertEquals(
            "0",
            scalar(
                "SELECT count(*)::text FROM information_schema.columns WHERE table_name='meeting_rooms' AND column_name='active_run_id'",
            ),
        )
        assertEquals(
            "7",
            flyway()
                .info()
                .current()
                .version.version,
        )
    }

    private fun seedRoom(): UUID {
        val room = UUID.randomUUID()
        update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date," +
                "search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) " +
                "VALUES (?,'abcdefghijklmnopqrstuv','synthetic legacy','REMOTE','Asia/Seoul','2026-10-07','2026-10-12'," +
                "'HOST_SPECIFIED',true,'CLOSED','MANUAL','2026-09-02T00:00:00Z','2026-09-01T00:00:00Z')",
            room,
        )
        return room
    }

    private fun seedRun(
        room: UUID,
        run: UUID,
        at: String,
    ): UUID {
        val batch = UUID.randomUUID()
        update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?::timestamptz)", batch, room, at)
        update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,candidate_quality,created_at) " +
                "VALUES (?,?,?,'COMPLETED','COMPLETE',?::timestamptz)",
            run,
            room,
            batch,
            at,
        )
        return run
    }

    private fun confirm(run: UUID) {
        val candidate = UUID.randomUUID()
        update("INSERT INTO candidates(id,coordination_run_id,rank) VALUES (?,?,1)", candidate, run)
        update(
            "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,'2026-09-04T00:00:00Z')",
            run,
            candidate,
        )
    }

    private fun update(
        sql: String,
        vararg arguments: Any,
    ) {
        connection().use { c ->
            c.prepareStatement(sql).use { s ->
                arguments.forEachIndexed { i, value -> s.setObject(i + 1, value) }
                s.executeUpdate()
            }
        }
    }

    private fun scalar(sql: String): String =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    check(r.next())
                    r.getString(1)
                }
            }
        }

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        target?.let(config::target)
        return config.load()
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
