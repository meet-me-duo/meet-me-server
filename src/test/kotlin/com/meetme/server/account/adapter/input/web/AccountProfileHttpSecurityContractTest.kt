package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.AccountProfileRepository
import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.application.service.AccountProfileService
import com.meetme.server.account.application.service.OAuthLoginService
import com.meetme.server.account.domain.AccountDisplayName
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.RefreshRotation
import com.meetme.server.config.AccountAuthProperties
import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.adapter.input.web.ApiExceptionHandler
import com.meetme.server.shared.application.service.operations.OperatorAccessPolicy
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue228, FR-109E: name editing has no token authority and cannot select a different account. */
class AccountProfileHttpSecurityContractTest {
    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `PH01 valid rename returns only latest safe account fields and cannot extend a session`() {
        val fixture = Fixture()
        val response = fixture.rename("  새 이름 😀  ")
        assertAccount(response, FIRST, "새 이름 😀", allowed = true)
        assertEquals(listOf(FIRST to "새 이름 😀"), fixture.repository.writes)
        assertEquals(AccountPrincipal(SECOND, "두 번째 이름"), fixture.repository.rows[SECOND])
        assertEquals(null, response.getHeader("Set-Cookie"))
        assertTrue(fixture.issued.isEmpty())
        assertTrue(fixture.sessions.rotations.isEmpty())
        assertTrue(fixture.sessions.creations.isEmpty())
        assertTrue(fixture.sessions.revocations.isEmpty())
        assertFalse(response.contentAsString.contains("accessToken"))
        assertFalse(response.contentAsString.contains("refreshToken"))
    }

    @Test
    fun `PH02 get ignores stale signed-token name and observes later persisted names`() {
        val fixture = Fixture()
        fixture.repository.rows[FIRST] = AccountPrincipal(FIRST, "DB 최신 이름")
        assertAccount(fixture.me(), FIRST, "DB 최신 이름", allowed = true)
        fixture.repository.rows[FIRST] = AccountPrincipal(FIRST, "다시 변경된 이름")
        assertAccount(fixture.me(), FIRST, "다시 변경된 이름", allowed = true)
        assertTrue(fixture.repository.writes.isEmpty())
        assertTrue(fixture.issued.isEmpty())
    }

    @Test
    fun `PH03 missing cookie-only malformed duplicate expired and forged bearer cannot rename`() {
        val fixture = Fixture()
        val expired = tokens(Clock.fixed(NOW.minusSeconds(3600), ZoneOffset.UTC)).issue(AccountPrincipal(FIRST, "이전 이름"))
        val forged = fixture.bearer.dropLast(8) + "AAAAAAAA"
        val headers =
            listOf(
                emptyList(),
                listOf(""),
                listOf("Basic ignored"),
                listOf("Bearer invalid"),
                listOf("Bearer $expired"),
                listOf("Bearer $forged"),
                listOf("Bearer ${fixture.bearer}", "Bearer ${fixture.bearer}"),
            )
        headers.forEach { supplied ->
            val request =
                put("/api/auth/me")
                    .header("Origin", ORIGIN)
                    .contentType("application/json")
                    .content("""{"displayName":"Unauthorized"}""")
                    .cookie(Cookie("meetme_refresh", "synthetic-refresh"), Cookie("meetme_guest", "synthetic-guest"))
            supplied.forEach { request.header("Authorization", it) }
            assertEquals(401, fixture.perform(request).status, "supplied bearer count=${supplied.size}")
        }
        assertTrue(fixture.repository.writes.isEmpty())
        assertTrue(fixture.repository.reads.isEmpty())
        assertTrue(fixture.issued.isEmpty())
        assertTrue(fixture.sessions.rotations.isEmpty())
    }

