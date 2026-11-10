package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.coordination.domain.matching.RecommendationParticipantStatus
import com.meetme.server.coordination.domain.matching.RecommendationParticipantStatusPolicy
import com.meetme.server.coordination.domain.matching.RecommendationVariant
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.submission.domain.TimePolarity as Polarity

/** Frozen legacy projection contract; manually typed synthetic conditions are not language/model oracles. */
class Issue222FrozenMatchingInputPolicyTest {
    private val day = LocalDate.parse("2065-06-05")
    private val at = Instant.parse("2065-05-01T00:00:00Z")
    private val roomId = MeetingRoomId(UUID(0, 101))
    private val batch = SubmissionBatchId(UUID(0, 102))
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `FMI01 reconstruction never revives manual legacy evidence and labels missing frozen versions`() {
        val plain = submission(1)
        val manual =
            submission(2).let {
                Submission.restore(it.id, it.roomId, it.participantId, it.latest.copy(rawText = null))
            }
        val missing = submission(3)
        val existing = listOf(result(plain, time(9, 12)), result(manual, time(9, 12)))
        val rebuilt = FrozenMatchingInputPolicy.reconstruct(listOf(plain, manual, missing), existing)
        assertEquals(listOf(plain.latest.id, manual.latest.id, missing.latest.id), rebuilt.results.map { it.submissionVersionId })
        assertEquals("LEGACY_MANUAL_ONLY_UNSUPPORTED", rebuilt.legacyResults.single().rejectionCode)
        assertEquals("STRUCTURED_RESULT_MISSING", rebuilt.missingResults.single().rejectionCode)
        assertTrue(
            rebuilt.legacyResults
                .single()
                .conditions
                .isEmpty(),
        )
        val prepared = FrozenMatchingInputPolicy.prepare(room(), listOf(plain, manual, missing), rebuilt.results, batch)
        assertEquals(listOf(window(9, 12)), input(prepared, plain).availableTimes)
        assertTrue(input(prepared, manual).availableTimes.isEmpty())
        assertTrue(input(prepared, missing).availableTimes.isEmpty())
        assertTrue(prepared.hasUnappliedInput)
    }

    @Test
    fun `FMI02 plain precise time subtraction and preference windows match the frozen producer arithmetic`() {
        val person = submission(1)
        val peer = submission(2)
        val preferred = StructuredCondition.PreferredTimeWindow(day, null, LocalTime.of(11, 0), LocalTime.NOON)
        val results = listOf(result(person, time(9, 12), time(10, 11, Polarity.UNAVAILABLE), preferred), result(peer, time(9, 12)))
        val prepared = FrozenMatchingInputPolicy.prepare(room(), listOf(person, peer), results, batch)
        assertEquals(listOf(window(9, 10), window(11, 12)), input(prepared, person).availableTimes)
        assertEquals(listOf(window(11, 12)), input(prepared, person).explicitPreferences.timeRanges)
        assertTrue(input(prepared, person).explicitPreferences.canScore)
        assertFalse(prepared.hasUnappliedInput)
        val evidence = FrozenMatchingInputPolicy.evidence(listOf(person, peer), results, prepared)
        assertTrue(evidence.getValue(person.participantId).interpretationComplete)
        assertTrue(evidence.getValue(person.participantId).timeKnown)
    }

    @Test
    fun `FMI03 unsafe time reasons suppress apparently valid time while a rejected partial input stays unknown`() {
        val person = submission(1)
        val peer = submission(2)
        for (reason in listOf("UNSUPPORTED_CONDITIONAL_CONSTRAINT", "AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT")) {
            val results = listOf(result(person, time(9, 12)).copy(rejectionCode = reason), result(peer, time(9, 12)))
            val prepared = FrozenMatchingInputPolicy.prepare(room(), listOf(person, peer), results, batch)
            assertTrue(input(prepared, person).availableTimes.isEmpty(), reason)
            val evidence = FrozenMatchingInputPolicy.evidence(listOf(person, peer), results, prepared)
            assertFalse(evidence.getValue(person.participantId).timeKnown)
            assertFalse(evidence.getValue(person.participantId).interpretationComplete)
        }
        val partial = listOf(result(person, time(9, 12)).copy(rejectionCode = "SYNTHETIC_CONDITION_REJECTED"), result(peer, time(9, 12)))
        val prepared = FrozenMatchingInputPolicy.prepare(room(), listOf(person, peer), partial, batch)
        assertEquals(listOf(window(9, 12)), input(prepared, person).availableTimes)
        assertFalse(input(prepared, person).explicitPreferences.canScore)
        val evidence = FrozenMatchingInputPolicy.evidence(listOf(person, peer), partial, prepared)
        assertFalse(evidence.getValue(person.participantId).interpretationComplete)
        assertTrue(evidence.getValue(peer.participantId).interpretationComplete)
    }

