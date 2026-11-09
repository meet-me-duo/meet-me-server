package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Three synthetic algebra leaves; typed model estimates do not establish user withdrawal. */
class ScheduleDslProtectedNegativeIndependentBaseTest {
    private val eligible = F.policy.copy(eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED))
    private val parser = StrictScheduleDslApprovedTermsResponseParser(JsonMapper.builder().findAndAddModules().build())
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)

    @Test
    fun `PNIB01 preserve independent explicit base outside protected negative while withholding estimate`() {
        val input = input()
        val envelope = parse(input)
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope.calculationProjection, eligible)
        val evidence = prepared.evidence.getValue(first)
        assertEquals(listOf(window(18, 19), window(20, 22)), times(prepared), actual(prepared))
        assertTrue(ScheduleDslProblem.RELATION_SCOPE in evidence.programProblems, actual(prepared))
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == "estimate" }.disposition)
        assertPeer(prepared)
        assertEquals(
            IrCalculationStatus.PARTIAL,
            ScheduleDslCalculator.calculateApprovedTerms(envelope, F.snapshot(input), eligible, MeetingMode.REMOTE).status,
        )
    }

    @Test
    fun `PNIB02 explicit only policy retains original correction influence protection`() {
        val input = input().copy(policy = F.policy)
        val envelope = parse(input)
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope.calculationProjection, F.policy)
        assertEquals(emptyList(), times(prepared), actual(prepared))
        assertPeer(prepared)
    }

    @Test
    fun `PNIB03 unresolved negative with unknown scope withholds precision and preserves possible base`() {
        val input = input()
        val envelope = parse(input, unknown = true)
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope.calculationProjection, eligible)
        val evidence = prepared.evidence.getValue(first)
        assertEquals(emptyList(), times(prepared), actual(prepared))
        assertEquals(listOf(window(18, 22)), evidence.possibleWindows, actual(prepared))
        assertTrue(ScheduleDslProblem.RELATION_SCOPE in evidence.programProblems, actual(prepared))
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == "estimate" }.disposition)
        assertPeer(prepared)
    }

    private fun input() =
        F
            .input(
                "2027-01-08 18:00부터 22:00까지 가능. 19:00부터 20:00까지 불가하며 불가 조건을 철회하지 않았음. 20:00부터 21:00까지 가능할 듯함. 일부 불가 시간은 아직 미정.",
            ).copy(policy = eligible)

    private fun parse(
        input: ScheduleDslOfflineInput,
        unknown: Boolean = false,
    ): ScheduleDslApprovedTermsEnvelope {
        val base = F.claim(input, "base", IrEffect.AVAILABLE, F.scope("2027-01-08T18:00", "2027-01-08T22:00"))
        val blocked =
            if (unknown) {
                F.claim(input, "blocked", IrEffect.UNRESOLVED)
            } else {
                F.claim(input, "blocked", IrEffect.UNAVAILABLE, F.scope("2027-01-08T19:00", "2027-01-08T20:00"))
            }
        val estimate =
            F.claim(input, "estimate", IrEffect.AVAILABLE, F.scope("2027-01-08T20:00", "2027-01-08T21:00")).copy(
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf("blocked"),
            )
        val nodes =
            listOf(
                F.ref(input, "base-ref", "base"),
                F.ref(input, "estimate-ref", "estimate"),
                F.node(input, "root", ScheduleDslOperator.ANY_OF, listOf("base-ref", "estimate-ref")),
            )
        val program = F.program(nodes, "root").copy(historyConditionIds = listOf("blocked"))
        val proposal = F.proposal(input, listOf(base, blocked, estimate), program, terms = emptyList())
        val envelope =
            parser.parse(
                F.json(F.document(proposal)),
                F.snapshot(input),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertTrue(
            envelope.calculationProjection.claims.results
                .all { it.quarantined.isEmpty() },
        )
        assertTrue(envelope.calculationProjection.programs.all { it.program != null && it.problems.isEmpty() })
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
