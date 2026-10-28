package com.meetme.server.review

import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RV14: stable review operation ids survive a narrow merge into an existing web snapshot. */
class ReviewOperationIdsPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `RV14 review operation ids are stable and unique across the whole native API`() {
        val spec =
            document(
                mvc
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val paths = objectValue(spec["paths"])
        val expected =
            mapOf(
                "/api/rooms/{inviteCode}/review-prompt" to "claimReviewPrompt",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/submission" to "submitReviewPrompt",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/close" to "closeReviewPrompt",
            )
        for ((path, operationId) in expected) {
            assertEquals(operationId, objectValue(objectValue(paths[path])["post"])["operationId"])
        }
        val operationIds =
            paths.values.flatMap { path ->
                objectValue(path).filterKeys { it in HTTP_METHODS }.values.mapNotNull { operation ->
                    objectValue(operation)["operationId"] as? String
                }
            }
        val duplicates = operationIds.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "Native API operation ids must be unique: ${duplicates.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = requireNotNull(value as? Map<String, Any?>)

    companion object {
        private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
