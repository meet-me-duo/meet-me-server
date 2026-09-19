package com.meetme.server.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class MatchingResultsMigrationTest {
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
    fun `fresh migration creates V4 matching result schema`() {
        val latest = flyway()

        latest.migrate()

        assertEquals(
            "4",
            latest
                .info()
                .current()
                .version.version,
        )
        latest.validate()
        assertNotNull(query("SELECT to_regclass('public.normalized_places')"))
        assertNotNull(query("SELECT to_regclass('public.candidate_participants')"))
        assertEquals(
            "resume_stage",
            query("SELECT column_name FROM information_schema.columns WHERE table_name='coordination_runs' AND column_name='resume_stage'"),
        )
        assertEquals(
            "plan_type",
            query("SELECT column_name FROM information_schema.columns WHERE table_name='candidates' AND column_name='plan_type'"),
        )
        assertEquals(
            "meeting_mode",
            query("SELECT column_name FROM information_schema.columns WHERE table_name='candidates' AND column_name='meeting_mode'"),
        )
        assertEquals(
            "attendance_count",
            query("SELECT column_name FROM information_schema.columns WHERE table_name='candidates' AND column_name='attendance_count'"),
        )
        assertEquals(
            "total_participants",
            query("SELECT column_name FROM information_schema.columns WHERE table_name='candidates' AND column_name='total_participants'"),
        )
        assertEquals(0, countColumns("candidates", "summary"))
    }

    @Test
    fun `upgrade from V3 preserves run and permits completed empty candidate result`() {
        flyway("3").migrate()
        val fixture = insertRun(status = "MATCHING")

        val latest = flyway()
        latest.migrate()
        connection().use { connection ->
            connection
                .prepareStatement(
                    "UPDATE coordination_runs SET status='COMPLETED', candidate_quality='COMPLETE' WHERE id=?",
                ).use { statement ->
                    statement.setObject(1, fixture.runId)
                    statement.executeUpdate()
                }
        }

        assertEquals(1, count("coordination_runs"))
        assertEquals(0, count("candidates"))
        latest.validate()
    }

    @Test
    fun `resume stage accepts only structuring or matching`() {
        flyway().migrate()
        val fixture = insertRun(status = "ANALYSIS_DELAYED")

        updateResumeStage(fixture.runId, "MATCHING")

        assertEquals("MATCHING", query("SELECT resume_stage FROM coordination_runs WHERE id='${fixture.runId}'"))
        assertFails { updateResumeStage(fixture.runId, "QUEUED") }
    }

    @Test
    fun `normalized place stores only stable snapshot fields and rejects duplicate query owner`() {
        flyway().migrate()
        val fixture = insertRun(status = "MATCHING")

        insertNormalizedPlace(fixture, UUID.randomUUID(), "봉천역")

        assertEquals(1, count("normalized_places"))
        assertFails { insertNormalizedPlace(fixture, UUID.randomUUID(), "봉천역") }
        assertEquals(0, countColumns("normalized_places", "provider_response"))
        assertEquals(0, countColumns("normalized_places", "raw_response"))
    }

    @Test
    fun `candidate participant membership is unique and foreign keyed`() {
        flyway().migrate()
        val fixture = insertRun(status = "COMPLETED")
        val otherRoom = insertRun(status = "COMPLETED")
        val candidateId = insertCandidate(fixture, rank = 1)

        insertCandidateParticipant(candidateId, fixture.hostParticipantId)

        assertFails { insertCandidateParticipant(candidateId, fixture.hostParticipantId) }
        assertFails { insertCandidateParticipant(candidateId, UUID.randomUUID()) }
        assertFails { insertCandidateParticipant(candidateId, otherRoom.hostParticipantId) }
    }

    @Test
    fun `confirmation accepts candidate from same run only and one row wins`() {
        flyway().migrate()
        val first = insertRun(status = "COMPLETED")
        val second = insertRun(status = "COMPLETED")
        val firstCandidate = insertCandidate(first, rank = 1)
        val otherCandidate = insertCandidate(second, rank = 1)

        insertConfirmation(first.runId, firstCandidate)

        assertFails { insertConfirmation(first.runId, otherCandidate) }
        val secondCandidate = insertCandidate(first, rank = 2)
        assertFails { insertConfirmation(first.runId, secondCandidate) }
        assertEquals(1, count("final_confirmations"))
    }

    private fun insertRun(status: String): Fixture {
        val sessionId = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val participantId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        connection().use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement(
                    "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                ).use {
                    it.setObject(1, sessionId)
                    it.setString(2, UUID.randomUUID().toString().replace("-", ""))
                    it.setObject(3, Instant.parse("2026-10-19T00:00:00Z").atOffset(ZoneOffset.UTC))
                    it.setObject(4, Instant.parse("2026-09-19T00:00:00Z").atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO meeting_rooms(
                        id,invite_code,purpose,duration_minutes,meeting_mode,time_zone_id,search_start_date,search_end_date,
                        search_range_source,expected_participants,submission_deadline,manual_only,collection_status,
                        closure_reason,closed_at,created_at,version
                    ) VALUES (?,?,'migration fixture',60,'EITHER','Asia/Seoul',DATE '2026-09-20',DATE '2026-09-27',
                        'HOST_SPECIFIED',2,NULL,FALSE,'CLOSED','EXPECTED_PARTICIPANTS',TIMESTAMPTZ '2026-09-19 01:00:00Z',
                        TIMESTAMPTZ '2026-09-19 00:00:00Z',0)
                    """.trimIndent(),
                ).use {
                    it.setObject(1, roomId)
                    it.setString(
                        2,
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(22),
                    )
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO participants(id,room_id,guest_session_id,display_name,role,joined_at) VALUES (?,?,?,'host','HOST',now())",
                ).use {
                    it.setObject(1, participantId)
                    it.setObject(2, roomId)
                    it.setObject(3, sessionId)
                    it.executeUpdate()
                }
            connection.prepareStatement("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)").use {
                it.setObject(1, submissionId)
                it.setObject(2, roomId)
                it.setObject(3, participantId)
                it.executeUpdate()
            }
            connection
                .prepareStatement(
                    "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,'fixture','ko-KR',now())",
                ).use {
                    it.setObject(1, versionId)
                    it.setObject(2, submissionId)
                    it.executeUpdate()
                }
            connection.prepareStatement("UPDATE submission_heads SET latest_version_id=? WHERE id=?").use {
                it.setObject(1, versionId)
                it.setObject(2, submissionId)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,now())").use {
                it.setObject(1, batchId)
                it.setObject(2, roomId)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)").use {
                it.setObject(1, batchId)
                it.setObject(2, versionId)
                it.executeUpdate()
            }
            connection
                .prepareStatement(
                    "INSERT INTO coordination_runs(id,room_id,batch_id,status,candidate_quality,created_at,version) VALUES (?,?,?,?,?,now(),0)",
                ).use {
                    it.setObject(1, runId)
                    it.setObject(2, roomId)
                    it.setObject(3, batchId)
                    it.setString(4, status)
                    it.setString(5, if (status == "COMPLETED") "COMPLETE" else null)
                    it.executeUpdate()
                }
            connection.commit()
        }
        return Fixture(roomId, participantId, versionId, batchId, runId)
    }

    private fun updateResumeStage(
        runId: UUID,
        stage: String,
    ) {
        connection().use { connection ->
            connection.prepareStatement("UPDATE coordination_runs SET resume_stage=? WHERE id=?").use {
                it.setString(1, stage)
                it.setObject(2, runId)
                it.executeUpdate()
            }
        }
    }

    private fun insertNormalizedPlace(
        fixture: Fixture,
        id: UUID,
        query: String,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO normalized_places(
                        id,coordination_run_id,batch_id,submission_version_id,query,
                        provider,provider_place_id,display_name,latitude,longitude
                    ) VALUES (?,?,?,?,?,'KAKAO','station-1','봉천역',37.482000,126.946000)
                    """.trimIndent(),
                ).use {
                    it.setObject(1, id)
                    it.setObject(2, fixture.runId)
                    it.setObject(3, fixture.batchId)
                    it.setObject(4, fixture.submissionVersionId)
                    it.setString(5, query)
                    it.executeUpdate()
                }
        }
    }

    private fun insertCandidate(
        fixture: Fixture,
        rank: Int,
    ): UUID {
        val id = UUID.randomUUID()
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO candidates(
                        id,coordination_run_id,rank,plan_type,meeting_mode,attendance_count,total_participants,
                        place_name,latitude,longitude
                    ) VALUES (?,?,?,'PLAN_A','REMOTE',1,1,NULL,NULL,NULL)
                    """.trimIndent(),
                ).use {
                    it.setObject(1, id)
                    it.setObject(2, fixture.runId)
                    it.setInt(3, rank)
                    it.executeUpdate()
                }
        }
        return id
    }

    private fun insertCandidateParticipant(
        candidateId: UUID,
        participantId: UUID,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO candidate_participants(candidate_id,participant_id,coordination_run_id,room_id)
                    SELECT ?, ?, c.coordination_run_id, cr.room_id
                    FROM candidates c JOIN coordination_runs cr ON cr.id = c.coordination_run_id
                    WHERE c.id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, candidateId)
                    it.setObject(2, participantId)
                    it.setObject(3, candidateId)
                    it.executeUpdate()
                }
        }
    }

    private fun insertConfirmation(
        runId: UUID,
        candidateId: UUID,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,now())",
                ).use {
                    it.setObject(1, runId)
                    it.setObject(2, candidateId)
                    it.executeUpdate()
                }
        }
    }

    private fun count(table: String): Int =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun countColumns(
        table: String,
        column: String,
    ): Int =
        connection().use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?",
                ).use {
                    it.setString(1, table)
                    it.setString(2, column)
                    it.executeQuery().use { result ->
                        result.next()
                        result.getInt(1)
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

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private data class Fixture(
        val roomId: UUID,
        val hostParticipantId: UUID,
        val submissionVersionId: UUID,
        val batchId: UUID,
        val runId: UUID,
    )

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
