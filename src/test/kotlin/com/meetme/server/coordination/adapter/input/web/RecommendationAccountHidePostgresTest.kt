package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #197: a hidden archive relationship leaves the confirmed result and its exact ownership intact. */
class RecommendationAccountHidePostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Test
    fun `host and member hide preserve recommendations confirmed results and own originals`() {
        val fixture = recommendedRoom()
        val option = items(recommendations(fixture.host, attendanceSupport = "conditional-v1"), "options").first()
        mvc.perform(selection(fixture, option, attendanceSupport = "conditional-v1")).andExpect(status().isOk)
        val actors = listOf(fixture.host, fixture.member)
        val results = actors.associateWith { result(it, attendanceSupport = "conditional-v1") }
        val recommendationsBefore = actors.associateWith { recommendations(it, attendanceSupport = "conditional-v1") }
        val originals = actors.associateWith(::owner)
        val roomBefore = room(fixture.host)
        val runBefore = jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id=?", fixture.sourceRun)

        for (actor in actors) {
            mvc
                .perform(post("/api/account/rooms/${actor.code}/hide").header("Origin", ORIGIN).cookie(actor.cookie()))
                .andExpect(status().isNoContent)
            mvc
                .perform(get("/api/account/rooms").cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.rooms.length()").value(0))
            assertEquals(results.getValue(actor), result(actor, attendanceSupport = "conditional-v1"))
            assertEquals(recommendationsBefore.getValue(actor), recommendations(actor, attendanceSupport = "conditional-v1"))
            assertEquals(originals.getValue(actor), owner(actor))
        }
        assertEquals(roomBefore, room(fixture.host))
        assertEquals(runBefore, jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id=?", fixture.sourceRun))
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
    }

    @Test
    fun `cleanup skips an uncommitted hide transaction then deletes its room and result without resurrection`() {
        val fixture = recommendedRoom()
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val transaction = TransactionTemplate(transactionManager)
        // Repository cutoff is explicit so the test can advance retention without waiting 30 real days.
        val cutoff = Instant.now().plusSeconds(60)
        val now = cutoff.plusSeconds(30L * 86_400)
        val hiding =
            executor.submit<Int> {
                requireNotNull(
                    transaction.execute {
                        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id=? FOR UPDATE", UUID::class.java, fixture.roomId)
                        val code =
                            mvc
                                .perform(
                                    post(
                                        "/api/account/rooms/${fixture.host.code}/hide",
                                    ).header("Origin", ORIGIN).cookie(fixture.host.cookie()),
                                ).andReturn()
                                .response.status
                        written.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        code
                    },
                )
            }
        try {
            assertTrue(written.await(10, TimeUnit.SECONDS))
            assertEquals(0, retention.deleteExpiredRooms(cutoff, 100, now), "Locked room is skipped, never partially removed")
            release.countDown()
            assertEquals(204, hiding.get(15, TimeUnit.SECONDS))
            assertEquals(1, retention.deleteExpiredRooms(cutoff, 100, now))
            mvc
                .perform(post("/api/account/rooms/${fixture.host.code}/hide").header("Origin", ORIGIN).cookie(fixture.host.cookie()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
            for (table in listOf(
                "meeting_rooms",
                "participants",
                "coordination_runs",
                "recommendation_analyses",
                "recommendation_options",
            )) {
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java), table)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
