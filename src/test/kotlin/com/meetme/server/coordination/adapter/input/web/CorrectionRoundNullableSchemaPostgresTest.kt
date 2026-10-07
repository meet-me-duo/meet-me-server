package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #92: native OpenAPI must admit the actual nullable round and preserve the required object fields.
class CorrectionRoundNullableSchemaPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `native revision round schema admits actual closed null and open object without contradictory ref sibling`() {
        val fixture = completedRoom()
        val closedRound = room(fixture.host)["revision_round"]
        assertNull(closedRound)
        openRound(fixture)
        val openRound = obj(room(fixture.host)["revision_round"])
        assertEquals("OPEN", openRound["status"])
        assertTrue(openRound.keys.containsAll(listOf("id", "generation", "status")))

        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val document = document(response)
        assertTrue(document["openapi"].toString().startsWith("3.1"))
        val schemas = obj(obj(document["components"])["schemas"])
        val roomSchema = obj(schemas["RoomResponse"])
        val roundProperty = obj(obj(roomSchema["properties"])["revision_round"])
        assertFalse(
            roundProperty["type"] == "null" && roundProperty.containsKey("\$ref"),
            "A reference to an object and a sibling null type are simultaneous constraints, not a nullable union",
        )
        assertTrue(accepts(roundProperty, closedRound, schemas), "Native schema must admit the actual closed null value")
        assertTrue(accepts(roundProperty, openRound, schemas), "Native schema must admit the actual OPEN object")
        assertFalse(accepts(roundProperty, emptyMap<String, Any?>(), schemas), "Nullable union must retain object requirements")
        assertFalse(accepts(roundProperty, "OPEN", schemas), "Nullable round does not admit a scalar substitute")

        val objectSchema = obj(schemas["RevisionRoundResponse"])
        val required = (objectSchema["required"] as? List<*>)?.map { it.toString() }.orEmpty()
        assertTrue(required.containsAll(listOf("id", "generation", "status")))
        assertTrue(accepts(objectSchema, openRound, schemas))
        for (missing in listOf("id", "generation", "status")) {
            assertFalse(accepts(objectSchema, openRound - missing, schemas), "Round object must require $missing")
        }
    }

    // Targeted JSON Schema checks: reference siblings intersect; unions are evaluated as unions.
    // This does not claim to implement a general JSON Schema validator.
    private fun accepts(
        schema: Map<String, Any?>,
        value: Any?,
        schemas: Map<String, Any?>,
    ): Boolean {
        val reference = schema["\$ref"] as? String
        if (reference != null) {
            if (!reference.startsWith("#/components/schemas/")) return false
            if (!accepts(obj(schemas[reference.substringAfterLast('/')]), value, schemas)) return false
        }
        val types =
            when (val type = schema["type"]) {
                is String -> listOf(type)
                is List<*> -> type.map { it.toString() }
                null -> emptyList()
                else -> return false
            }
        if (types.isNotEmpty() && types.none { matchesType(it, value) }) return false
        val alternatives = branches(schema, "anyOf")
        if (alternatives.isNotEmpty() && alternatives.none { accepts(it, value, schemas) }) return false
        val exclusive = branches(schema, "oneOf")
        if (exclusive.isNotEmpty() && exclusive.count { accepts(it, value, schemas) } != 1) return false
        if (branches(schema, "allOf").any { !accepts(it, value, schemas) }) return false
        val values = schema["enum"] as? List<*>
        if (values != null && value !in values) return false
        if (value is Map<*, *>) {
            val required = schema["required"] as? List<*> ?: emptyList<Any?>()
            if (required.any { !value.containsKey(it) }) return false
            val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (properties.any { (key, property) -> value.containsKey(key) && !accepts(obj(property), value[key], schemas) }) return false
        }
        return true
    }

    private fun matchesType(
        type: String,
        value: Any?,
    ): Boolean =
        when (type) {
            "null" -> value == null
            "object" -> value is Map<*, *>
            "string" -> value is String
            "integer" -> value is Byte || value is Short || value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "array" -> value is List<*>
            else -> false
        }

    private fun branches(
        schema: Map<String, Any?>,
        keyword: String,
    ): List<Map<String, Any?>> = (schema[keyword] as? List<*>)?.map(::obj).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> {
        assertTrue(value is Map<*, *>, "Expected native schema or actual round object")
        return value as Map<String, Any?>
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
