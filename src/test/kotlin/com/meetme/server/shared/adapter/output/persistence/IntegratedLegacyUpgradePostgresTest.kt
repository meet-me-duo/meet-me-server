package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals

/** Issue #99: actual V8 rows survive additive V9 recommendations and V10 bounded invocations unchanged. */
class IntegratedLegacyUpgradePostgresTest {
    @BeforeEach
    fun resetSchema() {
        execute("DROP SCHEMA public CASCADE")
        execute("CREATE SCHEMA public")
    }

    @Test
    fun `blank PostgreSQL migrates exactly ten versions and validates both independent schemas`() {
        assertEquals(10, flyway().migrate().migrationsExecuted)
        flyway().validate()
        assertEquals(
            "10",
            flyway()
                .info()
                .current()
                .version.version,
        )
        assertEquals("10", scalar("SELECT count(*)::text FROM flyway_schema_history WHERE success AND version IS NOT NULL"))
        assertEquals(
            "6",
            scalar(
                "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND table_name IN " +
                    "('recommendation_analyses','recommendation_options','recommendation_variants'," +
                    "'recommendation_variant_participants','recommendation_selections','analysis_invocations')",
            ),
        )
    }

    @Test
    fun `V8 upgrade preserves raw created frozen cohort legacy attempts confirmation and both revision round states`() {
        assertEquals(8, flyway("8").migrate().migrationsExecuted)
        seedRoom("CONFIRMED")
        seedRoom("OPEN")
        seedRoom("CONSUMED")
        val original = snapshots()

        assertEquals(2, flyway().migrate().migrationsExecuted)
        flyway().validate()

        assertEquals(original, snapshots(), "Every preexisting business column must survive upgrade without reanalysis or backfill")
        assertEquals("3", scalar("SELECT count(*)::text FROM coordination_attempts"))
        assertEquals(
            "3",
            scalar(
                "SELECT count(*)::text FROM coordination_attempts WHERE provider='GEMINI' " +
                    "AND model='gemini-3.8-flash' AND policy_version='gemini-v1' AND invocation_id IS NULL",
            ),
        )
        assertEquals("0", scalar("SELECT count(*)::text FROM analysis_invocations"))
        assertEquals("0", scalar("SELECT count(*)::text FROM recommendation_analyses"))
        assertEquals("0", scalar("SELECT count(*)::text FROM recommendation_selections"))
        assertEquals("1", scalar("SELECT count(*)::text FROM final_confirmations"))
        assertEquals("1", scalar("SELECT count(*)::text FROM input_revision_rounds WHERE status='OPEN'"))
        assertEquals("1", scalar("SELECT count(*)::text FROM input_revision_rounds WHERE status='CONSUMED'"))
        assertEquals(
            "3",
            scalar(
                "SELECT count(*)::text FROM coordination_runs r LEFT JOIN recommendation_analyses a ON a.coordination_run_id=r.id " +
                    "WHERE a.coordination_run_id IS NULL",
            ),
        )
    }

