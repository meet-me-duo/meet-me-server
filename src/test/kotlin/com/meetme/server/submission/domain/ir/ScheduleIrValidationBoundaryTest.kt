package com.meetme.server.submission.domain.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Additional review-derived requirements; the original 74-test RED oracle stays frozen. */
class ScheduleIrValidationBoundaryTest {
    private val validator = ScheduleIrValidator()
    private val fixture = ScheduleIrTestFixture

    @Test
    fun `server generated availability diagnosis does not consume provider condition budget on restoration`() {
        val conditions =
            (1..32).map { index ->
                fixture.condition("invalid-$index").copy(
                    evidence = listOf(fixture.evidence().copy(quote = "unmatched synthetic quote")),
                )
            }
        val snapshot = fixture.snapshot()
        val validated = validator.validate(fixture.proposal(conditions), snapshot)
        val result = validated.results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertTrue(result.conditions.isEmpty())
        assertEquals(33, result.quarantined.size)
        assertEquals(
            conditions.map { it.id }.toSet(),
            result.quarantined
                .filter { it.problem == IrProblem.EVIDENCE }
                .map { it.conditionId }
                .toSet(),
        )
        assertEquals(
            listOf(IrQuarantine(null, IrProblem.AVAILABILITY, null)),
            result.quarantined.filter { it.problem == IrProblem.AVAILABILITY },
        )
        assertTrue(result.quarantined.all { it.scope == null })
        assertEquals(validated, validator.validateRestored(validated, snapshot))
    }

    @Test
    fun `thirty three provider conditions remain over the input limit`() {
        val conditions = (1..33).map { fixture.condition("valid-$it") }

        assertFailsWith<IllegalArgumentException> {
            validator.validate(fixture.proposal(conditions), fixture.snapshot())
        }
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `known windows retain structural availability independently of interpretation label`(interpretation: IrInterpretation) {
        assertStructuralState(
            fixture.condition().copy(interpretation = interpretation),
            IrAvailabilityState.KNOWN_WINDOWS,
        )
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `unconstrained effect retains structural availability independently of interpretation label`(interpretation: IrInterpretation) {
        assertStructuralState(
            fixture.condition().copy(
                effect = IrEffect.UNCONSTRAINED,
                interpretation = interpretation,
                scope = fixture.fullSearch,
            ),
            IrAvailabilityState.UNCONSTRAINED,
        )
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `full unavailable coverage retains empty state independently of interpretation label`(interpretation: IrInterpretation) {
        assertStructuralState(
            fixture.condition().copy(
                effect = IrEffect.UNAVAILABLE,
                interpretation = interpretation,
                scope = fixture.fullSearch,
            ),
            IrAvailabilityState.EMPTY,
        )
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `unknown effect and scope remain unknown even with explicit interpretation label`(interpretation: IrInterpretation) {
        assertStructuralState(
            fixture.condition().copy(
                effect = IrEffect.UNRESOLVED,
                interpretation = interpretation,
                scope = null,
            ),
            IrAvailabilityState.UNKNOWN,
        )
    }

    /** IR-01/05: structural validity is not semantic accuracy, user confirmation, or recommendation eligibility. */
    private fun assertStructuralState(
        condition: ProposedIrCondition,
        availability: IrAvailabilityState,
    ) {
        val snapshot = fixture.snapshot()
        val expected =
            ScheduleIrEnvelope(
                SCHEDULE_IR_VERSION,
                snapshot.context,
                listOf(
                    IrResult(fixture.firstId, availability, listOf(fixture.expectedCondition(condition)), emptyList()),
                    IrResult(fixture.secondId, IrAvailabilityState.UNKNOWN, emptyList(), emptyList()),
                ),
            )

        // Only the label varies within each test; the effect, scope, evidence and active assertion stay fixed.
        assertEquals(IrAssertion.ASSERTED, condition.assertion)
        assertTrue(condition.supersedes.isEmpty())
        assertEquals(expected, validator.validate(fixture.proposal(listOf(condition), availability), snapshot))
        // Restore an independently constructed oracle, so a validator downgrade cannot define the expected state.
        assertEquals(expected, validator.validateRestored(expected, snapshot))
    }
}
