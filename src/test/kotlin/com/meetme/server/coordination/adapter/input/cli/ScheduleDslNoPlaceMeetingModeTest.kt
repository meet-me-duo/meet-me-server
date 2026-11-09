package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Meeting category is not a mandatory place constraint; all text and manually assigned times are synthetic. */
class ScheduleDslNoPlaceMeetingModeTest {
    private val q = ScheduleDslQuestionContextFixture
    private val w = ScheduleDslWireFixture
    private val window = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))

    @ParameterizedTest
    @ValueSource(strings = ["REMOTE", "IN_PERSON", "EITHER"])
    fun `NPM01 time-only inputs recommend their actual meeting modes without invented place or unverified-place flags`(name: String) {
        val mode = MeetingMode.valueOf(name)
        val input = q.input1().copy(meetingMode = mode)
        val result = ScheduleDslOfflinePipeline.evaluate(input, w.json())
        val contextResult =
            ScheduleDslOfflinePipeline.evaluateContext(
                input.copy(responseContractVersion = SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT),
                w.json(),
            )
        assertEquals(result, contextResult)
        assertEquals(IrCalculationStatus.CALCULATED, result.status)
        assertNoPlaceResult(result, input, setOf(window))
        val expectedModes = if (mode == MeetingMode.EITHER) setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE) else setOf(mode)
        assertEquals(
            expectedModes,
            result.candidates.candidates
                .map { it.meetingMode }
                .toSet(),
        )
        assertEquals(
            expectedModes,
            result.recommendations.options
                .flatMap { it.variants }
                .map { it.meetingMode }
                .toSet(),
        )
    }

    @Test
    fun `NPM02 four synthetic in-person participants with no place statements retain full attendance time recommendation`() {
        val initial = q.input1()
        val additions =
            (0..1).map { index ->
                ScheduleDslOfflineSubmission(
                    UUID(0, 610L + index),
                    UUID(0, 620L + index),
                    UUID(0, 630L + index),
                    7,
                    q.ir.SECOND_TEXT,
                    "ko-KR",
                    q.ir.reference.plusSeconds(300),
                    InputEvidence(3, q.ir.reference),
                )
            }
        val input = initial.copy(meetingMode = MeetingMode.IN_PERSON, inputs = initial.inputs + additions)
        val document = w.document()

        @Suppress("UNCHECKED_CAST")
        val results = document.getValue("results") as MutableList<MutableMap<String, Any?>>
        for (extra in additions) {
            val claim = q.ir.precise(firstSource = false)
            val program = w.f.program(listOf(w.f.ref("peer", "clear").copy(evidence = listOf(q.ir.evidence(false)))), "peer")
            results +=
                linkedMapOf<String, Any?>(
                    "input_ref" to extra.submissionVersionId.toString(),
                    "conditions" to listOf(q.ir.wire(claim)),
                    "program" to w.program(program),
                )
        }
        val result = ScheduleDslOfflinePipeline.evaluate(input, w.encode(document))
        assertEquals(IrCalculationStatus.CALCULATED, result.status)
        val ids = input.inputs.map { ParticipantId(it.participantId) }.toSet()
        val fullOption = result.recommendations.options.first()
        assertEquals(window, fullOption.window)
        assertTrue(fullOption.variants.any { it.participantIds.toSet() == ids && it.meetingMode == MeetingMode.IN_PERSON })
        assertEquals(window, result.recommendations.options[result.recommendations.primaryOptionIndexes.first()].window)
        assertTrue(result.candidates.candidates.isNotEmpty())
        for (candidate in result.candidates.candidates) {
            assertEquals(MeetingMode.IN_PERSON, candidate.meetingMode)
            assertEquals(ids, candidate.participantIds.toSet())
            assertEquals(listOf(window), candidate.timeRanges)
            assertEquals(null, candidate.representativeArea)
            assertEquals(null, candidate.representativePlace)
            assertTrue(candidate.unverifiedPlaceParticipantIds.isEmpty())
        }
        for (participant in result.prepared.participants) {
            assertEquals(OfflinePlaceAssessment.UNRESTRICTED, participant.offlinePlaceAssessment)
            assertEquals(null, participant.offlineArea)
            assertEquals(null, participant.offlineRegion)
        }
        for (variant in result.recommendations.options.flatMap { it.variants }) {
            assertEquals(MeetingMode.IN_PERSON, variant.meetingMode)
            assertTrue(ids.containsAll(variant.participantIds) && variant.participantIds.size >= 2)
            assertEquals(null, variant.representativeArea)
            assertEquals(null, variant.representativePlace)
            assertTrue(variant.unverifiedPlaceParticipantIds.isEmpty())
        }
        assertEquals(DeterministicCandidateMatcher.generate(input.meetingMode, result.prepared.participants), result.candidates)
        assertEquals(
            RecommendationProjector.generate(input.meetingMode, result.prepared.participants, ZoneId.of(input.timeZone)),
            result.recommendations,
        )
    }

    @Test
    fun `NPM03 enabling no-place in-person matching cannot promote nonprecise AVAILABLE to a numeric interval`() {
        val document = w.document()
        val claim = q.ir.condition("nonprecise")
        w.firstResult(document)["conditions"] = listOf(q.ir.wire(claim))
        w.firstResult(document)["program"] = w.program(w.f.program(listOf(w.f.ref("root", claim.id)), "root"))
        val result = ScheduleDslOfflinePipeline.evaluate(q.input1().copy(meetingMode = MeetingMode.IN_PERSON), w.encode(document))
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertTrue(result.candidates.candidates.isEmpty())
        assertTrue(result.recommendations.options.isEmpty())
        val kept =
            result.envelope.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, kept.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, kept.temporalResolution)
    }

    @Test
    fun `NPM04 missing place does not weaken conservative protection of an untrusted unavailable scope`() {
        val document = w.document()
        val base = w.f.claim("base", 18, 22)
        val negative = w.f.claim("negative", 19, 20, IrEffect.UNAVAILABLE).copy(scope = IrScope(IrDimension.TIME))
        w.firstResult(document)["conditions"] = listOf(q.ir.wire(base), q.ir.wire(negative))
        w.firstResult(document)["program"] =
            w.program(
                w.f.program(listOf(w.f.ref("base-ref", base.id), w.f.ref("negative-ref", negative.id)), "base-ref", block = "negative-ref"),
            )
        val result = ScheduleDslOfflinePipeline.evaluate(q.input1().copy(meetingMode = MeetingMode.EITHER), w.encode(document))
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertTrue(result.candidates.candidates.isEmpty())
        assertTrue(result.recommendations.options.isEmpty())
        assertEquals(
            negative.id,
            result.envelope.claims.results
                .first()
                .quarantined
                .single()
                .conditionId,
        )
        assertTrue(
            result.envelope.claims.results
                .first()
                .conditions
                .none { it.id == negative.id },
        )
    }

    private fun assertNoPlaceResult(
        result: ScheduleDslOfflineResult,
        input: ScheduleDslOfflineInput,
        windows: Set<InstantTimeRange>,
    ) {
        assertEquals(
            windows,
            result.recommendations.options
                .map { it.window }
                .toSet(),
        )
        assertTrue(result.candidates.candidates.isNotEmpty())
        for (participant in result.prepared.participants) {
            assertEquals(null, participant.offlineArea)
            assertEquals(null, participant.offlineRegion)
            assertEquals(OfflinePlaceAssessment.UNRESTRICTED, participant.offlinePlaceAssessment)
        }
        for (candidate in result.candidates.candidates) {
            assertEquals(null, candidate.representativeArea)
            assertEquals(null, candidate.representativePlace)
            assertTrue(candidate.unverifiedPlaceParticipantIds.isEmpty())
        }
        for (variant in result.recommendations.options.flatMap { it.variants }) {
            assertEquals(null, variant.representativeArea)
            assertEquals(null, variant.representativePlace)
            assertTrue(variant.unverifiedPlaceParticipantIds.isEmpty())
        }
        assertEquals(DeterministicCandidateMatcher.generate(input.meetingMode, result.prepared.participants), result.candidates)
        assertEquals(
            RecommendationProjector.generate(input.meetingMode, result.prepared.participants, ZoneId.of(input.timeZone)),
            result.recommendations,
        )
    }
}
