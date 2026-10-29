package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
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
import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.service.operations.OperatorAccessPolicy
import io.swagger.v3.core.converter.ModelConverters
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Approved menu capability contract; all identities, signing keys and refresh credentials are synthetic. */
class AuthOperatorCapabilityContractTest {
    @Test
    fun `OC01 enabled policy grants each approved identity and denies outsider and anonymous`() {
        val policy = OperatorAccessPolicy(properties())
        assertTrue(policy.canViewOperations(FIRST))
        assertTrue(policy.canViewOperations(SECOND))
        assertFalse(policy.canViewOperations(OUTSIDER))
        assertFalse(policy.canViewOperations(null))
    }

    @Test
    fun `OC02 disabled and empty policy never grant even an authenticated identity`() {
        listOf(OperatorQueryProperties(), properties(enabled = false), properties(ids = emptySet())).forEach { configuration ->
            val policy = OperatorAccessPolicy(configuration)
            listOf(FIRST, SECOND, OUTSIDER, null).forEach { assertFalse(policy.canViewOperations(it)) }
        }
    }

    @Test
    fun `OC03 me exposes true for either approved account and only that accounts safe fields`() {
        listOf(FIRST, SECOND).forEach { id ->
            val response = Fixture(id).me()
            assertAccount(response, id, true)
        }
    }

    @Test
    fun `OC04 me exposes explicit false for outsiders disabled and empty allowlist`() {
        assertAccount(Fixture(OUTSIDER).me(), OUTSIDER, false)
        assertAccount(Fixture(FIRST, configuration = properties(enabled = false)).me(), FIRST, false)
        assertAccount(Fixture(FIRST, configuration = properties(ids = emptySet())).me(), FIRST, false)
    }

    @Test
    fun `OC05 legacy controller construction defaults to false without changing account identity`() {
        assertAccount(Fixture(FIRST, useDefaultPolicy = true).me(), FIRST, false)
        val response = Fixture(FIRST, useDefaultPolicy = true).refresh()
        assertEquals(200, response.status)
        assertCapability(child(body(response), "account"), FIRST, false)
    }

    @Test
    fun `OC06 refresh computes capability for rotated principal independently of current bearer identity`() {
        listOf(FIRST, SECOND).forEach { id ->
            val response = Fixture(currentId = OUTSIDER, refreshedId = id).refresh()
            assertEquals(200, response.status)
            assertNoStore(response)
            val result = body(response)
            assertEquals(setOf("accessToken", "expiresAt", "account"), result.keys)
            assertCapability(child(result, "account"), id, true)
            assertEquals(NOW.plusSeconds(900).toString(), result["expiresAt"])
            assertTrue(assertNotNull(response.getHeader("Set-Cookie")).contains("HttpOnly"))
            assertFalse(response.contentAsString.contains("synthetic-private-refresh"))
            val token = result["accessToken"] as String
            val claims = mapper.readValue(Base64.getUrlDecoder().decode(token.split('.')[1]), Map::class.java)
            assertEquals(setOf("iss", "aud", "sub", "name", "iat", "exp", "jti"), claims.keys)
            assertEquals(id.toString(), claims["sub"])
        }
    }

    @Test
    fun `OC07 refresh never inherits an operators capability for an unlisted rotated account`() {
        val response = Fixture(currentId = FIRST, refreshedId = OUTSIDER).refresh()
        assertEquals(200, response.status)
        assertNoStore(response)
        assertCapability(child(body(response), "account"), OUTSIDER, false)
    }

    @Test
    fun `OC08 refresh with disabled or empty policy remains authenticated but not authorized`() {
        listOf(properties(enabled = false), properties(ids = emptySet())).forEach { configuration ->
            val response = Fixture(FIRST, configuration = configuration).refresh()
            assertEquals(200, response.status)
            assertCapability(child(body(response), "account"), FIRST, false)
        }
    }

    @Test
    fun `OC09 anonymous or invalid bearer cannot acquire a capability response`() {
        listOf(Fixture(null), Fixture(null, currentFailure = AuthFailure("AUTH_ACCESS_INVALID"))).forEach { fixture ->
            val response = fixture.me()
            assertEquals(401, response.status)
            assertNoStore(response)
            assertFalse(body(response).containsKey("capabilities"))
            assertFalse(response.contentAsString.contains(FIRST.toString()))
            assertFalse(response.contentAsString.contains(SECOND.toString()))
        }
    }

    @Test
    fun `OC10 missing refresh credential or wrong Origin cannot return operator capability`() {
        val fixture = Fixture(FIRST)
        listOf(
            fixture.mvc
                .perform(post("/api/auth/refresh").header("Origin", ORIGIN))
                .andReturn()
                .response to 401,
            fixture.mvc
                .perform(
                    post(
                        "/api/auth/refresh",
                    ).header("Origin", "https://other.synthetic.example").cookie(Cookie("meetme_refresh", "synthetic-refresh")),
                ).andReturn()
                .response to
                403,
        ).forEach { (response, status) ->
            assertEquals(status, response.status)
            assertNoStore(response)
            assertFalse(body(response).containsKey("account"))
            assertFalse(response.contentAsString.contains("canViewOperations"))
        }
    }

