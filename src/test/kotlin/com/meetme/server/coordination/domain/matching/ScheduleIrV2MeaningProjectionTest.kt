package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic manually assigned IR2: semantic certainty is never equated with a numeric interval. */
class ScheduleIrV2MeaningProjectionTest {
    private val f = ScheduleIrV2SyntheticFixture
    private val participant = ParticipantId(f.firstParticipant)
    private val friday = interval("2027-01-08T09:00:00Z", "2027-01-08T13:00:00Z")

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED"])
    fun `V2D01 known nonprecise meaning survives evidence but cannot create numeric availability`(name: String) {
        val effect = IrEffect.valueOf(name)
        val original = f.envelope(listOf(f.condition(effect = effect)))
        val prepared = ScheduleIrV2MatchInputPolicy.project(original, policy())

        assertTrue(times(prepared).isEmpty())
        val claim =
            prepared.evidence
                .getValue(participant)
                .conditions
                .single()
        assertEquals(effect, claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(IrInterpretation.EXPLICIT, claim.interpretation)
        assertEquals(
            effect,
            original.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(MatchOutcome.NO_MATCH, DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).outcome)
        assertTrue(RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED"])
    fun `V2D02 unscoped nonprecise positive meaning never becomes a global negative block on clear Friday`(name: String) {
        val effect = IrEffect.valueOf(name)
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.precise(), f.condition("nonprecise", effect))), policy())

        assertEquals(listOf(friday), times(prepared))
        assertEquals(
            listOf(friday),
            DeterministicCandidateMatcher
                .generate(MeetingMode.REMOTE, prepared.participants)
                .candidates
                .single()
                .timeRanges,
        )
        assertEquals(
            listOf(
                friday,
            ),
            RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options.map {
                it.window
            },
        )
        assertEquals(
            effect,
            prepared.evidence
                .getValue(participant)
                .conditions
                .single { it.conditionId == "nonprecise" }
                .effect,
        )
        assertTrue(
            prepared.participants
                .single { it.participantId == participant }
                .explicitPreferences.timeRanges
                .isEmpty(),
        )
    }

