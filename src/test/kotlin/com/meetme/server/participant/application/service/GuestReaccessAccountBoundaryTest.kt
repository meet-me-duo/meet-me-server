package com.meetme.server.participant.application.service

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import com.meetme.server.participant.domain.GuestReaccessDecision
import com.meetme.server.participant.domain.GuestReaccessProof
import com.meetme.server.participant.domain.GuestReaccessRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** #109 account-owned and malformed snapshots never establish guest ownership from a null owner. */
class GuestReaccessAccountBoundaryTest {
    private val policy = GuestReaccessPolicy(ExactPasswordPort())

    @Test
    fun `M06 회원 소유 기록의 null owner와 null active session은 OWNER가 아니다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(memberLinked = true, passwordHash = null), proof()),
        )
    }

    @Test
    fun `M06 회원 소유 기록은 다른 세션과 과거 guest 암호로 재접근할 수 없다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(memberLinked = true, passwordHash = HASH), proof(OTHER_SESSION, PASSWORD)),
        )
    }

    @Test
    fun `M07 원래 소유 세션이 누락된 비회원 snapshot의 null 세션도 OWNER가 아니다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(memberLinked = false, passwordHash = null), proof()),
        )
    }

    @Test
    fun `M07 소유 세션 없는 비회원 snapshot은 정답 암호로도 권한을 만들지 않는다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(memberLinked = false, passwordHash = HASH), proof(OTHER_SESSION, PASSWORD)),
        )
    }

    @Test
    fun `M07 owner가 없는 모든 snapshot은 회원 연결 암호와 요청 세션 조합에 관계없이 fail closed한다`() {
        listOf(false, true).forEach { memberLinked ->
            listOf(null, HASH).forEach { passwordHash ->
                listOf(null, OTHER_SESSION).forEach { activeSession ->
                    listOf(null, "", "wrong-password", PASSWORD).forEach { rawPassword ->
                        assertEquals(
                            GuestReaccessDecision.DENIED,
                            policy.evaluate(
                                record(memberLinked, passwordHash),
                                proof(activeSession, rawPassword),
                            ),
                            "A missing guest owner cannot prove ownership or grant replacement access",
                        )
                    }
                }
            }
        }
    }

    private fun record(
        memberLinked: Boolean,
        passwordHash: String?,
    ): GuestReaccessRecord =
        GuestReaccessRecord(
            roomId = ROOM,
            participantId = PARTICIPANT,
            ownerSessionId = null,
            passwordHash = passwordHash,
            memberLinked = memberLinked,
        )

    private fun proof(
        session: UUID? = null,
        password: String? = null,
    ): GuestReaccessProof =
        GuestReaccessProof(
            roomId = ROOM,
            participantId = PARTICIPANT,
            activeSessionId = session,
            rawPassword = password,
        )

    private class ExactPasswordPort : GuestPasswordPort {
        override fun hash(rawPassword: String): String = error("Reaccess cannot create a password")

        override fun verify(
            rawPassword: String,
            passwordHash: String,
        ): Boolean = passwordHash == HASH && rawPassword == PASSWORD
    }

    companion object {
        private const val PASSWORD = "old-guest-secret-108"
        private const val HASH = "opaque-prior-guest-hash"
        private val ROOM = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val PARTICIPANT = UUID.fromString("20000000-0000-0000-0000-000000000002")
        private val OTHER_SESSION = UUID.fromString("20000000-0000-0000-0000-000000000003")
    }
}
