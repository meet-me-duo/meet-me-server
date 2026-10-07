package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Independent reviewer regressions: public summaries must preserve actual chosen precision and native schema must match support.
class RecommendationReviewRegressionPostgresTest : RecommendationPostgresFixture() {
    @Test
    fun `actual seconds selection summary preserves both boundaries instead of showing zero duration`() {
        val fixture = recommendedRoom(days = 1)
        val option = items(recommendations(fixture.host), "options").single()
        val windowStart = Instant.parse(child(option, "time_range")["start_at"].toString())
        val start = windowStart.plusSeconds(1).toString()
        val end = windowStart.plusSeconds(30).toString()
        val confirmed =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val candidate = child(confirmed, "candidate")
        val summary = candidate.getValue("summary").toString()
        assertContains(summary, "18:00:01")
        assertContains(summary, "18:00:30")
        assertFalse(summary.contains("18:00~18:00"), "A positive 29-second meeting must not appear as zero duration")
        assertEquals(listOf(mapOf("start_at" to start, "end_at" to end)), items(candidate, "time_ranges"))
        assertEquals(start, child(confirmed, "selection")["start_at"])
        assertEquals(end, child(confirmed, "selection")["end_at"])
        assertEquals(confirmed, result(fixture.member))
        val replay =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(confirmed, replay)
    }

    @Test
    fun `one microsecond selection summary preserves actual fractions and persisted idempotent result`() {
        val fixture = recommendedRoom(days = 1)
        val option = items(recommendations(fixture.host), "options").single()
        val windowStart = Instant.parse(child(option, "time_range")["start_at"].toString())
        val start = windowStart.plusNanos(1000).toString()
        val end = windowStart.plusNanos(2000).toString()
        val confirmed =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val candidate = child(confirmed, "candidate")
        val summary = candidate.getValue("summary").toString()
        assertContains(summary, "18:00:00.000001")
        assertContains(summary, "18:00:00.000002")
        assertFalse(summary.contains("18:00~18:00"), "A positive one-microsecond meeting must retain distinct displayed boundaries")
        assertEquals(listOf(mapOf("start_at" to start, "end_at" to end)), items(candidate, "time_ranges"))
        assertEquals(start, child(confirmed, "selection")["start_at"])
        assertEquals(end, child(confirmed, "selection")["end_at"])
        assertEquals(confirmed, result(fixture.host))
        val replay =
            document(
                mvc
                    .perform(selection(fixture, option, start = start, end = end))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(confirmed, replay)
        assertEquals(confirmed, result(fixture.member))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
    }

    @Test
    fun `native OpenAPI allows only actual variant modes and nullable string room recommendation protocol`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val spec = document(response)
        val schemas = obj(obj(spec.getValue("components")).getValue("schemas"))
        val variant = obj(schemas.getValue("RecommendationVariantResponse"))
        val mode = resolve(property(variant, "meeting_mode"), schemas)
        assertEquals(setOf("IN_PERSON", "REMOTE"), (mode.getValue("enum") as List<*>).toSet())
        val roomSchema = obj(schemas.getValue("RoomResponse"))
        val protocol = resolve(property(roomSchema, "recommendation_protocol"), schemas)
        assertEquals(setOf("string", "null"), types(protocol), "Protocol is a nullable string, never object or array")
        assertEquals(setOf("string"), types(mode))
        val legacy = completedRoom()
        assertEquals(null, room(legacy.host)["recommendation_protocol"])
        val current = recommendedRoom(days = 1)
        assertEquals("diverse-time-v1", room(current.host)["recommendation_protocol"])
    }

    private fun resolve(
        schema: Map<String, Any?>,
        schemas: Map<String, Any?>,
    ): Map<String, Any?> {
        schema["\$ref"]?.let { return obj(schemas.getValue(it.toString().substringAfterLast('/'))) }
        return schema
    }

    private fun types(schema: Map<String, Any?>): Set<String> =
        buildSet {
            when (val type = schema["type"]) {
                is String -> add(type)
                is List<*> -> addAll(type.map { it.toString() })
            }
            if (schema["nullable"] == true) add("null")
            for (field in listOf("anyOf", "oneOf")) {
                (schema[field] as? List<*>)?.forEach { addAll(types(obj(it))) }
            }
        }

    private fun property(
        schema: Map<String, Any?>,
        field: String,
    ): Map<String, Any?> = obj(obj(schema.getValue("properties")).getValue(field))

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
