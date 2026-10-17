package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.AnalysisExecutionObservation
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.application.port.output.ObservedAnalysisProvider
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
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Processor identity is carried explicitly only after durable admission. No network executes. */
class GeminiBatchProcessorTransportContextTest {
    @Test
    fun `durably admitted parser request carries exactly the current invocation and attempt identity`() {
        val fixture = Fixture()
        fixture.process()
        val admission = fixture.admitted.single()
        assertEquals(fixture.invocation.id, admission.invocationId)
        assertEquals(ParserObservationContext(fixture.invocation.id, admission.id), fixture.requests.single().observationContext)
    }

    @Test
    fun `legacy processing without invocation keeps explicit context absent`() {
        val fixture = Fixture(bounded = false)
        fixture.process()
        assertNull(fixture.requests.single().observationContext)
        assertTrue(fixture.snapshots.isEmpty())
    }

    @Test
    fun `rejected durable admission does not dispatch or invent a transport observation`() {
        val fixture = Fixture()
        fixture.claimAllowed = false
        fixture.process()
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.snapshots.isEmpty())
        assertTrue(fixture.admitted.isEmpty())
    }

    @Test
    fun `deadline consumed by successful admission records exact known not dispatched snapshot`() {
        val fixture = Fixture()
        fixture.onAdmission = { fixture.elapsed = Duration.ofSeconds(60) }
        fixture.process()
        assertTrue(fixture.requests.isEmpty())
        val admission = fixture.admitted.single()
        val observation = fixture.snapshots.single()
        assertEquals(ParserObservationContext(fixture.invocation.id, admission.id), observation.context)
        assertFalse(observation.parserDispatched)
        assertTrue(observation.calls.isEmpty())
    }

    private class Fixture(
        private val bounded: Boolean = true,
    ) {
        var elapsed: Duration = Duration.ZERO
        var onAdmission: () -> Unit = {}
        var claimAllowed = true
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val requests = mutableListOf<NaturalLanguageBatchRequest>()
        val admitted = mutableListOf<CoordinationAttempt>()
        var gemini: (NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult = { result(100, 50) }
        var fallback: ((NaturalLanguageBatchRequest) -> NaturalLanguageBatchResult)? = null
        val observations = mutableListOf<AnalysisExecutionObservation>()
        val legacyCosts = mutableListOf<BigDecimal?>()
        val dispatches = mutableListOf<ObservedAnalysisProvider>()
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        private val runs = mock(CoordinationRunRepository::class.java)
        private val submissions = mock(SubmissionRepository::class.java)
        private val attempts = mock(CoordinationAttemptRepository::class.java)
        private val metrics = mock(ApplicationMetricsPort::class.java)
        private val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "synthetic observation room",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12)),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        private val inputs =
            (1..2).map {
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    room.id,
                    ParticipantId(UUID.randomUUID()),
                    SubmissionVersionId(UUID.randomUUID()),
                    "synthetic private input excluded from observations",
                    emptyList(),
                    Locale.KOREAN,
                    NOW,
                )
            }
        private val queued =
            CoordinationRun.queued(
                CoordinationRunId(UUID.randomUUID()),
                SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, inputs.map { it.latest.id }, NOW),
            )
        val run = queued.startStructuring()
        val invocation = AnalysisInvocation(UUID(0, 991), run.id, run.version, NOW, NOW.plusSeconds(60), UUID(0, 992))

        init {
            `when`(runs.findByBatchId(run.batch.id)).thenReturn(queued)
            `when`(persistence.start(queued)).thenReturn(run)
            `when`(persistence.isCurrent(run)).thenReturn(true)
            `when`(persistence.claimInvocation(anyValue(), anyValue(), anyValue())).thenReturn(invocation)
            doAnswer {
                if (claimAllowed) {
                    admitted += it.getArgument<CoordinationAttempt>(3)
                    onAdmission()
                }
                claimAllowed
            }.`when`(persistence).claimAttempt(anyValue(), anyValue(), anyValue(), anyValue())
            `when`(persistence.room(room.id)).thenReturn(room)
            `when`(submissions.findLatestByRoom(room.id)).thenReturn(inputs)
            doAnswer {
                onAdmission()
                null
            }.`when`(attempts).insert(anyValue())
            doAnswer {
                observations += it.getArgument<AnalysisExecutionObservation>(0)
                null
            }.`when`(metrics).analysisExecution(anyValue())
            doAnswer {
                legacyCosts += it.getArgument<BigDecimal?>(3)
                null
            }.`when`(metrics).geminiBatch(anyValue(), anyValue(), Mockito.anyInt(), anyValue())
        }

        fun result(
            input: Long?,
            output: Long?,
        ) = NaturalLanguageBatchResult(
            inputs.map {
                StructuredSubmissionResult(it.latest.id, listOf(StructuredCondition.TravelConstraint("synthetic area")), null)
            },
            ParserUsage(input, output, 512),
        )

        fun process() {
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = NOW.plus(elapsed)
                }
            GeminiBatchProcessor(
                runs,
                submissions,
                mock(StructuredSubmissionRepository::class.java),
                attempts,
                NaturalLanguageParserPort {
                    requests += it
                    dispatches += ObservedAnalysisProvider.GEMINI
                    gemini(it)
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { elapsed += it },
                JitterPort { 0 },
                MonotonicTimePort { elapsed.toNanos() },
                clock,
                persistence,
                metrics = metrics,
                invocationRepository = if (bounded) mock(AnalysisInvocationRepository::class.java) else null,
                transportObservations = ParserTransportObservationPort { snapshots += it },
                fallbackParser =
                    fallback?.let { parse ->
                        NaturalLanguageParserPort {
                            dispatches += ObservedAnalysisProvider.OPENAI
                            parse(it)
                        }
                    },
            ).process(run.batch.id)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-07T00:00:00Z")

        fun assertCost(
            expected: String,
            actual: BigDecimal?,
        ) {
            assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
