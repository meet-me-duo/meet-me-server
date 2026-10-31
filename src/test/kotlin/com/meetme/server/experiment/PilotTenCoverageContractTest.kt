package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PilotTenCoverageContractTest {
    @Test
    fun `a later synthetic final failure preserves observed question and answer without claiming final coverage`() {
        val coverage = pilotTenCoverage(emptyMap(), mapOf("questionCount" to 1, "answerCount" to 1, "stage" to "FINAL_ANALYSIS"))
        assertEquals(true, coverage["questionObserved"])
        assertEquals(true, coverage["answerSent"])
        assertNull(coverage["unappliedObserved"])
        assertNull(coverage["selectionPersisted"])
    }

    @Test
    fun `unvisited stages stay unknown while a completed no-question outcome is observed false`() {
        assertEquals(setOf(null), pilotTenCoverage(emptyMap(), emptyMap()).values.toSet())
        val coverage =
            pilotTenCoverage(
                mapOf("analysisCompleted" to true, "questionCount" to 0, "answerCount" to 0, "unappliedCount" to 0),
                emptyMap(),
            )
        assertEquals(false, coverage["questionObserved"])
        assertEquals(false, coverage["answerSent"])
        assertEquals(false, coverage["unappliedObserved"])
        assertNull(coverage["selectionPersisted"])
    }
}