    @Test
    fun `FMI04 conditional fallback is resolved for the entire cohort before any attendance subset`() {
        val people = listOf(submission(1), submission(2), submission(3))
        val fallback =
            StructuredCondition.ConditionalFallbackTimeWindow(
                ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
                day,
                null,
                LocalTime.of(14, 0),
                LocalTime.of(16, 0),
            )
        val results = listOf(result(people[0], time(9, 10), fallback), result(people[1], time(14, 15)), result(people[2], time(14, 15)))
        val prepared = FrozenMatchingInputPolicy.prepare(room(), people, results, batch)
        assertEquals(listOf(window(9, 10), window(14, 16)), input(prepared, people[0]).availableTimes)
        assertTrue(prepared.resolution.unsupportedParticipantIds.isEmpty())
        val primaryFits = listOf(result(people[0], time(14, 15), fallback), results[1], results[2])
        val primary = FrozenMatchingInputPolicy.prepare(room(), people, primaryFits, batch)
        assertEquals(listOf(window(14, 15)), input(primary, people[0]).availableTimes)
    }

    @Test
    fun `FMI05 missing peer evidence cannot enable a fallback for a surviving subset`() {
        val people = listOf(submission(1), submission(2), submission(3))
        val fallback =
            StructuredCondition.ConditionalFallbackTimeWindow(
                ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
                day,
                null,
                LocalTime.of(14, 0),
                LocalTime.of(16, 0),
            )
        val original = listOf(result(people[0], time(9, 10), fallback), result(people[1], time(14, 15)))
        val reconstructed = FrozenMatchingInputPolicy.reconstruct(people, original)
        val prepared = FrozenMatchingInputPolicy.prepare(room(), people, reconstructed.results, batch)
        assertTrue(input(prepared, people[0]).availableTimes.isEmpty())
        assertEquals(setOf(people[0].participantId), prepared.resolution.unsupportedParticipantIds)
        assertEquals(listOf(window(14, 15)), input(prepared, people[1]).availableTimes)
        val evidence = FrozenMatchingInputPolicy.evidence(people, original, prepared)
        assertFalse(evidence.getValue(people[0].participantId).timeKnown)
        assertTrue(evidence.getValue(people[1].participantId).timeKnown)
        assertFalse(evidence.getValue(people[2].participantId).interpretationComplete)
    }

    @Test
    fun `FMI06 missing peer blocks inferred historical AREA identity but not explicit stable AREA identity`() {
        val people = listOf(submission(1), submission(2), submission(3))
        val original =
            listOf(
                result(people[0], time(9, 12), StructuredCondition.SpecificPlace("Synthetic Zulu")),
                result(
                    people[2],
                    time(9, 12),
                    StructuredCondition.SpecificPlace("Synthetic Alpha", areaKey = "AREA_7", areaName = "Fixed"),
                ),
            )
        val reconstructed = FrozenMatchingInputPolicy.reconstruct(people, original)
        val prepared = FrozenMatchingInputPolicy.prepare(room(MeetingMode.IN_PERSON), people, reconstructed.results, batch)
        val evidence = FrozenMatchingInputPolicy.evidence(people, original, prepared)
        assertFalse(evidence.getValue(people[0].participantId).placeReferenceKnown)
        assertTrue(evidence.getValue(people[2].participantId).placeReferenceKnown)
        assertEquals(
            "AREA_7",
            input(prepared, people[2])
                .offlineArea
                ?.alternatives
                ?.single()
                ?.key,
        )
        assertFalse(evidence.getValue(people[1].participantId).interpretationComplete)
    }

