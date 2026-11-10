package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typed numeric authority, independent of language analysis and graph evaluation. */
class WeeklyCorrectionWindowAuthorityTest {
    @Test
    fun `WCWA01 an ineligible correction preserves every known weekly negative without blocking the whole search`() {
        val f = ScheduleIrV2SyntheticFixture
        val context =
            f.snapshot().context.copy(
                searchStart = LocalDate.parse("2064-04-01"),
                searchEnd = LocalDate.parse("2064-04-16"),
            )
        val zone = ZoneId.of(context.timeZone)
        val full = f.scope("2064-04-01T00:00", "2064-04-16T00:00")
        val base = claim("base", IrEffect.AVAILABLE, full)
        val weeklyNegative = claim("weekly-ban", IrEffect.UNAVAILABLE, null)
        val correction =
            claim(
                "estimated-withdrawal",
                IrEffect.AVAILABLE,
                f.scope("2064-04-04T09:00", "2064-04-04T10:00"),
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf("weekly-ban"),
            )
        val supports =
            listOf(
                window("2064-04-04T09:00", "2064-04-04T10:00", zone),
                window("2064-04-11T09:00", "2064-04-11T10:00", zone),
            )
        val first =
            IrNumericProjectionInput(
                f.first,
                listOf(base, weeklyNegative, correction),
                emptyList(),
                setOf("weekly-ban"),
                availabilityUnknown = false,
                separatePreferenceInfluence = true,
                knownWindows = mapOf("weekly-ban" to supports),
            )
        val peer =
            IrNumericProjectionInput(
                f.second,
                listOf(claim("peer", IrEffect.AVAILABLE, full, firstSource = false)),
                emptyList(),
                emptySet(),
                availabilityUnknown = false,
                separatePreferenceInfluence = true,
            )
        val policy = IrMatchingPolicy(context.policyVersion, setOf(IrInterpretation.EXPLICIT), emptyList(), true)
        val actual = ScheduleIrMatchInputPolicy.projectNumeric(context, listOf(first, peer), policy)
        val participant = ParticipantId(f.firstParticipant)
        assertEquals(
            listOf(
                window("2064-04-01T00:00", "2064-04-04T09:00", zone),
                window("2064-04-04T10:00", "2064-04-11T09:00", zone),
                window("2064-04-11T10:00", "2064-04-16T00:00", zone),
            ),
            actual.participants.single { it.participantId == participant }.availableTimes,
        )
        val evidence = actual.evidence.getValue(participant)
        assertTrue(evidence.unresolvedTime)
        assertEquals(IrConditionDisposition.SUPERSEDED, evidence.conditions.single { it.conditionId == "weekly-ban" }.disposition)
        assertEquals(
            IrConditionDisposition.WITHHELD,
            evidence.conditions.single { it.conditionId == "estimated-withdrawal" }.disposition,
        )
        assertEquals(supports, evidence.correctionInfluences.map { it.window })
        assertTrue(evidence.correctionInfluences.all { it.targetConditionId == "weekly-ban" })
        assertEquals(
            listOf(window("2064-04-01T00:00", "2064-04-16T00:00", zone)),
            actual.participants.single { it.participantId == ParticipantId(f.secondParticipant) }.availableTimes,
        )
        assertNull(weeklyNegative.scope)
        assertEquals(setOf("weekly-ban"), first.nonPreciseConditionIds)
    }

    private fun claim(
        id: String,
        effect: IrEffect,
        scope: IrScope?,
        interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        supersedes: List<String> = emptyList(),
        firstSource: Boolean = true,
    ) = IrCondition(
        id,
        effect,
        interpretation,
        scope,
        ScheduleIrV2SyntheticFixture.verified(ScheduleIrV2SyntheticFixture.precise(firstSource = firstSource)).evidence,
        IrAssertion.ASSERTED,
        supersedes,
        null,
        null,
    )

    private fun window(
        start: String,
        end: String,
        zone: ZoneId,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(zone).toInstant(),
        LocalDateTime.parse(end).atZone(zone).toInstant(),
    )
}
