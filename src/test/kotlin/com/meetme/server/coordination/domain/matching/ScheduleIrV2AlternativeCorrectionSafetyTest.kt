package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Manually assigned synthetic IR; missing branch selection cannot invent a hard negative. */
class ScheduleIrV2AlternativeCorrectionSafetyTest {
    private val f = ScheduleIrV2SyntheticFixture
    private val participant = ParticipantId(f.firstParticipant)
    private val friday = InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))
    private val policy = IrMatchingPolicy(f.POLICY, setOf(IrInterpretation.EXPLICIT), emptyList(), false)

    @Test
    fun `V2A01 unselected positive correction of nonprecise positive target cannot globally erase independent Friday`() {
        val target = f.condition("target")
        val left = f.condition("left", supersedes = listOf(target.id)).copy(alternativeGroup = "correction", alternative = "left")
        val right = f.condition("right", supersedes = listOf(target.id)).copy(alternativeGroup = "correction", alternative = "right")
        val original = f.envelope(listOf(f.precise("independent"), target, left, right))

        val prepared = ScheduleIrV2MatchInputPolicy.project(original, policy)

        assertEquals(listOf(friday), prepared.participants.single { it.participantId == participant }.availableTimes)
        val claims =
            prepared.evidence
                .getValue(participant)
                .conditions
                .associateBy { it.conditionId }
        for (condition in listOf(target, left, right)) {
            assertEquals(IrEffect.AVAILABLE, claims.getValue(condition.id).effect)
            assertEquals(IrTemporalResolution.NON_PRECISE, claims.getValue(condition.id).temporalResolution)
            assertEquals(null, claims.getValue(condition.id).scope)
        }
        for (id in listOf(left.id, right.id)) {
            assertEquals(IrConditionDisposition.WITHHELD, claims.getValue(id).disposition)
        }
        assertTrue(prepared.evidence.getValue(participant).unresolvedTime)
        assertEquals(
            listOf(target, left, right).map(f::verified),
            original.results
                .first()
                .conditions
                .drop(1),
        )
    }

    @Test
    fun `V2A02 unselected preference correction cannot cut hard availability at the preference target`() {
        val target = f.precise("preferred-target", f.preference).copy(effect = IrEffect.PREFERRED)
        val left =
            target.copy(
                id = "left",
                supersedes = listOf(target.id),
                alternativeGroup = "preference-correction",
                alternative = "left",
            )
        val right =
            target.copy(
                id = "right",
                supersedes = listOf(target.id),
                alternativeGroup = "preference-correction",
                alternative = "right",
            )
        val original = f.envelope(listOf(f.precise("independent"), target, left, right))

        val prepared = ScheduleIrV2MatchInputPolicy.project(original, policy)

        assertEquals(listOf(friday), prepared.participants.single { it.participantId == participant }.availableTimes)
        val claims =
            prepared.evidence
                .getValue(participant)
                .conditions
                .associateBy { it.conditionId }
        for (condition in listOf(target, left, right)) {
            assertEquals(IrEffect.PREFERRED, claims.getValue(condition.id).effect)
            assertEquals(IrTemporalResolution.PRECISE_INTERVAL, claims.getValue(condition.id).temporalResolution)
            assertEquals(f.preference, claims.getValue(condition.id).scope)
        }
        for (id in listOf(left.id, right.id)) {
            assertEquals(IrConditionDisposition.WITHHELD, claims.getValue(id).disposition)
        }
        assertEquals(
            listOf(target, left, right).map(f::verified),
            original.results
                .first()
                .conditions
                .drop(1),
        )
    }
}
