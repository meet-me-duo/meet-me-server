package com.meetme.server.coordination.application.service

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
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
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #91: whole provider-fixture -> adapter -> batch processor -> matching pipeline.
// Synthetic data only. No provider network calls and no current-time interpretation.
class GeminiTimeReferenceDateTest {
    @Test
    fun `immutable submission creation instant becomes each rooms local reference date at KST midnight`() {
        val fixture = fixture(listOf(Instant.parse("2026-10-06T14:59:59Z"), Instant.parse("2026-10-06T15:00:00Z")))
        val captured = mutableListOf<NaturalLanguageBatchRequest>()

        fixture
            .processor { request ->
                captured += request
                fixture.parse(request)
            }.process(fixture.batch.id)

        assertEquals(listOf(LocalDate.of(2026, 10, 6), DATE), captured.single().inputs.map { it.referenceDate })
        assertEquals(
            fixture.submissions.map {
                it.latest.id.value
                    .toString()
            },
            captured.single().inputs.map { it.inputRef },
        )
    }

    @Test
    fun `technical retries keep frozen reference dates despite worker time a week later`() {
        val fixture = fixture(listOf(CREATED, CREATED))
        val captured = mutableListOf<NaturalLanguageBatchRequest>()

        fixture
            .processor { request ->
                captured += request
                if (captured.size == 1) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                fixture.parse(request)
            }.process(fixture.batch.id)

        assertEquals(2, captured.size)
        assertEquals(listOf(DATE, DATE), captured.first().inputs.map { it.referenceDate })
        assertEquals(captured.first(), captured.last())
    }

    @Test
    fun `new submission version uses its new creation date while older version stays immutable`() {
        val fixture = fixture(listOf(CREATED, CREATED))
        val original = fixture.submissions.first()
        val newVersion =
            original.revise(
                SubmissionVersionId(UUID.randomUUID()),
                CANONICAL,
                emptyList(),
                Locale.KOREAN,
                CREATED.plusSeconds(604_800),
            )
        val secondFixture =
            fixture(
                listOf(
                    newVersion.latest.createdAt,
                    fixture.submissions
                        .last()
                        .latest.createdAt,
                ),
            )
        val captured = mutableListOf<NaturalLanguageBatchRequest>()

        secondFixture
            .processor { request ->
                captured += request
                secondFixture.parse(request)
            }.process(secondFixture.batch.id)

        assertEquals(listOf(DATE.plusDays(7), DATE), captured.single().inputs.map { it.referenceDate })
        assertEquals(CREATED, original.latest.createdAt)
        assertEquals(
            DATE,
            original.latest.createdAt
                .atZone(fixture.room.timeZone.value)
                .toLocalDate(),
        )
    }

    @Test
    fun `adapter context flows through the real batch and matching processors to a complete two person result`() {
        val fixture = fixture(listOf(CREATED, CREATED))

        fixture.processor { fixture.parse(it) }.process(fixture.batch.id)

        assertEquals(CandidateQuality.COMPLETE, fixture.run.quality)
        assertEquals(
            2,
            fixture.run.candidates
                .single()
                .participantIds.size,
        )
        assertEquals(
            listOf(utc(0, 19, 21), utc(1, 20, 21), utc(2, 19, 21), utc(3, 14, 19), utc(4, 14, 19)),
            fixture.run.candidates
                .single()
                .timeRanges,
        )
        assertTrue(fixture.structured.all { it.rejectionCode == null })
    }

    @Test
    fun `unsafe ambiguous and coupled inputs stay unapplied with no invented attendance in real matching processor`() {
        for (reason in listOf("AMBIGUOUS_TIME_CONSTRAINT", "UNSUPPORTED_CONDITIONAL_CONSTRAINT")) {
            val fixture = fixture(listOf(CREATED, CREATED), listOf("이번주는 목요일만 8시부터 돼요.", CANONICAL))

            fixture.processor { fixture.parse(it, reason) }.process(fixture.batch.id)

            assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
            assertTrue(fixture.run.candidates.isEmpty(), reason)
            assertEquals(reason, fixture.structured.first().rejectionCode)
            assertEquals(emptyList(), fixture.structured.first().conditions)
        }
    }

    private fun fixture(
        createdAt: List<Instant>,
        texts: List<String> = listOf(CANONICAL, CANONICAL),
    ): Fixture = Fixture(createdAt, texts)

