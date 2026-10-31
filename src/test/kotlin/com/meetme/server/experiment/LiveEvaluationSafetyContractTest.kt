package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic stage counters only. No dataset, gold, SDK client, provider endpoint or paid invocation. */
class LiveEvaluationSafetyContractTest {
    @Test
    fun `first common classification failure blocks its final stage second room and holdout`() {
        val observations = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { observations.toList() }
        var classifications = 0
        var finals = 0
        var secondRooms = 0
        var holdouts = 0

        val first =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.guard {
                    classifications++
                    observations += LiveEvaluationSignal("PRECHECK_CLASSIFICATION", parserFailureCode = "SERVER")
                }
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { finals++ } }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { secondRooms++ } }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { holdouts++ } }

        assertEquals(1, classifications)
        assertEquals(0, finals)
        assertEquals(0, secondRooms)
        assertEquals(0, holdouts)
        assertEquals("PRECHECK_CLASSIFICATION", first.barrier.stage)
        assertEquals("SERVER", first.barrier.failureCode)
        assertEquals(first.barrier, safety.barrier)
    }

    @Test
    fun `common final configuration failure blocks the next room and holdout`() {
        val observations = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { observations.toList() }
        var classifications = 0
        var finals = 0
        var nextRoomCalls = 0
        var holdoutCalls = 0

        safety.guard { classifications++ }
        val failure =
            assertFailsWith<LiveEvaluationTechnicalStop> {
                safety.guard {
                    finals++
                    observations += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "CONFIGURATION")
                }
            }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { nextRoomCalls++ } }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { holdoutCalls++ } }

        assertEquals(listOf(1, 1, 0, 0), listOf(classifications, finals, nextRoomCalls, holdoutCalls))
        assertEquals("CONFIGURATION", failure.barrier.failureCode)
    }

    @Test
    fun `first barrier stays latched after observation history is cleared reordered or caught`() {
        val observations = mutableListOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "NETWORK"))
        val safety = LiveEvaluationSafety { observations.toList() }
        val first = assertFailsWith<LiveEvaluationTechnicalStop> { safety.checkpoint() }.barrier
        observations.clear()
        observations += LiveEvaluationSignal("OTHER_STAGE", parserFailureCode = "SERVER")
        var calls = 0

        val second = assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }.barrier

        assertEquals(first, second)
        assertEquals(first, safety.barrier)
        assertEquals(0, calls)
    }

    @Test
    fun `driver boundary checks preserve retries already inside the current production invocation`() {
        val observations = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { observations.toList() }
        var attempts = 0
        var subsequentCalls = 0

        assertFailsWith<LiveEvaluationTechnicalStop> {
            safety.guard {
                repeat(2) {
                    attempts++
                    observations += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")
                }
                attempts++
                observations += LiveEvaluationSignal("FINAL_PRIMARY", httpStatus = 200)
            }
        }
        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { subsequentCalls++ } }

        assertEquals(3, attempts, "The driver must not rewrite the provider's existing bounded retry policy")
        assertEquals(0, subsequentCalls)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["SERVER", "CONFIGURATION", "NETWORK", "TIMEOUT", "AUTHENTICATION", "PERMISSION", "BILLING", "QUOTA", "RATE_LIMIT"],
    )
    fun `common provider failures stop before another action`(code: String) {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = code)) }
        var calls = 0

        val failure = assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }

        assertEquals(0, calls)
        assertEquals(code, failure.barrier.failureCode)
        assertTrue(failure.barrier.category.isNotBlank())
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 402, 403, 407, 408, 429, 500, 502, 503, 504])
    fun `observed account availability and server HTTP failures stop before another action`(status: Int) {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", httpStatus = status)) }
        var calls = 0

        val failure = assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }

        assertEquals(0, calls)
        assertEquals(status, failure.barrier.httpStatus)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["AMBIGUOUS_INPUT", "AMBIGUOUS_TIME_CONSTRAINT", "CONDITION_VALIDATION_FAILED", "INVALID_RESPONSE", "INVALID_REQUEST"],
    )
    fun `individual semantic or unclassified input failures do not invent a common outage`(code: String) {
        val observations = mutableListOf(LiveEvaluationSignal("FINAL_PRIMARY", httpStatus = 200, parserFailureCode = code))
        val safety = LiveEvaluationSafety { observations.toList() }
        var nextRooms = 0
        var holdouts = 0

        safety.guard { nextRooms++ }
        safety.guard { holdouts++ }

        assertEquals(1, nextRooms)
        assertEquals(1, holdouts)
        assertNull(safety.barrier)
    }

    @Test
    fun `successful empty overlap is a normal result and keeps the next room eligible`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", httpStatus = 200)) }
        val empty = safety.guard { emptyList<String>() }
        var nextRooms = 0

        safety.guard { nextRooms++ }

        assertTrue(empty.isEmpty())
        assertEquals(1, nextRooms)
        assertNull(safety.barrier)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 404, 405, 409, 422])
    fun `request-specific HTTP status alone does not invent a shared configuration failure`(status: Int) {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", httpStatus = status)) }
        var calls = 0

        safety.guard { calls++ }

        assertEquals(1, calls)
        assertNull(safety.barrier)
    }

    @Test
    fun `unavailable observation metadata latches a sanitized barrier before any provider action`() {
        val privateMarker = "synthetic-private-detail-never-publish"
        var unavailable = true
        var calls = 0
        val safety =
            LiveEvaluationSafety {
                if (unavailable) throw IOException(privateMarker)
                emptyList()
            }
        val first = assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }
        unavailable = false
        val second = assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }

        assertEquals(0, calls)
        assertEquals(first.barrier, second.barrier)
        assertEquals("OBSERVATION_UNAVAILABLE", first.barrier.category)
        assertTrue(privateMarker !in first.message.orEmpty())
        assertTrue(privateMarker !in first.barrier.toString())
        assertNull(first.cause)
    }

    @Test
    fun `typed transport failure metadata stops before another room without needing raw messages`() {
        val safety =
            LiveEvaluationSafety {
                listOf(LiveEvaluationSignal("FINAL_PRIMARY", transportFailureClass = "java.net.SocketTimeoutException"))
            }
        var calls = 0

        assertFailsWith<LiveEvaluationTechnicalStop> { safety.guard { calls++ } }

        assertEquals(0, calls)
        assertNotNull(safety.barrier)
    }
}
