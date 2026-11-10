package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrScope
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Every retained C5 source proof includes safely isolated rejected preference declarations. */
class ScheduleDslStructuralRejectedProofTest {
    @Test
    fun `SRP01 removing only the located proof of a retained rejected preference fails full restoration and both calculation entries`() {
        val input = F.input("$TIME_QUOTE. $PLACE_QUOTE.").copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val available =
            F.claim(input, "available", scope = F.scope("2027-01-08T14:00", "2027-01-08T18:00")).copy(
                evidence = listOf(F.evidence(input, TIME_QUOTE)),
            )
        val rejectedPreference =
            F.claim(input, "place-pref", effect = IrEffect.PREFERRED, scope = IrScope(IrDimension.PLACE)).copy(
                evidence = listOf(F.evidence(input, PLACE_QUOTE)),
            )
        val proposal =
            F.proposal(
                input,
                claims = listOf(available, rejectedPreference),
                program =
                    F.program(
                        listOf(F.ref(input, "available-ref", "available"), F.ref(input, "preferred-ref", "place-pref")),
                        "available-ref",
                        prefer = "preferred-ref",
                    ),
                terms = emptyList(),
            )
        val document = objectMap(locators(F.document(proposal)))
        document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        val results = document["results"] as List<MutableMap<String, Any?>>
        results.forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
        val snapshot = F.snapshot(input)
        val envelope =
            ScheduleDslStructuralProviderContract(JsonMapper.builder().findAndAddModules().build()).parseProviderResponse(
                F.json(document),
                snapshot,
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        val declaration = envelope.base.base.declaration
        val original = declaration.claims.results.single { it.submissionVersionId == F.ir.first }
        assertEquals(setOf("available"), original.conditions.map { it.id }.toSet())
        assertTrue(original.quarantined.any { it.conditionId == "place-pref" && it.problem == IrProblem.SCOPE })
        val program = declaration.programs.single { it.submissionVersionId == F.ir.first }
        assertNotNull(program.program)
        assertTrue(program.problems.isEmpty())
        val witness = declaration.rejectedPreferenceWitnesses.single()
        assertEquals(F.ir.first, witness.submissionVersionId)
        assertEquals("place-pref", witness.conditionId)
        assertEquals(IrEffect.PREFERRED, witness.effect)
        assertEquals(IrDimension.PLACE, witness.declaredScope?.dimension)
        val span = witness.evidence.single()
        assertTrue(original.conditions.flatMap { it.evidence }.none { it == span })
        assertTrue(requireNotNull(program.program).nodes.flatMap { it.evidence }.none { it == span })
        val located = envelope.locatedEvidence.single { it.submissionVersionId == F.ir.first && it.evidence.span == span }
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, snapshot))

        val expected = listOf(InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z")))
        val first = ParticipantId(F.ir.firstParticipant)
        val prepared = ScheduleDslStructuralCalculator.prepare(envelope, snapshot, input.policy, input.meetingMode)
        val calculated = ScheduleDslStructuralCalculator.calculate(envelope, snapshot, input.policy, input.meetingMode)
        assertEquals(
            expected,
            prepared.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(
            expected,
            calculated.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertTrue(
            calculated.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .explicitPreferences.timeRanges
                .isEmpty(),
        )

        val missing = envelope.copy(locatedEvidence = envelope.locatedEvidence.filterNot { it == located })
        assertEquals(envelope.locatedEvidence.size - 1, missing.locatedEvidence.size)
        assertEquals(envelope.base, missing.base)
        assertEquals(envelope.calculationProjection, missing.calculationProjection)
        assertEquals(envelope.directionalApplications, missing.directionalApplications)
        assertAll(
            "A retained rejected declaration cannot bypass C5 source-proof coverage",
            Executable {
                assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralValidator().validateRestored(missing, snapshot) }
            },
            Executable {
                assertFailsWith<IllegalArgumentException> {
                    ScheduleDslStructuralCalculator.prepare(missing, snapshot, input.policy, input.meetingMode)
                }
            },
            Executable {
                assertFailsWith<IllegalArgumentException> {
                    ScheduleDslStructuralCalculator.calculate(missing, snapshot, input.policy, input.meetingMode)
                }
            },
        )
    }

    /** Converts only fixture provider evidence; every source and AST declaration remains unchanged. */
    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val fields = value.entries.associate { it.key as String to it.value }
                if ("start_code_point" in fields) {
                    require(fields.keys == setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"))
                    linkedMapOf("source_id" to fields["source_id"], "revision" to fields["revision"], "quote" to fields["quote"])
                } else {
                    fields.mapValues { locators(it.value) }.toMutableMap()
                }
            }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(value: Any?) = requireNotNull(value as? MutableMap<String, Any?>)

    companion object {
        private const val TIME_QUOTE = "2027-01-08 14:00부터 18:00까지 가능"
        private const val PLACE_QUOTE = "조용한 공간 선호"
    }
}
