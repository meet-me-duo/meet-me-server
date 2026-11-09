package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Synthetic typed proposals over native raw evidence; no model or language-accuracy assertion. */
class ScheduleDslInferredNegativeCorrectionTest {
    private val policy = F.policy.copy(eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED))
    private val parser = StrictScheduleDslApprovedTermsResponseParser(JsonMapper.builder().findAndAddModules().build())
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)

    @Test
    fun `INC01 inferred availability cannot withdraw an asserted explicit negative through supersedes`() {
        val input = input("2027-01-08 19:00부터 20:00까지 불가. 이 불가 조건은 철회하지 않았음. 저녁에 가능할 듯함.")
        val old = claim(input, "old", IrEffect.UNAVAILABLE, 19, 20)
        val estimate =
            claim(input, "estimate", IrEffect.AVAILABLE, 18, 22).copy(
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf("old"),
            )
        val envelope = parse(input, listOf(old, estimate), "estimate", listOf("old"))
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, policy)
        assertTrue(
            ScheduleDslProblem.RELATION_SCOPE in prepared.evidence.getValue(first).programProblems,
            "programProblems=${prepared.evidence.getValue(first).programProblems}; " +
                "availableTimes=${prepared.participants.single { it.participantId == first }.availableTimes}; " +
                "possibleWindows=${prepared.evidence.getValue(first).possibleWindows}",
        )
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertTrue(
            prepared.participants
                .single { it.participantId == first }
                .availableTimes
                .isEmpty(),
        )
        assertPeer(prepared)
    }

    @Test
    fun `INC02 actual withdrawal retained as retracted history allows independent inferred availability`() {
        val input = input("2027-01-08 19:00부터 20:00까지 불가라고 했던 말은 철회함. 저녁에 가능할 듯함.")
        val old = claim(input, "old", IrEffect.UNAVAILABLE, 19, 20).copy(assertion = IrAssertion.RETRACTED)
        val estimate = claim(input, "estimate", IrEffect.AVAILABLE, 18, 22).copy(interpretation = IrInterpretation.INFERRED)
        assertAvailable(parse(input, listOf(old, estimate), "estimate", listOf("old")), 18, 22)
    }

    @Test
    fun `INC03 explicit numeric correction can supersede an explicit negative`() {
        val input = input("2027-01-08 19:00부터 20:00까지 불가라고 했지만 정정함. 18:00부터 22:00까지 가능.")
        val old = claim(input, "old", IrEffect.UNAVAILABLE, 19, 20)
        val correction = claim(input, "correction", IrEffect.AVAILABLE, 18, 22).copy(supersedes = listOf("old"))
        assertAvailable(parse(input, listOf(old, correction), "correction", listOf("old")), 18, 22)
    }

    @Test
    fun `INC04 inferred correction of positive availability remains eligible`() {
        val input = input("2027-01-08 18:00부터 19:00까지 가능. 정정하면 20:00부터 22:00까지 가능할 듯함.")
        val old = claim(input, "old", IrEffect.AVAILABLE, 18, 19)
        val correction =
            claim(input, "correction", IrEffect.AVAILABLE, 20, 22).copy(
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf("old"),
            )
        assertAvailable(parse(input, listOf(old, correction), "correction", listOf("old")), 20, 22)
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
        active: String,
        history: List<String>,
    ): ScheduleDslEnvelope {
        val program = F.program(listOf(F.ref(input, "root", active)), "root").copy(historyConditionIds = history)
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

    private fun assertAvailable(
        envelope: ScheduleDslEnvelope,
        start: Int,
        end: Int,
    ) {
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, policy)
        assertTrue(
            prepared.evidence
                .getValue(first)
                .programProblems
                .isEmpty(),
        )
        assertEquals(listOf(window(start, end)), prepared.participants.single { it.participantId == first }.availableTimes)
        assertPeer(prepared)
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
