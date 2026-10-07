package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Independent #95 database boundary: deliberately use old-writer SQL, bypassing new application protections.
class RecommendationDatabaseGuardPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `old candidate writer remains valid for a legacy analysis and is rejected for new protocol`() {
        val old = completedRoom()
        val oldCandidate = candidate(old)
        assertEquals(1, legacyConfirm(old, oldCandidate))
        val current = recommendedRoom()
        val currentCandidate =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM candidates WHERE coordination_run_id = ? ORDER BY rank LIMIT 1",
                    UUID::class.java,
                    current.sourceRun,
                ),
            )
        assertFailsWith<DataAccessException> { legacyConfirm(current, currentCandidate) }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
    }

    @Test
    fun `persisted typed selection makes old run status quality and room pointer updates fail`() {
        val fixture = recommendedRoom(partial = true)
        assertEquals(1, directSelection(fixture))
        val runBefore = jdbc.queryForMap("SELECT status, candidate_quality, version FROM coordination_runs WHERE id = ?", fixture.sourceRun)
        val roomBefore =
            jdbc.queryForMap(
                "SELECT active_run_id, active_revision_round_id, version FROM meeting_rooms WHERE id = ?",
                fixture.roomId,
            )
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "UPDATE coordination_runs SET status = 'ANALYSIS_DELAYED', candidate_quality = NULL, " +
                    "resume_stage = 'MATCHING' WHERE id = ?",
                fixture.sourceRun,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbc.update("UPDATE coordination_runs SET candidate_quality = 'COMPLETE' WHERE id = ?", fixture.sourceRun)
        }
        assertFailsWith<DataAccessException> { jdbc.update("UPDATE meeting_rooms SET active_run_id = NULL WHERE id = ?", fixture.roomId) }
        assertEquals(
            runBefore,
            jdbc.queryForMap("SELECT status, candidate_quality, version FROM coordination_runs WHERE id = ?", fixture.sourceRun),
        )
        assertEquals(
            roomBefore,
            jdbc.queryForMap("SELECT active_run_id, active_revision_round_id, version FROM meeting_rooms WHERE id = ?", fixture.roomId),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
    }

    @Test
    fun `old revision writer cannot insert an OPEN round after typed selection`() {
        val unconfirmed = recommendedRoom(partial = true)
        assertEquals(1, directRound(unconfirmed))
        val selected = recommendedRoom(partial = true)
        assertEquals(1, directSelection(selected))
        assertFailsWith<DataAccessException> { directRound(selected) }
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds WHERE room_id = ?", Int::class.java, selected.roomId),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_selections WHERE coordination_run_id = ?",
                Int::class.java,
                selected.sourceRun,
            ),
        )
    }

    @Test
    fun `direct typed inserts reject escaped window foreign option variant and room ownership`() {
        val fixture = recommendedRoom()
        val pair =
            jdbc.queryForMap(
                "SELECT o.id AS option_id, v.id AS variant_id FROM recommendation_options o " +
                    "JOIN recommendation_variants v ON v.option_id = o.id " +
                    "WHERE o.coordination_run_id = ? ORDER BY o.ordinal, v.id LIMIT 1",
                fixture.sourceRun,
            )
        val otherVariant =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM recommendation_variants WHERE coordination_run_id = ? AND option_id <> ? ORDER BY id LIMIT 1",
                    UUID::class.java,
                    fixture.sourceRun,
                    pair.getValue("option_id"),
                ),
            )
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                    "start_at, end_at, confirmed_at) " +
                    "SELECT coordination_run_id, room_id, id, ?, start_at - interval '1 second', end_at, now() " +
                    "FROM recommendation_options WHERE id = ?",
                pair.getValue("variant_id"),
                pair.getValue("option_id"),
            )
        }
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                    "start_at, end_at, confirmed_at) " +
                    "SELECT coordination_run_id, room_id, id, ?, start_at, end_at, now() FROM recommendation_options WHERE id = ?",
                otherVariant,
                pair.getValue("option_id"),
            )
        }
        val foreign = recommendedRoom()
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                    "start_at, end_at, confirmed_at) " +
                    "SELECT coordination_run_id, ?, id, ?, start_at, end_at, now() FROM recommendation_options WHERE id = ?",
                foreign.roomId,
                pair.getValue("variant_id"),
                pair.getValue("option_id"),
            )
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        assertEquals(1, directSelection(fixture), "The same valid tuple must remain insertable after rejected writes")
    }

    @Test
    fun `direct typed insertion during OPEN revision fails while unconfirmed published options remain immutable`() {
        val fixture = recommendedRoom(partial = true)
        val optionsBefore =
            jdbc.queryForList(
                "SELECT id, start_at, end_at FROM recommendation_options WHERE coordination_run_id = ? ORDER BY ordinal",
                fixture.sourceRun,
            )
        openRound(fixture)
        assertFailsWith<DataAccessException> { directSelection(fixture) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        assertEquals(
            optionsBefore,
            jdbc.queryForList(
                "SELECT id, start_at, end_at FROM recommendation_options WHERE coordination_run_id = ? ORDER BY ordinal",
                fixture.sourceRun,
            ),
        )
    }

    @Test
    fun `variant participant same room FK rejects another room participant`() {
        val fixture = recommendedRoom()
        val foreign = completedRoom()
        val outsider =
            requireNotNull(
                jdbc.queryForObject("SELECT id FROM participants WHERE room_id = ? ORDER BY id LIMIT 1", UUID::class.java, foreign.roomId),
            )
        val before = jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants", Int::class.java)
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_variant_participants(variant_id, option_id, coordination_run_id, room_id, participant_id) " +
                    "SELECT id, option_id, coordination_run_id, room_id, ? FROM recommendation_variants " +
                    "WHERE coordination_run_id = ? ORDER BY id LIMIT 1",
                outsider,
                fixture.sourceRun,
            )
        }
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants", Int::class.java))
    }

    private fun directSelection(fixture: CompletedCorrectionFixture): Int =
        jdbc.update(
            "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                "start_at, end_at, confirmed_at) " +
                "SELECT o.coordination_run_id, o.room_id, o.id, v.id, o.start_at, o.end_at, now() " +
                "FROM recommendation_options o JOIN recommendation_variants v ON v.option_id = o.id " +
                "WHERE o.coordination_run_id = ? ORDER BY o.ordinal, v.id LIMIT 1",
            fixture.sourceRun,
        )

    private fun directRound(fixture: CompletedCorrectionFixture): Int =
        jdbc.update(
            "INSERT INTO input_revision_rounds(id, room_id, generation, source_run_id, reopen_request_id, " +
                "expected_generation, status, opened_at) " +
                "VALUES (?, ?, 1, ?, ?, 0, 'OPEN', now())",
            UUID.randomUUID(),
            fixture.roomId,
            fixture.sourceRun,
            UUID.randomUUID(),
        )

    private fun candidate(fixture: CompletedCorrectionFixture): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) " +
                "VALUES (?, ?, 1, 'PLAN_B', 'REMOTE', 2, 2)",
            id,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id, range_order, start_at, end_at) " +
                "VALUES (?, 0, '2026-10-07T09:00:00Z', '2026-10-07T12:00:00Z')",
            id,
        )
        return id
    }

    private fun legacyConfirm(
        fixture: CompletedCorrectionFixture,
        candidate: UUID,
    ): Int =
        jdbc.update(
            "INSERT INTO final_confirmations(coordination_run_id, candidate_id, confirmed_at) VALUES (?, ?, now())",
            fixture.sourceRun,
            candidate,
        )

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
