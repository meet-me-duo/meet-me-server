package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission

/** Reads the exact immutable versions, including versions no longer selected by a head. */
object FrozenSubmissionReader {
    fun read(
        repository: SubmissionRepository,
        batch: SubmissionBatch,
    ): List<Submission> {
        val ids = batch.submissionVersionIds
        check(ids.size >= 2 && ids.distinct().size == ids.size) { "Frozen batch version identity is invalid" }
        val requested = ids.toSet()
        val exactHeads = repository.findLatestByRoom(batch.roomId).filter { it.latest.id in requested }
        val missing = ids.filter { id -> exactHeads.none { it.latest.id == id } }
        val historical = if (missing.isEmpty()) emptyList() else repository.findFrozenByVersionIds(batch.roomId, missing)
        check(historical.all { it.latest.id in missing }) { "Historical lookup returned an unrequested version" }
        val all = exactHeads + historical
        check(all.size == ids.size && all.map { it.latest.id }.toSet() == requested) { "Frozen submission batch is incomplete" }
        check(all.map { it.latest.id }.distinct().size == all.size) { "Duplicate frozen version" }
        check(all.all { it.roomId == batch.roomId }) { "Frozen version belongs to another room" }
        check(all.map { it.participantId }.distinct().size == all.size) { "Frozen batch duplicates an owner" }
        return all.sortedBy { it.participantId.value }
    }
}
