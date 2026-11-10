package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Structural synthetic evidence only; not a model-accuracy or user-confirmation oracle. */
class ScheduleDslStructuralNormalizerTest {
    private val normalizer = ScheduleDslStructuralNormalizer
    private val first = F.ir.first

    @Test
    fun `SC01 unique exact quotes derive Unicode positions and restore only against the same frozen evidence`() {
        val raw = "🙂\n금요일 20시 이후 가능"
        val frozen = snapshot(raw)
        val quote = "금요일 20시 이후"
        val expected = located(quote, 2, 12)
        val actual = normalizer.locate(first, ProposedLiteralQuoteLocator("original", 3, quote), frozen)
        assertEquals(expected, actual)
        assertEquals(EvidenceLocationDerivation.SERVER_LITERAL_QUOTE, actual.derivation)
        assertEquals(raw, frozen.sourceText(first, "original"))
        assertEquals(expected, normalizer.restoreEvidence(first, expected, frozen))

        val corruptions =
            listOf(
                expected.copy(span = expected.span.copy(contentSha256 = "0".repeat(64))),
                expected.copy(span = expected.span.copy(startCodePoint = 3, endCodePoint = 13)),
                expected.copy(span = expected.span.copy(sourceId = "missing-source")),
                expected.copy(span = expected.span.copy(revision = 4)),
            )
        corruptions.forEach { forged ->
            assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(first, forged, frozen) }
        }
        assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(F.ir.second, expected, frozen) }
        assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(first, expected, snapshot(raw, revision = 4)) }
        val duplicated = snapshot("aaa")
        assertFailsWith<IllegalArgumentException> { normalizer.restoreEvidence(first, located("aa", 0, 2), duplicated) }
    }

    @Test
    fun `SC02 overlapping ambiguity foreign source stale revision and invalid Unicode never get repaired by locating`() {
        val unique = snapshot("🙂\n금요일 20시 이후 가능")
        val quote = "금요일 20시 이후"
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(first, ProposedLiteralQuoteLocator("original", 3, "aa"), snapshot("aaa"))
        }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(F.ir.second, ProposedLiteralQuoteLocator("original", 3, quote), unique)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(UUID(0, 9999), ProposedLiteralQuoteLocator("original", 3, quote), unique)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(first, ProposedLiteralQuoteLocator("original", 4, quote), unique)
        }
        listOf("missing-source", "supplement:${UUID(0, 9998)}").forEach { source ->
            assertFailsWith<IllegalArgumentException> {
                normalizer.locate(first, ProposedLiteralQuoteLocator(source, 3, quote), unique)
            }
        }
        listOf("", " ", "없는 인용", "\uD83D", "\uDE42").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                normalizer.locate(first, ProposedLiteralQuoteLocator("original", 3, invalid), unique)
            }
        }
        val splitInput =
            F.q.input1().let { input ->
                input.copy(
                    inputs =
                        input.inputs.mapIndexed { index, item ->
                            if (index == 0) item.copy(rawText = "🙂 원문") else item
                        },
                )
            }
        val split = F.snapshot(splitInput)
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(first, ProposedLiteralQuoteLocator("original", 3, F.q.answer.answerText), split)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizer.locate(first, ProposedLiteralQuoteLocator("supplement:${F.q.questionId}", 3, "원문"), split)
        }
    }

    @Test
    fun `SC03 known one-sided clocks retain declaration and civil-day derivation with exact optional search support`() {
        val friday = directional("2027-01-08", "20:00", TemporalDirection.AFTER)
        val sunday = directional("2027-01-10", "17:00", TemporalDirection.BEFORE)
        assertEquals(DayOfWeek.FRIDAY, friday.declaration.localDate.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.declaration.localDate.dayOfWeek)
        val fridayScope = scope("2027-01-08T20:00", "2027-01-09T00:00")
        val sundayScope = scope("2027-01-10T00:00", "2027-01-10T17:00")
        listOf(friday to fridayScope, sunday to sundayScope).forEach { (fixture, expectedScope) ->
            val expected = DirectionalTimeApplication(first, fixture.declaration, expectedScope, expectedScope)
            assertEquals(expected, normalizer.resolve(first, fixture.declaration, fixture.snapshot))
            assertEquals(expected, normalizer.restoreTime(first, expected, fixture.snapshot))
            assertEquals(StructuralTimeDerivation.SERVER_DATE_SCOPE, expected.derivation)
            assertEquals(IrInterpretation.EXPLICIT, expected.declaration.interpretation)
            assertEquals(IrTemporalResolution.NON_PRECISE, expected.declaration.temporalResolution)
            assertEquals(fixture.raw, fixture.snapshot.sourceText(first, "original"))
        }

        val outside = directional("2027-01-12", "20:00", TemporalDirection.AFTER)
        val outsideScope = scope("2027-01-12T20:00", "2027-01-13T00:00")
        val outsideExpected = DirectionalTimeApplication(first, outside.declaration, outsideScope, null)
        val outsideActual = normalizer.resolve(first, outside.declaration, outside.snapshot)
        assertEquals(outsideExpected, outsideActual)
        assertNull(outsideActual.searchSupport)
        assertEquals(outsideExpected, normalizer.restoreTime(first, outsideExpected, outside.snapshot))
        assertFailsWith<IllegalArgumentException> {
            normalizer.restoreTime(first, outsideExpected.copy(searchSupport = outsideScope), outside.snapshot)
        }

        val expected = DirectionalTimeApplication(first, friday.declaration, fridayScope, fridayScope)
        val corruptions =
            listOf(
                expected.copy(submissionVersionId = F.ir.second),
                expected.copy(resolvedScope = scope("2027-01-08T00:00", "2027-01-09T00:00")),
                expected.copy(searchSupport = null),
                expected.copy(searchSupport = scope("2027-01-08T20:00", "2027-01-10T00:00")),
                expected.copy(declaration = friday.declaration.copy(clock = LocalTime.of(19, 0))),
            )
        corruptions.forEach { forged ->
            assertFailsWith<IllegalArgumentException> { normalizer.restoreTime(first, forged, friday.snapshot) }
        }
        assertFailsWith<IllegalArgumentException> { normalizer.restoreTime(F.ir.second, expected, friday.snapshot) }
        assertFailsWith<IllegalArgumentException> {
            normalizer.restoreTime(first, expected, snapshot(friday.raw, revision = 4))
        }
        listOf(
            friday.declaration.copy(dateEvidence = emptyList()),
            friday.declaration.copy(clockEvidence = emptyList()),
            friday.declaration.copy(clockEvidence = listOf(located("20:00", 11, 16, digest = "0".repeat(64)))),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { normalizer.resolve(first, invalid, friday.snapshot) }
        }

        val zero = directional("2027-01-08", "00:00", TemporalDirection.BEFORE)
        assertFailsWith<IllegalArgumentException> { normalizer.resolve(first, zero.declaration, zero.snapshot) }
        listOf(
            directional("2027-03-14", "02:30", TemporalDirection.AFTER, "America/New_York"),
            directional("2027-11-07", "01:30", TemporalDirection.BEFORE, "America/New_York"),
        ).forEach { invalidLocalClock ->
            assertFailsWith<IllegalArgumentException> {
                normalizer.resolve(first, invalidLocalClock.declaration, invalidLocalClock.snapshot)
            }
        }
    }

    private data class DirectionalFixture(
        val raw: String,
        val snapshot: ScheduleEvidenceSnapshot,
        val declaration: DirectionalTimeDeclaration,
    )

    private fun directional(
        date: String,
        time: String,
        direction: TemporalDirection,
        zone: String = "Asia/Seoul",
    ): DirectionalFixture {
        val raw = "$date $time ${if (direction == TemporalDirection.AFTER) "이후" else "이전"} 가능"
        return DirectionalFixture(
            raw,
            snapshot(raw, zone),
            DirectionalTimeDeclaration(
                "synthetic-bound",
                direction,
                LocalDate.parse(date),
                LocalTime.parse(time),
                listOf(located(date, 0, 10)),
                listOf(located(time, 11, 16)),
            ),
        )
    }

    private fun snapshot(
        raw: String,
        zone: String = "Asia/Seoul",
        revision: Int = 3,
    ): ScheduleEvidenceSnapshot =
        F.snapshot(
            F.input(raw).let { input ->
                input.copy(
                    timeZone = zone,
                    inputs =
                        input.inputs.map { item ->
                            item.copy(evidence = requireNotNull(item.evidence).copy(originalRevision = revision))
                        },
                )
            },
        )

    private fun located(
        quote: String,
        start: Int,
        end: Int,
        digest: String = sha256(quote),
    ) = LocatedLiteralEvidence(IrEvidenceSpan("original", 3, start, end, digest))

    private fun scope(
        start: String,
        end: String,
    ) = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
