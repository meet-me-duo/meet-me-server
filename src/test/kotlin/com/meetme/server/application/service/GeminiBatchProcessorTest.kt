package com.meetme.server.application.service

import com.meetme.server.application.port.output.CoordinationAttempt
import com.meetme.server.application.port.output.CoordinationAttemptRepository
import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.IdGenerator
import com.meetme.server.application.port.output.JitterPort
import com.meetme.server.application.port.output.MonotonicTimePort
import com.meetme.server.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.application.port.output.NaturalLanguageParserException
import com.meetme.server.application.port.output.NaturalLanguageParserPort
import com.meetme.server.application.port.output.ParserFailureKind
import com.meetme.server.application.port.output.ParserUsage
import com.meetme.server.application.port.output.RetryDelayPort
import com.meetme.server.application.port.output.StructuredSubmissionRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.SubmissionBatch
import com.meetme.server.domain.meeting.ClosurePolicy
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.submission.StructuredCondition
import com.meetme.server.domain.submission.StructuredSubmissionResult
import com.meetme.server.domain.submission.Submission
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals

class GeminiBatchProcessorTest {
    @Test
    fun `기술 오류만 Full Jitter로 세 번 재시도한 뒤 성공한다`() {
        val fixture = fixture()
        val parser = mock(NaturalLanguageParserPort::class.java)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        val runRepository = mock(CoordinationRunRepository::class.java)
        val submissionRepository = mock(SubmissionRepository::class.java)
        val structuredRepository = mock(StructuredSubmissionRepository::class.java)
        val delays = mutableListOf<Duration>()
        val jitterValues = listOf(200L, 400L).iterator()
        val jitterBounds = mutableListOf<Long>()
        val ids = generateSequence { UUID.randomUUID() }.iterator()
        var nanos = 0L
        var completedAttempt: CoordinationAttempt? = null

        `when`(runRepository.findByBatchId(fixture.run.batch.id)).thenReturn(fixture.run)
        `when`(persistence.start(fixture.run)).thenReturn(fixture.run.startStructuring())
        `when`(persistence.room(fixture.room.id)).thenReturn(fixture.room)
        `when`(submissionRepository.findLatestByRoom(fixture.room.id)).thenReturn(fixture.submissions)
        `when`(attempts.countByRun(fixture.run.id)).thenReturn(0)
        Mockito
            .doAnswer {
                completedAttempt = it.getArgument(2)
                null
            }.`when`(persistence)
            .complete(anyValue(), anyValue(), anyValue())
        `when`(parser.parse(anyValue()))
            .thenThrow(
                NaturalLanguageParserException(ParserFailureKind.RATE_LIMIT, retryAfterMillis = 750),
                NaturalLanguageParserException(ParserFailureKind.TIMEOUT),
                NaturalLanguageParserException(ParserFailureKind.SERVER),
            ).thenReturn(
                NaturalLanguageBatchResult(
                    listOf(
                        StructuredSubmissionResult(
                            fixture.submissions
                                .first()
                                .latest.id,
                            listOf(StructuredCondition.TravelConstraint("학교 근처")),
                            null,
                        ),
                    ),
                    ParserUsage(100, 50, 512),
                ),
            )
        val processor =
            GeminiBatchProcessor(
                runRepository,
                submissionRepository,
                structuredRepository,
                attempts,
                parser,
                IdGenerator { ids.next() },
                RetryDelayPort {
                    delays += it
                    nanos += it.toNanos()
                },
                JitterPort {
                    jitterBounds += it
                    jitterValues.next()
                },
                MonotonicTimePort { nanos },
                Clock.fixed(NOW, ZoneOffset.UTC),
                persistence,
            )

        processor.process(fixture.run.batch.id)

        assertEquals(listOf(Duration.ofMillis(750), Duration.ofMillis(200), Duration.ofMillis(400)), delays)
        assertEquals(listOf(2_001L, 4_001L), jitterBounds)
        assertEquals(Duration.ofSeconds(60), GeminiBatchProcessor.TOTAL_TIMEOUT)
        verify(parser, times(4)).parse(anyValue())
        verify(attempts, times(4)).insert(anyValue())
        verify(persistence).complete(anyValue(), anyValue(), anyValue())
        assertEquals(100, completedAttempt?.inputTokens)
        assertEquals(50, completedAttempt?.outputTokens)
        assertEquals(512, completedAttempt?.responseBytes)
        assertEquals(0, requireNotNull(completedAttempt?.estimatedCostUsd).compareTo(BigDecimal("0.00026250")))
    }

