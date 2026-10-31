package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual live fixture factory with stub authority and SDK counters. No database, SDK, model or network call. */
class LiveEvaluationAdmissionContractTest {
    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val deadline = now.plusSeconds(15)

    @Test
    fun `driver action does not hold the safety monitor needed by its worker`() {
        val safety = LiveEvaluationSafety { emptyList() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            safety.guard {
                val progress =
                    worker.submit<Boolean> {
                        safety.checkpoint()
                        true
                    }
                val completed =
                    try {
                        progress.get(5, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        false
                    }
                assertTrue(completed, "The driver must let an actual worker check safety while its action is active")
            }
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `actual fixture authorization stops new SDK admission after the first shared server failure`() {
        val signals = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { signals.toList() }
        val authority = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = request()
        `when`(authority.authorize(request, now)).thenReturn(deadline)
        var admissions = 0
        var sdkCalls = 0
        val fixtures =
            ExperimentalLiveHttpEvaluationTest.LiveFixtures {
                admissions++
                true
            }
        val liveGuard = fixtures.boundedActualDispatch(authority, LiveEvaluationAdmission(safety))

        if (liveGuard.authorize(request, now) != null) sdkCalls++
        signals += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")
        val rejected =
            runCatching {
                if (liveGuard.authorize(request, now) != null) sdkCalls++
            }.exceptionOrNull()

        assertEquals(1, sdkCalls, "The second logical retry must issue zero new SDK calls after the first common failure")
        assertEquals(1, admissions, "The common failure must be checked at actual admission, not only after a room finishes")
        assertIs<LiveEvaluationTechnicalStop>(rejected)
        verify(authority, times(2)).authorize(request, now)
    }

    @Test
    fun `actual fixture never turns missing original authority into an admission even with a barrier`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")) }
        val authority = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = request()
        `when`(authority.authorize(request, now)).thenReturn(null)
        var admissions = 0
        val liveGuard =
            ExperimentalLiveHttpEvaluationTest
                .LiveFixtures {
                    admissions++
                    true
                }.boundedActualDispatch(authority, LiveEvaluationAdmission(safety))

        assertNull(liveGuard.authorize(request, now))
        assertEquals(0, admissions)
        verify(authority).authorize(request, now)
    }

    @Test
    fun `actual fixture retains the existing bounded admission refusal`() {
        val safety = LiveEvaluationSafety { emptyList() }
        val authority = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = request()
        `when`(authority.authorize(request, now)).thenReturn(deadline)
        var admissions = 0
        val liveGuard =
            ExperimentalLiveHttpEvaluationTest
                .LiveFixtures {
                    admissions++
                    false
                }.boundedActualDispatch(authority, LiveEvaluationAdmission(safety))

        assertNull(liveGuard.authorize(request, now))
        assertEquals(1, admissions)
        verify(authority).authorize(request, now)
    }

    @Test
    fun `admission preserves the exact original grant and checks it before admission`() {
        val order = mutableListOf<String>()
        val admission =
            LiveEvaluationAdmission(
                LiveEvaluationSafety {
                    order += "SAFETY"
                    emptyList()
                },
            )

        val granted =
            admission.authorize({
                order += "ORIGINAL_FENCE"
                deadline
            }) {
                order += "ADMIT"
                true
            }

        assertSame(deadline, granted)
        assertEquals(listOf("ORIGINAL_FENCE", "SAFETY", "ADMIT", "SAFETY"), order)
    }

    @Test
    fun `missing original grant cannot be replaced by safety or admission success`() {
        var admissions = 0
        val admission = LiveEvaluationAdmission(LiveEvaluationSafety { error("No grant exists to inspect") })

        assertNull(
            admission.authorize<Instant>({ null }) {
                admissions++
                true
            },
        )
        assertEquals(0, admissions)
    }

    @Test
    fun `original fence exception is preserved without invoking admission`() {
        val original = IllegalStateException("synthetic original authority denial")
        val admission = LiveEvaluationAdmission(LiveEvaluationSafety { error("Original authority failed") })
        var admissions = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                admission.authorize<Instant>({ throw original }) {
                    admissions++
                    true
                }
            }

        assertSame(original, failure)
        assertEquals(0, admissions)
    }

    @Test
    fun `a common failure appearing during admission is checked before a grant is returned`() {
        val signals = mutableListOf<LiveEvaluationSignal>()
        val admission = LiveEvaluationAdmission(LiveEvaluationSafety { signals.toList() })
        var admissions = 0

        assertFailsWith<LiveEvaluationTechnicalStop> {
            admission.authorize({ deadline }) {
                admissions++
                signals += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "CONFIGURATION")
                true
            }
        }
        assertEquals(1, admissions)
    }

    private fun request(): AiBudgetReserveRequest =
        AiBudgetReserveRequest(
            AiBudgetExecution(
                UUID(0, 1),
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                UUID(0, 2),
                0,
                UUID(0, 3),
                0,
                "1".repeat(64),
                "2".repeat(64),
            ),
            listOf(AiPhysicalCallSpec(AiPhysicalOperation.FINAL_PRIMARY, 1, "3".repeat(64), "4".repeat(64), UUID(0, 4))),
        )
}
