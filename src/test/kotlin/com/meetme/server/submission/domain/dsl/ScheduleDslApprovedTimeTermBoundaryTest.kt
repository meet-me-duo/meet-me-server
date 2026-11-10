package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

/** Synthetic explicit refs; no raw-text term classifier or model interpretation oracle. */
class ScheduleDslApprovedTimeTermBoundaryTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val parser = StrictScheduleDslApprovedTermsResponseParser(mapper)
    private val validator = ScheduleDslApprovedTermsValidator()

    private fun parse(
        proposal: ScheduleDslApprovedTermsProposal,
        input: com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput,
    ) = parser.parse(
        F.json(F.document(proposal)),
        F.snapshot(input),
        "schedule-dsl-response-3",
        "schedule-ir-product-4",
        SCHEDULE_AST_VERSION,
        "ko-time-terms-1",
    )

    private fun times(envelope: ScheduleDslApprovedTermsEnvelope) =
        ScheduleDslInputPolicy
            .project(envelope.calculationProjection, F.policy)
            .participants
            .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
            .availableTimes

    private fun window(
        start: String,
        end: String,
        zone: String = "Asia/Seoul",
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of(zone)).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of(zone)).toInstant(),
    )

    @ParameterizedTest
    @ValueSource(strings = ["심야", "한밤중"])
    fun `ATB01 both approved late night aliases parse normalize and replay identically`(lexeme: String) {
        val input = F.input("🙂 2027-01-08 $lexeme 가능.")
        val proposal = F.proposal(input, terms = listOf(T.ref(input, ApprovedKoreanTimeTerm.LATE_NIGHT, lexeme)))
        val envelope = parse(proposal, input)
        assertEquals(listOf(window("2027-01-08T22:00", "2027-01-09T04:00")), times(envelope))
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            envelope.declaration.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertEquals(ApprovedKoreanTimeTerm.LATE_NIGHT, envelope.termApplications.single().term)
        assertEquals(envelope, validator.validateRestored(envelope, F.snapshot(input)))
    }

    @ParameterizedTest
    @CsvSource("2027-03-13,9", "2027-11-06,11")
    fun `ATB02 night follows local DST calendar rather than fixed elapsed duration`(
        date: String,
        hours: Long,
    ) {
        val day = LocalDate.parse(date)
        val input =
            F.input("🙂 $date 밤 가능.").copy(
                timeZone = "America/New_York",
                searchStart = day,
                searchEnd = day.plusDays(2),
            )
        val proposal = F.proposal(input, terms = listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤", day)))
        val envelope = parse(proposal, input)
        val expected = window("${day}T20:00", "${day.plusDays(1)}T06:00", "America/New_York")
        assertEquals(listOf(expected), times(envelope))
        assertEquals(Duration.ofHours(hours), Duration.between(expected.startInclusive, expected.endExclusive))
    }

    @Test
    fun `ATB03 explicit numbers override a valid term without intersecting or inventing a derivation witness`() {
        val input = F.input("🙂 2027-01-08 저녁이며 명시적으로 19:00부터 23:00까지 가능.")
        val scope = F.scope("2027-01-08T19:00", "2027-01-08T23:00")
        val claim = F.claim(input, scope = scope)
        val proposal = F.proposal(input, listOf(claim), terms = listOf(T.ref(input, ApprovedKoreanTimeTerm.EVENING, "저녁")))
        val envelope = parse(proposal, input)
        assertEquals(listOf(window("2027-01-08T19:00", "2027-01-08T23:00")), times(envelope))
        assertEquals(
            scope,
            envelope.declaration.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertEquals(
            scope,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertTrue(envelope.termApplications.isEmpty())
        assertEquals(envelope, validator.validateRestored(envelope, F.snapshot(input)))
    }

    @Test
    fun `ATB04 overnight intersection excludes both represented endpoints outside the explicit constraint`() {
        val input = F.input("🙂 2027-01-08 밤 가능. 별도 명시 23:00부터 다음날 02:00까지만 가능.")
        val narrow = F.claim(input, "narrow", scope = F.scope("2027-01-08T23:00", "2027-01-09T02:00"))
        val nodes =
            listOf(
                F.ref(input, "night", "half"),
                F.ref(input, "narrow-ref", "narrow"),
                F.node(input, "root", ScheduleDslOperator.ALL_OF, listOf("night", "narrow-ref")),
            )
        val proposal =
            F.proposal(
                input,
                listOf(F.claim(input), narrow),
                F.program(nodes, "root"),
                listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
            )
        assertEquals(listOf(window("2027-01-08T23:00", "2027-01-09T02:00")), times(parse(proposal, input)))
    }

    @Test
    fun `ATB05 unavailable night retains polarity and leaves the two independent precise shoulders`() {
        val input = F.input("🙂 2027-01-08 19:00부터 다음날 08:00까지 가능하지만 밤 불가.")
        val base = F.claim(input, "base", scope = F.scope("2027-01-08T19:00", "2027-01-09T08:00"))
        val block = F.claim(input, effect = IrEffect.UNAVAILABLE)
        val nodes =
            listOf(
                F.ref(input, "b", "base"),
                F.ref(input, "n", "half"),
                F.node(input, "root", ScheduleDslOperator.EXCEPT, listOf("b", "n")),
            )
        val envelope =
            parse(
                F.proposal(
                    input,
                    listOf(base, block),
                    F.program(nodes, "root"),
                    listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
                ),
                input,
            )
        assertEquals(
            listOf(
                window("2027-01-08T19:00", "2027-01-08T20:00"),
                window("2027-01-09T06:00", "2027-01-09T08:00"),
            ),
            times(envelope),
        )
        assertEquals(
            IrEffect.UNAVAILABLE,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "half" }
                .effect,
        )
    }

    @Test
    fun `ATB06 replacing late night witness with night cannot authenticate the old projection`() {
        val input = F.input("🙂 2027-01-08 심야 가능.")
        val envelope = parse(F.proposal(input, terms = listOf(T.ref(input, ApprovedKoreanTimeTerm.LATE_NIGHT, "심야"))), input)
        assertFailsWith<RuntimeException> {
            validator.validateRestored(
                envelope.copy(
                    termApplications =
                        envelope.termApplications.map {
                            it.copy(term = ApprovedKoreanTimeTerm.NIGHT)
                        },
                ),
                F.snapshot(input),
            )
        }
    }

    @Test
    fun `ATB07 night end is exclusive so an interval starting at next six has no overlap`() {
        val input = F.input("🙂 2027-01-08 밤 가능. 별도 명시 다음날 06:00부터 07:00까지만 가능.")
        val adjacent = F.claim(input, "adjacent", scope = F.scope("2027-01-09T06:00", "2027-01-09T07:00"))
        val nodes =
            listOf(
                F.ref(input, "n", "half"),
                F.ref(input, "a", "adjacent"),
                F.node(input, "root", ScheduleDslOperator.ALL_OF, listOf("n", "a")),
            )
        val envelope =
            parse(
                F.proposal(
                    input,
                    listOf(F.claim(input), adjacent),
                    F.program(nodes, "root"),
                    listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
                ),
                input,
            )
        assertTrue(times(envelope).isEmpty())
        assertEquals(1, envelope.termApplications.size)
    }
}
