package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

class ScheduleDslApprovedTermsValidatorTest {
    private val validator = ScheduleDslApprovedTermsValidator()
    private val first = ParticipantId(F.ir.firstParticipant)

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )

    private fun times(envelope: ScheduleDslApprovedTermsEnvelope) =
        ScheduleDslInputPolicy
            .project(envelope.calculationProjection, F.policy)
            .participants
            .single { it.participantId == first }
            .availableTimes

    @Test
    fun `AHT01 server applies afternoon while preserving a source-bound normalization witness`() {
        val proposal = F.proposal()
        val result = validator.validate(proposal, F.snapshot())
        assertEquals("schedule-ir-product-4", result.semanticVersion)
        assertEquals("ko-time-terms-1", result.vocabularyVersion)
        assertEquals(listOf(window("2027-01-08T12:00", "2027-01-09T00:00")), times(result))
        val original =
            proposal.base.claims.results
                .first()
                .conditions
                .single()
        val normalized =
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        val declaration =
            result.declaration.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrTemporalResolution.NON_PRECISE, declaration.temporalResolution)
        assertEquals(original.scope, declaration.scope)
        assertEquals(original.effect, normalized.effect)
        assertEquals(original.interpretation, normalized.interpretation)
        assertEquals(original.assertion, normalized.assertion)
        assertEquals(original.supersedes, normalized.supersedes)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, normalized.temporalResolution)
        val witness = result.termApplications.single()
        assertEquals(F.ir.first, witness.submissionVersionId)
        assertEquals("half", witness.conditionId)
        assertEquals(ApprovedKoreanTimeTerm.PM, witness.term)
        assertEquals(F.day, witness.localDate)
        assertEquals(normalized.scope, witness.resolvedScope)
        assertEquals(null, witness.declaredScope)
        assertEquals(13, witness.termEvidence.startCodePoint) // emoji counts as one code point
        assertEquals(15, witness.termEvidence.endCodePoint)
        assertEquals(validator.validateRestored(result, F.snapshot()), result)
    }

    @Test
    fun `AHT02 approved preference supplies new route candidates without rewriting effect or old route behavior`() {
        val input = F.input("🙂 2027-01-08 오후 선호.")
        val claim = F.claim(input, effect = IrEffect.PREFERRED)
        val program = F.program(listOf(F.ref(input, "pref", "half")), null, prefer = "pref")
        val result = validator.validate(F.proposal(input, listOf(claim), program), F.snapshot(input))
        // Legacy API remains unchanged; only explicit new route interpretation admits preference windows.
        assertTrue(times(result).isEmpty())
        val participant =
            ScheduleDslInputPolicy
                .projectWithPreferredAvailability(result.calculationProjection, F.policy)
                .participants
                .single { it.participantId == first }
        val preferred = listOf(window("2027-01-08T12:00", "2027-01-09T00:00"))
        assertEquals(preferred, participant.availableTimes)
        assertEquals(emptyList(), participant.explicitPreferences.timeRanges)
        assertEquals(
            IrEffect.PREFERRED,
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(1, result.termApplications.size)
    }

    @Test
    fun `AHT03 negative half-day subtracts afternoon without becoming availability`() {
        val input = F.input("🙂 2027-01-08 00:00부터 24:00까지 가능하지만 오후 불가.")
        val base = F.claim(input, "base", scope = F.scope("2027-01-08T00:00", "2027-01-09T00:00"))
        val negative = F.claim(input, effect = IrEffect.UNAVAILABLE)
        val nodes =
            listOf(
                F.ref(input, "b", "base"),
                F.ref(input, "h", "half"),
                F.node(input, "root", ScheduleDslOperator.EXCEPT, listOf("b", "h")),
            )
        val result = validator.validate(F.proposal(input, listOf(base, negative), F.program(nodes, "root")), F.snapshot(input))
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-08T12:00")), times(result))
        assertEquals(
            IrEffect.UNAVAILABLE,
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "half" }
                .effect,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["ALL_OF", "EXCEPT"])
    fun `AHT04 narrower context stays in the AST and is never widened to the full term`(operation: String) {
        val input = F.input("🙂 2027-01-08 오후 가능. 15:00부터 18:00까지라는 별도 합성 조건.")
        val constraint =
            F.claim(
                input,
                "narrow",
                if (operation == "EXCEPT") IrEffect.UNAVAILABLE else IrEffect.AVAILABLE,
                F.scope("2027-01-08T15:00", "2027-01-08T18:00"),
            )
        val nodes =
            listOf(
                F.ref(input, "h", "half"),
                F.ref(input, "n", "narrow"),
                F.node(input, "root", ScheduleDslOperator.valueOf(operation), listOf("h", "n")),
            )
        val proposal = F.proposal(input, listOf(F.claim(input), constraint), F.program(nodes, "root"))
        val result = validator.validate(proposal, F.snapshot(input))
        val expected =
            if (operation == "ALL_OF") {
                listOf(window("2027-01-08T15:00", "2027-01-08T18:00"))
            } else {
                listOf(
                    window("2027-01-08T12:00", "2027-01-08T15:00"),
                    window("2027-01-08T18:00", "2027-01-09T00:00"),
                )
            }
        assertEquals(expected, times(result))
        assertEquals(
            nodes.map { it.operator },
            result.calculationProjection.programs
                .first()
                .program!!
                .nodes
                .map { it.operator },
        )
    }

    @Test
    fun `AHT05 two dated afternoon terms preserve the intervening morning gap`() {
        val input = F.input("🙂 2027-01-08 오후 또는 2027-01-09 오후 가능.")
        val nodes =
            listOf(
                F.ref(input, "a", "half"),
                F.ref(input, "b", "next"),
                F.node(input, "root", ScheduleDslOperator.ANY_OF, listOf("a", "b")),
            )
        val terms = listOf(F.term(input), F.term(input, "next", date = F.day.plusDays(1)))
        val result =
            validator.validate(
                F.proposal(input, listOf(F.claim(input), F.claim(input, "next")), F.program(nodes, "root"), terms),
                F.snapshot(input),
            )
        assertEquals(
            listOf(
                window("2027-01-08T12:00", "2027-01-09T00:00"),
                window("2027-01-09T12:00", "2027-01-10T00:00"),
            ),
            times(result),
        )
    }

    @Test
    fun `AHT06 dictionary application does not select unresolved exclusive AM PM alternatives`() {
        val input = F.input("🙂 2027-01-08 오전 또는 오후 중 하나이며 아직 선택하지 않았다.")
        val am = F.claim(input, "am").copy(alternativeGroup = "period", alternative = "am")
        val pm = F.claim(input).copy(alternativeGroup = "period", alternative = "pm")
        val choice =
            F
                .node(input, "root", ScheduleDslOperator.CHOICE, listOf("a", "p"))
                .copy(choiceGroup = "period", branchLabels = listOf("am", "pm"))
        val choiceNodes = listOf(F.ref(input, "a", "am"), F.ref(input, "p", "half"), choice)
        val program = F.program(choiceNodes, "root")
        val result =
            validator.validate(
                F.proposal(
                    input,
                    listOf(am, pm),
                    program,
                    listOf(F.term(input, "am", ApprovedKoreanTimeTerm.AM), F.term(input)),
                ),
                F.snapshot(input),
            )
        assertTrue(times(result).isEmpty())
        assertEquals(2, result.termApplications.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["INFERRED", "UNRESOLVED"])
    fun `AHT07 numeric vocabulary resolution does not upgrade interpretation or confirmation`(label: String) {
        val claim = F.claim().copy(interpretation = IrInterpretation.valueOf(label))
        val result = validator.validate(F.proposal(claims = listOf(claim)), F.snapshot())
        assertEquals(
            claim.interpretation,
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .interpretation,
        )
        assertTrue(times(result).isEmpty()) // explicit-only injected test policy is unchanged
    }

    @ParameterizedTest
    @ValueSource(strings = ["revision", "quote", "codepoint", "question-source"])
    fun `AHT08 invalid term evidence cannot authorize that participant but preserves the independent peer`(change: String) {
        val term = F.term()
        val bad =
            term.copy(
                termEvidence =
                    when (change) {
                        "revision" -> term.termEvidence.copy(revision = 4)
                        "quote" -> term.termEvidence.copy(quote = "오전")
                        "codepoint" -> term.termEvidence.copy(startCodePoint = term.termEvidence.startCodePoint + 1)
                        else -> term.termEvidence.copy(sourceId = "question:synthetic-question")
                    },
            )
        val result = validator.validate(F.proposal(terms = listOf(bad)), F.snapshot())
        assertTrue(times(result).isEmpty())
        assertTrue(result.termApplications.isEmpty())
        val projected = ScheduleDslInputPolicy.project(result.calculationProjection, F.policy)
        assertEquals(
            listOf(window("2027-01-08T14:00", "2027-01-08T18:00")),
            projected.participants.single { it.participantId != first }.availableTimes,
        )
        assertTrue(
            result.calculationProjection.programs
                .first()
                .program == null ||
                result.calculationProjection.programs
                    .first()
                    .problems
                    .isNotEmpty(),
        )
        assertEquals(result, validator.validateRestored(result, F.snapshot()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["date", "scope", "digest", "missing", "duplicate", "vocabulary"])
    fun `AHT09 restored wrapper recomputes evidence and normalization instead of trusting numeric projection`(change: String) {
        val valid = validator.validate(F.proposal(), F.snapshot())
        val witness = valid.termApplications.single()
        val changed =
            when (change) {
                "date" -> valid.copy(termApplications = listOf(witness.copy(localDate = F.day.plusDays(1))))
                "scope" ->
                    valid.copy(
                        termApplications = listOf(witness.copy(resolvedScope = F.scope("2027-01-08T17:00", "2027-01-08T22:00"))),
                    )
                "digest" ->
                    valid.copy(
                        termApplications =
                            listOf(
                                witness.copy(
                                    termEvidence = witness.termEvidence.copy(contentSha256 = "0".repeat(64)),
                                ),
                            ),
                    )
                "missing" -> valid.copy(termApplications = emptyList())
                "duplicate" -> valid.copy(termApplications = listOf(witness, witness))
                else -> valid.copy(vocabularyVersion = "unapproved-meals-1")
            }
        assertFailsWith<RuntimeException> { validator.validateRestored(changed, F.snapshot()) }
    }

    @Test
    fun `AHT10 duplicate claimed term bindings are a structural failure`() {
        val term = F.term()
        assertFailsWith<RuntimeException> { validator.validate(F.proposal(terms = listOf(term, term)), F.snapshot()) }
    }

    @Test
    fun `AHT11 nonasserted claims remain nonasserted after term application`() {
        val input = F.input("🙂 2027-01-08 오후 가능이라는 말을 철회했다.")
        val claim = F.claim(input).copy(assertion = IrAssertion.RETRACTED)
        val result = validator.validate(F.proposal(input, listOf(claim)), F.snapshot(input))
        assertEquals(
            IrAssertion.RETRACTED,
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .assertion,
        )
        assertTrue(times(result).isEmpty())
    }

    @Test
    fun `AHT12 approved AM is normalized to midnight through noon rather than PM`() {
        val input = F.input("🙂 2027-01-08 오전 가능.")
        val result =
            validator.validate(
                F.proposal(input, terms = listOf(F.term(input, term = ApprovedKoreanTimeTerm.AM))),
                F.snapshot(input),
            )
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-08T12:00")), times(result))
        assertEquals(ApprovedKoreanTimeTerm.AM, result.termApplications.single().term)
    }

    @Test
    fun `AHT13 a native PM span cannot support a model-selected AM term`() {
        val mismatched = F.term().copy(term = ApprovedKoreanTimeTerm.AM)
        val result = validator.validate(F.proposal(terms = listOf(mismatched)), F.snapshot())
        assertTrue(times(result).isEmpty())
        assertTrue(result.termApplications.isEmpty())
        assertEquals(result, validator.validateRestored(result, F.snapshot()))
    }
}
