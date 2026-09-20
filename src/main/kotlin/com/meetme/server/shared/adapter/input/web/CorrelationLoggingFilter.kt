package com.meetme.server.shared.adapter.input.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorrelationLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        MDC.put(CORRELATION_ID, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            requestLogger.info(
                "http_request_completed method={} route={} status={} duration_ms={}",
                request.method,
                safeRoute(request.requestURI),
                response.status,
                durationMillis,
            )
            MDC.remove(CORRELATION_ID)
        }
    }

    private fun safeRoute(path: String): String =
        when {
            path == "/api/rooms" -> "/api/rooms"
            path.endsWith("/participants") -> "/api/rooms/{inviteCode}/participants"
            path.endsWith("/submission") -> "/api/rooms/{inviteCode}/submission"
            path.endsWith("/analysis/retry") -> "/api/rooms/{inviteCode}/analysis/retry"
            path.endsWith("/close") -> "/api/rooms/{inviteCode}/close"
            path.endsWith("/confirmation") -> "/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation"
            path.endsWith("/candidates") -> "/api/rooms/{inviteCode}/candidates"
            path.endsWith("/result") -> "/api/rooms/{inviteCode}/result"
            path.startsWith("/actuator/") -> "/actuator/{endpoint}"
            else -> "other"
        }

    companion object {
        const val HEADER = "X-Request-Id"
        const val CORRELATION_ID = "correlation_id"
        private val requestLogger = LoggerFactory.getLogger(CorrelationLoggingFilter::class.java)
    }
}
