package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Independent provenance expectations; catalog text is not an availability or language-accuracy oracle. */
class ServerEvidenceCatalogTest {
    private val native = ScheduleIrV2SyntheticFixture

    @Test
    fun `SEC01 every entry maps to an unchanged native Unicode interval and digest`() {
        val original = "🙂 수욜은 17:20 이후만 가능하고,\r\n목욜은 불가능. Cafe\u0301에서만 만남"
        val f = Fixture(original)
        val catalog = ServerEvidenceCatalog.create(f.snapshot())
        assertTrue(catalog.entries.isNotEmpty())
        catalog.entries.forEach { entry ->
            val source = f.texts.getValue(entry.inputId).getValue(entry.span.sourceId)
            val span = entry.span
            assertTrue(span.startCodePoint >= 0)
            assertTrue(span.endCodePoint > span.startCodePoint)
            assertTrue(span.endCodePoint <= source.codePointCount(0, source.length))
            val slice = source.substring(source.offsetByCodePoints(0, span.startCodePoint), source.offsetByCodePoints(0, span.endCodePoint))
            assertEquals(slice, entry.text)
            assertEquals(IrEvidenceBindings.sha256(slice), span.contentSha256)
            assertEquals(3, span.revision)
        }
        assertTrue(catalog.entries.any { it.inputId == native.first && it.kind == ServerEvidenceKind.CONTEXT && it.text == original })
        assertEquals(original, f.snapshot().sourceText(native.first, "original"))
    }

    @Test
    fun `SEC02 whole context and separate lines retain conjunction negatives restrictions and decomposed characters`() {
        val lines = listOf("수욜은 오후만 가능하고,", "목욜은 절대 불가", "Cafe\u0301에서는 저녁만 가능")
        val original = lines.joinToString("\n")
        val f = Fixture(original)
        val entries = ServerEvidenceCatalog.create(f.snapshot()).entries.filter { it.inputId == native.first }
        val contexts = entries.filter { it.kind == ServerEvidenceKind.CONTEXT }.map { it.text }
        assertTrue(original in contexts)
        lines.forEach { assertTrue(it in contexts, "Missing unchanged context line") }
        assertFalse(entries.any { it.text == "수욜은 오후만 가능," })
        assertFalse(entries.any { it.text == "목욜은 절대 가능" })
    }

    @Test
    fun `SEC03 approved term entries are exact native lexemes with separate positions for repeated terms`() {
        val original = "🙂 점심에는 불가, 다른 날 점심은 가능하고 밤은 미정"
        val f = Fixture(original)
        val entries = ServerEvidenceCatalog.create(f.snapshot()).entries.filter { it.inputId == native.first }
        val terms = entries.filter { it.kind == ServerEvidenceKind.TERM && it.text == "점심" }.sortedBy { it.span.startCodePoint }
        val first = original.indexOf("점심")
        val second = original.indexOf("점심", first + 1)
        assertEquals(2, terms.size)
        assertEquals(
            listOf(original.codePointCount(0, first), original.codePointCount(0, second)),
            terms.map { it.span.startCodePoint },
        )
        assertEquals(2, terms.map { it.id }.distinct().size)
        terms.forEach {
            assertEquals(2, it.span.endCodePoint - it.span.startCodePoint)
            assertEquals(IrEvidenceBindings.sha256("점심"), it.span.contentSha256)
        }
        assertFalse(entries.any { it.kind == ServerEvidenceKind.TERM && it.text == "점심에는" })
    }

    @Test
    fun `SEC04 repeated term IDs resolve their declared occurrence without choosing the first match`() {
        val f = Fixture("아침 불가, 다른 날 아침 가능")
        val catalog = ServerEvidenceCatalog.create(f.snapshot())
        val terms =
            catalog.entries
                .filter { it.inputId == native.first && it.kind == ServerEvidenceKind.TERM && it.text == "아침" }
                .sortedBy { it.span.startCodePoint }
        assertEquals(2, terms.size)
        val second = catalog.resolve(native.first, terms.last().id)
        assertEquals(terms.last().span, second.span)
        assertNotEquals(terms.first().span, second.span)
        assertEquals(EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE, second.derivation)
        assertEquals(terms.last().id, second.catalogId)
    }

    @Test
    fun `SEC05 numeric clock and public place subspans stay exact and inside their native source`() {
        val f = Fixture("화욜 17:20 이후 강남역에서만 가능")
        val entries = ServerEvidenceCatalog.create(f.snapshot()).entries.filter { it.inputId == native.first }
        assertTrue(entries.any { it.kind == ServerEvidenceKind.CLOCK && it.text == "17:20" })
        assertTrue(entries.any { it.kind == ServerEvidenceKind.PLACE && it.text == "강남역" })
        assertFalse(entries.any { it.kind == ServerEvidenceKind.PLACE && it.text == "강남역에서만" })
        assertFalse(entries.any { it.text == "17:20:00" })
    }