    @Test
    fun `PH04 exact single Origin is required before profile mutation`() {
        val fixture = Fixture()
        val origins =
            listOf(
                emptyList(),
                listOf("null"),
                listOf("$ORIGIN/"),
                listOf("$ORIGIN:443"),
                listOf("https://app.synthetic.example.evil"),
                listOf("http://app.synthetic.example"),
                listOf(ORIGIN, ORIGIN),
            )
        origins.forEach { supplied ->
            val request =
                put("/api/auth/me")
                    .header("Authorization", "Bearer ${fixture.bearer}")
                    .contentType("application/json")
                    .content("""{"displayName":"Rejected Origin"}""")
            supplied.forEach { request.header("Origin", it) }
            val response = fixture.perform(request)
            assertProblem(response, 403, "AUTH_ORIGIN_NOT_ALLOWED")
        }
        assertTrue(fixture.repository.writes.isEmpty())
        assertTrue(fixture.issued.isEmpty())
    }

    @Test
    fun `PH05 malformed absent null non-string blank overlong and control inputs fail without persisting or echoing`() {
        val fixture = Fixture()
        val json =
            listOf(
                "",
                "{",
                "{}",
                "null",
                "[]",
                """{"displayName":null}""",
                """{"displayName":123}""",
                """{"displayName":true}""",
                """{"displayName":[]}""",
                """{"displayName":{}}""",
                """{"displayName":""}""",
                """{"displayName":"    "}""",
                mapper.writeValueAsString(mapOf("displayName" to "😀".repeat(51))),
                mapper.writeValueAsString(mapOf("displayName" to "Sensitive\nName")),
                mapper.writeValueAsString(mapOf("displayName" to "Sensitive\u0000Name")),
            )
        json.forEach { body ->
            val response =
                fixture.perform(
                    put("/api/auth/me")
                        .header("Authorization", "Bearer ${fixture.bearer}")
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content(body),
                )
            assertProblem(response, 400, "VALIDATION_FAILED")
            assertFalse(response.contentAsString.contains("Sensitive"))
        }
        assertTrue(fixture.repository.writes.isEmpty())
        assertEquals(AccountPrincipal(FIRST, "처음 저장된 이름"), fixture.repository.rows[FIRST])
    }

    @Test
    fun `PH06 Unicode boundary and same-name retries preserve account and do not require unique names`() {
        val fixture = Fixture()
        listOf("A", "😀".repeat(50), "두 번째 이름", "두 번째 이름").forEach {
            assertAccount(fixture.rename(it), FIRST, it, allowed = true)
        }
        assertEquals(setOf(FIRST, SECOND), fixture.repository.rows.keys)
        assertEquals(AccountPrincipal(SECOND, "두 번째 이름"), fixture.repository.rows[SECOND])
        assertTrue(fixture.issued.isEmpty())
    }

    @Test
    fun `PH07 caller identity controls target even when a different account is supplied in query`() {
        val fixture = Fixture()
        val response =
            fixture.perform(
                put("/api/auth/me")
                    .queryParam("accountId", SECOND.toString())
                    .queryParam("user_id", SECOND.toString())
                    .header("Authorization", "Bearer ${fixture.bearer}")
                    .header("Origin", ORIGIN)
                    .contentType("application/json")
                    .content("""{"displayName":"Caller only"}"""),
            )
        assertAccount(response, FIRST, "Caller only", allowed = true)
        assertEquals(listOf(FIRST to "Caller only"), fixture.repository.writes)
        assertEquals(AccountPrincipal(SECOND, "두 번째 이름"), fixture.repository.rows[SECOND])
    }

    @Test
    fun `PH08 a missing persisted account cannot be read renamed or re-created from its old token`() {
        val fixture = Fixture().apply { repository.rows.remove(FIRST) }
        assertProblem(fixture.me(), 401, "AUTH_ACCESS_INVALID")
        assertProblem(fixture.rename("New"), 401, "AUTH_ACCESS_INVALID")
        assertEquals(setOf(SECOND), fixture.repository.rows.keys)
        assertTrue(fixture.issued.isEmpty())
    }

    @Test
    fun `PH09 profile storage failures are 503 and do not expose a stale bearer name or pretend rename success`() {
        val fixture = Fixture().apply { repository.failure = AuthFailure("AUTH_UNAVAILABLE") }
        assertProblem(fixture.me(), 503, "AUTH_UNAVAILABLE")
        assertProblem(fixture.rename("Private replacement"), 503, "AUTH_UNAVAILABLE")
        assertEquals(AccountPrincipal(FIRST, "처음 저장된 이름"), fixture.repository.rows[FIRST])
        assertTrue(fixture.issued.isEmpty())
    }

