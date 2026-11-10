package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Latest explicit policy: default availability is independent support, never a substitute for failed interpretation. */
class ScheduleDslDefaultAvailabilityGuardTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build())

    @Test
    fun `DAVG01 validated empty program permits default search availability while old project stays empty`() {
        val envelope = parse(emptyList(), f.program(emptyList(), null))
        assertClean(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(searchWindow()), participant(prepared).availableTimes)
        assertEquals(listOf(searchWindow()), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(
            envelope.claims.results
                .first()
                .conditions
                .isEmpty(),
        )
        val oldParticipant = ScheduleDslInputPolicy.project(envelope, f.policy).participants.single { it.participantId == first }
        assertTrue(oldParticipant.availableTimes.isEmpty())
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates
        assertEquals(1, candidates.size)
        assertEquals(listOf(window(18, 22)), candidates.single().timeRanges)
        assertEquals(setOf(first, peer), candidates.single().participantIds.toSet())
        assertPeer(prepared)
    }

    @Test
    fun `DAVG02 unknown-scope negative differs from no constraint and blocks the entire default lower`() {
        val condition = f.ir.condition("unknown", effect = IrEffect.UNRESOLVED, interpretation = IrInterpretation.UNRESOLVED)
        val envelope = parse(listOf(condition), f.program(listOf(f.ref("block", "unknown")), null, block = "block"))
        assertClean(envelope)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertEquals(listOf(searchWindow()), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            IrEffect.UNRESOLVED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertNull(
            envelope.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertPeer(prepared)
    }

    @Test
    fun `DAVG03 malformed JSON is a typed parser failure and never becomes an empty valid envelope`() {
        val failure = assertFailsWith<NaturalLanguageParserException> { decode("{not-json") }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertNull(failure.cause)
    }

    @Test
    fun `DAVG04 absent program cannot acquire default availability even with zero claims`() {
        val document = document(emptyList(), null)
        val envelope = decode(wire.encode(document))
        assertNull(envelope.programs.first().program)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(
            prepared.evidence
                .getValue(first)
                .programProblems
                .isNotEmpty(),
        )
        assertPeer(prepared)
    }

    @Test
    fun `DAVG05 explicit unknown-bounds positive root cannot be bypassed by default availability`() {
        val condition = f.ir.condition("positive", effect = IrEffect.AVAILABLE)
        val envelope = parse(listOf(condition), f.program(listOf(f.ref("allow", "positive")), "allow"))
        assertClean(envelope)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrEffect.AVAILABLE,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertPeer(prepared)
    }

    @Test
    fun `DAVG06 nonprecise preferred-only retains uncertainty without default availability or invented credit`() {
        val condition = f.ir.condition("preference", effect = IrEffect.PREFERRED)
        val envelope = parse(listOf(condition), f.program(listOf(f.ref("pref", "preference")), null, prefer = "pref"))
        assertClean(envelope)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertEquals(listOf(searchWindow()), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertNull(
            envelope.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `DAVG07 localized rejected preference blocks default but preserves independent validated preference support`(independent: Boolean) {
        val bad = f.claim("bad", 19, 20, IrEffect.PREFERRED).copy(scope = f.ir.preference.copy(dimension = IrDimension.PLACE))
        val good = f.claim("good", 20, 21, IrEffect.PREFERRED)
        val claims = if (independent) listOf(bad, good) else listOf(bad)
        val nodes =
            if (independent) {
                listOf(
                    f.ref("bad-ref", "bad"),
                    f.ref("good-ref", "good"),
                    f.node("root", ScheduleDslOperator.ANY_OF, "bad-ref", "good-ref"),
                )
            } else {
                listOf(f.ref("bad-ref", "bad"))
            }
        val envelope = parse(claims, f.program(nodes, null, prefer = if (independent) "root" else "bad-ref"))
        val quarantine =
            envelope.claims.results
                .first()
                .quarantined
                .single()
        assertEquals("bad", quarantine.conditionId)
        assertEquals(IrProblem.SCOPE, quarantine.problem)
        assertNotNull(envelope.programs.first().program)
        assertTrue(
            envelope.programs
                .first()
                .problems
                .isEmpty(),
        )
        assertEquals(listOf("bad"), envelope.rejectedPreferenceWitnesses.map { it.conditionId })
        val prepared = project(envelope)
        val expected = if (independent) listOf(window(20, 21)) else emptyList()
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            if (independent) listOf("good") else emptyList(),
            envelope.claims.results
                .first()
                .conditions
                .map { it.id },
        )
        assertPeer(prepared)
    }

    private fun project(envelope: ScheduleDslEnvelope) = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)

    private fun participant(prepared: ScheduleDslPreparedInputs) = prepared.participants.single { it.participantId == first }

    private fun document(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>?,
    ) = wire.document().also {
        wire.firstResult(it)["conditions"] = claims.map(f.ir::wire)
        wire.firstResult(it)["program"] = program?.let(wire::program)
    }

    private fun parse(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>?,
    ) = decode(wire.encode(document(claims, program)))

    private fun decode(json: String) =
        parser.parse(
            json,
            f.ir.snapshot(),
            SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
            SCHEDULE_DSL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
        )

    private fun assertClean(envelope: ScheduleDslEnvelope) {
        assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
        assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
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
