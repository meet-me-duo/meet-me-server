package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.RecommendationQuery
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent user contract: organizer selection preserves uncertainty instead of certifying a place condition. */
class ConditionalPlacePersistenceContractTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var structuredResults: StructuredSubmissionRepository

    @Autowired private lateinit var runRepository: CoordinationRunRepository

    @Autowired private lateinit var recommendationRepository: RecommendationRepository

    @Test
    fun `CPP01 unverified participants round trip in legacy and recommendation projections without changing frozen restrictions`() {
        val fixture = conditionalRoom()
        val run = requireNotNull(runRepository.findById(CoordinationRunId(fixture.sourceRun)))
        val preserved = structuredResults.findByBatch(run.batch.id)
        val expected =
            jdbc
                .queryForList(
                    "SELECT participant_id FROM candidate_participants WHERE coordination_run_id=? AND place_unverified ORDER BY participant_id::text",
                    UUID::class.java,
                    fixture.sourceRun,
                ).map { ParticipantId(requireNotNull(it)) }
        assertEquals(1, expected.size)
        assertEquals(expected, run.candidates.single().unverifiedPlaceParticipantIds)
        val analysis = requireNotNull(recommendationRepository.find(fixture.sourceRun, RecommendationQuery()))
        assertEquals(
            expected,
            analysis.options
                .single()
                .option.variants
                .single()
                .unverifiedPlaceParticipantIds,
        )
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants WHERE place_unverified", Int::class.java),
        )
        assertEquals(1, preserved.count { result -> result.conditions.any { it is StructuredCondition.TravelConstraint } })
        assertTrue(
            preserved.flatMap { it.conditions }.filterIsInstance<StructuredCondition.TravelConstraint>().all {
                it.expression ==
                    PRIVATE_TRAVEL
            },
        )
        val view = items(recommendations(fixture.host), "options").single()
        val variant = items(view, "variants").single()
        assertEquals(false, variant["attendance_confirmed"])
        assertEquals("IN_PERSON", variant["meeting_mode"])
        assertFalse(variant["summary"].toString().contains(PRIVATE_TRAVEL))
        assertEquals(preserved, structuredResults.findByBatch(run.batch.id))
    }

    @Test
    fun `CPP02 selecting and rereading a time leaves actual in person attendance unverified`() {
        val fixture = conditionalRoom()
        val before = pendingRows(fixture.sourceRun)
        val option = items(recommendations(fixture.host), "options").single()
        val variant = items(option, "variants").single()
        val selected =
            document(
                mvc
                    .perform(selection(fixture, option, variant))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(false, child(selected, "candidate")["attendance_confirmed"])
        val stored = result(fixture.host)
        val candidate = child(stored, "candidate")
        assertEquals(false, candidate["attendance_confirmed"])
        assertEquals("IN_PERSON", candidate["meeting_mode"])
        val summary = candidate["summary"].toString()
        assertTrue(summary.contains("확인"), "The selected candidate must keep the place verification limitation in its existing explanation")
        assertFalse(summary.contains(PRIVATE_TRAVEL), "Public confirmation cannot reveal private movement text")
        assertFalse(summary.contains("모두 참여 가능"))
        assertEquals(before, pendingRows(fixture.sourceRun))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_selections WHERE coordination_run_id=?",
                Int::class.java,
                fixture.sourceRun,
            ),
        )
        assertEquals("CONFIRMED", room(fixture.host)["public_status"])
        mvc.perform(selection(fixture, option, variant)).andExpect(status().isOk)
        assertEquals(stored, result(fixture.host), "Idempotent selection does not certify previously unverified conditions")
    }

    @Test
    fun `CPP03 a time only in person proposal persists without inventing a venue or switching online`() {
        val fixture = conditionalRoom(removeKnownPlace = true)
        val option = items(recommendations(fixture.host), "options").single()
        val variant = items(option, "variants").single()
        assertEquals("IN_PERSON", variant["meeting_mode"])
        assertNull(variant["place"])
        assertEquals(false, variant["attendance_confirmed"])
        mvc.perform(selection(fixture, option, variant)).andExpect(status().isOk)
        val candidate = child(result(fixture.host), "candidate")
        assertEquals("IN_PERSON", candidate["meeting_mode"])
        assertNull(candidate["place"])
        assertEquals(false, candidate["attendance_confirmed"])
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_variants WHERE meeting_mode='IN_PERSON' AND place_name IS NULL",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `CPP04 old SQL writers cannot clear uncertainty after confirmation in either stored projection`() {
        val fixture = conditionalRoom()
        val option = items(recommendations(fixture.host), "options").single()
        mvc.perform(selection(fixture, option)).andExpect(status().isOk)
        val before = pendingRows(fixture.sourceRun)
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "UPDATE recommendation_variant_participants SET place_unverified=false WHERE coordination_run_id=? AND place_unverified",
                fixture.sourceRun,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "UPDATE candidate_participants SET place_unverified=false WHERE coordination_run_id=? AND place_unverified",
                fixture.sourceRun,
            )
        }
        assertEquals(before, pendingRows(fixture.sourceRun))
        assertEquals(false, child(result(fixture.host), "candidate")["attendance_confirmed"])
    }

    @Test
    fun `CPP05 database rejects offline uncertainty attached to a newly inserted remote participant`() {
        val fixture = conditionalRoom(mode = MeetingMode.REMOTE)
        val option = items(recommendations(fixture.host), "options").single()
        assertEquals(true, items(option, "variants").single()["attendance_confirmed"])
        val participant =
            requireNotNull(
                jdbc.queryForObject("SELECT id FROM participants WHERE room_id=? ORDER BY id LIMIT 1", UUID::class.java, fixture.roomId),
            )
        val variant = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO recommendation_variants(id, option_id, coordination_run_id, room_id, meeting_mode, preference_count) VALUES (?, ?, ?, ?, 'REMOTE', 0)",
            variant,
            UUID.fromString(option["option_id"].toString()),
            fixture.sourceRun,
            fixture.roomId,
        )
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_variant_participants(variant_id, option_id, coordination_run_id, room_id, participant_id, place_unverified) VALUES (?, ?, ?, ?, ?, true)",
                variant,
                UUID.fromString(option["option_id"].toString()),
                fixture.sourceRun,
                fixture.roomId,
                participant,
            )
        }
        val candidate = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) VALUES (?, ?, 2, 'PLAN_B', 'REMOTE', 2, 2)",
            candidate,
            fixture.sourceRun,
        )
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id, place_unverified) VALUES (?, ?, ?, ?, true)",
                candidate,
                participant,
                fixture.sourceRun,
                fixture.roomId,
            )
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM candidate_participants WHERE place_unverified", Int::class.java))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants WHERE place_unverified", Int::class.java),
        )
    }

    private fun conditionalRoom(
        removeKnownPlace: Boolean = false,
        mode: MeetingMode = MeetingMode.IN_PERSON,
    ): CompletedCorrectionFixture {
        val fixture = matchingRoom()
        jdbc.update("UPDATE meeting_rooms SET meeting_mode=? WHERE id=?", mode.name, fixture.roomId)
        prepareMatching(fixture.sourceRun, days = 1)
        val run = requireNotNull(runRepository.findById(CoordinationRunId(fixture.sourceRun)))
        structuredResults.replaceForBatch(
            run.batch.id,
            structuredResults.findByBatch(run.batch.id).mapIndexed { index, result ->
                val conditions = result.conditions.filterNot { removeKnownPlace && it is StructuredCondition.SpecificPlace }
                result.copy(conditions = if (index == 0) conditions + StructuredCondition.TravelConstraint(PRIVATE_TRAVEL) else conditions)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        process(fixture.sourceRun)
        return fixture
    }

    private fun pendingRows(run: UUID): List<List<Map<String, Any?>>> =
        listOf("candidate_participants", "recommendation_variant_participants").map { table ->
            jdbc.queryForList(
                "SELECT participant_id, place_unverified FROM $table WHERE coordination_run_id=? ORDER BY participant_id::text",
                run,
            )
        }

    companion object {
        private const val PRIVATE_TRAVEL = "사적인 출발지에서 환승 없이 이동"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
