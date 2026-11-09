package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

/** A complete approved longer lexeme cannot authorize a conflicting shorter term by an internal substring span. */
class ScheduleDslApprovedNestedLexemeBoundaryTest {
    private val parser = StrictScheduleDslApprovedTermsResponseParser(JsonMapper.builder().findAndAddModules().build())

    @ParameterizedTest
    @CsvSource("한낮,낮,DAYTIME", "한밤중,밤,NIGHT")
    fun `ATNL01 nested shorter evidence cannot widen a distinct approved longer time term`(
        nativeLexeme: String,
        shortQuote: String,
        wrongTerm: ApprovedKoreanTimeTerm,
    ) {
        val input = F.input("🙂 2027-01-08 $nativeLexeme 가능.")
        // This quote and code-point span are genuinely present, but inside a different complete approved term.
        val bad = T.ref(input, wrongTerm, shortQuote)
        assertEquals(shortQuote, bad.termEvidence.quote)
        val proposal = F.proposal(input, terms = listOf(bad))
        val envelope =
            parser.parse(
                F.json(F.document(proposal)),
                F.snapshot(input),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertTrue(envelope.termApplications.isEmpty())
        val declared =
            envelope.declaration.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, declared.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertNull(declared.scope)
        val preserved =
            envelope.calculationProjection.claims.results
                .single { it.submissionVersionId == F.ir.first }
                .conditions
                .single()
        assertEquals(IrEffect.AVAILABLE, preserved.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, preserved.temporalResolution)
        assertNull(preserved.scope)
        val program = envelope.calculationProjection.programs.single { it.submissionVersionId == F.ir.first }
        assertTrue(program.program == null || program.problems.isNotEmpty())
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope.calculationProjection, F.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertEquals(
            listOf(
                InstantTimeRange(
                    LocalDateTime.parse("2027-01-08T14:00").atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                    LocalDateTime.parse("2027-01-08T18:00").atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ),
            ),
            prepared.participants.single { it.participantId == ParticipantId(F.ir.secondParticipant) }.availableTimes,
        )
        assertEquals(envelope, ScheduleDslApprovedTermsValidator().validateRestored(envelope, F.snapshot(input)))
    }
}
