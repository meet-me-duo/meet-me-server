package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** TRS: sentence boundaries do not erase a date context actually selected by the same owning condition. */
class TemporalRangeScopeContextTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @ParameterizedTest
    @CsvSource(
        value = [
            "2036-04-16에만 가능합니다.|2036-04-16",
            "검색 기간의 마지막 날에만 가능합니다.|2036-04-20",
        ],
        delimiter = '|',
    )
    fun `TRS01 independently selected date and time sentences preserve an inherited specific date without widening`(
        qualifier: String,
        date: String,
    ) {
        val f = F("$qualifier\n${F.RAW}")
        val atom =
            f.dated(upper = "20:40").also {
                it["local_date"] = date
                it["date_evidence"] = listOf(f.selection(qualifier))
            }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.selection(qualifier), f.selection(F.RAW))
        f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        val envelope = f.oldParse(doc)

        inconclusiveAndUnchanged(envelope, f)

        val scope =
            envelope.calculationProjection.claims.results
                .single { it.submissionVersionId == f.first }
                .conditions
                .single()
                .scope!!
        assertEquals(LocalDate.parse(date), scope.start!!.toLocalDate())
        assertEquals("16:20", scope.start!!.toLocalTime().toString())
        assertEquals("20:40", scope.end!!.toLocalTime().toString())
        assertTrue(envelope.weeklyApplications.none { it.inputId == f.first })
    }

    @Test
    fun `TRS02 an owned whole context containing a separate date qualifier is inconclusive rather than demonstrably dateless`() {
        val f = F("2036-04-16에만 가능합니다.\n${F.RAW}")
        val atom = f.dated(upper = "20:40").also { it["local_date"] = "2036-04-16" }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.whole())
        f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        val envelope = f.oldParse(doc)

        inconclusiveAndUnchanged(envelope, f)

        assertEquals(
            LocalDate.of(2036, 4, 16),
            envelope.calculationProjection.claims.results
                .single {
                    it.submissionVersionId == f.first
                }.conditions
                .single()
                .scope!!
                .start!!
                .toLocalDate(),
        )
    }

    @Test
    fun `TRS03 a separately owned weekday qualifier preserves its recurring weekday and both clocks`() {
        val qualifier = "목욜에만 가능합니다."
        val f = F("$qualifier\n${F.RAW}")
        val atom =
            f.weekly().also {
                it["weekdays"] = listOf("THURSDAY")
                it["weekday_evidence"] = listOf(f.selection(qualifier))
            }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.selection(qualifier), f.selection(F.RAW))
        val envelope = f.oldParse(doc)

        inconclusiveAndUnchanged(envelope, f)

        val own = envelope.weeklyApplications.single { it.inputId == f.first }
        assertEquals(setOf(DayOfWeek.THURSDAY), own.rule.weekdays)
        assertEquals(listOf(LocalDate.of(2036, 4, 17)), own.occurrences.map { it.anchorDate })
        assertEquals(
            "16:20",
            own.rule.time.lower
                .toString(),
        )
        assertEquals(
            "20:40",
            own.rule.time.upper
                .toString(),
        )
    }

    @Test
    fun `TRS04 node context alone cannot lend another sentence to a clock only condition date role`() {
        val f = F("${F.RAW}\n화요일은 불가능합니다.")
        val atom = f.dated(upper = "20:40").also { it["date_evidence"] = listOf(f.selection(F.RAW)) }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.selection(F.RAW))
        f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        // The fixture's availability node still selects the whole input; its broad context grants no claim/date role.
        val envelope = f.oldParse(doc)
        val frozen = mapper.writeValueAsString(envelope)

        assertFailsWith<IllegalArgumentException> { TemporalRangeAdmission.validate(envelope, f.snapshot) }

        assertEquals(frozen, mapper.writeValueAsString(envelope))
    }

    private fun inconclusiveAndUnchanged(
        envelope: ScheduleDslStructuralEnvelope,
        f: F,
    ) {
        val frozen = mapper.writeValueAsString(envelope)
        val report = TemporalRangeAdmission.assess(envelope, f.snapshot)
        assertEquals(0, report.checkedConditions, "A selected date context keeps this outside proven dateless coverage")
        assertTrue(report.unclassifiedConditions > 0)
        assertEquals(frozen, mapper.writeValueAsString(envelope))
        assertEquals(f.raw, f.snapshot.sourceText(f.first, "original"))
    }
}
