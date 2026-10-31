package com.meetme.server.precheck.application.port.input

import com.meetme.server.precheck.domain.PrecheckQuestion
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.InputEvidence
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class CreatePrecheckCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val requestId: UUID,
    val expectedRevision: Int,
    val rawText: String?,
    val locale: Locale,
)

data class PrecheckMutationCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val checkId: UUID,
    val requestId: UUID,
    val expectedRevision: Int,
    val expectedCheckVersion: Long,
    val questionId: UUID? = null,
    val choiceId: String? = null,
    val directText: String? = null,
)

data class PrecheckTextView(
    val revision: Int,
    val rawText: String,
    val createdAt: Instant,
    val referenceAt: Instant,
    val evidence: InputEvidence? = null,
)

data class PrecheckView(
    val id: UUID,
    val status: PrecheckStatus,
    val version: Long,
    val question: PrecheckQuestion?,
    val retryAllowed: Boolean,
    val failureCode: String?,
)

data class PrecheckRoomView(
    val collectionStatus: String,
    val stateVersion: Long,
    val submissionDeadline: Instant?,
)

data class PrecheckOutcome(
    val requestId: UUID,
    val candidateRevision: Int,
    val checkId: UUID,
    val checkStatus: PrecheckStatus,
)

data class PrecheckAcceptedView(
    val revision: Int,
    val rawText: String,
    val createdAt: Instant,
    val referenceAt: Instant,
    val confirmationSource: String,
    val evidence: InputEvidence? = null,
)

data class PrecheckSnapshot(
    val candidate: PrecheckTextView?,
    val accepted: PrecheckAcceptedView?,
    val check: PrecheckView?,
    val room: PrecheckRoomView,
    val submissionComplete: Boolean,
    val appliedToResult: Boolean,
    val outcome: PrecheckOutcome? = null,
)

interface PreSubmissionCheckUseCase {
    fun create(command: CreatePrecheckCommand): PrecheckSnapshot

    fun current(
        inviteCode: String,
        rawCredential: String?,
    ): PrecheckSnapshot

    fun answer(command: PrecheckMutationCommand): PrecheckSnapshot

    fun retry(command: PrecheckMutationCommand): PrecheckSnapshot

    fun cancel(command: PrecheckMutationCommand): PrecheckSnapshot
}

interface BlindPreSubmissionCheckUseCase {
    fun saveBlind(
        inviteCode: String,
        participantId: UUID,
        rawText: String?,
        rawCredential: String?,
        locale: Locale,
    )
}