    @Test
    fun `SEC06 catalog creation and resolution are deterministic for the same frozen snapshot`() {
        val f = Fixture("목욜은 오후 가능, 저녁은 불가")
        val first = ServerEvidenceCatalog.create(f.snapshot())
        val second = ServerEvidenceCatalog.create(f.snapshot())
        assertEquals(first.entries, second.entries)
        assertEquals(
            first.entries.size,
            first.entries
                .map { it.id }
                .distinct()
                .size,
        )
        assertTrue(ServerEvidenceCatalog.VERSION.isNotBlank())
        first.entries.forEach { entry ->
            assertEquals(first.resolve(entry.inputId, entry.id), second.resolve(entry.inputId, entry.id))
        }
    }

    @Test
    fun `SEC07 equal text from separate participants is never the same evidence reference`() {
        val f = Fixture("금욜 점심 불가", "금욜 점심 불가")
        val catalog = ServerEvidenceCatalog.create(f.snapshot())
        val first = catalog.entries.first { it.inputId == native.first && it.kind == ServerEvidenceKind.TERM && it.text == "점심" }
        val peer = catalog.entries.first { it.inputId == native.second && it.kind == ServerEvidenceKind.TERM && it.text == "점심" }
        assertNotEquals(first.id, peer.id)
        assertFailsWith<IllegalArgumentException> { catalog.resolve(native.second, first.id) }
        assertFailsWith<IllegalArgumentException> { catalog.resolve(native.first, peer.id) }
    }

    @Test
    fun `SEC08 original and linked supplement remain distinct even when their text is equal`() {
        val f = Fixture("화욜 오후 가능", supplement = "화욜 오후 가능")
        val catalog = ServerEvidenceCatalog.create(f.snapshot())
        val entries =
            catalog.entries.filter {
                it.inputId == native.first && it.kind == ServerEvidenceKind.CONTEXT && it.text == f.original
            }
        val original = entries.first { it.span.sourceId == "original" }
        val answer = entries.first { it.span.sourceId == f.supplementId }
        assertNotEquals(original.id, answer.id)
        assertEquals("original", catalog.resolve(native.first, original.id).span.sourceId)
        assertEquals(f.supplementId, catalog.resolve(native.first, answer.id).span.sourceId)
    }