    @Test
    fun `PH10 normal refresh resolves latest stored profile for rotated session identity rather than bearer identity`() {
        val fixture = Fixture()
        fixture.sessions.principal = AccountPrincipal(SECOND, "Redis에 남은 예전 이름")
        fixture.repository.rows[SECOND] = AccountPrincipal(SECOND, "최신 계정 이름")
        val response = fixture.refresh()
        assertEquals(200, response.status)
        val result = body(response)
        assertEquals(setOf("accessToken", "expiresAt", "account"), result.keys)
        val account = assertNotNull(result["account"] as? Map<*, *>)
        assertEquals(SECOND.toString(), account["id"])
        assertEquals("최신 계정 이름", account["displayName"])
        assertEquals(mapOf("canViewOperations" to false), account["capabilities"])
        assertEquals(listOf(AccountPrincipal(SECOND, "최신 계정 이름")), fixture.issued)
        val verified = tokens().verify(result["accessToken"] as String)
        assertEquals(AccountPrincipal(SECOND, "최신 계정 이름"), verified)
        assertEquals(listOf("synthetic-refresh"), fixture.sessions.rotations)
        val cookie = assertNotNull(response.getHeader("Set-Cookie"))
        assertTrue(cookie.contains("Path=/api/auth"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("Secure"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertTrue(cookie.contains("Max-Age=1209600"))
        assertFalse(response.contentAsString.contains("synthetic-rotated"))
        assertEquals(NOW.plusSeconds(900).toString(), result["expiresAt"])
    }

    @Test
    fun `PH11 refresh never issues token from cached profile if persisted account is missing or unavailable`() {
        listOf(false, true).forEach { unavailable ->
            val fixture = Fixture()
            if (unavailable) fixture.repository.failure = AuthFailure("AUTH_UNAVAILABLE") else fixture.repository.rows.remove(FIRST)
            assertProblem(fixture.refresh(), if (unavailable) 503 else 401, if (unavailable) "AUTH_UNAVAILABLE" else "AUTH_ACCESS_INVALID")
            assertTrue(fixture.issued.isEmpty())
        }
    }

    @Test
    fun `PH12 choosing an operator-like display name cannot grant operator capability`() {
        val fixture = Fixture(SECOND)
        assertAccount(fixture.rename("관리자"), SECOND, "관리자", allowed = false)
        assertEquals(listOf(SECOND to "관리자"), fixture.repository.writes)
        assertEquals(AccountPrincipal(FIRST, "처음 저장된 이름"), fixture.repository.rows[FIRST])
    }

    private class Fixture(
        caller: UUID = FIRST,
    ) {
        val repository = Profiles()
        val sessions = Sessions()
        val issued = mutableListOf<AccountPrincipal>()
        val bearer = tokens().issue(AccountPrincipal(caller, "JWT에 남은 예전 이름"))
        private val access =
            object : AccessTokenPort {
                override fun issue(principal: AccountPrincipal): String {
                    issued += principal
                    return tokens().issue(principal)
                }

                override fun verify(token: String): AccountPrincipal = tokens().verify(token)
            }
        private val current = RequestCurrentAccountAdapter(access)
        private val controller =
            AuthController(
                OAuthLoginService(
                    mock(OAuthStatePort::class.java),
                    mock(OAuthProviderPort::class.java),
                    mock(AccountRepository::class.java),
                    sessions,
                    CLOCK,
                ),
                sessions,
                access,
                current,
                AccountAuthProperties(),
                AnonymousAccessProperties(allowedOrigins = listOf(ORIGIN)),
                CLOCK,
                AccountProfileService(repository),
                OperatorAccessPolicy(OperatorQueryProperties(enabled = true, operatorUserIds = setOf(FIRST))),
            )
        private val mvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(AuthExceptionHandler(StaticMessageSource()), ApiExceptionHandler(StaticMessageSource()))
                .addInterceptors(
                    object : HandlerInterceptor {
                        override fun preHandle(
                            request: HttpServletRequest,
                            response: HttpServletResponse,
                            handler: Any,
                        ): Boolean {
                            current.currentAccount()
                            return true
                        }
                    },
                ).build()

        fun perform(request: MockHttpServletRequestBuilder): MockHttpServletResponse = mvc.perform(request).andReturn().response

        fun rename(value: String): MockHttpServletResponse =
            perform(
                put("/api/auth/me")
                    .header("Authorization", "Bearer $bearer")
                    .header("Origin", ORIGIN)
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(mapOf("displayName" to value))),
            )

        fun me(): MockHttpServletResponse = perform(get("/api/auth/me").header("Authorization", "Bearer $bearer"))

        fun refresh(): MockHttpServletResponse =
            perform(
                post("/api/auth/refresh")
                    .header("Authorization", "Bearer $bearer")
                    .header("Origin", ORIGIN)
                    .cookie(Cookie("meetme_refresh", "synthetic-refresh")),
            )
    }

    private class Profiles : AccountProfileRepository {
        val rows = mutableMapOf(FIRST to AccountPrincipal(FIRST, "처음 저장된 이름"), SECOND to AccountPrincipal(SECOND, "두 번째 이름"))
        val reads = mutableListOf<UUID>()
        val writes = mutableListOf<Pair<UUID, String>>()
        var failure: AuthFailure? = null

        override fun findById(accountId: UUID): AccountPrincipal? {
            failure?.let { throw it }
            reads += accountId
            return rows[accountId]
        }

        override fun updateDisplayName(
            accountId: UUID,
            displayName: AccountDisplayName,
        ): AccountPrincipal? {
            failure?.let { throw it }
            writes += accountId to displayName.value
            if (accountId !in rows) return null
            return AccountPrincipal(accountId, displayName.value).also { rows[accountId] = it }
        }
    }

    private class Sessions : RefreshSessionPort {
        var principal = AccountPrincipal(FIRST, "Redis에 남은 예전 이름")
        val rotations = mutableListOf<String>()
        val creations = mutableListOf<AccountPrincipal>()
        val revocations = mutableListOf<UUID>()

        override fun create(principal: AccountPrincipal): RefreshRotation {
            creations += principal
            return rotation(principal)
        }

        override fun rotate(token: String): RefreshRotation {
            rotations += token
            return rotation(principal)
        }

        override fun revoke(token: String): Unit = throw AssertionError("Rename must not revoke refresh")

        override fun revokeAll(accountId: UUID) {
            revocations += accountId
        }

        private fun rotation(principal: AccountPrincipal) =
            RefreshRotation(principal, "synthetic-rotated", NOW.plus(Duration.ofDays(14)), NOW.plus(Duration.ofDays(30)))
    }

    private fun assertAccount(
        response: MockHttpServletResponse,
        id: UUID,
        name: String,
        allowed: Boolean,
    ) {
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
        assertEquals(
            mapOf("id" to id.toString(), "displayName" to name, "capabilities" to mapOf("canViewOperations" to allowed)),
            body(response),
        )
    }

    private fun assertProblem(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.status)
        assertEquals(code, body(response)["code"])
        assertFalse(body(response).containsKey("account"))
        assertFalse(body(response).containsKey("accessToken"))
    }

    private fun body(response: MockHttpServletResponse): Map<*, *> = mapper.readValue(response.contentAsByteArray, Map::class.java)

    private companion object {
        val FIRST: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SECOND: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        const val ORIGIN = "https://app.synthetic.example"
        val NOW: Instant = Instant.parse("2026-11-11T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val KEY = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val mapper = ObjectMapper()

        fun tokens(clock: Clock = CLOCK) =
            Rs256AccessTokenAdapter(
                KEY.private,
                mapOf("profile-test" to KEY.public),
                "profile-test",
                clock,
                "https://api.synthetic.example",
                "meetme-web",
            )
    }
}
