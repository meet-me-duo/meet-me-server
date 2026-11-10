package com.meetme.server.coordination.adapter.input.cli

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslApprovedTermsProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextModeProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslGeminiWire
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslProviderContract
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextModeResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslResponseParser
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslNodeDisposition
import com.meetme.server.coordination.domain.dsl.ScheduleDslPreparedInputs
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

data class ScheduleDslOfflineSubmission(
    val submissionId: UUID,
    val submissionVersionId: UUID,
    val participantId: UUID,
    val submissionRevision: Int,
    val rawText: String,
    val locale: String,
    val acceptedAt: Instant,
    val evidence: InputEvidence?,
)

data class ScheduleDslOfflineInput(
    val manifestVersion: String,
    val responseContractVersion: String,
    val semanticVersion: String,
    val astVersion: String,
    val runId: UUID,
    val batchId: UUID,
    val roomId: UUID,
    val batchFixedAt: Instant,
    val timeZone: String,
    val searchStart: LocalDate,
    val searchEnd: LocalDate,
    val meetingMode: MeetingMode,
    val policy: IrMatchingPolicy,
    val inputs: List<ScheduleDslOfflineSubmission>,
)

data class ScheduleDslOfflineResult(
    val envelope: ScheduleDslEnvelope,
    val prepared: ScheduleDslPreparedInputs,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val precisionOutcome: IrPrecisionOutcome,
)

data class ScheduleDslApprovedTermsOfflineResult(
    val envelope: ScheduleDslApprovedTermsEnvelope,
    val calculation: ScheduleDslCalculation,
)

data class ScheduleDslContextModeOfflineResult(
    val envelope: ScheduleDslContextModeEnvelope,
    val calculation: ScheduleDslContextModeCalculation,
)

object ScheduleDslOfflinePipeline {
    private val mapper = offlineMapper()
    private val token = Regex("[A-Za-z0-9._:-]{1,64}")

    fun snapshotContextMode(input: ScheduleDslOfflineInput): ScheduleDslProviderInputCapsule {
        require(input.policy == ScheduleDslContextModePolicy.contextual(input.policy.alternativeChoices))
        val (snapshot, submissions) =
            nativeInput(
                input,
                SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT,
                SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION,
            )
        return ScheduleDslProviderInputCapsule.create(snapshot, submissions)
    }

    fun prepareContextMode(input: ScheduleDslOfflineInput): ByteArray =
        ScheduleDslContextModeProviderContract(mapper).requestBytes(snapshotContextMode(input), input.meetingMode)

    fun evaluateContextMode(
        input: ScheduleDslOfflineInput,
        responseJson: String,
    ): ScheduleDslContextModeOfflineResult {
        val snapshot = snapshotContextMode(input).snapshot
        val envelope =
            StrictScheduleDslContextModeResponseParser(mapper).parse(
                responseJson,
                snapshot,
                input.responseContractVersion,
                input.semanticVersion,
                input.astVersion,
                APPROVED_KOREAN_TIME_TERM_VERSION,
            )
        return ScheduleDslContextModeOfflineResult(
            envelope,
            ScheduleDslContextModeCalculator.calculate(envelope, snapshot, input.policy, input.meetingMode),
        )
    }

    fun snapshotApprovedTerms(input: ScheduleDslOfflineInput): ScheduleDslProviderInputCapsule {
        val (snapshot, submissions) =
            nativeInput(
                input,
                SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT,
                SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
            )
        return ScheduleDslProviderInputCapsule.create(snapshot, submissions)
    }

    fun prepareApprovedTerms(input: ScheduleDslOfflineInput): ByteArray =
        ScheduleDslApprovedTermsProviderContract(mapper).requestBytes(snapshotApprovedTerms(input), input.meetingMode)

