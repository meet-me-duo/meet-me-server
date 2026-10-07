package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Issue #92 agreed correction-round API, immutable snapshots, ownership, idempotency, and quota.
class CorrectionRoundHttpPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `reopen preserves CLOSED anchor and source cohort while publishing editable correction state`() {
        val fixture = completedRoom()
        val anchor = closedAt(fixture)
        val counts = workCounts()
        val original = owner(fixture.host)
        val roundId = openRound(fixture)

        val view = room(fixture.host)
        assertEquals("CLOSED", view["collection_status"])
        assertEquals("COLLECTING", view["public_status"])
        assertEquals(fixture.sourceRun.toString(), view["analysis_id"])
        assertEquals(1L, (view.getValue("revision_generation") as Number).toLong())
        assertEquals(roundId.toString(), child(view, "revision_round")["id"])
        val capabilities = child(view, "capabilities")
        assertEquals(true, capabilities["can_edit_own_submission"])
        assertEquals(true, capabilities["can_analyze_revision"])
        assertEquals(false, capabilities["can_confirm"])
        assertEquals(false, capabilities["can_open_revision"])
        assertEquals(3, (view.getValue("remaining_correction_analyses") as Number).toInt())
        assertEquals("CLOSED", jdbc.queryForObject("SELECT collection_status FROM meeting_rooms", String::class.java))
        assertEquals(anchor, closedAt(fixture))
        assertEquals(counts, workCounts())
        assertEquals(original["raw_text"], owner(fixture.host)["raw_text"])
        assertEquals(original["created_at"], owner(fixture.host)["created_at"])
        assertEquals(true, owner(fixture.member)["editable"])
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM participants", Int::class.java))
    }

    @Test
    fun `correction changes only self input without creating parse work or touching other participant`() {
        val fixture = completedRoom()
        val member = owner(fixture.member)
        val counts = workCounts()
        val roundId = openRound(fixture)

        mvc
            .perform(save(fixture.host, "H1 corrected", roundId, 1))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        assertEquals("H1 corrected", owner(fixture.host)["raw_text"])
        assertEquals(member["raw_text"], owner(fixture.member)["raw_text"])
        assertEquals(member["revision"], owner(fixture.member)["revision"])
        assertEquals(member["created_at"], owner(fixture.member)["created_at"])
        assertEquals(counts, workCounts())
        assertEquals(listOf("H0 original", "M0 original"), snapshotTexts(fixture.sourceRun))
    }

    @Test
    fun `normalized no-op preserves revision version and original reference date`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val original = owner(fixture.host)
        val versions = versionIds()
        val counts = workCounts()

        mvc.perform(save(fixture.host, "\uFEFF \tH0 original\r\n\u3000", roundId, 1)).andExpect(status().isOk)
        assertEquals(original, owner(fixture.host))
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        mvc
            .perform(save(fixture.host, "H0 original", roundId, 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(original, owner(fixture.host))
    }

    @Test
    fun `unchanged analyze reuses source without new batch run outbox or attempt`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val counts = workCounts()
        val requestId = UUID.randomUUID()
        val openState = (room(fixture.host).getValue("state_version") as Number).toLong()

        mvc
            .perform(analyze(fixture, roundId, requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
        assertTrue((room(fixture.host).getValue("state_version") as Number).toLong() > openState)
        assertEquals(counts, workCounts())
        assertEquals(false, owner(fixture.host)["editable"])
        assertEquals(3, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
        mvc
            .perform(analyze(fixture, roundId, requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        assertEquals(counts, workCounts())
    }

    @Test
    fun `changed analyze freezes current cohort and keeps original batch immutable`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        mvc.perform(save(fixture.host, "H1 corrected", roundId, 1)).andExpect(status().isOk)
        val before = workCounts()

        mvc
            .perform(analyze(fixture, roundId))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.outcome").value("QUEUED"))
        val next = activeRun(fixture.host)
        assertNotEquals(fixture.sourceRun, next)
        assertEquals(listOf("H1 corrected", "M0 original"), snapshotTexts(next))
        assertEquals(listOf("H0 original", "M0 original"), snapshotTexts(fixture.sourceRun))
        assertOneNewWork(before)
        assertEquals(false, owner(fixture.host)["editable"])
        assertEquals(2, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
    }

    @Test
    fun `force reparse creates independent work for identical version set and replay cannot duplicate it`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val requestId = UUID.randomUUID()
        val versions = versionIds()
        val before = workCounts()

        mvc
            .perform(analyze(fixture, roundId, requestId, force = true))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.outcome").value("QUEUED"))
        val next = activeRun(fixture.host)
        assertNotEquals(fixture.sourceRun, next)
        assertEquals(snapshotVersions(fixture.sourceRun), snapshotVersions(next))
        assertEquals(versions, versionIds())
        assertOneNewWork(before)
        completePartial(next)
        val after = workCounts()
        mvc
            .perform(analyze(fixture, roundId, requestId, force = true))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.outcome").value("QUEUED"))
        assertEquals(after, workCounts())
        assertEquals(next, activeRun(fixture.host))
    }

    @Test
    fun `late reopen replay returns original round and cannot overwrite newer generation`() {
        val fixture = completedRoom()
        val requestId = UUID.randomUUID()
        val first = openRound(fixture, requestId)
        mvc
            .perform(analyze(fixture, first))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        val second = openRound(fixture, generation = 1)
        val before = workCounts()

        val replay =
            mvc
                .perform(reopen(fixture, requestId))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals(first.toString(), child(document(replay), "round")["id"])
        assertEquals(second.toString(), child(room(fixture.host), "revision_round")["id"])
        assertEquals(2L, (room(fixture.host).getValue("revision_generation") as Number).toLong())
        assertEquals(before, workCounts())
        mvc
            .perform(reopen(fixture, requestId, generation = 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(second.toString(), child(room(fixture.host), "revision_round")["id"])
    }

    @Test
    fun `late analyze replay preserves current open round and changed request payload conflicts`() {
        val fixture = completedRoom()
        val first = openRound(fixture)
        val requestId = UUID.randomUUID()
        mvc
            .perform(analyze(fixture, first, requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        val second = openRound(fixture, generation = 1)
        val counts = workCounts()

        mvc
            .perform(analyze(fixture, first, requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        assertEquals(second.toString(), child(room(fixture.host), "revision_round")["id"])
        assertEquals(counts, workCounts())
        mvc
            .perform(analyze(fixture, first, requestId, force = true))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(counts, workCounts())
    }

    @Test
    fun `stale round and stale revision cannot mutate newer round including no-op text`() {
        val fixture = completedRoom()
        val first = openRound(fixture)
        mvc
            .perform(analyze(fixture, first))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        val second = openRound(fixture, generation = 1)
        val versions = versionIds()

        mvc
            .perform(save(fixture.host, "H0 original", first, 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        mvc
            .perform(save(fixture.host, "H1 corrected", second, 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(versions, versionIds())
        assertEquals("H0 original", owner(fixture.host)["raw_text"])
    }

    @Test
    fun `three committed correction runs exhaust permanent room quota and fourth changes no data`() {
        val fixture = completedRoom()
        val anchor = closedAt(fixture)
        var source = fixture.sourceRun
        repeat(3) { index ->
            val roundId = openRound(fixture, generation = index.toLong(), source = source)
            mvc
                .perform(analyze(fixture, roundId, force = true))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.outcome").value("QUEUED"))
            source = activeRun(fixture.host)
            completePartial(source)
            assertEquals(2 - index, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
        }
        val unchanged = openRound(fixture, generation = 3, source = source)
        val exhaustedCounts = workCounts()
        mvc
            .perform(analyze(fixture, unchanged))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("REUSED"))
        assertEquals(exhaustedCounts, workCounts())
        val roundId = openRound(fixture, generation = 4, source = source)
        mvc.perform(save(fixture.host, "H1 corrected", roundId, 1)).andExpect(status().isOk)
        val before = workCounts()
        val versions = versionIds()

        mvc
            .perform(analyze(fixture, roundId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CORRECTION_ANALYSIS_LIMIT_REACHED"))
        assertEquals(source, activeRun(fixture.host))
        assertEquals(before, workCounts())
        assertEquals(versions, versionIds())
        assertEquals(anchor, closedAt(fixture))
    }

    @Test
    fun `member cannot reopen or analyze but can edit own source-cohort submission`() {
        val fixture = completedRoom()
        mvc.perform(reopen(fixture, actor = fixture.member)).andExpect(status().isForbidden)
        val roundId = openRound(fixture)
        val before = workCounts()
        mvc.perform(analyze(fixture, roundId, actor = fixture.member)).andExpect(status().isForbidden)
        mvc.perform(save(fixture.member, "M1 corrected", roundId, 1)).andExpect(status().isOk)
        assertEquals("H0 original", owner(fixture.host)["raw_text"])
        assertEquals("M1 corrected", owner(fixture.member)["raw_text"])
        assertEquals(before, workCounts())
    }

    @Test
    fun `old close remains no-op during correction and unscoped PUT cannot update CLOSED room`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val counts = workCounts()
        val versions = versionIds()
        mvc
            .perform(
                post("/api/rooms/{code}/close", fixture.host.code)
                    .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                    .cookie(fixture.host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        mvc.perform(save(fixture.host, "H1 unscoped")).andExpect(status().isConflict)
        assertEquals(counts, workCounts())
        assertEquals(versions, versionIds())
        assertEquals(roundId.toString(), child(room(fixture.host), "revision_round")["id"])
    }

    @Test
    fun `active or delayed analysis cannot open a correction round`() {
        val fixture = completedRoom()
        for (state in listOf("QUEUED", "STRUCTURING", "MATCHING", "ANALYSIS_DELAYED")) {
            jdbc.update("UPDATE coordination_runs SET status = ?, candidate_quality = NULL WHERE id = ?", state, fixture.sourceRun)
            val counts = workCounts()
            mvc.perform(reopen(fixture)).andExpect(status().isConflict)
            assertEquals(counts, workCounts())
        }
    }

    @Test
    fun `foreign analysis and foreign round cannot be used as authority in another room`() {
        val first = completedRoom()
        val firstRound = openRound(first)
        val second = completedRoom()
        val counts = workCounts()
        mvc.perform(reopen(second, source = first.sourceRun)).andExpect(status().isConflict)
        val secondRound = openRound(second)
        mvc.perform(save(second.host, "other-room injection", firstRound, 1)).andExpect(status().isConflict)
        mvc.perform(analyze(second, firstRound)).andExpect(status().isConflict)
        assertEquals("H0 original", owner(second.host)["raw_text"])
        assertEquals(secondRound.toString(), child(room(second.host), "revision_round")["id"])
        assertEquals(counts, workCounts())
    }

    private fun snapshotTexts(runId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM coordination_runs r JOIN submission_batch_items i ON i.batch_id = r.batch_id " +
                    "JOIN submission_versions v ON v.id = i.submission_version_id WHERE r.id = ? ORDER BY v.raw_text",
                String::class.java,
                runId,
            ).map { requireNotNull(it) }

    private fun snapshotVersions(runId: UUID): List<UUID> =
        jdbc
            .queryForList(
                "SELECT i.submission_version_id FROM coordination_runs r " +
                    "JOIN submission_batch_items i ON i.batch_id = r.batch_id WHERE r.id = ? ORDER BY i.submission_version_id",
                UUID::class.java,
                runId,
            ).map { requireNotNull(it) }

    private fun assertOneNewWork(before: Map<String, Int>) {
        val after = workCounts()
        for (table in listOf("submission_batches", "coordination_runs", "outbox_events")) {
            assertEquals(before.getValue(table) + 1, after.getValue(table), table)
        }
        assertEquals(before["coordination_attempts"], after["coordination_attempts"])
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
