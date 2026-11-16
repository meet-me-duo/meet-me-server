package com.meetme.server.shared.application.port.input.operations

import com.meetme.server.precheck.application.port.output.PrecheckClassificationSnapshot
import java.time.Instant
import java.util.UUID

data class OperatorAnalysis(
    val id: UUID,
    val status: String,
    val createdAt: Instant,
    val fixedAt: Instant,
    val active: Boolean,
    val contract: String,
)

data class OperatorQuestion(
    val prompt: String,
    val excerpt: String?,
    val answer: String?,
    val answeredAt: Instant?,
    val consumed: Boolean,
    val choices: List<String> = emptyList(),
    val sourceRawText: String? = null,
)

data class OperatorPrecheckAttempt(
    val generation: Int,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val failureCode: String?,
    val classification: PrecheckClassificationSnapshot?,
)

data class OperatorInputCheck(
    val rawText: String,
    val createdAt: Instant,
    val referenceAt: Instant,
    val currentStatus: String,
    val question: OperatorQuestion?,
    val attempts: List<OperatorPrecheckAttempt>,
    val attemptsTruncated: Boolean,
)

data class OperatorInput(
    val alias: String,
    val displayName: String,
    val revision: Int?,
    val rawText: String?,
    val createdAt: Instant?,
    val referenceAt: Instant?,
    val interpretation: String?,
    val unresolvedReason: String?,
    val supplement: OperatorQuestion?,
    val checks: List<OperatorInputCheck>,
    val checksTruncated: Boolean,
    val legacyQuestions: List<OperatorQuestion> = emptyList(),
    val storedInterpretation: OperatorStoredInterpretation? = null,
)

data class OperatorTimelineEvent(
    val kind: String,
    val at: Instant?,
    val status: String,
)

data class OperatorResult(
    val startAt: Instant,
    val endAt: Instant,
    val meetingMode: String,
    val placeName: String?,
    val placeUnverified: Boolean,
    val availableAliases: List<String>,
)

data class OperatorRoomDiagnostics(
    val roomId: UUID,
    val scope: String,
    val analysis: OperatorAnalysis?,
    val inputs: List<OperatorInput>,
    val inputsTruncated: Boolean,
    val invocations: List<InvocationDetail>,
    val invocationsTruncated: Boolean,
    val timeline: List<OperatorTimelineEvent>,
    val timelineTruncated: Boolean,
    val results: List<OperatorResult>,
    val resultsTruncated: Boolean,
)

data class OperatorStoredCondition(
    val effect: String,
    val interpretation: String,
    val temporalResolution: String,
    val dimension: String?,
    val start: String?,
    val end: String?,
)

data class OperatorStoredInterpretation(
    val coverage: String,
    val conditions: List<OperatorStoredCondition>,
    val problems: List<String>,
    val truncated: Boolean,
)
