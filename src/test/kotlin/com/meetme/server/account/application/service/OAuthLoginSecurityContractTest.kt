package com.meetme.server.account.application.service

import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthAttempt
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.ProviderIdentity
import com.meetme.server.account.domain.RefreshRotation
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// User #109 decisions: two providers, browser-bound one-use authorization code flow, no email-based merge.
class OAuthLoginSecurityContractTest {
    @Test
    fun `both providers bind unique unpredictable state PKCE S256 nonce and initiating browser`() {
        for (provider in OAuthProvider.entries) {
            val fixture = Fixture()
            fixture.service.begin(provider, BROWSER)
            val first = requireNotNull(fixture.provider.authorization)
            val attempt = requireNotNull(fixture.state.entries[first.state])
            assertEquals(provider, attempt.provider)
            assertTrue(first.state.length >= 43)
            assertTrue(attempt.codeVerifier.length in 43..128)
            assertTrue(first.nonce.length >= 32)
            assertEquals(first.nonce, attempt.nonce)
            assertEquals(base64(MessageDigest.getInstance("SHA-256").digest(attempt.codeVerifier.toByteArray(UTF_8))), first.codeChallenge)
            assertNotEquals(BROWSER, attempt.browserBindingDigest)
            assertTrue(attempt.expiresAt > NOW)
            assertTrue(attempt.expiresAt <= NOW.plus(Duration.ofMinutes(10)))
            fixture.service.begin(provider, BROWSER)
            val second = requireNotNull(fixture.provider.authorization)
            assertNotEquals(first.state, second.state)
            assertNotEquals(first.nonce, second.nonce)
            assertNotEquals(attempt.codeVerifier, fixture.state.entries[second.state]?.codeVerifier)
        }
    }

    @Test
    fun `valid callback exchanges original verifier and nonce then persists provider subject once`() {
        for (provider in OAuthProvider.entries) {
            val fixture = Fixture()
            fixture.service.begin(provider, BROWSER)
            val authorization = requireNotNull(fixture.provider.authorization)
            val attempt = requireNotNull(fixture.state.entries[authorization.state])
            val result = fixture.service.callback(provider, "synthetic-auth-code", authorization.state, BROWSER)
            assertEquals(listOf(Triple(provider, "provider-specific-subject", "Synthetic name")), fixture.accounts.calls)
            assertEquals(listOf(Exchange(provider, "synthetic-auth-code", attempt.codeVerifier, attempt.nonce)), fixture.provider.exchanges)
            assertEquals(fixture.accounts.principal, result.principal)
            assertEquals(1, fixture.refresh.creations)
            assertFalse(fixture.state.entries.containsKey(authorization.state))
            assertFailsWith<AuthFailure> { fixture.service.callback(provider, "synthetic-auth-code", authorization.state, BROWSER) }
            assertEquals(1, fixture.accounts.calls.size)
            assertEquals(1, fixture.refresh.creations)
        }
    }

    @Test
    fun `missing wrong expired provider mismatched or foreign browser state creates no account or session`() {
        for (invalid in listOf("missing", "unknown", "expired", "provider", "browser")) {
            val fixture = Fixture()
            fixture.service.begin(OAuthProvider.GOOGLE, BROWSER)
            val state = requireNotNull(fixture.provider.authorization).state
            if (invalid == "expired") fixture.state.entries.computeIfPresent(state) { _, value -> value.copy(expiresAt = NOW) }
            val received =
                when (invalid) {
                    "missing" -> ""
                    "unknown" -> "unknown-but-syntactically-plausible-state"
                    else -> state
                }
            val provider = if (invalid == "provider") OAuthProvider.KAKAO else OAuthProvider.GOOGLE
            val browser = if (invalid == "browser") "different-browser-binding" else BROWSER
            assertFailsWith<AuthFailure> { fixture.service.callback(provider, "synthetic-code", received, browser) }
            assertEquals(0, fixture.provider.exchanges.size)
            assertEquals(0, fixture.accounts.calls.size)
            assertEquals(0, fixture.refresh.creations)
        }
    }

