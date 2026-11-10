package com.meetme.server.meetingroom.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ManualAvailability
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Issue218 default selection at the shared application boundary; PG atomicity is tested separately. */
class DefaultAnalysisClosureTest {
    @BeforeEach fun activeTransaction() = TransactionSynchronizationManager.setActualTransactionActive(true)

    @AfterEach fun clearTransaction() = TransactionSynchronizationManager.clear()

    @Test
    fun `DCL01 the unconfigured constructor retains historical Legacy behavior`() {
        val f = DefaultAnalysisClosureFixture()
        val service = CollectionClosureService(f.rooms, f.submissions, f.runs, f.outbox, f.ids, f.selection)
        service.close(f.room, ClosureReason.MANUAL, 2, f.now)
        assertTrue(f.frozen.isEmpty())
        assertEquals(
            CoordinationStatus.QUEUED,
            f.recordedRuns.values
                .single()
                .status,
        )
        assertEquals(CollectionClosureService.STRUCTURING_REQUESTED, f.events.single().eventType)
    }

    @ParameterizedTest
    @EnumSource(ClosureReason::class)
    fun `DCL02 every first closure reason selects weekly V5 for the entire natural language cohort`(reason: ClosureReason) {
        val f = DefaultAnalysisClosureFixture(reason)
        val closed = f.closure().close(f.room, reason, 2, f.now)
        assertEquals(CollectionStatus.CLOSED, closed.collectionStatus)
        val run = f.recordedRuns.values.single()
        assertEquals(run.id.value, closed.activeRunId)
        val route = f.frozen.values.single()
        val choice = assertNotNull(route.choice as? FinalAnalysisRouteChoice.DslStructuralV5)
        assertEquals("schedule-dsl-evidence-response-5", choice.identity.responseContractVersion)
        assertEquals("schedule-structural-union-1", choice.policy.version)
        assertEquals(f.current.map { it.latest.id }.toSet(), run.batch.submissionVersionIds.toSet())
        assertEquals(run.id.value, choice.context.runId)
        assertEquals(run.batch.id.value, choice.context.batchId)
        assertEquals(f.room.id.value, choice.context.roomId)
        assertTrue(f.current.all { it.latest.createdAt <= run.batch.fixedAt })
        assertEquals(CollectionClosureService.STRUCTURING_REQUESTED, f.events.single().eventType)
    }

    @Test
    fun `DCL03 explicit Legacy overrides the configured V5 default`() {
        val f = DefaultAnalysisClosureFixture()
        f.closure().close(f.room, ClosureReason.MANUAL, 2, f.now, FinalAnalysisContractSelection.LEGACY)
        assertTrue(f.frozen.isEmpty())
        assertEquals(CollectionClosureService.STRUCTURING_REQUESTED, f.events.single().eventType)
    }

    @Test
    fun `DCL04 a historical fully structured cohort keeps matching without an AI contract`() {
        val f = DefaultAnalysisClosureFixture()
        f.current = listOf(f.submission(1, false), f.submission(2, false))
        f.closure().close(f.room, ClosureReason.MANUAL, 2, f.now)
        assertTrue(f.frozen.isEmpty())
        assertEquals(
            CoordinationStatus.MATCHING,
            f.recordedRuns.values
                .single()
                .status,
        )
        assertEquals(CollectionClosureService.MATCHING_REQUESTED, f.events.single().eventType)
    }

