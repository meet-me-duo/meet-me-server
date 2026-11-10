package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Registry authority comes from exact frozen submissions; model labels and mutable heads are not authority. */
class ScheduleEvidenceSnapshotFactoryTest {
    private val factory = ScheduleEvidenceSnapshotFactory()
    private val fixture = ScheduleIrTestFixture
    private val room = MeetingRoomId(UUID(0, 203))
    private val question = UUID(0, 601)
    private val reference = fixture.reference
    private val answeredAt = reference.plusSeconds(7200)
    private val target = "금요일 7시는 미정"
    private val targetStart = ScheduleIrTestFixture.FIRST_TEXT.codePointCount(0, ScheduleIrTestFixture.FIRST_TEXT.indexOf(target))

    @Test
    fun `frozen original and scoped answer retain distinct source identity revision and reference clocks`() {
        val submissions = submissions()
        val run = run(submissions)
        val actual = create(run, submissions)
        val input = actual.context.inputs.single { it.submissionVersionId == fixture.firstId }
        val original = input.sources.single { it.kind == IrSourceKind.ORIGINAL }
        val supplement = input.sources.single { it.kind == IrSourceKind.SUPPLEMENT }

        assertEquals(run.id.value, actual.context.runId)
        assertEquals(run.batch.id.value, actual.context.batchId)
        assertEquals(room.value, actual.context.roomId)
        assertEquals("Asia/Seoul", actual.context.timeZone)
        assertEquals("synthetic-explicit-policy-1", actual.context.policyVersion)
        assertEquals(7, input.submissionRevision)
        assertEquals(3, input.originalRevision)
        assertEquals("original", original.sourceId)
        assertEquals(3, original.revision)
        assertEquals(reference, original.referenceAt)
        assertEquals(fixture.sha(ScheduleIrTestFixture.FIRST_TEXT), original.contentSha256)
        assertEquals(ScheduleIrTestFixture.FIRST_TEXT.codePointCount(0, ScheduleIrTestFixture.FIRST_TEXT.length), original.codePointLength)
        assertEquals("supplement:$question", supplement.sourceId)
        assertEquals(3, supplement.revision)
        assertEquals(question, supplement.questionId)
        assertEquals(targetStart, supplement.originalStartCodePoint)
        assertEquals(targetStart + target.codePointCount(0, target.length), supplement.originalEndCodePoint)
        assertEquals(reference, supplement.referenceAt)
        assertEquals(fixture.sha("오후"), supplement.contentSha256)
        assertEquals(2, supplement.codePointLength)
        assertEquals("오후", actual.sourceText(fixture.firstId, supplement.sourceId))
        assertEquals(ScheduleIrTestFixture.FIRST_TEXT, actual.sourceText(fixture.firstId, "original"))
        assertNull(actual.sourceText(fixture.secondId, supplement.sourceId))
    }

    @Test
    fun `legacy original source uses submission revision and creation clock without fabricating evidence`() {
        val submissions = submissions()
        val snapshot = create(run(submissions), submissions)
        val input = snapshot.context.inputs.single { it.submissionVersionId == fixture.secondId }
        val source = input.sources.single()

        assertEquals(4, input.submissionRevision)
        assertNull(input.originalRevision)
        assertEquals(4, source.revision)
        assertEquals(reference.plusSeconds(300), source.referenceAt)
        assertEquals(IrSourceKind.ORIGINAL, source.kind)
        assertNull(source.questionId)
        assertNull(source.originalStartCodePoint)
        assertNull(source.originalEndCodePoint)
        assertNull(snapshot.sourceText(fixture.secondId, "supplement:$question"))
    }

