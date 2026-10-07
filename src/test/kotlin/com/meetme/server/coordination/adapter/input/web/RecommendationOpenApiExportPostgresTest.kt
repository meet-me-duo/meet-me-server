package com.meetme.server.coordination.adapter.input.web

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Native generator contract test. Optional export writes the exact response bytes; no manually assembled spec.
class RecommendationOpenApiExportPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `native OpenAPI documents real recommendation endpoints selection requiredness and nullable schemas`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val spec = document(response)
        val paths = obj(spec.getValue("paths"))
        val schemas = obj(obj(spec.getValue("components")).getValue("schemas"))
        val prefix = "/api/rooms/{inviteCode}"
        val primary = obj(obj(paths.getValue("$prefix/recommendations")).getValue("get"))
        val alternatives = obj(obj(paths.getValue("$prefix/recommendations/alternatives")).getValue("get"))
        val confirmation = obj(obj(paths.getValue("$prefix/recommendations/{optionId}/confirmation")).getValue("post"))
        val result = obj(obj(paths.getValue("$prefix/result")).getValue("get"))
        val request = deref(content(obj(confirmation.getValue("requestBody"))), schemas)
        assertTrue(required(request).containsAll(listOf("analysis_id", "variant_id", "start_at", "end_at")))
        for (field in listOf("analysis_id", "variant_id")) assertFormat(property(request, field), "uuid")
        for (field in listOf("start_at", "end_at")) assertFormat(property(request, field), "date-time")
        val envelope = deref(responseSchema(primary, "200"), schemas)
        assertTrue(
            required(
                envelope,
            ).containsAll(listOf("protocol", "analysis_id", "state_version", "quality", "total_options", "options", "has_alternatives")),
        )
        assertFormat(property(envelope, "analysis_id"), "uuid")
        assertTrue(nullable(property(envelope, "protocol")), "Legacy protocol null is a real supported response")
        val option = deref(obj(property(envelope, "options").getValue("items")), schemas)
        assertTrue(required(option).containsAll(listOf("option_id", "rank", "time_range", "variants", "summary")))
        assertFormat(property(option, "option_id"), "uuid")
        assertTrue(nullable(property(option, "rank")), "Alternative options do not pretend to be primary cards")
        val range = deref(property(option, "time_range"), schemas)
        assertFormat(property(range, "start_at"), "date-time")
        assertFormat(property(range, "end_at"), "date-time")
        val variant = deref(obj(property(option, "variants").getValue("items")), schemas)
        assertTrue(
            required(
                variant,
            ).containsAll(listOf("variant_id", "meeting_mode", "attendance_count", "total_participants", "partial_attendance", "place")),
        )
        assertFormat(property(variant, "variant_id"), "uuid")
        assertEquals("boolean", property(variant, "partial_attendance")["type"])
        assertTrue(nullable(property(variant, "place")))
        val alternateResponse = deref(responseSchema(alternatives, "200"), schemas)
        assertNotNull(property(alternateResponse, "next_cursor"))
        assertTrue(nullable(property(alternateResponse, "next_cursor")))
        val parameters = list(alternatives.getValue("parameters")).associateBy { it.getValue("name") }
        assertEquals(true, parameters.getValue("analysis_id")["required"])
        assertFormat(obj(parameters.getValue("analysis_id").getValue("schema")), "uuid")
        val limit = obj(parameters.getValue("limit").getValue("schema"))
        assertEquals(1, (limit.getValue("minimum") as Number).toInt())
        assertEquals(100, (limit.getValue("maximum") as Number).toInt())
        assertEquals(20, (limit.getValue("default") as Number).toInt())
        val confirmed = deref(responseSchema(confirmation, "200"), schemas)
        assertEquals(responseSchema(confirmation, "200"), responseSchema(result, "200"))
        val selection = deref(property(confirmed, "selection"), schemas)
        assertTrue(nullable(property(confirmed, "selection")), "Historical candidate confirmations retain selection=null")
        assertTrue(required(selection).containsAll(listOf("protocol", "analysis_id", "option_id", "variant_id", "start_at", "end_at")))
        for (field in listOf("analysis_id", "option_id", "variant_id")) assertFormat(property(selection, field), "uuid")
        for (field in listOf("start_at", "end_at")) assertFormat(property(selection, field), "date-time")
        val candidate = deref(property(confirmed, "candidate"), schemas)
        assertTrue(nullable(property(candidate, "candidate_id")), "Option IDs must not impersonate candidate IDs")
        for (operation in listOf(primary, alternatives, confirmation)) {
            for (code in listOf("401", "403", "404", "409")) assertProblem(responseSchema(operation, code), schemas)
        }
        assertProblem(responseSchema(alternatives, "400"), schemas)
        for (code in listOf("400", "429", "503")) assertProblem(responseSchema(confirmation, code), schemas)
        System.getenv("MEETME_ISSUE95_OPENAPI_EXPORT")?.takeIf { it.isNotBlank() }?.let { exportPath ->
            val target = Path.of(exportPath)
            target.parent?.let(Files::createDirectories)
            Files.write(target, response.contentAsByteArray)
        }
    }

    private fun assertProblem(
        schema: Map<String, Any?>,
        schemas: Map<String, Any?>,
    ) {
        val problem = deref(schema, schemas)
        assertNotNull(property(problem, "status"))
        assertNotNull(property(problem, "code"))
    }

    private fun responseSchema(
        operation: Map<String, Any?>,
        code: String,
    ): Map<String, Any?> = content(obj(obj(operation.getValue("responses")).getValue(code)))

    private fun content(container: Map<String, Any?>): Map<String, Any?> {
        val content = obj(container.getValue("content"))
        val media = content["application/json"] ?: content["application/problem+json"] ?: content.values.single()
        return obj(obj(media).getValue("schema"))
    }

    private fun deref(
        schema: Map<String, Any?>,
        schemas: Map<String, Any?>,
    ): Map<String, Any?> {
        schema["\$ref"]?.let { return obj(schemas.getValue(it.toString().substringAfterLast('/'))) + schema }
        for (field in listOf("anyOf", "oneOf", "allOf")) {
            if (schema[field] is List<*>) {
                val real = list(schema.getValue(field)).firstOrNull { it["type"] != "null" }
                if (real != null) return deref(real, schemas) + schema
            }
        }
        return schema
    }

    private fun property(
        schema: Map<String, Any?>,
        field: String,
    ): Map<String, Any?> = obj(obj(schema.getValue("properties")).getValue(field))

    private fun required(schema: Map<String, Any?>): Set<String> =
        (schema["required"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun assertFormat(
        schema: Map<String, Any?>,
        expected: String,
    ) {
        assertEquals(expected, schema["format"], schema.toString())
    }

    private fun nullable(schema: Map<String, Any?>): Boolean =
        schema["nullable"] == true ||
            schema["type"] == "null" ||
            (schema["type"] as? List<*>)?.contains("null") == true ||
            listOf("anyOf", "oneOf").any { field -> (schema[field] as? List<*>)?.any { nullable(obj(it)) } == true }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
