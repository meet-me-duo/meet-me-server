package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New explicit preference availability preserves AST scope, uncertainty, corrections and native evidence. */
class ScheduleDslPreferredAvailabilityBoundaryTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build())

    @ParameterizedTest
    @ValueSource(strings = ["ALL_OF", "ANY_OF"])
    fun `PAVB01 preference-only conjunction and disjunction retain availability without extra credit`(operator: String) {
        val claims = listOf(pref("a", 18, 20), pref("b", 19, 22))
        val nodes = listOf(f.ref("ar", "a"), f.ref("br", "b"), f.node("root", ScheduleDslOperator.valueOf(operator), "ar", "br"))
        val envelope = parse(claims, f.program(nodes, null, prefer = "root"))
        assertValid(envelope)
        val prepared = project(envelope)
        val expected = listOf(if (operator == "ALL_OF") window(19, 20) else window(18, 22))
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertPeer(prepared)
    }

    @Test
    fun `PAVB02 explicit availability bounds an independent preference EXCEPT while global exclusions remain dominant`() {
        val claims =
            listOf(
                f.claim("plain", 18, 19),
                pref("pref", 18, 22),
                f.claim("local-block", 19, 20, IrEffect.UNAVAILABLE),
                f.claim("global-block", 21, 22, IrEffect.UNAVAILABLE),
            )
        val nodes =
            listOf(
                f.ref("plain-ref", "plain"),
                f.ref("pref-ref", "pref"),
                f.ref("local-ref", "local-block"),
                f.ref("global-ref", "global-block"),
                f.node("preferred", ScheduleDslOperator.EXCEPT, "pref-ref", "local-ref"),
            )
        val envelope = parse(claims, f.program(nodes, "plain-ref", "global-ref", "preferred"))
        assertValid(envelope)
        val prepared = project(envelope)
        val expected = listOf(window(18, 19))
        assertEquals(expected, participant(prepared).availableTimes)
        assertEquals(expected, participant(prepared).explicitPreferences.timeRanges)
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "left", "right"])
    fun `PAVB03 epistemic preference-only choice controls availability without extra credit`(selected: String) {
        val claims =
            listOf(
                pref("a", 18, 19).copy(alternativeGroup = "g", alternative = "left"),
                pref("b", 21, 22).copy(alternativeGroup = "g", alternative = "right"),
            )
        val choice =
            f
                .node("choice", ScheduleDslOperator.CHOICE, "ar", "br")
                .copy(choiceGroup = "g", branchLabels = listOf("left", "right"))
        val envelope = parse(claims, f.program(listOf(f.ref("ar", "a"), f.ref("br", "b"), choice), null, prefer = "choice"))
        assertValid(envelope)
        val choices = if (selected == "missing") emptyList() else listOf(IrAlternativeChoice(f.ir.first, "g", selected))
        val prepared = project(envelope, f.policy.copy(alternativeChoices = choices))
        val expected =
            when (selected) {
                "left" -> listOf(window(18, 19))
                "right" -> listOf(window(21, 22))
                else -> emptyList()
            }
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            if (selected == "missing") listOf(window(18, 19), window(21, 22)) else expected,
            prepared.evidence.getValue(first).possibleWindows,
        )
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(strings = ["INFERRED", "UNRESOLVED"])
    fun `PAVB04 ineligible interpretation is not a precise preferred candidate and preserves an independent alternative`(label: String) {
        val withheld = pref("uncertain", 19, 21).copy(interpretation = IrInterpretation.valueOf(label))
        val claims = listOf(f.claim("plain", 18, 19), withheld)
        val envelope =
            parse(
                claims,
                f.program(listOf(f.ref("plain-ref", "plain"), f.ref("pref-ref", "uncertain")), "plain-ref", prefer = "pref-ref"),
            )
        assertValid(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(window(18, 19)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(18, 19)), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(
            IrInterpretation.valueOf(label),
            envelope.claims.results
                .first()
                .conditions
                .last()
                .interpretation,
        )
        assertEquals(
            IrConditionDisposition.WITHHELD,
            prepared.evidence
                .getValue(first)
                .conditions
                .last()
                .disposition,
        )
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PAVB05 nonprecise preferred meaning retains its bounds without creating precise availability`(bounded: Boolean) {
        val uncertain =
            f.ir.condition(
                "uncertain",
                effect = IrEffect.PREFERRED,
                scope = if (bounded) f.claim("scope", 19, 21).scope else null,
            )
        val envelope =
            parse(
                listOf(f.claim("plain", 18, 19), uncertain),
                f.program(listOf(f.ref("plain-ref", "plain"), f.ref("pref-ref", "uncertain")), "plain-ref", prefer = "pref-ref"),
            )
        assertValid(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(window(18, 19)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            envelope.claims.results
                .first()
                .conditions
                .last()
                .temporalResolution,
        )
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .last()
                .effect,
        )
        assertEquals(listOf(window(18, 19)), prepared.evidence.getValue(first).possibleWindows)
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUOTED_OTHER", "RETRACTED"])
    fun `PAVB06 nonasserted preference cannot create availability credit or default fallback`(assertion: String) {
        val claims = listOf(pref("pref", 18, 22).copy(assertion = IrAssertion.valueOf(assertion)))
        val envelope = parse(claims, f.program(listOf(f.ref("pref-ref", "pref")), null, prefer = "pref-ref"))
        assertValid(envelope)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertTrue(
            prepared.evidence
                .getValue(first)
                .possibleWindows
                .isEmpty(),
        )
        assertEquals(
            IrAssertion.valueOf(assertion),
            envelope.claims.results
                .first()
                .conditions
                .single()
                .assertion,
        )
        assertPeer(prepared)
    }

    @Test
    fun `PAVB07 ineligible preference operator retains only bounded possible support without default fallback`() {
        val nodes = listOf(f.ref("pref-ref", "pref").copy(interpretation = IrInterpretation.INFERRED))
        val envelope = parse(listOf(pref("pref", 19, 21)), f.program(nodes, null, prefer = "pref-ref"))
        assertValid(envelope)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(19, 21)), prepared.evidence.getValue(first).possibleWindows)
        assertEquals(
            ScheduleDslNodeDisposition.WITHHELD,
            prepared.evidence
                .getValue(first)
                .nodes
                .single()
                .disposition,
        )
        assertPeer(prepared)
    }

    @Test
    fun `PAVB08 asserted eligible correction replaces the old preference without resurrecting history`() {
        val old = pref("old", 18, 19)
        val fresh = pref("fresh", 20, 21).copy(supersedes = listOf("old"))
        val program = f.program(listOf(f.ref("fresh-ref", "fresh")), null, prefer = "fresh-ref", history = listOf("old"))
        val envelope = parse(listOf(old, fresh), program)
        assertValid(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(window(20, 21)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            IrConditionDisposition.SUPERSEDED,
            prepared.evidence
                .getValue(first)
                .conditions
                .first()
                .disposition,
        )
        assertEquals(
            listOf(old, fresh).map(f.ir::verified),
            envelope.claims.results
                .first()
                .conditions,
        )
        assertPeer(prepared)
    }

    @Test
    fun `PAVB09 uncertain correction withholds only targeted preference while independent precise preference survives`() {
        val old = pref("old", 18, 19)
        val left =
            pref("correction-left", 19, 20).copy(
                supersedes = listOf("old"),
                alternativeGroup = "correction-choice",
                alternative = "left",
            )
        val right = left.copy(id = "correction-right", alternative = "right")
        val independent = pref("independent", 20, 21)
        val choice =
            f
                .node("choice", ScheduleDslOperator.CHOICE, "left-ref", "right-ref")
                .copy(choiceGroup = "correction-choice", branchLabels = listOf("left", "right"))
        val nodes =
            listOf(
                f.ref("old-ref", "old"),
                f.ref("left-ref", "correction-left"),
                f.ref("right-ref", "correction-right"),
                f.ref("independent-ref", "independent"),
                choice,
                f.node("root", ScheduleDslOperator.ANY_OF, "old-ref", "choice", "independent-ref"),
            )
        val claims = listOf(old, left, right, independent)
        val envelope = parse(claims, f.program(nodes, null, prefer = "root"))
        assertValid(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(window(20, 21)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            claims.map(f.ir::verified),
            envelope.claims.results
                .first()
                .conditions,
        )
        assertPeer(prepared)
    }

    @ParameterizedTest
    @ValueSource(strings = ["claim", "operator"])
    fun `PAVB10 forged quote cannot authorize preferred time or erase the independent peer`(target: String) {
        val original = pref("pref", 19, 21)
        val forged = f.ir.evidence().copy(quote = "Synthetic unmatched quote")
        val claim = if (target == "claim") original.copy(evidence = listOf(forged)) else original
        val node = if (target == "operator") f.ref("pref-ref", "pref").copy(evidence = listOf(forged)) else f.ref("pref-ref", "pref")
        val envelope = parse(listOf(claim), f.program(listOf(node), null, prefer = "pref-ref"))
        assertNull(envelope.programs.first().program)
        assertTrue(
            envelope.programs
                .first()
                .problems
                .isNotEmpty(),
        )
        if (target == "claim") {
            assertEquals(
                listOf("pref"),
                envelope.claims.results
                    .first()
                    .quarantined
                    .map { it.conditionId },
            )
        } else {
            assertTrue(ScheduleDslProblem.OPERATOR_EVIDENCE in envelope.programs.first().problems)
            assertEquals(
                listOf(f.ir.verified(original)),
                envelope.claims.results
                    .first()
                    .conditions,
            )
        }
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertPeer(prepared)
        assertNotNull(envelope.programs.last().program)
        assertTrue(
            envelope.programs
                .last()
                .problems
                .isEmpty(),
        )
    }

    @Test
    fun `PAVB11 new projection does not relax the validated role of a preferred claim`() {
        val envelope = parse(listOf(pref("pref", 19, 21)), f.program(listOf(f.ref("ref", "pref")), "ref"))
        assertNull(envelope.programs.first().program)
        assertTrue(ScheduleDslProblem.ROLE in envelope.programs.first().problems)
        val prepared = project(envelope)
        assertTrue(participant(prepared).availableTimes.isEmpty())
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertPeer(prepared)
    }

    @Test
    fun `PAVB12 overlapping preference-only claims supply availability without an extra point`() {
        val nodes = listOf(f.ref("ar", "a"), f.ref("br", "b"), f.node("root", ScheduleDslOperator.ANY_OF, "ar", "br"))
        val envelope = parse(listOf(pref("a", 19, 21), pref("b", 19, 21)), f.program(nodes, null, prefer = "root"))
        assertValid(envelope)
        val prepared = project(envelope)
        assertEquals(listOf(window(19, 21)), participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone).options
        assertEquals(listOf(window(19, 21)), options.map { it.window })
        assertEquals(listOf(0), options.map { it.variants.single().preferenceCount })
        assertPeer(prepared)
    }

    @Test
    fun `PAVB13 unresolved negative blocks preference-derived precision without making its possible interval definitely forbidden`() {
        val blocker =
            f
                .claim("uncertain-block", 19, 20, IrEffect.UNRESOLVED)
                .copy(interpretation = IrInterpretation.UNRESOLVED)
        val claims = listOf(pref("pref", 18, 22), blocker)
        val nodes = listOf(f.ref("pref-ref", "pref"), f.ref("block-ref", "uncertain-block"))
        val envelope = parse(claims, f.program(nodes, null, "block-ref", "pref-ref"))
        assertValid(envelope)
        val prepared = project(envelope)
        val expected = listOf(window(18, 19), window(20, 22))
        assertEquals(expected, participant(prepared).availableTimes)
        assertTrue(participant(prepared).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(window(18, 22)), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrEffect.UNRESOLVED,
            envelope.claims.results
                .first()
                .conditions
                .last()
                .effect,
        )
        assertPeer(prepared)
    }

    private fun pref(
        id: String,
        start: Int,
        end: Int,
    ) = f.claim(id, start, end, IrEffect.PREFERRED)

    private fun project(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy = f.policy,
    ) = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, policy)

    private fun parse(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ) = wire.document().let { document ->
        wire.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        wire.firstResult(document)["program"] = wire.program(program)
        parser.parse(
            wire.encode(document),
            f.ir.snapshot(),
            SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
            SCHEDULE_DSL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
        )
    }

    private fun assertValid(envelope: ScheduleDslEnvelope) {
        assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
        assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
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
