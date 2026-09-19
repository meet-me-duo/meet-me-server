package com.meetme.server.application.port.output

import com.meetme.server.domain.meeting.InviteCode
import java.time.Instant
import java.util.UUID

fun interface IdGenerator {
    fun next(): UUID
}

fun interface InviteCodeGenerator {
    fun next(): InviteCode
}

data class IssuedGuestCredential(
    val rawCredential: String,
    val credentialDigest: String,
    val expiresAt: Instant,
)

interface GuestCredentialPort {
    fun issue(createdAt: Instant): IssuedGuestCredential

    fun digest(rawCredential: String): String
}
