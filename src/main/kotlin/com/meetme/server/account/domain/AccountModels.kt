package com.meetme.server.account.domain

import java.time.Instant
import java.util.UUID

data class AccountPrincipal(
    val accountId: UUID,
    val displayName: String,
)

enum class OAuthProvider { GOOGLE, KAKAO }

class AuthFailure(
    val code: String,
) : RuntimeException(code)

data class RefreshRotation(
    val principal: AccountPrincipal,
    val refreshToken: String,
    val expiresAt: Instant,
    val absoluteExpiresAt: Instant,
)

data class OAuthAttempt(
    val provider: OAuthProvider,
    val browserBindingDigest: String,
    val codeVerifier: String,
    val nonce: String,
    val expiresAt: Instant,
)

data class ProviderIdentity(
    val subject: String,
    val displayName: String,
)
