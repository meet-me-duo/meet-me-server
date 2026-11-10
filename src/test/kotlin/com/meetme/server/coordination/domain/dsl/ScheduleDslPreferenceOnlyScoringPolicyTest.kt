package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.domain.matching.ParticipantPreferences
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.support.ScheduleDslSyntheticFixture
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent 2026-11-08 user policy: eligibility first; only availability plus preference earns a bonus. */
class ScheduleDslPreferenceOnlyScoringPolicyTest {
    private val f = ScheduleDslSyntheticFixture
    private val wire = ScheduleDslWireFixture
    private val first = ParticipantId(f.ir.firstParticipant)
    private val peer = ParticipantId(f.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")
    private val parser = StrictScheduleDslContextResponseParser(JsonMapper.builder().findAndAddModules().build())

    @Test
    fun `POS01 preference-only and availability-only yield equal eligibility and zero bonus without rewriting meaning`() {
        val preferred =
            parse(
                listOf(f.claim("p", 19, 21, IrEffect.PREFERRED)),
                f.program(listOf(f.ref("r", "p")), null, prefer = "r"),
            )
        val available = parse(listOf(f.claim("a", 19, 21)), f.program(listOf(f.ref("r", "a")), "r"))
        val prefPrepared = project(preferred)
        val availablePrepared = project(available)
        assertEquals(listOf(window(19, 21)), person(prefPrepared, first).availableTimes)
        assertEquals(availablePrepared.participants, prefPrepared.participants)
        assertTrue(person(prefPrepared, first).explicitPreferences.timeRanges.isEmpty())
        assertTrue(person(availablePrepared, first).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            availablePrepared.evidence.getValue(first).possibleWindows,
            prefPrepared.evidence.getValue(first).possibleWindows,
        )
        val actual = recommend(prefPrepared)
        assertEquals(recommend(availablePrepared), actual)
        assertTrue(actual.options.flatMap { it.variants }.all { it.preferenceCount == 0 })
        assertEquals(
            IrEffect.PREFERRED,
            preferred.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrEffect.AVAILABLE,
            available.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
    }

    @Test
    fun `POS02 availability plus preference earns credit only inside eligibility after hard exclusions`() {
        val claims =
            listOf(
                f.claim("a", 18, 22),
                f.claim("p", 17, 23, IrEffect.PREFERRED),
                f.claim("b", 19, 20, IrEffect.UNAVAILABLE),
            )
        val program = f.program(listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.ref("br", "b")), "ar", "br", "pr")
        val prepared = project(parse(claims, program))
        val expected = listOf(window(18, 19), window(20, 22))
        assertEquals(expected, person(prepared, first).availableTimes)
        assertEquals(expected, person(prepared, first).explicitPreferences.timeRanges)
        assertEquals(expected, prepared.evidence.getValue(first).possibleWindows)
        val actual = recommend(prepared)
        assertEquals(expected, actual.options.map { it.window })
        assertTrue(actual.options.flatMap { it.variants }.all { it.preferenceCount == 1 })
        assertFalse(
            actual.options.any {
                it.window.startInclusive < window(19, 20).endExclusive &&
                    window(19, 20).startInclusive < it.window.endExclusive
            },
        )
    }

    @Test
    fun `POS03 preference-only zeroing is participant-local while another participant keeps a valid bonus`() {
        for (preferenceOnlyFirst in listOf(true, false)) {
            val onlyClaims = listOf(f.claim("only", 18, 22, IrEffect.PREFERRED))
            val onlyProgram = f.program(listOf(f.ref("only-r", "only")), null, prefer = "only-r")
            val bothClaims = listOf(f.claim("base", 18, 22), f.claim("pref", 19, 20, IrEffect.PREFERRED))
            val bothProgram =
                f.program(listOf(f.ref("base-r", "base"), f.ref("pref-r", "pref")), "base-r", prefer = "pref-r")
            val envelope =
                if (preferenceOnlyFirst) {
                    parse(onlyClaims, onlyProgram, secondSource(bothClaims), secondSource(bothProgram))
                } else {
                    parse(bothClaims, bothProgram, secondSource(onlyClaims), secondSource(onlyProgram))
                }
            val prepared = project(envelope)
            val onlyId = if (preferenceOnlyFirst) first else peer
            val bothId = if (preferenceOnlyFirst) peer else first
            assertEquals(listOf(window(18, 22)), person(prepared, onlyId).availableTimes)
            assertEquals(listOf(window(18, 22)), person(prepared, bothId).availableTimes)
            assertTrue(person(prepared, onlyId).explicitPreferences.timeRanges.isEmpty())
            assertEquals(listOf(window(19, 20)), person(prepared, bothId).explicitPreferences.timeRanges)
            val actual = recommend(prepared)
            assertEquals(
                1,
                actual.options
                    .single { it.window == window(19, 20) }
                    .variants
                    .single()
                    .preferenceCount,
            )
            assertTrue(actual.options.flatMap { it.variants }.all { it.preferenceCount <= 1 })
        }
    }

    @Test
    fun `POS04 attendance outranks a valid preference bonus and equal attendance then uses that bonus`() {
        val claims =
            listOf(f.claim("early", 18, 19), f.claim("late", 20, 21), f.claim("pref", 20, 21, IrEffect.PREFERRED))
        val program =
            f.program(
                listOf(
                    f.ref("er", "early"),
                    f.ref("lr", "late"),
                    f.ref("pr", "pref"),
                    f.node("allow", ScheduleDslOperator.ANY_OF, "er", "lr"),
                ),
                "allow",
                prefer = "pr",
            )
        val prepared = project(parse(claims, program))
        val tie = recommend(prepared)
        assertEquals(window(20, 21), tie.options[tie.primaryOptionIndexes.first()].window)
        assertEquals(
            1,
            tie.options
                .single { it.window == window(20, 21) }
                .variants
                .single()
                .preferenceCount,
        )
        val third =
            person(prepared, peer).copy(
                participantId = ParticipantId(UUID(0, 303)),
                availableTimes = listOf(window(18, 19)),
                explicitPreferences = ParticipantPreferences(),
            )
        val attendance = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants + third, zone)
        val best = attendance.options[attendance.primaryOptionIndexes.first()]
        assertEquals(window(18, 19), best.window)
        assertEquals(
            3,
            best.variants
                .single()
                .participantIds.size,
        )
        assertEquals(0, best.variants.single().preferenceCount)
        assertEquals(
            1,
            attendance.options
                .single { it.window == window(20, 21) }
                .variants
                .single()
                .preferenceCount,
        )
    }