    private fun seedRoom(state: String) {
        val room = UUID.randomUUID()
        val batch = UUID.randomUUID()
        val run = UUID.randomUUID()
        execute(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date," +
                "search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) VALUES " +
                "(? ,? ,'synthetic V8 retention','REMOTE','Asia/Seoul','2026-10-07','2026-10-12','HOST_SPECIFIED',true," +
                "'CLOSED','MANUAL','2026-10-01T00:00:00.123456Z','2026-09-30T00:00:00.234567Z')",
            room,
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(22),
        )
        execute("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,'2026-10-01T00:00:00.345678Z')", batch, room)
        execute(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,candidate_quality,created_at,version) " +
                "VALUES (?,?,?,'COMPLETED','COMPLETE','2026-10-01T00:00:00.456789Z',7)",
            run,
            room,
            batch,
        )
        execute("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", run, room)
        repeat(2) { index ->
            val session = UUID.randomUUID()
            val participant = UUID.randomUUID()
            val submission = UUID.randomUUID()
            val version = UUID.randomUUID()
            execute(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) " +
                    "VALUES (?,?,'2026-11-01T00:00:00Z','2026-09-30T00:00:00Z')",
                session,
                UUID.randomUUID().toString(),
            )
            execute(
                "INSERT INTO participants(id,room_id,guest_session_id,role,joined_at,display_name) " +
                    "VALUES (?,?,?,?,'2026-09-30T00:00:00Z',?)",
                participant,
                room,
                session,
                if (index == 0) "HOST" else "MEMBER",
                "synthetic-$index",
            )
            execute("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", submission, room, participant)
            execute(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) " +
                    "VALUES (?,?,1,?,'ko-KR','2026-09-30T00:00:00.567890Z')",
                version,
                submission,
                "synthetic original $state $index",
            )
            execute("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, submission)
            execute("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", batch, version)
            execute(
                "INSERT INTO structured_submission_results(batch_id,submission_version_id,conditions,processed_at) " +
                    "VALUES (?,?,'[{\"type\":\"TIME_WINDOW\",\"polarity\":\"AVAILABLE\",\"date\":\"2026-10-07\"," +
                    "\"dayOfWeek\":null,\"startTime\":\"19:00\",\"endTime\":\"21:00\",\"endsAtNextDayStart\":false}]'::jsonb," +
                    "'2026-10-01T00:00:00.678901Z')",
                batch,
                version,
            )
        }
        execute(
            "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at," +
                "input_tokens,output_tokens,response_bytes,estimated_cost_usd) " +
                "VALUES (?,?,1,'2026-10-01T00:00:00.111111Z','2026-10-01T00:00:01.222222Z',123,45,678,0.00012345)",
            UUID.randomUUID(),
            run,
        )
        val candidate = UUID.randomUUID()
        execute(
            "INSERT INTO candidates(id,coordination_run_id,rank,plan_type,meeting_mode,attendance_count,total_participants) " +
                "VALUES (?,?,1,'PLAN_A','REMOTE',2,2)",
            candidate,
            run,
        )
        execute(
            "INSERT INTO candidate_time_ranges(candidate_id,range_order,start_at,end_at) " +
                "VALUES (?,0,'2026-10-07T10:00:00Z','2026-10-07T12:00:00Z')",
            candidate,
        )
        if (state == "CONFIRMED") {
            execute(
                "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) " +
                    "VALUES (?,?,'2026-10-02T00:00:00.789012Z')",
                run,
                candidate,
            )
        } else {
            val round = UUID.randomUUID()
            execute(
                "INSERT INTO input_revision_rounds(id,room_id,generation,source_run_id,reopen_request_id," +
                    "expected_generation,status,opened_at) VALUES (?,?,1,?,?,0,'OPEN','2026-10-02T00:00:00.890123Z')",
                round,
                room,
                run,
                UUID.randomUUID(),
            )
            if (state == "CONSUMED") {
                execute(
                    "UPDATE input_revision_rounds SET status='CONSUMED',consumed_at='2026-10-02T00:01:00.901234Z'," +
                        "analyze_request_id=?,force_reparse=false,resolved_run_id=?,outcome='REUSED' WHERE id=?",
                    UUID.randomUUID(),
                    run,
                    round,
                )
            }
            execute("UPDATE meeting_rooms SET revision_generation=1,active_revision_round_id=? WHERE id=?", round, room)
        }
    }

    private fun snapshots(): Map<String, String> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "structured_submission_results",
            "coordination_runs",
            "coordination_attempts",
            "candidates",
            "candidate_time_ranges",
            "final_confirmations",
            "input_revision_rounds",
        ).associateWith { table ->
            val value =
                if (table == "coordination_attempts") {
                    "to_jsonb(t) - 'provider' - 'model' - 'policy_version' - 'invocation_id'"
                } else {
                    "to_jsonb(t)"
                }
            scalar("SELECT COALESCE(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM (SELECT $value AS row FROM $table t) q")
        }

    private fun execute(
        sql: String,
        vararg arguments: Any,
    ) {
        connection().use { c ->
            c.prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        }
    }

    private fun scalar(sql: String): String =
        connection().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getString(1)
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
