package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

/** Mock typed model proposals only: no raw-NL accuracy, fixed relative-hour rule or live/personal source. */
class ScheduleDslApprovedModelContextTest {
    private val parser = StrictScheduleDslApprovedTermsResponseParser(JsonMapper.builder().findAndAddModules().build())
    private val validator = ScheduleDslApprovedTermsValidator()

    // Independent fixture, so preset TODO cannot mask parser/normalization RED in the other seven invocations.
    private val policy =
        IrMatchingPolicy(
            ApprovedScheduleDslMatchingPolicy.VERSION,
            setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED),
            emptyList(),
            true,
        )

    private fun input(text: String) = F.input(text).copy(policy = policy)

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    private fun participant(envelope: ScheduleDslApprovedTermsEnvelope) =
        ScheduleDslInputPolicy
            .projectWithPreferredAvailability(envelope.calculationProjection, policy)
            .participants
            .single { it.participantId == ParticipantId(F.ir.firstParticipant) }

    private fun parse(
        input: com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput,
        claim: com.meetme.server.submission.domain.ir.ProposedIrV2Condition,
        program: ScheduleDslProgram<com.meetme.server.submission.domain.ir.ProposedIrEvidence> =
            F.program(listOf(F.ref(input, "root", "half")), "root"),
        extra: List<com.meetme.server.submission.domain.ir.ProposedIrV2Condition> = emptyList(),
    ): ScheduleDslApprovedTermsEnvelope {
        val proposal =
            F.proposal(
                input,
                listOf(claim) + extra,
                program,
                listOf(T.ref(input, ApprovedKoreanTimeTerm.NIGHT, "밤")),
            )
        return parser.parse(
            F.json(F.document(proposal)),
            F.snapshot(input),
            "schedule-dsl-response-3",
            "schedule-ir-product-4",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )
    }

    private fun inferred(
        input: com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput,
        start: String,
        end: String,
    ) = F.claim(input, scope = F.scope(start, end)).copy(
        interpretation = IrInterpretation.INFERRED,
        temporalResolution = IrTemporalResolution.NON_PRECISE,
    )

    @Test
    fun `AMC01 explicit approved contextual policy retains selected choices without unresolved eligibility`() {
        val choice = IrAlternativeChoice(F.ir.first, "synthetic", "left")
        val selected = ApprovedScheduleDslMatchingPolicy.contextual(listOf(choice))
        assertEquals("schedule-approved-availability-1", ApprovedScheduleDslMatchingPolicy.VERSION)
        assertEquals(ApprovedScheduleDslMatchingPolicy.VERSION, selected.version)
        assertEquals(setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED), selected.eligibleInterpretations)
        assertEquals(listOf(choice), selected.alternativeChoices)
        assertTrue(selected.allowExplicitUnconstrained)
        assertTrue(ApprovedScheduleDslMatchingPolicy.contextual().alternativeChoices.isEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        "밤보다 조금 이른 7시부터 가능,2027-01-08T19:00,2027-01-09T06:00",
        "밤이지만 11시 전까지 가능,2027-01-08T20:00,2027-01-08T23:00",
        // 19:30 is this synthetic model proposal, not a server rule for the nonnumeric relative phrase.
        "밤인데 평소보다 좀 일찍,2027-01-08T19:30,2027-01-09T06:00",
    )
    fun `AMC02 different complete model-context proposals survive without a fixed nineteen hour rule`(
        context: String,
        start: String,
        end: String,
    ) {
        val input = input("🙂 2027-01-08 $context.")
        val original = inferred(input, start, end)
        val envelope = parse(input, original)
        val declaration =
            envelope.declaration.claims.results
                .first()
                .conditions
                .single()
        val projection =
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrInterpretation.INFERRED, declaration.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declaration.temporalResolution)
        assertEquals(original.scope, declaration.scope)
        assertEquals(IrInterpretation.INFERRED, projection.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projection.temporalResolution)
        assertEquals(original.scope, projection.scope)
        assertEquals(IrEffect.AVAILABLE, projection.effect)
        val witness = envelope.termApplications.single()
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, witness.derivation)
        assertEquals(original.scope, witness.declaredScope)
        assertEquals(original.scope, witness.resolvedScope)
        assertEquals(listOf(window(start, end)), participant(envelope).availableTimes)
        assertEquals(
            IrCalculationStatus.PARTIAL,
            ScheduleDslCalculator.calculateApprovedTerms(envelope, F.snapshot(input), policy, input.meetingMode).status,
        )
        assertEquals(envelope, validator.validateRestored(envelope, F.snapshot(input)))
    }

    @Test
    fun `AMC03 explicit negative overrides inferred contextual availability`() {
        val input = input("🙂 2027-01-08 밤 7시부터 가능하지만 20:00부터 21:00까지는 불가.")
        val original = inferred(input, "2027-01-08T19:00", "2027-01-09T06:00")
        val negative = F.claim(input, "blocked", IrEffect.UNAVAILABLE, F.scope("2027-01-08T20:00", "2027-01-08T21:00"))
        val program = F.program(listOf(F.ref(input, "a", "half"), F.ref(input, "b", "blocked")), "a", block = "b")
        val envelope = parse(input, original, program, listOf(negative))
        assertEquals(
            listOf(
                window("2027-01-08T19:00", "2027-01-08T20:00"),
                window("2027-01-08T21:00", "2027-01-09T06:00"),
            ),
            participant(envelope).availableTimes,
        )
        assertEquals(
            IrEffect.UNAVAILABLE,
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "blocked" }
                .effect,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["derivation", "missing-witness"])
    fun `AMC04 restored origin is rederived from the declaration rather than trusting or dropping stored provenance`(change: String) {
        val input = input("🙂 2027-01-08 밤 7시부터 가능.")
        val valid = parse(input, inferred(input, "2027-01-08T19:00", "2027-01-09T06:00"))
        val corrupted =
            if (change == "missing-witness") {
                valid.copy(termApplications = emptyList())
            } else {
                valid.copy(
                    termApplications = valid.termApplications.map { it.copy(derivation = ApprovedTimeTermDerivation.SERVER_VOCABULARY) },
                )
            }
        assertFailsWith<RuntimeException> { validator.validateRestored(corrupted, F.snapshot(input)) }
    }

    @Test
    fun `AMC05 inferred precise with a typed term cannot bypass context provenance through numeric priority`() {
        val input = input("🙂 2027-01-08 밤 7시부터 가능.")
        val proposed =
            inferred(input, "2027-01-08T19:00", "2027-01-09T06:00")
                .copy(temporalResolution = IrTemporalResolution.PRECISE_INTERVAL)
        val envelope = parse(input, proposed)
        assertTrue(envelope.termApplications.isEmpty())
        assertTrue(participant(envelope).availableTimes.isEmpty())
        val program = envelope.calculationProjection.programs.first()
        assertTrue(program.program == null || program.problems.isNotEmpty())
    }

    @Test
    fun `AMC06 incomplete numeric proposal cannot fill the missing endpoint from vocabulary or search bounds`() {
        val input = input("🙂 2027-01-08 밤 7시부터 가능.")
        val proposed =
            inferred(input, "2027-01-08T19:00", "2027-01-09T06:00").let {
                it.copy(scope = requireNotNull(it.scope).copy(end = null))
            }
        val envelope = parse(input, proposed)
        assertTrue(envelope.termApplications.isEmpty())
        assertTrue(participant(envelope).availableTimes.isEmpty())
        assertTrue(
            envelope.calculationProjection.claims.results
                .first()
                .quarantined
                .isNotEmpty() ||
                envelope.calculationProjection.programs
                    .first()
                    .problems
                    .isNotEmpty(),
        )
        assertNull(envelope.termApplications.firstOrNull())
    }
}
