package com.meetme.server.coordination.application.service

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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96 accepted contract: deterministic clock/fake parsers, zero external API calls. */
class BoundedProviderFallbackContractTest {
    @Test
    fun `four fast transient Gemini failures use Full Jitter then exactly one Luna call`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
        f.jitterValue = 100

        f.process()

        assertEquals(listOf("GEMINI", "GEMINI", "GEMINI", "GEMINI", "LUNA"), f.calls.map { it.first })
        assertEquals(listOf(1_001L, 2_001L, 4_001L), f.jitterBounds)
        assertEquals(List(3) { Duration.ofMillis(100) }, f.delays)
        assertEquals(5, f.attempts.size)
        assertEquals(1, f.published.size)
        assertEquals(f.success().results, f.published.single())
        verify(f.persistence, never()).delay(anyValue())
    }

    @Test
    fun `timeouts exhaust Gemini 42 seconds with only two retries and reserve Luna 15 seconds`() {
        val f = Fixture()
        f.geminiAction = { request ->
            f.advance(request.callTimeout)
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAction = { request ->
            f.advance(request.callTimeout)
            f.success()
        }

        f.process()

        assertEquals(listOf("GEMINI", "GEMINI", "GEMINI", "LUNA"), f.calls.map { it.first })
        assertEquals(listOf(15L, 15L, 12L, 15L), f.calls.map { it.second.callTimeout.seconds })
        assertEquals(Duration.ofSeconds(57), f.elapsed)
        assertEquals(1, f.published.size)
    }

    @Test
    fun `retry-after that cannot fit Gemini budget skips sleep and preserves fallback reserve`() {
        val f = Fixture()
        f.geminiAction = { request ->
            f.advance(request.callTimeout)
            throw NaturalLanguageParserException(ParserFailureKind.RATE_LIMIT, retryAfterMillis = 40_000)
        }

        f.process()

        assertEquals(listOf("GEMINI", "LUNA"), f.calls.map { it.first })
        assertTrue(f.delays.isEmpty())
        assertEquals(
            Duration.ofSeconds(15),
            f.calls
                .last()
                .second.callTimeout,
        )
        assertEquals(1, f.published.size)
    }

    @ParameterizedTest
    @EnumSource(ParserFailureKind::class, names = ["NETWORK", "TIMEOUT", "RATE_LIMIT", "SERVER"])
    fun `only transient Gemini failure kinds are eligible for Luna`(kind: ParserFailureKind) {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(kind) }

        f.process()

        assertEquals(4, f.calls.count { it.first == "GEMINI" })
        assertEquals(1, f.calls.count { it.first == "LUNA" })
    }

    @ParameterizedTest
    @EnumSource(
        ParserFailureKind::class,
        names = ["INVALID_RESPONSE", "CONFIGURATION", "AUTHENTICATION", "PERMISSION", "BILLING", "QUOTA", "INVALID_REQUEST"],
    )
    fun `authentication billing request and response failures delay without retry or fallback`(kind: ParserFailureKind) {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(kind) }

        f.process()

        assertEquals(listOf("GEMINI"), f.calls.map { it.first })
        assertTrue(f.delays.isEmpty())
        assertTrue(f.published.isEmpty())
        verify(f.persistence).delay(f.run.startStructuring())
    }

    @Test
    fun `two provider failures retain batch and never publish structured data or candidates`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.SERVER) }
        f.lunaAction = { throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT) }
        val originalBatch = f.run.batch
        val originalVersions = f.submissions.map { it.latest }

        f.process()

        assertEquals(5, f.calls.size)
        assertTrue(f.published.isEmpty())
        assertEquals(originalBatch, f.run.batch)
        assertEquals(originalVersions, f.submissions.map { it.latest })
        verify(f.persistence).delay(f.run.startStructuring())
        verifyNoInteractions(f.structuredRepository)
        assertNull(f.run.quality)
        assertTrue(f.run.candidates.isEmpty())
    }

    @Test
    fun `provider semantic rejection remains a successful result and never triggers Luna`() {
        val f = Fixture()
        f.geminiAction = {
            NaturalLanguageBatchResult(
                f.submissions.map { StructuredSubmissionResult(it.latest.id, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT") },
                ParserUsage(null, null, null),
            )
        }

        f.process()

        assertEquals(listOf("GEMINI"), f.calls.map { it.first })
        assertEquals(listOf("AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_TIME_CONSTRAINT"), f.published.single().map { it.rejectionCode })
        verify(f.persistence, never()).delay(anyValue())
    }

    @Test
    fun `every provider call receives the same frozen input and submission-local reference date`() {
        val f = Fixture()
        f.geminiAction = { throw NaturalLanguageParserException(ParserFailureKind.NETWORK) }

        f.process()

        val requests = f.calls.map { it.second.copy(callTimeout = Duration.ofSeconds(15)) }
        assertEquals(1, requests.distinct().size)
        val request = requests.first()
        assertEquals(ZoneId.of("Asia/Seoul"), request.timeZone)
        assertEquals(LocalDate.of(2026, 10, 7), request.searchStartDate)
        assertEquals(LocalDate.of(2026, 10, 12), request.searchEndDate)
        assertEquals(
            f.submissions
                .map {
                    it.latest.id.value
                        .toString()
                }.toSet(),
            request.inputs.map { it.inputRef }.toSet(),
        )
        assertEquals(setOf(LocalDate.of(2026, 10, 7)), request.inputs.map { it.referenceDate }.toSet())
        assertEquals(f.submissions.map { it.latest.rawText }.toSet(), request.inputs.map { it.rawText }.toSet())
    }

    @Test
    fun `late Gemini success cannot publish and invokes Luna while reserve remains`() {
        val f = Fixture()
        f.geminiAction = {
            f.advance(Duration.ofSeconds(43))
            f.success()
        }

        f.process()

        assertEquals(listOf("GEMINI", "LUNA"), f.calls.map { it.first })
        assertEquals(
            Duration.ofSeconds(14),
            f.calls
                .last()
                .second.callTimeout,
        )
        assertEquals(1, f.published.size)
    }

    @Test
    fun `late Luna success cannot publish inside the completion reserve`() {
        val f = Fixture()
        f.geminiAction = {
            f.advance(Duration.ofSeconds(42))
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }
        f.lunaAction = {
            f.advance(Duration.ofSeconds(16))
            f.success()
        }

        f.process()

        assertEquals(listOf("GEMINI", "LUNA"), f.calls.map { it.first })
        assertTrue(f.published.isEmpty())
        verify(f.persistence).delay(f.run.startStructuring())
    }

    @Test
    fun `no provider call begins once no completion-safe time remains`() {
        val f = Fixture()
        f.geminiAction = {
            f.advance(Duration.ofSeconds(60))
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        }

        f.process()

        assertEquals(listOf("GEMINI"), f.calls.map { it.first })
        assertTrue(f.published.isEmpty())
        verify(f.persistence).delay(f.run.startStructuring())
    }

    private class Fixture {
        var elapsed = Duration.ZERO
        var jitterValue = 0L
        val jitterBounds = mutableListOf<Long>()
        val delays = mutableListOf<Duration>()
        val calls = mutableListOf<Pair<String, NaturalLanguageBatchRequest>>()
        val attempts = mutableListOf<CoordinationAttempt>()
        val published = mutableListOf<List<StructuredSubmissionResult>>()
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "fixture",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12)),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        val submissions =
            listOf("평일 오후7~9", "매일 오후6~10").mapIndexed { index, raw ->
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    room.id,
                    ParticipantId(UUID(0, index.toLong() + 1)),
                    SubmissionVersionId(UUID.randomUUID()),
                    raw,
                    emptyList(),
                    Locale.KOREAN,
                    NOW,
                )
            }
        val run =
            CoordinationRun.queued(
                CoordinationRunId(UUID.randomUUID()),
                SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, submissions.map { it.latest.id }, NOW),
            )
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        val structuredRepository = mock(StructuredSubmissionRepository::class.java)
        private val runRepository = mock(CoordinationRunRepository::class.java)
        private val submissionRepository = mock(SubmissionRepository::class.java)
        private val attemptRepository = mock(CoordinationAttemptRepository::class.java)
        var geminiAction: (NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult = { success() }
        var lunaAction: (NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult = { success() }

        init {
            `when`(runRepository.findByBatchId(run.batch.id)).thenReturn(run)
            `when`(persistence.start(run)).thenReturn(run.startStructuring())
            `when`(persistence.isCurrent(run.startStructuring())).thenReturn(true)
            `when`(persistence.room(room.id)).thenReturn(room)
            `when`(submissionRepository.findLatestByRoom(room.id)).thenReturn(submissions)
            `when`(attemptRepository.countByRun(run.id)).thenReturn(0)
            doAnswer {
                attempts += it.getArgument<CoordinationAttempt>(0)
                null
            }.`when`(attemptRepository).insert(anyValue())
            doAnswer {
                published += it.getArgument<List<StructuredSubmissionResult>>(1)
                null
            }.`when`(persistence)
                .complete(anyValue(), anyValue(), anyValue())
        }

        fun advance(duration: Duration) {
            elapsed += duration
        }

        fun success() =
            NaturalLanguageBatchResult(
                submissions.map { StructuredSubmissionResult(it.latest.id, listOf(StructuredCondition.TravelConstraint("fixture")), null) },
                ParserUsage(100, 50, 200),
            )

        fun process() {
            GeminiBatchProcessor(
                runRepository,
                submissionRepository,
                structuredRepository,
                attemptRepository,
                NaturalLanguageParserPort {
                    calls += "GEMINI" to it
                    geminiAction(it)
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort {
                    delays += it
                    advance(it)
                },
                JitterPort {
                    jitterBounds += it
                    jitterValue
                },
                MonotonicTimePort { elapsed.toNanos() },
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = NOW.plus(elapsed)
                },
                persistence,
                fallbackParser =
                    NaturalLanguageParserPort {
                        calls += "LUNA" to it
                        lunaAction(it)
                    },
            ).process(run.batch.id)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T02:00:00Z")

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
