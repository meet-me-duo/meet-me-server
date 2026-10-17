package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.application.service.OAuthLoginService
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.RefreshRotation
import com.meetme.server.config.AccountAuthProperties
import com.meetme.server.config.AnonymousAccessProperties
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ADR-030/031 HTTP adapter contract; refresh credential must never become a response body field.
class AuthHttpSecurityContractTest {
    @Test
    fun `refresh rotates only exact Origin and emits host-only secure narrow-path cookie`() {
        val fixture = Fixture()
        val response = fixture.controller.refresh(request("/api/auth/refresh", ORIGIN))
        assertEquals(200, response.statusCode.value())
        val body = assertNotNull(response.body)
        assertEquals("synthetic-access", body.accessToken)
        assertEquals(NOW.plusSeconds(900), body.expiresAt)
        assertEquals(fixture.principal.accountId.toString(), body.account.id)
        val cookie = assertNotNull(response.headers.getFirst("Set-Cookie"))
        assertTrue(cookie.startsWith("meetme_refresh=synthetic-rotated"))
        assertTrue(cookie.contains("Path=/api/auth"))
        assertTrue(cookie.contains("Secure"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertTrue(cookie.contains("Max-Age=1209600"))
        assertFalse(cookie.contains("Domain=", ignoreCase = true))
        assertFalse(body.toString().contains("synthetic-rotated"))
        assertEquals(listOf("synthetic-refresh"), fixture.sessions.rotations)
    }

    @Test
    fun `refresh cookie max age is capped by remaining absolute family lifetime`() {
        val fixture = Fixture()
        fixture.sessions.remaining = Duration.ofHours(3)
        val response = fixture.controller.refresh(request("/api/auth/refresh", ORIGIN))
        assertTrue(assertNotNull(response.headers.getFirst("Set-Cookie")).contains("Max-Age=10800"))
    }

    @Test
    fun `missing null lookalike or nonexact Origin never reaches refresh or logout ports`() {
        for (origin in listOf(
            null,
            "null",
            "https://app.synthetic.example.evil",
            "$ORIGIN/",
            "http://app.synthetic.example",
            "$ORIGIN:443",
        )) {
            val fixture = Fixture()
            assertFailsWith<AuthFailure> { fixture.controller.refresh(request("/api/auth/refresh", origin)) }
            assertFailsWith<AuthFailure> { fixture.controller.logout(request("/api/auth/logout", origin)) }
            assertFailsWith<AuthFailure> { fixture.controller.logoutAll(request("/api/auth/logout-all", origin)) }
            assertTrue(fixture.sessions.rotations.isEmpty())
            assertTrue(fixture.sessions.revocations.isEmpty())
            assertTrue(fixture.sessions.allRevocations.isEmpty())
        }
    }

    @Test
    fun `current logout revokes only cookie family and expires cookie with matching scope`() {
        val fixture = Fixture()
        val response = fixture.controller.logout(request("/api/auth/logout", ORIGIN))
        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(listOf("synthetic-refresh"), fixture.sessions.revocations)
        assertTrue(fixture.sessions.allRevocations.isEmpty())
        val cookie = assertNotNull(response.headers.getFirst("Set-Cookie"))
        assertTrue(cookie.contains("Max-Age=0"))
        assertTrue(cookie.contains("Path=/api/auth"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("Secure"))
        assertFalse(cookie.contains("Domain=", ignoreCase = true))
    }

    @Test
    fun `all logout uses authenticated account and requires authenticated account independently of cookie`() {
        val fixture = Fixture()
        val response = fixture.controller.logoutAll(request("/api/auth/logout-all", ORIGIN))
        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(listOf(fixture.principal.accountId), fixture.sessions.allRevocations)
        assertTrue(assertNotNull(response.headers.getFirst("Set-Cookie")).contains("Max-Age=0"))
        val guest = Fixture(authenticated = false)
        assertFailsWith<AuthFailure> { guest.controller.logoutAll(request("/api/auth/logout-all", ORIGIN)) }
        assertTrue(guest.sessions.allRevocations.isEmpty())
    }

    @Test
    fun `missing refresh credential cannot issue Access`() {
        val fixture = Fixture()
        val request = MockHttpServletRequest("POST", "/api/auth/refresh").apply { addHeader("Origin", ORIGIN) }
        assertFailsWith<AuthFailure> { fixture.controller.refresh(request) }
        assertTrue(fixture.sessions.rotations.isEmpty())
    }

    private class Fixture(
        authenticated: Boolean = true,
    ) {
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic")
        val sessions = FakeSessions(principal)
        private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        private val access =
            object : AccessTokenPort {
                override fun issue(principal: AccountPrincipal) = "synthetic-access"

                override fun verify(token: String): AccountPrincipal = error("not called by refresh controller")
            }
        private val login =
            OAuthLoginService(
                mock(OAuthStatePort::class.java),
                mock(OAuthProviderPort::class.java),
                mock(AccountRepository::class.java),
                sessions,
                clock,
            )
        val controller =
            AuthController(
                login,
                sessions,
                access,
                CurrentAccountPort { if (authenticated) principal else null },
                AccountAuthProperties(),
                AnonymousAccessProperties(allowedOrigins = listOf(ORIGIN)),
                clock,
            )
    }

    private class FakeSessions(
        private val principal: AccountPrincipal,
    ) : RefreshSessionPort {
        val rotations = mutableListOf<String>()
        val revocations = mutableListOf<String>()
        val allRevocations = mutableListOf<UUID>()
        var remaining = Duration.ofDays(14)

        override fun create(principal: AccountPrincipal): RefreshRotation = error("not used in refresh test")

        override fun rotate(token: String): RefreshRotation {
            rotations.add(token)
            return RefreshRotation(principal, "synthetic-rotated", NOW.plus(remaining), NOW.plus(Duration.ofDays(30)))
        }

        override fun revoke(token: String) {
            revocations.add(token)
        }

        override fun revokeAll(accountId: UUID) {
            allRevocations.add(accountId)
        }
    }

    private fun request(
        path: String,
        origin: String?,
    ): MockHttpServletRequest =
        MockHttpServletRequest("POST", path).apply {
            if (origin != null) addHeader("Origin", origin)
            setCookies(Cookie("meetme_refresh", "synthetic-refresh"))
        }

    companion object {
        private const val ORIGIN = "https://app.synthetic.example"
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
    }
}
