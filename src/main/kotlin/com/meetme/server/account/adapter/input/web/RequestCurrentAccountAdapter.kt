package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Collections

class RequestCurrentAccountAdapter(
    private val access: AccessTokenPort,
) : CurrentAccountPort {
    override fun currentAccount(): AccountPrincipal? {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return null
        val cached = request.getAttribute(PRINCIPAL_ATTRIBUTE) as? AccountPrincipal
        if (cached != null) return cached
        val headers = Collections.list(request.getHeaders("Authorization"))
        if (headers.isEmpty()) return null
        if (headers.size != 1) throw AuthFailure("AUTH_ACCESS_INVALID")
        val header = headers.single()
        val match = BEARER.matchEntire(header) ?: throw AuthFailure("AUTH_ACCESS_INVALID")
        val token = match.groupValues[1]
        if (token.length > 8192) throw AuthFailure("AUTH_ACCESS_INVALID")
        return access.verify(token).also { request.setAttribute(PRINCIPAL_ATTRIBUTE, it) }
    }

    private companion object {
        const val PRINCIPAL_ATTRIBUTE = "meetme.auth.verifiedAccount"
        val BEARER = Regex("^Bearer ([A-Za-z0-9._~+/-]+=*)$", RegexOption.IGNORE_CASE)
    }
}
