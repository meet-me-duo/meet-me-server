package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslGeminiWire
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleDslContextOfflineEntryPointTest {
    @TempDir lateinit var directory: Path
    private val f = ScheduleDslQuestionContextFixture
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @ParameterizedTest
    @ValueSource(strings = ["prepare-context", "prepare-context-gemini", "evaluate-context", "evaluate-context-gemini"])
    fun `CTXC01 explicit context CLI verbs use private new outputs and the same native product pipeline`(command: String) {
        val input = f.input2()
        val inputFile = directory.resolve("synthetic-input.json")
        val output = directory.resolve("synthetic-output.json")
        val response = directory.resolve("synthetic-response.json")
        Files.write(inputFile, mapper.writeValueAsBytes(input))
        val evaluating = command.startsWith("evaluate")
        Files.writeString(response, if (command.endsWith("gemini")) gemini() else f.wire.json())
        val args =
            if (evaluating) {
                arrayOf(
                    command,
                    inputFile.toString(),
                    response.toString(),
                    output.toString(),
                )
            } else {
                arrayOf(command, inputFile.toString(), output.toString())
            }
        val observed = capture(args)
        assertEquals(0, observed.first)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(output))
        if (!evaluating) {
            val expected =
                if (command.endsWith(
                        "gemini",
                    )
                ) {
                    ScheduleDslGeminiWire.requestContextBytes(
                        mapper,
                        ScheduleDslOfflinePipeline.snapshotContext(input),
                        "gemini-3.8-flash",
                    )
                } else {
                    ScheduleDslOfflinePipeline.prepareContext(input)
                }
            assertContentEquals(expected, Files.readAllBytes(output))
        } else {
            val result = ScheduleDslOfflinePipeline.evaluateContext(input, f.wire.json())
            assertEquals(IrCalculationStatus.CALCULATED, result.status)
            val window = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))
            assertEquals(listOf(window), result.recommendations.options.map { it.window })
            assertEquals(mapper.readTree(mapper.writeValueAsBytes(result)), mapper.readTree(Files.readAllBytes(output)))
        }
        assertPrivate(observed.second)
        assertTrue(Regex("[a-f0-9]{64}").containsMatchIn(observed.second))
    }

    @ParameterizedTest
    @ValueSource(strings = ["old_command_with_context2", "context_command_with_old1"])
    fun `CTXC02 response version2 must be explicitly selected and cannot enter an old command`(mismatch: String) {
        val contextCommand = mismatch == "context_command_with_old1"
        val input = if (contextCommand) f.input1() else f.input2()
        val inputFile = directory.resolve("synthetic-input.json")
        val output = directory.resolve("must-not-exist.json")
        Files.write(inputFile, mapper.writeValueAsBytes(input))
        val observed =
            capture(arrayOf(if (contextCommand) "prepare-context" else "prepare", inputFile.toString(), output.toString()))
        assertEquals(2, observed.first)
        assertFalse(Files.exists(output))
        assertPrivate(observed.second)
    }

    @Test
    fun `CTXC03 existing context output cannot be overwritten and malformed private input is never printed`() {
        val input = directory.resolve("synthetic-input.json")
        val output = directory.resolve("existing.json")
        Files.write(input, mapper.writeValueAsBytes(f.input2()))
        val old = "Synthetic existing bytes".toByteArray()
        Files.write(output, old)
        val observed = capture(arrayOf("prepare-context", input.toString(), output.toString()))
        assertEquals(2, observed.first)
        assertContentEquals(old, Files.readAllBytes(output))
        assertPrivate(observed.second)
        val marker = "SYNTHETIC_PRIVATE_QUESTION_PARSE_MARKER"
        Files.writeString(input, "{\"$marker\":not JSON}")
        val absent = directory.resolve("must-not-exist.json")
        val failure = capture(arrayOf("prepare-context", input.toString(), absent.toString()))
        assertEquals(2, failure.first)
        assertFalse(Files.exists(absent))
        assertFalse(failure.second.contains(marker))
        assertPrivate(failure.second)
    }

    @Test
    fun `CTXC04 context no-place IN_PERSON computes its own mode without changing native meaning`() {
        val input = f.input2()
        val remote = ScheduleDslOfflinePipeline.evaluateContext(input, f.wire.json())
        val nonremote = ScheduleDslOfflinePipeline.evaluateContext(input.copy(meetingMode = MeetingMode.IN_PERSON), f.wire.json())
        assertEquals(IrCalculationStatus.CALCULATED, nonremote.status)
        val expectedWindow = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))
        assertEquals(listOf(expectedWindow), nonremote.recommendations.options.map { it.window })
        assertTrue(nonremote.candidates.candidates.isNotEmpty())
        for (candidate in nonremote.candidates.candidates) {
            assertEquals(MeetingMode.IN_PERSON, candidate.meetingMode)
            assertEquals(listOf(expectedWindow), candidate.timeRanges)
            assertEquals(null, candidate.representativeArea)
            assertEquals(null, candidate.representativePlace)
            assertTrue(candidate.unverifiedPlaceParticipantIds.isEmpty())
        }
        for (variant in nonremote.recommendations.options.flatMap { it.variants }) {
            assertEquals(MeetingMode.IN_PERSON, variant.meetingMode)
            assertEquals(null, variant.representativeArea)
            assertEquals(null, variant.representativePlace)
            assertTrue(variant.unverifiedPlaceParticipantIds.isEmpty())
        }
        assertEquals(remote.envelope, nonremote.envelope)
        assertEquals(remote.prepared, nonremote.prepared)
        val old = ScheduleDslOfflinePipeline.evaluate(f.input1(), f.wire.json())
        assertEquals(old, remote)
    }

    private fun gemini() =
        f.wire.encode(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to f.wire.json()))),
                            "finishReason" to "STOP",
                        ),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 10, "candidatesTokenCount" to 20, "totalTokenCount" to 30),
                "modelVersion" to "gemini-3.8-flash",
            ),
        )

    private fun assertPrivate(text: String) {
        for (privateText in listOf(
            f.ir.FIRST_TEXT,
            f.ir.SECOND_TEXT,
            f.PROMPT,
            f.CHOICE,
            f.answer.answerText,
            "Exception",
            "at com.meetme",
        )) {
            assertFalse(text.contains(privateText))
        }
    }

    private fun capture(args: Array<String>): Pair<Int, String> = ScheduleDslNativeProcessCapture.run(args)
}
