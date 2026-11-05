package com.meetme.server.submission.domain.ir

import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Review-derived relation integrity and bounded work at the public 32-condition limit. */
class ScheduleIrRelationComplexityTest {
    private val fixture = ScheduleIrTestFixture

    @Test
    fun `maximum dense acyclic corrections preserve every condition and finish within five seconds`() {
        val conditions =
            (0 until 32).map { index ->
                fixture.condition("c$index").copy(
                    supersedes = (0 until index).map { "c$it" },
                )
            }
        val snapshot = fixture.snapshot()
        val proposal = fixture.proposal(conditions)
        val expected =
            ScheduleIrEnvelope(
                SCHEDULE_IR_VERSION,
                snapshot.context,
                listOf(
                    IrResult(
                        fixture.firstId,
                        IrAvailabilityState.KNOWN_WINDOWS,
                        conditions.map(fixture::expectedCondition),
                        emptyList(),
                    ),
                    IrResult(fixture.secondId, IrAvailabilityState.UNKNOWN, emptyList(), emptyList()),
                ),
            )
        val task = FutureTask { ScheduleIrValidator().validate(proposal, snapshot) }
        // A failing implementation may ignore interruption. A daemon cannot keep the test JVM alive.
        val worker =
            Thread(task, "schedule-ir-dense-dag-resource-regression").apply {
                isDaemon = true
            }
        worker.start()
        try {
            val actual =
                try {
                    task.get(5, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    fail("Validation of 32 valid acyclic conditions exceeded the fixed five-second resource budget")
                }
            assertEquals(expected, actual)
            assertTrue(
                actual.results
                    .first()
                    .quarantined
                    .isEmpty(),
            )
        } finally {
            task.cancel(true)
        }
    }

    @Test
    fun `cycle and transitive dependents are quarantined while an independent condition survives`() {
        val independent = fixture.condition("independent")
        val cycleA = fixture.condition("cycle-a").copy(supersedes = listOf("cycle-b"))
        val cycleB = fixture.condition("cycle-b").copy(supersedes = listOf("cycle-a"))
        val dependent = fixture.condition("dependent").copy(supersedes = listOf("cycle-a"))
        val transitive = fixture.condition("transitive").copy(supersedes = listOf("dependent"))
        val conditions = listOf(independent, cycleA, cycleB, dependent, transitive)

        val actual = ScheduleIrValidator().validate(fixture.proposal(conditions), fixture.snapshot())
        val result = actual.results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
        assertEquals(listOf(fixture.expectedCondition(independent)), result.conditions)
        assertEquals(
            listOf("cycle-a", "cycle-b", "dependent", "transitive").map {
                IrQuarantine(it, IrProblem.RELATION, null)
            },
            result.quarantined,
        )
        assertEquals(
            IrResult(fixture.secondId, IrAvailabilityState.UNKNOWN, emptyList(), emptyList()),
            actual.results.single { it.submissionVersionId == fixture.secondId },
        )
    }
}
