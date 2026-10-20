package com.meetme.server.precheck.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PeriodClarificationPolicyTest {
    private val policy = PeriodClarificationPolicy()

    @Test
    fun `finite confidence below probability scale limit remains evidence rather than a gate`() {
        val evidence = certain(ClockPeriod.AM).copy(confidence = "1e-65".toBigDecimal())

        assertEquals(ClockPeriod.AM, evidence.uniqueChoice())
        assertEquals("1e-65".toBigDecimal(), evidence.confidence)
    }

    @Test
    fun `raw unique choice wins without a confidence threshold or decimal rewriting`() {
        val probabilities =
            mapOf(
                ClockPeriod.AM to BigDecimal("0.7000"),
                ClockPeriod.PM to BigDecimal("0.1000"),
                ClockPeriod.NOT_STATED to BigDecimal("0.1000"),
                ClockPeriod.UNRESOLVED to BigDecimal("0.1000"),
            )
        val result = ClockChoice(ClockPeriod.AM, probabilities, BigDecimal("0.01"))

        assertEquals(ClockPeriod.AM, result.uniqueChoice())
        assertEquals(probabilities, result.probabilities)
        assertEquals(BigDecimal("0.7000"), result.probabilities[ClockPeriod.AM])
        assertEquals(BigDecimal("0.01"), result.confidence)
    }

    @Test
    fun `a valid relevance tie has no unique choice`() {
        assertNull(relevanceTie().uniqueChoice())
    }

    @Test
    fun `a valid period tie has no unique choice`() {
        assertNull(periodTie().uniqueChoice())
    }

    @Test
    fun `returned choice outside maximum set is rejected rather than repaired`() {
        val invalid = certain(ClockPeriod.PM).copy(choice = ClockPeriod.NOT_STATED)

        assertThrows(IllegalArgumentException::class.java) { invalid.uniqueChoice() }
    }

    @Test
    fun `choice requires probabilities for every enum option`() {
        val invalid = certain(ClockPeriod.AM).copy(probabilities = mapOf(ClockPeriod.AM to BigDecimal.ONE))

        assertThrows(IllegalArgumentException::class.java) { invalid.uniqueChoice() }
    }

    @Test
    fun `REL-01 both missing endpoints survive aggregation`() {
        val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(1),
                candidates,
                candidates.associate { it.id to certain(ClockRelevance.RELEVANT) },
                candidates.associate { it.id to certain(ClockPeriod.NOT_STATED) },
            )

        assertEquals(PeriodClarificationOutcome.ASK_PERIOD, result.outcome)
        assertEquals(candidates, result.missingPeriods)
        assertTrue(result.heldTargets.isEmpty())
        assertPreserved(1, result)
    }

    @Test
    fun `REL-43 a clear Sunday must not erase missing Friday period`() {
        val candidates = PeriodClarificationFixtures.candidates(43, "5시" to 5, "8시" to 8)
        val periods = mapOf("q01" to certain(ClockPeriod.NOT_STATED), "q02" to certain(ClockPeriod.PM))
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(43),
                candidates,
                candidates.associate { it.id to certain(ClockRelevance.RELEVANT) },
                periods,
            )

        assertEquals(PeriodClarificationOutcome.ASK_PERIOD, result.outcome)
        assertEquals(listOf(candidates.first()), result.missingPeriods)
        assertEquals(periods, result.periods)
        assertEquals(candidates, result.candidates)
        assertPreserved(43, result)
    }

    @Test
    fun `REL-14 background clock never creates a missing period question`() {
        val candidates = PeriodClarificationFixtures.candidates(14, "7시 반" to 7, "8시" to 8)
        val relevance =
            mapOf(
                "q01" to certain(ClockRelevance.BACKGROUND),
                "q02" to certain(ClockRelevance.RELEVANT),
            )
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(14),
                candidates,
                relevance,
                mapOf("q02" to certain(ClockPeriod.NOT_STATED)),
            )

        assertEquals(listOf(candidates.last()), result.missingPeriods)
        assertEquals(relevance, result.relevance)
        assertEquals(candidates, result.candidates)
        assertPreserved(14, result)
    }

    @Test
    fun `selection unresolved holds one target while preserving another known missing target`() {
        val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(1),
                candidates,
                mapOf("q01" to certain(ClockRelevance.UNRESOLVED), "q02" to certain(ClockRelevance.RELEVANT)),
                mapOf("q02" to certain(ClockPeriod.NOT_STATED)),
            )

        assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
        assertEquals(setOf("q01"), result.heldTargets)
        assertEquals(listOf(candidates.last()), result.missingPeriods)
        assertNull(result.technicalFailure)
        assertPreserved(1, result)
    }

    @Test
    fun `selection tie holds without losing independently known missing period`() {
        val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(1),
                candidates,
                mapOf("q01" to relevanceTie(), "q02" to certain(ClockRelevance.RELEVANT)),
                mapOf("q02" to certain(ClockPeriod.NOT_STATED)),
            )

        assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
        assertEquals(setOf("q01"), result.heldTargets)
        assertEquals(listOf(candidates.last()), result.missingPeriods)
    }

    @Test
    fun `period tie and unresolved are HOLD rather than a question or a chosen period`() {
        for (unknown in listOf(periodTie(), certain(ClockPeriod.UNRESOLVED))) {
            val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
            val result =
                policy.aggregate(
                    PeriodClarificationFixtures.input(1),
                    candidates,
                    candidates.associate { it.id to certain(ClockRelevance.RELEVANT) },
                    mapOf("q01" to unknown, "q02" to certain(ClockPeriod.NOT_STATED)),
                )

            assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
            assertEquals(setOf("q01"), result.heldTargets)
            assertEquals(listOf(candidates.last()), result.missingPeriods)
            assertNull(result.technicalFailure)
        }
    }

    @Test
    fun `fixed 24 hour anchors need relevance but no period choice`() {
        val candidates = PeriodClarificationFixtures.candidates(9, "19시" to 19, "22시" to 22, "20시" to 20)
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(9),
                candidates,
                candidates.associate { it.id to certain(ClockRelevance.RELEVANT) },
                emptyMap(),
            )

        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertTrue(result.periods.isEmpty())
        assertTrue(result.missingPeriods.isEmpty())
        assertPreserved(9, result)
    }

    @Test
    fun `all background is no period question and never whole input clearance`() {
        val candidates = PeriodClarificationFixtures.candidates(14, "7시 반" to 7, "8시" to 8)
        val result =
            policy.aggregate(
                PeriodClarificationFixtures.input(14),
                candidates,
                candidates.associate { it.id to certain(ClockRelevance.BACKGROUND) },
                emptyMap(),
            )

        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertEquals(candidates, result.candidates)
        assertPreserved(14, result)
    }

    @Test
    fun `clockless original conditions survive without semantic regex categorization`() {
        for (id in listOf(5, 6, 11, 27, 47)) {
            val result = policy.aggregate(PeriodClarificationFixtures.input(id), emptyList(), emptyMap(), emptyMap())

            assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome, "original id=$id")
            assertTrue(result.missingPeriods.isEmpty())
            assertPreserved(id, result)
        }
    }

    @Test
    fun `aggregate rejects missing and extra relevance identifiers`() {
        val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
        for (relevance in listOf(
            mapOf("q01" to certain(ClockRelevance.RELEVANT)),
            candidates.associate { it.id to certain(ClockRelevance.RELEVANT) } +
                ("q03" to certain(ClockRelevance.BACKGROUND)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                policy.aggregate(
                    PeriodClarificationFixtures.input(1),
                    candidates,
                    relevance,
                    candidates.associate { it.id to certain(ClockPeriod.PM) },
                )
            }
        }
    }

    @Test
    fun `aggregate rejects missing or extraneous period identifiers`() {
        val candidates = PeriodClarificationFixtures.candidates(14, "7시 반" to 7, "8시" to 8)
        val relevance = mapOf("q01" to certain(ClockRelevance.BACKGROUND), "q02" to certain(ClockRelevance.RELEVANT))
        for (periods in listOf(
            emptyMap(),
            mapOf("q01" to certain(ClockPeriod.PM), "q02" to certain(ClockPeriod.NOT_STATED)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                policy.aggregate(PeriodClarificationFixtures.input(14), candidates, relevance, periods)
            }
        }
    }

    @Test
    fun `aggregate rejects duplicate candidate identifiers and fabricated source spans`() {
        val candidates = PeriodClarificationFixtures.candidates(1, "6시" to 6, "9시" to 9)
        for (invalid in listOf(
            listOf(candidates.first(), candidates.last().copy(id = "q01")),
            listOf(candidates.first().copy(start = 0), candidates.last()),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                policy.aggregate(
                    PeriodClarificationFixtures.input(1),
                    invalid,
                    invalid.associate { it.id to certain(ClockRelevance.RELEVANT) },
                    invalid.associate { it.id to certain(ClockPeriod.PM) },
                )
            }
        }
    }

    private fun assertPreserved(
        id: Int,
        result: PeriodClarificationResult,
    ) {
        assertEquals(PeriodClarificationFixtures.input(id), result.originalInput)
        assertEquals(result.originalInput, result.downstreamInput)
        assertFalse(result.hasWholeClearAuthority)
    }
}

internal inline fun <reified T : Enum<T>> certain(value: T): ClockChoice<T> =
    ClockChoice(
        value,
        enumValues<T>().associateWith { if (it == value) BigDecimal.ONE else BigDecimal.ZERO },
        BigDecimal("0.01"),
    )

internal fun relevanceTie(): ClockChoice<ClockRelevance> =
    ClockChoice(
        ClockRelevance.RELEVANT,
        mapOf(
            ClockRelevance.RELEVANT to BigDecimal("0.5"),
            ClockRelevance.BACKGROUND to BigDecimal("0.5"),
            ClockRelevance.UNRESOLVED to BigDecimal.ZERO,
        ),
        BigDecimal("0.99"),
    )

internal fun periodTie(): ClockChoice<ClockPeriod> =
    ClockChoice(
        ClockPeriod.AM,
        mapOf(
            ClockPeriod.AM to BigDecimal("0.5"),
            ClockPeriod.PM to BigDecimal("0.5"),
            ClockPeriod.NOT_STATED to BigDecimal.ZERO,
            ClockPeriod.UNRESOLVED to BigDecimal.ZERO,
        ),
        BigDecimal("0.99"),
    )

internal object PeriodClarificationFixtures {
    // Exact current parent-provided development48 strings; no rewritten or newly authored natural input.
    // Received-text SHA db2be1e3aa547e0837ea16b1143b67219d5fc37a2aeef11b42268b2d75fa6e50.
    // This is a retransmission fingerprint, not the lost original evaluation-file fingerprint.
    private val originals =
        mapOf(
            1 to "화목은 수업 끝나고 6시부터 돼요. 9시 전에는 끝나면 좋겠어요",
            3 to "이번 주 야간이라 아침에 퇴근해요. 수요일이랑 목요일 오후 3~5시 사이면 괜찮아요",
            5 to "저는 프리랜서라 웬만하면 맞출 수 있어요! 화요일 오후랑 금요일 오전만 피해주세요",
            6 to "토요일 아무 때나 가능",
            9 to "월~금 19시에서 22시 사이 가능합니다. 단, 수요일은 20시부터 가능합니다.",
            11 to "화요일 쉬는 날이라 그날이면 좋아요. 너무 이른 시간만 아니면 됩니다",
            12 to "11/5 14:00~17:00, 11/6 10:00~12:00 가능합니다.",
            14 to "퇴근하고 집에 도착하면 보통 7시 반이에요. 조금 여유 있게 8시부터 가능하고 목요일은 운동 가서 안 됩니다",
            15 to "토 오전 10~12 / 일 오후 2~5",
            20 to "4일 오후 4시부터 6시까지 됩니다. 다른 날은 아직 근무표가 안 나와서 확답하기 어려워요",
            22 to "월화는 출장이라 안 되고 수목금은 점심시간에 할 수 있어요. 12시 정각에 시작해서 1시 전에 끝나는 걸로요",
            27 to "이번 주는 언제든 괜찮아요. 정해지면 거기에 맞춰둘게요",
            30 to "월수금 6시에 수업 끝나요. 이동 시간 있어서 저녁 7시 이후면 되고 10시엔 기숙사 들어가야 해요",
            42 to "목요일 오후 3시 병원 예약 전까지 괜찮아요. 이동해야 하니까 모임은 2시까지 끝나면 좋겠고, 시작은 오전 11시 이후로 부탁드려요",
            43 to "금요일 5시 이후랑 일요일 저녁 가능! 일요일은 8시쯤이 좋을 듯",
            47 to "이번 주엔 참여가 어려울 것 같아요. 일정 잡히면 다음번에 같이할게요ㅠㅠ",
        )

    fun raw(id: Int): String = originals.getValue(id)

    fun input(id: Int): PeriodClarificationInput =
        PeriodClarificationInput(
            rawText = raw(id),
            referenceAt = Instant.parse("2026-11-01T23:30:00Z"),
            timeZone = ZoneId.of("Asia/Seoul"),
            searchStartDate = LocalDate.parse("2026-11-02"),
            displayedEndInclusive = LocalDate.parse("2026-11-08"),
            searchEndExclusive = LocalDate.parse("2026-11-09"),
            meetingDuration = Duration.ofMinutes(60),
        )

    fun candidates(
        id: Int,
        vararg clocks: Pair<String, Int>,
    ): List<ClockCandidate> =
        clocks.mapIndexed { index, clock ->
            val (span, hour) = clock
            val text = raw(id)
            val offset = text.indexOf(span)
            require(offset >= 0)
            val start = text.codePointCount(0, offset)
            ClockCandidate(
                id = "q%02d".format(index + 1),
                start = start,
                end = start + span.codePointCount(0, span.length),
                span = span,
                hour = hour,
                minute = if ("반" in span) 30 else 0,
                notation = if (':' in span) ClockNotation.COLON else ClockNotation.HOUR,
            )
        }
}
