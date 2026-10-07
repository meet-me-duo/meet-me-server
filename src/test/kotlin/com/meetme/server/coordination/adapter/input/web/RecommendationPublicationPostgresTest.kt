package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.reset
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Independent #95 complete publication transaction, duplicate-worker and inactive-analysis boundaries.
class RecommendationPublicationPostgresTest : RecommendationPostgresFixture() {
    @MockitoSpyBean private lateinit var rooms: MeetingRoomRepository

    @Test
    fun `failure after real room update rolls back legacy candidates recommendation projection and room version`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val runBefore = jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id = ?", fixture.sourceRun)
        val roomBefore = jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id = ?", fixture.roomId)
        val snapshots = stored(fixture.sourceRun)
        val versions = frozenVersions(fixture.sourceRun)
        val reached = AtomicBoolean()
        doAnswer { invocation ->
            invocation.callRealMethod()
            reached.set(true)
            throw IllegalStateException("synthetic publication failure after room write")
        }.`when`(rooms).update(anyValue())
        try {
            assertFailsWith<IllegalStateException> { process(fixture.sourceRun) }
        } finally {
            reset(rooms)
        }
        assertTrue(reached.get(), "A failure before publishing begins is not transaction rollback evidence")
        assertEquals(runBefore, jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id = ?", fixture.sourceRun))
        assertEquals(roomBefore, jdbc.queryForMap("SELECT * FROM meeting_rooms WHERE id = ?", fixture.roomId))
        assertEquals(snapshots, stored(fixture.sourceRun))
        assertEquals(versions, frozenVersions(fixture.sourceRun))
        process(fixture.sourceRun)
        assertEquals(
            "COMPLETED",
            jdbc.queryForObject("SELECT status FROM coordination_runs WHERE id = ?", String::class.java, fixture.sourceRun),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_analyses WHERE coordination_run_id = ?",
                Int::class.java,
                fixture.sourceRun,
            ),
        )
        assertEquals(
            5,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_options WHERE coordination_run_id = ?",
                Int::class.java,
                fixture.sourceRun,
            ),
        )
    }

    @Test
    fun `duplicate worker cannot replace immutable option IDs variant IDs or state version`() {
        val fixture = recommendedRoom()
        val before = stored(fixture.sourceRun)
        val roomBefore = room(fixture.host)
        process(fixture.sourceRun)
        process(fixture.sourceRun)
        assertEquals(before, stored(fixture.sourceRun))
        assertEquals(roomBefore, room(fixture.host))
    }

    @Test
    fun `worker for inactive source cannot publish over the correction successor`() {
        val fixture = recommendedRoom(partial = true)
        val round = openRound(fixture)
        mvc.perform(save(fixture.host, "H1 corrected", round, 1)).andExpect(status().isOk)
        mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
        val next = activeRun(fixture.host)
        prepareMatching(fixture.sourceRun, days = 1, partial = true)
        val oldBefore = stored(fixture.sourceRun)
        val nextBefore = jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id = ?", next)
        val roomBefore = room(fixture.host)
        process(fixture.sourceRun)
        assertEquals(oldBefore, stored(fixture.sourceRun))
        assertEquals(nextBefore, jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id = ?", next))
        assertEquals(roomBefore, room(fixture.host))
        assertEquals(next, activeRun(fixture.host))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM recommendation_analyses WHERE coordination_run_id = ?", Int::class.java, next),
        )
    }

    private fun stored(run: UUID): Map<String, Set<Map<String, Any?>>> =
        listOf(
            "candidates",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "recommendation_selections",
        ).associateWith { table -> jdbc.queryForList("SELECT * FROM $table WHERE coordination_run_id = ?", run).toSet() }

    private fun <T> anyValue(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
