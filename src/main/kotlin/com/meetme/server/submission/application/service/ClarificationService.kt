package com.meetme.server.submission.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.InputRevisionRoundRepository
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.RevisionRoundStatus
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.submission.application.port.input.AnswerClarificationCommand
import com.meetme.server.submission.application.port.input.ClarificationAnswerView
import com.meetme.server.submission.application.port.input.ClarificationChoice
import com.meetme.server.submission.application.port.input.ClarificationListView
import com.meetme.server.submission.application.port.input.ClarificationQuestionView
import com.meetme.server.submission.application.port.input.ClarificationUseCase
import com.meetme.server.submission.application.port.input.GetOwnSubmissionUseCase
import com.meetme.server.submission.application.port.input.SaveSubmissionCommand
import com.meetme.server.submission.application.port.input.SaveSubmissionUseCase
import com.meetme.server.submission.application.port.output.ClarificationOwnerPort
import com.meetme.server.submission.application.port.output.ClarificationRepository
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.SubmissionRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ClarificationService(
    private val rooms: MeetingRoomRepository,
    private val ownership: ClarificationOwnerPort,
    private val submissions: SubmissionRepository,
    private val structured: StructuredSubmissionRepository,
    private val runs: CoordinationRunRepository,
    private val rounds: InputRevisionRoundRepository,
    private val clarifications: ClarificationRepository,
    private val saveSubmission: SaveSubmissionUseCase,
    private val getSubmission: GetOwnSubmissionUseCase,
    private val clock: Clock,
) : ClarificationUseCase {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun get(
        inviteCode: String,
        rawCredential: String?,
    ): ClarificationListView {
        val room = room(inviteCode, false)
        val owner = ownership.requireOwner(room.id, rawCredential)
        val empty = ClarificationListView(emptyList(), room.version)
        val submission = submissions.findByParticipant(owner.id) ?: return empty
        val run = runs.findLatestByRoom(room.id) ?: return empty
        if (run.status != CoordinationStatus.COMPLETED ||
            run.isConfirmed ||
            submission.latest.id !in run.batch.submissionVersionIds
        ) {
            return empty
        }
        val result = structured.findByBatch(run.batch.id).find { it.submissionVersionId == submission.latest.id } ?: return empty
        if (result.rejectionCode !in AMBIGUITY_REASONS) return empty
        val record = clarifications.findBySource(submission.latest.id)?.takeIf { it.requestId == null } ?: return empty
        check(record.participantId == owner.id)
        val round = room.activeRevisionRoundId?.let { rounds.findById(room.id, it) }
        val editable = round?.status == RevisionRoundStatus.OPEN && round.sourceRunId == run.id.value
        val p = record.proposal
        return ClarificationListView(
            listOf(
                ClarificationQuestionView(record.questionId, record.sourceRevision, p.excerpt, p.question, p.optionA, p.optionB, editable),
            ),
            room.version,
        )
    }

    @Transactional
    override fun answer(command: AnswerClarificationCommand): ClarificationAnswerView {
        val room = room(command.inviteCode, true)
        val owner = ownership.requireOwner(room.id, command.rawCredential)
        val record = clarifications.findById(command.questionId)?.takeIf { it.participantId == owner.id } ?: conflict()
        if (record.requestId != null) {
            if (record.requestId != command.requestId ||
                record.revisionRoundId != command.revisionRoundId ||
                record.expectedRevision != command.expectedRevision ||
                record.choice != command.choice.name ||
                record.directText != command.directText
            ) {
                conflict()
            }
            return requireNotNull(record.response)
        }
        val current = submissions.findByParticipant(owner.id) ?: conflict()
        val run = runs.findLatestByRoom(room.id) ?: conflict()
        val round = rounds.findById(room.id, command.revisionRoundId) ?: conflict()
        if (room.activeRevisionRoundId != round.id ||
            round.status != RevisionRoundStatus.OPEN ||
            round.sourceRunId != run.id.value ||
            run.status != CoordinationStatus.COMPLETED ||
            run.isConfirmed ||
            record.sourceVersionId !in run.batch.submissionVersionIds ||
            current.latest.id != record.sourceVersionId ||
            current.latest.revision != command.expectedRevision
        ) {
            conflict()
        }
        if (structured.findByBatch(run.batch.id).none {
                it.submissionVersionId == record.sourceVersionId &&
                    it.rejectionCode in AMBIGUITY_REASONS
            }
        ) {
            conflict()
        }
        require((command.choice == ClarificationChoice.DIRECT) == (command.directText != null))
        val text =
            when (command.choice) {
                ClarificationChoice.A -> record.proposal.optionA
                ClarificationChoice.B -> record.proposal.optionB
                ClarificationChoice.DIRECT -> requireNotNull(command.directText)
                ClarificationChoice.CANCEL -> null
            }
        if (text != null) {
            require(SubmissionRules.trimRawText(text) != current.latest.rawText) { "A clarification must create a new input revision" }
            saveSubmission.save(
                SaveSubmissionCommand(command.inviteCode, command.rawCredential, text, command.locale, round.id, command.expectedRevision),
            )
        }
        rooms.update(room.transition())
        val view = if (text == null) null else getSubmission.get(command.inviteCode, command.rawCredential)
        val saved = if (text == null) null else requireNotNull(submissions.findByParticipant(owner.id)).latest.id
        val response = ClarificationAnswerView(if (text == null) "CANCELLED" else "ANSWERED", view)
        clarifications.answer(record.questionId, command, saved, response, clock.instant())
        return response
    }

    private fun room(
        code: String,
        lock: Boolean,
    ): MeetingRoom {
        val invite = runCatching { InviteCode.of(code) }.getOrNull() ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        return (if (lock) rooms.findByInviteCodeForUpdate(invite) else rooms.findByInviteCode(invite))
            ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
    }

    private fun conflict(): Nothing = throw RoomLifecycleException(RoomLifecycleErrorCode.REVISION_CONFLICT)

    companion object {
        val AMBIGUITY_REASONS = setOf("AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT")
    }
}
