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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real public HTTP, frozen input cohort, new calculation and PG; synthetic accounts only, no provider requests. */
class RecommendationPartialAttendanceHttpPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var partialStructured: StructuredSubmissionRepository

    @Autowired private lateinit var partialRuns: CoordinationRunRepository

    @Test
    fun `PN01 two of five actual attendees expose exactly three excluded display names without raw input or IDs`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val view = recommendations(fixture.host)
        assertEquals("diverse-time-v1", view["protocol"])
        assertEquals(1, (view.getValue("total_options") as Number).toInt())
        val option = items(view, "options").single()
        assertEquals("2026-10-07T09:00:00Z", child(option, "time_range")["start_at"])
        assertEquals("2026-10-07T12:00:00Z", child(option, "time_range")["end_at"])
        val variant = items(option, "variants").single()
        assertEquals("REMOTE", variant["meeting_mode"])
        assertEquals(2, (variant.getValue("attendance_count") as Number).toInt())
        assertEquals(5, (variant.getValue("total_participants") as Number).toInt())
        assertEquals(true, variant["partial_attendance"])
        val excluded = names(variant)
        assertEquals(setOf("민수", "다온", "라온"), excluded.toSet())
        assertEquals(3, excluded.size)
        assertFalse(variant.containsKey("participant_ids"))
        assertFalse(variant.containsKey("raw_text"))
        assertFalse(variant.containsKey("conditions"))
        assertFalse(view.toString().contains("synthetic private input"))
        assertEquals(view, recommendations(fixture.host), "A read must retain published variant IDs and the same excluded cohort")
    }

    @Test
    fun `PN02 full attendance exposes an empty excluded name list and one maximal remote variant`() {
        val fixture = fiveMemberRoom(availableCount = 5)
        val view = recommendations(fixture.host)
        val variant = items(items(view, "options").single(), "variants").single()
        assertEquals(5, (variant.getValue("attendance_count") as Number).toInt())
        assertEquals(5, (variant.getValue("total_participants") as Number).toInt())
        assertEquals(false, variant["partial_attendance"])
        assertEquals(emptyList<String>(), names(variant))
    }

    @Test
    fun `PN03 later room membership never enlarges the frozen recommendation excluded cohort`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val before = recommendations(fixture.host)
        val lateAccount = accounts.newAccount("늦은 참여자")
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'MEMBER',now(),?)",
            UUID.randomUUID(),
            fixture.roomId,
            UUID.fromString(lateAccount.value.removePrefix("test-account-")),
            "늦은 참여자",
        )
        val after = recommendations(fixture.host)
        val variant = items(items(after, "options").single(), "variants").single()
        assertEquals(5, (variant.getValue("total_participants") as Number).toInt())
        assertEquals(setOf("민수", "다온", "라온"), names(variant).toSet())
        assertTrue("늦은 참여자" !in names(variant))
        assertEquals(before, after, "Published recommendations belong to their frozen batch rather than live room membership")
    }

    private fun names(variant: Map<String, Any?>): List<*> = variant.getValue("excluded_participant_names") as List<*>

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
