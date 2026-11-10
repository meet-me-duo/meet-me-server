package com.meetme.server.submission.domain.dsl

import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent typed recurrence/calendar expectations; no native text parsing or model-accuracy oracle. */
class WeeklyScheduleRulesTest {
    private val native = ScheduleIrV2SyntheticFixture

    @Test
    fun `WKR01 a weekly Friday rule preserves every matching date across a month boundary`() {
        val f = Fixture("2055-01-26", "2055-02-10")
        val rule = f.rule()
        val actual = f.expand(rule)
        assertEquals(listOf(LocalDate.of(2055, 1, 29), LocalDate.of(2055, 2, 5)), f.active(actual).map { it.anchorDate })
        assertEquals(
            listOf(f.window("2055-01-29T19:00", "2055-01-29T21:00"), f.window("2055-02-05T19:00", "2055-02-05T21:00")),
            f.supports(actual),
        )
        assertTrue(actual.resolved)
        assertEquals(native.first, actual.inputId)
        assertEquals("weekly", actual.conditionId)
        assertEquals(rule, actual.rule)
        assertEquals("weekly-schedule-rule-1", actual.version)
    }

    @Test
    fun `WKR02 a weekly Friday rule cannot silently stop at the December year boundary`() {
        val f = Fixture()
        val actual = f.expand(f.rule())
        assertEquals(listOf(LocalDate.of(2055, 12, 31), LocalDate.of(2056, 1, 7)), f.active(actual).map { it.anchorDate })
        assertEquals(
            listOf(f.window("2055-12-31T19:00", "2055-12-31T21:00"), f.window("2056-01-07T19:00", "2056-01-07T21:00")),
            f.supports(actual),
        )
    }

    @Test
    fun `WKR03 leap day is a real matching weekday occurrence with a complete civil day`() {
        val f = Fixture("2056-02-25", "2056-03-04")
        val rule = f.rule(setOf(DayOfWeek.TUESDAY), WeeklyTimePattern(WeeklyTimeKind.WHOLE_DAY))
        val actual = f.expand(rule)
        assertEquals(listOf(LocalDate.of(2056, 2, 29)), f.active(actual).map { it.anchorDate })
        assertEquals(listOf(f.window("2056-02-29T00:00", "2056-03-01T00:00")), f.supports(actual))
    }

    @Test
    fun `WKR04 search start is inclusive and search end is exclusive for weekly anchors`() {
        val f = Fixture("2055-12-31", "2056-01-07")
        val actual = f.expand(f.rule())
        assertEquals(listOf(LocalDate.of(2055, 12, 31)), f.active(actual).map { it.anchorDate })
        assertEquals(listOf(f.window("2055-12-31T19:00", "2055-12-31T21:00")), f.supports(actual))
    }

    @Test
    fun `WKR05 only the owner supplied month filter may narrow the owner's repeated weekdays`() {
        val f = Fixture(peerText = "Synthetic peer says 1월만 가능")
        val unfiltered = f.expand(f.rule())
        assertEquals(listOf(LocalDate.of(2055, 12, 31), LocalDate.of(2056, 1, 7)), f.active(unfiltered).map { it.anchorDate })
        val rule = f.rule().copy(filter = WeeklyAnchorFilter(months = setOf(Month.DECEMBER), evidence = listOf(f.proof())))
        val filtered = f.expand(rule)
        assertEquals(listOf(LocalDate.of(2055, 12, 31)), f.active(filtered).map { it.anchorDate })
        assertEquals(rule, filtered.rule)
    }

    @Test
    fun `WKR06 date bounds and anchor exclusions can produce a resolved empty rule without default availability`() {
        val f = Fixture("2055-01-26", "2055-02-10")
        val filter =
            WeeklyAnchorFilter(
                fromInclusive = LocalDate.of(2055, 2, 1),
                untilExclusive = LocalDate.of(2055, 2, 6),
                excludedDates = setOf(LocalDate.of(2055, 2, 5)),
                evidence = listOf(f.proof()),
            )
        val rule = f.rule().copy(filter = filter)
        val actual = f.expand(rule)
        assertTrue(actual.resolved)
        assertTrue(actual.occurrences.isEmpty())
        assertTrue(f.supports(actual).isEmpty())
        assertEquals(filter, actual.rule.filter)
    }

