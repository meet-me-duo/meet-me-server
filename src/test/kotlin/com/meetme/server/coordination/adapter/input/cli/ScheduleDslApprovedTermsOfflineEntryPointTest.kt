package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslApprovedTermsProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextProviderContract
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Synthetic DB-free replay only; not dispatcher, winner, live-model or language-accuracy evidence. */
@Isolated("Captures process streams around the existing CLI run entry point")
class ScheduleDslApprovedTermsOfflineEntryPointTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `ATCLI01 native snapshot and prepare preserve question bindings while selecting response3 and frozen mode`() {
        val input = input(withQuestion = true).copy(meetingMode = MeetingMode.IN_PERSON)
        val legacy = legacy(input)
        val expected = F.capsule(legacy)
        val oldBytes = ScheduleDslContextProviderContract(mapper).requestBytes(expected)
        val capsule = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
        assertEquals(expected.snapshot.context, capsule.snapshot.context)
        assertEquals(expected.questions, capsule.questions)
        assertEquals(F.q.PROMPT, capsule.questions.single().prompt)
        assertEquals(F.q.CHOICE, capsule.questions.single().choiceId)
        assertFalse(capsule.questions.single().choiceDefinitionsAvailable)
        for (item in input.inputs) {
            assertEquals(item.rawText, capsule.snapshot.sourceText(item.submissionVersionId, "original"))
        }
        val prepared = ScheduleDslOfflinePipeline.prepareApprovedTerms(input)
        assertContentEquals(ScheduleDslApprovedTermsProviderContract(mapper).requestBytes(capsule, input.meetingMode), prepared)
        val request = mapper.readTree(prepared)
        assertEquals("schedule-dsl-request-3", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-3", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-4", request.path("semantic_version").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        assertEquals("IN_PERSON", request.path("context").path("meeting_mode").asString())
        assertEquals(mapper.readTree(oldBytes).path("inputs"), request.path("inputs"))
        assertContentEquals(oldBytes, ScheduleDslContextProviderContract(mapper).requestBytes(expected))
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslOfflinePipeline.snapshotApprovedTerms(
                input.copy(
                    batchFixedAt =
                        input.inputs
                            .first()
                            .acceptedAt
                            .minusSeconds(1),
                ),
            )
        }
    }

    @Test
    fun `ATCLI02 synthetic afternoon preference reaches strict wrapper new default and actual deterministic candidates`() {
        val input = input()
        val response = preferenceResponse(input)
        val result = ScheduleDslOfflinePipeline.evaluateApprovedTerms(input, response)
        val parsed =
            StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
                response,
                ScheduleDslOfflinePipeline.snapshotApprovedTerms(input).snapshot,
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertEquals(parsed, result.envelope)
        assertEquals("schedule-ir-product-4", result.envelope.semanticVersion)
        assertEquals("ko-time-terms-1", result.envelope.vocabularyVersion)
        val original =
            result.envelope.declaration.claims.results
                .first()
                .conditions
                .single()
        val normalized =
            result.envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrEffect.PREFERRED, original.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, original.temporalResolution)
        assertNull(original.scope)
        assertEquals(IrEffect.PREFERRED, normalized.effect)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, normalized.temporalResolution)
        assertEquals(F.scope("2027-01-08T12:00", "2027-01-09T00:00"), normalized.scope)
        assertEquals(
            normalized.scope,
            result.envelope.termApplications
                .single()
                .resolvedScope,
        )
        val prepared = result.calculation.prepared
        val participant = prepared.participants.single { it.participantId == first }
        assertEquals(listOf(afternoon()), participant.availableTimes)
        assertEquals(emptyList(), participant.explicitPreferences.timeRanges)
        assertEquals(listOf(window(14, 18)), prepared.participants.single { it.participantId == peer }.availableTimes)
        val candidate =
            result.calculation.candidates.candidates
                .single()
        assertEquals(setOf(first, peer), candidate.participantIds.toSet())
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertEquals(listOf(window(14, 18)), candidate.timeRanges)
        val option =
            result.calculation.recommendations.options
                .single()
        assertEquals(window(14, 18), option.window)
        assertEquals(0, option.variants.single().preferenceCount)
        assertEquals(IrCalculationStatus.CALCULATED, result.calculation.status)
        assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, result.calculation.precisionOutcome)
    }

    @Test
    fun `ATCLI03 old and new manifests and response documents reject each other's explicit routes`() {
        val current = input()
        val old = legacy(current)
        val context2 = old.copy(responseContractVersion = "schedule-dsl-response-2")
        assertFailsWith<IllegalArgumentException> { ScheduleDslOfflinePipeline.snapshotApprovedTerms(old) }
        assertFailsWith<IllegalArgumentException> { ScheduleDslOfflinePipeline.prepareApprovedTerms(context2) }
        assertFailsWith<IllegalArgumentException> { ScheduleDslOfflinePipeline.prepare(current) }
        assertFailsWith<IllegalArgumentException> { ScheduleDslOfflinePipeline.prepareContext(current) }
        val newRejectsOld =
            assertFailsWith<NaturalLanguageParserException> {
                ScheduleDslOfflinePipeline.evaluateApprovedTerms(current, F.q.wire.json())
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, newRejectsOld.kind)
        val oldRejectsNew =
            assertFailsWith<NaturalLanguageParserException> {
                ScheduleDslOfflinePipeline.evaluate(old, preferenceResponse(current))
            }
        val contextRejectsNew =
            assertFailsWith<NaturalLanguageParserException> {
                ScheduleDslOfflinePipeline.evaluateContext(context2, preferenceResponse(current))
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, oldRejectsNew.kind)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, contextRejectsNew.kind)
    }

    @Test
    fun `ATCLI04 unknown-scope negative blocks default precision while preserving the independent peer`() {
        val input = input("🙂 2027-01-08 불가 범위 미정.")
        val negative = F.claim(input, "negative", IrEffect.UNRESOLVED).copy(interpretation = IrInterpretation.UNRESOLVED)
        val program = F.program(listOf(F.ref(input, "block", "negative")), null, block = "block")
        val response = F.json(F.document(F.proposal(input, listOf(negative), program, emptyList())))
        val result = ScheduleDslOfflinePipeline.evaluateApprovedTerms(input, response)
        val prepared = result.calculation.prepared
        assertTrue(
            prepared.participants
                .single { it.participantId == first }
                .availableTimes
                .isEmpty(),
        )
        assertEquals(listOf(searchWindow()), prepared.evidence.getValue(first).possibleWindows)
        assertTrue(prepared.evidence.getValue(first).unresolvedTime)
        assertEquals(listOf(window(14, 18)), prepared.participants.single { it.participantId == peer }.availableTimes)
        assertTrue(
            result.calculation.candidates.candidates
                .isEmpty(),
        )
        assertTrue(
            result.calculation.recommendations.options
                .isEmpty(),
        )
        assertEquals(IrCalculationStatus.PARTIAL, result.calculation.status)
        assertEquals(IrPrecisionOutcome.NO_PRECISE_CANDIDATES, result.calculation.precisionOutcome)
        assertTrue(result.envelope.termApplications.isEmpty())
        val kept =
            result.envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single()
        assertEquals(IrEffect.UNRESOLVED, kept.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, kept.temporalResolution)
        assertNull(kept.scope)
    }

    @Test
    fun `ATCLI05 actual new command routing writes private create-new outputs and prints only metadata`() {
        val input = input(withQuestion = true)
        val manifest = directory.resolve("synthetic-manifest.json")
        val response = directory.resolve("synthetic-response.json")
        val prepared = directory.resolve("prepared.json")
        val evaluated = directory.resolve("result.json")
        Files.write(manifest, mapper.writeValueAsBytes(input))
        Files.writeString(response, preferenceResponse(input))
        val preparation = capture(arrayOf("prepare-approved-terms", manifest.toString(), prepared.toString()))
        assertEquals(0, preparation.first)
        assertPrivateFile(prepared)
        assertContentEquals(ScheduleDslOfflinePipeline.prepareApprovedTerms(input), Files.readAllBytes(prepared))
        assertMetadata(preparation.second, input, Files.readAllBytes(prepared))
        assertEquals("PREPARED", mapper.readTree(preparation.second.trim()).path("status").asString())
        val evaluation = capture(arrayOf("evaluate-approved-terms", manifest.toString(), response.toString(), evaluated.toString()))
        assertEquals(0, evaluation.first)
        assertPrivateFile(evaluated)
        val expected = ScheduleDslOfflinePipeline.evaluateApprovedTerms(input, preferenceResponse(input))
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(expected)), mapper.readTree(Files.readAllBytes(evaluated)))
        assertMetadata(evaluation.second, input, Files.readAllBytes(evaluated))
        assertEquals(expected.calculation.status.name, mapper.readTree(evaluation.second.trim()).path("status").asString())
        val bytes = Files.readAllBytes(evaluated)
        val repeat = capture(arrayOf("evaluate-approved-terms", manifest.toString(), response.toString(), evaluated.toString()))
        assertEquals(2, repeat.first)
        assertContentEquals(bytes, Files.readAllBytes(evaluated))
        assertMetadata(repeat.second, input, null)
    }

    @Test
    fun `ATCLI06 malformed or extra-field input and invalid response fail without output or sensitive diagnostics`() {
        val input = input(withQuestion = true)
        val manifest = directory.resolve("private-marker-manifest.json")
        val response = directory.resolve("private-marker-response.json")
        val missing = directory.resolve("must-not-exist.json")
        val marker = "SYNTHETIC_APPROVED_TERMS_PRIVATE_MARKER"
        val extra = mapper.readValue(mapper.writeValueAsBytes(input), Map::class.java).toMutableMap()
        extra["unexpected"] = marker
        for (invalid in listOf(mapper.writeValueAsString(extra), "{\"$marker\":not JSON}")) {
            Files.writeString(manifest, invalid)
            val rejected = capture(arrayOf("prepare-approved-terms", manifest.toString(), missing.toString()))
            assertEquals(2, rejected.first)
            assertFalse(Files.exists(missing))
            assertFalse(rejected.second.contains(marker))
            assertMetadata(rejected.second, input, null)
            assertEquals("INVALID_INPUT", mapper.readTree(rejected.second.trim()).path("status").asString())
        }
        Files.write(manifest, mapper.writeValueAsBytes(input))
        Files.writeString(response, "{\"$marker\":not JSON}")
        val rejected = capture(arrayOf("evaluate-approved-terms", manifest.toString(), response.toString(), missing.toString()))
        assertEquals(2, rejected.first)
        assertFalse(Files.exists(missing))
        assertFalse(rejected.second.contains(marker))
        assertMetadata(rejected.second, input, null)
        assertEquals("INVALID_RESPONSE", mapper.readTree(rejected.second.trim()).path("status").asString())
    }

    private fun input(
        text: String = "🙂 2027-01-08 오후 선호.",
        withQuestion: Boolean = false,
    ): ScheduleDslOfflineInput {
        val base = F.input(text)
        return base.copy(
            responseContractVersion = "schedule-dsl-response-3",
            semanticVersion = "schedule-ir-product-4",
            inputs =
                base.inputs.mapIndexed { index, item ->
                    if (withQuestion && index == 0) {
                        item.copy(
                            evidence =
                                F.q
                                    .input1()
                                    .inputs
                                    .first()
                                    .evidence,
                        )
                    } else {
                        item
                    }
                },
        )
    }

    private fun legacy(input: ScheduleDslOfflineInput) =
        input.copy(
            responseContractVersion = SCHEDULE_DSL_RESPONSE_CONTRACT,
            semanticVersion = SCHEDULE_DSL_SEMANTIC_VERSION,
        )

    private fun preferenceResponse(input: ScheduleDslOfflineInput): String {
        val preference = F.claim(input, effect = IrEffect.PREFERRED)
        val program = F.program(listOf(F.ref(input, "pref", "half")), null, prefer = "pref")
        return F.json(F.document(F.proposal(input, listOf(preference), program)))
    }

    private fun assertPrivateFile(path: Path) {
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
    }

    private fun assertMetadata(
        text: String,
        input: ScheduleDslOfflineInput,
        output: ByteArray?,
    ) {
        for (privateText in input.inputs.map { it.rawText } + listOf(F.q.PROMPT, F.q.CHOICE, F.q.answer.answerText, "Exception")) {
            assertFalse(text.contains(privateText))
        }
        assertFalse(text.contains(directory.toString()))
        val metadata = mapper.readTree(text.trim())
        assertEquals(3, metadata.size())
        assertTrue(metadata.has("status") && metadata.has("sha256") && metadata.has("bytes"))
        if (output == null) {
            assertTrue(metadata.path("sha256").isNull)
            assertEquals(0, metadata.path("bytes").asInt())
        } else {
            val hash = MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) }
            assertEquals(hash, metadata.path("sha256").asString())
            assertEquals(output.size, metadata.path("bytes").asInt())
        }
    }

    private fun capture(args: Array<String>): Pair<Int, String> = ScheduleDslNativeProcessCapture.run(args)

    private fun searchWindow() =
        InstantTimeRange(
            LocalDateTime.of(2027, 1, 4, 0, 0).atZone(zone).toInstant(),
            LocalDateTime.of(2027, 1, 11, 0, 0).atZone(zone).toInstant(),
        )

    private fun afternoon() =
        InstantTimeRange(
            LocalDateTime.of(2027, 1, 8, 12, 0).atZone(zone).toInstant(),
            LocalDateTime.of(2027, 1, 9, 0, 0).atZone(zone).toInstant(),
        )

    private fun window(
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        LocalDateTime.of(2027, 1, 8, start, 0).atZone(zone).toInstant(),
        LocalDateTime.of(2027, 1, 8, end, 0).atZone(zone).toInstant(),
    )
}
