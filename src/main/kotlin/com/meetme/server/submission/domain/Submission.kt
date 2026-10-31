package com.meetme.server.submission.domain

import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import java.time.Instant
import java.util.Locale

sealed interface ManualAvailability {
    data class Dated(
        val range: DatedTimeRange,
    ) : ManualAvailability

    data class Weekly(
        val range: WeeklyTimeRange,
    ) : ManualAvailability
}

data class SubmissionVersion(
    val id: SubmissionVersionId,
    val revision: Int,
    val rawText: String?,
    val manualAvailability: List<ManualAvailability>,
    val locale: Locale,
    val createdAt: Instant,
    val evidence: InputEvidence? = null,
)

data class Submission private constructor(
    val id: SubmissionId,
    val roomId: MeetingRoomId,
    val participantId: ParticipantId,
    val latest: SubmissionVersion,
) {
    companion object {
        fun start(
            id: SubmissionId,
            roomId: MeetingRoomId,
            participantId: ParticipantId,
            versionId: SubmissionVersionId,
            rawText: String?,
            manualAvailability: List<ManualAvailability>,
            locale: Locale,
            at: Instant,
            evidence: InputEvidence? = null,
        ): Submission =
            Submission(
                id = id,
                roomId = roomId,
                participantId = participantId,
                latest = createVersion(versionId, 1, rawText, manualAvailability, locale, at, evidence),
            )

        fun restore(
            id: SubmissionId,
            roomId: MeetingRoomId,
            participantId: ParticipantId,
            latest: SubmissionVersion,
        ): Submission = Submission(id, roomId, participantId, latest)

        private fun createVersion(
            id: SubmissionVersionId,
            revision: Int,
            rawText: String?,
            manualAvailability: List<ManualAvailability>,
            locale: Locale,
            at: Instant,
            evidence: InputEvidence? = null,
        ): SubmissionVersion {
            val hasText = !rawText?.let(SubmissionRules::trimRawText).isNullOrEmpty()
            require(hasText || manualAvailability.isNotEmpty()) { "Submission input is required" }
            if (rawText != null) {
                require(rawText.codePointCount(0, rawText.length) <= 500) { "Natural language input must not exceed 500 code points" }
            }
            require(locale.toLanguageTag().isNotBlank()) { "Locale must not be blank" }
            if (evidence != null) evidence.validateAgainst(requireNotNull(rawText) { "Evidence requires original natural language input" })
            return SubmissionVersion(id, revision, rawText, manualAvailability.toList(), locale, at, evidence)
        }
    }

    fun revise(
        versionId: SubmissionVersionId,
        rawText: String?,
        manualAvailability: List<ManualAvailability>,
        locale: Locale,
        at: Instant,
        evidence: InputEvidence? = null,
    ): Submission =
        copy(
            latest =
                createVersion(
                    id = versionId,
                    revision = latest.revision + 1,
                    rawText = rawText,
                    manualAvailability = manualAvailability,
                    locale = locale,
                    at = at,
                    evidence = evidence,
                ),
        )
}
