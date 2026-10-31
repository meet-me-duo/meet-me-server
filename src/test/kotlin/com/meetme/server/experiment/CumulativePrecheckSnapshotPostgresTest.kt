package com.meetme.server.experiment

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V16 defines execution identity as (check_id, generation); reporting must preserve both generations and unknown usage. */
class CumulativePrecheckSnapshotPostgresTest {
    private val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(source)
    private val at = Instant.parse("2026-10-29T12:00:00Z")
    private val checkId = UUID(0, 105)

    @BeforeEach
    fun migrateFreshTestDatabase() {
        Flyway
            .configure()
            .dataSource(source)
            .target("21")
            .load()
            .migrate()
        jdbc.execute("TRUNCATE TABLE meeting_rooms, guest_browser_sessions CASCADE")
    }

    @Test
    fun `snapshot retains composite identity failure facts and nullable usage across generations`() {
        seedCheck()
        jdbc.update(
            """
            INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at,finished_at,
            provider,model,failure_code) VALUES (?,1,?,?,?,'GEMINI','synthetic-model','SERVER')
            """.trimIndent(),
            checkId,
            Timestamp.from(at),
            Timestamp.from(at.plusSeconds(30)),
            Timestamp.from(at.plusSeconds(1)),
        )
        jdbc.update(
            """
            INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at,finished_at,
            provider,model,input_tokens,output_tokens,response_bytes)
            VALUES (?,2,?,?,?,'OPENAI','synthetic-fallback',17,5,123)
            """.trimIndent(),
            checkId,
            Timestamp.from(at.plusSeconds(2)),
            Timestamp.from(at.plusSeconds(32)),
            Timestamp.from(at.plusSeconds(3)),
        )

        val rows = jdbc.queryForList(CUMULATIVE_PRECHECK_EXECUTIONS_SQL)
        assertEquals(2, rows.size)
        val byIdentity = rows.associateBy { it["check_id"] to (it["generation"] as Number).toInt() }
        assertEquals(setOf(checkId to 1, checkId to 2), byIdentity.keys)
        val failed = byIdentity.getValue(checkId to 1)
        assertEquals("GEMINI", failed["provider"])
        assertEquals("synthetic-model", failed["model"])
        assertEquals("SERVER", failed["failure_code"])
        assertNull(failed["input_tokens"])
        assertNull(failed["output_tokens"])
        assertNull(failed["response_bytes"])
        assertEquals(at.plusSeconds(1), (failed["finished_at"] as Timestamp).toInstant())
        val succeeded = byIdentity.getValue(checkId to 2)
        assertEquals("OPENAI", succeeded["provider"])
        assertEquals("synthetic-fallback", succeeded["model"])
        assertNull(succeeded["failure_code"])
        assertEquals(17L, (succeeded["input_tokens"] as Number).toLong())
        assertEquals(5L, (succeeded["output_tokens"] as Number).toLong())
        assertEquals(123, (succeeded["response_bytes"] as Number).toInt())
        assertEquals(at.plusSeconds(3), (succeeded["finished_at"] as Timestamp).toInstant())
    }

    @Test
    fun `snapshot of a present empty execution table is a known empty result`() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM precheck_executions", Int::class.java))
        assertTrue(jdbc.queryForList(CUMULATIVE_PRECHECK_EXECUTIONS_SQL).isEmpty())
    }

    private fun seedCheck() {
        val room = UUID(0, 101)
        val guest = UUID(0, 102)
        val participant = UUID(0, 103)
        val candidate = UUID(0, 104)
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic SQL snapshot','REMOTE','Asia/Seoul','2026-10-29','2026-10-30',
            'HOST_SPECIFIED',true,'COLLECTING',?,'snapshotfixture0000001')
            """.trimIndent(),
            room,
            Timestamp.from(at),
        )
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
            guest,
            "synthetic-snapshot-digest",
            Timestamp.from(at.plusSeconds(3600)),
            Timestamp.from(at),
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,joined_at,display_name) VALUES (?,?,?,'MEMBER',?,'Synthetic')",
            participant,
            room,
            guest,
            Timestamp.from(at),
        )
        jdbc.update(
            """
            INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,
            created_at,interpretation_reference_at,owner_binding) VALUES (?,?,?,1,'synthetic input','ko-KR',?,?,'synthetic-owner')
            """.trimIndent(),
            candidate,
            room,
            participant,
            Timestamp.from(at),
            Timestamp.from(at),
        )
        jdbc.update(
            """
            INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,deadline_at)
            VALUES (?,?,'CLEAR',1,2,1,?)
            """.trimIndent(),
            checkId,
            candidate,
            Timestamp.from(at.plusSeconds(32)),
        )
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
