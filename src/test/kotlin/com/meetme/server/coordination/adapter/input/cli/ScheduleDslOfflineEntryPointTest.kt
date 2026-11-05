package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslProviderContract
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure offline public boundary using only synthetic sources, without Spring/DB/provider transport. */
class ScheduleDslOfflineEntryPointTest {
    @TempDir lateinit var directory: Path
    private val w = ScheduleDslWireFixture
    private val f = w.f.ir
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val accepted = Instant.parse("2027-01-04T00:10:00Z")
    private val fixed = Instant.parse("2027-01-04T01:00:00Z")

    private fun input(): ScheduleDslOfflineInput =
        ScheduleDslOfflineInput(
            MANIFEST,
            SCHEDULE_DSL_RESPONSE_CONTRACT,
            SCHEDULE_DSL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            f.run,
            f.batch,
            f.room,
            fixed,
            "Asia/Seoul",
            LocalDate.parse("2027-01-04"),
            LocalDate.parse("2027-01-11"),
            MeetingMode.REMOTE,
            w.f.policy,
            listOf(
                ScheduleDslOfflineSubmission(
                    UUID(0, 401),
                    f.first,
                    f.firstParticipant,
                    7,
                    f.FIRST_TEXT,
                    "ko-KR",
                    accepted,
                    InputEvidence(3, f.reference),
                ),
                ScheduleDslOfflineSubmission(
                    UUID(0, 402),
                    f.second,
                    f.secondParticipant,
                    7,
                    f.SECOND_TEXT,
                    "ko-KR",
                    accepted,
                    InputEvidence(3, f.reference),
                ),
            ),
        )

    @Test
    fun `DSLO01 trusted snapshot reuses evidence bindings and preserves Unicode answer revisions and immutable sources`() {
        val answerAt = f.reference.plusSeconds(60)
        val supplement =
            InputSupplement(UUID(0, 901), 3, "🙂", 0, "Synthetic scoped question", "Synthetic answer 🙂", null, answerAt, answerAt)
        val original = input()
        val first =
            original.inputs.first().copy(
                evidence = InputEvidence(3, f.reference, supplement, InputInterpretation.UNRESOLVED, "synthetic pending"),
            )
        val mutableInputs = (listOf(first) + original.inputs.last()).toMutableList()
        val manifest = original.copy(inputs = mutableInputs)
        val snapshot = ScheduleDslOfflinePipeline.snapshot(manifest)
        val domain =
            Submission.restore(
                SubmissionId(first.submissionId),
                MeetingRoomId(original.roomId),
                ParticipantId(first.participantId),
                SubmissionVersion(
                    SubmissionVersionId(first.submissionVersionId),
                    first.submissionRevision,
                    first.rawText,
                    emptyList(),
                    Locale.forLanguageTag(first.locale),
                    first.acceptedAt,
                    first.evidence,
                ),
            )
        val expectedBinding = IrEvidenceBindings.create(domain)
        val actual = snapshot.context.inputs.single { it.submissionVersionId == f.first }
        assertEquals(expectedBinding.first, actual)
        for ((sourceId, text) in expectedBinding.second) assertEquals(text, snapshot.sourceText(f.first, sourceId))
        assertEquals(7, actual.submissionRevision)
        assertEquals(3, actual.originalRevision)
        val raw = actual.sources.single { it.kind == IrSourceKind.ORIGINAL }
        assertEquals(f.FIRST_TEXT.codePointCount(0, f.FIRST_TEXT.length), raw.codePointLength)
        assertEquals(f.hash(f.FIRST_TEXT), raw.contentSha256)
        val answer = actual.sources.single { it.kind == IrSourceKind.SUPPLEMENT }
        assertEquals(f.hash(supplement.answerText), answer.contentSha256)
        assertEquals(3, answer.revision)
        assertEquals(answerAt, answer.referenceAt)
        mutableInputs.clear()
        assertEquals(2, snapshot.context.inputs.size)
        assertEquals(f.FIRST_TEXT, snapshot.sourceText(f.first, "original"))
    }

