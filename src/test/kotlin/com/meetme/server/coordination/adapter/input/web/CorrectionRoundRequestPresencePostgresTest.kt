package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #92 required preconditions cannot silently become generation 0 when JSON omits a primitive field.
class CorrectionRoundRequestPresencePostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `reopen omitted expected generation is 400 and leaves source and room immutable`() {
        val fixture = completedRoom()
        val roomBefore = room(fixture.host)
        val counts = workCounts()
        mvc
            .perform(
                command(
                    fixture,
                    "reopen",
                    mapOf(
                        "request_id" to UUID.randomUUID(),
                        "source_analysis_id" to fixture.sourceRun,
                    ),
                ),
            ).andExpect(status().isBadRequest)
        assertEquals(roomBefore, room(fixture.host))
        assertEquals(counts, workCounts())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM input_revision_rounds", Int::class.java))
    }

    @Test
    fun `reopen explicit null expected generation is 400 and cannot substitute zero`() {
        val fixture = completedRoom()
        val roomBefore = room(fixture.host)
        val counts = workCounts()
        mvc
            .perform(
                command(
                    fixture,
                    "reopen",
                    mapOf(
                        "request_id" to UUID.randomUUID(),
                        "source_analysis_id" to fixture.sourceRun,
                        "expected_generation" to null,
                    ),
                ),
            ).andExpect(status().isBadRequest)
        assertEquals(roomBefore, room(fixture.host))
        assertEquals(counts, workCounts())
    }

    @Test
    fun `reopen required UUIDs and nonnegative generation are checked without consuming state`() {
        val fixture = completedRoom()
        val roomBefore = room(fixture.host)
        val counts = workCounts()
        val valid =
            mapOf<String, Any?>(
                "request_id" to UUID.randomUUID(),
                "source_analysis_id" to fixture.sourceRun,
                "expected_generation" to 0,
            )
        val invalid =
            listOf(
                valid - "request_id",
                valid - "source_analysis_id",
                valid + ("expected_generation" to -1),
            )
        for (body in invalid) {
            mvc.perform(command(fixture, "reopen", body)).andExpect(status().isBadRequest)
            assertEquals(roomBefore, room(fixture.host))
            assertEquals(counts, workCounts())
        }
    }

    @Test
    fun `analysis missing request or round UUID is 400 and keeps round open without new work`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val roomBefore = room(fixture.host)
        val counts = workCounts()
        for (missing in listOf("request_id", "revision_round_id")) {
            val body = linkedMapOf<String, Any?>("request_id" to UUID.randomUUID(), "revision_round_id" to roundId)
            body.remove(missing)
            mvc.perform(command(fixture, "analysis", body)).andExpect(status().isBadRequest)
            assertEquals(roomBefore, room(fixture.host))
            assertEquals(counts, workCounts())
            assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM input_revision_rounds WHERE id=?", String::class.java, roundId))
        }
    }

    @Test
    fun `analysis omitted force reparse defaults false and reuses without new work`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val counts = workCounts()
        val response =
            mvc
                .perform(
                    command(
                        fixture,
                        "analysis",
                        mapOf(
                            "request_id" to UUID.randomUUID(),
                            "revision_round_id" to roundId,
                        ),
                    ),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals("REUSED", document(response)["outcome"])
        assertEquals(fixture.sourceRun.toString(), document(response)["analysis_id"])
        assertEquals(counts, workCounts())
    }

    @Test
    fun `malformed request source and round UUIDs are 400 without changing the current round`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val before = room(fixture.host)
        val counts = workCounts()
        val reopenBody =
            mapOf<String, Any?>(
                "request_id" to UUID.randomUUID(),
                "source_analysis_id" to fixture.sourceRun,
                "expected_generation" to 1,
            )
        val analysisBody = mapOf<String, Any?>("request_id" to UUID.randomUUID(), "revision_round_id" to roundId)
        for ((route, base, field) in listOf(
            Triple("reopen", reopenBody, "request_id"),
            Triple("reopen", reopenBody, "source_analysis_id"),
            Triple("analysis", analysisBody, "request_id"),
            Triple("analysis", analysisBody, "revision_round_id"),
        )) {
            val bad = base.toMutableMap().also { it[field] = "not-a-uuid" }
            mvc.perform(command(fixture, route, bad)).andExpect(status().isBadRequest)
            assertEquals(before, room(fixture.host))
            assertEquals(counts, workCounts())
        }
    }

    @Test
    fun `generated schema declares expected generation required nonnull integer and UUID preconditions required`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val schemas = child(child(document(response), "components"), "schemas")
        val request = child(schemas, "ReopenInputRequest")
        val required = (request["required"] as? List<*>).orEmpty()
        assertTrue(required.containsAll(listOf("request_id", "source_analysis_id", "expected_generation")))
        val generation = child(child(request, "properties"), "expected_generation")
        val types = (generation["type"] as? List<*>)?.toSet() ?: setOf(generation["type"])
        assertEquals(setOf("integer"), types)
        assertFalse(generation["nullable"] == true)
        val analysis = child(schemas, "AnalyzeRevisionRequest")
        val analysisRequired = (analysis["required"] as? List<*>).orEmpty()
        assertTrue(analysisRequired.containsAll(listOf("request_id", "revision_round_id")))
        assertFalse("force_reparse" in analysisRequired)
        assertEquals(false, child(child(analysis, "properties"), "force_reparse")["default"])
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEETME_ISSUE92_CAPTURE_OPENAPI", matches = "true")
    fun `capture actual native schema for debugging explicitly marked unvalidated`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val output = Path.of("/workspace/scratch/meet-me-bootstrap/issue-92-openapi.unvalidated.json")
        Files.createDirectories(output.parent)
        Files.write(output, response.contentAsByteArray)
    }

    private fun command(
        fixture: CompletedCorrectionFixture,
        route: String,
        body: Map<String, Any?>,
    ) = post("/api/rooms/{code}/{route}", fixture.host.code, route)
        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
        .cookie(fixture.host.cookie())
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(body))

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
