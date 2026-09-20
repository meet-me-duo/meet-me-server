package com.meetme.server.coordination.domain

import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class CoordinationRunTest {
    private val now = Instant.parse("2026-09-18T00:00:00Z")

    @Test
    fun `구조화와 매칭을 거쳐 순위가 고정된 후보를 완료한다`() {
        val candidates = listOf(candidate(2), candidate(1))
        val completed = run().startStructuring().startMatching().complete(CandidateQuality.COMPLETE, candidates)

        assertEquals(CoordinationStatus.COMPLETED, completed.status)
        assertEquals(listOf(1, 2), completed.candidates.map { it.rank })
        assertEquals(CandidateQuality.COMPLETE, completed.quality)
    }

    @Test
    fun `완료된 후보 중 하나만 확정할 수 있다`() {
        val candidate = candidate(1)
        val completed = run().startMatching().complete(CandidateQuality.PARTIAL, listOf(candidate))
        val confirmed = completed.confirm(candidate.id, now.plusSeconds(60))

        assertEquals(candidate.id, confirmed.confirmedCandidateId)
        assertThrows<IllegalStateException> { confirmed.confirm(candidate.id, now.plusSeconds(120)) }
        assertThrows<IllegalArgumentException> {
            completed.confirm(CandidateId(UUID.randomUUID()), now.plusSeconds(60))
        }
    }

    @Test
    fun `허용되지 않은 상태 전이를 거부한다`() {
        assertThrows<IllegalStateException> { run().complete(CandidateQuality.COMPLETE, listOf(candidate(1))) }
        assertThrows<IllegalStateException> { run().confirm(candidate(1).id, now) }
    }

    @Test
    fun `DLQ 재처리는 실패한 단계를 보존해 구조화 또는 매칭부터 재개한다`() {
        val structuring = run().startStructuring().deadLetter().retryDeadLetter()
        val matching = run().startMatching().deadLetter().retryDeadLetter()

        assertEquals(CoordinationStatus.QUEUED, structuring.status)
        assertEquals(CoordinationStatus.MATCHING, matching.status)
    }

    private fun run(): CoordinationRun {
        val roomId = MeetingRoomId(UUID.randomUUID())
        val batch =
            SubmissionBatch(
                id = SubmissionBatchId(UUID.randomUUID()),
                roomId = roomId,
                submissionVersionIds = listOf(SubmissionVersionId(UUID.randomUUID()), SubmissionVersionId(UUID.randomUUID())),
                fixedAt = now,
            )
        return CoordinationRun.queued(CoordinationRunId(UUID.randomUUID()), batch)
    }

    private fun candidate(rank: Int) =
        MeetingCandidate(
            id = CandidateId(UUID.randomUUID()),
            rank = rank,
            timeRanges = listOf(InstantTimeRange(now.plusSeconds(rank * 3600L), now.plusSeconds((rank + 1) * 3600L))),
        )
}
