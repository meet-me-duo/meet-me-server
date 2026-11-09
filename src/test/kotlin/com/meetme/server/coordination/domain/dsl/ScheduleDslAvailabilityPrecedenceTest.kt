package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit user precedence: availability first, otherwise preferences, otherwise the established default. */
class ScheduleDslAvailabilityPrecedenceTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build())

    @ParameterizedTest
    @ValueSource(strings = ["PRECISE_INTERVAL", "NON_PRECISE"])
    fun `APR01 preference-only uses its own support and never substitutes the default search period`(resolution: String) {
        val preference = f.claim("pref", 19, 21, IrEffect.PREFERRED).copy(temporalResolution = IrTemporalResolution.valueOf(resolution))
        val envelope = parse(listOf(preference), f.program(listOf(f.ref("p", "pref")), null, prefer = "p"))
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)
        val expected = if (resolution == "PRECISE_INTERVAL") listOf(window(19, 21)) else emptyList()
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(19, 21)), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(
            IrTemporalResolution.valueOf(resolution),
            envelope.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        val oldParticipant = ScheduleDslInputPolicy.project(envelope, f.policy).participants.single { it.participantId == first }
        assertTrue(oldParticipant.availableTimes.isEmpty())
        assertPeer(prepared)
    }

    @Test
    fun `APR02 explicit availability bounds both lower and upper while a wider preference earns only inside credit`() {
        val claims = listOf(f.claim("available", 19, 21), f.claim("pref", 18, 22, IrEffect.PREFERRED))
        val program = f.program(listOf(f.ref("a", "available"), f.ref("p", "pref")), "a", prefer = "p")
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, program), f.policy)
        assertEquals(listOf(window(19, 21)), participant(prepared).availableTimes)
        assertEquals(listOf(window(19, 21)), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(listOf(window(19, 21)), participant(prepared).explicitPreferences.timeRanges)
        val option = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone).options.single()
        assertEquals(window(19, 21), option.window)
        assertEquals(1, option.variants.single().preferenceCount)
        assertPeer(prepared)
    }

    @Test
    fun `APR03 explicit global unavailability wins over preference-derived availability without extra weight`() {
        val claims = listOf(f.claim("pref", 18, 22, IrEffect.PREFERRED), f.claim("ban", 19, 21, IrEffect.UNAVAILABLE))
        val program = f.program(listOf(f.ref("p", "pref"), f.ref("b", "ban")), null, "b", "p")
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, program), f.policy)
        val expected = listOf(window(18, 19), window(21, 22))
        assertEquals(expected, participant(prepared).availableTimes)
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertPeer(prepared)
    }

    @Test
    fun `APR04 independent unknown-scope preference cannot reopen an explicit availability EXCEPT upper`() {
        val claims =
            listOf(
                f.claim("base", 18, 22),
                f.claim("ban", 19, 20, IrEffect.UNAVAILABLE),
                f.ir.condition("unknown-pref", effect = IrEffect.PREFERRED),
            )
        val program =
            f.program(
                listOf(
                    f.ref("a", "base"),
                    f.ref("b", "ban"),
                    f.node("allow", ScheduleDslOperator.EXCEPT, "a", "b"),
                    f.ref("p", "unknown-pref"),
                ),
                "allow",
                prefer = "p",
            )
        val envelope = parse(claims, program)
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)
        val expected = listOf(window(18, 19), window(20, 22))
        assertEquals(expected, participant(prepared).availableTimes)
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            null,
            envelope.programs
                .first()
                .program
                ?.exclusionRoot,
        )
        assertEquals(
            null,
            envelope.claims.results
                .first()
                .conditions
                .last()
                .scope,
        )
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `APR05 without availability or preference roots the existing default and negative subtraction remain`(negative: Boolean) {
        val claims = if (negative) listOf(f.claim("ban", 19, 20, IrEffect.UNAVAILABLE)) else emptyList()
        val program = if (negative) f.program(listOf(f.ref("b", "ban")), null, block = "b") else f.program(emptyList(), null)
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, program), f.policy)
        val expected =
            if (negative) {
                listOf(
                    InstantTimeRange(searchWindow().startInclusive, window(19, 20).startInclusive),
                    InstantTimeRange(window(19, 20).endExclusive, searchWindow().endExclusive),
                )
            } else {
                listOf(searchWindow())
            }
        assertEquals(expected, participant(prepared).availableTimes)
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertPeer(prepared)
    }

    private fun parse(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ) = wire.document().let { document ->
        wire.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        wire.firstResult(document)["program"] = wire.program(program)
        parser
            .parse(
                wire.encode(document),
                f.ir.snapshot(),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            ).also { envelope ->
                assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
                assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
            }
    }

    private fun participant(prepared: ScheduleDslPreparedInputs) = prepared.participants.single { it.participantId == first }

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
