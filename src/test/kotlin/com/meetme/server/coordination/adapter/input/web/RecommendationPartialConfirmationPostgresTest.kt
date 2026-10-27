package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** FR-011, ADR-060: every published actual attendee set of at least two can be confirmed. */
class RecommendationPartialConfirmationPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var partialStructured: StructuredSubmissionRepository

    @Autowired private lateinit var partialRuns: CoordinationRunRepository

    @Test
    fun `PC01 two of five actual attendees confirm persist and replay the same selection`() {
        assertPartialConfirmation(2)
    }

    @Test
    fun `PC02 three of five actual attendees retain the same confirmation contract`() {
        assertPartialConfirmation(3)
    }

    @Test
    fun `PC03 direct selection rejects a one attendee variant without persisting a confirmation`() {
        val fixture = fiveMemberRoom(2)
        val variant = variantId(fixture)
        jdbc.update(
            "DELETE FROM recommendation_variant_participants WHERE variant_id = ? AND participant_id = " +
                "(SELECT participant_id FROM recommendation_variant_participants WHERE variant_id = ? ORDER BY participant_id LIMIT 1)",
            variant,
            variant,
        )
        assertEquals(1, attendeeCount(variant))
        assertFailsWith<DataAccessException> { directSelection(fixture) }
        assertEquals(0, selectionCount(fixture))
    }

    @Test
    fun `PC04 same room participant outside frozen batch cannot be selected even with enough attendees`() {
        val fixture = fiveMemberRoom(3)
        val variant = variantId(fixture)
        val lateAccount = accounts.newAccount("late participant")
        val lateParticipant = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'MEMBER',now(),?)",
            lateParticipant,
            fixture.roomId,
            UUID.fromString(lateAccount.value.removePrefix("test-account-")),
            "late participant",
        )
        jdbc.update(
            "INSERT INTO recommendation_variant_participants(variant_id,option_id,coordination_run_id,room_id,participant_id) " +
                "SELECT id,option_id,coordination_run_id,room_id,? FROM recommendation_variants WHERE id = ?",
            lateParticipant,
            variant,
        )
        assertEquals(4, attendeeCount(variant))
        assertFailsWith<DataAccessException> { directSelection(fixture) }
        assertEquals(0, selectionCount(fixture))
    }

    @Test
    fun `PC05 duplicate attendee cannot inflate a published attendee set`() {
        val fixture = fiveMemberRoom(3)
        val variant = variantId(fixture)
        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO recommendation_variant_participants(variant_id,option_id,coordination_run_id,room_id,participant_id) " +
                    "SELECT variant_id,option_id,coordination_run_id,room_id,participant_id " +
                    "FROM recommendation_variant_participants WHERE variant_id = ? LIMIT 1",
                variant,
            )
        }
        assertEquals(3, attendeeCount(variant))
        assertEquals(1, directSelection(fixture))
        assertEquals(1, selectionCount(fixture))
    }

    private fun assertPartialConfirmation(availableCount: Int) {
        val fixture = fiveMemberRoom(availableCount)
        val view = recommendations(fixture.host)
        assertEquals("diverse-time-v1", view["protocol"])
        val option = items(view, "options").single()
        val variant = items(option, "variants").single()
        assertEquals(availableCount, (variant.getValue("attendance_count") as Number).toInt())
        assertEquals(5, (variant.getValue("total_participants") as Number).toInt())
        assertEquals(true, variant["partial_attendance"])
        assertEquals(5 - availableCount, (variant.getValue("excluded_participant_names") as List<*>).size)
        val first =
            document(
                mvc
                    .perform(selection(fixture, option, variant))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val selected = child(first, "selection")
        assertEquals("diverse-time-v1", selected["protocol"])
        assertEquals(fixture.sourceRun.toString(), selected["analysis_id"])
        assertEquals(option["option_id"], selected["option_id"])
        assertEquals(variant["variant_id"], selected["variant_id"])
        assertEquals(child(option, "time_range")["start_at"], selected["start_at"])
        assertEquals(child(option, "time_range")["end_at"], selected["end_at"])
        assertEquals(1, selectionCount(fixture))
        assertEquals(availableCount, attendeeCount(UUID.fromString(variant.getValue("variant_id").toString())))
        assertEquals("CONFIRMED", room(fixture.host)["public_status"])
        assertEquals(first, result(fixture.member), "Result must reload the committed selection")
        val persisted = jdbc.queryForMap("SELECT * FROM recommendation_selections WHERE coordination_run_id = ?", fixture.sourceRun)
        val replay =
            document(
                mvc
                    .perform(selection(fixture, option, variant))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(first, replay)
        assertEquals(
            persisted,
            jdbc.queryForMap("SELECT * FROM recommendation_selections WHERE coordination_run_id = ?", fixture.sourceRun),
        )
        assertEquals(1, selectionCount(fixture))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertTrue(persisted["confirmed_at"] != null)
    }

    private fun variantId(fixture: CompletedCorrectionFixture): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM recommendation_variants WHERE coordination_run_id = ?",
                UUID::class.java,
                fixture.sourceRun,
            ),
        )

    private fun attendeeCount(variant: UUID): Int =
        requireNotNull(
            jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants WHERE variant_id = ?", Int::class.java, variant),
        )

    private fun selectionCount(fixture: CompletedCorrectionFixture): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_selections WHERE coordination_run_id = ?",
                Int::class.java,
                fixture.sourceRun,
            ),
        )

    private fun directSelection(fixture: CompletedCorrectionFixture): Int =
        jdbc.update(
            "INSERT INTO recommendation_selections(coordination_run_id,room_id,option_id,variant_id,start_at,end_at,confirmed_at) " +
                "SELECT o.coordination_run_id,o.room_id,o.id,v.id,o.start_at,o.end_at,now() " +
                "FROM recommendation_options o JOIN recommendation_variants v ON v.option_id = o.id WHERE o.coordination_run_id = ?",
            fixture.sourceRun,
        )

    private fun fiveMemberRoom(availableCount: Int): CompletedCorrectionFixture {
        val displayNames = listOf("가온", "나래", "민수", "다온", "라온")
        val hostCookie = accounts.newAccount(displayNames.first())
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(hostCookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic partial recommendation","meeting_mode":"REMOTE","host_display_name":"가온","manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        val actors = mutableListOf(CorrectionSession(code, hostCookie.value))
        for (name in displayNames.drop(1)) {
            val cookie = accounts.newAccount(name)
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", code)
                        .cookie(cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("display_name" to name))),
                ).andExpect(status().isCreated)
            actors += CorrectionSession(code, cookie.value)
        }
        actors.forEachIndexed { index, actor -> mvc.perform(save(actor, "synthetic private input $index")).andExpect(status().isOk) }
        mvc
            .perform(
                post("/api/rooms/{code}/close", code)
                    .cookie(actors.first().cookie())
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        val runId = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id=?", UUID::class.java, roomId))
        jdbc.update("UPDATE coordination_runs SET status='MATCHING',candidate_quality=NULL,version=version+1 WHERE id=?", runId)
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(runId)))
        val available =
            listOf(
                StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    LocalDate.of(2026, 10, 7),
                    null,
                    LocalTime.of(18, 0),
                    LocalTime.of(21, 0),
                ),
            )
        val unavailable =
            (0L..4L).map { day ->
                StructuredCondition.TimeWindow(
                    TimePolarity.UNAVAILABLE,
                    LocalDate.of(2026, 10, 7).plusDays(day),
                    null,
                    LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT,
                    endsAtNextDayStart = true,
                )
            }
        partialStructured.replaceForBatch(
            run.batch.id,
            run.batch.submissionVersionIds.map { version ->
                val name =
                    requireNotNull(
                        jdbc.queryForObject(
                            "SELECT p.display_name FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id JOIN participants p ON p.id=h.participant_id WHERE v.id=?",
                            String::class.java,
                            version.value,
                        ),
                    )
                StructuredSubmissionResult(version, if (name in displayNames.take(availableCount)) available else unavailable, null)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        process(runId)
        return CompletedCorrectionFixture(actors.first(), actors[1], roomId, runId)
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