    fun evaluateApprovedTerms(
        input: ScheduleDslOfflineInput,
        responseJson: String,
    ): ScheduleDslApprovedTermsOfflineResult {
        val snapshot = snapshotApprovedTerms(input).snapshot
        val envelope =
            StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
                responseJson,
                snapshot,
                input.responseContractVersion,
                input.semanticVersion,
                input.astVersion,
                APPROVED_KOREAN_TIME_TERM_VERSION,
            )
        return ScheduleDslApprovedTermsOfflineResult(
            envelope,
            ScheduleDslCalculator.calculateApprovedTerms(envelope, snapshot, input.policy, input.meetingMode),
        )
    }

    fun snapshotContext(input: ScheduleDslOfflineInput): ScheduleDslProviderInputCapsule {
        val (snapshot, submissions) = nativeInput(input, SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT)
        return ScheduleDslProviderInputCapsule.create(snapshot, submissions)
    }

    fun prepareContext(input: ScheduleDslOfflineInput): ByteArray =
        ScheduleDslContextProviderContract(mapper).requestBytes(snapshotContext(input))

    fun evaluateContext(
        input: ScheduleDslOfflineInput,
        responseJson: String,
    ): ScheduleDslOfflineResult {
        val snapshot = snapshotContext(input).snapshot
        val parsed =
            StrictScheduleDslContextResponseParser(mapper).parse(
                responseJson,
                snapshot,
                input.responseContractVersion,
                input.semanticVersion,
                input.astVersion,
            )
        return calculate(input, snapshot, parsed)
    }

    fun snapshot(input: ScheduleDslOfflineInput): ScheduleEvidenceSnapshot = nativeInput(input, SCHEDULE_DSL_RESPONSE_CONTRACT).first

    private fun nativeInput(
        input: ScheduleDslOfflineInput,
        expectedResponseContract: String,
        expectedSemanticVersion: String = SCHEDULE_DSL_SEMANTIC_VERSION,
    ): Pair<ScheduleEvidenceSnapshot, List<Submission>> {
        require(input.manifestVersion == "schedule-dsl-pilot-input-1") { "Unsupported offline manifest" }
        require(input.responseContractVersion == expectedResponseContract)
        require(input.semanticVersion == expectedSemanticVersion && input.astVersion == SCHEDULE_AST_VERSION)
        require(input.inputs.size in 2..50)
        require(
            input.inputs
                .map { it.submissionId }
                .distinct()
                .size == input.inputs.size,
        )
        require(
            input.inputs
                .map { it.submissionVersionId }
                .distinct()
                .size == input.inputs.size,
        )
        require(
            input.inputs
                .map { it.participantId }
                .distinct()
                .size == input.inputs.size,
        )
        require(input.inputs.all { it.acceptedAt <= input.batchFixedAt }) { "Acceptance follows frozen batch" }
        val zone = ZoneId.of(input.timeZone)
        require(ChronoUnit.DAYS.between(input.searchStart, input.searchEnd) in 1..31)
        require(input.policy.version.matches(token))
        val choices = input.policy.alternativeChoices
        require(choices.map { it.submissionVersionId to it.group }.distinct().size == choices.size)
        val versions = input.inputs.map { it.submissionVersionId }.toSet()
        require(choices.all { it.submissionVersionId in versions && it.group.matches(token) && it.alternative.matches(token) })
        val submissions =
            input.inputs.sortedBy { it.submissionVersionId }.map { item ->
                require(item.locale.isNotBlank())
                val locale = Locale.Builder().setLanguageTag(item.locale).build()
                val submission =
                    Submission.restore(
                        SubmissionId(item.submissionId),
                        MeetingRoomId(input.roomId),
                        ParticipantId(item.participantId),
                        SubmissionVersion(
                            SubmissionVersionId(item.submissionVersionId),
                            item.submissionRevision,
                            item.rawText,
                            emptyList(),
                            locale,
                            item.acceptedAt,
                            item.evidence,
                        ),
                    )
                submission
            }
        val bindings = submissions.map { IrEvidenceBindings.create(it) }
        val snapshot =
            ScheduleEvidenceSnapshot(
                ScheduleIrContext(
                    input.runId,
                    input.batchId,
                    input.roomId,
                    zone.id,
                    input.searchStart,
                    input.searchEnd,
                    input.policy.version,
                    bindings.map { it.first },
                ),
                bindings.associate { it.first.submissionVersionId to it.second },
            )
        return snapshot to submissions
    }

    fun prepare(input: ScheduleDslOfflineInput): ByteArray =
        ScheduleDslProviderContract(mapper).requestBytes(
            snapshot(input),
            input.responseContractVersion,
            input.semanticVersion,
            input.astVersion,
        )

    fun evaluate(
        input: ScheduleDslOfflineInput,
        responseJson: String,
    ): ScheduleDslOfflineResult {
        val snapshot = snapshot(input)
        val parsed =
            StrictScheduleDslResponseParser(mapper, input.responseContractVersion).parse(
                responseJson,
                snapshot,
                input.responseContractVersion,
                input.semanticVersion,
                input.astVersion,
            )
        return calculate(input, snapshot, parsed)
    }

    private fun calculate(
        input: ScheduleDslOfflineInput,
        snapshot: ScheduleEvidenceSnapshot,
        parsed: ScheduleDslEnvelope,
    ): ScheduleDslOfflineResult {
        val envelope = ScheduleDslValidator().validateRestored(parsed, snapshot)
        val prepared = ScheduleDslInputPolicy.project(envelope, input.policy)
        val candidates = DeterministicCandidateMatcher.generate(input.meetingMode, prepared.participants)
        val recommendations = RecommendationProjector.generate(input.meetingMode, prepared.participants, ZoneId.of(input.timeZone))
        val partial =
            prepared.evidence.values.any { evidence ->
                evidence.unresolvedTime ||
                    evidence.programProblems.isNotEmpty() ||
                    evidence.conditions.any { it.disposition == IrConditionDisposition.WITHHELD } ||
                    evidence.nodes.any { it.disposition == ScheduleDslNodeDisposition.WITHHELD }
            }
        val status =
            when {
                partial -> IrCalculationStatus.PARTIAL
                else -> IrCalculationStatus.CALCULATED
            }
        val precision =
            if (candidates.candidates.isEmpty()) {
                IrPrecisionOutcome.NO_PRECISE_CANDIDATES
            } else {
                IrPrecisionOutcome.PRECISE_CANDIDATES
            }
        return ScheduleDslOfflineResult(envelope, prepared, candidates, recommendations, status, precision)
    }
}

