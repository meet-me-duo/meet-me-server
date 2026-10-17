package com.meetme.server.precheck.domain

import java.time.Instant
import java.util.UUID

enum class PrecheckStatus {
    QUEUED,
    CHECKING,
    NEEDS_CHOICE,
    NEEDS_EDIT,
    CLEAR,
    FAILED,
    FAILED_QUEUE,
    TIMED_OUT,
    LIMIT_REACHED,
    CANCELLED,
    SUPERSEDED,
    CLOSED_BEFORE_COMPLETION,
}

data class PrecheckChoice(
    val id: String,
    val label: String,
    val proposedText: String,
)

data class PrecheckQuestion(
    val id: UUID,
    val prompt: String,
    val sourceExcerpt: String,
    val choices: List<PrecheckChoice>,
)

data class PrecheckCandidate(
    val id: UUID,
    val roomId: UUID,
    val participantId: UUID,
    val revision: Int,
    val rawText: String,
    val locale: String,
    val createdAt: Instant,
    val referenceAt: Instant,
    val ownerBinding: String,
    val sourceCandidateId: UUID? = null,
    val sourceQuestionId: UUID? = null,
    val selectedChoiceId: String? = null,
    val acceptedVersionId: UUID? = null,
)

data class PrecheckExecution(
    val id: UUID,
    val candidate: PrecheckCandidate,
    val status: PrecheckStatus,
    val version: Long,
    val generation: Int,
    val retryCount: Int,
    val deadlineAt: Instant,
    val question: PrecheckQuestion? = null,
    val ownerToken: UUID? = null,
    val claimedAt: Instant? = null,
    val failureCode: String? = null,
)
