package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestReaccessLimitCode
import com.meetme.server.participant.application.port.output.GuestReaccessLimitException
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestReaccessAttemptGuardTest {
    @Test
    fun `R01 허용 요청도 IP 30회와 room participant 5회의 15분 제한을 모두 검사한다`() {
        val checks = mutableListOf<Check>()
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { key, limit, window ->
                    checks += Check(key, limit, window)
                    RateLimitDecision(true, Duration.ZERO)
                },
            )

        guard.check(ROOM, PARTICIPANT, ADDRESS)

        assertEquals(2, checks.size)
        val ip = checks.single { it.key.startsWith(IP_PREFIX) }
        val record = checks.single { it.key.startsWith(RECORD_PREFIX) }
        assertEquals(30, ip.limit)
        assertEquals(5, record.limit)
        assertEquals(Duration.ofMinutes(15), ip.window)
        assertEquals(Duration.ofMinutes(15), record.window)
    }

    @Test
    fun `R01 IP 한도 초과는 공통 제한 코드와 Retry After로 거부한다`() {
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { _, limit, _ ->
                    RateLimitDecision(limit != 30, Duration.ofSeconds(19))
                },
            )

        val failure = assertThrows<GuestReaccessLimitException> { guard.check(ROOM, PARTICIPANT, ADDRESS) }

        assertEquals(GuestReaccessLimitCode.TOO_MANY_ATTEMPTS, failure.code)
        assertEquals(Duration.ofSeconds(19), failure.retryAfter)
        assertNull(failure.cause)
    }

    @Test
    fun `R01 대상 한도 초과도 IP와 같은 제한 코드로 거부한다`() {
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { _, limit, _ ->
                    RateLimitDecision(limit != 5, Duration.ofSeconds(23))
                },
            )

        val failure = assertThrows<GuestReaccessLimitException> { guard.check(ROOM, PARTICIPANT, ADDRESS) }

        assertEquals(GuestReaccessLimitCode.TOO_MANY_ATTEMPTS, failure.code)
        assertEquals(Duration.ofSeconds(23), failure.retryAfter)
        assertNull(failure.cause)
    }

    @Test
    fun `R02 IP를 교체해도 같은 room participant의 대상 제한 키는 유지한다`() {
        val original = keys(ROOM, PARTICIPANT, ADDRESS)
        val changedAddress = keys(ROOM, PARTICIPANT, "203.0.113.109")

        assertEquals(original.record, changedAddress.record)
        assertNotEquals(original.ip, changedAddress.ip)
    }

    @Test
    fun `R03 같은 IP의 다른 participant와 방은 각각 다른 대상 키를 사용한다`() {
        val original = keys(ROOM, PARTICIPANT, ADDRESS)
        val changedParticipant = keys(ROOM, OTHER_PARTICIPANT, ADDRESS)
        val changedRoom = keys(OTHER_ROOM, PARTICIPANT, ADDRESS)

        assertEquals(original.ip, changedParticipant.ip)
        assertEquals(original.ip, changedRoom.ip)
        assertEquals(3, setOf(original.record, changedParticipant.record, changedRoom.record).size)
    }

    @Test
    fun `R05 Redis 키는 유형별 접두사와 SHA256 hex이며 원 식별자를 포함하지 않는다`() {
        val keys = keys(ROOM, PARTICIPANT, ADDRESS)

        assertTrue(keys.ip.matches(Regex("meetme:guest-reaccess:ip:[0-9a-f]{64}")))
        assertTrue(keys.record.matches(Regex("meetme:guest-reaccess:record:[0-9a-f]{64}")))
        listOf(keys.ip, keys.record).forEach { key ->
            assertFalse(key.contains(ADDRESS))
            assertFalse(key.contains(ROOM.toString()))
            assertFalse(key.contains(PARTICIPANT.toString()))
        }
    }

    @Test
    fun `R04 IP limiter 장애는 fail closed이며 내부 오류와 식별자를 전달하지 않는다`() {
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { _, _, _ ->
                    throw IllegalStateException("private-storage-error $ADDRESS $ROOM $PARTICIPANT")
                },
            )

        assertUnavailableWithoutDetails(guard)
    }

    @Test
    fun `R04 IP 허용 후 대상 limiter 장애도 fail closed한다`() {
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { key, _, _ ->
                    if (key.startsWith(RECORD_PREFIX)) {
                        throw IllegalStateException("private-storage-error $ADDRESS $ROOM $PARTICIPANT")
                    }
                    RateLimitDecision(true, Duration.ZERO)
                },
            )

        assertUnavailableWithoutDetails(guard)
    }

    private fun assertUnavailableWithoutDetails(guard: GuestReaccessAttemptGuard) {
        val failure = assertThrows<GuestReaccessLimitException> { guard.check(ROOM, PARTICIPANT, ADDRESS) }

        assertEquals(GuestReaccessLimitCode.LIMIT_UNAVAILABLE, failure.code)
        assertNull(failure.retryAfter)
        assertNull(failure.cause)
        val exposed = failure.toString()
        assertFalse(exposed.contains("private-storage-error"))
        assertFalse(exposed.contains(ADDRESS))
        assertFalse(exposed.contains(ROOM.toString()))
        assertFalse(exposed.contains(PARTICIPANT.toString()))
    }

    private fun keys(
        room: UUID,
        participant: UUID,
        address: String,
    ): Keys {
        val keys = mutableListOf<String>()
        val guard =
            GuestReaccessAttemptGuard(
                RateLimitPort { key, _, _ ->
                    keys += key
                    RateLimitDecision(true, Duration.ZERO)
                },
            )
        guard.check(room, participant, address)
        return Keys(keys.single { it.startsWith(IP_PREFIX) }, keys.single { it.startsWith(RECORD_PREFIX) })
    }

    private data class Check(
        val key: String,
        val limit: Int,
        val window: Duration,
    )

    private data class Keys(
        val ip: String,
        val record: String,
    )

    companion object {
        private const val IP_PREFIX = "meetme:guest-reaccess:ip:"
        private const val RECORD_PREFIX = "meetme:guest-reaccess:record:"
        private const val ADDRESS = "203.0.113.108"
        private val ROOM = UUID.fromString("10000000-0000-0000-0000-000000000001")
        private val OTHER_ROOM = UUID.fromString("10000000-0000-0000-0000-000000000002")
        private val PARTICIPANT = UUID.fromString("10000000-0000-0000-0000-000000000003")
        private val OTHER_PARTICIPANT = UUID.fromString("10000000-0000-0000-0000-000000000004")
    }
}
