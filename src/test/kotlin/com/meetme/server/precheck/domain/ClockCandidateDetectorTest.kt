package com.meetme.server.precheck.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockCandidateDetectorTest {
    private val detector = ClockCandidateDetector()

    @Test
    fun `REL-14 lexical detection retains background clock for semantic selection`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(14))

        assertEquals(listOf("7시 반", "8시"), found.map { it.span })
        assertEquals(listOf(7, 8), found.map { it.hour })
        assertEquals(listOf(30, 0), found.map { it.minute })
        assertValidOrdered(14, found)
    }

    @Test
    fun `REL-30 lexical detection retains class time and dormitory bound`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(30))

        assertEquals(listOf("6시", "7시", "10시"), found.map { it.span })
        assertValidOrdered(30, found)
    }

    @Test
    fun `REL-42 lexical detection retains appointment and both availability bounds`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(42))

        assertEquals(listOf("3시", "2시", "11시"), found.map { it.span })
        assertValidOrdered(42, found)
    }

    @Test
    fun `REL-01 both endpoints remain separate candidates`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(1))

        assertEquals(listOf("6시", "9시"), found.map { it.span })
        assertValidOrdered(1, found)
    }

    @Test
    fun `REL-43 clear alternative does not remove earlier ambiguous alternative`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(43))

        assertEquals(listOf("5시", "8시"), found.map { it.span })
        assertValidOrdered(43, found)
    }

    @Test
    fun `clockless delegation and nonparticipation do not create numeric targets`() {
        for (id in listOf(5, 6, 11, 27, 47)) {
            assertTrue(detector.detect(PeriodClarificationFixtures.raw(id)).isEmpty(), "original id=$id")
        }
    }

    @Test
    fun `explicit 24 hour clocks are fixed anchors`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(9))

        assertEquals(listOf("19시", "22시", "20시"), found.map { it.span })
        assertEquals(listOf(19, 22, 20), found.map { it.hour })
        assertTrue(found.all { it.fixed24Hour })
        assertValidOrdered(9, found)
    }

    @Test
    fun `colon zero through twelve remains eligible while convention is OPEN`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(12))

        assertEquals(listOf("14:00", "17:00", "10:00", "12:00"), found.map { it.span })
        assertTrue(found.all { it.notation == ClockNotation.COLON })
        assertEquals(listOf(true, true, false, false), found.map { it.fixed24Hour })
        assertValidOrdered(12, found)
    }

    @Test
    fun `one sided range unit elision restores both exact source endpoints`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(3))

        assertEquals(listOf("3", "5시"), found.map { it.span })
        assertEquals(listOf(3, 5), found.map { it.hour })
        assertEquals(ClockNotation.ELIDED_RANGE, found.first().notation)
        assertValidOrdered(3, found)
    }

    @Test
    fun `compact ranges with both units elided remain lexical coverage not noon gold`() {
        val found = detector.detect(PeriodClarificationFixtures.raw(15))

        assertEquals(listOf("10", "12", "2", "5"), found.map { it.span })
        assertEquals(listOf(10, 12, 2, 5), found.map { it.hour })
        assertTrue(found.all { it.notation == ClockNotation.ELIDED_RANGE })
        assertValidOrdered(15, found)
    }

    @Test
    fun `fixed anchor boundary is a numeric DTO property rather than a period guess`() {
        val original = PeriodClarificationFixtures.candidates(1, "6시" to 6).single()

        for (hour in listOf(0, 13, 14, 23, 24)) {
            assertTrue(original.copy(hour = hour).fixed24Hour, "hour=$hour")
        }
        for (hour in listOf(1, 6, 11, 12)) {
            assertFalse(original.copy(hour = hour).fixed24Hour, "hour=$hour")
        }
    }

    private fun assertValidOrdered(
        id: Int,
        candidates: List<ClockCandidate>,
    ) {
        val raw = PeriodClarificationFixtures.raw(id)
        assertEquals(candidates.indices.map { "q%02d".format(it + 1) }, candidates.map { it.id })
        candidates.forEach { it.validate(raw) }
        assertTrue(candidates.zipWithNext().all { (first, next) -> first.end <= next.start })
    }
}