    @Test
    fun `DSLO02 prepare emits the same neutral bytes as the real product provider contract`() {
        val manifest = input()
        val snapshot = ScheduleDslOfflinePipeline.snapshot(manifest)
        assertContentEquals(
            ScheduleDslProviderContract(
                mapper,
            ).requestBytes(snapshot, SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
            ScheduleDslOfflinePipeline.prepare(manifest),
        )
    }

    @Test
    fun `DSLO03 offline typed AST calculation uses conjunction and existing candidate preference kernels`() {
        val result = ScheduleDslOfflinePipeline.evaluate(input(), w.json())
        val window = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))
        assertEquals(IrCalculationStatus.CALCULATED, result.status)
        assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, result.precisionOutcome)
        assertEquals(
            listOf(window),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
        assertEquals(listOf(window), result.recommendations.options.map { it.window })
        assertEquals(
            w.claims.map(f::verified),
            result.envelope.claims.results
                .first()
                .conditions,
        )
        assertEquals(
            w.program.nodes.map { it.operator },
            result.envelope.programs
                .first()
                .program!!
                .nodes
                .map { it.operator },
        )
    }

    @Test
    fun `DSLO04 nonremote capability is not silently calculated as REMOTE and retains prepared meaning`() {
        val remote = ScheduleDslOfflinePipeline.evaluate(input(), w.json())
        val nonremote = ScheduleDslOfflinePipeline.evaluate(input().copy(meetingMode = MeetingMode.IN_PERSON), w.json())
        assertEquals(IrCalculationStatus.REQUIRES_DECISION, nonremote.status)
        assertTrue(nonremote.candidates.candidates.isEmpty())
        assertTrue(nonremote.recommendations.options.isEmpty())
        assertEquals(remote.envelope, nonremote.envelope)
        assertEquals(remote.prepared, nonremote.prepared)
    }

    @ParameterizedTest
    @ValueSource(strings = ["batch_fixed", "accepted_at", "answer_revision", "duplicate_id", "semantic_version"])
    fun `DSLO05 invalid manifest temporal evidence identity or version cannot be trusted`(defect: String) {
        val original = input()
        val first = original.inputs.first()
        val invalid =
            when (defect) {
                "batch_fixed" -> original.copy(batchFixedAt = accepted.minusSeconds(1))
                "accepted_at" ->
                    original.copy(
                        inputs = listOf(first.copy(acceptedAt = f.reference.minusSeconds(1)), original.inputs.last()),
                    )
                "answer_revision" ->
                    original.copy(
                        inputs =
                            listOf(
                                first.copy(
                                    evidence =
                                        InputEvidence(
                                            3,
                                            f.reference,
                                            InputSupplement(
                                                UUID(0, 902),
                                                4,
                                                "🙂",
                                                0,
                                                "Synthetic question",
                                                "Synthetic answer",
                                                null,
                                                f.reference.plusSeconds(1),
                                                f.reference.plusSeconds(1),
                                            ),
                                        ),
                                ),
                                original.inputs.last(),
                            ),
                    )
                "duplicate_id" ->
                    original.copy(
                        inputs = listOf(first, original.inputs.last().copy(submissionVersionId = first.submissionVersionId)),
                    )
                else -> original.copy(semanticVersion = "schedule-ir-product-99")
            }
        assertFailsWith<RuntimeException> { ScheduleDslOfflinePipeline.snapshot(invalid) }
    }

    @Test
    fun `DSLO06 prepare creates private new output using real neutral pipeline without writing raw input to stdout`() {
        val manifest = input()
        val inputFile = directory.resolve("synthetic-input.json")
        val output = directory.resolve("request.json")
        Files.write(inputFile, mapper.writeValueAsBytes(manifest))
        val observed = capture { ScheduleDslOfflineEntryPoint.run(arrayOf("prepare", inputFile.toString(), output.toString())) }
        assertEquals(0, observed.first)
        assertContentEquals(ScheduleDslOfflinePipeline.prepare(manifest), Files.readAllBytes(output))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(output))
        assertTrue(Regex("[a-f0-9]{64}").containsMatchIn(observed.second))
        assertTrue(observed.second.lowercase().contains("bytes"))
        assertPrivateDiagnostic(observed.second)
    }

    @Test
    fun `DSLO07 evaluate writes private typed AST numeric result and prints only bounded diagnostics`() {
        val manifest = input()
        val inputFile = directory.resolve("synthetic-input.json")
        val response = directory.resolve("synthetic-response.json")
        val output = directory.resolve("result.json")
        Files.write(inputFile, mapper.writeValueAsBytes(manifest))
        Files.writeString(response, w.json())
        val observed =
            capture { ScheduleDslOfflineEntryPoint.run(arrayOf("evaluate", inputFile.toString(), response.toString(), output.toString())) }
        assertEquals(0, observed.first)
        assertEquals(
            mapper.readTree(mapper.writeValueAsBytes(ScheduleDslOfflinePipeline.evaluate(manifest, w.json()))),
            mapper.readTree(Files.readAllBytes(output)),
        )
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(output))
        assertPrivateDiagnostic(observed.second)
    }

    @Test
    fun `DSLO08 existing output is never overwritten and failure diagnostics reveal neither source nor exception text`() {
        val inputFile = directory.resolve("synthetic-input.json")
        val output = directory.resolve("existing.json")
        Files.write(inputFile, mapper.writeValueAsBytes(input()))
        val original = "synthetic existing output".toByteArray()
        Files.write(output, original)
        val existing = capture { ScheduleDslOfflineEntryPoint.run(arrayOf("prepare", inputFile.toString(), output.toString())) }
        assertEquals(2, existing.first)
        assertContentEquals(original, Files.readAllBytes(output))
        assertPrivateDiagnostic(existing.second)

        val marker = "SYNTHETIC_PRIVATE_PARSE_ERROR_MARKER"
        Files.writeString(inputFile, "{\"$marker\":not valid JSON}")
        val absentOutput = directory.resolve("must-not-exist.json")
        val malformed = capture { ScheduleDslOfflineEntryPoint.run(arrayOf("prepare", inputFile.toString(), absentOutput.toString())) }
        assertEquals(2, malformed.first)
        assertFalse(Files.exists(absentOutput))
        assertFalse(malformed.second.contains(marker))
        assertPrivateDiagnostic(malformed.second)
    }

    private fun assertPrivateDiagnostic(text: String) {
        assertFalse(text.contains(f.FIRST_TEXT))
        assertFalse(text.contains(f.SECOND_TEXT))
        assertFalse(text.contains("Exception"))
        assertFalse(text.contains("at com.meetme"))
    }

    private fun capture(action: () -> Int): Pair<Int, String> {
        val previousOut = System.out
        val previousErr = System.err
        val bytes = ByteArrayOutputStream()
        val stream = PrintStream(bytes, true, Charsets.UTF_8)
        return try {
            System.setOut(stream)
            System.setErr(stream)
            val code = action()
            stream.flush()
            code to bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            stream.close()
        }
    }

    companion object {
        private const val MANIFEST = "schedule-dsl-pilot-input-1"
    }
}