object ScheduleDslOfflineEntryPoint {
    private val mapper = offlineMapper()
    private val inputReader =
        mapper
            .readerFor(ScheduleDslOfflineInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)

    fun run(args: Array<String>): Int {
        var failure = OfflineFailure.INVALID_ARGUMENTS
        try {
            require(args.isNotEmpty())
            val operation = args[0]
            val preparing =
                operation in
                    setOf(
                        "prepare",
                        "prepare-gemini",
                        "prepare-context",
                        "prepare-context-gemini",
                        "prepare-approved-terms",
                        "prepare-context-mode",
                    )
            val evaluating =
                operation in
                    setOf(
                        "evaluate",
                        "evaluate-gemini",
                        "evaluate-context",
                        "evaluate-context-gemini",
                        "evaluate-approved-terms",
                        "evaluate-context-mode",
                    )
            require(preparing || evaluating)
            require(args.size == if (preparing) 3 else 4)
            failure = OfflineFailure.INVALID_INPUT
            val input = inputReader.readValue<ScheduleDslOfflineInput>(readBounded(Path.of(args[1])))
            val contextual = operation in setOf("prepare-context", "prepare-context-gemini", "evaluate-context", "evaluate-context-gemini")
            val approvedTerms = operation in setOf("prepare-approved-terms", "evaluate-approved-terms")
            val contextMode = operation in setOf("prepare-context-mode", "evaluate-context-mode")
            if (contextMode) {
                ScheduleDslOfflinePipeline.snapshotContextMode(input)
            } else if (approvedTerms) {
                ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
            } else if (contextual) {
                ScheduleDslOfflinePipeline.snapshotContext(input)
            } else {
                ScheduleDslOfflinePipeline.snapshot(input)
            }
            val bytes: ByteArray
            val status: String
            if (preparing) {
                bytes =
                    when (operation) {
                        "prepare-context-mode" -> ScheduleDslOfflinePipeline.prepareContextMode(input)
                        "prepare-approved-terms" -> ScheduleDslOfflinePipeline.prepareApprovedTerms(input)
                        "prepare-context-gemini" ->
                            ScheduleDslGeminiWire.requestContextBytes(
                                mapper,
                                ScheduleDslOfflinePipeline.snapshotContext(input),
                                ScheduleDslGeminiWire.PINNED_MODEL,
                            )
                        "prepare-context" -> ScheduleDslOfflinePipeline.prepareContext(input)
                        "prepare-gemini" ->
                            ScheduleDslGeminiWire.requestBytes(
                                mapper,
                                ScheduleDslOfflinePipeline.snapshot(input),
                                ScheduleDslGeminiWire.PINNED_MODEL,
                            )
                        else -> ScheduleDslOfflinePipeline.prepare(input)
                    }
                status = "PREPARED"
            } else {
                failure = OfflineFailure.INVALID_RESPONSE
                val response = readBounded(Path.of(args[2]))
                val json =
                    if (operation in setOf("evaluate-gemini", "evaluate-context-gemini")) {
                        ScheduleDslGeminiWire.response(mapper, response).responseJson
                    } else {
                        val text = response.toString(Charsets.UTF_8)
                        require(text.toByteArray(Charsets.UTF_8).contentEquals(response)) { "Invalid response encoding" }
                        text
                    }
                if (contextMode) {
                    val result = ScheduleDslOfflinePipeline.evaluateContextMode(input, json)
                    failure = OfflineFailure.OUTPUT_FAILURE
                    bytes = mapper.writeValueAsBytes(result)
                    status = result.calculation.status.name
                } else if (approvedTerms) {
                    val result = ScheduleDslOfflinePipeline.evaluateApprovedTerms(input, json)
                    failure = OfflineFailure.OUTPUT_FAILURE
                    bytes = mapper.writeValueAsBytes(result)
                    status = result.calculation.status.name
                } else {
                    val result =
                        if (contextual) {
                            ScheduleDslOfflinePipeline.evaluateContext(input, json)
                        } else {
                            ScheduleDslOfflinePipeline.evaluate(input, json)
                        }
                    failure = OfflineFailure.OUTPUT_FAILURE
                    bytes = mapper.writeValueAsBytes(result)
                    status = result.status.name
                }
            }
            failure = OfflineFailure.OUTPUT_FAILURE
            privateOutput(Path.of(args.last()), bytes)
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            println("{\"status\":\"$status\",\"sha256\":\"$hash\",\"bytes\":${bytes.size}}")
            return 0
        } catch (_: IOException) {
            failure = OfflineFailure.IO_FAILURE
        } catch (_: Exception) {
            // Never include a source path, raw input/response, parser message or exception cause.
        }
        println("{\"status\":\"${failure.name}\",\"sha256\":null,\"bytes\":0}")
        return 2
    }

    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(run(args))
    }

    private fun readBounded(path: Path): ByteArray =
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(1_048_577)
            require(bytes.size <= 1_048_576) { "Offline input exceeds size limit" }
            bytes
        }

    private fun privateOutput(
        path: Path,
        bytes: ByteArray,
    ) {
        Files
            .newByteChannel(
                path,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
    }

    private enum class OfflineFailure { INVALID_ARGUMENTS, INVALID_INPUT, INVALID_RESPONSE, OUTPUT_FAILURE, IO_FAILURE }
}

private fun offlineMapper(): JsonMapper =
    JsonMapper
        .builder()
        .findAndAddModules()
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .build()
