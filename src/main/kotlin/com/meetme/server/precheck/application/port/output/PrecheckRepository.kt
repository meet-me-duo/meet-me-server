package com.meetme.server.precheck.application.port.output

import com.meetme.server.precheck.application.port.input.PrecheckOutcome
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import java.time.Instant
import java.util.UUID

data class PrecheckOperation(
    val fingerprint: String,
    val outcome: PrecheckOutcome,
)

interface PrecheckRepository {
    fun protocol(roomId: UUID): String

    fun current(participantId: UUID): PrecheckExecution?

    fun find(checkId: UUID): PrecheckExecution?

    fun insert(
        candidate: PrecheckCandidate,
        check: PrecheckExecution,
    )

    fun update(check: PrecheckExecution)

    fun operation(
        participantId: UUID,
        kind: String,
        requestId: UUID,
    ): PrecheckOperation?

    fun recordOperation(
        participantId: UUID,
        kind: String,
        requestId: UUID,
        operation: PrecheckOperation,
    )

    fun latestCandidateTextLength(
        roomId: UUID,
        excludingParticipantId: UUID,
    ): Int

    fun questionCount(
        roomId: UUID,
        participantId: UUID,
    ): Int

    fun callCount(
        roomId: UUID,
        participantId: UUID,
    ): Int

    fun queueSize(): Int

    fun lockAdmission()

    fun queuedCheckIds(limit: Int): List<UUID>

    fun unfinishedCheckIds(limit: Int): List<UUID>

    fun inFlight(now: Instant): Int

    fun startAttempt(
        check: PrecheckExecution,
        at: Instant,
    )

    fun finishAttempt(
        check: PrecheckExecution,
        result: PrecheckProviderResult?,
        at: Instant,
    )

    fun anchor(versionId: UUID): Instant?

    fun bindAccepted(
        candidateId: UUID,
        versionId: UUID,
    )
}