    @Test
    fun `direct answer has its own clock while original date reference remains fixed`() {
        val original = evidence()
        val direct = original.copy(supplement = original.supplement!!.copy(choiceId = null, referenceAt = answeredAt))
        val submissions = submissions(direct)
        val input = create(run(submissions), submissions).context.inputs.single { it.submissionVersionId == fixture.firstId }

        assertEquals(reference, input.sources.single { it.kind == IrSourceKind.ORIGINAL }.referenceAt)
        assertEquals(answeredAt, input.sources.single { it.kind == IrSourceKind.SUPPLEMENT }.referenceAt)
        assertEquals(3, input.sources.single { it.kind == IrSourceKind.SUPPLEMENT }.revision)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["raw", "locale", "submission_revision", "answer", "question", "answer_time", "interpretation", "question_scope"],
    )
    fun `immutable input content digest changes for each relevant source component`(field: String) {
        val original = submissions()
        val first = original.first()
        val current = first.latest
        val ev = current.evidence!!
        val answer = ev.supplement!!
        val changedVersion =
            when (field) {
                "raw" -> current.copy(rawText = current.rawText + " 추가 문장.")
                "locale" -> current.copy(locale = Locale.ENGLISH)
                "submission_revision" -> current.copy(revision = current.revision + 1)
                "answer" -> current.copy(evidence = ev.copy(supplement = answer.copy(answerText = "오전")))
                "question" -> current.copy(evidence = ev.copy(supplement = answer.copy(questionId = UUID(0, 602))))
                "answer_time" -> current.copy(evidence = ev.copy(supplement = answer.copy(answeredAt = answeredAt.plusSeconds(1))))
                "interpretation" ->
                    current.copy(
                        evidence = ev.copy(interpretation = InputInterpretation.UNRESOLVED, unresolvedReason = "SYNTHETIC_UNRESOLVED"),
                    )
                else ->
                    current.copy(
                        evidence =
                            ev.copy(
                                supplement =
                                    answer.copy(
                                        sourceExcerpt = "장소는 역 근처",
                                        sourceStartCodePoint =
                                            ScheduleIrTestFixture.FIRST_TEXT.codePointCount(
                                                0,
                                                ScheduleIrTestFixture.FIRST_TEXT.indexOf("장소는 역 근처"),
                                            ),
                                    ),
                            ),
                    )
            }
        val changed = listOf(restore(first, changedVersion), original.last())
        val fixedRun = run(original)
        val oldBinding = create(fixedRun, original).context.inputs.single { it.submissionVersionId == fixture.firstId }
        val newBinding = create(fixedRun, changed).context.inputs.single { it.submissionVersionId == fixture.firstId }

        assertNotEquals(oldBinding.contentSha256, newBinding.contentSha256)
        assertEquals(fixture.firstId, newBinding.submissionVersionId)
    }

    @Test
    fun `reordering frozen inputs or copying them does not alter per input evidence identity`() {
        val submissions = submissions()
        val fixedRun = run(submissions)
        val first = create(fixedRun, submissions)
        val again = create(fixedRun, submissions.reversed().map { restore(it, it.latest.copy()) })

        assertEquals(first.context.inputs.toSet(), again.context.inputs.toSet())
        assertEquals(first.context.copy(inputs = emptyList()), again.context.copy(inputs = emptyList()))
        assertEquals(first.sourceText(fixture.firstId, "original"), again.sourceText(fixture.firstId, "original"))
    }

    @Test
    fun `force reparse execution and policy identity change without rewriting immutable sources`() {
        val submissions = submissions()
        val firstRun = run(submissions)
        val nextRun =
            CoordinationRun
                .queued(
                    CoordinationRunId(UUID(0, 901)),
                    firstRun.batch.copy(id = SubmissionBatchId(UUID(0, 902))),
                ).startStructuring()
        val baseline = create(firstRun, submissions)
        val next = create(nextRun, submissions)
        val changedPolicy = create(firstRun, submissions, "different-reviewed-policy")

        assertNotEquals(baseline.context.runId, next.context.runId)
        assertNotEquals(baseline.context.batchId, next.context.batchId)
        assertEquals(baseline.context.inputs, next.context.inputs)
        assertNotEquals(baseline.context.policyVersion, changedPolicy.context.policyVersion)
        assertEquals(baseline.context.inputs, changedPolicy.context.inputs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "latest_head", "other_room", "same_participant"])
    fun `factory rejects inputs that are not the exact frozen cohort`(defect: String) {
        val originals = submissions()
        val first = originals.first()
        val changed =
            when (defect) {
                "missing" -> originals.take(1)
                "duplicate" -> originals + first
                "latest_head" ->
                    listOf(
                        restore(first, first.latest.copy(id = SubmissionVersionId(UUID(0, 999)), revision = 8)),
                        originals.last(),
                    )
                "other_room" ->
                    listOf(
                        Submission.restore(first.id, MeetingRoomId(UUID(0, 999)), first.participantId, first.latest),
                        originals.last(),
                    )
                else -> listOf(first, Submission.restore(originals.last().id, room, first.participantId, originals.last().latest))
            }

        assertFailsWith<IllegalArgumentException> { create(run(originals), changed) }
    }

