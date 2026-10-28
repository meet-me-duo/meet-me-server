package com.meetme.server.review

import com.meetme.server.coordination.adapter.input.web.RecommendationPostgresFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RV14: the native HTTP schema is authoritative; this does not synthesize an OpenAPI document. */
class ReviewNativeOpenApiPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `RV14 native OpenAPI bytes are exported and review has exactly three write-only paths`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val destination = Path.of(System.getenv("MEETME_REVIEW_OPENAPI_EXPORT") ?: "build/review-openapi.json")
        destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(destination, response.contentAsByteArray)
        assertTrue(Files.readAllBytes(destination).contentEquals(response.contentAsByteArray))
        val spec = document(response)
        val paths = objectValue(spec["paths"])
        val reviews = paths.filterKeys { it.contains("review-prompt") }
        assertEquals(REVIEW_PATHS, reviews.keys)
        for (path in REVIEW_PATHS) {
            assertEquals(setOf("post"), objectValue(reviews[path]).keys)
        }
    }

    @Test
    fun `RV14 native endpoint errors and success reference their public schemas`() {
        val spec = specification()
        for (path in REVIEW_PATHS) {
            val operation = objectValue(objectValue(objectValue(spec["paths"])[path])["post"])
            val responses = objectValue(operation["responses"])
            assertTrue(responses.keys.containsAll(RESPONSE_CODES), "Each review operation documents every public status")
            for (code in RESPONSE_CODES - "200") {
                val content = objectValue(objectValue(responses[code])["content"])
                assertTrue(content.isNotEmpty())
                assertTrue(content.containsKey("application/problem+json"), "Review errors document RFC 9457 media type")
                val errors = content.values.map { resolve(spec, objectValue(it)["schema"]) }
                assertTrue(errors.all { objectValue(it["properties"]).keys.containsAll(setOf("code", "status", "detail")) })
            }
            val success = objectValue(objectValue(responses["200"])["content"])
            assertTrue(success.isNotEmpty())
            for (entry in success.values) {
                val schema = resolve(spec, objectValue(entry)["schema"])
                assertEquals(setOf("prompt_id", "state"), objectValue(schema["properties"]).keys)
            }
        }
    }

    @Test
    fun `RV14 choices and optional OTHER limit are represented in the generated request schema`() {
        val spec = specification()
        val schema = requestSchema(spec, "/api/rooms/{inviteCode}/review-prompt/{promptId}/submission")
        val properties = objectValue(schema["properties"])
        assertEquals(setOf("choices", "other_text"), properties.keys)
        val choices = resolve(spec, properties["choices"])
        assertEquals(1, (choices["minItems"] as? Number)?.toInt())
        assertEquals(5, (choices["maxItems"] as? Number)?.toInt())
        assertEquals(true, choices["uniqueItems"])
        val item = resolve(spec, choices["items"])
        val allowedChoices = item["enum"] as? List<*>
        assertNotNull(allowedChoices, "Choice enum must be documented")
        assertTrue(allowedChoices.all { it is Number }, "Integer choice enum must contain JSON numbers")
        assertEquals(setOf(1, 2, 3, 4, 5), allowedChoices.filterIsInstance<Number>().map(Number::toInt).toSet())
        val other = resolve(spec, properties["other_text"])
        // Existing frozen technical proposal, not a new product-policy decision.
        assertEquals(2000, (other["maxLength"] as? Number)?.toInt())
        assertTrue(isNullable(other), "Optional OTHER text accepts null")
        assertFalse((schema["required"] as? List<*>)?.contains("other_text") == true)
        assertTrue((schema["required"] as? List<*>)?.contains("choices") == true)
    }

    @Test
    fun `RV14 claim close and terminal schemas expose no draft or owner identity`() {
        val spec = specification()
        val claim = requestSchema(spec, "/api/rooms/{inviteCode}/review-prompt")
        val claimProperties = objectValue(claim["properties"])
        assertEquals(setOf("request_id"), claimProperties.keys)
        assertEquals("uuid", resolve(spec, claimProperties["request_id"])["format"])
        assertTrue((claim["required"] as? List<*>)?.contains("request_id") == true)
        val close = requestSchema(spec, "/api/rooms/{inviteCode}/review-prompt/{promptId}/close")
        val closeProperties = objectValue(close["properties"])
        assertEquals(setOf("hide_for_week"), closeProperties.keys)
        assertTrue((close["required"] as? List<*>)?.contains("hide_for_week") == true)
        val hide = resolve(spec, closeProperties["hide_for_week"])
        assertTrue(hasType(hide, "boolean"))
        assertFalse(isNullable(hide), "Explicit close choice is mandatory")
        val schemas = objectValue(objectValue(spec["components"])["schemas"])
        val response = resolve(spec, schemas["ReviewResponse"])
        val fields = objectValue(response["properties"])
        assertEquals(setOf("prompt_id", "state"), fields.keys)
        assertEquals(setOf("prompt_id", "state"), (response["required"] as? List<*>)?.toSet())
        assertTrue(isNullable(resolve(spec, fields["prompt_id"])))
        val states = resolve(spec, fields["state"])["enum"] as? List<*>
        assertNotNull(states)
        assertEquals(setOf("PENDING", "SUBMITTED", "CLOSED", "SUPPRESSED"), states.toSet())
    }

    private fun specification(): Map<String, Any?> =
        document(
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun requestSchema(
        spec: Map<String, Any?>,
        path: String,
    ): Map<String, Any?> {
        val operation = objectValue(objectValue(objectValue(spec["paths"])[path])["post"])
        val content = objectValue(objectValue(operation["requestBody"])["content"])
        val json = objectValue(content["application/json"])
        return resolve(spec, json["schema"])
    }

    private fun resolve(
        spec: Map<String, Any?>,
        value: Any?,
    ): Map<String, Any?> {
        val schema = objectValue(value)
        val reference = schema["\$ref"] as? String ?: return schema
        assertTrue(reference.startsWith("#/components/schemas/"))
        return resolve(spec, objectValue(objectValue(spec["components"])["schemas"])[reference.substringAfterLast('/')])
    }

    private fun isNullable(schema: Map<String, Any?>): Boolean =
        schema["nullable"] == true ||
            hasType(schema, "null") ||
            listOf("anyOf", "oneOf").any { key ->
                (schema[key] as? List<*>)?.any { isNullable(objectValue(it)) } == true
            }

    private fun hasType(
        schema: Map<String, Any?>,
        value: String,
    ): Boolean = schema["type"] == value || (schema["type"] as? List<*>)?.contains(value) == true

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = requireNotNull(value as? Map<String, Any?>)

    companion object {
        private val REVIEW_PATHS =
            setOf(
                "/api/rooms/{inviteCode}/review-prompt",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/submission",
                "/api/rooms/{inviteCode}/review-prompt/{promptId}/close",
            )
        private val RESPONSE_CODES = setOf("200", "400", "401", "403", "404", "409", "503")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
