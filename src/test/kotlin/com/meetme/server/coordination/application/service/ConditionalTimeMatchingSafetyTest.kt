package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConditionalTimeMatchingSafetyTest {
    @Test
    fun `whole room decision opens fallback even when a primary subgroup already matches`() {
        val f = NaturalLanguageOnlyFixture(listOf("conditional", "primary and fallback", "fallback only"))
        f.results(listOf(f.window(9, 10), fallback(f)), listOf(f.window(9, 10), f.window(14, 15)), listOf(f.window(14, 15)))

        f.process()

        assertEquals(f.submissions.map { it.participantId }.toSet(), f.singleCandidate().participantIds.toSet())
        assertEquals(listOf(f.utc(14, 15)), f.singleCandidate().timeRanges)
        assertEquals(CandidateQuality.COMPLETE, f.run.quality)
        assertTrue(
            f.structured
                .first()
                .conditions
                .any { it is StructuredCondition.ConditionalFallbackTimeWindow },
        )
    }

    @Test
    fun `unknown primary does not provide evidence to activate another participant fallback`() {
        val f = NaturalLanguageOnlyFixture(listOf("conditional", "known", "unknown"))
        f.results(listOf(f.window(9, 10), fallback(f)), listOf(f.window(14, 15)), emptyList())

        f.process()

        assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", f.structured.first().rejectionCode)
        assertTrue(f.run.candidates.none { f.submissions.first().participantId in it.participantIds })
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(2, view.unappliedInputs)
    }

    @Test
    fun `multiple conditional participants stay unsupported instead of opening each other fallback`() {
        val f = NaturalLanguageOnlyFixture(listOf("conditional one", "conditional two", "known"))
        f.results(listOf(f.window(9, 10), fallback(f)), listOf(f.window(11, 12), fallback(f)), listOf(f.window(14, 15)))

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertEquals(
            listOf("UNSUPPORTED_CONDITIONAL_CONSTRAINT", "UNSUPPORTED_CONDITIONAL_CONSTRAINT"),
            f.structured.take(2).map {
                it.rejectionCode
            },
        )
    }

    @Test
    fun `restored fallback without explicit primary does not use unrestricted flat default`() {
        val f = NaturalLanguageOnlyFixture(listOf("missing primary", "known"))
        f.results(listOf(fallback(f)), listOf(f.window(14, 15)))

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", f.structured.first().rejectionCode)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
    }

    @Test
    fun `missing structured participant does not count as a known empty primary`() {
        val f = NaturalLanguageOnlyFixture(listOf("conditional", "missing"))
        f.results(listOf(f.window(9, 10), fallback(f)))

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", f.structured.first().rejectionCode)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
    }

    private fun fallback(f: NaturalLanguageOnlyFixture): StructuredCondition.ConditionalFallbackTimeWindow {
        val window = f.window(14, 15)
        return StructuredCondition.ConditionalFallbackTimeWindow(
            ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
            window.date,
            window.dayOfWeek,
            window.startTime,
            window.endTime,
        )
    }
}
