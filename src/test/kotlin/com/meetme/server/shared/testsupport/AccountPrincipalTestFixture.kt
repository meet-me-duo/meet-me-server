package com.meetme.server.shared.testsupport

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.shared.adapter.input.web.GuestCookie
import jakarta.servlet.http.Cookie
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/** Mocked application account context for legacy business regressions; no production authentication bypass. */
class AccountPrincipalTestFixture(
    private val jdbc: JdbcTemplate,
    private val current: CurrentAccountPort,
) {
    fun install() {
        `when`(current.currentAccount()).thenAnswer {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            val marker = request?.cookies?.firstOrNull { it.name == GuestCookie.NAME && it.value.startsWith(PREFIX) }?.value
            val id = marker?.removePrefix(PREFIX)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            id?.let {
                jdbc
                    .query(
                        "SELECT display_name FROM account_users WHERE id = ?",
                        { rs, _ -> AccountPrincipal(it, rs.getString(1)) },
                        it,
                    ).firstOrNull()
            }
        }
    }

    fun newAccount(name: String = "host"): Cookie {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", id, name)
        return Cookie(GuestCookie.NAME, PREFIX + id)
    }

    companion object {
        const val PREFIX = "test-account-"
    }
}