    @Test
    fun `POS05 unknown preference remains uncertain without invented availability or extra credit`() {
        val envelope =
            parse(
                listOf(f.ir.condition("unknown", effect = IrEffect.PREFERRED)),
                f.program(listOf(f.ref("u", "unknown")), null, prefer = "u"),
            )
        val prepared = project(envelope)
        assertTrue(person(prepared, first).availableTimes.isEmpty())
        assertTrue(person(prepared, first).explicitPreferences.timeRanges.isEmpty())
        assertTrue(
            prepared.evidence
                .getValue(first)
                .possibleWindows
                .isNotEmpty(),
        )
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(
            null,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertEquals(
            IrEffect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(listOf(window(18, 22)), person(prepared, peer).availableTimes)
        assertTrue(recommend(prepared).options.isEmpty())
    }

    @Test
    fun `POS06 invalid roots or foreign evidence never become default availability or preference credit`() {
        for (fault in listOf("root", "owner", "revision")) {
            val claim = f.claim("p", 18, 22, IrEffect.PREFERRED)
            val damaged =
                when (fault) {
                    "owner" -> claim.copy(evidence = listOf(f.ir.evidence(false)))
                    "revision" -> claim.copy(evidence = claim.evidence.map { it.copy(revision = 4) })
                    else -> claim
                }
            val program = f.program(listOf(f.ref("r", if (fault == "root") "missing" else "p")), null, prefer = "r")
            val envelope = decode(listOf(damaged), program)
            assertTrue(
                envelope.programs.first().program == null &&
                    envelope.programs
                        .first()
                        .problems
                        .isNotEmpty(),
            )
            if (fault != "root") {
                assertEquals(
                    IrProblem.EVIDENCE,
                    envelope.claims.results
                        .first()
                        .quarantined
                        .single()
                        .problem,
                )
            }
            val prepared = project(envelope)
            assertTrue(person(prepared, first).availableTimes.isEmpty())
            assertTrue(person(prepared, first).explicitPreferences.timeRanges.isEmpty())
            assertEquals(listOf(window(18, 22)), person(prepared, peer).availableTimes)
            assertTrue(recommend(prepared).options.isEmpty())
        }
    }

    private fun parse(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
        secondClaims: List<ProposedIrV2Condition>? = null,
        secondProgram: ScheduleDslProgram<ProposedIrEvidence>? = null,
    ) = decode(claims, program, secondClaims, secondProgram).also { envelope ->
        assertTrue(envelope.claims.results.all { it.quarantined.isEmpty() })
        assertTrue(envelope.programs.all { it.program != null && it.problems.isEmpty() })
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
        secondClaims: List<ProposedIrV2Condition>? = null,
        secondProgram: ScheduleDslProgram<ProposedIrEvidence>? = null,
    ): ScheduleDslEnvelope {
        val document = wire.document()
        wire.firstResult(document)["conditions"] = claims.map(f.ir::wire)
        wire.firstResult(document)["program"] = wire.program(program)
        if (secondClaims != null) {
            val second = (document.getValue("results") as MutableList<MutableMap<String, Any?>>)[1]
            second["conditions"] = secondClaims.map(f.ir::wire)
            second["program"] = wire.program(requireNotNull(secondProgram))
        }
        return parser.parse(
            wire.encode(document),
            f.ir.snapshot(),
            SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
            SCHEDULE_DSL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
        )
    }

    private fun secondSource(claims: List<ProposedIrV2Condition>) = claims.map { it.copy(evidence = listOf(f.ir.evidence(false))) }

    private fun secondSource(program: ScheduleDslProgram<ProposedIrEvidence>) =
        program.copy(nodes = program.nodes.map { it.copy(evidence = listOf(f.ir.evidence(false))) })

    private fun project(envelope: ScheduleDslEnvelope) = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)

    private fun person(
        prepared: ScheduleDslPreparedInputs,
        id: ParticipantId,
    ) = prepared.participants.single { it.participantId == id }

    private fun recommend(prepared: ScheduleDslPreparedInputs) =
        RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, zone)

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        LocalDateTime.of(2027, 1, 8, start, 0).atZone(zone).toInstant(),
        LocalDateTime.of(2027, 1, 8, end, 0).atZone(zone).toInstant(),
    )
}
