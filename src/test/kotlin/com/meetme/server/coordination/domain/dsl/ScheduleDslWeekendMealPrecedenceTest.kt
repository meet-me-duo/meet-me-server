package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

/** Fresh synthetic native data; preserves represented date x meal bindings, never reconstructs omitted source meaning. */
class ScheduleDslWeekendMealPrecedenceTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED", "MIXED", "MIXED_WITH_BAN"])
    fun `APM01 represented weekend meals respect explicit availability and exclusions through the actual typed calculator`(mode: String) {
        val mixed = mode.startsWith("MIXED")
        val withBan = mode == "MIXED_WITH_BAN"
        val effect = if (mode == "AVAILABLE") IrEffect.AVAILABLE else IrEffect.PREFERRED
        val source =
            "🙂 합성 참여자: 2027-01-09와 2027-01-10 점심과 저녁 ${if (effect == IrEffect.PREFERRED) "선호" else "가능"}." +
                if (mixed) {
                    " 가능 시간은 2027-01-09 12:00부터 14:00까지, 2027-01-10 18:00부터 20:00까지." +
                        if (withBan) " 2027-01-09 11:00부터 15:00까지는 불가." else ""
                } else {
                    ""
                }
        val original = F.input(source)
        val input =
            original.copy(
                inputs =
                    original.inputs.mapIndexed { index, item ->
                        if (index == 1) item.copy(rawText = "2027-01-09 00:00부터 2027-01-11 00:00까지 가능.") else item
                    },
            )
        val dates = listOf(LocalDate.of(2027, 1, 9), LocalDate.of(2027, 1, 10))
        val terms =
            dates.flatMap { date ->
                listOf(
                    T.ref(input, ApprovedKoreanTimeTerm.LUNCH, "점심", date, "lunch-${date.dayOfMonth}"),
                    T.ref(input, ApprovedKoreanTimeTerm.EVENING, "저녁", date, "evening-${date.dayOfMonth}"),
                )
            }
        val literalClaims =
            if (mixed) {
                listOf(
                    F.claim(input, "sat-window", scope = F.scope("2027-01-09T12:00", "2027-01-09T14:00")),
                    F.claim(input, "sun-window", scope = F.scope("2027-01-10T18:00", "2027-01-10T20:00")),
                ) +
                    if (withBan) {
                        listOf(F.claim(input, "sat-ban", IrEffect.UNAVAILABLE, F.scope("2027-01-09T11:00", "2027-01-09T15:00")))
                    } else {
                        emptyList()
                    }
            } else {
                emptyList()
            }
        val claims = terms.map { F.claim(input, it.conditionId, effect) } + literalClaims
        val refs = terms.map { F.ref(input, "ref-${it.conditionId}", it.conditionId) }
        val root = F.node(input, "root", ScheduleDslOperator.ANY_OF, refs.map { it.id })
        val literalNodes =
            if (mixed) {
                listOf(
                    F.ref(input, "sat-ref", "sat-window"),
                    F.ref(input, "sun-ref", "sun-window"),
                    F.node(input, "explicit-root", ScheduleDslOperator.ANY_OF, listOf("sat-ref", "sun-ref")),
                ) + if (withBan) listOf(F.ref(input, "ban-ref", "sat-ban")) else emptyList()
            } else {
                emptyList()
            }
        val program =
            F.program(
                refs + root + literalNodes,
                if (mixed) {
                    "explicit-root"
                } else if (effect == IrEffect.AVAILABLE) {
                    "root"
                } else {
                    null
                },
                block = if (withBan) "ban-ref" else null,
                prefer = if (effect == IrEffect.PREFERRED) "root" else null,
            )
        val proposed = F.proposal(input, claims, program, terms)
        val peerClaim = F.claim(input, "peer", scope = F.scope("2027-01-09T00:00", "2027-01-11T00:00"), index = 1)
        val proposal =
            proposed.copy(
                base =
                    proposed.base.copy(
                        claims =
                            proposed.base.claims.copy(
                                results =
                                    proposed.base.claims.results.map { result ->
                                        if (result.submissionVersionId ==
                                            F.ir.second
                                        ) {
                                            result.copy(conditions = listOf(peerClaim))
                                        } else {
                                            result
                                        }
                                    },
                            ),
                    ),
            )
        val snapshot = F.snapshot(input)
        val envelope =
            StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
                F.json(F.document(proposal)),
                snapshot,
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        val calculation = ScheduleDslCalculator.calculateApprovedTerms(envelope, snapshot, F.policy, MeetingMode.REMOTE)
        val expected =
            when (mode) {
                "MIXED" ->
                    listOf(
                        window("2027-01-09T12:00", "2027-01-09T14:00"),
                        window("2027-01-10T18:00", "2027-01-10T20:00"),
                    )
                "MIXED_WITH_BAN" -> listOf(window("2027-01-10T18:00", "2027-01-10T20:00"))
                else ->
                    listOf(
                        window("2027-01-09T11:00", "2027-01-09T15:00"),
                        window("2027-01-09T17:00", "2027-01-09T22:00"),
                        window("2027-01-10T11:00", "2027-01-10T15:00"),
                        window("2027-01-10T17:00", "2027-01-10T22:00"),
                    )
            }
        val participant = calculation.prepared.participants.single { it.participantId == first }
        assertEquals(expected, participant.availableTimes)
        assertEquals(
            expected,
            calculation.prepared.evidence
                .getValue(first)
                .possibleWindows,
        )
        assertEquals(
            if (mixed && effect == IrEffect.PREFERRED) expected else emptyList(),
            participant.explicitPreferences.timeRanges,
        )
        val candidate = calculation.candidates.candidates.single()
        assertEquals(setOf(first, peer), candidate.participantIds.toSet())
        assertEquals(expected, candidate.timeRanges)
        assertEquals(expected, calculation.recommendations.options.map { it.window })
        assertTrue(
            calculation.recommendations.options.all {
                it.variants.single().preferenceCount == if (mixed && effect == IrEffect.PREFERRED) 1 else 0
            },
        )
        assertEquals(4, envelope.termApplications.size)
        assertEquals(terms.map { it.localDate to it.term }.toSet(), envelope.termApplications.map { it.localDate to it.term }.toSet())
        val termIds = terms.map { it.conditionId }.toSet()
        val declared =
            envelope.declaration.claims.results
                .first()
                .conditions
        val projected =
            envelope.calculationProjection.claims.results
                .first()
                .conditions
        assertEquals(termIds, declared.filter { it.id in termIds }.map { it.id }.toSet())
        assertTrue(
            declared.filter { it.id in termIds }.all {
                it.effect == effect && it.temporalResolution == IrTemporalResolution.NON_PRECISE
            },
        )
        assertTrue(
            projected.filter { it.id in termIds }.all {
                it.effect == effect && it.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL
            },
        )
        assertEquals(declared.filter { it.id !in termIds }, projected.filter { it.id !in termIds })
        assertEquals(literalClaims.map { it.id }.toSet(), declared.filter { it.id !in termIds }.map { it.id }.toSet())
        assertEquals(
            listOf(window("2027-01-09T00:00", "2027-01-11T00:00")),
            calculation.prepared.participants
                .single { it.participantId == peer }
                .availableTimes,
        )
    }

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(zone).toInstant(),
        LocalDateTime.parse(end).atZone(zone).toInstant(),
    )
}
