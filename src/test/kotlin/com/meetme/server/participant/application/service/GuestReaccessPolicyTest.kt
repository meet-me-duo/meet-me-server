package com.meetme.server.participant.application.service

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import com.meetme.server.participant.domain.GuestReaccessDecision
import com.meetme.server.participant.domain.GuestReaccessProof
import com.meetme.server.participant.domain.GuestReaccessRecord
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** These expectations describe the agreed proof contract; persistence and HTTP are tested separately. */
class GuestReaccessPolicyTest {
    private val policy = GuestReaccessPolicy(ExactPasswordPort())

    @Test
    fun `I06 존재하지 않는 기록은 일반 거부로 귀결된다`() {
        assertEquals(GuestReaccessDecision.DENIED, policy.evaluate(null, proof(password = PASSWORD)))
    }

    @Test
    fun `I04 기존 소유 세션이어도 다른 방을 지정하면 거부한다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(), proof(session = OWNER, password = PASSWORD).copy(roomId = OTHER_ROOM)),
        )
    }

    @Test
    fun `A03 기존 소유 세션이어도 다른 participant를 지정하면 거부한다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record(), proof(session = OWNER, password = PASSWORD).copy(participantId = OTHER_PARTICIPANT)),
        )
    }

    @Test
    fun `I02 유효한 기존 소유 세션은 암호를 재입력하지 않고 범위를 유지한다`() {
        assertEquals(GuestReaccessDecision.OWNER, policy.evaluate(record(), proof(session = OWNER)))
    }

    @Test
    fun `I08 기존 소유 세션에 틀린 암호가 전달되어도 기존 권한을 회수하지 않는다`() {
        assertEquals(
            GuestReaccessDecision.OWNER,
            policy.evaluate(record(), proof(session = OWNER, password = "wrong-password")),
        )
    }

    @Test
    fun `I02 암호 없는 기록의 기존 소유 세션도 읽기 범위를 유지한다`() {
        assertEquals(
            GuestReaccessDecision.OWNER,
            policy.evaluate(record().copy(passwordHash = null), proof(session = OWNER)),
        )
    }

    @Test
    fun `I05 다른 기기의 정확한 암호 증명은 guest 제출 접근만 부여한다`() {
        assertEquals(
            GuestReaccessDecision.VERIFIED_GUEST,
            policy.evaluate(record(), proof(session = OTHER_SESSION, password = PASSWORD)),
        )
        assertEquals(
            GuestReaccessDecision.VERIFIED_GUEST,
            policy.evaluate(record(), proof(password = PASSWORD)),
        )
    }

    @Test
    fun `I06 다른 기기의 암호 누락 빈 값 오입력은 모두 거부한다`() {
        listOf(null, "", "wrong-password", " $PASSWORD ").forEach { password ->
            assertEquals(
                GuestReaccessDecision.DENIED,
                policy.evaluate(record(), proof(session = OTHER_SESSION, password = password)),
            )
        }
    }

    @Test
    fun `W01 암호 없는 기록의 다른 기기는 기존 원문 없는 대체 입력만 허용한다`() {
        listOf(null, OTHER_SESSION).forEach { session ->
            assertEquals(
                GuestReaccessDecision.REPLACE_ONLY,
                policy.evaluate(record().copy(passwordHash = null), proof(session = session)),
            )
        }
    }

    @Test
    fun `W01 암호 없는 기록에 임의 암호를 제출해도 읽기 권한으로 승격하지 않는다`() {
        assertEquals(
            GuestReaccessDecision.REPLACE_ONLY,
            policy.evaluate(record().copy(passwordHash = null), proof(password = PASSWORD)),
        )
    }

    @Test
    fun `W05 암호 설정 기록은 암호 없는 대체 입력으로 우회할 수 없다`() {
        assertEquals(GuestReaccessDecision.DENIED, policy.evaluate(record(), proof()))
    }

    @Test
    fun `M02 회원 연결 기록도 기존 유효 소유 세션 범위는 유지한다`() {
        assertEquals(
            GuestReaccessDecision.OWNER,
            policy.evaluate(record().copy(memberLinked = true), proof(session = OWNER)),
        )
    }

    @Test
    fun `M02 회원 연결 기록은 이전 guest 암호로 다른 기기에서 접근할 수 없다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record().copy(memberLinked = true), proof(password = PASSWORD)),
        )
    }

    @Test
    fun `M03 회원 연결된 무암호 기록에는 다른 기기의 대체 입력도 허용하지 않는다`() {
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(record().copy(memberLinked = true, passwordHash = null), proof()),
        )
    }

    @Test
    fun `I04 다른 방 또는 participant는 무암호여도 대체 입력 권한을 얻지 못한다`() {
        val unprotected = record().copy(passwordHash = null)

        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(unprotected, proof().copy(roomId = OTHER_ROOM)),
        )
        assertEquals(
            GuestReaccessDecision.DENIED,
            policy.evaluate(unprotected, proof().copy(participantId = OTHER_PARTICIPANT)),
        )
    }

    private fun record(): GuestReaccessRecord =
        GuestReaccessRecord(
            roomId = ROOM,
            participantId = PARTICIPANT,
            ownerSessionId = OWNER,
            passwordHash = HASH,
            memberLinked = false,
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
        override fun hash(rawPassword: String): String = error("Policy must not create or reset a password")

        override fun verify(
            rawPassword: String,
            passwordHash: String,
        ): Boolean = passwordHash == HASH && rawPassword == PASSWORD
    }

    companion object {
        private const val PASSWORD = "guest proof 108"
        private const val HASH = "opaque-test-hash-for-108"
        private val ROOM = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OTHER_ROOM = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val PARTICIPANT = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val OTHER_PARTICIPANT = UUID.fromString("00000000-0000-0000-0000-000000000004")
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000005")
        private val OTHER_SESSION = UUID.fromString("00000000-0000-0000-0000-000000000006")
    }
}