    @Test
    fun `OC11 generated account schema describes a minimal boolean capability without operator identities`() {
        val schemas = ModelConverters.getInstance().readAll(AccountResponse::class.java)
        val account = assertNotNull(schemas["AccountResponse"])
        assertEquals(setOf("id", "displayName", "capabilities"), account.properties.keys)
        val capabilityReference = assertNotNull(account.properties["capabilities"])
        val capability = assertNotNull(schemas[assertNotNull(capabilityReference.`$ref`).substringAfterLast('/')])
        assertFalse(capability.description.isNullOrBlank())
        assertEquals(setOf("canViewOperations"), capability.properties.keys)
        val flag = assertNotNull(capability.properties["canViewOperations"])
        assertEquals("boolean", flag.type)
        assertFalse(flag.description.isNullOrBlank())
    }

    private class Fixture(
        currentId: UUID?,
        refreshedId: UUID = currentId ?: FIRST,
        configuration: OperatorQueryProperties = properties(),
        useDefaultPolicy: Boolean = false,
        currentFailure: AuthFailure? = null,
    ) {
        private val refreshed = AccountPrincipal(refreshedId, "Synthetic operator")
        private val sessions =
            object : RefreshSessionPort {
                override fun create(principal: AccountPrincipal): RefreshRotation = error("not used")

                override fun rotate(token: String): RefreshRotation {
                    if (token != "synthetic-refresh") throw AuthFailure("AUTH_REFRESH_INVALID")
                    return RefreshRotation(
                        refreshed,
                        "synthetic-private-refresh",
                        NOW.plus(Duration.ofDays(14)),
                        NOW.plus(Duration.ofDays(30)),
                    )
                }

                override fun revoke(token: String) = error("not used")

                override fun revokeAll(accountId: UUID) = error("not used")
            }
        private val login =
            OAuthLoginService(
                mock(OAuthStatePort::class.java),
                mock(OAuthProviderPort::class.java),
                mock(AccountRepository::class.java),
                sessions,
                CLOCK,
            )
        private val current =
            CurrentAccountPort {
                if (currentFailure != null) throw currentFailure
                currentId?.let { AccountPrincipal(it, "Synthetic operator") }
            }
        private val access =
            Rs256AccessTokenAdapter(
                KEY.private,
                mapOf("synthetic" to KEY.public),
                "synthetic",
                CLOCK,
                "https://api.synthetic.example",
                "meetme-web",
            )
        private val controller =
            if (useDefaultPolicy) {
                AuthController(
                    login,
                    sessions,
                    access,
                    current,
                    AccountAuthProperties(),
                    AnonymousAccessProperties(allowedOrigins = listOf(ORIGIN)),
                    CLOCK,
                )
            } else {
                AuthController(
                    login,
                    sessions,
                    access,
                    current,
                    AccountAuthProperties(),
                    AnonymousAccessProperties(allowedOrigins = listOf(ORIGIN)),
                    CLOCK,
                    OperatorAccessPolicy(configuration),
                )
            }
        val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(AuthExceptionHandler(StaticMessageSource())).build()

        fun me() = mvc.perform(get("/api/auth/me")).andReturn().response

        fun refresh() =
            mvc
                .perform(
                    post("/api/auth/refresh").header("Origin", ORIGIN).cookie(Cookie("meetme_refresh", "synthetic-refresh")),
                ).andReturn()
                .response
    }

    private fun assertAccount(
        response: MockHttpServletResponse,
        id: UUID,
        allowed: Boolean,
    ) {
        assertEquals(200, response.status)
        assertNoStore(response)
        assertCapability(body(response), id, allowed)
    }

    private fun assertCapability(
        account: Map<*, *>,
        id: UUID,
        allowed: Boolean,
    ) {
        assertEquals(setOf("id", "displayName", "capabilities"), account.keys)
        assertEquals(id.toString(), account["id"])
        assertEquals("Synthetic operator", account["displayName"])
        assertEquals(mapOf("canViewOperations" to allowed), child(account, "capabilities"))
    }

    private fun assertNoStore(response: MockHttpServletResponse) {
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    private fun body(response: MockHttpServletResponse): Map<*, *> = mapper.readValue(response.contentAsByteArray, Map::class.java)

    private fun child(
        parent: Map<*, *>,
        key: String,
    ): Map<*, *> = assertNotNull(parent[key] as? Map<*, *>)

    companion object {
        private val FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555")
        private val SECOND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        private val OUTSIDER = UUID.fromString("99999999-8888-7777-6666-555555555555")
        private const val ORIGIN = "https://app.synthetic.example"
        private val NOW = Instant.parse("2026-10-29T12:00:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
        private val KEY = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val mapper = ObjectMapper()

        private fun properties(
            enabled: Boolean = true,
            ids: Set<UUID> = setOf(FIRST, SECOND),
        ) = OperatorQueryProperties(enabled = enabled, operatorUserIds = ids)
    }
}
