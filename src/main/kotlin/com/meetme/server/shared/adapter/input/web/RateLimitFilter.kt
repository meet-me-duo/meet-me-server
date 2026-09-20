package com.meetme.server.shared.adapter.input.web

import com.meetme.server.config.RateLimitProperties
import com.meetme.server.config.RateLimitRule
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "meetme.rate-limit", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RateLimitFilter(
    private val rateLimitPort: RateLimitPort,
    private val properties: RateLimitProperties,
    private val objectMapper: ObjectMapper,
    private val metrics: ApplicationMetricsPort? = null,
    private val messageSource: MessageSource? = null,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val policy = policy(request)
        if (policy == null) {
            filterChain.doFilter(request, response)
            return
        }
        val (category, rule) = policy
        val identifier = request.cookies?.firstOrNull { it.name == GuestCookie.NAME }?.value ?: request.remoteAddr
        val subject =
            if (category == Category.HOST_COMMAND) {
                "$identifier:${request.requestURI.split('/').getOrNull(3).orEmpty()}"
            } else {
                identifier
            }
        val key = "meetme:rate:${category.key}:${digest(subject)}"
        val decision =
            try {
                rateLimitPort.check(key, rule.limit, rule.window)
            } catch (_: DataAccessException) {
                if (rule.failClosed) {
                    metrics?.rateLimit(category.key, "UNAVAILABLE_CLOSED")
                    writeProblem(response, request, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE", null)
                    return
                }
                metrics?.rateLimit(category.key, "UNAVAILABLE_OPEN")
                filterChain.doFilter(request, response)
                return
            }
        if (!decision.allowed) {
            metrics?.rateLimit(category.key, "REJECTED")
            writeProblem(
                response,
                request,
                429,
                "RATE_LIMIT_EXCEEDED",
                decision.retryAfter,
            )
            return
        }
        metrics?.rateLimit(category.key, "ALLOWED")
        filterChain.doFilter(request, response)
    }

    private fun policy(request: HttpServletRequest): Pair<Category, RateLimitRule>? {
        val path = request.requestURI
        return when {
            request.method == "POST" && path == "/api/rooms" -> Category.CREATE to properties.createRoom
            request.method == "POST" && PARTICIPANTS.matches(path) -> Category.JOIN to properties.joinRoom
            request.method == "PUT" && SUBMISSION.matches(path) -> Category.SUBMISSION to properties.submission
            request.method == "POST" && HOST_COMMAND.matches(path) -> Category.HOST_COMMAND to properties.hostCommand
            else -> null
        }
    }

    private fun writeProblem(
        response: HttpServletResponse,
        request: HttpServletRequest,
        status: Int,
        code: String,
        retryAfter: Duration?,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        val retrySeconds = retryAfter?.seconds?.coerceAtLeast(1)
        retrySeconds?.let { response.setHeader("Retry-After", it.toString()) }
        objectMapper.writeValue(
            response.outputStream,
            linkedMapOf(
                "type" to "https://api.meet-me.co.kr/problems/${code.lowercase().replace('_', '-')}",
                "title" to if (status == 429) "Too Many Requests" else "Service Unavailable",
                "status" to status,
                "detail" to
                    (
                        messageSource?.getMessage("api.error.$code", null, code, request.locale)
                            ?: if (status == 429) {
                                "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
                            } else {
                                "요청 제한 서비스를 사용할 수 없습니다."
                            }
                    ),
                "instance" to URI.create(request.requestURI).toString(),
                "code" to code,
                "retry_after_seconds" to retrySeconds,
            ),
        )
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private enum class Category(
        val key: String,
    ) {
        CREATE("create"),
        JOIN("join"),
        SUBMISSION("submission"),
        HOST_COMMAND("host-command"),
    }

    private companion object {
        val PARTICIPANTS = Regex("^/api/rooms/[^/]+/participants$")
        val SUBMISSION = Regex("^/api/rooms/[^/]+/submission$")
        val HOST_COMMAND =
            Regex("^/api/rooms/[^/]+/(close|analysis/retry|candidates/[^/]+/confirmation)$")
    }
}
