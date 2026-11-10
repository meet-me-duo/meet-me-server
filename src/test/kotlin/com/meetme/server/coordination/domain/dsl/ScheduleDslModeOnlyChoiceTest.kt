package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ProposedScheduleModeRestriction
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** A mode choice is globally valid without manufacturing an alternative group in the independent availability claims. */
class ScheduleDslModeOnlyChoiceTest {
    @ParameterizedTest
    @ValueSource(strings = ["remote", "inperson"])
    fun `MOC01 mode-only alternatives choose the permitted mode without leaking their group into base availability`(selected: String) {
        val selectedMode = if (selected == "remote") MeetingMode.REMOTE else MeetingMode.IN_PERSON
        val excludedMode = if (selectedMode == MeetingMode.REMOTE) MeetingMode.IN_PERSON else MeetingMode.REMOTE
        val choice = IrAlternativeChoice(F.ir.first, "mode-group", selected)
        val policy = ScheduleDslContextModePolicy.contextual(listOf(choice))
        val input = F.input("🙂 2027-01-08 14:00부터 18:00까지 가능. 모드는 비대면 또는 대면 중 하나를 선택.").copy(policy = policy)
        val snapshot = F.snapshot(input)
        val scope = F.scope("2027-01-08T14:00", "2027-01-08T18:00")
        val base =
            ScheduleDslApprovedTermsValidator().validate(
                F.proposal(input, claims = listOf(F.claim(input, scope = scope)), terms = emptyList()),
                snapshot,
            )
        assertEquals(snapshot.context, base.declaration.context)
        assertTrue(
            base.declaration.claims.results
                .flatMap { it.conditions }
                .all { it.alternativeGroup == null },
        )
        val restrictions =
            listOf("remote" to MeetingMode.REMOTE, "inperson" to MeetingMode.IN_PERSON).map { (alternative, mode) ->
                ProposedScheduleModeRestriction(
                    submissionVersionId = F.ir.first,
                    id = "mode-$alternative",
                    scope = scope,
                    permittedModes = setOf(mode),
                    modeInterpretation = IrInterpretation.EXPLICIT,
                    timeInterpretation = IrInterpretation.EXPLICIT,
                    temporalResolution = IrTemporalResolution.PRECISE_INTERVAL,
                    evidence = listOf(F.evidence(input, input.inputs.first().rawText)),
                    alternativeGroup = "mode-group",
                    alternative = alternative,
                )
            }
        val envelope = ScheduleDslContextModeValidator().validateDeclaration(base, restrictions, snapshot)
        val invalidPolicy = ScheduleDslContextModePolicy.contextual(listOf(choice.copy(group = "unknown-group")))
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslContextModeCalculator.calculate(envelope, snapshot, invalidPolicy, MeetingMode.EITHER)
        }
        val result = ScheduleDslContextModeCalculator.calculate(envelope, snapshot, policy, MeetingMode.EITHER)
        val first = ParticipantId(F.ir.firstParticipant)
        val peer = ParticipantId(F.ir.secondParticipant)
        val expected =
            listOf(
                InstantTimeRange(
                    LocalDateTime.parse("2027-01-08T14:00").atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                    LocalDateTime.parse("2027-01-08T18:00").atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ),
            )
        assertEquals(
            expected,
            result.preparedByMode
                .getValue(selectedMode)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertTrue(
            result.preparedByMode
                .getValue(excludedMode)
                .participants
                .single { it.participantId == first }
                .availableTimes
                .isEmpty(),
        )
        for (mode in listOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON)) {
            assertEquals(
                expected,
                result.preparedByMode
                    .getValue(mode)
                    .participants
                    .single { it.participantId == peer }
                    .availableTimes,
            )
        }
        val candidate = result.candidates.candidates.single()
        assertEquals(selectedMode, candidate.meetingMode)
        assertEquals(setOf(first, peer), candidate.participantIds.toSet())
        assertEquals(expected, candidate.timeRanges)
        val recommendation = result.recommendations.options.single()
        assertEquals(expected.single(), recommendation.window)
        assertEquals(selectedMode, recommendation.variants.single().meetingMode)
        assertEquals(base, envelope.base)
        assertEquals(setOf("remote", "inperson"), envelope.modeRestrictions.map { requireNotNull(it.alternative) }.toSet())
        assertEquals(listOf(choice), policy.alternativeChoices)
    }
}
