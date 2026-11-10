package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generic synthetic regression, not either unavailable original experiment or a language-accuracy claim. */
class ScheduleDslQuarantinedPreferenceIsolationTest {
    private val f = ScheduleDslSyntheticFixture
    private val w = ScheduleDslWireFixture
    private val snapshot = ScheduleDslQuestionContextFixture.snapshot()
    private val parser = ScheduleDslProviderContract(JsonMapper.builder().findAndAddModules().build())
    private val participant = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val friday = range("2027-01-08T09:00:00Z", "2027-01-08T13:00:00Z")
    private val base get() = f.claim("base", 18, 22)
    private val invalidPreference get() =
        f
            .claim(
                "bad",
                19,
                20,
                IrEffect.PREFERRED,
            ).copy(scope = f.ir.preference.copy(dimension = IrDimension.PLACE))

    @ParameterizedTest
    @ValueSource(strings = ["separate_root", "prefer_wrapper"])
    fun `QP01 invalid PLACE preference stays quarantined without erasing verified independent REMOTE time`(structure: String) {
        val program =
            if (structure == "separate_root") {
                f.program(listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")), "base-ref", prefer = "bad-ref")
            } else {
                f.program(
                    listOf(
                        f.ref("base-ref", "base"),
                        f.ref("bad-ref", "bad"),
                        f.node("root", ScheduleDslOperator.PREFER, "base-ref", "bad-ref"),
                    ),
                    "root",
                )
            }
        val parsed = parse(listOf(base, invalidPreference), program)
        assertQuarantine(parsed, IrProblem.SCOPE)
        assertEquals(
            listOf(f.ir.verified(base)),
            parsed.claims.results
                .first()
                .conditions,
        )
        assertProjectionAndRestore(parsed, listOf(friday))
    }

    @Test
    fun `QP02 isolating bad preference cannot turn known nonprecise AVAILABLE into numeric availability`() {
        val nonprecise = f.ir.condition("base")
        val program = f.program(listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")), "base-ref", prefer = "bad-ref")
        val parsed = parse(listOf(nonprecise, invalidPreference), program)
        assertQuarantine(parsed, IrProblem.SCOPE)
        val kept =
            parsed.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, kept.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, kept.temporalResolution)
        assertProjectionAndRestore(parsed, emptyList())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "selected"])
    fun `QP03 preference isolation cannot union epistemic branches and must retain explicit branch selection`(selection: String) {
        val left = f.claim("left", 18, 19).copy(alternativeGroup = "g", alternative = "left")
        val right = f.claim("right", 21, 22).copy(alternativeGroup = "g", alternative = "right")
        val choice =
            f
                .node(
                    "choice",
                    ScheduleDslOperator.CHOICE,
                    "left-ref",
                    "right-ref",
                ).copy(choiceGroup = "g", branchLabels = listOf("left", "right"))
        val program =
            f.program(
                listOf(f.ref("left-ref", "left"), f.ref("right-ref", "right"), choice, f.ref("bad-ref", "bad")),
                "choice",
                prefer = "bad-ref",
            )
        val parsed = parse(listOf(left, right, invalidPreference), program)
        assertQuarantine(parsed, IrProblem.SCOPE)
        val policy =
            if (selection ==
                "selected"
            ) {
                f.policy.copy(alternativeChoices = listOf(IrAlternativeChoice(f.ir.first, "g", "left")))
            } else {
                f.policy
            }
        val expected = if (selection == "selected") listOf(range("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z")) else emptyList()
        assertProjectionAndRestore(parsed, expected, policy)
    }

    @Test
    fun `QP04 invalid UNAVAILABLE with untrusted scope remains conservative rather than being skipped`() {
        val negative = f.claim("bad", 19, 20, IrEffect.UNAVAILABLE).copy(scope = IrScope(IrDimension.TIME, f.ir.preference.start, null))
        val program = f.program(listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")), "base-ref", block = "bad-ref")
        val parsed = parse(listOf(base, negative), program)
        assertQuarantine(parsed, IrProblem.SCOPE)
        assertProjectionAndRestore(parsed, emptyList())
    }

    @Test
    fun `QP05 quarantined excluded operand cannot be deleted to make EXCEPT authorize its entire base`() {
        val negative = f.claim("bad", 19, 20, IrEffect.UNAVAILABLE).copy(scope = IrScope(IrDimension.TIME))
        val program =
            f.program(
                listOf(
                    f.ref("base-ref", "base"),
                    f.ref("bad-ref", "bad"),
                    f.node("root", ScheduleDslOperator.EXCEPT, "base-ref", "bad-ref"),
                ),
                "root",
            )
        val parsed = parse(listOf(base, negative), program)
        assertQuarantine(parsed, IrProblem.SCOPE)
        assertProjectionAndRestore(parsed, emptyList())
    }

    @Test
    fun `QP06 unparseable polarity cannot be trusted merely because the AST puts its ref under preference root`() {
        val program = f.program(listOf(f.ref("base-ref", "base"), f.ref("bad-ref", "bad")), "base-ref", prefer = "bad-ref")
        val document = document(listOf(base, invalidPreference), program)

        @Suppress("UNCHECKED_CAST")
        val claims = w.firstResult(document).getValue("conditions") as List<MutableMap<String, Any?>>
        claims.last()["effect"] = "SYNTHETIC_UNKNOWN_POLARITY"
        val parsed = parser.parseProviderResponse(w.encode(document), snapshot, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)
        assertQuarantine(parsed, IrProblem.SHAPE)
        assertProjectionAndRestore(parsed, emptyList())
    }

    private fun document(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ): MutableMap<String, Any?> {
        val document = w.document()
        w.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        w.firstResult(document)["program"] = w.program(program)
        return document
    }

    private fun parse(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ) = parser.parseProviderResponse(w.encode(document(claims, program)), snapshot, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)

    private fun assertQuarantine(
        envelope: ScheduleDslEnvelope,
        problem: IrProblem,
    ) {
        val quarantine =
            envelope.claims.results
                .first()
                .quarantined
                .single()
        assertEquals("bad", quarantine.conditionId)
        assertEquals(problem, quarantine.problem)
        assertEquals(null, quarantine.scope)
        assertTrue(
            envelope.claims.results
                .first()
                .conditions
                .none { it.id == "bad" },
        )
    }

    private fun assertProjectionAndRestore(
        envelope: ScheduleDslEnvelope,
        expected: List<InstantTimeRange>,
        policy: IrMatchingPolicy = f.policy,
    ) {
        val prepared = ScheduleDslInputPolicy.project(envelope, policy)
        val actual = prepared.participants.single { it.participantId == participant }
        assertEquals(expected, actual.availableTimes)
        assertTrue(actual.explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(friday), prepared.participants.single { it.participantId == peer }.availableTimes)
        val restored = ScheduleDslValidator().validateRestored(envelope, snapshot)
        assertEquals(envelope, restored)
        assertEquals(prepared, ScheduleDslInputPolicy.project(restored, policy))
    }

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))
}