    @Test
    fun `SEC09 unknown and foreign input references cannot be repaired with whole source text`() {
        val f = Fixture("토욜 저녁 가능")
        val catalog = ServerEvidenceCatalog.create(f.snapshot())
        val entry = catalog.entries.first { it.inputId == native.first }
        assertFailsWith<IllegalArgumentException> { catalog.resolve(native.first, "missing-evidence-reference") }
        assertFailsWith<IllegalArgumentException> { catalog.resolve(UUID(0, 998), entry.id) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["run", "batch", "room", "zone", "range", "policy", "input", "participant", "submission-revision", "revision", "digest"],
    )
    fun `SEC10 an old ID cannot be replayed after its run input revision or whole source digest changes`(change: String) {
        val f = Fixture("오전만 가능\nSynthetic alpha context")
        val snapshot = f.snapshot()
        val old =
            ServerEvidenceCatalog.create(snapshot).entries.first {
                it.inputId == native.first && it.kind == ServerEvidenceKind.TERM && it.text == "오전"
            }
        val changedInput = if (change == "input") UUID(0, 997) else native.first
        val sourceText = if (change == "digest") f.original.replace("alpha", "omega") else f.original
        val bindings =
            snapshot.context.inputs.map { input ->
                if (input.submissionVersionId != native.first) {
                    input
                } else {
                    input.copy(
                        submissionVersionId = changedInput,
                        participantId = if (change == "participant") UUID(0, 994) else input.participantId,
                        submissionRevision =
                            if (change ==
                                "submission-revision"
                            ) {
                                input.submissionRevision + 1
                            } else {
                                input.submissionRevision
                            },
                        sources =
                            input.sources.map {
                                it.copy(
                                    revision = if (change == "revision") 4 else it.revision,
                                    contentSha256 = IrEvidenceBindings.sha256(sourceText),
                                )
                            },
                    )
                }
            }
        val changed =
            ScheduleEvidenceSnapshot(
                snapshot.context.copy(
                    runId = if (change == "run") UUID(0, 996) else snapshot.context.runId,
                    batchId = if (change == "batch") UUID(0, 993) else snapshot.context.batchId,
                    roomId = if (change == "room") UUID(0, 992) else snapshot.context.roomId,
                    timeZone = if (change == "zone") "UTC" else snapshot.context.timeZone,
                    searchEnd = if (change == "range") snapshot.context.searchEnd.plusDays(1) else snapshot.context.searchEnd,
                    policyVersion = if (change == "policy") "synthetic-other-frozen-policy" else snapshot.context.policyVersion,
                    inputs = bindings,
                ),
                mapOf(changedInput to mapOf("original" to sourceText), native.second to mapOf("original" to f.peer)),
            )
        val catalog = ServerEvidenceCatalog.create(changed)
        assertFailsWith<IllegalArgumentException> { catalog.resolve(changedInput, old.id) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["digest", "length", "missing", "duplicate-source"])
    fun `SEC11 malformed frozen native source metadata is rejected before IDs are issued`(change: String) {
        val f = Fixture("목욜 저녁은 불가")
        val snapshot = f.snapshot()
        val bindings =
            snapshot.context.inputs.map { input ->
                if (input.submissionVersionId != native.first) {
                    input
                } else {
                    val source = input.sources.single()
                    input.copy(
                        sources =
                            when (change) {
                                "digest" -> listOf(source.copy(contentSha256 = IrEvidenceBindings.sha256("different source")))
                                "length" -> listOf(source.copy(codePointLength = source.codePointLength + 1))
                                "duplicate-source" -> listOf(source, source)
                                else -> listOf(source)
                            },
                    )
                }
            }
        val texts =
            if (change == "missing") {
                f.texts - native.first
            } else {
                f.texts
            }
        assertFailsWith<IllegalArgumentException> {
            ServerEvidenceCatalog.create(ScheduleEvidenceSnapshot(snapshot.context.copy(inputs = bindings), texts))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u0000", "\uD800"])
    fun `SEC12 invalid Unicode or NUL never becomes an evidence catalog entry`(invalid: String) {
        val f = Fixture("Synthetic $invalid source")
        assertFailsWith<IllegalArgumentException> { ServerEvidenceCatalog.create(f.snapshot()) }
    }

    @Test
    fun `SEC13 strict legacy quote lookup still rejects repeated native text`() {
        val f = Fixture("점심은 불가, 다른 날 점심 가능")
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslStructuralNormalizer.locate(native.first, ProposedLiteralQuoteLocator("original", 3, "점심"), f.snapshot())
        }
    }

    @Test
    fun `SEC14 source size bound fails explicitly rather than truncating the original`() {
        val original = "가".repeat(ServerEvidenceCatalog.MAX_SOURCE_CODE_POINTS + 1)
        val f = Fixture(original)
        assertFailsWith<IllegalArgumentException> { ServerEvidenceCatalog.create(f.snapshot()) }
        assertEquals(original, f.snapshot().sourceText(native.first, "original"))
    }

    @Test
    fun `SEC15 entry count bound fails explicitly rather than dropping repeated occurrences`() {
        val source = "점심 ".repeat(ServerEvidenceCatalog.MAX_ENTRIES / 2 + 1)
        assertTrue(source.codePointCount(0, source.length) <= ServerEvidenceCatalog.MAX_SOURCE_CODE_POINTS)
        val f = Fixture(source, source)
        assertFailsWith<IllegalArgumentException> { ServerEvidenceCatalog.create(f.snapshot()) }
    }

    private inner class Fixture(
        val original: String,
        val peer: String = "Synthetic independent peer 09:45 possibility remains unspecified",
        supplement: String? = null,
    ) {
        val supplementId = "supplement:${UUID(0, 995)}"
        val texts: Map<UUID, Map<String, String>> =
            mapOf(
                native.first to (mapOf("original" to original) + if (supplement == null) emptyMap() else mapOf(supplementId to supplement)),
                native.second to mapOf("original" to peer),
            )

        fun snapshot(): ScheduleEvidenceSnapshot {
            val old = native.snapshot()
            val inputs =
                old.context.inputs.map { input ->
                    input.copy(
                        sources =
                            texts.getValue(input.submissionVersionId).map { (id, text) ->
                                IrSourceBinding(
                                    sourceId = id,
                                    kind = if (id == "original") IrSourceKind.ORIGINAL else IrSourceKind.SUPPLEMENT,
                                    revision = 3,
                                    codePointLength = text.codePointCount(0, text.length),
                                    contentSha256 = IrEvidenceBindings.sha256(text),
                                    referenceAt = native.reference,
                                    questionId = if (id == "original") null else UUID(0, 995),
                                    originalStartCodePoint = if (id == "original") null else 0,
                                    originalEndCodePoint = if (id == "original") null else original.codePointCount(0, original.length),
                                )
                            },
                    )
                }
            return ScheduleEvidenceSnapshot(old.context.copy(inputs = inputs), texts)
        }
    }
}
