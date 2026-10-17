package com.meetme.server.account.adapter.output.redis

import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Supplemental verification: unlike malformed tokens, a valid-format token must actually reach unavailable Redis.
class RefreshAvailabilitySecurityContractTest {
    @Test
    fun `valid-format refresh and session creation fail as unavailable when Redis cannot connect`() {
        val factory = LettuceConnectionFactory("127.0.0.1", 1).apply { afterPropertiesSet() }
        try {
            val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
            val adapter = RedisRefreshSessionAdapter(redis, Clock.systemUTC())
            val refresh = assertFailsWith<AuthFailure> { adapter.rotate("A".repeat(43)) }
            assertEquals("AUTH_UNAVAILABLE", refresh.code)
            val create = assertFailsWith<AuthFailure> { adapter.create(AccountPrincipal(UUID.randomUUID(), "Synthetic")) }
            assertEquals("AUTH_UNAVAILABLE", create.code)
        } finally {
            factory.destroy()
        }
    }
}
