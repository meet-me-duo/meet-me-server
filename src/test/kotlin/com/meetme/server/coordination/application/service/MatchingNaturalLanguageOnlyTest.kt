package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.MatchingResultErrorCode
import com.meetme.server.coordination.application.port.input.MatchingResultException
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ManualAvailability
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.support.StaticMessageSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #83: synthetic archived versions are restored, never submitted through the deprecated API.
class MatchingNaturalLanguageOnlyTest {
    @Test
    fun `archived slots cannot narrow natural language candidates`() {
        val f = NaturalLanguageOnlyFixture(listOf("natural one", "natural two"))
        f.results(listOf(f.window(9, 13)), listOf(f.window(10, 14)))

        f.process()

        assertEquals(
            listOf(f.utc(10, 13)),
            f.singleCandidate().timeRanges,
        )
        assertEquals(CandidateQuality.COMPLETE, f.run.quality)
    }

    @Test
    fun `legacy slot-only participant stays in total but has empty availability for Plan C`() {
        val f = NaturalLanguageOnlyFixture(listOf("natural one", "natural two", null))
        f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)))

        f.process()

        val candidate = f.singleCandidate()
        assertEquals(PlanType.C, candidate.planType)
        assertEquals(3, candidate.totalParticipants)
        assertEquals(
            f.submissions
                .take(2)
                .map { it.participantId }
                .toSet(),
            candidate.participantIds.toSet(),
        )
        assertEquals(listOf(f.utc(10, 12)), candidate.timeRanges)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(2, view.appliedSubmissions)
        assertEquals(3, view.totalSubmissions)
        assertEquals(1, view.unappliedInputs)
    }

    @Test
    fun `one natural and one legacy cannot satisfy minimum two and host sees nullable unapplied on no match`() {
        val f = NaturalLanguageOnlyFixture(listOf("natural one", null))
        f.results(listOf(f.window(9, 12)))

        f.process()

        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertTrue(f.run.candidates.isEmpty())
        val service = f.resultService()
        val view = service.getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(1, view.appliedSubmissions)
        assertEquals(2, view.totalSubmissions)
        assertEquals(1, view.unappliedInputs)
        val unapplied = service.getUnappliedInputs(f.room.inviteCode.value, "host").single()
        assertEquals("participant 2", unapplied.participantDisplayName)
        assertNull(unapplied.rawText)
        assertEquals("LEGACY_MANUAL_ONLY_UNSUPPORTED", unapplied.reason)
        val denied =
            assertThrows<MatchingResultException> {
                service.getUnappliedInputs(f.room.inviteCode.value, "member")
            }
        assertEquals(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED, denied.code)
    }

    @Test
    fun `all archived slot-only inputs complete partial with no candidates rather than full boundary`() {
        val f = NaturalLanguageOnlyFixture(listOf(null, null))
        f.results()

        f.process()

        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertTrue(f.run.candidates.isEmpty())
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(0, view.appliedSubmissions)
        assertEquals(2, view.totalSubmissions)
        assertEquals(2, view.unappliedInputs)
    }

    @Test
    fun `place-only natural input uses full search boundary while another natural time narrows it`() {
        val f = NaturalLanguageOnlyFixture(listOf("place only", "time only"))
        f.results(listOf(StructuredCondition.SpecificPlace("station")), listOf(f.window(9, 13)))

        f.process()

        assertEquals(
            listOf(f.utc(9, 13)),
            f.singleCandidate().timeRanges,
        )
        assertEquals(CandidateQuality.COMPLETE, f.run.quality)
    }

    @Test
    fun `available alternatives union before unavailable subtraction and preserve all precise intervals`() {
        val f = NaturalLanguageOnlyFixture(listOf("alternatives", "broad"))
        f.results(
            listOf(f.window(9, 11), f.window(10, 12), f.window(14, 16), f.window(10, 11, TimePolarity.UNAVAILABLE)),
            listOf(f.window(8, 17)),
        )

        f.process()

        assertEquals(
            listOf(f.utc(9, 10), f.utc(11, 12), f.utc(14, 16)),
            f.singleCandidate().timeRanges,
        )
    }

    @Test
    fun `unavailable-only input subtracts from neutral base without archived slot constraints`() {
        val f = NaturalLanguageOnlyFixture(listOf("unavailable only", "available"))
        f.results(listOf(f.window(10, 11, TimePolarity.UNAVAILABLE)), listOf(f.window(9, 12)))

        f.process()

        assertEquals(
            listOf(f.utc(9, 10), f.utc(11, 12)),
            f.singleCandidate().timeRanges,
        )
    }

    @Test
    fun `contradiction consumes availability and touching half open boundaries do not overlap`() {
        val contradiction = NaturalLanguageOnlyFixture(listOf("contradiction", "available"))
        contradiction.results(
            listOf(contradiction.window(9, 12), contradiction.window(9, 12, TimePolarity.UNAVAILABLE)),
            listOf(contradiction.window(9, 12)),
        )
        contradiction.process()
        assertTrue(contradiction.run.candidates.isEmpty())
        assertEquals(CandidateQuality.COMPLETE, contradiction.run.quality)

        val touching = NaturalLanguageOnlyFixture(listOf("early", "late"))
        touching.results(listOf(touching.window(9, 10)), listOf(touching.window(10, 11)))
        touching.process()
        assertTrue(touching.run.candidates.isEmpty())
    }

    @Test
    fun `valid time survives partial rejection and all failed natural input retains neutral base`() {
        val partial = NaturalLanguageOnlyFixture(listOf("partially valid", "available"))
        partial.results(listOf(partial.window(9, 12)), listOf(partial.window(10, 13)))
        partial.structured =
            partial.structured.mapIndexed { index, result ->
                if (index == 0) result.copy(rejectionCode = "INVALID_CONDITION") else result
            }
        partial.process()
        assertEquals(
            listOf(partial.utc(10, 12)),
            partial.singleCandidate().timeRanges,
        )
        assertEquals(CandidateQuality.PARTIAL, partial.run.quality)

        val failed = NaturalLanguageOnlyFixture(listOf("all invalid", "available"))
        failed.results(emptyList(), listOf(failed.window(10, 13)))
        failed.structured =
            failed.structured.mapIndexed { index, result ->
                if (index == 0) result.copy(rejectionCode = "INVALID_CONDITION") else result
            }
        failed.process()
        assertEquals(
            listOf(failed.utc(10, 13)),
            failed.singleCandidate().timeRanges,
        )
        assertEquals(CandidateQuality.PARTIAL, failed.run.quality)
        assertEquals(CoordinationStatus.COMPLETED, failed.run.status)
    }

    @Test
    fun `existing completed and confirmed candidates are never recomputed or overwritten`() {
        val f = NaturalLanguageOnlyFixture(listOf("one", "two"))
        f.results(listOf(f.window(18, 20)), listOf(f.window(18, 20)))
        f.process()
        val completed = f.run
        f.results(listOf(f.window(9, 10)), listOf(f.window(9, 10)))
        f.process()
        assertEquals(completed, f.run)
        f.run = completed.confirm(completed.candidates.single().id, NaturalLanguageOnlyFixture.NOW.plusSeconds(60))
        val confirmed = f.run
        f.process()
        assertEquals(confirmed, f.run)
    }
}

