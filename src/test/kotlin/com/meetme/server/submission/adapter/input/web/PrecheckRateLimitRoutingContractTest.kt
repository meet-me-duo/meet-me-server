package com.meetme.server.submission.adapter.input.web

import com.meetme.server.config.RateLimitProperties
import com.meetme.server.shared.adapter.input.web.RateLimitFilter
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Existing ADR-040 submission policy: per-session 30/minute and Redis failure opens writes. */
class PrecheckRateLimitRoutingContractTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/answer",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/retry",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/cancel",
        ],
    )
    fun `PS09 신규 검사 write 네 경로도 기존 제출 세션 30회 제한을 우회하지 못한다`(suffix: String) {
        val observed = mutableListOf<Pair<Int, Duration>>()
        val filter =
            RateLimitFilter(
                RateLimitPort { _, limit, window ->
                    observed += limit to window
                    RateLimitDecision(false, Duration.ofSeconds(17))
                },
                RateLimitProperties(),
                ObjectMapper(),
            )
        val request = request("POST", suffix)
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        assertEquals(429, response.status)
        assertEquals("17", response.getHeader("Retry-After"))
        assertEquals(listOf(30 to Duration.ofMinutes(1)), observed)
        assertEquals("RATE_LIMIT_EXCEEDED", ObjectMapper().readTree(response.contentAsByteArray).path("code").stringValue())
        assertFalse(response.contentAsString.contains(CREDENTIAL))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/answer",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/retry",
            "/3f96cc72-4a22-4147-9a29-b6768250c635/cancel",
        ],
    )
    fun `PS09 신규 검사 write도 기존 제출 Redis 장애 fail open 정책을 유지한다`(suffix: String) {
        val filter =
            RateLimitFilter(
                RateLimitPort { _, _, _ -> throw DataAccessResourceFailureException("synthetic Redis unavailable") },
                RateLimitProperties(),
                ObjectMapper(),
            )
        val request = request("POST", suffix)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertEquals(request, chain.request)
    }

    @Test
    fun `PS09 private current GET은 제출 write quota를 소비하지 않는다`() {
        val filter =
            RateLimitFilter(
                RateLimitPort { _, _, _ -> error("GET must not consume the write quota") },
                RateLimitProperties(),
                ObjectMapper(),
            )
        val request = request("GET", "/current")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertEquals(request, chain.request)
    }

    private fun request(
        method: String,
        suffix: String,
    ): MockHttpServletRequest =
        MockHttpServletRequest(method, "/api/rooms/abcdefghijklmnopqrstuv/submission/checks$suffix").apply {
            remoteAddr = "203.0.113.19"
            setCookies(Cookie("meet_me_guest", CREDENTIAL))
        }

    companion object {
        private const val CREDENTIAL = "synthetic-boundary-session-107"
    }
}
