package com.meetme.server.shared.adapter.output.redis

import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class RedisRateLimiter(
    private val redisTemplate: StringRedisTemplate,
) : RateLimitPort {
    override fun check(
        key: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision {
        require(key.isNotBlank())
        require(limit > 0)
        require(!window.isNegative && !window.isZero)
        val count =
            requireNotNull(
                redisTemplate.execute(INCREMENT_SCRIPT, listOf(key), window.toMillis().toString()),
            ) { "Redis rate limit script returned no result" }
        val remaining = Duration.ofMillis(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS).coerceAtLeast(1_000))
        return RateLimitDecision(count <= limit, if (count <= limit) Duration.ZERO else remaining)
    }

    private companion object {
        val INCREMENT_SCRIPT =
            DefaultRedisScript(
                """
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """.trimIndent(),
                Long::class.java,
            )
    }
}
