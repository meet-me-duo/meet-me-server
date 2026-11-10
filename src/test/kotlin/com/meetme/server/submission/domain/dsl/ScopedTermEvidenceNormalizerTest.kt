package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.meetme.server.support.ScopedTermEvidenceTestFixture as F

/** Scope ownership and exact evidence positions only; no provider or private participant input. */
class ScopedTermEvidenceNormalizerTest {
    private val normalizer = ScheduleDslStructuralNormalizer

    @Test
    fun `STN01 unique condition context resolves the middle approved word using Unicode code points`() {
        val snapshot = F.snapshot()
        val term = F.locator("오후")
        assertFailsWith<IllegalArgumentException> { normalizer.locate(F.first, term, snapshot) }
        val actual = normalizer.locateScopedTerm(F.first, term, listOf(F.locator(F.CONTEXT)), snapshot)
        assertEquals(F.span("오후", 20, 22), actual.span)
        assertEquals(F.span(F.CONTEXT, 9, 26), actual.contextSpan)
        assertEquals(EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE, actual.derivation)
        assertEquals(actual, normalizer.restoreEvidence(F.first, actual, snapshot))
        assertEquals(F.RAW, snapshot.sourceText(F.first, "original"))
    }

    @Test
    fun `STN02 agreeing anchors choose the same shortest exact context independently of order`() {
        val term = F.locator("오후")
        val anchors = listOf(F.locator(F.CONTEXT), F.locator(F.NARROW_CONTEXT))
        val actual = normalizer.locateScopedTerm(F.first, term, anchors, F.snapshot())
        assertEquals(F.span("오후", 20, 22), actual.span)
        assertEquals(F.span(F.NARROW_CONTEXT, 9, 22), actual.contextSpan)
        assertEquals(actual, normalizer.locateScopedTerm(F.first, term, anchors.reversed(), F.snapshot()))
    }

    @Test
    fun `STN03 no anchor ambiguous local word duplicated context and conflicting anchors remain rejected`() {
        val term = F.locator("오후")
        val snapshot = F.snapshot()
        assertFailsWith<IllegalArgumentException> { normalizer.locateScopedTerm(F.first, term, emptyList(), snapshot) }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locateScopedTerm(F.first, term, listOf(F.locator(F.RAW)), snapshot)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locateScopedTerm(
                F.first,
                term,
                listOf(F.locator(F.CONTEXT), F.locator(F.OTHER_CONTEXT)),
                snapshot,
            )
        }
        val duplicate = F.CONTEXT + " " + F.CONTEXT
        assertFailsWith<IllegalArgumentException> {
            normalizer.locateScopedTerm(F.first, term, listOf(F.locator(F.CONTEXT)), F.snapshot(duplicate))
        }
        val repeatedInside = "2027-01-08 오후 또는 오후 가능."
        assertFailsWith<IllegalArgumentException> {
            normalizer.locateScopedTerm(F.first, term, listOf(F.locator(repeatedInside)), F.snapshot(repeatedInside))
        }
    }

    @Test
    fun `STN04 scoped lookup never borrows another input source revision or invented term`() {
        val term = F.locator("오후")
        val snapshot = F.snapshot()
        listOf(F.second, UUID(0, 91919)).forEach { foreign ->
            assertFailsWith<IllegalArgumentException> {
                normalizer.locateScopedTerm(foreign, term, listOf(F.locator(F.CONTEXT)), snapshot)
            }
        }
        for (anchor in listOf(F.locator(F.CONTEXT, revision = 4), F.locator(F.CONTEXT, source = "missing-source"))) {
            assertFailsWith<IllegalArgumentException> {
                normalizer.locateScopedTerm(F.first, term, listOf(anchor), snapshot)
            }
        }
        for (invalid in listOf(F.locator("오후", revision = 4), F.locator("오후", source = "missing-source"))) {
            assertFailsWith<IllegalArgumentException> {
                normalizer.locateScopedTerm(F.first, invalid, listOf(F.locator(F.CONTEXT)), snapshot)
            }
        }
        for (invalid in listOf("2027-01-08 오후", " ", "\uD83D", "\uDE42")) {
            assertFailsWith<IllegalArgumentException> {
                normalizer.locateScopedTerm(F.first, F.locator(invalid), listOf(F.locator(F.CONTEXT)), snapshot)
            }
        }
    }

    @Test
    fun `STN05 restore retains the specific occurrence and rejects forged spans context hashes and provenance`() {
        val snapshot = F.snapshot()
        val actual = normalizer.locateScopedTerm(F.first, F.locator("오후"), listOf(F.locator(F.CONTEXT)), snapshot)
        val context = requireNotNull(actual.contextSpan)
        val corruptions =
            listOf(
                actual.copy(span = actual.span.copy(startCodePoint = 5, endCodePoint = 7)),
                actual.copy(span = actual.span.copy(contentSha256 = "0".repeat(64))),
                actual.copy(span = actual.span.copy(revision = 4)),
                actual.copy(contextSpan = null),
                actual.copy(contextSpan = context.copy(contentSha256 = "0".repeat(64))),
                actual.copy(contextSpan = context.copy(sourceId = "missing-source")),
                actual.copy(contextSpan = context.copy(revision = 4)),
                actual.copy(
                    contextSpan = context.copy(startCodePoint = 0, endCodePoint = 32, contentSha256 = IrEvidenceBindings.sha256(F.RAW)),
                ),
                actual.copy(derivation = EvidenceLocationDerivation.SERVER_LITERAL_QUOTE),
            )
        corruptions.forEach { forged ->
            assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(F.first, forged, snapshot) }
        }
        assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(F.second, actual, snapshot) }
        val changedTexts =
            snapshot.context.inputs
                .associate { input ->
                    input.submissionVersionId to
                        input.sources.associate { source ->
                            source.sourceId to requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId))
                        }
                }.toMutableMap()
        changedTexts[F.first] = mapOf("original" to F.RAW.replace("안내", "알림"))
        assertFailsWith<IllegalArgumentException> {
            normalizer.restoreEvidence(F.first, actual, ScheduleEvidenceSnapshot(snapshot.context, changedTexts))
        }
    }

    @Test
    fun `STN06 globally unique quotes retain legacy witness semantics without scoped provenance`() {
        val raw = "2027-01-08 오후 가능."
        val snapshot = F.snapshot(raw)
        val locator = F.locator("오후")
        val legacy = normalizer.locate(F.first, locator, snapshot)
        val actual = normalizer.locateScopedTerm(F.first, locator, emptyList(), snapshot)
        assertEquals(legacy, actual)
        assertEquals(EvidenceLocationDerivation.SERVER_LITERAL_QUOTE, actual.derivation)
        assertNull(actual.contextSpan)
        assertEquals(actual, normalizer.restoreEvidence(F.first, actual, snapshot))
    }
}
