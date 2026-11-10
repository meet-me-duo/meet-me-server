package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslApprovedTermsProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextModeProviderContract
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Real offline main with complete child stdout/stderr capture. No HTTP, database, live model or new fixture timeout. */
class ScheduleDslContextModeOfflineEntryPointTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `CMCLI01 actual prepare command preserves native sources questions vocabulary and explicit C4 identity`() {
        val input = input()
        val manifest = directory.resolve("synthetic-input.json")
        val output = directory.resolve("request.json")
        Files.write(manifest, mapper.writeValueAsBytes(input))
        val observed =
            ScheduleDslNativeProcessCapture.run(
                arrayOf("prepare-context-mode", manifest.toString(), output.toString()),
            )
        assertEquals(0, observed.first)
        privateFile(output)
        val bytes = Files.readAllBytes(output)
        assertMetadata(observed.second, "PREPARED", bytes)
        val capsule = ScheduleDslOfflinePipeline.snapshotContextMode(input)
        assertEquals(N.snapshot().context, capsule.snapshot.context)
        assertEquals(N.capsule().questions, capsule.questions)
        assertEquals(F.q.PROMPT, capsule.questions.single().prompt)
        assertEquals(F.q.CHOICE, capsule.questions.single().choiceId)
        assertFalse(capsule.questions.single().choiceDefinitionsAvailable)
        for (submission in input.inputs) {
            assertEquals(submission.rawText, capsule.snapshot.sourceText(submission.submissionVersionId, "original"))
        }
        assertContentEquals(ScheduleDslOfflinePipeline.prepareContextMode(input), bytes)
        assertContentEquals(ScheduleDslContextModeProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER), bytes)
        val request = mapper.readTree(bytes)
        assertEquals("schedule-dsl-request-4", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-4", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-5", request.path("semantic_version").asString())
        assertEquals("schedule-context-mode-1", request.path("context").path("policy_version").asString())
        assertEquals("EITHER", request.path("context").path("meeting_mode").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        val oldNative = ScheduleDslApprovedTermsProviderContract(mapper).requestBytes(N.capsule(), MeetingMode.EITHER)
        assertEquals(mapper.readTree(oldNative).path("inputs"), request.path("inputs"))
        val termEnum =
            request
                .path("schema")
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("approved_terms")
                .path("items")
                .path("properties")
                .path("term")
                .path("enum")
        assertTrue(termEnum.isArray)
        assertEquals(11, termEnum.size())
        val terms = (0 until termEnum.size()).map { requireNotNull(termEnum.path(it).asString()) }.toSet()
        assertEquals(
            setOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
            terms,
        )
    }

    @Test
    fun `CMCLI02 actual evaluate command preserves contextual provenance and hard modes without disclosing native text`() {
        val input = input()
        val manifest = directory.resolve("synthetic-input.json")
        val response = directory.resolve("synthetic-response.json")
        val output = directory.resolve("result.json")
        val responseJson = F.json(N.document())
        Files.write(manifest, mapper.writeValueAsBytes(input))
        Files.writeString(response, responseJson)
        val observed =
            ScheduleDslNativeProcessCapture.run(
                arrayOf("evaluate-context-mode", manifest.toString(), response.toString(), output.toString()),
            )
        assertEquals(0, observed.first)
        privateFile(output)
        val bytes = Files.readAllBytes(output)
        val result = ScheduleDslOfflinePipeline.evaluateContextMode(input, responseJson)
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(result)), mapper.readTree(bytes))
        assertMetadata(observed.second, "PARTIAL", bytes)
        val privateTexts = privateTexts() + responseJson
        val rendered = bytes.toString(Charsets.UTF_8)
        privateTexts.forEach { assertFalse(rendered.contains(it), "Native text leaked into offline result") }
        listOf("\"quote\"", "\"rawText\"", "\"answerText\"", "\"prompt\"", "\"sourceText\"").forEach {
            assertFalse(rendered.contains(it), "Raw evidence field leaked into offline result")
        }
        assertEquals(setOf("envelope", "calculation"), mapper.readValue(bytes, Map::class.java).keys)
        assertEquals(N.expectedEnvelope(), result.envelope)
        val declared =
            result.envelope.base.declaration.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
        val projected =
            result.envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
        assertEquals(IrInterpretation.INFERRED, declared.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertEquals(IrInterpretation.INFERRED, projected.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projected.temporalResolution)
        assertEquals(declared.scope, projected.scope)
        assertEquals(declared.evidence, projected.evidence)
        assertEquals(
            ApprovedTimeTermDerivation.MODEL_CONTEXT,
            result.envelope.contextApplications
                .single()
                .derivation,
        )
        assertTrue(
            result.envelope.base.termApplications
                .isEmpty(),
        )
        assertFalse(N.FIRST.contains("오후"))
        val first = ParticipantId(F.ir.firstParticipant)
        val calculation = result.calculation
        assertEquals(
            listOf(N.thursday, N.friday),
            calculation.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(
            listOf(N.friday),
            calculation.preparedByMode
                .getValue(MeetingMode.IN_PERSON)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        val candidates = calculation.candidates.candidates.associateBy { it.meetingMode }
        assertEquals(listOf(N.thursday, N.friday), candidates.getValue(MeetingMode.REMOTE).timeRanges)
        assertEquals(listOf(N.friday), candidates.getValue(MeetingMode.IN_PERSON).timeRanges)
        assertTrue(candidates.values.all { it.participantIds.size == 2 && it.representativePlace == null })
        val options = calculation.recommendations.options.associateBy { it.window }
        assertEquals(
            setOf(MeetingMode.REMOTE),
            options
                .getValue(N.thursday)
                .variants
                .map { it.meetingMode }
                .toSet(),
        )
        assertEquals(
            setOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON),
            options
                .getValue(N.friday)
                .variants
                .map { it.meetingMode }
                .toSet(),
        )
        assertEquals(IrCalculationStatus.PARTIAL, calculation.status)
        assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, calculation.precisionOutcome)
    }

    private fun input() = N.input().copy(responseContractVersion = "schedule-dsl-response-4", semanticVersion = "schedule-ir-product-5")

    private fun privateFile(path: Path) {
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
    }

    private fun privateTexts() = listOf(N.FIRST, N.SECOND, N.MODE_QUOTE, F.q.PROMPT, F.q.CHOICE, F.q.answer.answerText)

    private fun assertMetadata(
        captured: String,
        status: String,
        output: ByteArray,
    ) {
        for (privateText in privateTexts() + listOf(directory.toString(), "Exception")) {
            assertFalse(captured.contains(privateText), "Native text or exception leaked into complete child output")
        }
        assertEquals(1, captured.lineSequence().filter { it.isNotBlank() }.count())
        val metadata = mapper.readTree(captured.trim())
        assertEquals(setOf("status", "sha256", "bytes"), mapper.readValue(captured.trim(), Map::class.java).keys)
        assertEquals(status, metadata.path("status").asString())
        assertEquals(output.size, metadata.path("bytes").asInt())
        val hash = MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) }
        assertEquals(hash, metadata.path("sha256").asString())
    }
}
