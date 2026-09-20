package com.meetme.server.submission.application.port.input

import com.meetme.server.submission.domain.ManualAvailability
import java.time.Instant
import java.util.Locale

data class SaveSubmissionCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val rawText: String?,
    val manualAvailability: List<ManualAvailability>,
    val locale: Locale,
)

data class SubmissionView(
    val revision: Int,
    val rawText: String?,
    val manualAvailability: List<ManualAvailability>,
    val locale: String,
    val createdAt: Instant,
    val editable: Boolean,
)

interface SaveSubmissionUseCase {
    fun save(command: SaveSubmissionCommand): SubmissionView
}

interface GetOwnSubmissionUseCase {
    fun get(
        inviteCode: String,
        rawCredential: String?,
    ): SubmissionView
}

enum class SubmissionErrorCode {
    SUBMISSION_INPUT_REQUIRED,
    SUBMISSION_TEXT_TOO_LONG,
    SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED,
    SUBMISSION_TIME_RANGE_INVALID,
    SUBMISSION_TIME_RANGE_MODE_MISMATCH,
    SUBMISSION_NOT_FOUND,
    PARTICIPANT_REQUIRED,
    ANALYSIS_NOT_DELAYED,
}

class SubmissionException(
    val code: SubmissionErrorCode,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(code.name)
