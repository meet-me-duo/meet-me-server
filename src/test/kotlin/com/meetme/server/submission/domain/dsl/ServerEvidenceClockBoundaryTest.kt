package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Literal clock candidate boundaries only; these entries establish neither availability nor temporal meaning. */
class ServerEvidenceClockBoundaryTest {
    private val native = ScheduleIrV2SyntheticFixture

    @ParameterizedTest
    @ValueSource(strings = ["117:20", "27:00", "17:200", "17:20:005", "117시", "7시300분", "7시30분5", "1시간", "오후 7시간"])
    fun `SECB01 malformed numeric tokens and duration units cannot yield shorter clock candidates`(token: String) {
        val text = "Synthetic prefix $token synthetic suffix"
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        assertTrue(entries.any { it.kind == ServerEvidenceKind.CONTEXT && it.text == text })
        assertFalse(entries.any { it.kind == ServerEvidenceKind.CLOCK }, "A partial clock token was issued")
    }

    @ParameterizedTest
    @ValueSource(strings = ["17:20", "9:05:07", "23:59:59", "7시", "오후 7시", "오전7시", "7시30분", "7시 반"])
    fun `SECB02 complete native clock tokens remain selectable without normalizing their text`(token: String) {
        val text = "🙂 Synthetic prefix $token synthetic suffix"
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        val clocks = entries.filter { it.kind == ServerEvidenceKind.CLOCK && it.text == token }
        assertTrue(clocks.isNotEmpty(), "Missing complete native clock token")
        clocks.forEach { entry ->
            val start = text.indexOf(token)
            assertEquals(text.codePointCount(0, start), entry.span.startCodePoint)
            assertEquals(text.codePointCount(0, start + token.length), entry.span.endCodePoint)
            assertEquals(IrEvidenceBindings.sha256(token), entry.span.contentSha256)
        }
    }

    @ParameterizedTest
    @CsvSource(
        value =
            [
                "17:20부터 가능|17:20",
                "7시부터 불가|7시",
                "7시까지 가능|7시",
                "오후 7시까지 미정|오후 7시",
                "7시30분에는 미정|7시30분",
                "7시30분부터 가능|7시30분",
            ],
        delimiter = '|',
    )
    fun `SECB03 ordinary Korean particles do not make an otherwise complete clock token disappear`(
        text: String,
        expected: String,
    ) {
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        assertTrue(entries.any { it.kind == ServerEvidenceKind.CLOCK && it.text == expected })
    }

    @Test
    fun `SECB04 one malformed token does not suppress another independent valid clock`() {
        val text = "Synthetic 117:20 and 1시간 remain literal context. 다른 문맥은 오후 7시부터 가능"
        val entries = ServerEvidenceCatalog.create(snapshot(text)).entries.filter { it.inputId == native.first }
        val clocks = entries.filter { it.kind == ServerEvidenceKind.CLOCK }
        assertTrue(clocks.any { it.text == "오후 7시" })
        assertFalse(clocks.any { it.text in setOf("17:20", "7:20", "1시") })
        assertTrue(entries.any { it.kind == ServerEvidenceKind.CONTEXT && it.text == text })
    }

    private fun snapshot(text: String): ScheduleEvidenceSnapshot {
        val old = native.snapshot()
        val texts = mapOf(native.first to text, native.second to "Synthetic independent peer without a clock token")
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
