package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** User decision 2026-11-05: explicit new projection only; these synthetic spans do not test language accuracy. */
class ScheduleDslPreferredAvailabilityCoreTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build())

    @Test
    fun `PAV01 preference-only uses its exact interval as availability without extra weight`() {
        val claims = listOf(f.claim("pref", 19, 21, IrEffect.PREFERRED))
        val envelope = parse(claims, f.program(listOf(f.ref("pref-ref", "pref")), null, prefer = "pref-ref"))
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)
        assertEquals(listOf(window(19, 21)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(19, 21)), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertPeer(prepared)
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates
        assertEquals(1, candidates.size)
        assertEquals(setOf(first, peer), candidates.single().participantIds.toSet())
        assertEquals(listOf(window(19, 21)), candidates.single().timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone).options
        assertEquals(listOf(window(19, 21)), options.map { it.window })
        assertEquals(listOf(0), options.map { it.variants.single().preferenceCount })
    }

    @Test
    fun `PAV02 explicit availability excludes an outside preference from both candidates and credit`() {
        val claims = listOf(f.claim("plain", 18, 19), f.claim("pref", 20, 22, IrEffect.PREFERRED))
        val program = f.program(listOf(f.ref("plain-ref", "plain"), f.ref("pref-ref", "pref")), "plain-ref", prefer = "pref-ref")
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, program), f.policy)
        assertEquals(listOf(window(18, 19)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertPeer(prepared)
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates
        assertEquals(listOf(window(18, 19)), candidates.single().timeRanges)
        val recommendations = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone)
        assertEquals(listOf(window(18, 19)), recommendations.options.map { it.window })
        assertEquals(listOf(0), recommendations.options.map { it.variants.single().preferenceCount })
        assertEquals(0, recommendations.primaryOptionIndexes.first())
    }

    @Test
    fun `PAV03 explicit global unavailability subtracts from preference-derived availability without extra weight`() {
        val claims = listOf(f.claim("pref", 18, 22, IrEffect.PREFERRED), f.claim("blocked", 19, 21, IrEffect.UNAVAILABLE))
        val program = f.program(listOf(f.ref("pref-ref", "pref"), f.ref("block-ref", "blocked")), null, "block-ref", "pref-ref")
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, program), f.policy)
        val expected = listOf(window(18, 19), window(21, 22))
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertPeer(prepared)
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates
        assertEquals(expected, candidates.single().timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone).options
        assertEquals(expected, options.map { it.window })
        assertTrue(options.all { it.variants.single().preferenceCount == 0 })
    }

    @Test
    fun `PAV04 unchanged legacy projection keeps preference-only empty`() {
        val claims = listOf(f.claim("pref", 19, 21, IrEffect.PREFERRED))
        val envelope = parse(claims, f.program(listOf(f.ref("pref-ref", "pref")), null, prefer = "pref-ref"))
        val prepared = ScheduleDslInputPolicy.project(envelope, f.policy)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(
            prepared.evidence
                .getValue(first)
                .possibleWindows
                .isEmpty(),
        )
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertPeer(prepared)
    }

    @Test
    fun `PAV05 scoped PREFER annotation retains DSL06 branch clipping in the explicit new projection`() {
        val claims = listOf(f.claim("a", 18, 19), f.claim("b", 20, 22), f.claim("pref", 18, 22, IrEffect.PREFERRED))
        val nodes =
            listOf(
                f.ref("ar", "a"),
                f.ref("br", "b"),
                f.ref("pr", "pref"),
                f.node("annotated", ScheduleDslOperator.PREFER, "ar", "pr"),
                f.node("root", ScheduleDslOperator.ANY_OF, "annotated", "br"),
            )
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(parse(claims, f.program(nodes, "root")), f.policy)
        assertEquals(listOf(window(18, 19), window(20, 22)), participant(prepared).availableTimes)
        assertEquals(listOf(window(18, 19)), participant(prepared).explicitPreferences.timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone).options
        assertEquals(listOf(window(18, 19), window(20, 22)), options.map { it.window })
        assertEquals(listOf(1, 0), options.map { it.variants.single().preferenceCount })
        assertPeer(prepared)
    }

    @Test
    fun `PAV06 block-only subtracts its exact interval from the authorized default search base`() {
        val program = f.program(listOf(f.ref("block", "blocked")), null, block = "block")
        val prepared =
            ScheduleDslInputPolicy.projectWithPreferredAvailability(
                parse(listOf(f.claim("blocked", 19, 20, IrEffect.UNAVAILABLE)), program),
                f.policy,
            )
        val expected =
            listOf(
                InstantTimeRange(searchWindow().startInclusive, window(19, 20).startInclusive),
                InstantTimeRange(window(19, 20).endExclusive, searchWindow().endExclusive),
            )
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
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
            ).also {
                assertTrue(it.claims.results.all { result -> result.quarantined.isEmpty() })
                assertTrue(it.programs.all { result -> result.problems.isEmpty() && result.program != null })
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