    @Test
    fun `FMI07 unverified travel and unresolved location preserve valid time with owned uncertainty witnesses`() {
        val person = submission(1)
        val peer = submission(2)
        val results =
            listOf(
                result(
                    person,
                    time(9, 12),
                    StructuredCondition.TravelConstraint("Synthetic travel bound"),
                    StructuredCondition.UnresolvedPlace("Synthetic origin"),
                ),
                result(peer, time(9, 12)),
            )
        val prepared = FrozenMatchingInputPolicy.prepare(room(MeetingMode.IN_PERSON), listOf(person, peer), results, batch)
        assertEquals(listOf(window(9, 12)), input(prepared, person).availableTimes)
        assertEquals(OfflinePlaceAssessment.UNVERIFIED, input(prepared, person).offlinePlaceAssessment)
        assertEquals(OfflinePlaceAssessment.UNRESTRICTED, input(prepared, peer).offlinePlaceAssessment)
        assertTrue(prepared.hasUnappliedInput)
        val snapshot = prepared.snapshots.single()
        assertEquals(person.latest.id, snapshot.submissionVersionId)
        assertEquals(NormalizedPlaceStatus.NO_EXACT_MATCH, snapshot.status)
    }

    @Test
    fun `FMI08 a per person place read failure never makes a verified peer unknown`() {
        val person = submission(1)
        val peer = submission(2)
        val results = listOf(result(person, time(9, 12)), result(peer, time(9, 12)))
        val prepared = FrozenMatchingInputPolicy.prepare(room(MeetingMode.IN_PERSON), listOf(person, peer), results, batch)
        val evidence = FrozenMatchingInputPolicy.evidence(listOf(person, peer), results, prepared, setOf(person.latest.id))
        assertEquals(OfflinePlaceAssessment.UNVERIFIED, evidence.getValue(person.participantId).input?.offlinePlaceAssessment)
        assertEquals(OfflinePlaceAssessment.UNRESTRICTED, evidence.getValue(peer.participantId).input?.offlinePlaceAssessment)
        val variant = RecommendationVariant(MeetingMode.IN_PERSON, listOf(person.participantId, peer.participantId))
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            RecommendationParticipantStatusPolicy.assess(
                person.participantId,
                window(9, 12),
                variant,
                evidence.getValue(person.participantId),
                MeetingMode.IN_PERSON,
            ),
        )
        assertEquals(
            RecommendationParticipantStatus.AVAILABLE,
            RecommendationParticipantStatusPolicy.assess(
                peer.participantId,
                window(9, 12),
                variant,
                evidence.getValue(peer.participantId),
                MeetingMode.IN_PERSON,
            ),
        )
    }

    private fun input(
        prepared: FrozenMatchingInputPolicy.PreparedInputs,
        person: Submission,
    ) = prepared.resolution.participants.single { it.participantId == person.participantId }

    private fun result(
        person: Submission,
        vararg conditions: StructuredCondition,
    ) = StructuredSubmissionResult(person.latest.id, conditions.toList(), null)

    private fun time(
        start: Int,
        end: Int,
        polarity: Polarity = Polarity.AVAILABLE,
    ) = StructuredCondition.TimeWindow(polarity, day, null, LocalTime.of(start, 0), LocalTime.of(end, 0))

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(day.atTime(start, 0).atZone(zone).toInstant(), day.atTime(end, 0).atZone(zone).toInstant())

    private fun submission(index: Long): Submission =
        Submission.start(
            SubmissionId(UUID(0, 200 + index)),
            roomId,
            ParticipantId(UUID(0, 300 + index)),
            SubmissionVersionId(UUID(0, 400 + index)),
            "Synthetic input $index",
            emptyList(),
            Locale.ENGLISH,
            at,
        )

    private fun room(mode: MeetingMode = MeetingMode.REMOTE): MeetingRoom =
        MeetingRoom.create(
            roomId,
            InviteCode.of("abcdefghijklmnopqrstuv"),
            "Synthetic frozen matching",
            mode,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(day, day.plusDays(2)),
            ClosurePolicy.of(expectedParticipants = 3),
            at,
        )
}
