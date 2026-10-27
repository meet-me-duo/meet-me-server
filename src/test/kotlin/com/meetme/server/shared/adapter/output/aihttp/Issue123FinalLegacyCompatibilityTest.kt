package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.AiDispatchModePort
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
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Legacy constructor compatibility uses in-memory repositories, matching its historical public seam. */
class Issue123FinalLegacyCompatibilityTest {
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `AD01 absent or OFF mode preserves legacy no fallback no invocation repository successful wire`(modeAbsent: Boolean) {
        val zone = MeetingTimeZone.of("Asia/Seoul")
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID(0, 123)),
                InviteCode.fromEntropy(
                    ByteArray(16) {
                        it.toByte()
                    },
                ),
                "Synthetic legacy",
                MeetingMode.EITHER,
                zone,
                SearchDateRange.explicit(
                    LocalDate.of(2026, 10, 18),
                    LocalDate.of(2026, 10, 23),
                ),
                ClosurePolicy.of(expectedParticipants = 2),
                dispatchNow,
            )
        val submissions =
            (1L..2L).map { n ->
                Submission.start(
                    SubmissionId(UUID(1, n)),
                    room.id,
                    ParticipantId(UUID(2, n)),
                    SubmissionVersionId(UUID(3, n)),
                    "synthetic immutable source $n",
                    emptyList(),
                    Locale.forLanguageTag("ko-KR"),
                    dispatchNow,
                )
            }
        val batch = SubmissionBatch(SubmissionBatchId(UUID(0, 701)), room.id, submissions.map { it.latest.id }, dispatchNow)
        val initial = CoordinationRun.queued(CoordinationRunId(UUID(0, 702)), batch)
        val current = initial.startStructuring()
        val runs = mock(CoordinationRunRepository::class.java)
        val source = mock(SubmissionRepository::class.java)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val persistence = mock(GeminiProcessingPersistenceService::class.java)
        `when`(runs.findByBatchId(batch.id)).thenReturn(initial)
        `when`(persistence.start(initial)).thenReturn(current)
        `when`(persistence.isCurrent(current)).thenReturn(true)
        `when`(persistence.room(room.id)).thenReturn(room)
        `when`(source.findLatestByRoom(room.id)).thenReturn(submissions)
        val completed = AtomicInteger()
        Mockito
            .doAnswer {
                completed.incrementAndGet()
                null
            }.`when`(persistence)
            .complete(anyValue(), anyValue(), anyValue())
        val budget = DispatchBudget().apply { reserveFailure = true }
        val mapper = JsonMapper.builder().build()
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        submissions.map {
                            mapOf(
                                "input_ref" to
                                    it.latest.id.value
                                        .toString(),
                                "rejection_code" to null,
                                "conditions" to
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-10-18",
                                            "day_of_week" to null,
                                            "start_time" to "19:00",
                                            "end_time" to "21:00",
                                        ),
                                    ),
                            )
                        },
                ),
            )
        val response =
            mapper.writeValueAsString(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to payload))),
                            ),
                        ),
                ),
            )
        DispatchEndpoint(response = { _, _ -> 200 to response }).use { endpoint ->
            val parser =
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-key"),
                    mapper,
                    endpoint.origin,
                    dispatchGate = gate(budget, false),
                )
            val processor =
                GeminiBatchProcessor(
                    runs,
                    source,
                    mock(StructuredSubmissionRepository::class.java),
                    attempts,
                    parser,
                    IdGenerator {
                        UUID.randomUUID()
                    },
                    RetryDelayPort {
                    },
                    JitterPort {
                        0
                    },
                    MonotonicTimePort {
                        System.nanoTime()
                    },
                    Clock.fixed(
                        dispatchNow,
                        ZoneOffset.UTC,
                    ),
                    persistence,
                    dispatchMode = if (modeAbsent)null else AiDispatchModePort { false },
                )
            processor.process(batch.id)
            assertEquals(1, endpoint.requests.size)
            assertEquals(1, completed.get())
            assertTrue(budget.reserves.isEmpty())
            assertTrue(budget.claims.isEmpty())
            assertTrue(budget.observations.isEmpty())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
