package com.meetme.server.coordination.application.port.input

import com.meetme.server.coordination.domain.RevisionAnalysisOutcome
import com.meetme.server.meetingroom.application.port.input.RevisionRoundView
import com.meetme.server.meetingroom.application.port.input.RoomView
import java.util.UUID

data class ReopenInputCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val requestId: UUID,
    val sourceAnalysisId: UUID,
    val expectedGeneration: Long,
)

data class AnalyzeRevisionCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val revisionRoundId: UUID,
    val requestId: UUID,
    val forceReparse: Boolean = false,
)

data class ReopenedInputView(
    val room: RoomView,
    val round: RevisionRoundView,
)

data class RevisionAnalysisView(
    val outcome: RevisionAnalysisOutcome,
    val analysisId: UUID,
    val revisionRoundId: UUID,
    val room: RoomView,
)

interface InputRevisionUseCase {
    fun reopen(command: ReopenInputCommand): ReopenedInputView

    fun analyze(command: AnalyzeRevisionCommand): RevisionAnalysisView
}
