package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic relation uncertainty cannot erase a separate, precise preference claim. */
class ScheduleIrV2IndependentPreferenceSafetyTest {
    private val f = ScheduleIrV2SyntheticFixture
    private val participant = ParticipantId(f.firstParticipant)
    private val friday = InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))
    private val preferred = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T11:00:00Z"))
    private val policy = IrMatchingPolicy(f.POLICY, setOf(IrInterpretation.EXPLICIT), emptyList(), false)

    @Test
    fun `V2P01 uncertain correction of nonprecise preference cannot erase independent precise preference credit`() {
        assertIndependentPreferenceSurvives(IrTemporalResolution.NON_PRECISE)
    }

    @Test
    fun `V2P02 uncertain correction of precise preference withholds target credit but preserves independent coincident credit`() {
        assertIndependentPreferenceSurvives(IrTemporalResolution.PRECISE_INTERVAL)
    }

    private fun assertIndependentPreferenceSurvives(resolution: IrTemporalResolution) {
        val independent = f.precise("independent-preference", f.preference).copy(effect = IrEffect.PREFERRED)
        val target =
            f.condition(
                "target",
                effect = IrEffect.PREFERRED,
                resolution = resolution,
                scope = if (resolution == IrTemporalResolution.PRECISE_INTERVAL) f.preference else null,
            )
        val left =
            f.precise("left", f.preference).copy(
                effect = IrEffect.PREFERRED,
                supersedes = listOf(target.id),
                alternativeGroup = "preference-correction",
                alternative = "left",
            )
        val right = left.copy(id = "right", alternative = "right")
        val original = f.envelope(listOf(f.precise("availability"), independent, target, left, right))

        val prepared = ScheduleIrV2MatchInputPolicy.project(original, policy)

        val first = prepared.participants.single { it.participantId == participant }
        assertEquals(listOf(friday), first.availableTimes)
        assertEquals(listOf(preferred), first.explicitPreferences.timeRanges)
        val claims =
            prepared.evidence
                .getValue(participant)
                .conditions
                .associateBy { it.conditionId }
        assertEquals(IrConditionDisposition.APPLIED, claims.getValue(independent.id).disposition)
        assertEquals(IrEffect.PREFERRED, claims.getValue(target.id).effect)
        assertEquals(resolution, claims.getValue(target.id).temporalResolution)
        assertEquals(target.scope, claims.getValue(target.id).scope)
        for (id in listOf(left.id, right.id)) {
            assertEquals(IrConditionDisposition.WITHHELD, claims.getValue(id).disposition)
        }
        assertEquals(
            listOf(independent, target, left, right).map(f::verified),
            original.results
                .first()
                .conditions
                .drop(1),
        )

        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options
        assertTrue(options.any { it.window == friday })
        assertTrue(options.any { it.window == preferred })
        for (option in options) {
            val inside = preferred.startInclusive <= option.window.startInclusive && option.window.endExclusive <= preferred.endExclusive
            assertEquals(if (inside) 1 else 0, option.variants.single().preferenceCount)
        }
    }
}
