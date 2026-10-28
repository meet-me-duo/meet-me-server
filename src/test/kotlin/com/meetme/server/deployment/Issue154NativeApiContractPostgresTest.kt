package com.meetme.server.deployment

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
import kotlin.test.assertTrue

/** I154-API: deployment verification follows the actual native Spring contract, not a hand-maintained subset. */
class Issue154NativeApiContractPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `I154 API deployment contract matches every native operation and schema property`() {
        val native =
            document(
                mvc
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val operations =
            objectValue(native["paths"])
                .flatMap { (path, item) ->
                    objectValue(item)
                        .filterKeys { it in HTTP_METHODS }
                        .map { (method, operation) ->
                            val operationId = objectValue(operation)["operationId"] as? String
                            assertTrue(!operationId.isNullOrBlank(), "Every native operation needs an operationId")
                            mapOf("path" to path, "method" to method, "operationId" to operationId)
                        }
                }.sortedWith(compareBy({ it.getValue("path") }, { it.getValue("method") }, { it.getValue("operationId") }))
        assertTrue(operations.isNotEmpty(), "The actual native API must contain public operations")
        val schemas = objectValue(objectValue(native["components"])["schemas"])
        assertTrue(schemas.isNotEmpty(), "The actual native API must contain public schemas")
        val schemaProperties =
            schemas.toSortedMap().mapValues { (_, value) ->
                val schema = objectValue(value)
                if (schema["properties"] == null) emptyList() else objectValue(schema["properties"]).keys.sorted()
            }
        val projection =
            mapOf(
                "protocol" to "meet-me-public-api-contract-v1",
                "operations" to operations,
                "schemaProperties" to schemaProperties,
            )
        // Safe build output contains only API identifiers/property names, never examples or provider/user payloads.
        val destination = Path.of("build/issue154-native-public-api-contract.json")
        Files.createDirectories(destination.parent)
        Files.writeString(destination, mapper.writeValueAsString(projection))
        val published = objectValue(mapper.readValue(Files.readString(Path.of("deploy/public-api-contract.json")), Map::class.java))
        assertEquals(setOf("protocol", "operations", "schemaProperties"), published.keys)
        assertEquals(projection, published, "Published deployment contract must match all actual native operations and schemas")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = requireNotNull(value as? Map<String, Any?>)

    companion object {
        private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
