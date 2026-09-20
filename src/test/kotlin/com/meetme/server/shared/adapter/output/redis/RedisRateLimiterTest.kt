package com.meetme.server.shared.adapter.output.redis

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisRateLimiterTest {
    @Test
    fun `고정 구간 한도까지 허용하고 초과 요청에는 남은 대기 시간을 반환한다`() {
        val limiter = RedisRateLimiter(redisTemplate())
        val key = "test:rate:${UUID.randomUUID()}"

        assertTrue(limiter.check(key, 2, Duration.ofMinutes(1)).allowed)
        assertTrue(limiter.check(key, 2, Duration.ofMinutes(1)).allowed)
        val denied = limiter.check(key, 2, Duration.ofMinutes(1))

        assertFalse(denied.allowed)
        assertTrue(denied.retryAfter > Duration.ZERO)
        assertTrue(denied.retryAfter <= Duration.ofMinutes(1))
    }

    private fun redisTemplate(): StringRedisTemplate {
        val factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
        factory.afterPropertiesSet()
        return StringRedisTemplate(factory).also { it.afterPropertiesSet() }
    }

    companion object {
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379)

        init {
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = redis.stop()
    }
}
