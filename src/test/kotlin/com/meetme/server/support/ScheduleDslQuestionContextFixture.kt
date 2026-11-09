package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineSubmission
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** Native, synthetic sources only. Never calls the new capsule factory while preparing a fixture. */
object ScheduleDslQuestionContextFixture {
    val ir = ScheduleIrV2SyntheticFixture
    val wire = ScheduleDslWireFixture
    val questionId = UUID(0, 1101)
    const val PROMPT = "Synthetic question: which of the displayed alternatives did you select?"
    const val CHOICE = "synthetic-choice-2"
    val answeredAt = ir.reference.plusSeconds(60)
    val answer = InputSupplement(questionId, 3, "🙂", 0, PROMPT, "Synthetic selected reply.", CHOICE, answeredAt, ir.reference)

    fun input1() =
        ScheduleDslOfflineInput(
            "schedule-dsl-pilot-input-1",
            SCHEDULE_DSL_RESPONSE_CONTRACT,
            SCHEDULE_DSL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            ir.run,
            ir.batch,
            ir.room,
            Instant.parse("2027-01-04T01:00:00Z"),
            "Asia/Seoul",
            LocalDate.parse("2027-01-04"),
            LocalDate.parse("2027-01-11"),
            MeetingMode.REMOTE,
            wire.f.policy,
            listOf(
                ScheduleDslOfflineSubmission(
                    UUID(0, 401),
                    ir.first,
                    ir.firstParticipant,
                    7,
                    ir.FIRST_TEXT,
                    "ko-KR",
                    ir.reference.plusSeconds(300),
                    InputEvidence(3, ir.reference, answer),
                ),
                ScheduleDslOfflineSubmission(
                    UUID(0, 402),
                    ir.second,
                    ir.secondParticipant,
                    7,
                    ir.SECOND_TEXT,
                    "ko-KR",
                    ir.reference.plusSeconds(300),
                    InputEvidence(3, ir.reference),
                ),
            ),
        )

    fun input2() = input1().copy(responseContractVersion = SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT)

    fun snapshot() = ScheduleDslOfflinePipeline.snapshot(input1())

    fun native(input: ScheduleDslOfflineInput = input1()): List<Submission> =
        input.inputs.map { item ->
            Submission.restore(
                SubmissionId(item.submissionId),
                MeetingRoomId(input.roomId),
                ParticipantId(item.participantId),
                SubmissionVersion(
                    SubmissionVersionId(item.submissionVersionId),
                    item.submissionRevision,
                    item.rawText,
                    emptyList(),
                    Locale.forLanguageTag(item.locale),
                    item.acceptedAt,
                    item.evidence,
                ),
            )
        }
}
