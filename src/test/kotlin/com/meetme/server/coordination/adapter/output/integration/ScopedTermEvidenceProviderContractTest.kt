package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrProblem
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as F

/** Explicit reader candidate against unmodified synthetic response bytes; no provider call or private input. */
class ScopedTermEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    private fun parse(
        document: MutableMap<String, Any?> = F.document(),
        enabled: Boolean = true,
        raw: String = F.RAW,
    ): ScheduleDslStructuralEnvelope =
        StrictScheduleDslStructuralResponseParser(mapper, allowScopedTermEvidence = enabled).parse(
            H.json(document),
            F.snapshot(raw),
            "schedule-dsl-response-5",
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    @Test
    fun `STP01 default reader keeps prior quarantine while opt in recovers the exact declared term and real matching`() {
        val document = F.document()
        val frozen = H.json(document)
        val previous = parse(document, enabled = false)
        assertTrue(
            previous.base.base.termApplications
                .none { it.submissionVersionId == F.first },
        )
        assertNull(
            previous.base.base.declaration.programs
                .single { it.submissionVersionId == F.first }
                .program,
        )
        assertEquals(
            IrProblem.EVIDENCE,
            previous.base.base.declaration.claims.results
                .single { it.submissionVersionId == F.first }
                .quarantined
                .single()
                .problem,
        )

        val actual = parse(document)
        val term =
            actual.base.base.termApplications
                .single { it.submissionVersionId == F.first }
        assertEquals(F.span("오후", 20, 22), term.termEvidence)
        assertEquals(H.scope("2027-01-08T12:00", "2027-01-09T00:00"), term.resolvedScope)
        assertNotNull(
            actual.base.base.declaration.programs
                .single { it.submissionVersionId == F.first }
                .program,
        )
        val witness = actual.locatedEvidence.single { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE }
        assertEquals(F.first, witness.submissionVersionId)
        assertEquals(F.span(F.CONTEXT, 9, 26), witness.evidence.contextSpan)
        assertEquals(actual, ScheduleDslStructuralValidator().validateRestored(actual, F.snapshot()))

        val result = ScheduleDslStructuralCalculator.calculate(actual, F.snapshot(), F.policy, MeetingMode.REMOTE)
        assertEquals(
            listOf(InstantTimeRange(Instant.parse("2027-01-08T03:00:00Z"), Instant.parse("2027-01-08T15:00:00Z"))),
            result.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == ParticipantId(H.ir.firstParticipant) }
                .availableTimes,
        )
        val expected = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
        assertEquals(listOf(expected), result.recommendations.options.map { it.window })
        assertTrue(
            result.recommendations.options
                .single()
                .variants
                .all { it.participantIds.size == 2 },
        )
        assertEquals(frozen, H.json(document))
        assertEquals(F.RAW, F.snapshot().sourceText(F.first, "original"))
    }

    @Test
    fun `STP02 conflicting ownership contexts stale source and broad term quotes retain failed condition safeguards`() {
        val invalidDocuments =
            listOf(
                F.document(contexts = listOf(F.CONTEXT, F.OTHER_CONTEXT)),
                F.document().also { F.fields(F.firstTerm(it)["term_evidence"])["revision"] = 4 },
                F.document().also { F.fields(F.firstTerm(it)["term_evidence"])["source_id"] = "missing-source" },
                F.document().also { F.fields(F.firstTerm(it)["term_evidence"])["quote"] = F.CONTEXT },
            )
        invalidDocuments.forEach { document ->
            val actual = parse(document)
            assertTrue(
                actual.base.base.termApplications
                    .none { it.submissionVersionId == F.first },
            )
            assertNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == F.first }
                    .program,
            )
            assertNotNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == F.second }
                    .program,
            )
        }
    }

    @Test
    fun `STP03 scoped native position cannot be restored using an undeclared context from the same source`() {
        val envelope = parse()
        val forged =
            envelope.copy(
                locatedEvidence =
                    envelope.locatedEvidence.map { witness ->
                        if (witness.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE) {
                            witness.copy(evidence = witness.evidence.copy(contextSpan = F.span(F.NARROW_CONTEXT, 9, 22)))
                        } else {
                            witness
                        }
                    },
            )
        assertFailsWith<IllegalArgumentException> { ScheduleDslStructuralValidator().validateRestored(forged, F.snapshot()) }
    }

    @Test
    fun `STP04 unique evidence retains the same legacy envelope and witness shape under reader opt in`() {
        val input = F.input(H.FIRST)
        val document = F.document(input, contexts = listOf(H.FIRST))
        val default = parse(document, enabled = false, raw = H.FIRST)
        val candidate = parse(document, raw = H.FIRST)
        assertEquals(default, candidate)
        assertTrue(candidate.locatedEvidence.all { it.evidence.derivation == EvidenceLocationDerivation.SERVER_LITERAL_QUOTE })
        assertTrue(candidate.locatedEvidence.all { it.evidence.contextSpan == null })
    }
}
