package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent ADR-067 cache review: public equivalent MVC paths, synthetic accounts and real PostgreSQL. */
class AttendanceNoStoreEquivalentPathPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `CACHE01 matrix parameter equivalents of all six result routes keep no store`() {
        verifyEquivalentPaths(::matrix)
    }

    @Test
    fun `CACHE02 encoded literal equivalents of all six result routes keep no store`() {
        verifyEquivalentPaths(::encoded)
    }

    @Test
    fun `CACHE03 equivalent path compatibility conflicts retain no store without exposing false payload`() {
        val fixture = completedRoom()
        jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", fixture.roomId)
        val candidate = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) " +
                "VALUES (?, ?, 1, 'PLAN_A', 'IN_PERSON', 2, 2)",
            candidate,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id, range_order, start_at, end_at) " +
                "VALUES (?, 0, '2026-10-07T10:00:00Z', '2026-10-07T11:00:00Z')",
            candidate,
        )
        val participants =
            jdbc.queryForList("SELECT id FROM participants WHERE room_id=? ORDER BY id::text", UUID::class.java, fixture.roomId)
        participants.forEachIndexed { index, participant ->
            jdbc.update(
                "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id, place_unverified) " +
                    "VALUES (?, ?, ?, ?, ?)",
                candidate,
                participant,
                fixture.sourceRun,
                fixture.roomId,
                index == 0,
            )
        }
        val base = "/api/rooms/${fixture.host.code}"
        val responses =
            listOf(::matrix, ::encoded).flatMap { equivalent ->
                val readPath = equivalent("$base/candidates")
                val writePath = equivalent("$base/candidates/$candidate/confirmation")
                listOf(
                    perform(readPath, get(URI.create(readPath)), fixture),
                    perform(writePath, post(URI.create(writePath)), fixture),
                )
            }
        for ((path, response) in responses) {
            assertEquals(409, response.status, "Equivalent route must reach the compatibility guard: $path")
            val problem = document(response)
            assertEquals("CLIENT_UPDATE_REQUIRED", problem["code"])
            assertTrue(listOf("candidate", "candidates", "selection").none { it in problem })
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertNoStore(responses)
    }

    private fun verifyEquivalentPaths(equivalent: (String) -> String) {
        val fixture = recommendedRoom(days = 5)
        val option = items(recommendations(fixture.host), "options").first()
        val variant = items(option, "variants").first()
        val selected = mvc.perform(selection(fixture, option, variant)).andReturn().response
        assertEquals(200, selected.status, "The real typed selection must exist before result-path review")
        val candidate =
            jdbc.queryForObject(
                "SELECT id FROM candidates WHERE coordination_run_id=? ORDER BY rank LIMIT 1",
                UUID::class.java,
                fixture.sourceRun,
            )
        val base = "/api/rooms/${fixture.host.code}"
        val paths =
            listOf(
                "$base/candidates",
                "$base/recommendations",
                "$base/recommendations/alternatives",
                "$base/recommendations/${option.getValue("option_id")}/confirmation",
                "$base/candidates/$candidate/confirmation",
                "$base/result",
            ).map(equivalent)
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "analysis_id" to fixture.sourceRun,
                    "variant_id" to variant.getValue("variant_id"),
                    "start_at" to child(option, "time_range").getValue("start_at"),
                    "end_at" to child(option, "time_range").getValue("end_at"),
                ),
            )
        val responses =
            paths.mapIndexed { index, path ->
                val request =
                    when (index) {
                        3 -> post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(body)
                        4 -> post(URI.create(path))
                        else -> get(URI.create(path))
                    }
                if (index == 2) request.param("analysis_id", fixture.sourceRun.toString())
                perform(path, request, fixture)
            }
        responses.forEachIndexed { index, (path, response) ->
            assertEquals(if (index == 4) 409 else 200, response.status, "Equivalent path must invoke the existing route: $path")
            if (index == 4) assertEquals("RECOMMENDATION_SELECTION_REQUIRED", document(response)["code"])
        }
        assertNoStore(responses)
    }

    private fun perform(
        path: String,
        request: MockHttpServletRequestBuilder,
        fixture: CompletedCorrectionFixture,
    ): Pair<String, MockHttpServletResponse> =
        path to
            mvc
                .perform(request.cookie(fixture.host.cookie()).header("Origin", ORIGIN))
                .andReturn()
                .response

    private fun assertNoStore(responses: List<Pair<String, MockHttpServletResponse>>) {
        val missing =
            responses.filterNot { (_, response) -> response.getHeaders(HttpHeaders.CACHE_CONTROL).any { it.contains("no-store") } }
        assertTrue(missing.isEmpty(), "Equivalent MVC result routes must keep no-store: ${missing.map { it.first }}")
    }

    private fun matrix(path: String): String =
        path
            .replace("/recommendations", "/recommendations;client=old")
            .replace("/candidates", "/candidates;client=old")
            .replace("/result", "/result;client=old")

    private fun encoded(path: String): String =
        path
            .replace("/recommendations", "/recomm%65ndations")
            .replace("/candidates", "/candidat%65s")
            .replace("/result", "/res%75lt")

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
