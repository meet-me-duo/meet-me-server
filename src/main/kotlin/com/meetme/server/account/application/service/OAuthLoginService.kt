package com.meetme.server.account.application.service

import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.domain.AuthEntropy
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthAttempt
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.RefreshRotation
import java.time.Clock

class OAuthLoginService(
    private val statePort: OAuthStatePort,
    private val providerPort: OAuthProviderPort,
    private val accounts: AccountRepository,
    private val refreshSessions: RefreshSessionPort,
    private val clock: Clock,
) {
    fun begin(
        provider: OAuthProvider,
        browserBinding: String,
    ): String {
        if (browserBinding.isBlank() || browserBinding.length > 256) throw AuthFailure("AUTH_OAUTH_INVALID")
        val state = AuthEntropy.token()
        val verifier = AuthEntropy.token()
        val nonce = AuthEntropy.token()
        val uri = providerPort.authorizationUri(provider, state, AuthEntropy.digest(verifier), nonce)
        statePort.save(state, OAuthAttempt(provider, AuthEntropy.digest(browserBinding), verifier, nonce, clock.instant().plusSeconds(300)))
        return uri
    }

    fun callback(
        provider: OAuthProvider,
        code: String,
        state: String,
        browserBinding: String,
    ): RefreshRotation {
        if (state.isBlank() || state.length > 256) throw AuthFailure("AUTH_OAUTH_INVALID")
        val attempt = statePort.consume(state) ?: throw AuthFailure("AUTH_OAUTH_INVALID")
        if (attempt.provider != provider ||
            !attempt.expiresAt.isAfter(clock.instant()) ||
            browserBinding.isBlank() ||
            !AuthEntropy.same(attempt.browserBindingDigest, AuthEntropy.digest(browserBinding)) ||
            code.isBlank() ||
            code.length > 4096
        ) {
            throw AuthFailure("AUTH_OAUTH_INVALID")
        }
        val identity = providerPort.exchange(provider, code, attempt.codeVerifier, attempt.nonce)
        val principal = accounts.findOrCreateIdentity(provider, identity.subject, identity.displayName)
        return refreshSessions.create(principal)
    }
}
