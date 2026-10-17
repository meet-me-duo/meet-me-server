package com.meetme.server.account.application.port.output

import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.OAuthAttempt
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.ProviderIdentity
import com.meetme.server.account.domain.RefreshRotation
import java.util.UUID

fun interface CurrentAccountPort {
    fun currentAccount(): AccountPrincipal?
}

interface AccountRepository {
    fun findOrCreateIdentity(
        provider: OAuthProvider,
        subject: String,
        displayName: String,
    ): AccountPrincipal
}

interface AccessTokenPort {
    fun issue(principal: AccountPrincipal): String

    fun verify(token: String): AccountPrincipal
}

interface RefreshSessionPort {
    fun create(principal: AccountPrincipal): RefreshRotation

    fun rotate(token: String): RefreshRotation

    fun revoke(token: String)

    fun revokeAll(accountId: UUID)
}

interface OAuthStatePort {
    fun save(
        state: String,
        attempt: OAuthAttempt,
    )

    fun consume(state: String): OAuthAttempt?
}

interface OAuthProviderPort {
    fun authorizationUri(
        provider: OAuthProvider,
        state: String,
        codeChallenge: String,
        nonce: String,
    ): String

    fun exchange(
        provider: OAuthProvider,
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): ProviderIdentity
}
