package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationHttpPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `five actual windows publish three primary options and every alternative exactly once`() {
        val fixture = recommendedRoom()
        val first = recommendations(fixture.host)
        assertEquals("diverse-time-v1", first["protocol"])
        assertEquals(fixture.sourceRun.toString(), first["analysis_id"])
        assertEquals(5, (first.getValue("total_options") as Number).toInt())
        assertEquals(true, first["has_alternatives"])
        val primary = items(first, "options")
        assertEquals(3, primary.size)
        assertEquals(listOf(1, 2, 3), primary.map { (it.getValue("rank") as Number).toInt() })
        assertEquals(first, recommendations(fixture.member))
        val all = primary.toMutableList()
        var cursor: String? = null
        var pages = 0
        do {
            val page =
                document(
                    mvc
                        .perform(alternatives(fixture, cursor))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response,
                )
            assertEquals(fixture.sourceRun.toString(), page["analysis_id"])
            val options = items(page, "options")
            assertEquals(1, options.size)
            assertNull(options.single()["rank"])
            all += options
            cursor = page["next_cursor"] as String?
            pages++
            assertTrue(pages <= 2, "Two remaining windows must terminate without repetition")
        } while (cursor != null)
        assertEquals(2, pages)
        assertEquals(5, all.map { it.getValue("option_id") }.distinct().size)
        assertEquals(
            (7..11).map { "2026-10-${it.toString().padStart(2, '0')}T09:00:00Z" }.toSet(),
            all.map { child(it, "time_range").getValue("start_at") }.toSet(),
        )
        for (option in all) {
            val range = child(option, "time_range")
            assertEquals(
                10800L,
                Instant.parse(range["end_at"].toString()).epochSecond - Instant.parse(range["start_at"].toString()).epochSecond,
            )
            val variants = items(option, "variants")
            assertEquals(setOf("IN_PERSON", "REMOTE"), variants.map { it["meeting_mode"] }.toSet())
            assertEquals(2, variants.map { it.getValue("variant_id") }.distinct().size)
            variants.forEach {
                assertEquals(2, (it.getValue("attendance_count") as Number).toInt())
                assertEquals(2, (it.getValue("total_participants") as Number).toInt())
                assertEquals(false, it["partial_attendance"])
                if (it["meeting_mode"] == "REMOTE") {
                    assertNull(it["place"])
                } else {
                    assertEquals("합성 공통 지역", child(it, "place")["display_name"])
                }
                assertFalse(it.containsKey("participant_ids"))
                assertFalse(it.containsKey("raw_text"))
                assertFalse(it.containsKey("conditions"))
            }
        }
        assertEquals(first, recommendations(fixture.host), "Read-only paging must preserve published IDs and state version")
    }

    @Test
    fun `one actual window stays one option with no artificial duplicates`() {
        val fixture = recommendedRoom(days = 1)
        val view = recommendations(fixture.host)
        assertEquals(1, items(view, "options").size)
        assertEquals(1, (view.getValue("total_options") as Number).toInt())
        assertEquals(false, view["has_alternatives"])
        val page =
            document(
                mvc
                    .perform(alternatives(fixture))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(emptyList(), items(page, "options"))
        assertNull(page["next_cursor"])
    }

    @Test
    fun `zero two three and four windows preserve actual count and at most three primary cards`() {
        for (count in listOf(0, 2, 3, 4)) {
            val fixture = recommendedRoom(days = count)
            val view = recommendations(fixture.host)
            assertEquals(count, (view.getValue("total_options") as Number).toInt())
            assertEquals(minOf(count, 3), items(view, "options").size)
            assertEquals(count > 3, view["has_alternatives"])
            if (count == 0) assertEquals("NO_MATCH", room(fixture.host)["public_status"])
        }
    }

    @Test
    fun `historical legacy analysis retains candidate confirmation and selection null`() {
        val fixture = completedRoom()
        val candidate = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) " +
                "VALUES (?, ?, 1, 'PLAN_B', 'REMOTE', 2, 2)",
            candidate,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id, range_order, start_at, end_at) " +
                "VALUES (?, 0, '2026-10-07T09:00:00Z', '2026-10-07T12:00:00Z')",
            candidate,
        )
        jdbc.update(
            "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id) " +
                "SELECT ?, id, ?, room_id FROM participants WHERE room_id = ?",
            candidate,
            fixture.sourceRun,
            fixture.roomId,
        )
        assertNull(recommendations(fixture.host)["protocol"])
        val request =
            post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, candidate)
                .header("Origin", ORIGIN)
                .cookie(fixture.host.cookie())
        val confirmed =
            document(
                mvc
                    .perform(request)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(candidate.toString(), child(confirmed, "candidate")["candidate_id"])
        assertNull(confirmed["selection"])
        assertEquals(confirmed, result(fixture.member))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals("CONFIRMED", room(fixture.host)["public_status"])
    }

    @Test
    fun `typed confirmation preserves chosen twenty minutes and protects the whole immutable tuple`() {
        val fixture = recommendedRoom(partial = true)
        val before = recommendations(fixture.host)
        val option = items(before, "options").first()
        val range = child(option, "time_range")
        val start = Instant.parse(range["start_at"].toString()).plusSeconds(600).toString()
        val end = Instant.parse(start).plusSeconds(1200).toString()
        val first =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val selected = child(first, "selection")
        assertEquals("diverse-time-v1", selected["protocol"])
        assertEquals(fixture.sourceRun.toString(), selected["analysis_id"])
        assertEquals(option["option_id"], selected["option_id"])
        assertEquals(items(option, "variants").first()["variant_id"], selected["variant_id"])
        assertEquals(start, selected["start_at"])
        assertEquals(end, selected["end_at"])
        val candidate = child(first, "candidate")
        assertNull(candidate["candidate_id"], "A new option UUID must never impersonate a legacy candidate FK")
        assertEquals(listOf(mapOf("start_at" to start, "end_at" to end)), items(candidate, "time_ranges"))
        assertEquals(first, result(fixture.member), "Result reads must reload the persisted selection")
        assertEquals("CONFIRMED", room(fixture.host)["public_status"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        val replay =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(first, replay)
        mvc
            .perform(
                selection(fixture, option, start = start, end = Instant.parse(end).plusSeconds(60).toString()),
            ).andExpect(status().isConflict)
        mvc.perform(selection(fixture, option, items(option, "variants").last(), start = start, end = end)).andExpect(status().isConflict)
        mvc.perform(selection(fixture, items(before, "options").last())).andExpect(status().isConflict)
        mvc.perform(reopen(fixture)).andExpect(status().isConflict)
        assertEquals(first, result(fixture.host))
        assertEquals(range, child(items(recommendations(fixture.host), "options").first(), "time_range"))
    }

    @Test
    fun `invalid actual intervals foreign variants and stale analyses cannot alter database state`() {
        val fixture = recommendedRoom()
        val options = items(recommendations(fixture.host), "options")
        val option = options.first()
        val range = child(option, "time_range")
        val start = range["start_at"].toString()
        val end = range["end_at"].toString()
        val before = room(fixture.host)
        val work = workCounts()
        for ((invalidStart, invalidEnd) in listOf(
            start to start,
            end to start,
            Instant.parse(start).minusSeconds(1).toString() to end,
            start to Instant.parse(end).plusSeconds(1).toString(),
        )) {
            mvc.perform(selection(fixture, option, start = invalidStart, end = invalidEnd)).andExpect(status().isBadRequest)
        }
        mvc.perform(selection(fixture, option, items(options.last(), "variants").first())).andExpect(status().isBadRequest)
        mvc.perform(selection(fixture, option, analysis = UUID.randomUUID())).andExpect(status().isConflict)
        assertEquals(before, room(fixture.host))
        assertEquals(work, workCounts())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        mvc.perform(get("/api/rooms/{code}/result", fixture.host.code).cookie(fixture.host.cookie())).andExpect(status().isNotFound)
    }

    @Test
    fun `submicrosecond selection is rejected and cannot replay a different tuple as identical`() {
        val fixture = recommendedRoom()
        val option = items(recommendations(fixture.host), "options").first()
        val window = child(option, "time_range")
        val start =
            Instant
                .parse(window["start_at"].toString())
                .plusSeconds(60)
                .plusNanos(1000)
                .toString()
        val end = Instant.parse(start).plusSeconds(1200).toString()
        val before = room(fixture.host)
        val counts = workCounts()
        val tooPreciseStart = Instant.parse(start).plusNanos(500).toString()
        val tooPreciseEnd = Instant.parse(end).plusNanos(500).toString()
        mvc.perform(selection(fixture, option, start = tooPreciseStart, end = end)).andExpect(status().isBadRequest)
        mvc.perform(selection(fixture, option, start = start, end = tooPreciseEnd)).andExpect(status().isBadRequest)
        assertEquals(before, room(fixture.host))
        assertEquals(counts, workCounts())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        val confirmed =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(start, child(confirmed, "selection")["start_at"])
        assertEquals(end, child(confirmed, "selection")["end_at"])
        assertEquals(confirmed, result(fixture.host))
        mvc.perform(selection(fixture, option, start = tooPreciseStart, end = end)).andExpect(status().isBadRequest)
        mvc.perform(selection(fixture, option, start = start, end = tooPreciseEnd)).andExpect(status().isBadRequest)
        assertEquals(confirmed, result(fixture.member))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
    }

    @Test
    fun `typed request fields are all mandatory and invalid requests cannot confirm`() {
        val fixture = recommendedRoom()
        val option = items(recommendations(fixture.host), "options").first()
        val complete =
            linkedMapOf<String, Any?>(
                "analysis_id" to fixture.sourceRun,
                "variant_id" to items(option, "variants").first()["variant_id"],
                "start_at" to child(option, "time_range")["start_at"],
                "end_at" to child(option, "time_range")["end_at"],
            )
        val before = room(fixture.host)
        for (field in complete.keys) {
            for (body in listOf(complete - field, complete + (field to null))) {
                mvc
                    .perform(
                        post("/api/rooms/{code}/recommendations/{option}/confirmation", fixture.host.code, option["option_id"])
                            .header("Origin", ORIGIN)
                            .cookie(fixture.host.cookie())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)),
                    ).andExpect(status().isBadRequest)
            }
        }
        assertEquals(before, room(fixture.host))
    }

    @Test
    fun `new protocol blocks candidate only confirmation`() {
        val fixture = recommendedRoom()
        val candidates =
            document(
                mvc
                    .perform(get("/api/rooms/{code}/candidates", fixture.host.code).cookie(fixture.host.cookie()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val id = items(candidates, "candidates").first().getValue("candidate_id")
        mvc
            .perform(
                post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, id)
                    .header("Origin", ORIGIN)
                    .cookie(fixture.host.cookie()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOMMENDATION_SELECTION_REQUIRED"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
    }

    @Test
    fun `only host may confirm while participant and guest read permissions stay scoped`() {
        val fixture = recommendedRoom()
        val option = items(recommendations(fixture.host), "options").first()
        mvc.perform(selection(fixture, option, actor = fixture.member)).andExpect(status().isForbidden)
        mvc.perform(get("/api/rooms/{code}/recommendations", fixture.host.code)).andExpect(status().isUnauthorized)
        mvc
            .perform(
                get("/api/rooms/{code}/recommendations/alternatives", fixture.host.code)
                    .param("analysis_id", fixture.sourceRun.toString()),
            ).andExpect(status().isUnauthorized)
        val foreign = completedRoom()
        mvc
            .perform(
                get("/api/rooms/{code}/recommendations", fixture.host.code).cookie(foreign.host.cookie()),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                selection(fixture, option, actor = CorrectionSession(fixture.host.code, foreign.host.credential)),
            ).andExpect(status().isForbidden)
        assertEquals("READY", room(fixture.host)["public_status"])
    }

    @Test
    fun `open correction and replacement analysis protect frozen versions and reject old option selection`() {
        val fixture = recommendedRoom(partial = true)
        val oldOption = items(recommendations(fixture.host), "options").first()
        val versions = frozenVersions(fixture.sourceRun)
        val frozenOriginalSql =
            "SELECT v.id, v.raw_text, v.created_at FROM coordination_runs r " +
                "JOIN submission_batch_items i ON i.batch_id = r.batch_id " +
                "JOIN submission_versions v ON v.id = i.submission_version_id WHERE r.id = ? ORDER BY v.id"
        val frozenOriginals = jdbc.queryForList(frozenOriginalSql, fixture.sourceRun)
        val member = owner(fixture.member)
        val round = openRound(fixture)
        mvc
            .perform(
                get("/api/rooms/{code}/recommendations", fixture.host.code).cookie(fixture.host.cookie()),
            ).andExpect(status().isConflict)
        mvc.perform(alternatives(fixture)).andExpect(status().isConflict)
        mvc.perform(selection(fixture, oldOption)).andExpect(status().isConflict)
        mvc.perform(save(fixture.host, "H1 corrected", round, 1)).andExpect(status().isOk)
        mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
        val next = activeRun(fixture.host)
        publish(next)
        assertEquals(versions, frozenVersions(fixture.sourceRun))
        assertEquals(frozenOriginals, jdbc.queryForList(frozenOriginalSql, fixture.sourceRun))
        val memberAfter = owner(fixture.member)
        assertEquals(member - "state_version", memberAfter - "state_version")
        val beforeState = (member.getValue("state_version") as Number).toLong()
        val afterState = (memberAfter.getValue("state_version") as Number).toLong()
        assertTrue(afterState > beforeState, "Correction transitions must publish a newer room snapshot")
        assertEquals((room(fixture.host).getValue("state_version") as Number).toLong(), afterState)
        assertEquals("H1 corrected", owner(fixture.host)["raw_text"])
        assertEquals(next.toString(), recommendations(fixture.host)["analysis_id"])
        mvc.perform(selection(fixture, oldOption)).andExpect(status().isConflict)
        mvc.perform(alternatives(fixture)).andExpect(status().isConflict)
        assertEquals(next, activeRun(fixture.host))
    }

    @Test
    fun `alternative query requires active analysis and validates limit and opaque cursor`() {
        val fixture = recommendedRoom()
        mvc
            .perform(
                get("/api/rooms/{code}/recommendations/alternatives", fixture.host.code).cookie(fixture.host.cookie()),
            ).andExpect(status().isBadRequest)
        for (limit in listOf(0, -1, 101)) mvc.perform(alternatives(fixture, limit = limit)).andExpect(status().isBadRequest)
        mvc.perform(alternatives(fixture, cursor = "invalid-cursor")).andExpect(status().isBadRequest)
        mvc.perform(alternatives(fixture, analysis = UUID.randomUUID())).andExpect(status().isConflict)
        val page =
            document(
                mvc
                    .perform(alternatives(fixture))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val other = recommendedRoom()
        mvc.perform(alternatives(other, cursor = page["next_cursor"].toString())).andExpect(status().isBadRequest)
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
