package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Fixed calendar facts and synthetic parser controls; no live provider or language-quality claim. */
class ScheduleDslCalendarGroundingContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `CG01 calendar facts cover 31 fixed local days across leap month boundaries with an exclusive end`() {
        val input = calendarInput()
        val request = request(input)
        assertTrue("calendar_context" in request, "C5 requests must include frozen server calendar facts")
        val calendar = fields(request.getValue("calendar_context"))
        assertEquals(setOf("version", "time_zone", "search_days", "search_end_exclusive", "reference_days"), calendar.keys)
        assertEquals("server-calendar-facts-1", calendar["version"])
        assertEquals("Asia/Seoul", calendar["time_zone"])
        val actual = list(calendar["search_days"]).map(::fields)
        val expected =
            CALENDAR_ROWS.trimIndent().lines().map { row ->
                val (date, weekday) = row.split(' ')
                mapOf("local_date" to date, "weekday" to weekday)
            }
        assertEquals(31, actual.size)
        assertEquals(expected, actual)
        actual.forEach { assertEquals(setOf("local_date", "weekday"), it.keys) }
        assertEquals(mapOf("local_date" to "2028-03-02", "weekday" to "THURSDAY"), fields(calendar["search_end_exclusive"]))
        assertFalse(actual.any { it["local_date"] == "2028-03-02" })
        assertTrue(actual.any { it["local_date"] == "2028-02-29" })
    }

    @Test
    fun `CG02 reference days deduplicate and sort frozen source instants before converting across local midnight`() {
        val input = referenceInput()
        val capsule = N.capsule(input)
        assertEquals(
            3,
            capsule.snapshot.context.inputs
                .sumOf { it.sources.size },
        )
        assertEquals(1, capsule.questions.size)
        val bytes = contract.requestBytes(capsule, MeetingMode.EITHER)
        val request = fields(mapper.readValue(bytes, Map::class.java))
        assertTrue("calendar_context" in request)
        val calendar = fields(request["calendar_context"])
        assertEquals("America/Los_Angeles", calendar["time_zone"])
        val references = list(calendar["reference_days"]).map(::fields)
        assertEquals(
            listOf(
                mapOf("reference_at" to "2028-03-01T00:30:00Z", "local_date" to "2028-02-29", "weekday" to "TUESDAY"),
                mapOf("reference_at" to "2028-03-01T08:30:00Z", "local_date" to "2028-03-01", "weekday" to "WEDNESDAY"),
            ),
            references,
        )
        references.forEach { assertEquals(setOf("reference_at", "local_date", "weekday"), it.keys) }
        assertContentEquals(bytes, contract.requestBytes(capsule, MeetingMode.EITHER))
    }

    @Test
    fun `CG03 calendar metadata preserves native context vocabulary and response grammar without restricting output dates`() {
        val input = referenceInput()
        val capsule = N.capsule(input)
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val oldBytes = c4.requestBytes(capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val responseSchema = mapper.writeValueAsBytes(contract.responseSchema())
        val previous = fields(mapper.readValue(oldBytes, Map::class.java))
        val current = request(input)
        assertTrue("calendar_context" in current)
        assertEquals(previous["inputs"], current["inputs"])
        assertEquals(previous["context"], current["context"])
        assertEquals("c5-owned-proof-choice-2", current["producer_schema_revision"])
        assertEquals("schedule-dsl-response-5", current["response_contract_version"])
        assertEquals("schedule-ir-product-6", current["semantic_version"])
        assertEquals(fields(mapper.readValue(responseSchema, Map::class.java)), current["schema"])
        val oldPrompt = previous.getValue("prompt") as String
        val table =
            oldPrompt
                .substringAfter("The complete approved vocabulary is ")
                .substringBefore("These are local calendar boundaries,")
                .substringBefore("These are local calendar boundaries.")
                .trim()
        assertEquals(table, (current.getValue("prompt") as String).substringAfter("The complete approved vocabulary is ").trim())
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = variants(item(result.getValue("conditions")), schema)
        val atoms = conditions.flatMap { variants(properties(it).getValue("temporal"), schema) }
        atoms.filter { "local_date" in properties(it) }.forEach {
            val date = fields(properties(it)["local_date"])
            assertFalse("enum" in date)
            assertFalse("const" in date)
        }
        assertContentEquals(responseSchema, mapper.writeValueAsBytes(contract.responseSchema()))
        assertContentEquals(oldBytes, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(c4.responseSchema()))
        val old = DateScopeInlineFixture.dateOnly()
        val outside = old.copy(input = old.input.copy(searchStart = input.searchStart, searchEnd = input.searchEnd))
        request(outside.input)
        val envelope = outside.parse()
        assertEquals(4, outside.firstClaims(envelope).size)
        assertEquals(4, envelope.directionalApplications.size)
        assertTrue(envelope.directionalApplications.all { it.searchSupport == null })
        assertNotNull(outside.firstProgram(envelope).program)
    }

    @Test
    fun `CG04 general temporal guidance does not turn a missing personal clock or calendar fact into native availability`() {
        val fixture = DateScopeInlineFixture.unknown()
        val request = request(fixture.input)
        val prompt = request.getValue("prompt") as String
        listOf(
            "Choose the temporal atom after determining the asserted effect and its restrictions.",
            "Use WHOLE_DAY only when that effect applies to the entire resolved date; " +
                "a date-only availability answer without another restriction covers that date.",
            "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; " +
                "retain their date and event context in complete condition evidence without inventing clocks or whole-day availability.",
            "Supported approximate boundaries stay INFERRED/NON_PRECISE.",
            "Known availability with a genuinely unknown restriction stays AVAILABLE/NON_PRECISE with temporal.UNKNOWN, " +
                "rather than losing its effect.",
            "calendar_context contains calendar facts, not availability or native evidence.",
            "Use source reference_at, calendar_context and search context to resolve relative dates.",
            "Bind actual questions and answers to their source scope before interpreting time.",
            "Calendar facts do not limit output dates or remove out-of-range conditions.",
            "EXPLICIT is a model interpretation, not user confirmation.",
        ).forEach { assertTrue(prompt.contains(it), "Missing general grounding contract: $it") }
        val raw = F.json(fixture.document)
        val envelope = fixture.parse()
        val unknown = fixture.firstClaims(envelope).single()
        assertEquals(IrEffect.AVAILABLE, unknown.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, unknown.temporalResolution)
        assertNull(unknown.scope)
        assertTrue(envelope.directionalApplications.isEmpty())
        assertTrue(fixture.available(envelope).isEmpty())
        assertEquals(F.snapshot(fixture.input).context, envelope.context)
        assertEquals(raw, F.json(fixture.document))
        @Suppress("UNCHECKED_CAST")
        val evidence = fixture.conditions().single()["evidence"] as List<MutableMap<String, Any?>>
        evidence.single()["source_id"] = "calendar_context"
        evidence.single()["quote"] = "2027-01-08"
        val rejected = fixture.parse()
        assertNull(fixture.firstProgram(rejected).program)
        assertTrue(fixture.available(rejected).isEmpty())
        assertTrue(
            rejected.base.base.declaration.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .quarantined
                .any { it.problem == IrProblem.EVIDENCE },
        )
        assertNotNull(
            rejected.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.second }
                .program,
        )
    }

    private fun calendarInput() =
        F.input("독립 달력 계약의 원문이에요.").copy(
            policy = N.policy,
            meetingMode = MeetingMode.EITHER,
            searchStart = LocalDate.parse("2028-01-31"),
            searchEnd = LocalDate.parse("2028-03-02"),
        )

    private fun referenceInput(): ScheduleDslOfflineInput {
        val early = Instant.parse("2028-03-01T00:30:00Z")
        val late = Instant.parse("2028-03-01T08:30:00Z")
        val old = calendarInput()
        val answer =
            InputSupplement(
                UUID(0, 12001),
                3,
                "확인",
                0,
                "조건별 시간을 확인해 주세요.",
                "조건별 답변이에요.",
                null,
                late,
                late,
            )
        return old.copy(
            timeZone = "America/Los_Angeles",
            searchStart = LocalDate.parse("2028-02-28"),
            searchEnd = LocalDate.parse("2028-03-02"),
            batchFixedAt = Instant.parse("2028-03-01T10:00:00Z"),
            inputs =
                old.inputs.mapIndexed { i, item ->
                    item.copy(
                        rawText = if (i == 0) "검토할 날짜 조건이에요." else "확인할 시간 조건이에요.",
                        acceptedAt = Instant.parse("2028-03-01T09:00:00Z"),
                        evidence = InputEvidence(3, if (i == 0) late else early, if (i == 0) null else answer),
                    )
                },
        )
    }

    private fun request(input: ScheduleDslOfflineInput) =
        fields(mapper.readValue(contract.requestBytes(N.capsule(input), input.meetingMode), Map::class.java))

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = requireNotNull(value as? Map<String, Any?>)

    private fun properties(value: Any?) = fields(fields(value)["properties"])

    private fun item(value: Any?) = fields(value)["items"]

    private fun list(value: Any?) = requireNotNull(value as? List<*>)

    private fun variants(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = fields(value)
        val reference = schema["\$ref"] as? String
        if (reference != null) return variants(fields(root["\$defs"])[reference.substringAfterLast('/')], root)
        val alternatives = schema["anyOf"] ?: schema["oneOf"]
        return if (alternatives is List<*>) alternatives.flatMap { variants(it, root) } else listOf(schema)
    }

    companion object {
        private const val CALENDAR_ROWS = """
            2028-01-31 MONDAY
            2028-02-01 TUESDAY
            2028-02-02 WEDNESDAY
            2028-02-03 THURSDAY
            2028-02-04 FRIDAY
            2028-02-05 SATURDAY
            2028-02-06 SUNDAY
            2028-02-07 MONDAY
            2028-02-08 TUESDAY
            2028-02-09 WEDNESDAY
            2028-02-10 THURSDAY
            2028-02-11 FRIDAY
            2028-02-12 SATURDAY
            2028-02-13 SUNDAY
            2028-02-14 MONDAY
            2028-02-15 TUESDAY
            2028-02-16 WEDNESDAY
            2028-02-17 THURSDAY
            2028-02-18 FRIDAY
            2028-02-19 SATURDAY
            2028-02-20 SUNDAY
            2028-02-21 MONDAY
            2028-02-22 TUESDAY
            2028-02-23 WEDNESDAY
            2028-02-24 THURSDAY
            2028-02-25 FRIDAY
            2028-02-26 SATURDAY
            2028-02-27 SUNDAY
            2028-02-28 MONDAY
            2028-02-29 TUESDAY
            2028-03-01 WEDNESDAY
        """
    }
}
