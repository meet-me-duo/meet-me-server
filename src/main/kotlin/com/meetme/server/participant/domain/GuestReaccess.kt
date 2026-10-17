package com.meetme.server.participant.domain

import java.util.UUID

/** Immutable policy input obtained inside the caller's ownership transaction. */
data class GuestReaccessRecord(
    val roomId: UUID,
    val participantId: UUID,
    val ownerSessionId: UUID?,
    val passwordHash: String?,
    val memberLinked: Boolean = false,
) {
    override fun toString(): String = "GuestReaccessRecord(redacted)"
}

/** activeSessionId must come from a server-validated, unexpired and unrevoked session. */
data class GuestReaccessProof(
    val roomId: UUID,
    val participantId: UUID,
    val activeSessionId: UUID?,
    val rawPassword: String?,
) {
    override fun toString(): String = "GuestReaccessProof(redacted)"
}

/** Grants submission access only. OWNER preserves the caller's existing authority. */
enum class GuestReaccessDecision {
    OWNER,
    VERIFIED_GUEST,
    REPLACE_ONLY,
    DENIED,
}
