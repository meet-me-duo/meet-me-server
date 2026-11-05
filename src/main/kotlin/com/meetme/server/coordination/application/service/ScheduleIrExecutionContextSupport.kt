package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.time.Instant

/** Shared frozen execution fences; contains no version-specific parsing or matching policy. */
internal class ScheduleIrExecutionContextSupport(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val registry: ScheduleIrExecutionRegistry,
    private val supportedResponseContractVersion: String,
    private val supportedSemanticVersion: String,
) {
    fun prepare(
        runId: CoordinationRunId,
        expectedRunVersion: Long,
        identity: IrExecutionIdentity,
        policy: IrMatchingPolicy,
        preparedAt: Instant,
    ): IrExecutionTicket {
        validateIdentity(identity, policy)
        val frozen = loadFrozen(runId, expectedRunVersion, policy.version)
        require(preparedAt >= frozen.run.batch.fixedAt)
        val selection =
            policy.copy(
                eligibleInterpretations = policy.eligibleInterpretations.sortedBy { it.name }.toSet(),
                alternativeChoices =
                    policy.alternativeChoices.sortedWith(
                        compareBy({
                            it.submissionVersionId.toString()
                        }, { it.group }, { it.alternative }),
                    ),
            )
        val ticket = IrExecutionTicket(expectedRunVersion, identity, selection, frozen.snapshot.context, frozen.room.mode, preparedAt)
        registry.bind(ticket)
        return checkNotNull(registry.find(runId.value)) { "Missing prepared IR selection" }
    }

    fun requireCurrent(ticket: IrExecutionTicket): ScheduleEvidenceSnapshot {
        validateIdentity(ticket.identity, ticket.policy)
        val frozen = loadFrozen(CoordinationRunId(ticket.context.runId), ticket.expectedRunVersion, ticket.policy.version)
        check(frozen.snapshot.context == ticket.context && frozen.room.mode == ticket.meetingMode) { "Stale IR source or room context" }
        registry.requireCurrent(ticket)
        return frozen.snapshot
    }

    private fun loadFrozen(
        runId: CoordinationRunId,
        expectedRunVersion: Long,
        policyVersion: String,
    ): FrozenExecution {
        require(expectedRunVersion >= 0)
        val initial = checkNotNull(runs.findById(runId)) { "Missing IR execution" }
        // A non-locking identity read precedes room -> run locks; it never reverses the existing lock order.
        val room = checkNotNull(rooms.findByIdForUpdate(initial.roomId)) { "Missing IR room" }
        val run = checkNotNull(runs.findByIdForUpdate(runId)) { "Missing IR execution" }
        check(run.roomId == room.id && run.version == expectedRunVersion && run.status == CoordinationStatus.STRUCTURING)
        check(room.activeRunId == run.id.value && room.activeRevisionRoundId == null && !run.isConfirmed) { "Protected IR execution" }
        check(room.collectionStatus == CollectionStatus.CLOSED && room.closedAt != null) { "Unfrozen IR execution" }
        val frozen = FrozenSubmissionReader.read(submissions, run.batch)
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                frozen,
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policyVersion,
            )
        return FrozenExecution(room, run, snapshot)
    }

    private fun validateIdentity(
        identity: IrExecutionIdentity,
        policy: IrMatchingPolicy,
    ) {
        require(identity.responseContractVersion == supportedResponseContractVersion) { "Unsupported IR response contract" }
        require(identity.irSemanticVersion == supportedSemanticVersion) { "Unsupported IR semantic version" }
        require(identity.policyVersion == policy.version && policy.version.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
    }

    private data class FrozenExecution(
        val room: MeetingRoom,
        val run: CoordinationRun,
        val snapshot: ScheduleEvidenceSnapshot,
    )
}