    @Test
    fun `스키마 오류는 재시도하지 않고 분석 지연으로 보존한다`() {
        val fixture = fixture()
        val parser = mock(NaturalLanguageParserPort::class.java)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        val runRepository = mock(CoordinationRunRepository::class.java)
        val submissionRepository = mock(SubmissionRepository::class.java)
        `when`(runRepository.findByBatchId(fixture.run.batch.id)).thenReturn(fixture.run)
        `when`(persistence.start(fixture.run)).thenReturn(fixture.run.startStructuring())
        `when`(persistence.room(fixture.room.id)).thenReturn(fixture.room)
        `when`(submissionRepository.findLatestByRoom(fixture.room.id)).thenReturn(fixture.submissions)
        `when`(attempts.countByRun(fixture.run.id)).thenReturn(0)
        `when`(parser.parse(anyValue())).thenThrow(NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE))
        val processor =
            GeminiBatchProcessor(
                runRepository,
                submissionRepository,
                mock(StructuredSubmissionRepository::class.java),
                attempts,
                parser,
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { error("must not sleep") },
                JitterPort { error("must not jitter") },
                MonotonicTimePort { 0 },
                Clock.fixed(NOW, ZoneOffset.UTC),
                persistence,
            )

        processor.process(fixture.run.batch.id)

        verify(parser).parse(anyValue())
        verify(persistence).delay(fixture.run.startStructuring())
    }

    @Test
    fun `네 번의 기술 시도가 모두 실패하면 고정 배치를 분석 지연으로 보존한다`() {
        val fixture = fixture()
        val parser = mock(NaturalLanguageParserPort::class.java)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        val runRepository = mock(CoordinationRunRepository::class.java)
        val submissionRepository = mock(SubmissionRepository::class.java)
        `when`(runRepository.findByBatchId(fixture.run.batch.id)).thenReturn(fixture.run)
        `when`(persistence.start(fixture.run)).thenReturn(fixture.run.startStructuring())
        `when`(persistence.room(fixture.room.id)).thenReturn(fixture.room)
        `when`(submissionRepository.findLatestByRoom(fixture.room.id)).thenReturn(fixture.submissions)
        `when`(attempts.countByRun(fixture.run.id)).thenReturn(0)
        `when`(parser.parse(anyValue())).thenThrow(NaturalLanguageParserException(ParserFailureKind.NETWORK))
        val processor =
            GeminiBatchProcessor(
                runRepository,
                submissionRepository,
                mock(StructuredSubmissionRepository::class.java),
                attempts,
                parser,
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort {},
                JitterPort { 0 },
                MonotonicTimePort { 0 },
                Clock.fixed(NOW, ZoneOffset.UTC),
                persistence,
            )

        processor.process(fixture.run.batch.id)

        verify(parser, times(4)).parse(anyValue())
        verify(persistence).delay(fixture.run.startStructuring())
    }

    private fun fixture(): Fixture {
        val roomId = MeetingRoomId(UUID.randomUUID())
        val versionId = SubmissionVersionId(UUID.randomUUID())
        val room =
            MeetingRoom.create(
                roomId,
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "회의",
                MeetingDuration.ofMinutes(60),
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 28)),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        val secondVersionId = SubmissionVersionId(UUID.randomUUID())
        val submissions =
            listOf(versionId, secondVersionId).map { currentVersionId ->
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    roomId,
                    ParticipantId(UUID.randomUUID()),
                    currentVersionId,
                    "월요일 저녁 학교 근처",
                    emptyList(),
                    Locale.forLanguageTag("ko-KR"),
                    NOW,
                )
            }
        val batch = SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), roomId, listOf(versionId, secondVersionId), NOW)
        val run = CoordinationRun.queued(CoordinationRunId(UUID.randomUUID()), batch)
        return Fixture(room, submissions, run)
    }

    private data class Fixture(
        val room: MeetingRoom,
        val submissions: List<Submission>,
        val run: CoordinationRun,
    )

    companion object {
        private val NOW = Instant.parse("2026-09-19T00:00:00Z")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
