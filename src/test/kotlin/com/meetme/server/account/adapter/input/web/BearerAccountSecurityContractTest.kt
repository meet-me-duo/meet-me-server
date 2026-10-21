package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// An invalid supplied Bearer must never silently become a guest credential path.
class BearerAccountSecurityContractTest {
    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `missing Authorization means guest and valid Bearer returns verified account`() {
        val fixture = Fixture()
        bind()
        assertNull(fixture.adapter.currentAccount())
        assertTrue(fixture.tokens.isEmpty())
        bind("Bearer synthetic-valid")
        assertEquals(fixture.principal, fixture.adapter.currentAccount())
        assertEquals(listOf("synthetic-valid"), fixture.tokens)
    }

    @Test
    fun `invalid malformed blank wrong scheme or duplicate Authorization fail closed`() {
        for (headers in listOf(
            listOf(""),
            listOf("Bearer"),
            listOf("Bearer "),
            listOf("Basic synthetic"),
            listOf("Bearer synthetic-invalid"),
            listOf("Bearer synthetic-valid", "Bearer synthetic-valid"),
        )) {
            val fixture = Fixture()
            bind(*headers.toTypedArray())
            assertFailsWith<AuthFailure> { fixture.adapter.currentAccount() }
        }
    }

    private fun bind(vararg headers: String) {
        val request = MockHttpServletRequest("GET", "/api/rooms/synthetic")
        headers.forEach { request.addHeader("Authorization", it) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    private class Fixture {
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic")
        val tokens = mutableListOf<String>()
        val adapter =
            RequestCurrentAccountAdapter(
                object : AccessTokenPort {
                    override fun issue(principal: AccountPrincipal): String = error("not used")

                    override fun verify(token: String): AccountPrincipal {
                        tokens.add(token)
                        if (token != "synthetic-valid") throw AuthFailure("AUTH_ACCESS_INVALID")
                        return principal
                    }
                },
            )
    }
}
