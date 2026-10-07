package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

// Independent #95 binding boundary: documented RFC 9457 errors also cover Spring parameter and path conversion failures.
class RecommendationBindingPostgresTest : RecommendationPostgresFixture() {
    @ParameterizedTest(name = "invalid alternative binding {0} returns the documented problem without mutation")
    @ValueSource(strings = ["MISSING_ANALYSIS", "MALFORMED_ANALYSIS", "NONNUMERIC_LIMIT"])
    fun `alternative query binding errors return validation problem and cannot write selection`(case: String) {
        val fixture = recommendedRoom(days = 1)
        val request = get("/api/rooms/{code}/recommendations/alternatives", fixture.host.code).cookie(fixture.host.cookie())
        when (case) {
            "MISSING_ANALYSIS" -> Unit
            "MALFORMED_ANALYSIS" -> request.param("analysis_id", "not-a-uuid")
            "NONNUMERIC_LIMIT" -> request.param("analysis_id", fixture.sourceRun.toString()).param("limit", "twenty")
            else -> error("Unspecified public binding example")
        }
        assertBindingProblem(fixture, request)
    }

    @Test
    fun `invalid option UUID path with otherwise valid selection returns validation problem without mutation`() {
        val fixture = recommendedRoom(days = 1)
        val option = items(recommendations(fixture.host), "options").single()
        val range = child(option, "time_range")
        val request =
            post("/api/rooms/{code}/recommendations/{option}/confirmation", fixture.host.code, "not-an-option-uuid")
                .header("Origin", ORIGIN)
                .cookie(fixture.host.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "analysis_id" to fixture.sourceRun,
                            "variant_id" to items(option, "variants").first().getValue("variant_id"),
                            "start_at" to range.getValue("start_at"),
                            "end_at" to range.getValue("end_at"),
                        ),
                    ),
                )
        assertBindingProblem(fixture, request)
    }

    private fun assertBindingProblem(
        fixture: CompletedCorrectionFixture,
        request: MockHttpServletRequestBuilder,
    ) {
        val beforeRoom = room(fixture.host)
        val beforeWork = workCounts()
        mvc
            .perform(request)
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.type").isString)
            .andExpect(jsonPath("$.title").isString)
            .andExpect(jsonPath("$.detail").isString)
            .andExpect(jsonPath("$.instance").isString)
        assertEquals(beforeRoom, room(fixture.host))
        assertEquals(beforeWork, workCounts())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
