package com.meetme.server.review

import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Release compatibility: API support is an optional hint, never participant or confirmation authority. */
class ReviewSupportHintPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `RS01 native room GET advertises review support without granting confirmation or participant authority`() {
        val fixture = recommendedRoom(days = 1)
        val beforeConfirmation = roomDocument(fixture.host)
        assertEquals(true, beforeConfirmation[SUPPORT_FIELD], "The new server advertises support before confirmation too")
        mvc.perform(claimRequest(fixture.host)).andExpect(status().isConflict)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM review_prompts", Int::class.java))

        mvc.perform(selection(fixture, items(recommendations(fixture.host), "options").first())).andExpect(status().isOk)
        for (actor in listOf(fixture.host, fixture.member)) {
            val room = roomDocument(actor)
            assertEquals(true, room[SUPPORT_FIELD])
            assertEquals("CONFIRMED", room["public_status"])
            val receipt =
                document(
                    mvc
                        .perform(claimRequest(actor))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response,
                )
            assertEquals("PENDING", receipt["state"])
            assertNotNull(receipt["prompt_id"])
        }

        val outsider = CorrectionSession(fixture.host.code, accounts.newAccount("unrelated support-hint viewer").value)
        assertEquals(true, roomDocument(outsider)[SUPPORT_FIELD])
        mvc.perform(claimRequest(outsider)).andExpect(status().isForbidden)
        mvc
            .perform(
                post("/api/rooms/${fixture.host.code}/review-prompt")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"request_id":"${UUID.randomUUID()}"}"""),
            ).andExpect(status().isUnauthorized)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM review_prompts", Int::class.java))
    }

    @Test
    fun `RS02 actual native schema preserves RoomResponse fields and documents an optional boolean review hint`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val destination = Path.of(System.getenv("MEETME_REVIEW_OPENAPI_EXPORT") ?: "build/review-openapi-supported.json")
        destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(destination, response.contentAsByteArray)
        assertTrue(Files.readAllBytes(destination).contentEquals(response.contentAsByteArray))
        val specification = document(response)
        val schemas = objectValue(objectValue(specification["components"])["schemas"])
        val room = objectValue(schemas["RoomResponse"])
        val properties = objectValue(room["properties"])
        val hint = properties[SUPPORT_FIELD] as? Map<*, *>
        assertNotNull(hint, "RoomResponse documents the additive review support hint")
        assertEquals("boolean", hint["type"])
        assertFalse(hint["nullable"] == true)
        assertTrue((hint["description"] as? String)?.isNotBlank() == true)
        assertFalse((room["required"] as? List<*>)?.contains(SUPPORT_FIELD) == true, "Old servers may omit the hint")
        assertEquals(EXISTING_ROOM_FIELDS + SUPPORT_FIELD, properties.keys, "The optional hint preserves all existing room fields")

        val paths = objectValue(specification["paths"])
        val reviewPaths = paths.filterKeys { it.contains("review-prompt") }
        assertEquals(REVIEW_PATHS, reviewPaths.keys)
        for (path in REVIEW_PATHS) {
            assertEquals(setOf("post"), objectValue(reviewPaths[path]).keys)
        }
    }

    private fun roomDocument(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/${actor.code}").cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun claimRequest(actor: CorrectionSession): MockHttpServletRequestBuilder =
        post("/api/rooms/${actor.code}/review-prompt")
            .cookie(actor.cookie())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"request_id":"${UUID.randomUUID()}"}""")

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = requireNotNull(value as? Map<String, Any?>)

    companion object {
        private const val SUPPORT_FIELD = "review_prompt_supported"
        private val EXISTING_ROOM_FIELDS =
            setOf(
                "purpose",
                "viewer",
                "capabilities",
                "invite_code",
                "meeting_mode",
                "time_zone_id",
                "search_start_date",
                "search_end_date",
                "search_range_source",
                "expected_participants",
                "submission_deadline",
                "manual_only",
                "collection_status",
                "closure_reason",
                "closed_at",
                "public_status",
                "input_disclosure_policy",
                "analysis_id",
                "revision_generation",
                "revision_round",
                "remaining_correction_analyses",
                "state_version",
                "recommendation_protocol",
                "clarification_protocol",
                "submission_protocol",
            )
        private val REVIEW_PATHS =
            setOf(
                "/api/rooms/{inviteCode}/review-prompt",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/submission",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/close",
            )
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
