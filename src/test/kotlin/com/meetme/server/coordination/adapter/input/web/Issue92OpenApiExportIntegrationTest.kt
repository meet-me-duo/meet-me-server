package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Independent post-implementation verification. Skipped by default; never claimed as a pre-implementation RED.
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MEETME_ISSUE92_EXPORT_OPENAPI", matches = "true")
class Issue92OpenApiExportIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `verify correction public schemas then export actual native OpenAPI JSON`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val document = obj(mapper.readValue(response.contentAsByteArray, Map::class.java))
        assertTrue(document["openapi"].toString().startsWith("3."))
        val paths = obj(document["paths"])
        val schemas = obj(obj(document["components"])["schemas"])
        val reopen = obj(obj(paths["/api/rooms/{inviteCode}/reopen"])["post"])
        val analyze = obj(obj(paths["/api/rooms/{inviteCode}/analysis"])["post"])
        val putSubmission = obj(obj(paths["/api/rooms/{inviteCode}/submission"])["put"])
        val getRoom = obj(obj(paths["/api/rooms/{inviteCode}"])["get"])

        assertSchemaRef(requestSchema(reopen), "ReopenInputRequest")
        assertSchemaRef(requestSchema(analyze), "AnalyzeRevisionRequest")
        assertSchemaRef(requestSchema(putSubmission), "SaveSubmissionRequest")
        assertSchemaRef(responseSchema(reopen, "200"), "ReopenedInputResponse")
        assertSchemaRef(responseSchema(analyze, "200"), "RevisionAnalysisResponse")
        assertSchemaRef(responseSchema(analyze, "202"), "RevisionAnalysisResponse")
        assertSchemaRef(responseSchema(getRoom, "200"), "RoomResponse")

        val reopenRequest = obj(schemas["ReopenInputRequest"])
        assertTrue(required(reopenRequest).containsAll(listOf("request_id", "source_analysis_id", "expected_generation")))
        assertUuid(property(reopenRequest, "request_id"))
        assertUuid(property(reopenRequest, "source_analysis_id"))
        assertEquals(0L, (property(reopenRequest, "expected_generation")["minimum"] as Number).toLong())
        val analysisRequest = obj(schemas["AnalyzeRevisionRequest"])
        assertTrue(required(analysisRequest).containsAll(listOf("revision_round_id", "request_id")))
        assertUuid(property(analysisRequest, "revision_round_id"))
        assertUuid(property(analysisRequest, "request_id"))
        assertFalse("force_reparse" in required(analysisRequest), "Default false preserves omission compatibility")
        assertEquals(false, property(analysisRequest, "force_reparse")["default"])

        val submission = obj(schemas["SaveSubmissionRequest"])
        assertTrue("raw_text" in required(submission))
        val roundId = property(submission, "revision_round_id")
        assertUuid(roundId)
        assertTrue(allowsNull(roundId), "Collecting PUT does not require a correction token")
        assertTrue(allowsNull(property(submission, "expected_revision")))
        assertFalse("revision_round_id" in required(submission))
        assertFalse("expected_revision" in required(submission))
        assertTrue(roundId["description"].toString().contains("필수"), "Correction-only requiredness must be documented")
        assertTrue(property(submission, "expected_revision")["description"].toString().contains("필수"))

        val room = obj(schemas["RoomResponse"])
        assertTrue(allowsNull(property(room, "analysis_id")))
        assertUuid(property(room, "analysis_id"))
        assertTrue(allowsNull(property(room, "revision_round")))
        assertEquals(0L, (property(room, "revision_generation")["minimum"] as Number).toLong())
        assertEquals(0L, (property(room, "state_version")["minimum"] as Number).toLong())
        assertEquals(3L, (property(room, "remaining_correction_analyses")["maximum"] as Number).toLong())
        val viewer = obj(schemas["ViewerParticipationResponse"])
        assertTrue(allowsNull(property(viewer, "context_id")), "Anonymous/expired viewer context is null")
        assertUuid(property(viewer, "context_id"))
        val capabilities = obj(schemas["RoomCapabilitiesResponse"])
        for (field in listOf(
            "can_edit_own_submission",
            "can_open_revision",
            "can_analyze_revision",
            "can_confirm",
            "can_force_reparse",
        )) {
            assertEquals("boolean", property(capabilities, field)["type"], field)
        }
        val revision = obj(schemas["RevisionRoundResponse"])
        assertTrue(required(revision).containsAll(listOf("id", "generation", "status")))
        assertUuid(property(revision, "id"))
        assertEquals(setOf("OPEN", "CONSUMED"), enumValues(property(revision, "status")))
        val result = obj(schemas["RevisionAnalysisResponse"])
        assertEquals(setOf("QUEUED", "REUSED"), enumValues(property(result, "outcome")))
        assertUuid(property(result, "analysis_id"))
        assertUuid(property(result, "revision_round_id"))
        assertSchemaRef(property(result, "room"), "RoomResponse")
        val reopened = obj(schemas["ReopenedInputResponse"])
        assertSchemaRef(property(reopened, "room"), "RoomResponse")
        assertSchemaRef(property(reopened, "round"), "RevisionRoundResponse")

        for (operation in listOf(reopen, analyze)) {
            for (code in listOf("400", "401", "403", "404", "409", "429", "503")) {
                assertSchemaRef(responseSchema(operation, code), "ApiProblemSchema")
            }
        }
        for (code in listOf("400", "401", "403", "404", "409", "429")) {
            assertSchemaRef(responseSchema(putSubmission, code), "ApiProblemSchema")
        }
        val analysisConflict = obj(obj(analyze["responses"])["409"])["description"].toString()
        assertTrue(analysisConflict.contains("REVISION_CONFLICT"))
        assertTrue(analysisConflict.contains("CORRECTION_ANALYSIS_LIMIT_REACHED"))
        val putConflict = obj(obj(putSubmission["responses"])["409"])["description"].toString()
        assertTrue(putConflict.contains("REVISION_CONFLICT"), "Correction PUT conflict must be documented")
        val problem = obj(schemas["ApiProblemSchema"])
        for (field in listOf("type", "title", "status", "detail", "instance", "code")) {
            assertNotNull(property(problem, field))
        }
        export(response.contentAsByteArray)
    }

    private fun requestSchema(operation: Map<String, Any?>) = contentSchema(obj(operation["requestBody"]))

    private fun responseSchema(
        operation: Map<String, Any?>,
        code: String,
    ) = contentSchema(obj(obj(operation["responses"])[code]))

    private fun contentSchema(container: Map<String, Any?>): Map<String, Any?> {
        val content = obj(container["content"])
        val media = content["application/json"] ?: content["application/problem+json"] ?: content.values.single()
        return obj(obj(media)["schema"])
    }

    private fun property(
        schema: Map<String, Any?>,
        field: String,
    ) = obj(obj(schema["properties"])[field])

    private fun required(schema: Map<String, Any?>) = (schema["required"] as? List<*>)?.map { it.toString() }.orEmpty()

    private fun enumValues(schema: Map<String, Any?>) = (schema["enum"] as? List<*>)?.map { it.toString() }?.toSet()

    private fun assertSchemaRef(
        schema: Map<String, Any?>,
        name: String,
    ) {
        val references = listOf(schema) + branches(schema)
        assertTrue(references.any { it["\$ref"] == "#/components/schemas/$name" }, "Expected schema reference $name")
    }

    private fun assertUuid(schema: Map<String, Any?>) {
        assertTrue((listOf(schema) + branches(schema)).any { it["format"] == "uuid" }, "Expected UUID schema format")
    }

    private fun allowsNull(schema: Map<String, Any?>): Boolean =
        schema["nullable"] == true ||
            schema["type"] == "null" ||
            (schema["type"] as? List<*>)?.contains("null") == true ||
            branches(schema).any(::allowsNull)

    private fun branches(schema: Map<String, Any?>): List<Map<String, Any?>> =
        listOf("anyOf", "oneOf", "allOf").flatMap { key -> (schema[key] as? List<*>)?.map(::obj).orEmpty() }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> {
        assertTrue(value is Map<*, *>, "Expected native OpenAPI object, got ${value?.javaClass?.simpleName}")
        return value as Map<String, Any?>
    }

    private fun export(bytes: ByteArray) {
        val configured = System.getenv("MEETME_ISSUE92_OPENAPI_OUTPUT")?.takeIf { it.isNotBlank() }
        val path = Path.of(configured ?: "/workspace/scratch/meet-me-bootstrap/issue-92-openapi.json")
        require(path.isAbsolute && path.fileName.toString().endsWith(".json")) { "Use an absolute .json output path" }
        val output = path.normalize()
        require(output.startsWith("/workspace") || output.startsWith("/tmp")) { "Output must stay in the authorized workspace or tmp" }
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "issue-92-openapi-", ".pending")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
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
