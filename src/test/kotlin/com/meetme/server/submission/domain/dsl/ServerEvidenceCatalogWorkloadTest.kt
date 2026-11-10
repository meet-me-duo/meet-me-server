package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bounded verification workload. Measure externally; machine speed is not a correctness assertion. */
class ServerEvidenceCatalogWorkloadTest {
    @Test
    fun `SECW01 fifty independent original and supplemental sources preserve all selected native references`() {
        val snapshot = boundedSnapshot()
        val catalog = ServerEvidenceCatalog.create(snapshot)
        assertTrue(catalog.entries.size <= ServerEvidenceCatalog.MAX_ENTRIES)
        val wholeContexts =
            snapshot.context.inputs.flatMap { input ->
                input.sources.map { source ->
                    val text = requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId))
                    catalog.entries.single { entry ->
                        entry.inputId == input.submissionVersionId &&
                            entry.kind == ServerEvidenceKind.CONTEXT &&
                            entry.span.sourceId == source.sourceId &&
                            entry.span.startCodePoint == 0 &&
                            entry.span.endCodePoint == source.codePointLength &&
                            entry.text == text
                    }
                }
            }
        assertEquals(100, wholeContexts.size)
        snapshot.context.inputs.forEach { input ->
            assertTrue(
                catalog.entries.any {
                    it.inputId == input.submissionVersionId &&
                        it.kind == ServerEvidenceKind.CLOCK &&
                        it.text == "07:25"
                },
            )
            assertTrue(
                catalog.entries.any {
                    it.inputId == input.submissionVersionId &&
                        it.kind == ServerEvidenceKind.CLOCK &&
                        it.text == "14:35"
                },
            )
            assertTrue(
                catalog.entries.any {
                    it.inputId == input.submissionVersionId &&
                        it.kind == ServerEvidenceKind.TERM &&
                        it.text == "점심"
                },
            )
        }
        val selected =
            wholeContexts +
                catalog.entries.filter { it.kind in setOf(ServerEvidenceKind.CLOCK, ServerEvidenceKind.TERM) }
        assertTrue(selected.size >= 200)
        selected.forEach { entry ->
            val proof = catalog.resolve(entry.inputId, entry.id)
            assertEquals(entry.span, proof.span)
            assertEquals(proof, ScheduleDslStructuralNormalizer.restoreEvidence(entry.inputId, proof, snapshot))
        }
    }

    private fun boundedSnapshot(): ScheduleEvidenceSnapshot {
        val native = ScheduleIrV2SyntheticFixture.snapshot()
        val texts = linkedMapOf<UUID, Map<String, String>>()
        val inputs =
            (0 until 50).map { index ->
                val inputId = UUID(42, index.toLong() + 1)
                val questionId = UUID(43, index.toLong() + 1)
                val supplementId = "supplement:$questionId"
                val original = "Synthetic original $index 07:25, literal context only ".padEnd(500, 'x')
                val supplement = "Synthetic supplemental $index 14:35, 점심, literal context only ".padEnd(1500, 'y')
                val sources = mapOf("original" to original, supplementId to supplement)
                texts[inputId] = sources
                native.context.inputs.first().copy(
                    submissionVersionId = inputId,
                    participantId = UUID(44, index.toLong() + 1),
                    contentSha256 = IrEvidenceBindings.sha256("synthetic frozen binding $index"),
                    sources =
                        sources.map { (sourceId, text) ->
                            IrSourceBinding(
                                sourceId = sourceId,
                                kind = if (sourceId == "original") IrSourceKind.ORIGINAL else IrSourceKind.SUPPLEMENT,
                                revision = 3,
                                codePointLength = text.codePointCount(0, text.length),
                                contentSha256 = IrEvidenceBindings.sha256(text),
                                referenceAt = ScheduleIrV2SyntheticFixture.reference,
                                questionId = if (sourceId == "original") null else questionId,
                                originalStartCodePoint = if (sourceId == "original") null else 0,
                                originalEndCodePoint = if (sourceId == "original") null else original.codePointCount(0, original.length),
                            )
                        },
                )
            }
        return ScheduleEvidenceSnapshot(native.context.copy(inputs = inputs), texts)
    }
}
