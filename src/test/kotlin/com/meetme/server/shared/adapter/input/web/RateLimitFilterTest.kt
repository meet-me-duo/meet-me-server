package com.meetme.server.shared.adapter.input.web

import com.meetme.server.config.RateLimitProperties
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RateLimitFilterTest {
    @Test
    fun `방 생성 한도 초과는 429와 Retry-After를 반환하고 원 식별자를 노출하지 않는다`() {
        val filter = filter(RateLimitPort { _, _, _ -> RateLimitDecision(false, Duration.ofSeconds(17)) })
        val request = MockHttpServletRequest("POST", "/api/rooms").apply { remoteAddr = "203.0.113.9" }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(429, response.status)
        assertEquals("17", response.getHeader("Retry-After"))
        assertFalse(response.contentAsString.contains("203.0.113.9"))
        assertEquals("RATE_LIMIT_EXCEEDED", ObjectMapper().readTree(response.contentAsByteArray).path("code").stringValue())
    }

    @Test
    fun `Redis 장애 중 제출은 fail open으로 계속 처리한다`() {
        val filter = filter(RateLimitPort { _, _, _ -> throw DataAccessResourceFailureException("redis unavailable") })
        val request = MockHttpServletRequest("PUT", "/api/rooms/abc/submission")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertEquals(request, chain.request)
    }

    @Test
    fun `Redis 장애 중 비용 유발 주최자 명령은 fail closed한다`() {
        val filter = filter(RateLimitPort { _, _, _ -> throw DataAccessResourceFailureException("redis unavailable") })
        val request = MockHttpServletRequest("POST", "/api/rooms/abc/analysis/retry")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(503, response.status)
        assertEquals("RATE_LIMIT_UNAVAILABLE", ObjectMapper().readTree(response.contentAsByteArray).path("code").stringValue())
    }

    private fun filter(port: RateLimitPort) = RateLimitFilter(port, RateLimitProperties(), ObjectMapper())
}
