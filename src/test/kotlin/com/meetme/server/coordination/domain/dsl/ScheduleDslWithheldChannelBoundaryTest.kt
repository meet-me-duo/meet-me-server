package com.meetme.server.coordination.domain.dsl

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.support.ScheduleDslSyntheticFixture
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Withheld operator influence stays in the role's channel; preference never authorizes time. */
class ScheduleDslWithheldChannelBoundaryTest {
    private val f = ScheduleDslSyntheticFixture
    private val pid = ParticipantId(f.ir.firstParticipant)

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        Instant.parse("2027-01-08T${(start - 9).toString().padStart(2, '0')}:00:00Z"),
        Instant.parse("2027-01-08T${(end - 9).toString().padStart(2, '0')}:00:00Z"),
    )

    @Test
    fun `DSLC01 withheld PREFER upper is bounded by base and cannot inherit wider preference possibility`() {
        val claims = listOf(f.claim("base", 18, 19), f.claim("pref", 18, 22, IrEffect.PREFERRED))
        val root = f.node("root", ScheduleDslOperator.PREFER, "base-ref", "pref-ref").copy(interpretation = IrInterpretation.INFERRED)
        val program = f.program(listOf(f.ref("base-ref", "base"), f.ref("pref-ref", "pref"), root), "root")
        val prepared = ScheduleDslInputPolicy.project(f.envelope(claims, program), f.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == pid }
                .availableTimes
                .isEmpty(),
        )
        assertTrue(
            prepared.participants
                .single { it.participantId == pid }
                .explicitPreferences.timeRanges
                .isEmpty(),
        )
        assertEquals(listOf(window(18, 19)), prepared.evidence.getValue(pid).possibleWindows)
        assertEquals(
            ScheduleDslNodeDisposition.WITHHELD,
            prepared.evidence
                .getValue(pid)
                .nodes
                .single { it.nodeId == "root" }
                .disposition,
        )
    }

    @Test
    fun `DSLC02 withheld EXCEPT upper cannot enlarge base into the excluded child's separate interval`() {
        val claims = listOf(f.claim("base", 18, 19), f.claim("excluded", 19, 22, IrEffect.UNAVAILABLE))
        val root = f.node("root", ScheduleDslOperator.EXCEPT, "base-ref", "excluded-ref").copy(interpretation = IrInterpretation.INFERRED)
        val program = f.program(listOf(f.ref("base-ref", "base"), f.ref("excluded-ref", "excluded"), root), "root")
        val prepared = ScheduleDslInputPolicy.project(f.envelope(claims, program), f.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == pid }
                .availableTimes
                .isEmpty(),
        )
        assertEquals(listOf(window(18, 19)), prepared.evidence.getValue(pid).possibleWindows)
        assertEquals(
            ScheduleDslNodeDisposition.WITHHELD,
            prepared.evidence
                .getValue(pid)
                .nodes
                .single { it.nodeId == "root" }
                .disposition,
        )
    }

    @Test
    fun `DSLC03 withheld BLOCK conjunction retains both possible negative influences instead of empty intersection`() {
        val claims =
            listOf(f.claim("base", 18, 22), f.claim("left", 18, 19, IrEffect.UNAVAILABLE), f.claim("right", 21, 22, IrEffect.UNAVAILABLE))
        val blocked =
            f
                .node(
                    "blocked",
                    ScheduleDslOperator.ALL_OF,
                    "left-ref",
                    "right-ref",
                ).copy(interpretation = IrInterpretation.INFERRED)
        val program =
            f.program(
                listOf(f.ref("base-ref", "base"), f.ref("left-ref", "left"), f.ref("right-ref", "right"), blocked),
                "base-ref",
                block = "blocked",
            )
        val prepared = ScheduleDslInputPolicy.project(f.envelope(claims, program), f.policy)
        assertEquals(listOf(window(19, 21)), prepared.participants.single { it.participantId == pid }.availableTimes)
        assertEquals(listOf(window(18, 22)), prepared.evidence.getValue(pid).possibleWindows)
        assertTrue(prepared.evidence.getValue(pid).unresolvedTime)
        assertEquals(
            ScheduleDslNodeDisposition.WITHHELD,
            prepared.evidence
                .getValue(pid)
                .nodes
                .single { it.nodeId == "blocked" }
                .disposition,
        )
    }
}
