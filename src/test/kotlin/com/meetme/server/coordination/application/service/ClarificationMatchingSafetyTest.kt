package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #107, FR-008B/008E: rejected ambiguity never acquires invented availability or attendance. */
class ClarificationMatchingSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `one valid participant and one unresolved ambiguity cannot form a meeting`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("7~9시 가능", "오전 10~13시 가능"))
        fixture.results(emptyList(), listOf(fixture.window(10, 13)))
        rejectFirst(fixture, reason)

        fixture.process()

        assertEquals(CoordinationStatus.COMPLETED, fixture.run.status)
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertTrue(fixture.run.candidates.isEmpty(), "Ambiguity must have empty availability rather than the full search range")
        val view = fixture.resultService().getCandidates(fixture.room.inviteCode.value, "host", java.util.Locale.KOREAN)
        assertEquals(2, view.totalSubmissions)
        assertEquals(1, view.appliedSubmissions)
        assertEquals(1, view.unappliedInputs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `two valid participants form only their precise partial intersection and keep rejected person in total`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("7~9시 불가", "오전 9~13시 가능", "오전 10~14시 가능"))
        fixture.results(emptyList(), listOf(fixture.window(9, 13)), listOf(fixture.window(10, 14)))
        rejectFirst(fixture, reason)

        fixture.process()

        val candidate = fixture.singleCandidate()
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertEquals(PlanType.C, candidate.planType)
        assertEquals(3, candidate.totalParticipants)
        assertEquals(
            fixture.submissions
                .drop(1)
                .map { it.participantId }
                .toSet(),
            candidate.participantIds.toSet(),
        )
        assertEquals(listOf(fixture.utc(10, 13)), candidate.timeRanges)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `all unresolved ambiguities complete partial without widening into all day`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("7~9시 가능", "비슷한 시간 가능"))
        fixture.results(emptyList(), emptyList())
        fixture.structured = fixture.structured.map { it.copy(rejectionCode = reason) }

        fixture.process()

        assertEquals(CoordinationStatus.COMPLETED, fixture.run.status)
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertTrue(fixture.run.candidates.isEmpty())
        val view = fixture.resultService().getCandidates(fixture.room.inviteCode.value, "host", java.util.Locale.KOREAN)
        assertEquals(2, view.totalSubmissions)
        assertEquals(0, view.appliedSubmissions)
        assertEquals(2, view.unappliedInputs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `retained hard available unavailable and preferred fragments cannot override unresolved rejection`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("미해소 시간과 선호", "오전 10~14시 가능", "오전 10~14시 가능"))
        val preferred =
            StructuredCondition.PreferredTimeWindow(
                NaturalLanguageOnlyFixture.DATE,
                null,
                LocalTime.of(11, 0),
                LocalTime.of(12, 0),
            )
        fixture.results(
            listOf(fixture.window(9, 17), fixture.window(10, 11, TimePolarity.UNAVAILABLE), preferred),
            listOf(fixture.window(10, 14)),
            listOf(fixture.window(10, 14)),
        )
        rejectFirst(fixture, reason)

        fixture.process()

        val candidate = fixture.singleCandidate()
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertEquals(3, candidate.totalParticipants)
        assertEquals(
            fixture.submissions
                .drop(1)
                .map { it.participantId }
                .toSet(),
            candidate.participantIds.toSet(),
        )
        assertEquals(listOf(fixture.utc(10, 14)), candidate.timeRanges)
        assertEquals(
            3,
            fixture.structured
                .first()
                .conditions.size,
            "Safety exclusion must preserve stored evidence",
        )
        assertEquals(reason, fixture.structured.first().rejectionCode)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `preferred only ambiguity cannot become neutral all day availability`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("7~9시가 좋아요", "오전 10~14시 가능"))
        fixture.results(
            listOf(
                StructuredCondition.PreferredTimeWindow(
                    NaturalLanguageOnlyFixture.DATE,
                    null,
                    LocalTime.of(10, 0),
                    LocalTime.of(14, 0),
                ),
            ),
            listOf(fixture.window(10, 14)),
        )
        rejectFirst(fixture, reason)

        fixture.process()

        assertTrue(fixture.run.candidates.isEmpty())
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT"])
    fun `recommendation projection excludes unresolved person and cannot award their preference point`(reason: String) {
        val fixture = NaturalLanguageOnlyFixture(listOf("7~9시 선호", "오전 10~14시 가능", "오전 10~14시 가능"))
        fixture.results(
            listOf(
                fixture.window(10, 14),
                StructuredCondition.PreferredTimeWindow(
                    NaturalLanguageOnlyFixture.DATE,
                    null,
                    LocalTime.of(11, 0),
                    LocalTime.of(12, 0),
                ),
            ),
            listOf(fixture.window(10, 14)),
            listOf(fixture.window(10, 14)),
        )
        rejectFirst(fixture, reason)
        val recommendations = mock(RecommendationRepository::class.java)
        val published = mutableListOf<RecommendationAnalysis>()
        doAnswer {
            published += it.getArgument<RecommendationAnalysis>(0)
            null
        }.`when`(recommendations).publish(NaturalLanguageOnlyFixture.anyValue())
        val persistence =
            MatchingProcessingPersistenceService(
                fixture.roomRepository,
                fixture.runRepository,
                fixture.normalizedRepository,
                recommendations,
            )
        val processor =
            MatchingProcessor(
                fixture.runRepository,
                fixture.submissionRepository,
                fixture.structuredRepository,
                fixture.normalizedRepository,
                IdGenerator { UUID.randomUUID() },
                persistence,
                recommendationRepository = recommendations,
            )

        processor.process(fixture.batch.id)

        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        val analysis = published.single()
        assertTrue(analysis.options.isNotEmpty())
        val expectedParticipants =
            fixture.submissions
                .drop(1)
                .map { it.participantId }
                .toSet()
        analysis.options.forEach { stored ->
            assertTrue(stored.option.window.startInclusive >= fixture.utc(10, 14).startInclusive)
            assertTrue(stored.option.window.endExclusive <= fixture.utc(10, 14).endExclusive)
            stored.option.variants.forEach {
                assertEquals(expectedParticipants, it.participantIds.toSet())
                assertEquals(0, it.preferenceCount, "Unresolved preference cannot score or create attendance")
            }
        }
        assertTrue(analysis.options.any { it.option.window == fixture.utc(10, 14) }, "Valid broad alternative must survive")
    }

    private fun rejectFirst(
        fixture: NaturalLanguageOnlyFixture,
        reason: String,
    ) {
        fixture.structured =
            fixture.structured.mapIndexed { index, result -> if (index == 0) result.copy(rejectionCode = reason) else result }
    }
}
