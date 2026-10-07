package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

// Independent #95 retention boundary: confirmed recommendation rows follow original CLOSED anchor and room retention.
class RecommendationRetentionPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Test
    fun `expired typed confirmed room is deleted without leftovers and recently closed room remains intact`() {
        val expired = recommendedRoom()
        val fresh = recommendedRoom()
        for (fixture in listOf(expired, fresh)) {
            assertEquals(
                1,
                jdbc.update(
                    "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                        "start_at, end_at, confirmed_at) " +
                        "SELECT o.coordination_run_id, o.room_id, o.id, v.id, o.start_at, o.end_at, now() " +
                        "FROM recommendation_options o JOIN recommendation_variants v ON v.option_id = o.id " +
                        "WHERE o.coordination_run_id = ? ORDER BY o.ordinal, v.id LIMIT 1",
                    fixture.sourceRun,
                ),
            )
        }
        val now = Instant.now()
        jdbc.update(
            "UPDATE meeting_rooms SET created_at = ?, closed_at = ? WHERE id = ?",
            now.minus(32, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            now.minus(31, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            expired.roomId,
        )
        jdbc.update(
            "UPDATE meeting_rooms SET created_at = ?, closed_at = ? WHERE id = ?",
            now.minus(32, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            now.atOffset(ZoneOffset.UTC),
            fresh.roomId,
        )
        val freshRoom = jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id = ?", fresh.roomId)
        val tables =
            listOf(
                "recommendation_analyses",
                "recommendation_options",
                "recommendation_variants",
                "recommendation_variant_participants",
                "recommendation_selections",
            )
        val freshSnapshots =
            tables.associateWith {
                jdbc.queryForList("SELECT * FROM $it WHERE room_id = ?", fresh.roomId).toSet()
            }
        assertEquals(1, retention.deleteExpiredRooms(now.minus(30, ChronoUnit.DAYS), 10, now))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM meeting_rooms WHERE id = ?", Int::class.java, expired.roomId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_runs WHERE room_id = ?", Int::class.java, expired.roomId))
        for (table in tables) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table WHERE room_id = ?", Int::class.java, expired.roomId), table)
            assertEquals(
                freshSnapshots.getValue(table),
                jdbc.queryForList("SELECT * FROM $table WHERE room_id = ?", fresh.roomId).toSet(),
                table,
            )
        }
        assertEquals(freshRoom, jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id = ?", fresh.roomId))
        assertEquals("CLOSED", freshRoom["collection_status"])
        assertEquals("CONFIRMED", room(fresh.host)["public_status"])
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
