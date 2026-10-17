package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestReaccessAttemptPort
import com.meetme.server.participant.application.port.output.GuestReaccessLimitCode
import com.meetme.server.participant.application.port.output.GuestReaccessLimitException
import com.meetme.server.shared.application.port.output.RateLimitPort
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

class GuestReaccessAttemptGuard(
    private val limiter: RateLimitPort,
) : GuestReaccessAttemptPort {
    override fun check(
        roomId: UUID,
        participantId: UUID,
        clientAddress: String,
    ) {
        checkLimit("meetme:guest-reaccess:ip:${digest(clientAddress)}", 30)
        checkLimit("meetme:guest-reaccess:record:${digest("$roomId:$participantId")}", 5)
    }

    private fun checkLimit(
        key: String,
        limit: Int,
    ) {
        val decision =
            try {
                limiter.check(key, limit, Duration.ofMinutes(15))
            } catch (_: RuntimeException) {
                throw GuestReaccessLimitException(GuestReaccessLimitCode.LIMIT_UNAVAILABLE)
            }
        if (!decision.allowed) {
            throw GuestReaccessLimitException(
                GuestReaccessLimitCode.TOO_MANY_ATTEMPTS,
                decision.retryAfter.coerceAtLeast(Duration.ofSeconds(1)),
            )
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
