package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Native synthetic evidence and manual typed claims; no model language-accuracy claim. */
class ScheduleDslProtectedNegativeAuthorityEdgeTest {
    private val policy = F.policy.copy(eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED))
    private val parser = StrictScheduleDslApprovedTermsResponseParser(JsonMapper.builder().findAndAddModules().build())
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)

    @Test
    fun `PNAE01 unsafe inferred preference alone cannot activate default availability`() {
        val envelope = preferenceOnly()
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, policy)
        assertEquals(emptyList(), times(prepared), actual(prepared))
        assertTrue(ScheduleDslProblem.RELATION_SCOPE in prepared.evidence.getValue(first).programProblems, actual(prepared))
        assertPeer(prepared)
    }

    @Test
    fun `PNAE02 active explicit correction retains withdrawal authority alongside inferred correction`() {
        val input = input("2027-01-08 18:00부터 22:00까지 가능. 19:00부터 20:00까지 불가라고 한 말은 정정함. 20:00부터 22:00까지는 명시적으로 가능. 저녁에는 가능할 듯함.")
        val claims =
            listOf(
                claim(input, "base", IrEffect.AVAILABLE, 18, 22),
                claim(input, "blocked", IrEffect.UNAVAILABLE, 19, 20),
                claim(input, "confirmed", IrEffect.AVAILABLE, 20, 22).copy(supersedes = listOf("blocked")),
                claim(input, "estimate", IrEffect.AVAILABLE, 18, 22).copy(
                    interpretation = IrInterpretation.INFERRED,
                    supersedes = listOf("blocked"),
                ),
            )
        val nodes =
            listOf(
                F.ref(input, "base-ref", "base"),
                F.ref(input, "confirmed-ref", "confirmed"),
                F.ref(input, "estimate-ref", "estimate"),
                F.node(input, "root", ScheduleDslOperator.ANY_OF, listOf("base-ref", "confirmed-ref", "estimate-ref")),
            )
        val program = F.program(nodes, "root").copy(historyConditionIds = listOf("blocked"))
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(input, claims, program), policy)
        assertEquals(listOf(window(18, 22)), times(prepared), actual(prepared))
        assertPeer(prepared)
    }

    @Test
    fun `PNAE03 frozen project retains no availability for preference only input`() {
        val prepared = ScheduleDslInputPolicy.project(preferenceOnly(), policy)
        assertEquals(emptyList(), times(prepared), actual(prepared))
        assertPeer(prepared)
    }

    private fun preferenceOnly(): ScheduleDslEnvelope {
        val input = input("2027-01-08 19:00부터 20:00까지 불가. 이 불가 조건은 철회하지 않았음. 저녁 시간이 좋을 듯함.")
        val claims =
            listOf(
                claim(input, "blocked", IrEffect.UNAVAILABLE, 19, 20),
                claim(input, "estimate", IrEffect.PREFERRED, 18, 22).copy(
                    interpretation = IrInterpretation.INFERRED,
                    supersedes = listOf("blocked"),
                ),
            )
        val program =
            F
                .program(listOf(F.ref(input, "preferred-ref", "estimate")), null, prefer = "preferred-ref")
                .copy(historyConditionIds = listOf("blocked"))
        return parse(input, claims, program)
    }

    private fun input(text: String) = F.input(text).copy(policy = policy)

    private fun claim(
        input: ScheduleDslOfflineInput,
        id: String,
        effect: IrEffect,
        start: Int,
        end: Int,
    ) = F.claim(input, id, effect, F.scope("2027-01-08T%02d:00".format(start), "2027-01-08T%02d:00".format(end)))

    private fun parse(
        input: ScheduleDslOfflineInput,
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ): ScheduleDslEnvelope {
        val proposal = F.proposal(input, claims, program, terms = emptyList())
        val envelope =
            parser
                .parse(
                    F.json(F.document(proposal)),
                    F.snapshot(input),
                    "schedule-dsl-response-3",
                    "schedule-ir-product-4",
                    SCHEDULE_AST_VERSION,
                    "ko-time-terms-1",
                ).calculationProjection
        assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
        assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
        return envelope
    }

    private fun times(prepared: ScheduleDslPreparedInputs) = prepared.participants.single { it.participantId == first }.availableTimes

    private fun actual(prepared: ScheduleDslPreparedInputs): String {
        val evidence = prepared.evidence.getValue(first)
        return "availableTimes=${times(prepared)}; possibleWindows=${evidence.possibleWindows}; programProblems=${evidence.programProblems}"
    }

    private fun assertPeer(prepared: ScheduleDslPreparedInputs) {
        assertEquals(listOf(window(14, 18)), prepared.participants.single { it.participantId == peer }.availableTimes)
    }

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        LocalDateTime.of(2027, 1, 8, start, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.of(2027, 1, 8, end, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )
}
