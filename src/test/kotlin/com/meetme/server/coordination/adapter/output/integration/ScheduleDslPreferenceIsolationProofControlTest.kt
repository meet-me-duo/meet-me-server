package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Controls against over-broad local preference proofs; all sources and assigned claims are synthetic. */
class ScheduleDslPreferenceIsolationProofControlTest {
    private val f = ScheduleDslSyntheticFixture
    private val w = ScheduleDslWireFixture
    private val snapshot = ScheduleDslQuestionContextFixture.snapshot()
    private val parser = ScheduleDslProviderContract(JsonMapper.builder().findAndAddModules().build())
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val base get() = f.claim("base", 18, 22)
    private val bad get() = f.claim("bad", 19, 20, IrEffect.PREFERRED).copy(scope = f.ir.preference.copy(dimension = IrDimension.PLACE))

    private fun referenceProgram() = f.program(listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")), "base-ref", prefer = "bad-ref")

    @Test
    fun `QPC01 primary SCOPE error cannot mask invalid source evidence when admitting a local preference proof`() {
        val forged = bad.copy(evidence = listOf(f.ir.evidence().copy(quote = "Synthetic quote absent from trusted source")))
        assertSafe(listOf(base, forged), referenceProgram(), allowOutsideNegative = false)
    }

    @Test
    fun `QPC02 quarantined preference correction cannot silently withdraw a hard negative`() {
        val negative = f.claim("negative", 19, 20, IrEffect.UNAVAILABLE)
        val correction = bad.copy(supersedes = listOf(negative.id))
        val program =
            f.program(
                listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad"), f.ref("negative-ref", negative.id)),
                "base-ref",
                block = "negative-ref",
                prefer = "bad-ref",
            )
        assertSafe(listOf(base, negative, correction), program, allowOutsideNegative = true)
    }

    @Test
    fun `QPC03 a quarantined preferred claim placed in ALLOW role is not a safe preference-only branch`() {
        val program =
            f.program(
                listOf(
                    f.ref("base-ref", "base"),
                    f.ref("bad-ref", "bad"),
                    f.node("root", ScheduleDslOperator.ANY_OF, "base-ref", "bad-ref"),
                ),
                "root",
            )
        assertSafe(listOf(base, bad), program, allowOutsideNegative = false)
    }

    @Test
    fun `QPC04 a shared quarantined preference ref also used as BLOCK cannot receive a preference-only proof`() {
        val program =
            f.program(
                listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")),
                "base-ref",
                block = "bad-ref",
                prefer = "bad-ref",
            )
        assertSafe(listOf(base, bad), program, allowOutsideNegative = false)
    }

    private fun assertSafe(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
        allowOutsideNegative: Boolean,
    ) {
        val document = w.document()
        w.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        w.firstResult(document)["program"] = w.program(program)
        val envelope = parser.parseProviderResponse(w.encode(document), snapshot, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)
        assertTrue(
            envelope.claims.results
                .first()
                .quarantined
                .any { it.conditionId == "bad" },
        )
        assertTrue(
            envelope.claims.results
                .first()
                .conditions
                .none { it.id == "bad" },
        )
        val prepared = ScheduleDslInputPolicy.project(envelope, f.policy)
        val actual = prepared.participants.single { it.participantId == first }
        if (allowOutsideNegative) {
            val forbidden = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T11:00:00Z"))
            assertTrue(
                actual.availableTimes.none { it.startInclusive < forbidden.endExclusive && forbidden.startInclusive < it.endExclusive },
            )
        } else {
            assertTrue(actual.availableTimes.isEmpty())
        }
        assertTrue(actual.explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            listOf(InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))),
            prepared.participants
                .single {
                    it.participantId ==
                        peer
                }.availableTimes,
        )
        val restored = ScheduleDslValidator().validateRestored(envelope, snapshot)
        assertEquals(envelope, restored)
        assertEquals(prepared, ScheduleDslInputPolicy.project(restored, f.policy))
    }
}
