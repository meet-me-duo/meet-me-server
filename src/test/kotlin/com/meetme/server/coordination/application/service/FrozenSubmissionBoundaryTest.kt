package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Issue #92: frozen identities determine raw text, locale and reference date even after a head is edited.
class FrozenSubmissionBoundaryTest {
    @Test
    fun `historical version returns original text and date while changed latest head is ignored`() {
        val first = submission(1)
        val second = submission(2)
        val newer = first.revise(SubmissionVersionId(UUID(0, 91)), "edited tomorrow", emptyList(), Locale.US, NOW.plusSeconds(86400))
        val repository = repository(listOf(newer, second), listOf(first), listOf(first.latest.id))

        val frozen = FrozenSubmissionReader.read(repository, batch(first, second))
        assertEquals(first, frozen.first { it.participantId == first.participantId })
        assertEquals(NOW, frozen.first { it.participantId == first.participantId }.latest.createdAt)
        assertEquals(Locale.KOREAN, frozen.first { it.participantId == first.participantId }.latest.locale)
    }

    @Test
    fun `missing historical version never borrows latest text`() {
        val first = submission(1)
        val second = submission(2)
        val newer = first.revise(SubmissionVersionId(UUID(0, 91)), "edited tomorrow", emptyList(), Locale.US, NOW.plusSeconds(86400))
        val repository = repository(listOf(newer, second), emptyList(), listOf(first.latest.id))
        assertFailsWith<IllegalStateException> { FrozenSubmissionReader.read(repository, batch(first, second)) }
    }

    @Test
    fun `historical lookup must not return another room even for requested version identity`() {
        val first = submission(1)
        val second = submission(2)
        val foreign = Submission.restore(first.id, MeetingRoomId(UUID(0, 999)), first.participantId, first.latest)
        val repository = repository(emptyList(), listOf(foreign, second), listOf(first.latest.id, second.latest.id))
        assertFailsWith<IllegalStateException> { FrozenSubmissionReader.read(repository, batch(first, second)) }
    }

    @Test
    fun `historical lookup extra or duplicate identities fail closed`() {
        val first = submission(1)
        val second = submission(2)
        for (returned in listOf(listOf(first, second, submission(3)), listOf(first, first, second))) {
            val repository = repository(emptyList(), returned, listOf(first.latest.id, second.latest.id))
            assertFailsWith<IllegalStateException> { FrozenSubmissionReader.read(repository, batch(first, second)) }
        }
    }

    @Test
    fun `two versions for the same participant cannot satisfy a frozen cohort`() {
        val first = submission(1)
        val originalSecond = submission(2)
        val second = Submission.restore(originalSecond.id, originalSecond.roomId, first.participantId, originalSecond.latest)
        val repository = repository(emptyList(), listOf(first, second), listOf(first.latest.id, second.latest.id))
        assertFailsWith<IllegalStateException> { FrozenSubmissionReader.read(repository, batch(first, second)) }
    }

    private fun repository(
        heads: List<Submission>,
        historical: List<Submission>,
        requested: List<SubmissionVersionId>,
    ): SubmissionRepository =
        mock(SubmissionRepository::class.java).also {
            `when`(it.findLatestByRoom(ROOM)).thenReturn(heads)
            `when`(it.findFrozenByVersionIds(ROOM, requested)).thenReturn(historical)
        }

    private fun batch(vararg submissions: Submission) =
        SubmissionBatch(SubmissionBatchId(UUID(0, 90)), ROOM, submissions.map { it.latest.id }, NOW)

    private fun submission(index: Long) =
        Submission.start(
            SubmissionId(UUID(0, 100 + index)),
            ROOM,
            ParticipantId(UUID(0, 200 + index)),
            SubmissionVersionId(UUID(0, 300 + index)),
            "original $index",
            emptyList(),
            Locale.KOREAN,
            NOW,
        )

    companion object {
        private val ROOM = MeetingRoomId(UUID(0, 1))
        private val NOW = Instant.parse("2026-10-07T14:59:59Z")
    }
}
