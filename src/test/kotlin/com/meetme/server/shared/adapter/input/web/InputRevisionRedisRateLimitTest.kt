package com.meetme.server.shared.adapter.input.web

import com.meetme.server.config.RateLimitProperties
import com.meetme.server.config.RateLimitRule
import com.meetme.server.shared.adapter.output.redis.RedisRateLimiter
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #92 exact new HOST routes use actual Redis for quota; outage must fail closed before a write reaches the chain.
class InputRevisionRedisRateLimitTest {
    @Test
    fun `reopen and analysis are room scoped Redis HOST commands with 429 Retry-After`() {
        val factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379)).apply { afterPropertiesSet() }
        try {
            val template = StringRedisTemplate(factory).apply { afterPropertiesSet() }
            val filter =
                RateLimitFilter(
                    RedisRateLimiter(template),
                    RateLimitProperties(hostCommand = RateLimitRule(1, Duration.ofMinutes(1), true)),
                    ObjectMapper(),
                )
            for (route in listOf("reopen", "analysis")) {
                val cookie = "synthetic-${UUID.randomUUID()}"
                val room = UUID.randomUUID().toString()
                val first = request(room, route, cookie)
                val allowed = MockFilterChain()
                filter.doFilter(first, MockHttpServletResponse(), allowed)
                assertEquals(first, allowed.request)
                val response = MockHttpServletResponse()
                val denied = MockFilterChain()
                filter.doFilter(request(room, route, cookie), response, denied)
                assertEquals(429, response.status)
                assertNull(denied.request)
                val document = ObjectMapper().readTree(response.contentAsByteArray)
                assertEquals("RATE_LIMIT_EXCEEDED", document.path("code").stringValue())
                val retry = requireNotNull(response.getHeader("Retry-After")).toLong()
                assertTrue(retry in 1..60)
                assertFalse(response.contentAsString.contains(cookie))
                val other = request(UUID.randomUUID().toString(), route, cookie)
                val independent = MockFilterChain()
                filter.doFilter(other, MockHttpServletResponse(), independent)
                assertEquals(other, independent.request)
                val keys = requireNotNull(template.keys("meetme:rate:host-command:*"))
                assertTrue(keys.none { key -> requireNotNull(key).contains(cookie) || requireNotNull(key).contains(room) })
            }
        } finally {
            factory.destroy()
        }
    }

    @Test
    fun `new correction commands fail closed with 503 during Redis outage and execute no write chain`() {
        val unavailable = RateLimitPort { _, _, _ -> throw DataAccessResourceFailureException("synthetic Redis outage") }
        val filter = RateLimitFilter(unavailable, RateLimitProperties(), ObjectMapper())
        for (route in listOf("reopen", "analysis")) {
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request("synthetic-room", route, "synthetic-cookie"), response, chain)
            assertEquals(503, response.status)
            assertNull(chain.request)
            assertEquals("RATE_LIMIT_UNAVAILABLE", ObjectMapper().readTree(response.contentAsByteArray).path("code").stringValue())
            assertFalse(response.contentAsString.contains("synthetic-cookie"))
        }
    }

    private fun request(
        room: String,
        route: String,
        cookie: String,
    ) = MockHttpServletRequest("POST", "/api/rooms/$room/$route").apply {
        setCookies(Cookie(GuestCookie.NAME, cookie))
    }

    companion object {
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = redis.stop()
    }
}
