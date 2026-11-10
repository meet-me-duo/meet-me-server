package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.support.ScheduleDslSyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A claim-level correction cannot withdraw a negative through an AST path lacking authority. */
class ScheduleDslCorrectionAuthorityBoundaryTest {
    private val f = ScheduleDslSyntheticFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        Instant.parse("2027-01-08T${(start - 9).toString().padStart(2, '0')}:00:00Z"),
        Instant.parse("2027-01-08T${(end - 9).toString().padStart(2, '0')}:00:00Z"),
    )

    @ParameterizedTest
    @CsvSource(
        "leaf,QUOTED_OTHER,EXPLICIT",
        "leaf,RETRACTED,EXPLICIT",
        "ancestor,QUOTED_OTHER,EXPLICIT",
        "ancestor,RETRACTED,EXPLICIT",
        "leaf,ASSERTED,INFERRED",
        "ancestor,ASSERTED,INFERRED",
    )
    fun `DSLAUTH01 correction without eligible asserted AST path cannot reopen the old negative`(
        location: String,
        assertion: String,
        interpretation: String,
    ) {
        val base = f.claim("base", 18, 22)
        val old = f.claim("old-negative", 19, 20, IrEffect.UNAVAILABLE)
        val correction = f.claim("correction", 20, 21).copy(supersedes = listOf(old.id))
        val claims = listOf(base, old, correction)
        var correctionRef = f.ref("correction-ref", correction.id)
        var ancestor = f.node("ancestor", ScheduleDslOperator.ALL_OF, "correction-ref", "base-ref")
        if (location == "leaf") {
            correctionRef =
                correctionRef.copy(assertion = IrAssertion.valueOf(assertion), interpretation = IrInterpretation.valueOf(interpretation))
        } else {
            ancestor = ancestor.copy(assertion = IrAssertion.valueOf(assertion), interpretation = IrInterpretation.valueOf(interpretation))
        }
        val program =
            f.program(
                listOf(
                    f.ref("base-ref", base.id),
                    f.ref("old-ref", old.id),
                    correctionRef,
                    ancestor,
                    f.node("root", ScheduleDslOperator.ANY_OF, "base-ref", "ancestor"),
                ),
                "root",
                block = "old-ref",
            )
        val expected = f.envelope(claims, program)
        val envelope = ScheduleDslValidator().validate(f.proposal(claims, program), f.ir.snapshot())
        // Structurally valid, source-verified graphs must reach the numeric authority boundary.
        assertEquals(expected, envelope)

        val prepared = ScheduleDslInputPolicy.project(envelope, f.policy)

        val forbidden = window(19, 20)
        val available = prepared.participants.single { it.participantId == first }.availableTimes
        assertTrue(
            available.none { it.startInclusive < forbidden.endExclusive && forbidden.startInclusive < it.endExclusive },
            "Untrusted AST correction must not reopen19-20; local isolation or safe program withholding are both permitted",
        )
        val baseWindow = window(18, 22)
        assertTrue(available.all { baseWindow.startInclusive <= it.startInclusive && it.endExclusive <= baseWindow.endExclusive })
        // Full program withholding may remove numeric support, but never claim meaning or the independent peer.
        assertEquals(
            claims.map(f.ir::verified),
            envelope.claims.results
                .first()
                .conditions,
        )
        assertEquals(
            IrEffect.UNAVAILABLE,
            prepared.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == old.id }
                .effect,
        )
        assertEquals(
            IrEffect.AVAILABLE,
            prepared.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == base.id }
                .effect,
        )
        assertEquals(listOf(baseWindow), prepared.participants.single { it.participantId == peer }.availableTimes)
        assertEquals(expected.claims.results.last(), envelope.claims.results.last())
        assertEquals(expected.programs.last(), envelope.programs.last())
    }

    @Test
    fun `DSLAUTH02 ungrouped correction present only in an unselected valid choice branch cannot withdraw the old negative`() {
        val base = f.claim("base", 18, 22)
        val old = f.claim("old-negative", 19, 20, IrEffect.UNAVAILABLE)
        val correction = f.claim("correction", 20, 21).copy(supersedes = listOf(old.id))
        val left = f.claim("left-claim", 18, 19).copy(alternativeGroup = "g", alternative = "left")
        val right = f.claim("right-claim", 20, 21).copy(alternativeGroup = "g", alternative = "right")
        val claims = listOf(base, old, correction, left, right)
        val choice =
            f
                .node(
                    "choice",
                    ScheduleDslOperator.CHOICE,
                    "left-ref",
                    "right-branch",
                ).copy(choiceGroup = "g", branchLabels = listOf("left", "right"))
        val program =
            f.program(
                listOf(
                    f.ref("base-ref", base.id),
                    f.ref("old-ref", old.id),
                    f.ref("correction-ref", correction.id),
                    f.ref("left-ref", left.id),
                    f.ref("right-ref", right.id),
                    f.node("right-branch", ScheduleDslOperator.ALL_OF, "correction-ref", "right-ref"),
                    choice,
                    f.node("root", ScheduleDslOperator.ANY_OF, "base-ref", "choice"),
                ),
                "root",
                block = "old-ref",
            )
        val expected = f.envelope(claims, program)
        val envelope = ScheduleDslValidator().validate(f.proposal(claims, program), f.ir.snapshot())
        assertEquals(expected, envelope)
        assertEquals(null, correction.alternativeGroup)
        val policy = f.policy.copy(alternativeChoices = listOf(IrAlternativeChoice(f.ir.first, "g", "left")))

        val prepared = ScheduleDslInputPolicy.project(envelope, policy)

        val forbidden = window(19, 20)
        val available = prepared.participants.single { it.participantId == first }.availableTimes
        assertTrue(
            available.none { it.startInclusive < forbidden.endExclusive && forbidden.startInclusive < it.endExclusive },
            "A correction reachable only through the unselected branch has no authority to reopen19-20",
        )
        val baseWindow = window(18, 22)
        assertTrue(available.all { baseWindow.startInclusive <= it.startInclusive && it.endExclusive <= baseWindow.endExclusive })
        assertEquals(
            claims.map(f.ir::verified),
            envelope.claims.results
                .first()
                .conditions,
        )
        assertEquals(
            IrEffect.UNAVAILABLE,
            prepared.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == old.id }
                .effect,
        )
        assertEquals(
            IrEffect.AVAILABLE,
            prepared.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == base.id }
                .effect,
        )
        assertEquals(listOf(baseWindow), prepared.participants.single { it.participantId == peer }.availableTimes)
        assertEquals(expected.claims.results.last(), envelope.claims.results.last())
        assertEquals(expected.programs.last(), envelope.programs.last())
    }
}