    @Test
    fun `invalid saved answer cannot become an authoritative source registry`() {
        val invalid = evidence().copy(supplement = evidence().supplement!!.copy(sourceRevision = 4))
        val submissions = submissions(invalid)

        assertFailsWith<IllegalArgumentException> { create(run(submissions), submissions) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["original_after_acceptance", "answer_after_acceptance", "acceptance_after_freeze"])
    fun `source chronology cannot extend beyond acceptance or frozen batch time`(defect: String) {
        val originals = submissions()
        val fixedRun = run(originals)
        val first = originals.first()
        val current = first.latest
        val originalEvidence = current.evidence!!
        val changedVersion =
            when (defect) {
                "original_after_acceptance" ->
                    current.copy(
                        evidence =
                            originalEvidence.copy(
                                referenceAt = current.createdAt.plusSeconds(1),
                                supplement = null,
                                interpretation = InputInterpretation.PENDING,
                            ),
                    )
                "answer_after_acceptance" ->
                    current.copy(
                        evidence =
                            originalEvidence.copy(
                                supplement = originalEvidence.supplement!!.copy(answeredAt = current.createdAt.plusSeconds(1)),
                            ),
                    )
                else -> current.copy(createdAt = fixedRun.batch.fixedAt.plusSeconds(1))
            }
        val changed = listOf(restore(first, changedVersion), originals.last())

        assertFailsWith<IllegalArgumentException> { create(fixedRun, changed) }
    }

    @Test
    fun `source snapshot owns its input collection and redacts raw original and answer in diagnostics`() {
        val supplied = submissions().toMutableList()
        val snapshot: ScheduleEvidenceSnapshot = create(run(supplied), supplied)
        supplied.clear()

        assertEquals(2, snapshot.context.inputs.size)
        assertEquals(ScheduleIrTestFixture.FIRST_TEXT, snapshot.sourceText(fixture.firstId, "original"))
        assertFalse(snapshot.toString().contains(ScheduleIrTestFixture.FIRST_TEXT))
        assertFalse(snapshot.toString().contains("오후"))
    }

    private fun evidence(): InputEvidence =
        InputEvidence(
            3,
            reference,
            InputSupplement(question, 3, target, targetStart, "오전과 오후 중 언제인가요?", "오후", "PM", answeredAt, reference),
            InputInterpretation.CONFIRMED,
        )

    private fun submissions(evidence: InputEvidence = evidence()): List<Submission> =
        listOf(
            Submission.restore(
                SubmissionId(UUID(0, 401)),
                room,
                ParticipantId(UUID(0, 301)),
                SubmissionVersion(
                    SubmissionVersionId(fixture.firstId),
                    7,
                    ScheduleIrTestFixture.FIRST_TEXT,
                    emptyList(),
                    Locale.KOREAN,
                    answeredAt.plusSeconds(1),
                    evidence,
                ),
            ),
            Submission.restore(
                SubmissionId(UUID(0, 402)),
                room,
                ParticipantId(UUID(0, 302)),
                SubmissionVersion(
                    SubmissionVersionId(fixture.secondId),
                    4,
                    ScheduleIrTestFixture.SECOND_TEXT,
                    emptyList(),
                    Locale.KOREAN,
                    reference.plusSeconds(300),
                ),
            ),
        )

    private fun run(submissions: List<Submission>): CoordinationRun =
        CoordinationRun
            .queued(
                CoordinationRunId(UUID(0, 201)),
                SubmissionBatch(SubmissionBatchId(UUID(0, 202)), room, submissions.map { it.latest.id }, reference.plusSeconds(10800)),
            ).startStructuring()

    private fun create(
        run: CoordinationRun,
        submissions: List<Submission>,
        policy: String = "synthetic-explicit-policy-1",
    ): ScheduleEvidenceSnapshot =
        factory.create(run, submissions, ZoneId.of("Asia/Seoul"), fixture.context().searchStart, fixture.context().searchEnd, policy)

    private fun restore(
        submission: Submission,
        version: SubmissionVersion,
    ): Submission = Submission.restore(submission.id, submission.roomId, submission.participantId, version)
}
