package com.meetme.server.participant.application.port.output

import java.time.Duration
import java.util.UUID

fun interface GuestReaccessAttemptPort {
    fun check(
        roomId: UUID,
        participantId: UUID,
        clientAddress: String,
    )
}

enum class GuestReaccessLimitCode {
    TOO_MANY_ATTEMPTS,
    LIMIT_UNAVAILABLE,
}

class GuestReaccessLimitException(
    val code: GuestReaccessLimitCode,
    val retryAfter: Duration? = null,
) : RuntimeException(code.name)
