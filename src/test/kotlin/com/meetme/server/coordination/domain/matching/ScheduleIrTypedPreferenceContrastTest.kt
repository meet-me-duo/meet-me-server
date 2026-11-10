package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrResult
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Manually assigned synthetic typed IR only. No lexical classification, model or family input. */
class ScheduleIrTypedPreferenceContrastTest {
    private val firstVersion = UUID(0, 901)
    private val secondVersion = UUID(0, 902)
    private val firstParticipant = ParticipantId(UUID(0, 903))
    private val secondParticipant = ParticipantId(UUID(0, 904))
    private val broad = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-08T18:00:00"), LocalDateTime.parse("2027-01-08T22:00:00"))
    private val preferred =
        IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-08T19:00:00"), LocalDateTime.parse("2027-01-08T20:00:00"))
    private val broadInstant = InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))
    private val preferredInstant = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T11:00:00Z"))
    private val source = "synthetic manually assigned typed condition"
    private val policy = IrMatchingPolicy("synthetic-typed-contrast-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

    @Test
    fun `T01 eligible preference alone never invents positive availability`() {
        val original = envelope(listOf(condition("preferred", IrEffect.PREFERRED, preferred)), IrAvailabilityState.UNKNOWN)
        val prepared = ScheduleIrMatchInputPolicy.project(original, policy)

        assertTrue(
            prepared.participants
                .single { it.participantId == firstParticipant }
                .availableTimes
                .isEmpty(),
        )
        assertEquals(IrAvailabilityState.UNKNOWN, prepared.evidence.getValue(firstParticipant).declaredAvailability)
        assertEquals(
            IrEffect.PREFERRED,
            original.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(MatchOutcome.NO_MATCH, DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).outcome)
        assertTrue(RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options.isEmpty())
    }

    @Test
    fun `T02 eligible preference preserves the full available range and scores only contained windows`() {
        val original =
            envelope(
                listOf(condition("available", IrEffect.AVAILABLE, broad), condition("preferred", IrEffect.PREFERRED, preferred)),
                IrAvailabilityState.KNOWN_WINDOWS,
            )
        val prepared = ScheduleIrMatchInputPolicy.project(original, policy)
        val input = prepared.participants.single { it.participantId == firstParticipant }

        assertEquals(listOf(broadInstant), input.availableTimes)
        assertEquals(listOf(preferredInstant), input.explicitPreferences.timeRanges)
        assertTrue(input.explicitPreferences.hasTimePreference)
        assertTrue(input.explicitPreferences.canScore)
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants)
        assertEquals(listOf(broadInstant), candidates.candidates.single().timeRanges)
        val projection = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul"))
        assertTrue(projection.options.any { it.window == broadInstant })
        assertTrue(projection.options.any { it.window == preferredInstant })
        for (option in projection.options) {
            assertTrue(
                broadInstant.startInclusive <= option.window.startInclusive && option.window.endExclusive <= broadInstant.endExclusive,
            )
            val contained =
                preferredInstant.startInclusive <= option.window.startInclusive &&
                    option.window.endExclusive <= preferredInstant.endExclusive
            assertEquals(if (contained) 1 else 0, option.variants.single().preferenceCount)
            assertEquals(listOf(firstParticipant, secondParticipant), option.variants.single().participantIds)
        }
        assertEquals(
            listOf(IrEffect.AVAILABLE, IrEffect.PREFERRED),
            original.results
                .first()
                .conditions
                .map { it.effect },
        )
    }

    private fun condition(
        id: String,
        effect: IrEffect,
        scope: IrScope,
    ) = IrCondition(
        id,
        effect,
        IrInterpretation.EXPLICIT,
        scope,
        listOf(IrEvidenceSpan("original", 3, 0, source.length, hash(source))),
        IrAssertion.ASSERTED,
        emptyList(),
        null,
        null,
    )

    private fun envelope(
        firstConditions: List<IrCondition>,
        state: IrAvailabilityState,
    ): ScheduleIrEnvelope {
        val reference = Instant.parse("2027-01-04T00:00:00Z")
        val sources = listOf(IrSourceBinding("original", IrSourceKind.ORIGINAL, 3, source.length, hash(source), reference))
        val inputs =
            listOf(
                IrInputBinding(firstVersion, firstParticipant.value, 7, 3, hash("synthetic first binding"), sources),
                IrInputBinding(secondVersion, secondParticipant.value, 7, 3, hash("synthetic second binding"), sources),
            )
        val context =
            ScheduleIrContext(
                UUID(0, 905),
                UUID(0, 906),
                UUID(0, 907),
                "Asia/Seoul",
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                policy.version,
                inputs,
            )
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(firstVersion, state, firstConditions, emptyList()),
                IrResult(
                    secondVersion,
                    IrAvailabilityState.KNOWN_WINDOWS,
                    listOf(condition("available", IrEffect.AVAILABLE, broad)),
                    emptyList(),
                ),
            ),
        )
    }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