    private class Fixture(
        createdAt: List<Instant>,
        texts: List<String>,
    ) {
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.of("abcdefghijklmnopqrstuv"),
                "synthetic context test",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(DATE, DATE.plusDays(5)),
                ClosurePolicy.of(expectedParticipants = 2),
                CREATED,
            )
        val submissions =
            createdAt.mapIndexed { index, instant ->
                Submission.start(
                    SubmissionId(UUID.randomUUID()),
                    room.id,
                    ParticipantId(UUID.randomUUID()),
                    SubmissionVersionId(UUID.randomUUID()),
                    texts[index],
                    emptyList(),
                    Locale.KOREAN,
                    instant,
                )
            }
        val batch = SubmissionBatch(SubmissionBatchId(UUID.randomUUID()), room.id, submissions.map { it.latest.id }, WORKER_NOW)
        var run = CoordinationRun.queued(CoordinationRunId(UUID.randomUUID()), batch)
        var structured = emptyList<StructuredSubmissionResult>()
        private val runRepository = mock(CoordinationRunRepository::class.java)
        private val submissionsRepository = mock(SubmissionRepository::class.java)
        private val attempts = mock(CoordinationAttemptRepository::class.java)
        private val processingPersistence = mock(GeminiProcessingPersistenceService::class.java)
        private val rooms = mock(MeetingRoomRepository::class.java)
        private val normalized = mock(NormalizedPlaceRepository::class.java)
        private val structuredRepository =
            object : StructuredSubmissionRepository {
                override fun findByBatch(batchId: SubmissionBatchId): List<StructuredSubmissionResult> = structured

                override fun replaceForBatch(
                    batchId: SubmissionBatchId,
                    results: List<StructuredSubmissionResult>,
                    processedAt: Instant,
                ) {
                    structured = results
                }
            }
        private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

        init {
            `when`(runRepository.findByBatchId(batch.id)).thenAnswer { run }
            `when`(submissionsRepository.findLatestByRoom(room.id)).thenReturn(submissions)
            `when`(processingPersistence.start(anyValue())).thenAnswer { run.startStructuring().also { run = it } }
            `when`(processingPersistence.room(room.id)).thenReturn(room)
            `when`(rooms.findById(room.id)).thenReturn(room)
            `when`(attempts.countByRun(run.id)).thenReturn(0)
            Mockito
                .doAnswer {
                    structured = it.getArgument(1)
                    run = (it.getArgument(0) as CoordinationRun).finishStructuring()
                    null
                }.`when`(processingPersistence)
                .complete(anyValue(), anyValue(), anyValue())
            Mockito
                .doAnswer {
                    run = it.getArgument(0)
                    null
                }.`when`(runRepository)
                .update(anyValue())
        }

        fun parse(
            request: NaturalLanguageBatchRequest,
            reason: String = "AMBIGUOUS_TIME_CONSTRAINT",
        ): NaturalLanguageBatchResult {
            val results =
                request.inputs.joinToString(",") {
                    """{"input_ref":"${it.inputRef}","conditions":[],"rejection_code":"$reason"}"""
                }
            val response = """{"schema_version":"2","results":[$results]}"""
            return NaturalLanguageBatchResult(adapter.parseProviderResponse(response, request), ParserUsage(null, null, response.length))
        }

        fun processor(parser: NaturalLanguageParserPort): GeminiBatchProcessor =
            GeminiBatchProcessor(
                runRepository,
                submissionsRepository,
                structuredRepository,
                attempts,
                parser,
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort {},
                JitterPort { 0 },
                MonotonicTimePort { 0 },
                Clock.fixed(WORKER_NOW, ZoneOffset.UTC),
                processingPersistence,
                MatchingProcessor(
                    runRepository,
                    submissionsRepository,
                    structuredRepository,
                    normalized,
                    IdGenerator { UUID.randomUUID() },
                    MatchingProcessingPersistenceService(rooms, runRepository, normalized),
                ),
            )
    }

    companion object {
        private val DATE: LocalDate = LocalDate.of(2026, 10, 7)
        private val CREATED: Instant = Instant.parse("2026-10-07T00:00:00Z")
        private val WORKER_NOW: Instant = Instant.parse("2026-10-14T00:00:00Z")
        private const val CANONICAL = "평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요."

        private fun utc(
            offset: Long,
            start: Int,
            end: Int,
        ) = InstantTimeRange(
            DATE.plusDays(offset).atTime(start, 0).toInstant(ZoneOffset.ofHours(9)),
            DATE.plusDays(offset).atTime(end, 0).toInstant(ZoneOffset.ofHours(9)),
        )

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
