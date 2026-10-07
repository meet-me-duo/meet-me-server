package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #96: only budget exhaustion after a prior transient Gemini failure can move a rejected admission to Luna. */
class RejectedAdmissionFallbackContractTest {
    @Test
    fun `second Gemini admission rejected at second 43 starts Luna with fourteen remaining seconds`() {
        val fixture = Fixture()
        fixture.onClaim = { provider, number ->
            if (provider == AnalysisProvider.GEMINI && number == 2) {
                fixture.elapsed = Duration.ofSeconds(43)
                false
            } else {
                true
            }
        }

        fixture.process()

        assertEquals(listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI), fixture.calls.map { it.first })
        assertEquals(
            Duration.ofSeconds(14),
            fixture.calls
                .last()
                .second.callTimeout,
        )
        assertEquals(1, fixture.published.size)
        verify(fixture.persistence, never()).delayBounded(anyValue(), anyValue())
    }

    @Test
    fun `admission rejected before budget exhaustion does not authorize another provider`() {
        val fixture = Fixture()
        fixture.onClaim = { provider, number ->
            if (provider == AnalysisProvider.GEMINI && number == 2) {
                fixture.elapsed = Duration.ofSeconds(1)
                false
            } else {
                true
            }
        }

        fixture.process()

        assertEquals(listOf(AnalysisProvider.GEMINI), fixture.calls.map { it.first })
        assertTrue(fixture.published.isEmpty())
    }

    @Test
    fun `stale run rejected at exhausted Gemini budget cannot start Luna`() {
        val fixture = Fixture()
        fixture.onClaim = { provider, number ->
            if (provider == AnalysisProvider.GEMINI && number == 2) {
                fixture.elapsed = Duration.ofSeconds(43)
                `when`(fixture.persistence.isCurrent(fixture.run)).thenReturn(false)
                false
            } else {
                true
            }
        }

        fixture.process()

        assertEquals(listOf(AnalysisProvider.GEMINI), fixture.calls.map { it.first })
        assertTrue(fixture.published.isEmpty())
    }

    private class Fixture {
        var elapsed: Duration = Duration.ZERO
        var onClaim: (AnalysisProvider, Int) -> Boolean = { _, _ -> true }
        val calls = mutableListOf<Pair<AnalysisProvider, NaturalLanguageBatchRequest>>()
        val updated = mutableListOf<CoordinationAttempt>()
        val published = mutableListOf<List<StructuredSubmissionResult>>()
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        private val runRepository = mock(CoordinationRunRepository::class.java)
        private val submissionRepository = mock(SubmissionRepository::class.java)
        private val structuredRepository = mock(StructuredSubmissionRepository::class.java)
        private val attemptRepository = mock(CoordinationAttemptRepository::class.java)
        private val invocationRepository = mock(AnalysisInvocationRepository::class.java)
        private val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "synthetic admission budget",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12)),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        private val submissions =
            (1..2).map {
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    room.id,
                    ParticipantId(UUID.randomUUID()),
                    SubmissionVersionId(UUID.randomUUID()),
                    "수요일 19–21",
                    emptyList(),
                    Locale.KOREAN,
                    NOW,
                )
            }
        private val queued =
            CoordinationRun.queued(
                CoordinationRunId(UUID.randomUUID()),
                SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, submissions.map { it.latest.id }, NOW),
            )
        val run = queued.startStructuring()
        val invocation = AnalysisInvocation(UUID.randomUUID(), run.id, run.version, NOW, NOW.plusSeconds(60), UUID.randomUUID())

        init {
            `when`(runRepository.findByBatchId(run.batch.id)).thenReturn(queued)
            `when`(persistence.start(queued)).thenReturn(run)
            `when`(persistence.isCurrent(run)).thenReturn(true)
            `when`(persistence.room(room.id)).thenReturn(room)
            `when`(submissionRepository.findLatestByRoom(room.id)).thenReturn(submissions)
            `when`(persistence.claimInvocation(anyValue(), anyValue(), anyValue())).thenReturn(invocation)
            doAnswer { call ->
                val attempt = call.getArgument<CoordinationAttempt>(3)
                onClaim(call.getArgument(2), attempt.attemptNumber)
            }.`when`(persistence).claimAttempt(anyValue(), anyValue(), anyValue(), anyValue())
            doAnswer { call ->
                updated += call.getArgument<CoordinationAttempt>(0)
                null
            }.`when`(attemptRepository).update(anyValue())
            doAnswer { call ->
                published += call.getArgument<List<StructuredSubmissionResult>>(1)
                true
            }.`when`(persistence).completeBounded(anyValue(), anyValue(), anyValue(), anyValue(), anyValue())
        }

        fun process() {
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = NOW.plus(elapsed)
                }
            GeminiBatchProcessor(
                runRepository,
                submissionRepository,
                structuredRepository,
                attemptRepository,
                NaturalLanguageParserPort {
                    calls += AnalysisProvider.GEMINI to it
                    throw NaturalLanguageParserException(ParserFailureKind.SERVER)
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { elapsed += it },
                JitterPort { 0 },
                MonotonicTimePort { elapsed.toNanos() },
                clock,
                persistence,
                fallbackParser =
                    NaturalLanguageParserPort {
                        calls += AnalysisProvider.OPENAI to it
                        NaturalLanguageBatchResult(
                            submissions.map { submission ->
                                StructuredSubmissionResult(
                                    submission.latest.id,
                                    listOf(StructuredCondition.TravelConstraint("synthetic")),
                                    null,
                                )
                            },
                            ParserUsage(null, null, null),
                        )
                    },
                invocationRepository = invocationRepository,
            ).process(run.batch.id)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
