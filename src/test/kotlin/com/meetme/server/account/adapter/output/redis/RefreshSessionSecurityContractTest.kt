package com.meetme.server.account.adapter.output.redis

import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthAttempt
import com.meetme.server.account.domain.OAuthProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR-029/031: real Redis, independent clients and a public Clock; no in-memory substitute for atomicity.
class RefreshSessionSecurityContractTest {
    private lateinit var factory: LettuceConnectionFactory
    private lateinit var redis: StringRedisTemplate
    private lateinit var clock: MutableClock
    private lateinit var sessions: RedisRefreshSessionAdapter
    private val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic member")

    @BeforeEach
    fun setUp() {
        factory = LettuceConnectionFactory(container.host, container.getMappedPort(6379)).apply { afterPropertiesSet() }
        redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
        clock = MutableClock(Instant.parse("2026-10-17T12:00:00Z"))
        sessions = RedisRefreshSessionAdapter(redis, clock)
    }

    @AfterEach
    fun tearDown() = factory.destroy()

    @Test
    fun `opaque tokens are unique with fourteen day idle and fixed thirty day absolute expiry`() {
        val issued = sessions.create(principal)
        val other = sessions.create(principal)
        assertNotEquals(issued.refreshToken, other.refreshToken)
        assertTrue(issued.refreshToken.length >= 43)
        assertFalse(issued.refreshToken.contains('.'))
        assertEquals(clock.instant().plus(Duration.ofDays(14)), issued.expiresAt)
        assertEquals(clock.instant().plus(Duration.ofDays(30)), issued.absoluteExpiresAt)
        val keys = requireNotNull(redis.keys("*"))
        for (key in keys) {
            assertFalse(requireNotNull(key).contains(issued.refreshToken))
            val stored = redis.execute(RedisCallback { connection -> connection.keyCommands().dump(key.toByteArray()) })
            assertFalse(stored?.decodeToString()?.contains(issued.refreshToken) ?: false)
        }
    }

    @Test
    fun `rotation preserves account and absolute expiry then replay revokes newest descendant`() {
        val issued = sessions.create(principal)
        clock.advance(Duration.ofDays(10))
        val next = sessions.rotate(issued.refreshToken)
        assertEquals(principal, next.principal)
        assertNotEquals(issued.refreshToken, next.refreshToken)
        assertEquals(clock.instant().plus(Duration.ofDays(14)), next.expiresAt)
        assertEquals(issued.absoluteExpiresAt, next.absoluteExpiresAt)
        assertFailsWith<AuthFailure> { sessions.rotate(issued.refreshToken) }
        assertFailsWith<AuthFailure> { sessions.rotate(next.refreshToken) }
    }

    @Test
    fun `idle boundary and absolute boundary refuse rotation while final day cookie life is capped`() {
        val idle = sessions.create(principal)
        clock.advance(Duration.ofDays(14))
        assertFailsWith<AuthFailure> { sessions.rotate(idle.refreshToken) }
        val active = sessions.create(principal)
        clock.advance(Duration.ofDays(13))
        val day13 = sessions.rotate(active.refreshToken)
        clock.advance(Duration.ofDays(13))
        val day26 = sessions.rotate(day13.refreshToken)
        assertEquals(active.absoluteExpiresAt, day26.expiresAt)
        assertEquals(Duration.ofDays(4), Duration.between(clock.instant(), day26.expiresAt))
        clock.advance(Duration.ofDays(4))
        assertFailsWith<AuthFailure> { sessions.rotate(day26.refreshToken) }
    }

