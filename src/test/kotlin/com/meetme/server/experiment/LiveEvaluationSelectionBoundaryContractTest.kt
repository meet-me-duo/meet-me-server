package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Metadata-only completion checks; no provider, model response, case input or semantic answer key. */
class LiveEvaluationSelectionBoundaryContractTest {
    @Test
    fun `LSB01 selection and final result both returned 200 allow the next room without semantic grading`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_FALLBACK", parserFailureCode = "INVALID_RESPONSE")) }
        var nextRoomCalls = 0

        safety.requireCompletedSelection(200, 200)
        safety.guard { nextRoomCalls++ }

        assertNull(safety.barrier)
        assertEquals(1, nextRoomCalls)
    }

    @Test
    fun `LSB02 failed selection takes priority over its missing final result and blocks further room calls`() {
        assertIncomplete(500, 404, 500)
    }

    @Test
    fun `LSB03 a successful selection without a successful final result blocks further room calls`() {
        assertIncomplete(200, 404, 404)
    }

    @Test
    fun `LSB04 omitted selection or final result status cannot masquerade as completed execution`() {
        assertIncomplete(null, 200, null)
        assertIncomplete(200, null, null)
        assertIncomplete(null, null, null)
    }

    @Test
    fun `LSB05 other 2xx statuses do not satisfy the exact completed selection and result contract`() {
        for (status in listOf(201, 202, 204, 206)) {
            assertIncomplete(status, 200, status)
            assertIncomplete(200, status, status)
        }
    }

    @Test
    fun `LSB06 an incomplete selection barrier remains latched even if later statuses and observations look successful`() {
        val observations = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { observations.toList() }
        var nextRoomCalls = 0
        val first =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.requireCompletedSelection(500, 404)
            }
        observations += LiveEvaluationSignal("FINAL_SELECTION", httpStatus = 200)

        val repeated =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.requireCompletedSelection(200, 200)
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { nextRoomCalls++ } }

        assertEquals(first.barrier, repeated.barrier)
        assertEquals(first.barrier, safety.barrier)
        assertEquals(0, nextRoomCalls)
    }

    private fun assertIncomplete(
        selectionStatus: Int?,
        resultStatus: Int?,
        expectedFailureStatus: Int?,
    ) {
        val safety = LiveEvaluationSafety { emptyList() }
        var nextRoomCalls = 0
        val failure =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.requireCompletedSelection(selectionStatus, resultStatus)
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { nextRoomCalls++ } }

        assertEquals("FINAL_SELECTION", failure.barrier.stage)
        assertEquals("INCOMPLETE_SELECTION", failure.barrier.category)
        assertEquals(expectedFailureStatus, failure.barrier.httpStatus)
        assertNull(failure.barrier.failureCode)
        assertNull(failure.cause)
        assertEquals(failure.barrier, safety.barrier)
        assertEquals(0, nextRoomCalls)
    }
}
