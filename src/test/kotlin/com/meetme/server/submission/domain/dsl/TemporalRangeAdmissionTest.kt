package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** Positively supported native forms only; passing an inconclusive form is not a claim of semantic verification. */
class TemporalRangeAdmissionTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @ParameterizedTest
    @CsvSource(
        value = [
            "오후 4시 20분부터 8시 40분까지 가능합니다.|16:20|20:40|오후 4시 20분|8시 40분",
            "16:35~20:55 가능.|16:35|20:55|16:35|20:55",
            "오전 9시 반부터 11시 20분까지 가능합니다.|09:30|11:20|오전 9시 반|11시 20분",
            "오후 4시 20분 이후부터 8시 40분까지 가능합니다.|16:20|20:40|오후 4시 20분|8시 40분",
        ],
        delimiter = '|',
    )
    fun `TRA01 held out supported native forms retain both exact boundaries on every search day`(
        raw: String,
        lower: String,
        upper: String,
        lowerText: String,
        upperText: String,
    ) {
        val f = F(raw)
        val envelope = f.oldParse(f.oldDocument(f.weekly(f.time(lower, upper, listOf(f.clock(lowerText), f.clock(upperText))))))
        val frozen = mapper.writeValueAsString(envelope)
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        val app = envelope.weeklyApplications.single()
        assertEquals(DayOfWeek.entries.toSet(), app.rule.weekdays)
        assertEquals((14..20).map { LocalDate.of(2036, 4, it) }, app.occurrences.map { it.anchorDate })
        assertEquals(
            List(7) { lower },
            app.occurrences.map {
                it.localScope.start!!
                    .toLocalTime()
                    .toString()
            },
        )
        assertEquals(
            List(7) { upper },
            app.occurrences.map {
                it.localScope.end!!
                    .toLocalTime()
                    .toString()
            },
        )
        assertEquals(frozen, mapper.writeValueAsString(envelope))
        assertEquals(raw, f.snapshot.sourceText(f.first, "original"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `TRA02 selecting one native endpoint cannot hide the other through a one sided weekly declaration`(upperOnly: Boolean) {
        val f = F()
        val time = if (upperOnly) f.time(null, "20:40", listOf(f.clock("8시 40분"))) else f.time("16:20", null, listOf(f.clock("오후 4시 20분")))
        invalid(f, f.oldDocument(f.weekly(time)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["after", "before", "two-bounds"])
    fun `TRA03 clock only evidence cannot pin a supported standalone range to one permitted search date`(kind: String) {
        val f = F()
        val atom = f.dated(lower = if (kind == "before") null else "16:20", upper = if (kind == "after") null else "20:40")
        val doc = f.oldDocument(atom)
        if (kind == "two-bounds") f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        invalid(f, doc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["weekdays", "filter", "lower-value", "upper-value", "missing-upper-proof", "broad-proof"])
    fun `TRA04 the effective scope values and distinct atomic endpoint witnesses all remain mandatory`(fault: String) {
        val f = F()
        val atom = f.weekly()
        val time = f.fields(atom["time"])
        when (fault) {
            "weekdays" -> atom["weekdays"] = listOf("MONDAY")
            "filter" -> f.fields(atom["anchor_filter"]).putAll(mapOf("months" to listOf("APRIL"), "evidence" to listOf(f.whole())))
            "lower-value" -> time["lower"] = "15:20"
            "upper-value" -> time["upper"] = "21:40"
            "missing-upper-proof" -> time["clock_evidence"] = listOf(f.clock("오후 4시 20분"))
            "broad-proof" -> time["clock_evidence"] = listOf(f.whole())
        }
        invalid(f, f.oldDocument(atom))
    }

    @Test
    fun `TRA05 an upper endpoint appearing only as general meaning evidence is not an effective clock role`() {
        val f = F()
        val doc = f.oldDocument(f.weekly(f.time(clocks = listOf(f.clock("오후 4시 20분")))))
        f.condition(doc)["evidence"] = listOf(f.whole(), f.clock("8시 40분"))
        invalid(f, doc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["WHOLE_DAY", "APPROVED_TERM", "RAW_SCOPE"])
    fun `TRA06 exact clause context blocks hiding both endpoints through another known atom kind`(kind: String) {
        val f = F()
        val atom: MutableMap<String, Any?> =
            when (kind) {
                "WHOLE_DAY" -> linkedMapOf<String, Any?>("kind" to kind, "local_date" to "2036-04-14", "date_evidence" to listOf(f.whole()))
                "APPROVED_TERM" ->
                    linkedMapOf(
                        "kind" to kind,
                        "term" to "PM",
                        "local_date" to "2036-04-14",
                        "term_evidence" to f.selection("오후", ServerEvidenceKind.TERM),
                        "date_evidence" to listOf(f.whole()),
                        "model_scope" to null,
                    )
                else ->
                    linkedMapOf(
                        "kind" to kind,
                        "scope" to mapOf("dimension" to "TIME", "start" to "2036-04-14T16:20", "end" to "2036-04-14T20:40"),
                    )
            }
        val doc = f.oldDocument(atom)
        if (kind == "RAW_SCOPE") f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        invalid(f, doc)
    }

    @Test
    fun `TRA07 UNKNOWN remains unresolved and admission never fills missing clocks`() {
        val f = F()
        val envelope = f.oldParse(f.oldDocument(linkedMapOf("kind" to "UNKNOWN")))
        val frozen = mapper.writeValueAsString(envelope)
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        assertTrue(envelope.weeklyApplications.isEmpty())
        assertTrue(envelope.directionalApplications.none { it.submissionVersionId == f.first })
        assertTrue(envelope.directionalApplications.any { it.submissionVersionId == f.second })
        assertEquals(
            null,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertEquals(frozen, mapper.writeValueAsString(envelope))
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "오후 4시 20분 이후에 가능합니다.|16:20|오후 4시 20분",
            "오후 2시 5분부터 4시 10분까지 수업이 있고 오후 5시 25분 이후 가능합니다.|17:25|오후 5시 25분",
            "오후 2시부터 4시까지 가능하다는 말은 철회합니다. 정정하면 오후 5시 25분 이후 가능합니다.|17:25|오후 5시 25분",
        ],
        delimiter = '|',
    )
    fun `TRA08 one sided event and correction forms preserve compatibility without a verification claim`(
        raw: String,
        lower: String,
        proof: String,
    ) {
        val f = F(raw)
        val envelope = f.oldParse(f.oldDocument(f.weekly(f.time(lower, null, listOf(f.clock(proof))))))
        val frozen = mapper.writeValueAsString(envelope)
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        assertEquals(frozen, mapper.writeValueAsString(envelope))
    }

    @Test
    fun `TRA09 explicit date and bare weekday scope are outside standalone dateless coverage and are not broadened`() {
        val f = F("2036-04-16 오후 4시 20분부터 8시 40분까지 가능합니다.")
        val dated = f.dated(upper = "20:40").also { it["local_date"] = "2036-04-16" }
        val doc = f.oldDocument(dated)
        f.condition(doc)["temporal_resolution"] = "PRECISE_INTERVAL"
        val envelope = f.oldParse(doc)
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        assertEquals(
            LocalDate.of(2036, 4, 16),
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .scope!!
                .start!!
                .toLocalDate(),
        )
        val weekday = F("목요일 오후 4시 20분부터 8시 40분까지 가능합니다.")
        val atom = weekday.weekly().also { it["weekdays"] = listOf("THURSDAY") }
        val weekly = weekday.oldParse(weekday.oldDocument(atom))
        TemporalRangeAdmission.validate(weekly, weekday.snapshot)
        assertEquals(
            setOf(DayOfWeek.THURSDAY),
            weekly.weeklyApplications
                .single()
                .rule.weekdays,
        )
    }

    @Test
    fun `TRA10 an actually linked upper correction must not be forced back to the old standalone original upper`() {
        val f = F(linkedAnswer = true)
        val atom = f.weekly(f.time("16:20", "19:50", listOf(f.clock("오후 4시 20분"), f.clock("오후 7시 50분"))))
        val envelope = f.oldParse(f.oldDocument(atom))
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        assertEquals(
            "19:50",
            envelope.weeklyApplications
                .single()
                .rule.time.upper
                .toString(),
        )
        assertEquals(
            2,
            envelope.weeklyApplications
                .single()
                .rule.time.clockEvidence
                .map { it.span.sourceId }
                .toSet()
                .size,
        )
        assertEquals(F.RAW, f.snapshot.sourceText(f.first, "original"))
    }

    @Test
    fun `TRA11 old response five structural restoration is not retroactively subject to the new admission meaning`() {
        val f = F()
        val old = f.oldParse(f.oldDocument(f.dated()))
        assertEquals(old, ScheduleDslStructuralValidator().validateRestored(old, f.snapshot))
        assertEquals(
            TemporalDirection.AFTER,
            old.directionalApplications
                .single { it.submissionVersionId == f.first && it.declaration.conditionId == "range" }
                .declaration.direction,
        )
        assertEquals(
            "00:00",
            old.directionalApplications
                .single { it.submissionVersionId == f.first && it.declaration.conditionId == "range" }
                .resolvedScope.end!!
                .toLocalTime()
                .toString(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "16:35부터 20:55까지가 좋아요.|16:35|20:55|16:35|20:55|PREFERRED",
            "오전 9시 반부터 11시 20분까지 불가능합니다.|09:30|11:20|오전 9시 반|11시 20분|UNAVAILABLE",
        ],
        delimiter = '|',
    )
    fun `TRA12 standalone preference and negative ranges preserve their own graph role and exact endpoints`(
        raw: String,
        lower: String,
        upper: String,
        lowerText: String,
        upperText: String,
        effect: String,
    ) {
        val f = F(raw)
        val doc = f.oldDocument(f.weekly(f.time(lower, upper, listOf(f.clock(lowerText), f.clock(upperText)))))
        f.condition(doc)["effect"] = effect
        @Suppress("UNCHECKED_CAST")
        val result = (doc["results"] as List<MutableMap<String, Any?>>).first()
        val program = f.fields(result["program"])
        val ref = program["availability_root"]
        program["availability_root"] = null
        program[if (effect == "PREFERRED") "preference_root" else "exclusion_root"] = ref
        val envelope = f.oldParse(doc)
        TemporalRangeAdmission.validate(envelope, f.snapshot)
        assertEquals(
            effect,
            envelope.base.base.declaration.claims.results
                .first()
                .conditions
                .single()
                .effect.name,
        )
        assertEquals(
            lower,
            envelope.weeklyApplications
                .single()
                .rule.time.lower
                .toString(),
        )
        assertEquals(
            upper,
            envelope.weeklyApplications
                .single()
                .rule.time.upper
                .toString(),
        )
    }

    @Test
    fun `TRA13 a separate weekday exception keeps its own root while the standalone base retains all seven days`() {
        val exception = "화요일은 불가능합니다."
        val f = F("${F.RAW}\n$exception")
        val doc = f.oldDocument()
        val base = f.condition(doc)
        val ban = base.toMutableMap()
        val atom =
            f.weekly(f.time(null, null, emptyList(), "WHOLE_DAY")).also {
                it["weekdays"] = listOf("TUESDAY")
                it["weekday_evidence"] = listOf(f.selection(exception))
            }
        ban.putAll(
            mapOf(
                "id" to "tuesday-block",
                "effect" to "UNAVAILABLE",
                "evidence" to listOf(f.selection(exception)),
                "temporal" to atom,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val result = (doc["results"] as List<MutableMap<String, Any?>>).first()
        result["conditions"] = listOf(base, ban)
        val program = f.fields(result["program"])
        program["exclusion_root"] =
            f.fields(program["availability_root"]).toMutableMap().also {
                it["claim_id"] = "tuesday-block"
                it["evidence"] = listOf(f.selection(exception))
            }
        val valid = f.oldParse(doc)
        TemporalRangeAdmission.validate(valid, f.snapshot)
        assertEquals(
            DayOfWeek.entries.toSet(),
            valid.weeklyApplications
                .single { it.conditionId == "range" }
                .rule.weekdays,
        )
        assertEquals(
            setOf(DayOfWeek.TUESDAY),
            valid.weeklyApplications
                .single { it.conditionId == "tuesday-block" }
                .rule.weekdays,
        )
        val combined =
            f.weekly().also {
                it["weekdays"] =
                    DayOfWeek.entries.filter { day -> day != DayOfWeek.TUESDAY }.map { day -> day.name }
                // The new guard can prove this scope role is only the standalone clock clause.
                it["weekday_evidence"] = listOf(f.selection(F.RAW))
            }
        invalid(f, f.oldDocument(combined))
    }

    @Test
    fun `TRA14 broad scope context with a separate weekday qualifier remains inconclusive and unchanged`() {
        val f = F("${F.RAW}\n화요일은 불가능합니다.")
        val atom =
            f.weekly().also {
                it["weekdays"] = DayOfWeek.entries.filter { day -> day != DayOfWeek.TUESDAY }.map { day -> day.name }
                // Preserve the original TRA13 counterexample's whole-input scope selection.
                it["weekday_evidence"] = listOf(f.whole())
            }
        val envelope = f.oldParse(f.oldDocument(atom))
        val frozen = mapper.writeValueAsString(envelope)

        val report = TemporalRangeAdmission.assess(envelope, f.snapshot)

        assertEquals(0, report.checkedConditions)
        assertTrue(report.unclassifiedConditions > 0)
        assertEquals(
            DayOfWeek.entries.toSet() - DayOfWeek.TUESDAY,
            envelope.weeklyApplications
                .single()
                .rule.weekdays,
        )
        assertEquals(frozen, mapper.writeValueAsString(envelope))
        assertEquals(f.raw, f.snapshot.sourceText(f.first, "original"))
    }

    @Test
    fun `TRA15 broad meaning cannot replace a directional date role that selected only the clock clause`() {
        val f = F("2036-04-16에만 가능합니다.\n${F.RAW}")
        val atom = f.dated().also { it["date_evidence"] = listOf(f.selection(F.RAW)) }
        val doc = f.oldDocument(atom)
        f.condition(doc)["evidence"] = listOf(f.whole())
        val envelope = f.oldParse(doc)
        val own = envelope.directionalApplications.single { it.submissionVersionId == f.first && it.declaration.conditionId == "range" }
        assertEquals(listOf(f.catalog.entries[f.selection(F.RAW)].span), own.declaration.dateEvidence.map { it.span })
        val frozen = mapper.writeValueAsString(envelope)

        assertFailsWith<IllegalArgumentException> { TemporalRangeAdmission.validate(envelope, f.snapshot) }

        assertEquals(frozen, mapper.writeValueAsString(envelope))
    }

    private fun invalid(
        f: F,
        document: MutableMap<String, Any?>,
    ) {
        val envelope = f.oldParse(document)
        val frozen = mapper.writeValueAsString(envelope)
        assertFailsWith<IllegalArgumentException> { TemporalRangeAdmission.validate(envelope, f.snapshot) }
        assertEquals(frozen, mapper.writeValueAsString(envelope))
    }
}