    @Test
    fun `V2D03 numeric influence bounds of nonprecise availability are not a precise positive window`() {
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.condition(scope = f.friday))), policy())
        assertTrue(times(prepared).isEmpty())
        val claim =
            prepared.evidence
                .getValue(participant)
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(f.friday, claim.scope)
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `V2D04 explicit label eligibility cannot rewrite known meaning or synthesize missing numbers`(label: IrInterpretation) {
        val original = f.envelope(listOf(f.condition(interpretation = label)))
        val prepared = ScheduleIrV2MatchInputPolicy.project(original, policy(setOf(IrInterpretation.EXPLICIT, label)))
        val claim =
            prepared.evidence
                .getValue(participant)
                .conditions
                .single()

        assertEquals(IrEffect.AVAILABLE, claim.effect)
        assertEquals(label, claim.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertTrue(times(prepared).isEmpty())
        assertEquals(
            label,
            original.results
                .first()
                .conditions
                .single()
                .interpretation,
        )
    }

    @ParameterizedTest
    @CsvSource("AVAILABLE,QUOTED_OTHER", "AVAILABLE,RETRACTED", "UNAVAILABLE,QUOTED_OTHER", "UNAVAILABLE,RETRACTED")
    fun `V2D05 quoted or retracted meaning is history and cannot change independent availability`(
        effect: String,
        assertion: String,
    ) {
        val history = f.condition("history", IrEffect.valueOf(effect), assertion = IrAssertion.valueOf(assertion))
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.precise(), history)), policy())
        val claim =
            prepared.evidence
                .getValue(participant)
                .conditions
                .single { it.conditionId == "history" }

        assertEquals(listOf(friday), times(prepared))
        assertEquals(IrEffect.valueOf(effect), claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(IrConditionDisposition.EXCLUDED_ASSERTION, claim.disposition)
    }

    @ParameterizedTest
    @CsvSource("UNAVAILABLE,bounded", "UNAVAILABLE,global", "UNRESOLVED,bounded", "UNRESOLVED,global")
    fun `V2D06 nonprecise negative or unresolved meaning retains conservative influence without relabeling`(
        effect: String,
        bound: String,
    ) {
        val scope = if (bound == "bounded") f.preference else null
        val uncertain = f.condition("negative", IrEffect.valueOf(effect), scope = scope)
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.precise(), uncertain)), policy())
        val expected =
            if (bound ==
                "global"
            ) {
                emptyList()
            } else {
                listOf(interval("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z"), interval("2027-01-08T11:00:00Z", "2027-01-08T13:00:00Z"))
            }

        assertEquals(expected, times(prepared))
        val claim =
            prepared.evidence
                .getValue(participant)
                .conditions
                .single { it.conditionId == "negative" }
        assertEquals(IrEffect.valueOf(effect), claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(scope, claim.scope)
        assertTrue(prepared.evidence.getValue(participant).unresolvedTime)
    }

    @Test
    fun `V2D07 known nonprecise positive correction supersedes old positive without deleting independent clear time`() {
        val base = f.precise("base", f.scope("2027-01-08T18:00:00", "2027-01-08T19:00:00"))
        val old = f.precise("old", f.scope("2027-01-08T20:00:00", "2027-01-08T21:00:00"))
        val correction = f.condition("correction", supersedes = listOf("old"))
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(base, old, correction)), policy())

        assertEquals(listOf(interval("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z")), times(prepared))
        val claims =
            prepared.evidence
                .getValue(participant)
                .conditions
                .associateBy { it.conditionId }
        assertEquals(IrConditionDisposition.SUPERSEDED, claims.getValue("old").disposition)
        assertEquals(IrEffect.AVAILABLE, claims.getValue("correction").effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claims.getValue("correction").temporalResolution)
    }

    @Test
    fun `V2D08 missing numbers alone cannot revive an old negative superseded by an eligible known positive correction`() {
        val old = f.precise("old-negative", f.preference).copy(effect = IrEffect.UNAVAILABLE)
        val correction = f.condition("correction", supersedes = listOf(old.id))
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.precise("base"), old, correction)), policy())

        assertEquals(listOf(friday), times(prepared))
        val claims =
            prepared.evidence
                .getValue(participant)
                .conditions
                .associateBy { it.conditionId }
        assertEquals(IrConditionDisposition.SUPERSEDED, claims.getValue(old.id).disposition)
        assertEquals(IrEffect.AVAILABLE, claims.getValue("correction").effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claims.getValue("correction").temporalResolution)
    }

    @Test
    fun `V2D09 precise preference retains broad availability and only scores fully contained options`() {
        val preference = f.precise("preferred", f.preference).copy(effect = IrEffect.PREFERRED)
        val prepared = ScheduleIrV2MatchInputPolicy.project(f.envelope(listOf(f.precise(), preference)), policy())
        val preferredWindow = interval("2027-01-08T10:00:00Z", "2027-01-08T11:00:00Z")
        val first = prepared.participants.single { it.participantId == participant }

        assertEquals(listOf(friday), first.availableTimes)
        assertEquals(listOf(preferredWindow), first.explicitPreferences.timeRanges)
        val projected = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul"))
        assertTrue(projected.options.any { it.window == friday })
        assertTrue(projected.options.any { it.window == preferredWindow })
        for (option in projected.options) {
            val inside =
                preferredWindow.startInclusive <= option.window.startInclusive && option.window.endExclusive <= preferredWindow.endExclusive
            assertEquals(if (inside) 1 else 0, option.variants.single().preferenceCount)
        }
    }

    private fun policy(labels: Set<IrInterpretation> = setOf(IrInterpretation.EXPLICIT)) =
        IrMatchingPolicy(f.POLICY, labels, emptyList(), false)

    private fun times(input: IrV2PreparedMatchingInputs) = input.participants.single { it.participantId == participant }.availableTimes

    private fun interval(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))
}
