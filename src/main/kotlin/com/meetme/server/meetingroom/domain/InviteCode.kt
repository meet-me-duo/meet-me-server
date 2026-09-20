package com.meetme.server.meetingroom.domain

import java.util.Base64

@JvmInline
value class InviteCode private constructor(
    val value: String,
) {
    companion object {
        private val FORMAT = Regex("^[A-Za-z0-9_-]{22}$")

        fun fromEntropy(entropy: ByteArray): InviteCode {
            require(entropy.size == 16) { "Invite code entropy must contain exactly 16 bytes" }
            return InviteCode(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy))
        }

        fun of(value: String): InviteCode {
            require(FORMAT.matches(value)) { "Invite code must be a 22-character base64url value" }
            return InviteCode(value)
        }
    }
}
