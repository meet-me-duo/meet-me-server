package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextModeResponseParser
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Confirmed date-only policy; the selected date is synthetic and supplied explicitly, not an NLP-accuracy oracle. */
class ScheduleDslContextModeDateOnlyTest {
    @Test
    fun `CMDATE01 a selected date without time restrictions is that complete Saturday and never the entire search period`() {
        val phrase = "마지막주 토욜"
        val reference = Instant.parse("2027-01-25T00:00:00Z")
        val saturday = LocalDate.parse("2027-01-30")
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val input =
            F.input(phrase).let { base ->
                base.copy(
                    batchFixedAt = reference.plusSeconds(3600),
                    searchStart = LocalDate.parse("2027-01-25"),
                    searchEnd = LocalDate.parse("2027-02-01"),
                    policy = N.policy,
                    meetingMode = MeetingMode.REMOTE,
                    inputs =
                        base.inputs.mapIndexed { index, item ->
                            item.copy(
                                rawText = if (index == 0) phrase else "2027-01-25 00:00부터 2027-02-01 00:00 전까지 가능.",
                                acceptedAt = reference.plusSeconds(300),
                                evidence = InputEvidence(3, reference),
                            )
                        },
                )
            }
        val dayScope = F.scope("2027-01-30T00:00", "2027-01-31T00:00")
        val dateOnly =
            F.claim(input, "selected-saturday", scope = dayScope).copy(
                interpretation = IrInterpretation.INFERRED,
                temporalResolution = IrTemporalResolution.NON_PRECISE,
            )
        val proposal =
            F
                .proposal(
                    input,
                    claims = listOf(dateOnly),
                    program = F.program(listOf(F.ref(input, "date-ref", "selected-saturday")), "date-ref"),
                    terms = emptyList(),
                ).let { original ->
                    val peer = F.claim(input, "peer", scope = F.scope("2027-01-25T00:00", "2027-02-01T00:00"), index = 1)
                    original.copy(
                        base =
                            original.base.copy(
                                claims =
                                    original.base.claims.copy(
                                        results =
                                            listOf(
                                                original.base.claims.results
                                                    .first(),
                                                ProposedIrV2Result(F.ir.second, listOf(peer)),
                                            ),
                                    ),
                            ),
                    )
                }
        val document =
            F.document(proposal).apply {
                this["semantic_version"] = "schedule-ir-product-5"
                @Suppress("UNCHECKED_CAST")
                val results = this["results"] as MutableList<MutableMap<String, Any?>>
                results.forEach { it["mode_restrictions"] = emptyList<Any>() }
            }
        val snapshot = F.snapshot(input)
        val envelope =
            StrictScheduleDslContextModeResponseParser(JsonMapper.builder().findAndAddModules().build()).parse(
                F.json(document),
                snapshot,
                "schedule-dsl-response-4",
                "schedule-ir-product-5",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        val result = ScheduleDslContextModeCalculator.calculate(envelope, snapshot, input.policy, input.meetingMode)
        val expectedDay = InstantTimeRange(Instant.parse("2027-01-29T15:00:00Z"), Instant.parse("2027-01-30T15:00:00Z"))
        val expectedSearch = InstantTimeRange(Instant.parse("2027-01-24T15:00:00Z"), Instant.parse("2027-01-31T15:00:00Z"))
        val first = ParticipantId(F.ir.firstParticipant)
        val peer = ParticipantId(F.ir.secondParticipant)
        val prepared = result.preparedByMode.getValue(MeetingMode.REMOTE)
        assertEquals(listOf(expectedDay), prepared.participants.single { it.participantId == first }.availableTimes)
        assertEquals(listOf(expectedDay), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(listOf(expectedSearch), prepared.participants.single { it.participantId == peer }.availableTimes)
        val candidate = result.candidates.candidates.single()
        assertEquals(setOf(first, peer), candidate.participantIds.toSet())
        assertEquals(listOf(expectedDay), candidate.timeRanges)
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertEquals(null, candidate.representativePlace)
        assertEquals(listOf(expectedDay), result.recommendations.options.map { it.window })
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, result.precisionOutcome)

        val declared =
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single()
        val projected =
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, declared.effect)
        assertEquals(IrInterpretation.INFERRED, declared.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertEquals(dayScope, declared.scope)
        assertEquals(IrInterpretation.INFERRED, projected.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projected.temporalResolution)
        assertEquals(dayScope, projected.scope)
        assertEquals(declared.evidence, projected.evidence)
        val derivation = envelope.contextApplications.single()
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, derivation.derivation)
        assertEquals(dayScope, derivation.declaredScope)
        assertEquals(dayScope, derivation.resolvedScope)
        assertEquals(declared.evidence, derivation.evidence)
        assertTrue(envelope.base.termApplications.isEmpty())
        assertTrue(envelope.modeRestrictions.isEmpty())
        assertEquals(phrase, snapshot.sourceText(F.ir.first, "original"))
        assertEquals(phrase, dateOnly.evidence.single().quote)
        assertEquals(0, declared.evidence.single().startCodePoint)
        assertEquals(phrase.codePointCount(0, phrase.length), declared.evidence.single().endCodePoint)
    }
}
