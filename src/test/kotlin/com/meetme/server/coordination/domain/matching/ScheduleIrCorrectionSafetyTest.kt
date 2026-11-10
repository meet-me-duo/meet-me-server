package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrResult
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent correction-safety regressions; the affected target remains uncertain, not reinstated as fact. */
class ScheduleIrCorrectionSafetyTest {
    private val a = UUID(0, 2801)
    private val b = UUID(0, 2802)
    private val pa = ParticipantId(UUID(0, 2901))
    private val pb = ParticipantId(UUID(0, 2902))
    private val broad = scope("2027-01-08T18:00:00", "2027-01-08T22:00:00")
    private val affected = scope("2027-01-08T19:00:00", "2027-01-08T20:00:00")
    private val corrected = scope("2027-01-08T20:00:00", "2027-01-08T21:00:00")
    private val raw = "합성 원문. 가능 시간과 예외, 정정 및 두 장소 해석을 별도로 보존한다."

    @Test
    fun `C01 missing place branch protects its time correction target without erasing independent times`() {
        val base = condition("base", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, broad)
        val target = condition("target-time", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, affected)
        val placeA =
            condition("place-a", IrEffect.UNRESOLVED, IrInterpretation.UNRESOLVED, IrScope(IrDimension.PLACE)).copy(
                alternativeGroup = "location",
                alternative = "A",
                supersedes = listOf(target.id),
            )
        val placeB =
            condition("place-b", IrEffect.UNRESOLVED, IrInterpretation.UNRESOLVED, IrScope(IrDimension.PLACE)).copy(
                alternativeGroup = "location",
                alternative = "B",
            )
        val input = envelope(listOf(base, target, placeA, placeB))
        val policy = IrMatchingPolicy("correction-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertIndependentIntervalsAndPeer(prepared)
        val evidence = prepared.evidence.getValue(pa)
        assertTrue(evidence.unresolvedTime)
        assertEquals(setOf(IrDimension.PLACE), evidence.unsupportedDimensions)
        assertEquals(0, evidence.quarantineCount)
        assertEquals(affected, evidence.conditions.single { it.conditionId == target.id }.scope)
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == placeA.id }.disposition)
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == placeB.id }.disposition)
        assertEquals(
            listOf(target.id),
            input.results
                .first()
                .conditions
                .single { it.id == placeA.id }
                .supersedes,
        )
    }

    @Test
    fun `C02 withheld positive correction cannot reopen a superseded negative target through independent availability`() {
        val base = condition("base", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, broad)
        val oldNegative = condition("old-negative", IrEffect.UNAVAILABLE, IrInterpretation.EXPLICIT, affected)
        val correction =
            condition("correction", IrEffect.AVAILABLE, IrInterpretation.INFERRED, corrected).copy(
                supersedes = listOf(oldNegative.id),
            )
        val input = envelope(listOf(base, oldNegative, correction))
        val policy = IrMatchingPolicy("correction-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertIndependentIntervalsAndPeer(prepared)
        val evidence = prepared.evidence.getValue(pa)
        assertTrue(evidence.unresolvedTime)
        assertEquals(0, evidence.quarantineCount)
        // The old statement stays superseded. Its scope is protected as uncertainty from the withheld correction.
        assertEquals(IrConditionDisposition.SUPERSEDED, evidence.conditions.single { it.conditionId == oldNegative.id }.disposition)
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == correction.id }.disposition)
        assertEquals(IrInterpretation.INFERRED, evidence.conditions.single { it.conditionId == correction.id }.interpretation)
        assertEquals(
            listOf(oldNegative.id),
            input.results
                .first()
                .conditions
                .single { it.id == correction.id }
                .supersedes,
        )
    }

    private fun assertIndependentIntervalsAndPeer(prepared: IrPreparedMatchingInputs) {
        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
        assertEquals(
            listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z"), instant("2027-01-08T11:00:00Z", "2027-01-08T13:00:00Z")),
            prepared.participants.single { it.participantId == pa }.availableTimes,
        )
        assertEquals(
            listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T13:00:00Z")),
            prepared.participants.single { it.participantId == pb }.availableTimes,
        )
    }

    private fun condition(
        id: String,
        effect: IrEffect,
        label: IrInterpretation,
        scope: IrScope?,
    ): IrCondition =
        IrCondition(
            id,
            effect,
            label,
            scope,
            listOf(IrEvidenceSpan("original", 3, 0, raw.codePointCount(0, raw.length), hash(raw))),
            IrAssertion.ASSERTED,
            emptyList(),
            null,
            null,
        )

    private fun envelope(left: List<IrCondition>): ScheduleIrEnvelope {
        val source =
            IrSourceBinding(
                "original",
                IrSourceKind.ORIGINAL,
                3,
                raw.codePointCount(0, raw.length),
                hash(raw),
                Instant.parse("2027-01-04T00:00:00Z"),
            )
        val context =
            ScheduleIrContext(
                UUID(0, 2811),
                UUID(0, 2812),
                UUID(0, 2813),
                "Asia/Seoul",
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                "correction-synthetic-1",
                listOf(
                    IrInputBinding(a, pa.value, 7, 3, hash("correction-input-a"), listOf(source)),
                    IrInputBinding(b, pb.value, 7, 3, hash("correction-input-b"), listOf(source)),
                ),
            )
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(a, IrAvailabilityState.KNOWN_WINDOWS, left, emptyList()),
                IrResult(
                    b,
                    IrAvailabilityState.KNOWN_WINDOWS,
                    listOf(condition("peer", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, broad)),
                    emptyList(),
                ),
            ),
        )
    }

    private fun scope(
        start: String,
        end: String,
    ): IrScope = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private fun instant(
        start: String,
        end: String,
    ): InstantTimeRange = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