    @Test
    fun `concurrent rotation across adapter instances allows one winner then invalidates the whole replayed family`() {
        val issued = sessions.create(principal)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                (1..8).map {
                    pool.submit(
                        Callable {
                            gate.await(10, TimeUnit.SECONDS)
                            runCatching { RedisRefreshSessionAdapter(redis, clock).rotate(issued.refreshToken) }
                        },
                    )
                }
            gate.countDown()
            val outcomes = results.map { it.get(15, TimeUnit.SECONDS) }
            val winners = outcomes.mapNotNull { it.getOrNull() }
            assertEquals(1, winners.size)
            assertEquals(7, outcomes.count { it.exceptionOrNull() is AuthFailure })
            assertFailsWith<AuthFailure> { sessions.rotate(winners.single().refreshToken) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `current logout preserves another device while all logout preserves another account`() {
        val deviceA = sessions.create(principal)
        val deviceB = sessions.create(principal)
        val stranger = sessions.create(AccountPrincipal(UUID.randomUUID(), "Other synthetic account"))
        sessions.revoke(deviceA.refreshToken)
        assertFailsWith<AuthFailure> { sessions.rotate(deviceA.refreshToken) }
        val remaining = sessions.rotate(deviceB.refreshToken)
        sessions.revokeAll(principal.accountId)
        assertFailsWith<AuthFailure> { sessions.rotate(remaining.refreshToken) }
        assertNotEquals(stranger.refreshToken, sessions.rotate(stranger.refreshToken).refreshToken)
    }

    @Test
    fun `logout using already rotated token still revokes its current family`() {
        val initial = sessions.create(principal)
        val next = sessions.rotate(initial.refreshToken)
        sessions.revoke(initial.refreshToken)
        assertFailsWith<AuthFailure> { sessions.rotate(next.refreshToken) }
    }

    @Test
    fun `concurrent all-device logout and rotation leave no descendant session alive`() {
        repeat(12) {
            val issued = sessions.create(principal)
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val rotate =
                    pool.submit(
                        Callable {
                            gate.await()
                            runCatching { sessions.rotate(issued.refreshToken) }
                        },
                    )
                val logout =
                    pool.submit(
                        Callable {
                            gate.await()
                            sessions.revokeAll(principal.accountId)
                        },
                    )
                gate.countDown()
                val rotated = rotate.get(10, TimeUnit.SECONDS).getOrNull()
                logout.get(10, TimeUnit.SECONDS)
                if (rotated != null) assertFailsWith<AuthFailure> { sessions.rotate(rotated.refreshToken) }
                assertFailsWith<AuthFailure> { sessions.rotate(issued.refreshToken) }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `unknown malformed and lost Redis session cannot be recovered by token content`() {
        assertFailsWith<AuthFailure> { sessions.rotate("not-a-refresh-token") }
        val issued = sessions.create(principal)
        val keys = requireNotNull(redis.keys("*"))
        redis.delete(keys)
        assertFailsWith<AuthFailure> { sessions.rotate(issued.refreshToken) }
        assertFalse(requireNotNull(redis.keys("*")).any { it.contains(issued.refreshToken) })
    }

    @Test
    fun `Redis outage refuses refresh and cannot fabricate a successful session`() {
        val unavailable = LettuceConnectionFactory("127.0.0.1", 1).apply { afterPropertiesSet() }
        try {
            val adapter = RedisRefreshSessionAdapter(StringRedisTemplate(unavailable).apply { afterPropertiesSet() }, clock)
            assertFails { adapter.rotate("synthetic-refresh") }
        } finally {
            unavailable.destroy()
        }
    }

    @Test
    fun `real Redis OAuth state is consumed exactly once across concurrent clients`() {
        val state = "synthetic-state-${UUID.randomUUID()}"
        val attempt =
            OAuthAttempt(
                OAuthProvider.GOOGLE,
                "synthetic-browser-digest",
                "synthetic-code-verifier",
                "synthetic-nonce",
                clock.instant().plusSeconds(300),
            )
        RedisOAuthStateAdapter(redis, clock).save(state, attempt)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (1..4).map {
                    pool.submit(
                        Callable {
                            gate.await()
                            RedisOAuthStateAdapter(redis, clock).consume(state)
                        },
                    )
                }
            gate.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(attempt), results.filterNotNull())
            assertNull(RedisOAuthStateAdapter(redis, clock).consume(state))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `Redis OAuth state expiry rejects exact boundary and never returns browser verifier nonce after expiry`() {
        val state = "synthetic-state-${UUID.randomUUID()}"
        val attempt =
            OAuthAttempt(
                OAuthProvider.KAKAO,
                "synthetic-browser-digest",
                "synthetic-code-verifier",
                "synthetic-nonce",
                clock.instant().plusSeconds(300),
            )
        val states = RedisOAuthStateAdapter(redis, clock)
        states.save(state, attempt)
        clock.advance(Duration.ofSeconds(300))
        assertNull(states.consume(state))
        assertNull(states.consume(state))
    }

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    companion object {
        private val container = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = container.stop()
    }
}
