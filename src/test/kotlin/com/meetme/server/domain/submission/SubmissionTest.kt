package com.meetme.server.domain.submission

import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals

class SubmissionTest {
    private val at = Instant.parse("2026-09-18T00:00:00Z")

    @Test
    fun `자연어는 Unicode 코드 포인트 500자까지 허용한다`() {
        val text = "😀".repeat(500)
        val submission = submission(text)

        assertEquals(text, submission.latest.rawText)
        assertThrows<IllegalArgumentException> { submission("😀".repeat(501)) }
    }

    @Test
    fun `공백 자연어와 빈 수동 시간만 있으면 제출을 거부한다`() {
        assertThrows<IllegalArgumentException> { submission("   ") }
        assertThrows<IllegalArgumentException> { submission(null) }
    }

    @Test
    fun `수정은 새 불변 버전을 만들고 revision을 증가시킨다`() {
        val first = submission("화요일 저녁")
        val revised =
            first.revise(
                versionId = SubmissionVersionId(UUID.randomUUID()),
                rawText = "목요일 저녁",
                manualAvailability = emptyList(),
                locale = Locale.forLanguageTag("ko-KR"),
                at = at.plusSeconds(60),
            )

        assertEquals(1, first.latest.revision)
        assertEquals(2, revised.latest.revision)
        assertEquals("목요일 저녁", revised.latest.rawText)
        assertEquals("화요일 저녁", first.latest.rawText)
    }

    private fun submission(rawText: String?) =
        Submission.start(
            id = SubmissionId(UUID.randomUUID()),
            roomId = MeetingRoomId(UUID.randomUUID()),
            participantId = ParticipantId(UUID.randomUUID()),
            versionId = SubmissionVersionId(UUID.randomUUID()),
            rawText = rawText,
            manualAvailability = emptyList(),
            locale = Locale.forLanguageTag("ko-KR"),
            at = at,
        )
}
