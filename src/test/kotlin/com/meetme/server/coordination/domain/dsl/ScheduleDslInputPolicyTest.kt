package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.support.ScheduleDslSyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleDslInputPolicyTest {
    private val f = ScheduleDslSyntheticFixture
    private val pid = ParticipantId(f.ir.firstParticipant)

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        Instant.parse("2027-01-08T${(start - 9).toString().padStart(2, '0')}:00:00Z"),
        Instant.parse("2027-01-08T${(end - 9).toString().padStart(2, '0')}:00:00Z"),
    )

    private fun times(p: ScheduleDslPreparedInputs) = p.participants.single { it.participantId == pid }.availableTimes

    @ParameterizedTest
    @CsvSource("ALL_OF,19,21", "ANY_OF,18,22")
    fun `DSL01 explicit conjunction and disjunction retain different set semantics`(
        operator: String,
        start: Int,
        end: Int,
    ) {
        val claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21))
        val program =
            f.program(
                listOf(f.ref("a-ref", "a"), f.ref("b-ref", "b"), f.node("root", ScheduleDslOperator.valueOf(operator), "a-ref", "b-ref")),
                "root",
            )
        val original = f.envelope(claims, program)
        val prepared = ScheduleDslInputPolicy.project(original, f.policy)
        assertEquals(listOf(window(start, end)), times(prepared))
        assertEquals(original, f.envelope(claims, program))
        assertEquals(
            3,
            prepared.evidence
                .getValue(pid)
                .nodes.size,
        )
    }

    @Test
    fun `DSL02 nested ALL over ANY is neither flat union nor flat intersection`() {
        val claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21), f.claim("c", 20, 23))
        val nodes =
            listOf(
                f.ref("ar", "a"),
                f.ref("br", "b"),
                f.ref("cr", "c"),
                f.node("or", ScheduleDslOperator.ANY_OF, "br", "cr"),
                f.node("root", ScheduleDslOperator.ALL_OF, "ar", "or"),
            )
        assertEquals(listOf(window(19, 22)), times(ScheduleDslInputPolicy.project(f.envelope(claims, f.program(nodes, "root")), f.policy)))
    }

    @Test
    fun `DSL03 branch local EXCEPT never becomes a global exclusion`() {
        val claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 20, IrEffect.UNAVAILABLE), f.claim("c", 19, 21))
        val nodes =
            listOf(
                f.ref("ar", "a"),
                f.ref("br", "b"),
                f.ref("cr", "c"),
                f.node("except", ScheduleDslOperator.EXCEPT, "ar", "br"),
                f.node("root", ScheduleDslOperator.ANY_OF, "except", "cr"),
            )
        assertEquals(listOf(window(18, 22)), times(ScheduleDslInputPolicy.project(f.envelope(claims, f.program(nodes, "root")), f.policy)))
    }

    @ParameterizedTest
    @CsvSource("ANY_OF,true", "ALL_OF,false")
    fun `DSL04 known nonprecise availability has no lower support but is not a negative sibling`(
        operator: String,
        supported: Boolean,
    ) {
        val np = f.ir.condition("np")
        val claims = listOf(f.claim("a", 18, 22), np)
        val nodes = listOf(f.ref("ar", "a"), f.ref("nr", "np"), f.node("root", ScheduleDslOperator.valueOf(operator), "ar", "nr"))
        val prepared = ScheduleDslInputPolicy.project(f.envelope(claims, f.program(nodes, "root")), f.policy)
        assertEquals(if (supported) listOf(window(18, 22)) else emptyList(), times(prepared))
        val evidence = prepared.evidence.getValue(pid)
        assertTrue(evidence.unresolvedTime)
        val claim = evidence.conditions.single { it.conditionId == "np" }
        assertEquals(IrEffect.AVAILABLE, claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
    }

    @Test
    fun `DSL05 missing epistemic choice never acts as ordinary OR`() {
        val a = f.claim("a", 18, 19).copy(alternativeGroup = "g", alternative = "left")
        val b = f.claim("b", 21, 22).copy(alternativeGroup = "g", alternative = "right")
        val choice = f.node("root", ScheduleDslOperator.CHOICE, "ar", "br").copy(choiceGroup = "g", branchLabels = listOf("left", "right"))
        val envelope = f.envelope(listOf(a, b), f.program(listOf(f.ref("ar", "a"), f.ref("br", "b"), choice), "root"))
        assertTrue(times(ScheduleDslInputPolicy.project(envelope, f.policy)).isEmpty())
        val selected = f.policy.copy(alternativeChoices = listOf(IrAlternativeChoice(f.ir.first, "g", "right")))
        assertEquals(listOf(window(21, 22)), times(ScheduleDslInputPolicy.project(envelope, selected)))
    }

    @Test
    fun `DSL06 branch preference clips to its own base and cannot leak to another OR branch`() {
        val claims = listOf(f.claim("a", 18, 19), f.claim("b", 20, 22), f.claim("p", 18, 22, IrEffect.PREFERRED))
        val nodes =
            listOf(
                f.ref("ar", "a"),
                f.ref("br", "b"),
                f.ref("pr", "p"),
                f.node("annotated", ScheduleDslOperator.PREFER, "ar", "pr"),
                f.node("root", ScheduleDslOperator.ANY_OF, "annotated", "br"),
            )
        val prepared = ScheduleDslInputPolicy.project(f.envelope(claims, f.program(nodes, "root")), f.policy)
        val first = prepared.participants.single { it.participantId == pid }
        assertEquals(listOf(window(18, 19), window(20, 22)), first.availableTimes)
        assertEquals(listOf(window(18, 19)), first.explicitPreferences.timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options
        assertTrue(options.any { it.window == window(18, 19) })
        assertTrue(options.any { it.window == window(20, 22) })
        for (option in options) assertEquals(if (option.window == window(18, 19)) 1 else 0, option.variants.single().preferenceCount)
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUOTED_OTHER", "RETRACTED"])
    fun `DSL07 nonasserted operator evidence cannot authorize its precise child`(assertion: String) {
        val node = f.ref("root", "a").copy(assertion = IrAssertion.valueOf(assertion))
        val prepared = ScheduleDslInputPolicy.project(f.envelope(listOf(f.claim("a", 18, 22)), f.program(listOf(node), "root")), f.policy)
        assertTrue(times(prepared).isEmpty())
        assertEquals(
            ScheduleDslNodeDisposition.EXCLUDED_ASSERTION,
            prepared.evidence
                .getValue(pid)
                .nodes
                .single()
                .disposition,
        )
    }

    @Test
    fun `DSL08 corrected inactive reference is not rewritten to TRUE or resurrected`() {
        val old = f.claim("old", 18, 22)
        val fresh = f.ir.condition("fresh", supersedes = listOf("old"))
        val nodes =
            listOf(f.ref("old-ref", "old"), f.ref("new-ref", "fresh"), f.node("root", ScheduleDslOperator.ALL_OF, "old-ref", "new-ref"))
        val prepared = ScheduleDslInputPolicy.project(f.envelope(listOf(old, fresh), f.program(nodes, "root")), f.policy)
        assertTrue(times(prepared).isEmpty())
        assertTrue(prepared.evidence.getValue(pid).unresolvedTime)
        assertEquals(
            ScheduleDslNodeDisposition.INACTIVE_REFERENCE,
            prepared.evidence
                .getValue(pid)
                .nodes
                .single {
                    it.nodeId == "old-ref"
                }.disposition,
        )
    }

    @Test
    fun `DSL09 block root without explicit allow root cannot create availability`() {
        val prepared =
            ScheduleDslInputPolicy.project(
                f.envelope(
                    listOf(f.claim("b", 19, 20, IrEffect.UNAVAILABLE)),
                    f.program(listOf(f.ref("block", "b")), null, block = "block"),
                ),
                f.policy,
            )
        assertTrue(times(prepared).isEmpty())
    }
}
