package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ScheduleEvidenceSnapshotFactory {
    fun create(
        run: CoordinationRun,
        submissions: List<Submission>,
        timeZone: ZoneId,
        searchStart: LocalDate,
        searchEnd: LocalDate,
        policyVersion: String,
    ): ScheduleEvidenceSnapshot {
        val ids = run.batch.submissionVersionIds
        require(ids.size in 2..50 && ids.distinct().size == ids.size)
        require(run.batch.roomId == run.roomId)
        require(submissions.size == ids.size && submissions.map { it.latest.id }.toSet() == ids.toSet())
        require(submissions.map { it.participantId }.distinct().size == submissions.size)
        require(submissions.all { it.roomId == run.roomId && it.latest.createdAt <= run.batch.fixedAt })
        require(ChronoUnit.DAYS.between(searchStart, searchEnd) in 1..31)
        require(policyVersion.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
        val bound = submissions.sortedBy { it.latest.id.value }.map(IrEvidenceBindings::create)
        return ScheduleEvidenceSnapshot(
            ScheduleIrContext(
                run.id.value,
                run.batch.id.value,
                run.roomId.value,
                timeZone.id,
                searchStart,
                searchEnd,
                policyVersion,
                bound.map { it.first },
            ),
            bound.associate { it.first.submissionVersionId to it.second },
        )
    }
}
