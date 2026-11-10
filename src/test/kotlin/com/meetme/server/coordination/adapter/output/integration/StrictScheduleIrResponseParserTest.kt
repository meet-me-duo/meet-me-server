package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Separate response contract selection is checked before the unchanged strict IR contract. */
class StrictScheduleIrResponseParserTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val fixture = ScheduleIrTestFixture
    private val parser = StrictScheduleIrResponseParser(mapper, CONTRACT)

    @Test
    fun `explicit configured response contract and IR semantic identity preserve the exact typed result`() {
        assertEquals(CONTRACT, parser.supportedResponseContractVersion)
        assertEquals(fixture.envelope(), parser.parse(json(), fixture.snapshot(), CONTRACT, SCHEDULE_IR_VERSION))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "other-contract", "schedule-ir-product-1"])
    fun `response contract cannot be omitted inferred from semantic identity or substituted`(contract: String) {
        assertFailsWith<RuntimeException> { parser.parse(json(), fixture.snapshot(), contract, SCHEDULE_IR_VERSION) }
    }

    @Test
    fun `caller semantic identity cannot silently choose a legacy or future IR decoder`() {
        assertFailsWith<RuntimeException> { parser.parse(json(), fixture.snapshot(), CONTRACT, "unsupported-semantic") }
    }

    @Test
    fun `strict parsing failure does not expose a raw input quote in exception causes`() {
        val secretLikeSynthetic = "synthetic private quote must stay out of exceptions"
        val malformed = "{\"semantic_version\":\"$SCHEDULE_IR_VERSION\",\"results\":[\"$secretLikeSynthetic\""
        val failure = assertFailsWith<RuntimeException> { parser.parse(malformed, fixture.snapshot(), CONTRACT, SCHEDULE_IR_VERSION) }
        val messages = generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.toString() }
        assertTrue(!messages.contains(secretLikeSynthetic))
        assertTrue(!messages.contains(ScheduleIrTestFixture.FIRST_TEXT))
    }

    private fun json(): String {
        val evidence = fixture.evidence()
        val condition =
            mapOf(
                "id" to "thursday",
                "effect" to "AVAILABLE",
                "interpretation" to "EXPLICIT",
                "scope" to mapOf("dimension" to "TIME", "start" to "2027-01-07T18:00:00", "end" to "2027-01-07T20:00:00"),
                "evidence" to
                    listOf(
                        mapOf(
                            "source_id" to evidence.sourceId,
                            "revision" to 3,
                            "start_code_point" to evidence.startCodePoint,
                            "end_code_point" to evidence.endCodePoint,
                            "quote" to evidence.quote,
                        ),
                    ),
                "assertion" to "ASSERTED",
                "supersedes" to emptyList<String>(),
                "alternative_group" to null,
                "alternative" to null,
            )
        return mapper.writeValueAsString(
            mapOf(
                "semantic_version" to SCHEDULE_IR_VERSION,
                "results" to
                    listOf(
                        mapOf(
                            "input_ref" to fixture.firstId.toString(),
                            "availability" to "KNOWN_WINDOWS",
                            "conditions" to listOf(condition),
                        ),
                        mapOf("input_ref" to fixture.secondId.toString(), "availability" to "UNKNOWN", "conditions" to emptyList<Any>()),
                    ),
            ),
        )
    }

    companion object {
        private const val CONTRACT = "synthetic-response-contract-1"
    }
}