    @Test
    fun `DCL05 a mixed historical cohort stays Legacy without dropping the structured participant`() {
        val f = DefaultAnalysisClosureFixture()
        f.current = listOf(f.submission(1, true), f.submission(2, false))
        f.closure().close(f.room, ClosureReason.MANUAL, 2, f.now)
        assertTrue(f.frozen.isEmpty())
        assertEquals(
            f.current.map { it.latest.id }.toSet(),
            f.recordedRuns.values
                .single()
                .batch.submissionVersionIds
                .toSet(),
        )
        assertEquals(CollectionClosureService.STRUCTURING_REQUESTED, f.events.single().eventType)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `DCL06 insufficient submissions close without creating any analysis or outbox job`(count: Int) {
        val f = DefaultAnalysisClosureFixture()
        f.current = f.current.take(count)
        val result = f.closure().close(f.room, ClosureReason.MANUAL, count, f.now)
        assertEquals(CollectionStatus.CLOSED, result.collectionStatus)
        assertNull(result.activeRunId)
        assertTrue(f.recordedRuns.isEmpty() && f.frozen.isEmpty() && f.events.isEmpty())
    }

    @Test
    fun `DCL07 an already closed Legacy room cannot be upgraded by an omitted repeated close`() {
        val f = DefaultAnalysisClosureFixture()
        val service = f.closure()
        val closed = service.close(f.room, ClosureReason.MANUAL, 2, f.now, FinalAnalysisContractSelection.LEGACY)
        val runs = f.recordedRuns.toMap()
        val events = f.events.toList()
        val result = service.close(closed, ClosureReason.MANUAL, 2, f.now.plusSeconds(1))
        assertSame(closed, result)
        assertEquals(runs, f.recordedRuns)
        assertEquals(events, f.events)
        assertTrue(f.frozen.isEmpty())
    }

    @Test
    fun `DCL08 a repeated selected close preserves its original frozen descriptor and one outbox event`() {
        val f = DefaultAnalysisClosureFixture()
        val service = f.closure()
        val closed = service.close(f.room, ClosureReason.MANUAL, 2, f.now)
        val original = f.frozen.toMap()
        service.close(closed, ClosureReason.MANUAL, 2, f.now.plusSeconds(5))
        assertEquals(original, f.frozen)
        assertEquals(1, f.events.size)
        assertEquals(1, f.recordedRuns.size)
    }

    @Test
    fun `DCL09 the real deadline scheduler reaches the same configured default selection`() {
        val f = DefaultAnalysisClosureFixture(ClosureReason.DEADLINE)
        `when`(f.rooms.findDueForUpdate(f.now, 100)).thenReturn(listOf(f.room))
        DeadlineClosureScheduler(f.rooms, f.submissions, f.closure(), Clock.fixed(f.now, ZoneOffset.UTC)).closeDueRooms()
        val choice =
            f.frozen.values
                .single()
                .choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals("schedule-dsl-evidence-response-5", choice.identity.responseContractVersion)
    }
}

/** In-memory public repositories retain exact native values; no provider transport or production database is involved. */
internal class DefaultAnalysisClosureFixture(
    reason: ClosureReason = ClosureReason.MANUAL,
) {
    val now: Instant = Instant.parse("2027-01-01T00:00:00.123456789Z")
    val room =
        MeetingRoom.create(
            MeetingRoomId(UUID(0, 101)),
            InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
            "synthetic default selection",
            MeetingMode.REMOTE,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 12)),
            when (reason) {
                ClosureReason.MANUAL -> ClosurePolicy.of(manualOnly = true)
                ClosureReason.EXPECTED_PARTICIPANTS -> ClosurePolicy.of(expectedParticipants = 2)
                ClosureReason.DEADLINE -> ClosurePolicy.of(deadline = now.minusSeconds(1))
            },
            now.minusSeconds(3600),
        )
    val rooms = mock(MeetingRoomRepository::class.java)
    val submissions = mock(SubmissionRepository::class.java)
    val runs = mock(CoordinationRunRepository::class.java)
    val routes = mock(FinalAnalysisRouteRegistry::class.java)
    val outbox = mock(OutboxRepository::class.java)
    val recordedRuns = linkedMapOf<com.meetme.server.shared.domain.CoordinationRunId, CoordinationRun>()
    val frozen = linkedMapOf<UUID, FrozenFinalAnalysisRoute>()
    val events = mutableListOf<OutboxEvent>()
    var current: List<Submission> = listOf(submission(1), submission(2))
    var historical: List<Submission> = current
    var currentRoom = room
    private var next = 2000L
    val ids = IdGenerator { UUID(0, next++) }
    val selection = FinalAnalysisRouteSelectionService(runs, routes, true, true, true, true, true, true)

    init {
        `when`(rooms.findById(room.id)).thenAnswer { currentRoom }
        `when`(rooms.findByInviteCodeForUpdate(room.inviteCode)).thenAnswer { currentRoom }
        Mockito
            .doAnswer {
                currentRoom = it.getArgument(0)
                null
            }.`when`(rooms)
            .update(anyValue())
        `when`(submissions.findLatestByRoom(room.id)).thenAnswer { current }
        `when`(submissions.countSubmittedParticipants(room.id)).thenAnswer { current.size }
        for (requested in listOf(current.map { it.latest.id }, listOf(current[0].latest.id), listOf(current[1].latest.id))) {
            `when`(submissions.findFrozenByVersionIds(room.id, requested)).thenAnswer { historical.filter { it.latest.id in requested } }
        }
        Mockito
            .doAnswer {
                record(it.getArgument(0))
                null
            }.`when`(runs)
            .insert(anyValue())
        Mockito
            .doAnswer {
                record(it.getArgument(0))
                null
            }.`when`(runs)
            .update(anyValue())
        `when`(runs.findLatestByRoomForUpdate(room.id)).thenAnswer { recordedRuns.values.lastOrNull() }
        `when`(routes.find(anyValue())).thenAnswer { frozen[it.getArgument(0)] }
        `when`(routes.freeze(anyValue(), Mockito.anyLong(), anyValue())).thenAnswer { call ->
            val run = recordedRuns.values.single { it.id.value == call.getArgument<UUID>(0) }
            val route = FrozenFinalAnalysisRoute(run.id.value, run.batch.id.value, room.id.value, call.getArgument(2), now)
            check(frozen.putIfAbsent(run.id.value, route) == null)
            route
        }
        Mockito
            .doAnswer {
                events += it.getArgument<OutboxEvent>(0)
                null
            }.`when`(outbox)
            .insert(anyValue())
    }

    fun record(run: CoordinationRun) {
        recordedRuns[run.id] = run
        `when`(runs.findById(run.id)).thenAnswer { recordedRuns[run.id] }
        `when`(runs.findByIdForUpdate(run.id)).thenAnswer { recordedRuns[run.id] }
    }

    fun closure(default: FinalAnalysisContractSelection = FinalAnalysisContractSelection.DSL_STRUCTURAL_V5) =
        CollectionClosureService(rooms, submissions, runs, outbox, ids, selection, defaultAnalysisContract = default)

    fun submission(
        index: Long,
        natural: Boolean = true,
    ): Submission =
        Submission.start(
            SubmissionId(UUID(0, 300 + index)),
            room.id,
            ParticipantId(UUID(0, 400 + index)),
            SubmissionVersionId(UUID(0, 500 + index)),
            if (natural) "2027-01-08 19:00~21:00 available synthetic participant $index" else null,
            if (natural) {
                emptyList()
            } else {
                listOf(
                    ManualAvailability.Dated(
                        DatedTimeRange(LocalDate.of(2027, 1, 8), LocalTimeRange.of(LocalTime.of(19, 0), LocalTime.of(21, 0))),
                    ),
                )
            },
            Locale.KOREAN,
            now.minusSeconds(60),
        )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
