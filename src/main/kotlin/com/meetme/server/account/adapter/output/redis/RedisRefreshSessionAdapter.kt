package com.meetme.server.account.adapter.output.redis

import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthEntropy
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.RefreshRotation
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** All session keys share one Redis Cluster hash slot so rotation and revocation remain atomic. */
class RedisRefreshSessionAdapter(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) : RefreshSessionPort {
    override fun create(principal: AccountPrincipal): RefreshRotation =
        boundary {
            val now = clock.instant()
            val absolute = now.plus(Duration.ofDays(30))
            val expires = now.plus(Duration.ofDays(14))
            val token = AuthEntropy.token()
            val family = UUID.randomUUID().toString()
            val hash = AuthEntropy.digest(token)
            val result =
                redis.execute(
                    CREATE,
                    listOf(tokenKey(hash), familyKey(family), userKey(principal.accountId)),
                    principal.accountId.toString(),
                    principal.displayName,
                    now.toEpochMilli().toString(),
                    absolute.toEpochMilli().toString(),
                    expires.toEpochMilli().toString(),
                    hash,
                    family,
                )
            if (result != "OK") throw AuthFailure("AUTH_UNAVAILABLE")
            RefreshRotation(principal, token, expires, absolute)
        }

    override fun rotate(token: String): RefreshRotation =
        boundary {
            validateToken(token)
            val next = AuthEntropy.token()
            val now = clock.instant()
            val result =
                redis.execute(
                    ROTATE,
                    listOf(tokenKey(AuthEntropy.digest(token)), tokenKey(AuthEntropy.digest(next))),
                    now.toEpochMilli().toString(),
                    AuthEntropy.digest(token),
                    AuthEntropy.digest(next),
                    PREFIX,
                    Duration.ofDays(14).toMillis().toString(),
                ) ?: throw AuthFailure("AUTH_UNAVAILABLE")
            if (result == "INVALID") throw AuthFailure("AUTH_REFRESH_INVALID")
            val values = mapper.readTree(result)
            RefreshRotation(
                AccountPrincipal(UUID.fromString(values[0].stringValue()), values[1].stringValue()),
                next,
                Instant.ofEpochMilli(values[2].stringValue().toLong()),
                Instant.ofEpochMilli(values[3].stringValue().toLong()),
            )
        }

    override fun revoke(token: String) {
        boundary {
            validateToken(token)
            redis.execute(REVOKE, listOf(tokenKey(AuthEntropy.digest(token))), PREFIX)
                ?: throw AuthFailure("AUTH_UNAVAILABLE")
        }
    }

    override fun revokeAll(accountId: UUID) {
        boundary {
            redis.execute(REVOKE_ALL, listOf(userKey(accountId)), PREFIX)
                ?: throw AuthFailure("AUTH_UNAVAILABLE")
        }
    }

    private fun validateToken(token: String) {
        if (!TOKEN.matches(token)) throw AuthFailure("AUTH_REFRESH_INVALID")
    }

    private fun <T> boundary(action: () -> T): T =
        try {
            action()
        } catch (failure: AuthFailure) {
            throw failure
        } catch (_: DataAccessException) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        } catch (_: Exception) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        }

    private fun tokenKey(hash: String) = "${PREFIX}token:$hash"

    private fun familyKey(family: String) = "${PREFIX}family:$family"

    private fun userKey(accountId: UUID) = "${PREFIX}user:$accountId"

    private companion object {
        const val PREFIX = "meetme:auth:{sessions}:"
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        val mapper = JsonMapper.builder().build()
        val CREATE =
            DefaultRedisScript(
                """
                local ttl = tonumber(ARGV[4]) - tonumber(ARGV[3])
                if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 'COLLISION' end
                redis.call('SET', KEYS[1], ARGV[7], 'PX', ttl)
                redis.call('HSET', KEYS[2], 'uid', ARGV[1], 'name', ARGV[2], 'absolute', ARGV[4],
                    'expires', ARGV[5], 'active', ARGV[6], 'revoked', '0')
                redis.call('PEXPIRE', KEYS[2], ttl)
                redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[3])
                redis.call('ZADD', KEYS[3], ARGV[4], ARGV[7])
                if redis.call('PTTL', KEYS[3]) < ttl then redis.call('PEXPIRE', KEYS[3], ttl) end
                return 'OK'
                """.trimIndent(),
                String::class.java,
            )
        val ROTATE =
            DefaultRedisScript(
                """
                local family = redis.call('GET', KEYS[1])
                if not family then return 'INVALID' end
                local fk = ARGV[4] .. 'family:' .. family
                if redis.call('EXISTS', fk) == 0 then return 'INVALID' end
                local values = redis.call('HMGET', fk, 'uid', 'name', 'absolute', 'expires', 'active', 'revoked')
                local now = tonumber(ARGV[1])
                local absolute = tonumber(values[3])
                if values[6] == '1' or absolute <= now or tonumber(values[4]) <= now then return 'INVALID' end
                if values[5] ~= ARGV[2] then
                    redis.call('HSET', fk, 'revoked', '1')
                    return 'INVALID'
                end
                if redis.call('EXISTS', KEYS[2]) == 1 then return 'INVALID' end
                local expires = math.min(now + tonumber(ARGV[5]), absolute)
                local ttl = absolute - now
                redis.call('HSET', fk, 'active', ARGV[3], 'expires', tostring(expires))
                redis.call('PEXPIRE', fk, ttl)
                redis.call('SET', KEYS[2], family, 'PX', ttl)
                return cjson.encode({values[1], values[2], tostring(expires), tostring(absolute)})
                """.trimIndent(),
                String::class.java,
            )
        val REVOKE =
            DefaultRedisScript(
                """
                local family = redis.call('GET', KEYS[1])
                if family then
                    local fk = ARGV[1] .. 'family:' .. family
                    if redis.call('EXISTS', fk) == 1 then redis.call('HSET', fk, 'revoked', '1') end
                end
                return 'OK'
                """.trimIndent(),
                String::class.java,
            )
        val REVOKE_ALL =
            DefaultRedisScript(
                """
                local families = redis.call('ZRANGE', KEYS[1], 0, -1)
                for _, family in ipairs(families) do
                    local fk = ARGV[1] .. 'family:' .. family
                    if redis.call('EXISTS', fk) == 1 then redis.call('HSET', fk, 'revoked', '1') end
                end
                redis.call('DEL', KEYS[1])
                return 'OK'
                """.trimIndent(),
                String::class.java,
            )
    }
}
