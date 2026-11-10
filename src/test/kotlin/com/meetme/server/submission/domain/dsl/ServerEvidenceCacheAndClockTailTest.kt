package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Follow-up cache ownership and lexical tail cases; earlier catalog/clock expectations stay frozen. */
class ServerEvidenceCacheAndClockTailTest {
    private val native = ScheduleIrV2SyntheticFixture
    private val peer = "Synthetic unchanged independent peer"

    @Test
    fun `SECC01 a local catalog restores references for its exact immutable snapshot instance`() {
        val snapshot = snapshot("Synthetic 17:20 literal context")
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val entry = catalog.entries.first { it.inputId == native.first && it.kind == ServerEvidenceKind.CLOCK }
        val proof = catalog.resolve(native.first, entry.id)
        assertEquals(proof, ScheduleDslStructuralNormalizer.restoreEvidence(native.first, proof, snapshot, catalog))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `SECC02 a catalog cannot be reused with another snapshot object merely because metadata matches`(changePeer: Boolean) {
        val text = "Synthetic 17:20 literal context"
        val snapshot = snapshot(text)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val entry = catalog.entries.first { it.inputId == native.first && it.kind == ServerEvidenceKind.CLOCK }
        val proof = catalog.resolve(native.first, entry.id)
        val other =
            ScheduleEvidenceSnapshot(
                snapshot.context,
                mapOf(
                    native.first to mapOf("original" to text),
                    native.second to mapOf("original" to if (changePeer) "Synthetic altered peer bytes" else peer),
                ),
            )
        assertEquals(snapshot.context, other.context)
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslStructuralNormalizer.restoreEvidence(native.first, proof, other, catalog)
        }
        assertEquals(peer, snapshot.sourceText(native.second, "original"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["7시 300분", "7시 30분5", "7시반5"])
    fun `SECC03 an invalid pending minute or half-hour tail cannot fall back to its shorter hour prefix`(token: String) {
        val text = "Synthetic prefix $token synthetic suffix"
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        assertFalse(entries.any { it.kind == ServerEvidenceKind.CLOCK }, "Invalid tail was discarded to manufacture a shorter clock")
        assertTrue(entries.any { it.kind == ServerEvidenceKind.CONTEXT && it.text == text })
    }

    @ParameterizedTest
    @CsvSource(
        value = ["7시30분|7시30분|-", "7시 30분|7시 30분|-", "7시반|7시반|-", "7시 반|7시 반|-", "7시 8시|7시|8시"],
        delimiter = '|',
    )
    fun `SECC04 valid minute half-hour and independent following hour tokens remain exact candidates`(
        text: String,
        first: String,
        second: String,
    ) {
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        val clocks = entries.filter { it.kind == ServerEvidenceKind.CLOCK }.map { it.text }
        assertTrue(first in clocks)
        if (second != "-") assertTrue(second in clocks)
    }

    private fun snapshot(text: String): ScheduleEvidenceSnapshot {
        val old = native.snapshot()
        val texts = mapOf(native.first to text, native.second to peer)
        val inputs =
            old.context.inputs.map { input ->
                val source = texts.getValue(input.submissionVersionId)
                input.copy(
                    sources =
                        listOf(
                            input.sources.single().copy(
                                codePointLength = source.codePointCount(0, source.length),
                                contentSha256 = IrEvidenceBindings.sha256(source),
                            ),
                        ),
                )
            }
        return ScheduleEvidenceSnapshot(old.context.copy(inputs = inputs), texts.mapValues { mapOf("original" to it.value) })
    }
}
