package com.meetme.server.application.service

import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.IdGenerator
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.NormalizedPlace
import com.meetme.server.application.port.output.NormalizedPlaceRepository
import com.meetme.server.application.port.output.NormalizedPlaceStatus
import com.meetme.server.application.port.output.PlaceNormalizationResult
import com.meetme.server.application.port.output.PlaceSearchPort
import com.meetme.server.application.port.output.StructuredSubmissionRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.CoordinationStatus
import com.meetme.server.domain.coordination.SubmissionBatch
import com.meetme.server.domain.meeting.ClosurePolicy
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.submission.StructuredCondition
import com.meetme.server.domain.submission.StructuredSubmissionResult
import com.meetme.server.domain.submission.Submission
import com.meetme.server.domain.submission.TimePolarity
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingProcessorTest {
    @Test
    fun `미확정 장소는 PARTIAL과 스냅샷으로 남기고 후보 표시명에 원문 장소를 노출하지 않는다`() {
        val roomRepository = mock(MeetingRoomRepository::class.java)
        val runRepository = FakeCoordinationRunRepository()
        val submissionRepository = mock(SubmissionRepository::class.java)
        val structuredRepository = mock(StructuredSubmissionRepository::class.java)
        val normalizedRepository = CapturingNormalizedPlaceRepository()
        val room = room()
        val submissions = listOf(submission(1), submission(2))
        val batch =
            SubmissionBatch(
                SubmissionBatchId(uuid(20)),
                room.id,
                submissions.map { it.latest.id },
                NOW,
            )
        val run = CoordinationRun.queued(CoordinationRunId(uuid(21)), batch).startMatching()
        runRepository.current = run
        `when`(roomRepository.findById(room.id)).thenReturn(room)
        `when`(submissionRepository.findLatestByRoom(room.id)).thenReturn(submissions)
        `when`(structuredRepository.findByBatch(batch.id)).thenReturn(
            listOf(
                StructuredSubmissionResult(
                    submissions[0].latest.id,
                    listOf(StructuredCondition.UnresolvedPlace("중앙역"), timeWindow()),
                    null,
                ),
                StructuredSubmissionResult(submissions[1].latest.id, listOf(timeWindow()), null),
            ),
        )
        val persistence = MatchingProcessingPersistenceService(roomRepository, runRepository, normalizedRepository)
        val processor =
            MatchingProcessor(
                runRepository,
                submissionRepository,
                structuredRepository,
                normalizedRepository,
                object : PlaceSearchPort {
                    override fun normalize(query: String): PlaceNormalizationResult = PlaceNormalizationResult.NoExactMatch
                },
                IdGenerator { UUID.randomUUID() },
                persistence,
            )

        processor.process(batch.id)

        val completed = requireNotNull(runRepository.current)
        assertEquals(CoordinationStatus.COMPLETED, completed.status)
        assertEquals(CandidateQuality.PARTIAL, completed.quality)
        assertEquals(1, completed.candidates.size)
        assertEquals(null, completed.candidates.single().place)
        assertTrue(normalizedRepository.places.any { it.status == NormalizedPlaceStatus.NO_EXACT_MATCH })
    }

    private fun room(): MeetingRoom =
        MeetingRoom.create(
            MeetingRoomId(uuid(1)),
            InviteCode.of("abcdefghijklmnopqrstuv"),
            "테스트",
            MeetingDuration.ofMinutes(60),
            MeetingMode.REMOTE,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 23)),
            ClosurePolicy.of(expectedParticipants = 2),
            NOW,
        )

    private fun submission(index: Long): Submission =
        Submission.start(
            SubmissionId(uuid(100 + index)),
            MeetingRoomId(uuid(1)),
            ParticipantId(uuid(200 + index)),
            SubmissionVersionId(uuid(300 + index)),
            "중앙역에서 가능",
            emptyList(),
            Locale.forLanguageTag("ko-KR"),
            NOW,
        )

    private fun timeWindow() =
        StructuredCondition.TimeWindow(
            TimePolarity.AVAILABLE,
            LocalDate.of(2026, 9, 21),
            null,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
        )

    private class CapturingNormalizedPlaceRepository : NormalizedPlaceRepository {
        var places: List<NormalizedPlace> = emptyList()

        override fun replaceForBatch(
            batchId: SubmissionBatchId,
            places: List<NormalizedPlace>,
        ) {
            this.places = places
        }

        override fun findByBatch(batchId: SubmissionBatchId): List<NormalizedPlace> = places
    }

    private class FakeCoordinationRunRepository : CoordinationRunRepository {
        var current: CoordinationRun? = null

        override fun insert(run: CoordinationRun) {
            current = run
        }

        override fun findById(id: CoordinationRunId): CoordinationRun? = current?.takeIf { it.id == id }

        override fun update(run: CoordinationRun) {
            current = run
        }

        override fun findLatestByRoom(roomId: MeetingRoomId): CoordinationRun? = current?.takeIf { it.roomId == roomId }

        override fun findLatestByRoomForUpdate(roomId: MeetingRoomId): CoordinationRun? = findLatestByRoom(roomId)

        override fun findByBatchId(batchId: SubmissionBatchId): CoordinationRun? = current?.takeIf { it.batch.id == batchId }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-19T00:00:00Z")

        private fun uuid(value: Long): UUID = UUID(0, value)
    }
}
