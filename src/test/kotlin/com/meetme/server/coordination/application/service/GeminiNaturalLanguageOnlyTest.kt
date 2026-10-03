package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.submission.domain.StructuredSubmissionResult
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiNaturalLanguageOnlyTest {
    @Test
    fun `mixed archived batch sends only natural input and persists legacy unsupported reason`() {
        val f = NaturalLanguageOnlyFixture(listOf("natural one", "natural two", null))
        f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)))
        val naturalResults = f.structured
        val parser = mock(NaturalLanguageParserPort::class.java)
        var captured: NaturalLanguageBatchRequest? = null
        `when`(parser.parse(NaturalLanguageOnlyFixture.anyValue())).thenAnswer {
            captured = it.getArgument(0)
            NaturalLanguageBatchResult(naturalResults, ParserUsage(10, 10, 100))
        }

        processor(f, parser).process(f.batch.id)

        val request = requireNotNull(captured)
        assertEquals(listOf("natural one", "natural two"), request.inputs.map { it.rawText })
        assertEquals(
            f.submissions.take(2).map {
                it.latest.id.value
                    .toString()
            },
            request.inputs.map { it.inputRef },
        )
        assertFalse(request.toString().contains("manualAvailability"))
        assertEquals(f.room.searchRange.startInclusive, request.searchStartDate)
        assertEquals(f.room.searchRange.endExclusive, request.searchEndDate)
        val legacyResults =
            f.structured.filter {
                it.submissionVersionId ==
                    f.submissions
                        .last()
                        .latest.id
            }
        assertEquals(1, legacyResults.size, "Frozen legacy input must have an explicit unsupported result")
        val legacy = legacyResults.single()
        assertEquals("LEGACY_MANUAL_ONLY_UNSUPPORTED", legacy.rejectionCode)
        assertTrue(legacy.conditions.isEmpty())
        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertEquals(f.batch, f.run.batch)
    }

    @Test
    fun `actual provider failure delays same frozen batch without candidates or fabricated legacy success`() {
        val f = NaturalLanguageOnlyFixture(listOf("natural", null))
        val parser = mock(NaturalLanguageParserPort::class.java)
        `when`(parser.parse(NaturalLanguageOnlyFixture.anyValue()))
            .thenThrow(NaturalLanguageParserException(ParserFailureKind.NETWORK))

        val processor = processor(f, parser)
        processor.process(f.batch.id)

        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, f.run.status)
        assertEquals(f.batch, f.run.batch)
        assertTrue(f.run.candidates.isEmpty())
        verify(parser, times(4)).parse(NaturalLanguageOnlyFixture.anyValue())
        f.run = f.run.retryAnalysis()
        processor.process(f.batch.id)
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, f.run.status)
        assertEquals(f.batch, f.run.batch)
        assertEquals(listOf("natural", null), f.submissions.map { it.latest.rawText })
        verify(parser, times(8)).parse(NaturalLanguageOnlyFixture.anyValue())
    }

    @Test
    fun `successful response with all invalid natural conditions yields neutral partial rather than analysis delayed`() {
        val f = NaturalLanguageOnlyFixture(listOf("invalid", "valid"))
        val parser = mock(NaturalLanguageParserPort::class.java)
        `when`(parser.parse(NaturalLanguageOnlyFixture.anyValue())).thenReturn(
            NaturalLanguageBatchResult(
                listOf(
                    StructuredSubmissionResult(f.submissions[0].latest.id, emptyList(), "INVALID_CONDITION"),
                    StructuredSubmissionResult(f.submissions[1].latest.id, listOf(f.window(10, 13)), null),
                ),
                ParserUsage(10, 10, 100),
            ),
        )

        processor(f, parser).process(f.batch.id)

        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertEquals(
            listOf(f.utc(10, 13)),
            f.singleCandidate().timeRanges,
        )
        verify(parser).parse(NaturalLanguageOnlyFixture.anyValue())
    }

    private fun processor(
        f: NaturalLanguageOnlyFixture,
        parser: NaturalLanguageParserPort,
    ): GeminiBatchProcessor {
        f.run = CoordinationRun.queued(f.run.id, f.batch)
        val attempts = mock(CoordinationAttemptRepository::class.java)
        val clock = Clock.fixed(NaturalLanguageOnlyFixture.NOW, ZoneOffset.UTC)
        val matching =
            MatchingProcessor(
                f.runRepository,
                f.submissionRepository,
                f.structuredRepository,
                f.normalizedRepository,
                IdGenerator { UUID.randomUUID() },
                MatchingProcessingPersistenceService(f.roomRepository, f.runRepository, f.normalizedRepository),
            )
        return GeminiBatchProcessor(
            f.runRepository,
            f.submissionRepository,
            f.structuredRepository,
            attempts,
            parser,
            IdGenerator { UUID.randomUUID() },
            RetryDelayPort {},
            JitterPort { 0 },
            MonotonicTimePort { 0 },
            clock,
            GeminiProcessingPersistenceService(f.roomRepository, f.runRepository, f.structuredRepository, attempts, clock),
            matching,
        )
    }
}
