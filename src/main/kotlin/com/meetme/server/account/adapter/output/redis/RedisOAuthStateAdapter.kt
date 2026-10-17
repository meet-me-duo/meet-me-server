package com.meetme.server.account.adapter.output.redis

import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.domain.AuthEntropy
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthAttempt
import com.meetme.server.account.domain.OAuthProvider
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

class RedisOAuthStateAdapter(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) : OAuthStatePort {
    override fun save(
        state: String,
        attempt: OAuthAttempt,
    ) {
        val ttl = Duration.between(clock.instant(), attempt.expiresAt)
        if (ttl.isNegative || ttl.isZero || ttl > Duration.ofMinutes(10)) throw AuthFailure("AUTH_OAUTH_INVALID")
        val json =
            mapper.writeValueAsString(
                mapOf(
                    "provider" to attempt.provider.name,
                    "browser" to attempt.browserBindingDigest,
                    "verifier" to attempt.codeVerifier,
                    "nonce" to attempt.nonce,
                    "expires" to attempt.expiresAt.toEpochMilli(),
                ),
            )
        try {
            if (redis.opsForValue().setIfAbsent(key(state), json, ttl) != true) throw AuthFailure("AUTH_OAUTH_INVALID")
        } catch (_: DataAccessException) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        }
    }

    override fun consume(state: String): OAuthAttempt? {
        try {
            val json = redis.opsForValue().getAndDelete(key(state)) ?: return null
            val node = mapper.readTree(json)
            val expires = Instant.ofEpochMilli(node.path("expires").longValue())
            if (!expires.isAfter(clock.instant())) return null
            return OAuthAttempt(
                OAuthProvider.valueOf(node.path("provider").stringValue()),
                node.path("browser").stringValue(),
                node.path("verifier").stringValue(),
                node.path("nonce").stringValue(),
                expires,
            )
        } catch (_: DataAccessException) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        } catch (_: Exception) {
            throw AuthFailure("AUTH_OAUTH_INVALID")
        }
    }

    private fun key(state: String) = "meetme:auth:oauth:${AuthEntropy.digest(state)}"

    private companion object {
        val mapper = JsonMapper.builder().build()
    }
}
