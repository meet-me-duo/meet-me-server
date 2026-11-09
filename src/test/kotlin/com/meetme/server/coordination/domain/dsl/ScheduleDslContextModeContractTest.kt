package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslApprovedTermsProviderContract
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.ProposedScheduleModeRestriction
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Native synthetic declarations only; no raw-language classifier, inferred owner confirmation, provider or persistence oracle. */
class ScheduleDslContextModeContractTest {
    private val validator = ScheduleDslContextModeValidator()
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)
    private val third = ParticipantId(UUID(0, 303))
    private val policy =
        F.policy.copy(
            version = "schedule-context-mode-1",
            eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED),
            allowExplicitUnconstrained = true,
        )
    private val thursday = window("2027-01-07T18:00", "2027-01-07T22:00")
    private val friday = window("2027-01-08T18:00", "2027-01-08T22:00")

    @Test
    fun `CM01 complete inferred context without a vocabulary word is calculable while original evidence remains unchanged`() {
        val input = input("🙂 2027-01-08에는 6시 반쯤부터 10시까지 가능할 듯해.")
        val base = base(input, listOf(inferred(input)), allow(input, "context"))
        val envelope = validator.validateDeclaration(base, emptyList(), F.snapshot(input))
        val calculated = calculate(input, envelope, MeetingMode.REMOTE)
        val expected = listOf(window("2027-01-08T18:30", "2027-01-08T22:00"))
        assertEquals("schedule-ir-product-5", envelope.semanticVersion)
        assertEquals(base, envelope.base)
        assertTrue(base.termApplications.isEmpty())
        val declared =
            base.declaration.claims.results
                .first()
                .conditions
                .single()
        val projected =
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrInterpretation.INFERRED, declared.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertEquals(IrInterpretation.INFERRED, projected.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projected.temporalResolution)
        assertEquals(declared.scope, projected.scope)
        assertEquals(declared.evidence, projected.evidence)
        val application = envelope.contextApplications.single()
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, application.derivation)
        assertEquals(declared.scope, application.declaredScope)
        assertEquals(declared.scope, application.resolvedScope)
        assertEquals(declared.evidence, application.evidence)
        assertEquals(expected, times(calculated, MeetingMode.REMOTE, first))
        assertEquals(
            expected,
            calculated.candidates.candidates
                .single()
                .timeRanges,
        )
        assertEquals(expected, calculated.recommendations.options.map { it.window })
        assertEquals(IrCalculationStatus.PARTIAL, calculated.status)
        assertEquals(input.inputs.first().rawText, F.snapshot(input).sourceText(F.ir.first, "original"))
        assertFalse(
            input.inputs
                .first()
                .rawText
                .contains("오후"),
        )
        val selected = ScheduleDslContextModePolicy.contextual()
        assertEquals(policy, selected)
    }

    @Test
    fun `CM02 the old C3 control retains its own frozen policy nonprecise support and unchanged request bytes`() {
        val input = input("🙂 2027-01-08에는 6시 반쯤부터 10시까지 가능할 듯해.")
        val oldPolicy = ApprovedScheduleDslMatchingPolicy.contextual()
        val oldInput = input.copy(policy = oldPolicy)
        val oldBase = base(oldInput, listOf(inferred(oldInput)), allow(oldInput, "context"))
        val currentBase = base(input, listOf(inferred(input)), allow(input, "context"))
        assertEquals("schedule-approved-availability-1", oldBase.declaration.context.policyVersion)
        assertEquals(policy.eligibleInterpretations, oldPolicy.eligibleInterpretations)
        assertEquals("schedule-context-mode-1", currentBase.declaration.context.policyVersion)
        val contract = ScheduleDslApprovedTermsProviderContract(JsonMapper.builder().findAndAddModules().build())
        val capsule = F.capsule(oldInput)
        val before = contract.requestBytes(capsule, MeetingMode.REMOTE)
        val old = ScheduleDslCalculator.calculateApprovedTerms(oldBase, F.snapshot(oldInput), oldInput.policy, MeetingMode.REMOTE)
        assertTrue(
            old.prepared.participants
                .single { it.participantId == first }
                .availableTimes
                .isEmpty(),
        )
        assertTrue(old.candidates.candidates.isEmpty())
        assertTrue(oldBase.termApplications.isEmpty())
        val current = validator.validateDeclaration(currentBase, emptyList(), F.snapshot(input))
        calculate(input, current, MeetingMode.REMOTE)
        assertEquals(currentBase, current.base)
        assertContentEquals(before, contract.requestBytes(capsule, MeetingMode.REMOTE))
        val oldAgain = ScheduleDslCalculator.calculateApprovedTerms(oldBase, F.snapshot(oldInput), oldInput.policy, MeetingMode.REMOTE)
        assertEquals(old, oldAgain)
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslContextModeCalculator.calculate(
                current,
                F.snapshot(input),
                policy.copy(version = "schedule-approved-availability-1"),
                MeetingMode.REMOTE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validateDeclaration(oldBase, emptyList(), F.snapshot(input))
        }
    }

    @Test
    fun `CM03 unresolved interpretation and missing endpoint never acquire a complete interval from context or search bounds`() {
        val input = input("🙂 2027-01-08에는 6시 반쯤 가능할 수도 있으나 종료 시각은 미정.")
        val complete = inferred(input)
        val cases =
            listOf(
                complete.copy(interpretation = IrInterpretation.UNRESOLVED),
                complete.copy(scope = requireNotNull(complete.scope).copy(end = null)),
            )
        for (claim in cases) {
            val base = base(input, listOf(claim), allow(input, "context"))
            val envelope = validator.validateDeclaration(base, emptyList(), F.snapshot(input))
            val result = calculate(input, envelope, MeetingMode.REMOTE)
            assertEquals(base, envelope.base)
            assertTrue(envelope.contextApplications.isEmpty())
            assertTrue(times(result, MeetingMode.REMOTE, first).isEmpty())
            assertEquals(listOf(thursday, friday), times(result, MeetingMode.REMOTE, peer))
            assertTrue(result.candidates.candidates.isEmpty())
        }
    }

    @Test
    fun `CM04 contextual numeric projection cannot cancel an explicit negative and preserves independent valid availability`() {
        val input = input("🙂 2027-01-08 18:00부터 22:00까지 가능. 19:00부터 20:00까지 불가를 철회하지 않았음. 6시 반부터 가능할 듯해.")
        val claims =
            listOf(
                F.claim(input, "base", scope = F.scope("2027-01-08T18:00", "2027-01-08T22:00")),
                F.claim(input, "ban", IrEffect.UNAVAILABLE, F.scope("2027-01-08T19:00", "2027-01-08T20:00")),
                inferred(input).copy(supersedes = listOf("ban")),
            )
        val program =
            F
                .program(
                    listOf(
                        F.ref(input, "b", "base"),
                        F.ref(input, "c", "context"),
                        F.node(input, "all", ScheduleDslOperator.ANY_OF, listOf("b", "c")),
                    ),
                    "all",
                ).copy(historyConditionIds = listOf("ban"))
        val envelope = validator.validateDeclaration(base(input, claims, program), emptyList(), F.snapshot(input))
        val result = calculate(input, envelope, MeetingMode.REMOTE)
        assertEquals(
            listOf(
                window("2027-01-08T18:00", "2027-01-08T19:00"),
                window("2027-01-08T20:00", "2027-01-08T22:00"),
            ),
            times(result, MeetingMode.REMOTE, first),
        )
        val evidence =
            result.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .evidence
                .getValue(first)
        assertTrue(ScheduleDslProblem.RELATION_SCOPE in evidence.programProblems)
        assertEquals(IrConditionDisposition.WITHHELD, evidence.conditions.single { it.conditionId == "context" }.disposition)
        assertEquals(listOf(thursday, friday), times(result, MeetingMode.REMOTE, peer))
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
    }

    @Test
    fun `CM05 a Thursday remote-only hard predicate cannot be ORed away by broad availability in EITHER`() {
        val input = input()
        val envelope = validator.validateDeclaration(base(input), listOf(restriction(input)), F.snapshot(input))
        val result = calculate(input, envelope, MeetingMode.EITHER)
        assertEquals(setOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON), result.preparedByMode.keys)
        assertEquals(listOf(thursday, friday), times(result, MeetingMode.REMOTE, first))
        assertEquals(listOf(friday), times(result, MeetingMode.IN_PERSON, first))
        val candidates = result.candidates.candidates.associateBy { it.meetingMode }
        assertEquals(listOf(thursday, friday), candidates.getValue(MeetingMode.REMOTE).timeRanges)
        assertEquals(listOf(friday), candidates.getValue(MeetingMode.IN_PERSON).timeRanges)
        assertTrue(candidates.values.all { it.participantIds.toSet() == setOf(first, peer) })
        val options = result.recommendations.options.associateBy { it.window }
        assertEquals(
            setOf(MeetingMode.REMOTE),
            options
                .getValue(thursday)
                .variants
                .map { it.meetingMode }
                .toSet(),
        )
        assertEquals(
            setOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON),
            options
                .getValue(friday)
                .variants
                .map { it.meetingMode }
                .toSet(),
        )
        assertTrue(result.candidates.candidates.all { it.representativeArea == null && it.representativePlace == null })
        assertEquals(
            IrConditionDisposition.APPLIED,
            result.modeEvidence
                .getValue(first)
                .single()
                .disposition,
        )
    }

    @Test
    fun `CM06 category and existing candidate subset policy are distinct from per-mode recommendation groups`() {
        val input = input("🙂 2027-01-07 18:00부터 22:00까지 가능. 2027-01-07에는 비대면만 가능.", third = true)
        val firstOnlyThursday = F.claim(input, "thu", scope = F.scope("2027-01-07T18:00", "2027-01-07T22:00"))
        val envelope =
            validator.validateDeclaration(
                base(input, listOf(firstOnlyThursday), allow(input, "thu")),
                listOf(restriction(input)),
                F.snapshot(input),
            )
        val offline = calculate(input, envelope, MeetingMode.IN_PERSON)
        assertEquals(setOf(MeetingMode.IN_PERSON), offline.preparedByMode.keys)
        val subset = offline.candidates.candidates.single()
        assertEquals(PlanType.C, subset.planType)
        assertEquals(MeetingMode.IN_PERSON, subset.meetingMode)
        assertEquals(setOf(peer, third), subset.participantIds.toSet())
        assertEquals(listOf(thursday, friday), subset.timeRanges)
        assertTrue(
            offline.recommendations.options.flatMap { it.variants }.all {
                it.meetingMode == MeetingMode.IN_PERSON && it.participantIds.toSet() == setOf(peer, third)
            },
        )
        val either = calculate(input, envelope, MeetingMode.EITHER)
        assertEquals(1, either.candidates.candidates.size)
        val full = either.candidates.candidates.single()
        assertEquals(MeetingMode.REMOTE, full.meetingMode)
        assertEquals(setOf(first, peer, third), full.participantIds.toSet())
        assertEquals(listOf(thursday), full.timeRanges)
        val thuVariants =
            either.recommendations.options
                .single { it.window == thursday }
                .variants
        assertTrue(thuVariants.any { it.meetingMode == MeetingMode.REMOTE && it.participantIds.toSet() == setOf(first, peer, third) })
        assertTrue(thuVariants.any { it.meetingMode == MeetingMode.IN_PERSON && it.participantIds.toSet() == setOf(peer, third) })
        assertTrue(
            either.recommendations.options.single { it.window == friday }.variants.all {
                it.participantIds.toSet() == setOf(peer, third)
            },
        )
    }

    @Test
    fun `CM07 quoted and retracted mode declarations preserve history without constraining either mode`() {
        val input = input()
        for (assertion in listOf(IrAssertion.QUOTED_OTHER, IrAssertion.RETRACTED)) {
            val envelope =
                validator.validateDeclaration(
                    base(input),
                    listOf(restriction(input).copy(assertion = assertion)),
                    F.snapshot(input),
                )
            val result = calculate(input, envelope, MeetingMode.EITHER)
            assertEquals(assertion, envelope.modeRestrictions.single().assertion)
            for (mode in listOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON)) {
                assertEquals(listOf(thursday, friday), times(result, mode, first))
            }
            assertEquals(
                IrConditionDisposition.EXCLUDED_ASSERTION,
                result.modeEvidence
                    .getValue(first)
                    .single()
                    .disposition,
            )
        }
    }

    @Test
    fun `CM08 unknown mode and unknown time scope cannot authorize an otherwise forbidden precise mode`() {
        val input = input()
        val unknownTime =
            restriction(input).copy(
                scope = null,
                timeInterpretation = IrInterpretation.UNRESOLVED,
                temporalResolution = IrTemporalResolution.NON_PRECISE,
            )
        val timeResult =
            calculate(
                input,
                validator.validateDeclaration(base(input), listOf(unknownTime), F.snapshot(input)),
                MeetingMode.EITHER,
            )
        assertTrue(times(timeResult, MeetingMode.IN_PERSON, first).isEmpty())
        assertEquals(listOf(thursday, friday), times(timeResult, MeetingMode.REMOTE, first))
        assertEquals(
            IrConditionDisposition.WITHHELD,
            timeResult.modeEvidence
                .getValue(first)
                .single()
                .disposition,
        )
        assertEquals(IrCalculationStatus.PARTIAL, timeResult.status)
        val unknownMode = restriction(input).copy(modeInterpretation = IrInterpretation.UNRESOLVED)
        val modeResult =
            calculate(
                input,
                validator.validateDeclaration(base(input), listOf(unknownMode), F.snapshot(input)),
                MeetingMode.EITHER,
            )
        for (mode in listOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON)) {
            assertEquals(listOf(friday), times(modeResult, mode, first))
            assertEquals(listOf(thursday, friday), times(modeResult, mode, peer))
            assertEquals(
                listOf(thursday, friday),
                modeResult.preparedByMode
                    .getValue(mode)
                    .evidence
                    .getValue(first)
                    .possibleWindows,
            )
        }
        assertEquals(IrCalculationStatus.PARTIAL, modeResult.status)
    }

    @Test
    fun `CM09 native source revision quote and frozen context mismatches fail closed rather than become an unrestricted mode`() {
        val input = input()
        val base = base(input)
        val valid = restriction(input)
        for (proof in listOf(valid.evidence.single().copy(revision = 999), valid.evidence.single().copy(quote = "not present"))) {
            assertFailsWith<IllegalArgumentException> {
                validator.validateDeclaration(base, listOf(valid.copy(evidence = listOf(proof))), F.snapshot(input))
            }
        }
        val changed =
            input.copy(
                inputs =
                    input.inputs.mapIndexed { index, value ->
                        if (index == 0) value.copy(rawText = value.rawText + " changed native revision content") else value
                    },
            )
        for (stale in listOf(changed, input.copy(timeZone = "UTC"))) {
            assertFailsWith<IllegalArgumentException> {
                validator.validateDeclaration(base, listOf(valid), F.snapshot(stale))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validateDeclaration(base, listOf(valid.copy(permittedModes = setOf(MeetingMode.EITHER))), F.snapshot(input))
        }
    }

    @Test
    fun `CM10 restored contextual witness and mode evidence must replay against the frozen declaration`() {
        val input = input("🙂 2027-01-08에는 6시 반쯤부터 10시까지 가능할 듯해. 2027-01-07 18:00부터 22:00까지는 비대면만 가능.")
        val envelope =
            validator.validateDeclaration(
                base(input, listOf(inferred(input)), allow(input, "context")),
                listOf(restriction(input)),
                F.snapshot(input),
            )
        assertEquals(envelope, validator.validateRestored(envelope, F.snapshot(input)))
        assertFailsWith<IllegalArgumentException> {
            validator.validateRestored(envelope.copy(contextApplications = emptyList()), F.snapshot(input))
        }
        val forgedOrigin =
            envelope.copy(
                contextApplications =
                    envelope.contextApplications.map {
                        it.copy(derivation = ApprovedTimeTermDerivation.SERVER_VOCABULARY)
                    },
            )
        assertFailsWith<IllegalArgumentException> { validator.validateRestored(forgedOrigin, F.snapshot(input)) }
        val forgedEvidence =
            envelope.copy(
                modeRestrictions =
                    envelope.modeRestrictions.map {
                        it.copy(evidence = it.evidence.map { span -> span.copy(contentSha256 = "0".repeat(64)) })
                    },
            )
        assertFailsWith<IllegalArgumentException> { validator.validateRestored(forgedEvidence, F.snapshot(input)) }
    }

    private fun input(
        text: String = "🙂 2027-01-07과 2027-01-08 18:00부터 22:00까지 가능. 2027-01-07에는 비대면만 가능.",
        third: Boolean = false,
    ): ScheduleDslOfflineInput {
        val old = F.input(text)
        val peerText = "2027-01-07과 2027-01-08 18:00부터 22:00까지 가능."
        val inputs = old.inputs.mapIndexed { index, value -> if (index == 1) value.copy(rawText = peerText) else value }
        return old.copy(
            policy = policy,
            inputs =
                inputs +
                    if (third) {
                        listOf(
                            inputs[1].copy(
                                submissionId = UUID(0, 403),
                                submissionVersionId = UUID(0, 103),
                                participantId = UUID(0, 303),
                            ),
                        )
                    } else {
                        emptyList()
                    },
        )
    }

    private fun inferred(input: ScheduleDslOfflineInput) =
        F
            .claim(
                input,
                "context",
                IrEffect.AVAILABLE,
                F.scope("2027-01-08T18:30", "2027-01-08T22:00"),
            ).copy(interpretation = IrInterpretation.INFERRED, temporalResolution = IrTemporalResolution.NON_PRECISE)

    private fun allow(
        input: ScheduleDslOfflineInput,
        id: String,
    ) = F.program(listOf(F.ref(input, "root", id)), "root")

    private fun base(
        input: ScheduleDslOfflineInput,
        firstClaims: List<ProposedIrV2Condition>? = null,
        firstProgram: ScheduleDslProgram<ProposedIrEvidence>? = null,
    ): ScheduleDslApprovedTermsEnvelope {
        val sets =
            input.inputs.mapIndexed { index, _ ->
                firstClaims?.takeIf { index == 0 } ?: listOf(
                    F.claim(input, "thu", scope = F.scope("2027-01-07T18:00", "2027-01-07T22:00"), index = index),
                    F.claim(input, "fri", scope = F.scope("2027-01-08T18:00", "2027-01-08T22:00"), index = index),
                )
            }
        val programs =
            input.inputs.mapIndexed { index, item ->
                ProposedParticipantScheduleProgram(
                    item.submissionVersionId,
                    firstProgram?.takeIf { index == 0 } ?: F.program(
                        listOf(
                            F.ref(input, "t", "thu", index),
                            F.ref(input, "f", "fri", index),
                            F.node(input, "root", ScheduleDslOperator.ANY_OF, listOf("t", "f"), index = index),
                        ),
                        "root",
                    ),
                )
            }
        val proposal = F.proposal(input, sets.first(), requireNotNull(programs.first().program), terms = emptyList())
        val complete =
            proposal.copy(
                base =
                    proposal.base.copy(
                        claims =
                            proposal.base.claims.copy(
                                results =
                                    input.inputs.mapIndexed { index, item ->
                                        ProposedIrV2Result(item.submissionVersionId, sets[index])
                                    },
                            ),
                        programs = programs,
                    ),
            )
        return ScheduleDslApprovedTermsValidator().validate(complete, F.snapshot(input))
    }

    private fun restriction(input: ScheduleDslOfflineInput) =
        ProposedScheduleModeRestriction(
            F.ir.first,
            "remote-thu",
            F.scope("2027-01-07T18:00", "2027-01-07T22:00"),
            setOf(MeetingMode.REMOTE),
            IrInterpretation.EXPLICIT,
            IrInterpretation.EXPLICIT,
            IrTemporalResolution.PRECISE_INTERVAL,
            listOf(F.evidence(input, input.inputs.first().rawText)),
        )

    private fun calculate(
        input: ScheduleDslOfflineInput,
        envelope: ScheduleDslContextModeEnvelope,
        mode: MeetingMode,
    ) = ScheduleDslContextModeCalculator.calculate(envelope, F.snapshot(input), policy, mode)

    private fun times(
        result: ScheduleDslContextModeCalculation,
        mode: MeetingMode,
        participant: ParticipantId,
    ) = result.preparedByMode
        .getValue(mode)
        .participants
        .single { it.participantId == participant }
        .availableTimes

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )
}
