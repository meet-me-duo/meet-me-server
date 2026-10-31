package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A completed semantic failure is distinct from an unfinished application analysis. No providers are called. */
class LiveEvaluationCompletionBoundaryContractTest {
    @Test
    fun `individual invalid response is not outage but an unfinished room blocks the next room`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "INVALID_RESPONSE")) }
        var nextRoomCalls = 0
        safety.checkpoint()
        assertNull(safety.barrier)

        val incomplete =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.requireCompletedAnalysis("ANALYSIS_DELAYED", 409, 409)
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { nextRoomCalls++ } }

        assertEquals("INCOMPLETE_ANALYSIS", incomplete.barrier.category)
        assertEquals(0, nextRoomCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NO_MATCH", "READY", "READY_WITH_WARNINGS", "CONFIRMED"])
    fun `completed semantic and empty overlap results can continue without a gold comparison`(publicStatus: String) {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "INVALID_RESPONSE")) }
        var nextRoomCalls = 0

        safety.requireCompletedAnalysis(publicStatus, 200, 200)
        safety.guard { nextRoomCalls++ }

        assertNull(safety.barrier)
        assertEquals(1, nextRoomCalls)
    }

    @Test
    fun `a ready status without completed public views cannot authorize the next room`() {
        val safety = LiveEvaluationSafety { emptyList() }
        var nextRoomCalls = 0

        val incomplete =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.requireCompletedAnalysis("READY", 200, 409)
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { nextRoomCalls++ } }

        assertEquals("INCOMPLETE_ANALYSIS", incomplete.barrier.category)
        assertEquals(0, nextRoomCalls)
    }
}
