package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.ServerEvidenceWire
import com.meetme.server.coordination.adapter.output.integration.TemporalRangeEvidenceProviderContract
import com.meetme.server.coordination.adapter.output.integration.WeeklyEvidenceProviderContract
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.DayOfWeek
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** Independent invented weekday relations; admission never supplies missing days or repairs model output. */
class TemporalRangeWeekdayCompletenessTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val fullClause = "매주 월요일, 목요일, 토요일은 10:10부터 14:20까지 가능합니다."
    private val fullDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)

    @ParameterizedTest
    @ValueSource(strings = ["full", "aliases", "permuted"])
    fun `WDL01 a complete three day rule is positively checked with exact native clocks and no rewritten evidence`(style: String) {
        val (raw, lower, upper, lowerQuote, upperQuote, days) =
            when (style) {
                "aliases" ->
                    Case(
                        "화욜/금욜/일욜 06:25부터 09:35까지 가능해요.",
                        "06:25",
                        "09:35",
                        "06:25",
                        "09:35",
                        setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
                    )
                "permuted" -> Case("토요일 및 월요일 하고 목요일에는 오전 9시 반부터 오후 1시 15분까지 가능합니다.", "09:30", "13:15", "오전 9시 반", "오후 1시 15분", fullDays)
                else -> Case(fullClause, "10:10", "14:20", "10:10", "14:20", fullDays)
            }
        val f = F(raw)
        val doc = document(f, days, lower, upper, lowerQuote, upperQuote)
        val envelope = checkedAndUnchanged(f, doc)
        val app = envelope.weeklyApplications.single { it.inputId == f.first }
        assertEquals(days, app.rule.weekdays)
        assertEquals(days, app.occurrences.map { it.anchorDate.dayOfWeek }.toSet())
        assertEquals(days.size, app.occurrences.size)
        assertEquals(
            lower,
            app.rule.time.lower
                .toString(),
        )
        assertEquals(
            upper,
            app.rule.time.upper
                .toString(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", ", ", "/", "와 ", "과 ", " 및 ", "하고 "])
    fun `WDL02 finite list separators retain every stated weekday rather than selecting the first token`(separator: String) {
        val raw = listOf("월요일", "목요일", "토요일").joinToString(separator) + "에는 10:10부터 14:20까지 가능합니다."
        val f = F(raw)
        checkedAndUnchanged(f, document(f, fullDays))
    }

    @ParameterizedTest
    @ValueSource(strings = ["수요일", "수욜"])
    fun `WDL03 a single explicitly named weekday is a valid one element rule and is never expanded to all days`(weekday: String) {
        val f = F("매주 ${weekday}은 15:05부터 17:55까지 가능합니다.")
        val doc = document(f, setOf(DayOfWeek.WEDNESDAY), "15:05", "17:55", "15:05", "17:55")
        val envelope = checkedAndUnchanged(f, doc)
        assertEquals(
            setOf(DayOfWeek.WEDNESDAY),
            envelope.weeklyApplications
                .single { it.inputId == f.first }
                .rule.weekdays,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED", "UNAVAILABLE"])
    fun `WDL04 availability preference and exclusion keep their own graph roles while receiving the same list completeness check`(
        effect: String,
    ) {
        val suffix =
            when (effect) {
                "PREFERRED" -> "선호해요"
                "UNAVAILABLE" -> "불가능합니다"
                else -> "가능합니다"
            }
        val f = F("매주 월요일, 목요일, 토요일은 10:10부터 14:20까지 $suffix.")
        val doc = document(f, fullDays)
        f.condition(doc)["effect"] = effect
        if (effect != "AVAILABLE") {
            val program = program(f, doc)
            program[if (effect == "PREFERRED") "preference_root" else "exclusion_root"] = program["availability_root"]
            program["availability_root"] = null
        }
        checkedAndUnchanged(f, doc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["EXPLICIT", "INFERRED"])
    fun `WDL05 a model interpretation label cannot hide a known incomplete weekday set`(interpretation: String) {
        val f = F(fullClause)
        val doc = document(f, setOf(DayOfWeek.MONDAY))
        f.condition(doc)["interpretation"] = interpretation
        rejectedAndUnchanged(f, doc)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "subset",
            "superset",
            "all-seven",
            "missing-upper",
            "wrong-lower",
            "wrong-upper",
            "missing-proof",
            "filter",
            "single-date",
            "split",
        ],
    )
    fun `WDL06 complete weekday scope and both scalar and atomic endpoints remain mandatory`(fault: String) {
        val f = F(fullClause)
        val doc = document(f, fullDays)
        val atom = f.fields(f.condition(doc)["temporal"])
        val time = f.fields(atom["time"])
        when (fault) {
            "subset" -> atom["weekdays"] = listOf("MONDAY", "SATURDAY")
            "superset" -> atom["weekdays"] = (fullDays + DayOfWeek.TUESDAY).map { it.name }
            "all-seven" -> atom["weekdays"] = DayOfWeek.entries.map { it.name }
            "missing-upper" -> time["upper"] = null
            "wrong-lower" -> time["lower"] = "10:40"
            "wrong-upper" -> time["upper"] = "15:20"
            "missing-proof" -> time["clock_evidence"] = listOf(f.clock("10:10"))
            "filter" -> f.fields(atom["anchor_filter"]).putAll(mapOf("months" to listOf("APRIL"), "evidence" to listOf(f.whole())))
            "single-date" -> {
                f.condition(doc)["temporal"] = f.dated("10:10", "14:20", f.clock("10:10"), f.clock("14:20"))
                f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
            }
            "split" -> {
                atom["weekdays"] = listOf("MONDAY")
                val other =
                    f.condition(doc).toMutableMap().also {
                        it["id"] = "remaining-days"
                        it["temporal"] = weekly(f, setOf(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY))
                    }
                firstResult(f, doc)["conditions"] = listOf(f.condition(doc), other)
                val p = program(f, doc)
                val ref = f.fields(p["availability_root"])
                p["availability_root"] =
                    ref.toMutableMap().also {
                        it["operator"] = "ANY_OF"
                        it["claim_id"] = null
                        it["children"] =
                            listOf(ref.toMutableMap(), ref.toMutableMap().also { child -> child["claim_id"] = "remaining-days" })
                    }
            }
        }
        rejectedAndUnchanged(f, doc)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "월목토 10:10부터 14:20까지 가능합니다.",
            "월요일부터 금요일까지 10:10부터 14:20까지 가능합니다.",
            "월요일 또는 목요일 10:10부터 14:20까지 가능합니다.",
            "다음 주 월요일과 목요일 10:10부터 14:20까지 가능합니다.",
            "4월 둘째 월요일과 목요일 10:10부터 14:20까지 가능합니다.",
            "2036년 4월 14일 월요일 10:10부터 14:20까지 가능합니다.",
            "월요일, 목요일, 토요일 수업이 10:10부터 14:20까지 끝나면 가능합니다.",
        ],
    )
    fun `WDL07 unsupported date range choice and event syntax remains inconclusive without new weekday guesses`(raw: String) {
        val f = F(raw)
        inconclusiveAndUnchanged(f, document(f, setOf(DayOfWeek.MONDAY)))
    }

    @Test
    fun `WDL08 independently selected absolute date context retains scope authority over a separate weekday clause`() {
        val qualifier = "2036-04-17에만 가능합니다."
        val f = F("$qualifier\n$fullClause")
        val atom =
            f.dated("10:10", "14:20", f.clock("10:10"), f.clock("14:20")).also {
                it["local_date"] = "2036-04-17"
                it["date_evidence"] = listOf(f.selection(qualifier))
            }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.selection(qualifier), f.selection(fullClause))
        f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        inconclusiveAndUnchanged(f, doc)
    }

    @Test
    fun `WDL09 broad inherited scope is not positive proof that a local weekday list has no separate qualifier`() {
        val f = F("2036-04-17에만 가능합니다.\n$fullClause")
        inconclusiveAndUnchanged(f, document(f, setOf(DayOfWeek.THURSDAY)))
    }

    @Test
    fun `WDL10 linked supplements and explicit correction context remain outside the bounded weekday guarantee`() {
        for (f in listOf(F(fullClause, linkedAnswer = true), F("$fullClause\n정정 기록은 별도로 보존합니다."))) {
            inconclusiveAndUnchanged(f, document(f, setOf(DayOfWeek.MONDAY)))
        }
    }

    @Test
    fun `WDL11 UNKNOWN stays unresolved and receives neither inferred weekdays nor numeric endpoints`() {
        val f = F(fullClause)
        val doc = f.oldDocument(linkedMapOf("kind" to "UNKNOWN"))
        val envelope = inconclusiveAndUnchanged(f, doc)
        assertTrue(envelope.weeklyApplications.none { it.inputId == f.first })
        assertTrue(envelope.directionalApplications.none { it.submissionVersionId == f.first })
    }

    @Test
    fun `WDL12 old five retains a subset declaration while new six must reject it and old neutral bytes remain frozen`() {
        val f = F(fullClause)
        val doc = document(f, setOf(DayOfWeek.MONDAY))
        val old = f.oldParse(doc)
        assertEquals(
            setOf(DayOfWeek.MONDAY),
            old.weeklyApplications
                .single { it.inputId == f.first }
                .rule.weekdays,
        )
        assertEquals(old, ScheduleDslStructuralValidator().validateRestored(old, f.snapshot))
        rejectedAndUnchanged(f, doc)
        val default = F()
        val bytes = WeeklyEvidenceProviderContract(mapper).requestBytes(default.capsule, MeetingMode.EITHER)
        assertEquals("0bcdcb82841cf3da2c40a3f054790bf107879a1e4f345a573f53be9378a85d6f", sha(bytes))
    }

    @Test
    fun `WDL13 generic completeness instructions are identical on both native wires without source examples or schema changes`() {
        val f = F(fullClause)
        val neutral = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val ai = wire(neutral, AnalysisProvider.OPENAI)
        val gemini = wire(neutral, AnalysisProvider.GEMINI)
        val instructions = ai.path("instructions").asText()
        assertTrue(instructions.contains("Explicit weekday-list completeness:"))
        assertTrue(!instructions.contains(fullClause))
        assertEquals(
            instructions,
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertEquals(
            mapper.readTree(ai.path("input").asText()),
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            ),
        )
        val request = mapper.readTree(neutral)
        assertEquals(
            mapper.valueToTree<JsonNode>(TemporalRangeEvidenceProviderContract(mapper).responseSchema(f.snapshot)),
            request.path("schema"),
        )
    }

    private fun weekly(
        f: F,
        days: Set<DayOfWeek>,
        lower: String = "10:10",
        upper: String = "14:20",
        lowerQuote: String = "10:10",
        upperQuote: String = "14:20",
    ) = f.weekly(f.time(lower, upper, listOf(f.clock(lowerQuote), f.clock(upperQuote)))).also {
        it["weekdays"] =
            days.map { day -> day.name }
    }

    private fun document(
        f: F,
        days: Set<DayOfWeek>,
        lower: String = "10:10",
        upper: String = "14:20",
        lowerQuote: String = "10:10",
        upperQuote: String = "14:20",
    ) = f.oldDocument(weekly(f, days, lower, upper, lowerQuote, upperQuote))

    private fun checkedAndUnchanged(
        f: F,
        doc: MutableMap<String, Any?>,
    ): ScheduleDslStructuralEnvelope {
        val before = mapper.writeValueAsBytes(doc)
        val envelope = f.oldParse(doc)
        val report = TemporalRangeAdmission.assess(envelope, f.snapshot)
        assertEquals(1, report.checkedConditions)
        assertEquals(envelope, newParse(f, doc))
        assertContentEquals(before, mapper.writeValueAsBytes(doc))
        assertEquals(f.raw, f.snapshot.sourceText(f.first, "original"))
        return envelope
    }

    private fun rejectedAndUnchanged(
        f: F,
        doc: MutableMap<String, Any?>,
    ) {
        val before = mapper.writeValueAsBytes(doc)
        val envelope = f.oldParse(doc)
        assertFailsWith<IllegalArgumentException> { TemporalRangeAdmission.validate(envelope, f.snapshot) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, assertFailsWith<NaturalLanguageParserException> { newParse(f, doc) }.kind)
        assertContentEquals(before, mapper.writeValueAsBytes(doc))
    }

    private fun inconclusiveAndUnchanged(
        f: F,
        doc: MutableMap<String, Any?>,
    ): ScheduleDslStructuralEnvelope {
        val envelope = f.oldParse(doc)
        val before = mapper.writeValueAsBytes(envelope)
        val report = TemporalRangeAdmission.assess(envelope, f.snapshot)
        assertEquals(0, report.checkedConditions)
        assertTrue(report.unclassifiedConditions > 0)
        assertEquals(envelope, newParse(f, doc))
        assertContentEquals(before, mapper.writeValueAsBytes(envelope))
        return envelope
    }

    private fun newParse(
        f: F,
        doc: Map<String, Any?>,
    ) = f.newParse(
        doc + ("temporal_range_admission_version" to TemporalRangeAdmission.VERSION),
    )

    private fun firstResult(
        f: F,
        doc: MutableMap<String, Any?>,
    ) = f.fields((doc["results"] as List<*>).first())

    private fun program(
        f: F,
        doc: MutableMap<String, Any?>,
    ) = f.fields(firstResult(f, doc)["program"])

    private fun wire(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ) = mapper.readTree(
        ServerEvidenceWire.bytes(
            mapper,
            neutral,
            provider,
            if (provider ==
                AnalysisProvider.OPENAI
            ) {
                "gpt-6-luna"
            } else {
                "gemini-3.8-flash"
            },
        ),
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Case(
        val raw: String,
        val lower: String,
        val upper: String,
        val lowerQuote: String,
        val upperQuote: String,
        val days: Set<DayOfWeek>,
    )
}
