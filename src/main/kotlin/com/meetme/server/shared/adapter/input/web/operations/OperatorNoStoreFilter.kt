package com.meetme.server.shared.adapter.input.web.operations

import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.AuditRoute
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.Clock
import java.util.UUID

/** Buffer protected output until pre-controller authentication and audit handling settle. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class OperatorNoStoreFilter(
    private val audit: OperatorAccessAuditPort,
    private val clock: Clock,
    private val identity: OperatorIdentityPort? = null,
) : OncePerRequestFilter() {
    companion object {
        const val AUDIT_HANDLED_ATTRIBUTE = "meetme.operations.auditHandled"
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !path(request).startsWith("/api/operations/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("Referrer-Policy", "no-referrer")
        // MVC validates transport preflight; no protected query or business audit is involved.
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response)
            return
        }
        val buffered = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(request, buffered)
        } catch (_: Exception) {
            buffered.resetBuffer()
            buffered.status = 503
        }
        val handled = request.getAttribute(AUDIT_HANDLED_ATTRIBUTE) == true
        if (!handled) {
            val location = path(request).removePrefix("/api/operations/").split('/')
            val route =
                when (location.firstOrNull()) {
                    "invocations" -> AuditRoute.INVOCATION_DETAIL
                    "rooms" -> if (location.size > 1) AuditRoute.ROOM_DETAIL else AuditRoute.ROOM_LIST
                    else -> AuditRoute.ROOM_LIST
                }
            val target = OperationsCursorCodec.uuidOrNull(location.getOrNull(1))
            if (buffered.status < 400) buffered.status = 503
            val outcome =
                when (buffered.status) {
                    401, 403 -> AuditOutcome.DENIED
                    400 -> AuditOutcome.INVALID_QUERY
                    404 -> AuditOutcome.NOT_FOUND
                    else -> AuditOutcome.UNAVAILABLE
                }
            val actor = runCatching { identity?.currentUserId() }.getOrNull()
            try {
                audit.append(OperatorAccessAudit(UUID.randomUUID(), clock.instant(), actor, route, target, outcome))
            } catch (_: Exception) {
                buffered.status = 503
            }
        }
        if (buffered.status >= 400) {
            val status = buffered.status.takeIf { it in setOf(400, 401, 403, 404, 405) } ?: 503
            buffered.resetBuffer()
            buffered.status = status
            buffered.characterEncoding = "UTF-8"
            buffered.contentType = "application/problem+json"
            buffered.writer.write(OperatorProblems.json(status))
        }
        buffered.copyBodyToResponse()
    }

    private fun path(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath)
}
