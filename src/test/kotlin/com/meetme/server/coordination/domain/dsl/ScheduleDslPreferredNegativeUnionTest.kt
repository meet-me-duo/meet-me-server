package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Global negative union cannot be bypassed when unconditional preferences supply positive support. */
class ScheduleDslPreferredNegativeUnionTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `PAVN01 two ineligible global negatives retain both exclusions from preference-derived precision`() {
        val left = f.claim("left", 19, 20, IrEffect.UNAVAILABLE).copy(interpretation = IrInterpretation.INFERRED)
        val right = f.claim("right", 21, 22, IrEffect.UNAVAILABLE).copy(interpretation = IrInterpretation.INFERRED)
        val prepared = project(left, right)
        val participant = prepared.participants.single { it.participantId == first }
        val expected = listOf(window(18, 19), window(20, 21))
        assertEquals(expected, participant.availableTimes)
        assertTrue(participant.explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(18, 22)), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertPeer(prepared)
    }

    @Test
    fun `PAVN02 unknown-scope negative union prevents preference-derived precision while known negative still reduces possible upper`() {
        val unknown = f.ir.condition("left", effect = IrEffect.UNRESOLVED, interpretation = IrInterpretation.UNRESOLVED)
        val known = f.claim("right", 19, 20, IrEffect.UNAVAILABLE)
        val prepared = project(unknown, known)
        val participant = prepared.participants.single { it.participantId == first }
        assertTrue(participant.availableTimes.isEmpty())
        assertTrue(participant.explicitPreferences.timeRanges.isEmpty())
        val possible = listOf(window(18, 19), window(20, 22))
        assertEquals(possible, prepared.evidence.getValue(first).possibleWindows)
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertPeer(prepared)
    }

    private fun project(
        left: ProposedIrV2Condition,
        right: ProposedIrV2Condition,
    ): ScheduleDslPreparedInputs {
        val claims = listOf(f.claim("pref", 18, 22, IrEffect.PREFERRED), left, right)
        val program =
            f.program(
                listOf(
                    f.ref("pref-ref", "pref"),
                    f.ref("left-ref", "left"),
                    f.ref("right-ref", "right"),
                    f.node("blocked", ScheduleDslOperator.ANY_OF, "left-ref", "right-ref"),
                ),
                null,
                "blocked",
                "pref-ref",
            )
        val document = wire.document()
        wire.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        wire.firstResult(document)["program"] = wire.program(program)
        val envelope =
            StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build()).parse(
                wire.encode(document),
                f.ir.snapshot(),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
        assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
        return ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)
    }

    private fun assertPeer(prepared: ScheduleDslPreparedInputs) {
        assertEquals(listOf(window(18, 22)), prepared.participants.single { it.participantId == peer }.availableTimes)
    }

    private fun searchWindow() =
        InstantTimeRange(
            LocalDateTime.of(2027, 1, 4, 0, 0).atZone(zone).toInstant(),
            LocalDateTime.of(2027, 1, 11, 0, 0).atZone(zone).toInstant(),
        )

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        LocalDateTime.of(2027, 1, 8, start, 0).atZone(zone).toInstant(),
        LocalDateTime.of(2027, 1, 8, end, 0).atZone(zone).toInstant(),
    )
}
