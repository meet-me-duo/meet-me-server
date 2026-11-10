package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

class ScheduleDslApprovedTimeTermPreferenceTest {
    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    private fun participant(envelope: ScheduleDslApprovedTermsEnvelope) =
        ScheduleDslInputPolicy
            .projectWithPreferredAvailability(envelope.calculationProjection, F.policy)
            .participants
            .single { it.participantId == ParticipantId(F.ir.firstParticipant) }

    @Test
    fun `ATP01 preference only night yields new route candidates and rank but preserves old route behavior and effect`() {
        val input = F.input("🙂 2027-01-08 밤 선호.")
        val claim = F.claim(input, effect = IrEffect.PREFERRED)
        val program = F.program(listOf(F.ref(input, "p", "half")), null, prefer = "p")
        val envelope =
            ScheduleDslApprovedTermsValidator().validate(
                F.proposal(
                    input,
                    listOf(claim),
                    program,
                    listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
                ),
                F.snapshot(input),
            )
        val preferred = listOf(window("2027-01-08T20:00", "2027-01-09T06:00"))
        assertEquals(preferred, participant(envelope).availableTimes)
        assertEquals(emptyList(), participant(envelope).explicitPreferences.timeRanges)
        assertEquals(
            IrEffect.PREFERRED,
            envelope.declaration.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrEffect.PREFERRED,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertTrue(
            ScheduleDslInputPolicy
                .project(envelope.calculationProjection, F.policy)
                .participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
    }

    @Test
    fun `ATP02 explicit exclusion takes priority over newly usable night preference`() {
        val input = F.input("🙂 2027-01-08 밤 선호. 다만 23:00부터 다음날 01:00까지 불가.")
        val preferred = F.claim(input, effect = IrEffect.PREFERRED)
        val negative = F.claim(input, "block", IrEffect.UNAVAILABLE, F.scope("2027-01-08T23:00", "2027-01-09T01:00"))
        val program = F.program(listOf(F.ref(input, "p", "half"), F.ref(input, "b", "block")), null, block = "b", prefer = "p")
        val envelope =
            ScheduleDslApprovedTermsValidator().validate(
                F.proposal(
                    input,
                    listOf(preferred, negative),
                    program,
                    listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
                ),
                F.snapshot(input),
            )
        assertEquals(
            listOf(
                window("2027-01-08T20:00", "2027-01-08T23:00"),
                window("2027-01-09T01:00", "2027-01-09T06:00"),
            ),
            participant(envelope).availableTimes,
        )
        assertEquals(
            listOf(IrEffect.PREFERRED, IrEffect.UNAVAILABLE),
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .map { it.effect },
        )
    }

    @Test
    fun `ATP03 PREFER annotation remains clipped to its base instead of becoming independent preference availability`() {
        val input = F.input("🙂 2027-01-08 18:00부터 19:00까지 가능하며 저녁 선호.")
        val base = F.claim(input, "base", scope = F.scope("2027-01-08T18:00", "2027-01-08T19:00"))
        val preference = F.claim(input, effect = IrEffect.PREFERRED)
        val nodes =
            listOf(
                F.ref(input, "a", "base"),
                F.ref(input, "p", "half"),
                F.node(input, "root", ScheduleDslOperator.PREFER, listOf("a", "p")),
            )
        val envelope =
            ScheduleDslApprovedTermsValidator().validate(
                F.proposal(
                    input,
                    listOf(base, preference),
                    F.program(nodes, "root"),
                    listOf(T.ref(input, ApprovedKoreanTimeTerm.EVENING, "저녁")),
                ),
                F.snapshot(input),
            )
        val expected = listOf(window("2027-01-08T18:00", "2027-01-08T19:00"))
        assertEquals(expected, participant(envelope).availableTimes)
        assertEquals(expected, participant(envelope).explicitPreferences.timeRanges)
    }

    @Test
    fun `ATP04 nonprecise preference retains meaning without default availability or invented numeric credit`() {
        val input = F.input("🙂 2027-01-08 합성 비정밀 선호 진술.")
        val claim = F.claim(input, effect = IrEffect.PREFERRED)
        val program = F.program(listOf(F.ref(input, "p", "half")), null, prefer = "p")
        val envelope =
            ScheduleDslApprovedTermsValidator().validate(
                F.proposal(input, listOf(claim), program, terms = emptyList()),
                F.snapshot(input),
            )
        assertTrue(participant(envelope).availableTimes.isEmpty())
        assertTrue(participant(envelope).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            IrEffect.PREFERRED,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            com.meetme.server.submission.domain.ir.IrTemporalResolution.NON_PRECISE,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertTrue(envelope.termApplications.isEmpty())
    }
}
