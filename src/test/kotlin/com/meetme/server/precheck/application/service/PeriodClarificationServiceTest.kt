package com.meetme.server.precheck.application.service

import com.meetme.server.precheck.application.port.output.ClockClassificationException
import com.meetme.server.precheck.application.port.output.ClockPeriodPort
import com.meetme.server.precheck.application.port.output.ClockRelevancePort
import com.meetme.server.precheck.domain.ClockCandidate
import com.meetme.server.precheck.domain.ClockChoice
import com.meetme.server.precheck.domain.ClockPeriod
import com.meetme.server.precheck.domain.ClockRelevance
import com.meetme.server.precheck.domain.PeriodClarificationFixtures
import com.meetme.server.precheck.domain.PeriodClarificationInput
import com.meetme.server.precheck.domain.PeriodClarificationOutcome
import com.meetme.server.precheck.domain.PeriodClarificationResult
import com.meetme.server.precheck.domain.certain
import com.meetme.server.precheck.domain.periodTie
import com.meetme.server.precheck.domain.relevanceTie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeriodClarificationServiceTest {
    @Test
    fun `REL-14 only possible 8 reaches period port and background routine remains downstream`() {
        val harness =
            PortHarness(
                relevance = { candidates ->
                    candidates.associate { candidate ->
                        candidate.id to
                            certain(
                                if (candidate.span == "7시 반") ClockRelevance.BACKGROUND else ClockRelevance.RELEVANT,
                            )
                    }
                },
                periods = { candidates -> candidates.associate { it.id to certain(ClockPeriod.NOT_STATED) } },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(14))

        assertEquals(listOf("7시 반", "8시"), harness.relevanceCandidates.map { it.span })
        assertEquals(listOf("8시"), harness.periodCandidates.map { it.span })
        assertEquals(1, harness.relevanceCalls)
        assertEquals(1, harness.periodCalls)
        assertEquals(PeriodClarificationOutcome.ASK_PERIOD, result.outcome)
        assertEquals(listOf("8시"), result.missingPeriods.map { it.span })
        assertEquals(ClockRelevance.BACKGROUND, result.relevance.getValue("q01").choice)
        assertPreserved(14, result)
        assertEquals(PeriodClarificationFixtures.input(14), harness.relevanceInput)
        assertEquals(harness.relevanceInput, harness.periodInput)
    }

    @Test
    fun `REL-01 starts and ends both reach one batched period call`() {
        val harness =
            PortHarness(
                periods = { candidates -> candidates.associate { it.id to certain(ClockPeriod.NOT_STATED) } },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertEquals(listOf("6시", "9시"), harness.periodCandidates.map { it.span })
        assertEquals(listOf("6시", "9시"), result.missingPeriods.map { it.span })
        assertEquals(1, harness.relevanceCalls)
        assertEquals(1, harness.periodCalls)
        assertEquals(PeriodClarificationOutcome.ASK_PERIOD, result.outcome)
        assertPreserved(1, result)
    }

    @Test
    fun `REL-30 class clock excluded while availability and dormitory bounds both reach period port`() {
        val harness =
            PortHarness(
                relevance = { candidates ->
                    candidates.associate {
                        it.id to certain(if (it.span == "6시") ClockRelevance.BACKGROUND else ClockRelevance.RELEVANT)
                    }
                },
            )

        // The fake period answers test routing, not the OPEN semantic label of the dormitory bound.
        val result = harness.service.check(PeriodClarificationFixtures.input(30))

        assertEquals(listOf("6시", "7시", "10시"), harness.relevanceCandidates.map { it.span })
        assertEquals(listOf("7시", "10시"), harness.periodCandidates.map { it.span })
        assertEquals(setOf("q02", "q03"), result.periods.keys)
        assertEquals(1, harness.periodCalls)
        assertPreserved(30, result)
    }

    @Test
    fun `REL-42 superseded hospital clock excluded but end and start both retained`() {
        val harness =
            PortHarness(
                relevance = { candidates ->
                    candidates.associate {
                        it.id to certain(if (it.span == "3시") ClockRelevance.BACKGROUND else ClockRelevance.RELEVANT)
                    }
                },
            )

        // The period fake is supplied evidence for orchestration; it does not settle the 2시 context policy.
        val result = harness.service.check(PeriodClarificationFixtures.input(42))

        assertEquals(listOf("3시", "2시", "11시"), harness.relevanceCandidates.map { it.span })
        assertEquals(listOf("2시", "11시"), harness.periodCandidates.map { it.span })
        assertEquals(setOf("q02", "q03"), result.periods.keys)
        assertPreserved(42, result)
    }

    @Test
    fun `REL-43 Sunday clear answer does not discard Friday missing information`() {
        val harness =
            PortHarness(
                periods = { candidates ->
                    candidates.associate {
                        it.id to certain(if (it.span == "5시") ClockPeriod.NOT_STATED else ClockPeriod.PM)
                    }
                },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(43))

        assertEquals(listOf("5시", "8시"), harness.periodCandidates.map { it.span })
        assertEquals(PeriodClarificationOutcome.ASK_PERIOD, result.outcome)
        assertEquals(listOf("5시"), result.missingPeriods.map { it.span })
        assertEquals(ClockPeriod.PM, result.periods.getValue("q02").choice)
        assertEquals(listOf("5시", "8시"), result.candidates.map { it.span })
        assertPreserved(43, result)
    }

    @Test
    fun `clockless flexible early and nonparticipation inputs make no classifier call`() {
        for (id in listOf(5, 6, 11, 27, 47)) {
            val harness = PortHarness()

            val result = harness.service.check(PeriodClarificationFixtures.input(id))

            assertEquals(0, harness.relevanceCalls, "original id=$id")
            assertEquals(0, harness.periodCalls, "original id=$id")
            assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
            assertTrue(result.missingPeriods.isEmpty())
            assertPreserved(id, result)
        }
    }

    @Test
    fun `REL-20 future roster uncertainty remains downstream without a manufactured period question`() {
        val harness = PortHarness()

        val result = harness.service.check(PeriodClarificationFixtures.input(20))

        assertEquals(listOf("4시", "6시"), harness.periodCandidates.map { it.span })
        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertTrue(result.missingPeriods.isEmpty())
        assertPreserved(20, result)
    }

    @Test
    fun `REL-22 duration mismatch is not repaired by an additional period question`() {
        val harness = PortHarness()

        // Fake unique periods avoid fixing OPEN lunch/noon policy; the full strict-before text must survive.
        val result = harness.service.check(PeriodClarificationFixtures.input(22))

        assertEquals(listOf("12시", "1시"), harness.periodCandidates.map { it.span })
        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertTrue(result.missingPeriods.isEmpty())
        assertPreserved(22, result)
        assertEquals(60L, result.downstreamInput.meetingDuration.toMinutes())
    }

    @Test
    fun `relevant fixed 24 hour clocks skip the period port`() {
        val harness = PortHarness()

        val result = harness.service.check(PeriodClarificationFixtures.input(9))

        assertEquals(listOf("19시", "22시", "20시"), harness.relevanceCandidates.map { it.span })
        assertEquals(1, harness.relevanceCalls)
        assertEquals(0, harness.periodCalls)
        assertTrue(result.periods.isEmpty())
        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertPreserved(9, result)
    }

    @Test
    fun `OPEN colon convention upper bound requests periods only for ten and twelve`() {
        val harness = PortHarness()

        val result = harness.service.check(PeriodClarificationFixtures.input(12))

        assertEquals(listOf("14:00", "17:00", "10:00", "12:00"), harness.relevanceCandidates.map { it.span })
        assertEquals(listOf("10:00", "12:00"), harness.periodCandidates.map { it.span })
        assertEquals(setOf("q03", "q04"), result.periods.keys)
        assertEquals(1, harness.periodCalls)
        assertPreserved(12, result)
    }

    @Test
    fun `all background evidence skips period classification but keeps original input`() {
        val harness =
            PortHarness(
                relevance = { candidates -> candidates.associate { it.id to certain(ClockRelevance.BACKGROUND) } },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(14))

        assertEquals(0, harness.periodCalls)
        assertEquals(PeriodClarificationOutcome.NO_PERIOD_QUESTION, result.outcome)
        assertEquals(listOf("7시 반", "8시"), result.candidates.map { it.span })
        assertPreserved(14, result)
    }

    @Test
    fun `unknown relevance is held while known missing target is still classified`() {
        for (unknown in listOf(certain(ClockRelevance.UNRESOLVED), relevanceTie())) {
            val harness =
                PortHarness(
                    relevance = { candidates ->
                        candidates.associate {
                            it.id to if (it.id == "q01") unknown else certain(ClockRelevance.RELEVANT)
                        }
                    },
                    periods = { candidates -> candidates.associate { it.id to certain(ClockPeriod.NOT_STATED) } },
                )

            val result = harness.service.check(PeriodClarificationFixtures.input(1))

            assertEquals(listOf("9시"), harness.periodCandidates.map { it.span })
            assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
            assertEquals(setOf("q01"), result.heldTargets)
            assertEquals(listOf("9시"), result.missingPeriods.map { it.span })
            assertNull(result.technicalFailure)
        }
    }

    @Test
    fun `period tie is semantic hold rather than technical failure or automatic question`() {
        val harness = PortHarness(periods = { candidates -> candidates.associate { it.id to periodTie() } })

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
        assertEquals(setOf("q01", "q02"), result.heldTargets)
        assertTrue(result.missingPeriods.isEmpty())
        assertNull(result.technicalFailure)
        assertPreserved(1, result)
    }

    @Test
    fun `relevance exception is explicit technical hold with no fallback`() {
        val harness = PortHarness(relevance = { throw ClockClassificationException("SYNTHETIC_RELEVANCE_FAILURE") })

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertTechnicalHold(1, result)
        assertEquals(0, harness.periodCalls)
        assertEquals(1, harness.relevanceCalls)
        assertEquals(listOf("6시", "9시"), result.candidates.map { it.span })
    }

    @Test
    fun `period exception keeps safe selection evidence but produces no partial questions`() {
        val harness = PortHarness(periods = { throw ClockClassificationException("SYNTHETIC_PERIOD_FAILURE") })

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertTechnicalHold(1, result)
        assertEquals(setOf("q01", "q02"), result.relevance.keys)
        assertEquals(1, harness.relevanceCalls)
        assertEquals(1, harness.periodCalls)
    }

    @Test
    fun `missing or extra selection qids are technical holds before period dispatch`() {
        for (invalid in listOf(
            mapOf("q01" to certain(ClockRelevance.RELEVANT)),
            mapOf(
                "q01" to certain(ClockRelevance.RELEVANT),
                "q02" to certain(ClockRelevance.RELEVANT),
                "q03" to certain(ClockRelevance.BACKGROUND),
            ),
        )) {
            val harness = PortHarness(relevance = { invalid })

            val result = harness.service.check(PeriodClarificationFixtures.input(1))

            assertTechnicalHold(1, result)
            assertEquals(0, harness.periodCalls)
        }
    }

    @Test
    fun `invalid selection maximum is technical hold before period dispatch`() {
        val harness =
            PortHarness(
                relevance = { candidates ->
                    candidates.associate { it.id to certain(ClockRelevance.RELEVANT).copy(choice = ClockRelevance.BACKGROUND) }
                },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertTechnicalHold(1, result)
        assertEquals(0, harness.periodCalls)
    }

    @Test
    fun `missing or extra period qids are technical holds without partial question credit`() {
        for (invalid in listOf(
            mapOf("q01" to certain(ClockPeriod.NOT_STATED)),
            mapOf(
                "q01" to certain(ClockPeriod.NOT_STATED),
                "q02" to certain(ClockPeriod.PM),
                "q03" to certain(ClockPeriod.PM),
            ),
        )) {
            val harness = PortHarness(periods = { invalid })

            assertTechnicalHold(1, harness.service.check(PeriodClarificationFixtures.input(1)))
            assertEquals(1, harness.periodCalls)
        }
    }

    @Test
    fun `invalid period maximum cannot promote partial NOT_STATED evidence to a question`() {
        val harness =
            PortHarness(
                periods = {
                    mapOf(
                        "q01" to certain(ClockPeriod.NOT_STATED),
                        "q02" to certain(ClockPeriod.PM).copy(choice = ClockPeriod.NOT_STATED),
                    )
                },
            )

        val result = harness.service.check(PeriodClarificationFixtures.input(1))

        assertTechnicalHold(1, result)
        assertEquals(setOf("q01", "q02"), result.relevance.keys)
    }

    private fun assertTechnicalHold(
        id: Int,
        result: PeriodClarificationResult,
    ) {
        assertEquals(PeriodClarificationOutcome.HOLD, result.outcome)
        assertNotNull(result.technicalFailure)
        assertTrue(result.technicalFailure.orEmpty().isNotBlank())
        assertTrue(result.missingPeriods.isEmpty())
        assertPreserved(id, result)
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

private class PortHarness(
    relevance: (List<ClockCandidate>) -> Map<String, ClockChoice<ClockRelevance>> = { candidates ->
        candidates.associate { it.id to certain(ClockRelevance.RELEVANT) }
    },
    periods: (List<ClockCandidate>) -> Map<String, ClockChoice<ClockPeriod>> = { candidates ->
        candidates.associate { it.id to certain(ClockPeriod.PM) }
    },
) {
    var relevanceCalls = 0
    var periodCalls = 0
    var relevanceInput: PeriodClarificationInput? = null
    var periodInput: PeriodClarificationInput? = null
    var relevanceCandidates: List<ClockCandidate> = emptyList()
    var periodCandidates: List<ClockCandidate> = emptyList()
    val service =
        PeriodClarificationService(
            relevancePort =
                ClockRelevancePort { input, candidates ->
                    relevanceCalls++
                    relevanceInput = input
                    relevanceCandidates = candidates.toList()
                    relevance(candidates)
                },
            periodPort =
                ClockPeriodPort { input, candidates ->
                    periodCalls++
                    periodInput = input
                    periodCandidates = candidates.toList()
                    periods(candidates)
                },
        )
}
