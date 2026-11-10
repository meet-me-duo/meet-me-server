package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real public HTTP, frozen input cohort, new calculation and PG; synthetic accounts only, no provider requests. */
class RecommendationPartialAttendanceHttpPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var partialStructured: StructuredSubmissionRepository

    @Autowired private lateinit var partialRuns: CoordinationRunRepository

    @Autowired private lateinit var statusSubmissions: SubmissionRepository

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
        assertEquals(listOf("가온", "나래").sorted(), included(variant).map { it.toString() }.sorted())
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
        assertEquals(listOf("가온", "나래", "민수", "다온", "라온").sorted(), included(variant).map { it.toString() }.sorted())
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
        assertTrue("늦은 참여자" !in included(variant))
        assertEquals(before, after, "Published recommendations belong to their frozen batch rather than live room membership")
    }

    @Test
    fun `PN04 distinct participants with identical names retain both entries by identity`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        jdbc.update("UPDATE participants SET display_name='동명' WHERE room_id=?", fixture.roomId)
        val variant = items(items(recommendations(fixture.host), "options").single(), "variants").single()
        assertEquals(listOf("동명", "동명"), included(variant))
        assertEquals(listOf("동명", "동명", "동명"), names(variant))
        assertEquals(recommendations(fixture.host), recommendations(fixture.member))
    }

    @Test
    fun `PN05 names are returned on every variant and alternative page for a verified guest`() {
        val fixture = completedRoom(memberAccount = false)
        jdbc.update("UPDATE meeting_rooms SET meeting_mode='EITHER' WHERE id=?", fixture.roomId)
        publish(fixture.sourceRun)
        val primary = recommendations(fixture.host)
        assertEquals(primary, recommendations(fixture.member))
        val options = items(primary, "options").toMutableList()
        var cursor: String? = null
        do {
            val page =
                document(
                    mvc
                        .perform(alternatives(fixture, cursor, actor = fixture.member))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response,
                )
            options += items(page, "options")
            cursor = page["next_cursor"] as String?
        } while (cursor != null)
        assertEquals(5, options.size)
        var references: Map<Any?, Any?>? = null
        for (option in options) {
            assertEquals(setOf("REMOTE", "IN_PERSON"), items(option, "variants").map { it["meeting_mode"] }.toSet())
            for (variant in items(option, "variants")) {
                assertEquals(listOf("host", "member"), included(variant).map { it.toString() }.sorted())
                assertEquals(emptyList<String>(), names(variant))
                val currentReferences = items(variant, "participant_statuses").associate { it["display_name"] to it["participant_ref"] }
                assertEquals(2, currentReferences.size)
                if (references == null) references = currentReferences else assertEquals(references, currentReferences)
            }
        }
        mvc.perform(selection(fixture, options.first(), actor = fixture.member)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/rooms/{code}/recommendations", fixture.host.code)).andExpect(status().isUnauthorized)
        val outsider = accounts.newAccount("외부인")
        mvc.perform(get("/api/rooms/{code}/recommendations", fixture.host.code).cookie(outsider)).andExpect(status().isForbidden)
        mvc.perform(alternatives(fixture, actor = CorrectionSession(fixture.host.code, outsider.value))).andExpect(status().isForbidden)
    }

    @Test
    fun `PN06 conditional attendance names identify time matches without claiming place verification`() {
        val fixture = recommendedRoom(partial = true)
        val view = recommendations(fixture.host, attendanceSupport = "conditional-v1")
        for (option in items(view, "options")) {
            for (variant in items(option, "variants")) {
                assertEquals((variant.getValue("attendance_count") as Number).toInt(), included(variant).size)
                assertEquals(2, included(variant).size + names(variant).size)
                if (variant["attendance_confirmed"] == false) {
                    assertTrue(variant.getValue("summary").toString().contains("미확정"))
                }
            }
        }
        assertTrue(items(view, "options").flatMap { items(it, "variants") }.any { it["attendance_confirmed"] == false })
    }

    @Test
    fun `PS01 each frozen person has one scoped public reference and a justified status`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val view = recommendations(fixture.host)
        val variant = items(items(view, "options").single(), "variants").single()
        val rows = items(variant, "participant_statuses")
        assertEquals(5, rows.size)
        assertEquals(5, rows.map { it["participant_ref"] }.distinct().size)
        assertEquals(setOf("가온", "나래", "민수", "다온", "라온"), rows.map { it["display_name"] }.toSet())
        for (row in rows) {
            assertEquals(setOf("participant_ref", "display_name", "status"), row.keys)
            assertTrue(row.getValue("participant_ref").toString().matches(Regex("p[1-9][0-9]*")))
            assertEquals(if (row["display_name"] in setOf("가온", "나래")) "AVAILABLE" else "UNAVAILABLE", row["status"])
        }
        assertEquals(view, recommendations(fixture.member))
        assertEquals(view, recommendations(fixture.host))
        assertFalse(rows.toString().contains("synthetic private input"))
        assertFalse(rows.toString().contains("미제출 참여자"))
    }

    @Test
    fun `PS02 duplicate display names keep distinct stable public references`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        jdbc.update("UPDATE participants SET display_name='동명' WHERE room_id=?", fixture.roomId)
        val view = recommendations(fixture.host)
        val rows = items(items(items(view, "options").single(), "variants").single(), "participant_statuses")
        assertEquals(List(5) { "동명" }, rows.map { it["display_name"] })
        assertEquals(5, rows.map { it["participant_ref"] }.distinct().size)
        assertEquals(2, rows.count { it["status"] == "AVAILABLE" })
        assertEquals(3, rows.count { it["status"] == "UNAVAILABLE" })
        assertEquals(view, recommendations(fixture.member))
    }

    @Test
    fun `PS03 rejected and missing interpretation is unknown without publishing reasons or changing saved membership`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val before = recommendations(fixture.host)
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val hostVersion = versionForName(fixture, "가온")
        val missingVersion = versionForName(fixture, "민수")
        partialStructured.replaceForBatch(
            run.batch.id,
            partialStructured.findByBatch(run.batch.id).filter { it.submissionVersionId != missingVersion }.map {
                if (it.submissionVersionId == hostVersion) it.copy(rejectionCode = "AMBIGUOUS_INPUT") else it
            },
            Instant.parse("2026-10-07T05:10:00Z"),
        )
        val saved = partialStructured.findByBatch(run.batch.id)
        val after = recommendations(fixture.host)
        val variant = items(items(after, "options").single(), "variants").single()
        val byName = items(variant, "participant_statuses").associateBy { it["display_name"] }
        assertEquals("UNKNOWN", byName.getValue("가온")["status"])
        assertEquals("UNKNOWN", byName.getValue("민수")["status"])
        assertEquals("AVAILABLE", byName.getValue("나래")["status"])
        assertEquals("UNAVAILABLE", byName.getValue("다온")["status"])
        val previous = items(items(before, "options").single(), "variants").single()
        assertEquals(included(previous), included(variant))
        assertEquals(names(previous), names(variant))
        assertFalse(after.toString().contains("AMBIGUOUS_INPUT"))
        assertFalse(after.toString().contains("STRUCTURED_RESULT_MISSING"))
        assertEquals(saved, partialStructured.findByBatch(run.batch.id), "GET must not persist restored diagnostics")
        assertEquals(run, partialRuns.findById(CoordinationRunId(fixture.sourceRun)), "GET must not run the matching state machine")
    }

    @Test
    fun `PS04 status uses the full recommendation window even after a shorter actual selection`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val changed = versionForName(fixture, "민수")
        partialStructured.replaceForBatch(
            run.batch.id,
            partialStructured.findByBatch(run.batch.id).map {
                if (it.submissionVersionId == changed) {
                    it.copy(
                        conditions =
                            listOf(
                                StructuredCondition.TimeWindow(
                                    TimePolarity.AVAILABLE,
                                    LocalDate.of(2026, 10, 7),
                                    null,
                                    LocalTime.of(19, 0),
                                    LocalTime.of(20, 0),
                                ),
                            ),
                    )
                } else {
                    it
                }
            },
            Instant.parse("2026-10-07T05:10:00Z"),
        )
        val before = recommendations(fixture.host)
        val option = items(before, "options").single()
        val previousRows = items(items(option, "variants").single(), "participant_statuses")
        mvc
            .perform(selection(fixture, option, start = "2026-10-07T10:00:00Z", end = "2026-10-07T11:00:00Z"))
            .andExpect(status().isOk)
        val after = recommendations(fixture.host)
        assertEquals((before.getValue("state_version") as Number).toLong() + 1, (after.getValue("state_version") as Number).toLong())
        assertEquals(before.filterKeys { it != "state_version" }, after.filterKeys { it != "state_version" })
        val afterOption = items(after, "options").single()
        assertEquals(option["time_range"], afterOption["time_range"])
        val rows = items(items(afterOption, "variants").single(), "participant_statuses")
        assertEquals(previousRows, rows, "The chosen shorter tuple cannot reinterpret full-window statuses")
        assertEquals("UNAVAILABLE", rows.single { it["display_name"] == "민수" }["status"])
    }

    @Test
    fun `PS05 historical frozen versions remain the status source after the current submission head changes`() {
        val fixture = fiveMemberRoom(availableCount = 2)
        val before = recommendations(fixture.host)
        assertEquals(5, items(items(items(before, "options").single(), "variants").single(), "participant_statuses").size)
        val owner =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM participants WHERE room_id=? AND display_name=?",
                    UUID::class.java,
                    fixture.roomId,
                    "가온",
                ),
            )
        val original = requireNotNull(statusSubmissions.findByParticipant(ParticipantId(owner)))
        statusSubmissions.save(
            original.revise(
                SubmissionVersionId(UUID.randomUUID()),
                "synthetic later private version",
                emptyList(),
                Locale.KOREAN,
                Instant.parse("2026-10-07T05:10:00Z"),
            ),
        )
        val after = recommendations(fixture.host)
        assertEquals(before, after)
        assertFalse(after.toString().contains("synthetic later private version"))
    }

    @Test
    fun `PS06 only the individually unverified attendee is unknown in a conditional variant`() {
        val fixture =
            fiveMemberRoom(5, "IN_PERSON") { name, conditions ->
                if (name == "가온") conditions + StructuredCondition.TravelConstraint("synthetic unverified travel") else conditions
            }
        val view = recommendations(fixture.host, attendanceSupport = "conditional-v1")
        val variant = items(items(view, "options").single(), "variants").single()
        assertEquals(false, variant["attendance_confirmed"])
        val rows = items(variant, "participant_statuses")
        assertEquals("UNKNOWN", rows.single { it["display_name"] == "가온" }["status"])
        assertEquals(4, rows.count { it["status"] == "AVAILABLE" })
        assertEquals(5, included(variant).size)
    }

    @Test
    fun `PS07 each variant checks its own known place rather than treating all excluded people as time mismatches`() {
        val fixture =
            fiveMemberRoom(5, "IN_PERSON") { name, conditions ->
                val key = if (name in setOf("가온", "나래")) "AREA_1" else "AREA_2"
                conditions + StructuredCondition.SpecificPlace("synthetic $key", areaKey = key, areaName = "synthetic $key")
            }
        val options = items(recommendations(fixture.host), "options")
        val variants = options.flatMap { items(it, "variants") }
        assertEquals(2, variants.size)
        val references = mutableMapOf<String, Any?>()
        for (variant in variants) {
            val firstArea = child(variant, "place")["display_name"] == "synthetic AREA_1"
            for (row in items(variant, "participant_statuses")) {
                val name = row.getValue("display_name").toString()
                val belongs = (name in setOf("가온", "나래")) == firstArea
                assertEquals(if (belongs) "AVAILABLE" else "UNAVAILABLE", row["status"])
                val previous = references.putIfAbsent(name, row["participant_ref"])
                if (previous != null) assertEquals(previous, row["participant_ref"])
            }
        }
        assertEquals(5, references.size)
    }

    @Test
    fun `PS08 conditional fallback is resolved for the whole frozen batch and missing peer evidence cannot activate a subset fallback`() {
        val fixture =
            fiveMemberRoom(5, "IN_PERSON") { name, _ ->
                fun available(
                    start: Int,
                    end: Int,
                ) = StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    LocalDate.of(2026, 10, 7),
                    null,
                    LocalTime.of(start, 0),
                    LocalTime.of(end, 0),
                )
                val time =
                    when (name) {
                        "가온" ->
                            listOf(
                                available(18, 19),
                                StructuredCondition.ConditionalFallbackTimeWindow(
                                    ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
                                    LocalDate.of(2026, 10, 7),
                                    null,
                                    LocalTime.of(20, 0),
                                    LocalTime.of(21, 0),
                                ),
                            )
                        "나래" -> listOf(available(18, 19), available(20, 21))
                        else -> listOf(available(20, 21))
                    }
                val key = if (name in setOf("가온", "나래")) "AREA_1" else "AREA_2"
                time + StructuredCondition.SpecificPlace("synthetic $key", areaKey = key, areaName = "synthetic $key")
            }

        fun target(view: Map<String, Any?>): Map<String, Any?> =
            items(items(view, "options").single { child(it, "time_range")["start_at"] == "2026-10-07T11:00:00Z" }, "variants")
                .single { child(it, "place")["display_name"] == "synthetic AREA_1" }
        val before = target(recommendations(fixture.host))
        assertEquals("AVAILABLE", items(before, "participant_statuses").single { it["display_name"] == "가온" }["status"])
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val missing = versionForName(fixture, "민수")
        partialStructured.replaceForBatch(
            run.batch.id,
            partialStructured.findByBatch(run.batch.id).filter {
                it.submissionVersionId !=
                    missing
            },
            Instant.parse("2026-10-07T05:10:00Z"),
        )
        val persisted = partialStructured.findByBatch(run.batch.id)
        val after = target(recommendations(fixture.host))
        assertEquals("UNKNOWN", items(after, "participant_statuses").single { it["display_name"] == "가온" }["status"])
        assertEquals(included(before), included(after))
        assertEquals(
            persisted,
            partialStructured.findByBatch(run.batch.id),
            "Read-only fallback resolution must not save missing or unsupported diagnostics",
        )
    }

    @Test
    fun `PS09 missing peer evidence cannot silently reuse reconstructed legacy area identities`() {
        val fixture =
            fiveMemberRoom(5, "IN_PERSON") { _, conditions ->
                conditions + StructuredCondition.SpecificPlace("synthetic legacy area")
            }
        val before = recommendations(fixture.host)
        val previous = items(items(before, "options").single(), "variants").single()
        assertEquals(5, items(previous, "participant_statuses").count { it["status"] == "AVAILABLE" })
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val missing = versionForName(fixture, "가온")
        partialStructured.replaceForBatch(
            run.batch.id,
            partialStructured.findByBatch(run.batch.id).filter { it.submissionVersionId != missing },
            Instant.parse("2026-10-07T05:10:00Z"),
        )
        val after = items(items(recommendations(fixture.host), "options").single(), "variants").single()
        assertEquals(5, items(after, "participant_statuses").count { it["status"] == "UNKNOWN" })
        assertEquals(included(previous), included(after))
        assertEquals(previous["place"], after["place"])
    }

    private fun versionForName(
        fixture: CompletedCorrectionFixture,
        name: String,
    ): SubmissionVersionId =
        SubmissionVersionId(
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT v.id FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id " +
                        "JOIN participants p ON p.id=h.participant_id WHERE p.room_id=? AND p.display_name=? AND v.id=h.latest_version_id",
                    UUID::class.java,
                    fixture.roomId,
                    name,
                ),
            ),
        )

    private fun included(variant: Map<String, Any?>): List<*> = variant.getValue("available_participant_names") as List<*>

    private fun names(variant: Map<String, Any?>): List<*> = variant.getValue("excluded_participant_names") as List<*>

    private fun fiveMemberRoom(
        availableCount: Int,
        mode: String = "REMOTE",
        transform: (String, List<StructuredCondition>) -> List<StructuredCondition> = { _, conditions -> conditions },
    ): CompletedCorrectionFixture {
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
                            """{"purpose":"synthetic partial recommendation","meeting_mode":"$mode","host_display_name":"가온","manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
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
        val unsubmitted = accounts.newAccount("미제출 참여자")
        mvc
            .perform(
                post("/api/rooms/{code}/participants", code)
                    .cookie(unsubmitted)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"미제출 참여자"}"""),
            ).andExpect(status().isCreated)
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
                StructuredSubmissionResult(
                    version,
                    transform(
                        name,
                        if (name in
                            displayNames.take(availableCount)
                        ) {
                            available
                        } else {
                            unavailable
                        },
                    ),
                    null,
                )
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