    @Test
    fun `WKR07 a genuine linked supplemental filter belongs to the same participant and remains a separate source`() {
        val f = Fixture(supplementText = "추가 답변: 12월의 금요일만 가능")
        val rule =
            f.rule().copy(
                filter = WeeklyAnchorFilter(months = setOf(Month.DECEMBER), evidence = listOf(f.proof(sourceId = f.supplementId))),
            )
        val actual = f.expand(rule)
        assertEquals(listOf(LocalDate.of(2055, 12, 31)), f.active(actual).map { it.anchorDate })
        assertEquals(
            f.supplementId,
            actual.rule.filter.evidence
                .single()
                .span.sourceId,
        )
        assertEquals(f.firstText, f.snapshot.sourceText(native.first, "original"))
    }

    @Test
    fun `WKR08 matching text from a different participant is not valid weekly or filter evidence`() {
        val f = Fixture(peerText = DEFAULT_TEXT)
        val peer = f.proof(native.second)
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(weekdayEvidence = listOf(peer))) }
        val foreignFilter = WeeklyAnchorFilter(months = setOf(Month.DECEMBER), evidence = listOf(peer))
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(filter = foreignFilter)) }
    }

    @Test
    fun `WKR09 missing or stale evidence cannot authorize a weekly declaration or an active date filter`() {
        val f = Fixture()
        val stale = f.proof().let { it.copy(span = it.span.copy(revision = 4)) }
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(weekdayEvidence = emptyList())) }
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(weekdayEvidence = listOf(stale))) }
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(filter = WeeklyAnchorFilter(months = setOf(Month.DECEMBER)))) }
        assertFailsWith<IllegalArgumentException> { f.expand(f.rule().copy(weekdays = emptySet())) }
        assertFailsWith<IllegalArgumentException> { WeeklyScheduleRules.expand(UUID(0, 887), "weekly", f.rule(), f.snapshot) }
    }

    @Test
    fun `WKR10 approved lunch afternoon and night baselines expand for every selected anchor`() {
        val f = Fixture("2055-12-26", "2056-01-09")
        val examples =
            listOf(
                Triple(ApprovedKoreanTimeTerm.LUNCH, "점심", "2055-12-31T11:00" to "2055-12-31T15:00"),
                Triple(ApprovedKoreanTimeTerm.PM, "오후", "2055-12-31T12:00" to "2056-01-01T00:00"),
                Triple(ApprovedKoreanTimeTerm.NIGHT, "밤", "2055-12-31T20:00" to "2056-01-01T06:00"),
            )
        examples.forEach { (term, literal, expected) ->
            val time = WeeklyTimePattern(WeeklyTimeKind.TERM, term = term, termEvidence = f.termProof(literal))
            val actual = f.expand(f.rule(time = time))
            assertEquals(f.window(expected.first, expected.second), f.supports(actual).first())
            assertEquals(2, f.active(actual).size)
        }
    }

    @Test
    fun `WKR11 known one sided clocks keep the missing civil day boundary server derived`() {
        val f = Fixture("2055-12-31", "2056-01-01")
        val after = WeeklyTimePattern(WeeklyTimeKind.BOUNDS, lower = LocalTime.of(19, 0), clockEvidence = listOf(f.proof()))
        val before = WeeklyTimePattern(WeeklyTimeKind.BOUNDS, upper = LocalTime.of(21, 0), clockEvidence = listOf(f.proof()))
        assertEquals(listOf(f.window("2055-12-31T19:00", "2056-01-01T00:00")), f.supports(f.expand(f.rule(time = after))))
        assertEquals(listOf(f.window("2055-12-31T00:00", "2055-12-31T21:00")), f.supports(f.expand(f.rule(time = before))))
    }

    @Test
    fun `WKR12 an explicitly overnight December anchor keeps its January tail under an anchor month filter`() {
        val f = Fixture("2055-12-30", "2056-01-03")
        val rule =
            f
                .rule(
                    time = f.overnight(),
                ).copy(filter = WeeklyAnchorFilter(months = setOf(Month.DECEMBER), evidence = listOf(f.proof())))
        val actual = f.expand(rule)
        assertEquals(LocalDate.of(2055, 12, 31), actual.occurrences.single().anchorDate)
        assertEquals(f.scope("2055-12-31T22:00", "2056-01-01T02:00"), actual.occurrences.single().localScope)
        assertEquals(listOf(f.window("2055-12-31T22:00", "2056-01-01T02:00")), f.supports(actual))
    }

    @Test
    fun `WKR13 the previous day anchor contributes only its overlapping tail and filters still test that anchor`() {
        val f = Fixture("2056-01-01", "2056-01-02")
        val december = WeeklyAnchorFilter(months = setOf(Month.DECEMBER), evidence = listOf(f.proof()))
        val actual = f.expand(f.rule(time = f.overnight()).copy(filter = december))
        assertEquals(LocalDate.of(2055, 12, 31), actual.occurrences.single().anchorDate)
        assertEquals(f.scope("2055-12-31T22:00", "2056-01-01T02:00"), actual.occurrences.single().localScope)
        assertEquals(listOf(f.window("2056-01-01T00:00", "2056-01-01T02:00")), f.supports(actual))
        val january = december.copy(months = setOf(Month.JANUARY))
        val empty = f.expand(f.rule(time = f.overnight()).copy(filter = january))
        assertTrue(empty.resolved)
        assertTrue(f.supports(empty).isEmpty())
    }

    @Test
    fun `WKR14 search clipping does not rewrite the stored original overnight interval`() {
        val f = Fixture("2055-12-31", "2056-01-01")
        val actual = f.expand(f.rule(time = f.overnight()))
        assertEquals(f.scope("2055-12-31T22:00", "2056-01-01T02:00"), actual.occurrences.single().localScope)
        assertEquals(listOf(f.window("2055-12-31T22:00", "2056-01-01T00:00")), f.supports(actual))
        assertEquals(1, actual.rule.time.endDayOffset)
    }

    @Test
    fun `WKR15 weekly whole civil days follow daylight saving rather than fixed 24 hour durations`() {
        val spring = Fixture("2036-03-09", "2036-03-10", zone = "America/New_York")
        val fall = Fixture("2036-11-02", "2036-11-03", zone = "America/New_York")
        listOf(spring to 23L, fall to 25L).forEach { (f, hours) ->
            val app = f.expand(f.rule(setOf(DayOfWeek.SUNDAY), WeeklyTimePattern(WeeklyTimeKind.WHOLE_DAY)))
            val window = f.supports(app).single()
            assertEquals(hours, Duration.between(window.startInclusive, window.endExclusive).toHours())
        }
    }

    @Test
    fun `WKR16 nonexistent or ambiguous daylight saving clock endpoints cannot acquire an invented offset`() {
        val spring = Fixture("2036-03-09", "2036-03-10", zone = "America/New_York")
        val fall = Fixture("2036-11-02", "2036-11-03", zone = "America/New_York")
        val springTime =
            WeeklyTimePattern(
                WeeklyTimeKind.BOUNDS,
                lower = LocalTime.of(2, 30),
                upper = LocalTime.of(3, 30),
                clockEvidence = listOf(spring.proof()),
            )
        val fallTime =
            WeeklyTimePattern(
                WeeklyTimeKind.BOUNDS,
                lower = LocalTime.of(1, 30),
                upper = LocalTime.of(3, 30),
                clockEvidence = listOf(fall.proof()),
            )
        assertFailsWith<IllegalArgumentException> { spring.expand(spring.rule(setOf(DayOfWeek.SUNDAY), springTime)) }
        assertFailsWith<IllegalArgumentException> { fall.expand(fall.rule(setOf(DayOfWeek.SUNDAY), fallTime)) }
    }

    @Test
    fun `WKR17 unknown personal time remains unresolved rather than becoming a full day or full search`() {
        val f = Fixture()
        val rule = f.rule(time = WeeklyTimePattern(WeeklyTimeKind.UNKNOWN))
        val actual = f.expand(rule)
        assertFalse(actual.resolved)
        assertTrue(f.supports(actual).isEmpty())
        assertEquals(rule, actual.rule)
    }

    @Test
    fun `WKR18 malformed temporal unions and implicit overnight rollover are rejected instead of normalized`() {
        val f = Fixture()
        val invalid =
            listOf(
                WeeklyTimePattern(WeeklyTimeKind.BOUNDS),
                WeeklyTimePattern(
                    WeeklyTimeKind.BOUNDS,
                    lower = LocalTime.of(22, 0),
                    upper = LocalTime.of(2, 0),
                    clockEvidence = listOf(f.proof()),
                ),
                f.overnight().copy(endDayOffset = 2),
                f.overnight().copy(endDayOffset = -1),
                WeeklyTimePattern(WeeklyTimeKind.WHOLE_DAY, lower = LocalTime.NOON),
                WeeklyTimePattern(WeeklyTimeKind.TERM, term = ApprovedKoreanTimeTerm.LUNCH),
                WeeklyTimePattern(
                    WeeklyTimeKind.TERM,
                    lower = LocalTime.NOON,
                    term = ApprovedKoreanTimeTerm.LUNCH,
                    termEvidence = f.termProof("점심"),
                ),
                WeeklyTimePattern(WeeklyTimeKind.UNKNOWN, upper = LocalTime.NOON),
            )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { f.expand(f.rule(time = it)) } }
    }

    @Test
    fun `WKR19 restoring reexpands the same original rule and rejects missing invented or moved occurrences`() {
        val f = Fixture()
        val actual = f.expand(f.rule())
        assertEquals(actual, WeeklyScheduleRules.restore(actual, f.snapshot))
        val first = actual.occurrences.first()
        val corruptions =
            listOf(
                actual.copy(occurrences = actual.occurrences.dropLast(1)),
                actual.copy(occurrences = actual.occurrences + first),
                actual.copy(occurrences = listOf(first.copy(anchorDate = first.anchorDate.plusDays(1))) + actual.occurrences.drop(1)),
                actual.copy(
                    occurrences =
                        listOf(first.copy(searchSupport = f.window("2055-12-31T18:00", "2055-12-31T21:00"))) + actual.occurrences.drop(1),
                ),
                actual.copy(rule = actual.rule.copy(weekdays = setOf(DayOfWeek.MONDAY))),
                actual.copy(inputId = native.second),
                actual.copy(version = "unknown-weekly-version"),
            )
        corruptions.forEach { assertFailsWith<IllegalArgumentException> { WeeklyScheduleRules.restore(it, f.snapshot) } }
    }

    @Test
    fun `WKR20 original search limits are retained and a resolved rule with no matching weekday stays empty`() {
        val start = LocalDate.of(2055, 12, 1)
        val f = Fixture(start.toString(), start.plusDays(31).toString())
        val whole = f.expand(f.rule(DayOfWeek.entries.toSet(), WeeklyTimePattern(WeeklyTimeKind.WHOLE_DAY)))
        assertTrue(whole.resolved)
        assertEquals(31, whole.occurrences.mapNotNull { it.searchSupport }.size)
        assertEquals(1024, WeeklyScheduleRules.MAX_WINDOWS)
        val tooLong = Fixture(start.toString(), start.plusDays(32).toString())
        assertFailsWith<IllegalArgumentException> { tooLong.expand(tooLong.rule()) }
        val short = Fixture("2056-01-01", "2056-01-02")
        val empty = short.expand(short.rule(setOf(DayOfWeek.MONDAY)))
        assertTrue(empty.resolved)
        assertTrue(f.supports(empty).isEmpty())
    }

    private inner class Fixture(
        start: String = "2055-12-25",
        end: String = "2056-01-09",
        val zone: String = "Asia/Seoul",
        val firstText: String = DEFAULT_TEXT,
        peerText: String = "Synthetic independent peer without owner date filters",
        supplementText: String? = null,
    ) {
        val supplementId = "supplement:${UUID(0, 886)}"
        val snapshot: ScheduleEvidenceSnapshot
        private val catalog: ServerEvidenceCatalog

        init {
            val old = native.snapshot()
            val texts =
                mapOf(
                    native.first to
                        (
                            mapOf("original" to firstText) +
                                if (supplementText ==
                                    null
                                ) {
                                    emptyMap()
                                } else {
                                    mapOf(supplementId to supplementText)
                                }
                        ),
                    native.second to mapOf("original" to peerText),
                )
            val inputs =
                old.context.inputs.map { input ->
                    input.copy(
                        sources =
                            texts.getValue(input.submissionVersionId).map { (id, text) ->
                                IrSourceBinding(
                                    id,
                                    if (id == "original") IrSourceKind.ORIGINAL else IrSourceKind.SUPPLEMENT,
                                    3,
                                    text.codePointCount(0, text.length),
                                    IrEvidenceBindings.sha256(text),
                                    native.reference,
                                    if (id == "original") null else UUID(0, 886),
                                    if (id == "original") null else 0,
                                    if (id == "original") null else firstText.codePointCount(0, firstText.length),
                                )
                            },
                    )
                }
            snapshot =
                ScheduleEvidenceSnapshot(
                    old.context.copy(
                        timeZone = zone,
                        searchStart = LocalDate.parse(start),
                        searchEnd = LocalDate.parse(end),
                        inputs = inputs,
                    ),
                    texts,
                )
            catalog = ServerEvidenceCatalog.create(snapshot)
        }

        fun proof(
            inputId: UUID = native.first,
            sourceId: String = "original",
        ): LocatedLiteralEvidence {
            val text = requireNotNull(snapshot.sourceText(inputId, sourceId))
            val entry =
                catalog.entries.first {
                    it.inputId == inputId && it.kind == ServerEvidenceKind.CONTEXT && it.span.sourceId == sourceId && it.text == text
                }
            return catalog.resolve(inputId, entry.id)
        }

        fun termProof(literal: String): LocatedLiteralEvidence =
            catalog.entries.first { it.inputId == native.first && it.kind == ServerEvidenceKind.TERM && it.text == literal }.let {
                catalog.resolve(native.first, it.id)
            }

        fun rule(
            weekdays: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY),
            time: WeeklyTimePattern =
                WeeklyTimePattern(
                    WeeklyTimeKind.BOUNDS,
                    lower = LocalTime.of(19, 0),
                    upper = LocalTime.of(21, 0),
                    clockEvidence = listOf(proof()),
                ),
        ) = WeeklyScheduleRule(weekdays, time, listOf(proof()))

        fun overnight() =
            WeeklyTimePattern(
                WeeklyTimeKind.BOUNDS,
                lower = LocalTime.of(22, 0),
                upper = LocalTime.of(2, 0),
                endDayOffset = 1,
                clockEvidence = listOf(proof()),
            )

        fun expand(rule: WeeklyScheduleRule) = WeeklyScheduleRules.expand(native.first, "weekly", rule, snapshot)

        fun active(application: WeeklyScheduleApplication) = application.occurrences.filter { it.searchSupport != null }

        fun supports(application: WeeklyScheduleApplication): List<InstantTimeRange> =
            application.occurrences.mapNotNull { it.searchSupport }

        fun window(
            start: String,
            end: String,
        ) = InstantTimeRange(
            LocalDateTime.parse(start).atZone(ZoneId.of(zone)).toInstant(),
            LocalDateTime.parse(end).atZone(ZoneId.of(zone)).toInstant(),
        )

        fun scope(
            start: String,
            end: String,
        ) = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))
    }

    companion object {
        private const val DEFAULT_TEXT =
            "🙂 Synthetic weekly MON TUE WED THU FRI SAT SUN 19:00 21:00 22:00 02:00 02:30 01:30 03:30 점심 저녁 오후 밤 " +
                "1월 12월 2055-02-01 2055-02-05 2055-02-06 2055-12-31 2056-01-07 literal owner selectors"
    }
}
