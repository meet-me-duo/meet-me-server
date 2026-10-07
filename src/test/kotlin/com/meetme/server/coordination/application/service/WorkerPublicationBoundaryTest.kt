package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// Issue #92 public worker persistence contract: obsolete publication cannot mutate the active generation.
class WorkerPublicationBoundaryTest {
    @Test
    fun `replaced active run prevents stale Gemini start complete delay and current check`() {
        val fixture = Fixture()
        val run = fixture.queued.startStructuring()
        fixture.lockedRoom(fixture.room.transition(activeRunId = UUID(0, 999)))
        `when`(fixture.runs.findByIdForUpdate(fixture.queued.id)).thenReturn(fixture.queued)
        val service = fixture.gemini()

        assertNull(service.start(fixture.queued))
        `when`(fixture.runs.findByIdForUpdate(run.id)).thenReturn(run)
        assertFalse(service.isCurrent(run))
        service.complete(run, emptyList(), attempt(run))
        service.delay(run)
        fixture.noWrites()
    }

    @Test
    fun `open correction round prevents old run publication even when active run id still matches`() {
        val fixture = Fixture()
        val run = fixture.queued.startStructuring()
        fixture.lockedRoom(fixture.room.transition(activeRevisionRoundId = UUID(0, 998)))
        `when`(fixture.runs.findByIdForUpdate(fixture.queued.id)).thenReturn(fixture.queued)
        val service = fixture.gemini()

        assertNull(service.start(fixture.queued))
        `when`(fixture.runs.findByIdForUpdate(run.id)).thenReturn(run)
        assertFalse(service.isCurrent(run))
        service.complete(run, emptyList(), attempt(run))
        service.delay(run)
        fixture.noWrites()
    }

    @Test
    fun `replaced active run prevents matching start completion and delay with no snapshot writes`() {
        val fixture = Fixture()
        val matching = fixture.queued.startMatching()
        fixture.lockedRoom(fixture.room.transition(activeRunId = UUID(0, 999)))
        `when`(fixture.runs.findByIdForUpdate(matching.id)).thenReturn(matching)
        val service = fixture.matching()

        assertNull(service.start(matching))
        service.complete(matching.complete(CandidateQuality.COMPLETE, emptyList()), emptyList())
        service.delay(matching)
        fixture.noWrites()
    }

    @Test
    fun `open correction round blocks old matching publication without rewriting place snapshots`() {
        val fixture = Fixture()
        val matching = fixture.queued.startMatching()
        fixture.lockedRoom(fixture.room.transition(activeRevisionRoundId = UUID(0, 998)))
        `when`(fixture.runs.findByIdForUpdate(matching.id)).thenReturn(matching)
        val service = fixture.matching()

        assertNull(service.start(matching))
        service.complete(matching.complete(CandidateQuality.PARTIAL, emptyList()), emptyList())
        service.delay(matching)
        fixture.noWrites()
    }

    @Test
    fun `same active id with stale run version prevents Gemini publication`() {
        val fixture = Fixture()
        val stale = fixture.queued.startStructuring()
        val current =
            CoordinationRun.restore(
                stale.id,
                stale.roomId,
                stale.batch,
                stale.status,
                stale.quality,
                stale.candidates,
                stale.confirmedCandidateId,
                stale.confirmedAt,
                stale.resumeStage,
                stale.version + 1,
            )
        fixture.lockedRoom(fixture.room)
        `when`(fixture.runs.findByIdForUpdate(stale.id)).thenReturn(current)
        val service = fixture.gemini()

        assertFalse(service.isCurrent(stale))
        service.complete(stale, emptyList(), attempt(stale))
        service.delay(stale)
        fixture.noWrites()
    }

    @Test
    fun `active worker locks room before exact run and never chooses latest by timestamp`() {
        val fixture = Fixture()
        fixture.lockedRoom(fixture.room)
        `when`(fixture.runs.findByIdForUpdate(fixture.queued.id)).thenReturn(fixture.queued)
        val service = fixture.gemini()

        assertEquals(fixture.queued.startStructuring(), service.start(fixture.queued))
        val order = inOrder(fixture.rooms, fixture.runs)
        order.verify(fixture.rooms).findByIdForUpdate(fixture.room.id)
        order.verify(fixture.runs).findByIdForUpdate(fixture.queued.id)
        verify(fixture.runs, never()).findLatestByRoomForUpdate(fixture.room.id)
        verify(fixture.runs).update(fixture.queued.startStructuring())
        verifyNoInteractions(fixture.structured, fixture.attempts, fixture.places)
    }

    @Test
    fun `same id and version cannot publish a run attached to a different batch`() {
        val fixture = Fixture()
        fixture.lockedRoom(fixture.room)
        val otherBatch = fixture.queued.batch.copy(id = SubmissionBatchId(UUID(0, 997)))
        val initial = fixture.queued
        val wrong =
            CoordinationRun.restore(
                initial.id,
                initial.roomId,
                otherBatch,
                initial.status,
                initial.quality,
                initial.candidates,
                initial.confirmedCandidateId,
                initial.confirmedAt,
                initial.resumeStage,
                initial.version,
            )
        `when`(fixture.runs.findByIdForUpdate(fixture.queued.id)).thenReturn(wrong)
        kotlin.test.assertFailsWith<IllegalStateException> { fixture.gemini().start(fixture.queued) }
        fixture.noWrites()
    }

    private class Fixture {
        val rooms = mock(MeetingRoomRepository::class.java)
        val runs = mock(CoordinationRunRepository::class.java)
        val structured = mock(StructuredSubmissionRepository::class.java)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val places = mock(NormalizedPlaceRepository::class.java)
        val room =
            MeetingRoom
                .create(
                    MeetingRoomId(UUID(0, 1)),
                    InviteCode.of("abcdefghijklmnopqrstuv"),
                    "synthetic worker",
                    MeetingMode.REMOTE,
                    MeetingTimeZone.of("Asia/Seoul"),
                    SearchDateRange.explicit(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12)),
                    ClosurePolicy.of(manualOnly = true),
                    NOW,
                ).close(ClosureReason.MANUAL, NOW, 2)
                .transition(activeRunId = UUID(0, 2))
        val queued =
            CoordinationRun.queued(
                CoordinationRunId(UUID(0, 2)),
                SubmissionBatch(
                    SubmissionBatchId(UUID(0, 3)),
                    room.id,
                    listOf(SubmissionVersionId(UUID(0, 4)), SubmissionVersionId(UUID(0, 5))),
                    NOW,
                ),
            )

        fun lockedRoom(value: MeetingRoom) {
            `when`(rooms.findByIdForUpdate(room.id)).thenReturn(value)
        }

        fun gemini() = GeminiProcessingPersistenceService(rooms, runs, structured, attempts, Clock.fixed(NOW, ZoneOffset.UTC))

        fun matching() = MatchingProcessingPersistenceService(rooms, runs, places)

        fun noWrites() {
            verify(rooms, never()).update(anyValue())
            verify(runs, never()).update(anyValue())
            verifyNoInteractions(structured, attempts, places)
        }
    }

    private fun attempt(run: CoordinationRun) = CoordinationAttempt(UUID(0, 6), run.id, 1, NOW, NOW, null, null, null, null, null)

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
