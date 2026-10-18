package com.meetme.server.shared.application.port.output

import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Called only within the budget application transaction, after current execution locks. */
interface DurableAiBudgetRepository {
    fun lockRoom(roomId: UUID): AiRoomBudgetSnapshot?

    fun findRoom(roomId: UUID): AiRoomBudgetSnapshot?

    fun lockExistingRoom(roomId: UUID): Boolean

    fun configuration(roomId: UUID): AiBudgetRoomConfiguration?

    fun findCall(
        id: UUID,
        lock: Boolean = false,
    ): AiStoredBudgetCall?

    fun findPhysical(
        execution: AiBudgetExecution,
        call: AiPhysicalCallSpec,
    ): AiStoredBudgetCall?

    fun insertProfile(
        snapshotFingerprint: String,
        quote: AiVerifiedBudgetQuote,
    ): Boolean

    fun insertCall(
        call: AiStoredBudgetCall,
        profileSnapshot: String,
    )

    fun updateRoom(snapshot: AiRoomBudgetSnapshot)

    fun claim(
        id: UUID,
        dispatchToken: UUID,
        deadline: Instant,
        at: Instant,
    ): Boolean

    fun updateAccounting(
        id: UUID,
        state: AiBudgetCallState,
        hold: BigInteger,
        settled: BigInteger?,
        evidence: String?,
        at: Instant,
    )

    fun eventFingerprint(
        reservationId: UUID,
        eventKey: String,
    ): String?

    fun insertEvent(event: AiBudgetStoredEvent)
}

data class AiBudgetRoomConfiguration(
    val policyFingerprint: String?,
    val coverageStartedAt: Instant?,
)

enum class AiBudgetCallState { RESERVED, DISPATCH_INTENT, UNKNOWN, SETTLED }

data class AiStoredBudgetCall(
    val id: UUID,
    val execution: AiBudgetExecution,
    val call: AiPhysicalCallSpec,
    val quote: AiVerifiedBudgetQuote,
    val epochId: UUID,
    val state: AiBudgetCallState,
    val hold: BigInteger,
    val reservedAt: Instant,
    val dispatchToken: UUID? = null,
    val deadlineAt: Instant? = null,
    val settled: BigInteger? = null,
    val settledEvidence: String? = null,
)

data class AiBudgetStoredEvent(
    val reservationId: UUID,
    val key: String,
    val fingerprint: String,
    val at: Instant,
    val observation: AiBudgetObservation? = null,
    val billAmount: BigInteger? = null,
    val evidenceFingerprint: String? = null,
    val accountingFailure: Boolean = false,
)