internal class NaturalLanguageOnlyFixture(
    texts: List<String?>,
    mode: MeetingMode = MeetingMode.REMOTE,
) {
    var room =
        MeetingRoom.create(
            MeetingRoomId(UUID.randomUUID()),
            InviteCode.of("abcdefghijklmnopqrstuv"),
            "synthetic",
            mode,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(DATE, DATE.plusDays(2)),
            ClosurePolicy.of(expectedParticipants = texts.size),
            NOW,
        )
    val archivedSlots =
        listOf(
            ManualAvailability.Dated(DatedTimeRange(DATE, LocalTimeRange.of(LocalTime.of(18, 0), LocalTime.of(20, 0)))),
        )
    val submissions =
        texts.mapIndexed { index, text ->
            Submission.restore(
                SubmissionId(UUID.randomUUID()),
                room.id,
                ParticipantId(UUID(0, index.toLong() + 1)),
                SubmissionVersion(SubmissionVersionId(UUID.randomUUID()), 7, text, archivedSlots, Locale.KOREAN, NOW),
            )
        }
    val batch =
        SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, submissions.map { it.latest.id }, NOW)
    var run = CoordinationRun.queued(CoordinationRunId(UUID.randomUUID()), batch).startMatching()
    var structured = emptyList<StructuredSubmissionResult>()
    val runRepository = mock(CoordinationRunRepository::class.java)
    val roomRepository = mock(MeetingRoomRepository::class.java)
    val submissionRepository = mock(SubmissionRepository::class.java)
    val structuredRepository =
        object : StructuredSubmissionRepository {
            override fun findByBatch(batchId: SubmissionBatchId): List<StructuredSubmissionResult> = structured

            override fun replaceForBatch(
                batchId: SubmissionBatchId,
                results: List<StructuredSubmissionResult>,
                processedAt: Instant,
            ) {
                assertEquals(batch.id, batchId)
                structured = results
            }
        }
    val normalizedRepository = mock(NormalizedPlaceRepository::class.java)

    init {
        room = room.close(ClosureReason.EXPECTED_PARTICIPANTS, NOW, submissions.size).transition(activeRunId = run.id.value)
        `when`(roomRepository.findById(room.id)).thenReturn(room)
        `when`(roomRepository.findByIdForUpdate(room.id)).thenReturn(room)
        `when`(roomRepository.findByInviteCode(room.inviteCode)).thenReturn(room)
        `when`(submissionRepository.findLatestByRoom(room.id)).thenReturn(submissions)
        `when`(runRepository.findByBatchId(batch.id)).thenAnswer { run }
        `when`(runRepository.findById(run.id)).thenAnswer { run }
        `when`(runRepository.findByIdForUpdate(run.id)).thenAnswer { run }
        `when`(runRepository.findLatestByRoom(room.id)).thenAnswer { run }
        `when`(runRepository.findLatestByRoomForUpdate(room.id)).thenAnswer { run }
        doAnswer {
            run = it.getArgument(0)
            null
        }.`when`(runRepository).update(anyValue())
    }

    fun results(vararg conditions: List<StructuredCondition>) {
        structured =
            conditions.mapIndexed { index, value ->
                StructuredSubmissionResult(submissions[index].latest.id, value, if (value.isEmpty()) "INVALID_CONDITION" else null)
            }
    }

    fun process() {
        MatchingProcessor(
            runRepository,
            submissionRepository,
            structuredRepository,
            normalizedRepository,
            IdGenerator { UUID.randomUUID() },
            MatchingProcessingPersistenceService(roomRepository, runRepository, normalizedRepository),
        ).process(batch.id)
    }

    fun singleCandidate(): com.meetme.server.coordination.domain.MeetingCandidate {
        assertEquals(1, run.candidates.size, "Natural language must produce a candidate regardless of archived 18:00-20:00 slots")
        return run.candidates.single()
    }

    fun resultService(): MatchingResultService {
        val sessions = mock(GuestSessionRepository::class.java)
        val participants = mock(ParticipantRepository::class.java)
        val credentials = mock(GuestCredentialPort::class.java)
        listOf("host", "member").forEachIndexed { index, credential ->
            val session = GuestSession(GuestSessionId(UUID.randomUUID()), credential, NOW.plusSeconds(3600), null, NOW)
            val owner =
                if (index == 0) {
                    Participant.host(submissions[index].participantId, room.id, session.id, ParticipantDisplayName.of("participant 1"), NOW)
                } else {
                    Participant.member(
                        submissions[index].participantId,
                        room.id,
                        session.id,
                        ParticipantDisplayName.of("participant 2"),
                        NOW,
                    )
                }
            `when`(credentials.digest(credential)).thenReturn(credential)
            `when`(sessions.findByCredentialDigest(credential)).thenReturn(session)
            `when`(participants.findByRoomAndGuestSession(room.id, session.id)).thenReturn(owner)
            `when`(participants.findById(owner.id)).thenReturn(owner)
        }
        val messages = StaticMessageSource().apply { addMessage("candidate.summary.explicit", Locale.KOREAN, "{0}") }
        return MatchingResultService(
            roomRepository,
            sessions,
            participants,
            submissionRepository,
            structuredRepository,
            normalizedRepository,
            runRepository,
            credentials,
            messages,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    fun window(
        start: Int,
        end: Int,
        polarity: TimePolarity = TimePolarity.AVAILABLE,
    ) = StructuredCondition.TimeWindow(
        polarity,
        DATE,
        null,
        LocalTime.of(start, 0),
        LocalTime.of(end, 0),
    )

    fun utc(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        DATE.atTime(start, 0).toInstant(ZoneOffset.ofHours(9)),
        DATE.atTime(end, 0).toInstant(ZoneOffset.ofHours(9)),
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-09-19T00:00:00Z")
        val DATE: LocalDate = LocalDate.of(2026, 9, 21)

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            org.mockito.Mockito.any<T>()
            return null as T
        }
    }
}