    @Test
    fun `provider token validation failure never creates account or service session and state cannot replay`() {
        val fixture = Fixture()
        fixture.service.begin(OAuthProvider.KAKAO, BROWSER)
        val state = requireNotNull(fixture.provider.authorization).state
        fixture.provider.reject = true
        assertFailsWith<AuthFailure> { fixture.service.callback(OAuthProvider.KAKAO, "invalid-code", state, BROWSER) }
        fixture.provider.reject = false
        assertFailsWith<AuthFailure> { fixture.service.callback(OAuthProvider.KAKAO, "replacement-code", state, BROWSER) }
        assertEquals(0, fixture.accounts.calls.size)
        assertEquals(0, fixture.refresh.creations)
    }

    @Test
    fun `callback race consumes state once before account and refresh issuance`() {
        val fixture = Fixture()
        fixture.service.begin(OAuthProvider.GOOGLE, BROWSER)
        val state = requireNotNull(fixture.provider.authorization).state
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val futures =
                (1..2).map {
                    pool.submit(
                        Callable {
                            gate.await()
                            runCatching { fixture.service.callback(OAuthProvider.GOOGLE, "synthetic-code", state, BROWSER) }
                        },
                    )
                }
            gate.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is AuthFailure })
            assertEquals(1, fixture.accounts.calls.size)
            assertEquals(1, fixture.refresh.creations)
        } finally {
            pool.shutdownNow()
        }
    }

    private class Fixture {
        val state = MemoryState()
        val provider = FakeProvider()
        val accounts = FakeAccounts()
        val refresh = FakeRefresh()
        val service = OAuthLoginService(state, provider, accounts, refresh, Clock.fixed(NOW, ZoneOffset.UTC))
    }

    private class MemoryState : OAuthStatePort {
        val entries = ConcurrentHashMap<String, OAuthAttempt>()

        override fun save(
            state: String,
            attempt: OAuthAttempt,
        ) {
            entries[state] = attempt
        }

        override fun consume(state: String): OAuthAttempt? = entries.remove(state)
    }

    private data class Authorization(
        val provider: OAuthProvider,
        val state: String,
        val codeChallenge: String,
        val nonce: String,
    )

    private data class Exchange(
        val provider: OAuthProvider,
        val code: String,
        val codeVerifier: String,
        val nonce: String,
    )

    private class FakeProvider : OAuthProviderPort {
        var authorization: Authorization? = null
        val exchanges = mutableListOf<Exchange>()
        var reject = false

        override fun authorizationUri(
            provider: OAuthProvider,
            state: String,
            codeChallenge: String,
            nonce: String,
        ): String {
            authorization = Authorization(provider, state, codeChallenge, nonce)
            return "https://isolated-provider.synthetic/authorize"
        }

        override fun exchange(
            provider: OAuthProvider,
            code: String,
            codeVerifier: String,
            expectedNonce: String,
        ): ProviderIdentity {
            if (reject) throw AuthFailure("AUTH_OAUTH_INVALID")
            exchanges.add(Exchange(provider, code, codeVerifier, expectedNonce))
            return ProviderIdentity("provider-specific-subject", "Synthetic name")
        }
    }

    private class FakeAccounts : AccountRepository {
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic name")
        val calls = mutableListOf<Triple<OAuthProvider, String, String>>()

        override fun findOrCreateIdentity(
            provider: OAuthProvider,
            subject: String,
            displayName: String,
        ): AccountPrincipal {
            calls.add(Triple(provider, subject, displayName))
            return principal
        }
    }

    private class FakeRefresh : RefreshSessionPort {
        var creations = 0

        override fun create(principal: AccountPrincipal): RefreshRotation {
            creations++
            return RefreshRotation(principal, "synthetic-refresh-only", NOW.plus(Duration.ofDays(14)), NOW.plus(Duration.ofDays(30)))
        }

        override fun rotate(token: String): RefreshRotation = error("not part of OAuth callback")

        override fun revoke(token: String) = error("not part of OAuth callback")

        override fun revokeAll(accountId: UUID) = error("not part of OAuth callback")
    }

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private const val BROWSER = "synthetic-browser-binding-109"

        private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
