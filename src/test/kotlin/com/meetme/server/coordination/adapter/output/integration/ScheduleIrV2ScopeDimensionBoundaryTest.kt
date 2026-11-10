package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nonprecision changes temporal resolution, not the supported dimension of a known effect. */
class ScheduleIrV2ScopeDimensionBoundaryTest {
    private val f = ScheduleIrV2SyntheticFixture
    private val parser = ScheduleIrV2ProviderContract(JsonMapper.builder().findAndAddModules().build())
    private val validator = ScheduleIrV2Validator()

    @ParameterizedTest
    @CsvSource(
        "AVAILABLE,PLACE,bounded",
        "AVAILABLE,ALL,bounded",
        "PREFERRED,PLACE,bounded",
        "PREFERRED,ALL,bounded",
        "UNAVAILABLE,PLACE,bounded",
        "UNAVAILABLE,ALL,bounded",
        "AVAILABLE,PLACE,unbounded",
        "PREFERRED,ALL,unbounded",
    )
    fun `V2S01 nonprecise known effects cannot bypass TIME dimension restriction`(
        effect: String,
        dimension: String,
        bounds: String,
    ) {
        val scope =
            if (bounds ==
                "bounded"
            ) {
                f.preference.copy(dimension = IrDimension.valueOf(dimension))
            } else {
                IrScope(IrDimension.valueOf(dimension))
            }
        val bad = f.condition("bad", effect = IrEffect.valueOf(effect), scope = scope)
        val clear = f.precise("independent")
        val conditions = listOf(clear, bad)
        val snapshot = f.snapshot()

        val validated = validator.validate(f.proposal(conditions), snapshot)
        val parsed = parser.parseProviderResponse(f.json(conditions), snapshot, SCHEDULE_IR_V2_VERSION)

        assertEquals(validated, parsed)
        for (actual in listOf(validated, parsed)) {
            assertEquals(listOf(f.verified(clear)), actual.results.first().conditions)
            val quarantine =
                actual.results
                    .first()
                    .quarantined
                    .single()
            assertEquals(bad.id, quarantine.conditionId)
            assertEquals(IrProblem.SCOPE, quarantine.problem)
            assertEquals(null, quarantine.scope)
            assertEquals(f.envelope(conditions).results.last(), actual.results.last())
            assertEquals(actual, validator.validateRestored(actual, snapshot))
        }
        // A forged persisted accepted condition must not be trusted merely because its evidence is valid.
        assertFailsWith<RuntimeException> { validator.validateRestored(f.envelope(conditions), snapshot) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PLACE", "ALL"])
    fun `V2S02 unresolved nonprecise claims retain supported nonTIME scope through validation and restoration`(dimension: String) {
        val unresolved =
            f.condition(
                "unresolved",
                effect = IrEffect.UNRESOLVED,
                scope = f.preference.copy(dimension = IrDimension.valueOf(dimension)),
            )
        val conditions = listOf(f.precise("independent"), unresolved)
        val expected = f.envelope(conditions)
        val validated = validator.validate(f.proposal(conditions), f.snapshot())
        val parsed = parser.parseProviderResponse(f.json(conditions), f.snapshot(), SCHEDULE_IR_V2_VERSION)

        assertEquals(expected, validated)
        assertEquals(expected, parsed)
        assertEquals(expected, validator.validateRestored(expected, f.snapshot()))
        assertTrue(
            parsed.results
                .first()
                .quarantined
                .isEmpty(),
        )
        val claim =
            parsed.results
                .first()
                .conditions
                .last()
        assertEquals(IrEffect.UNRESOLVED, claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(unresolved.scope, claim.scope)
    }
}
