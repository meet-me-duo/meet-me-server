package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.IssuedGuestCredential
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

class GuestCredentialService(
    private val secureRandom: SecureRandom = SecureRandom(),
) : GuestCredentialPort {
    override fun issue(createdAt: Instant): IssuedGuestCredential {
        val entropy = ByteArray(32)
        secureRandom.nextBytes(entropy)
        val rawCredential = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        return IssuedGuestCredential(
            rawCredential = rawCredential,
            credentialDigest = digest(rawCredential),
            expiresAt = createdAt.plus(Duration.ofDays(30)),
        )
    }

    override fun digest(rawCredential: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawCredential.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
