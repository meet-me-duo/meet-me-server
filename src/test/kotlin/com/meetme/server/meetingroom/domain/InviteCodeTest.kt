package com.meetme.server.meetingroom.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InviteCodeTest {
    @Test
    fun `16바이트 엔트로피를 22자 base64url 초대 코드로 만든다`() {
        val entropy = ByteArray(16) { it.toByte() }

        val code = InviteCode.fromEntropy(entropy)

        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy), code.value)
        assertEquals(22, code.value.length)
        assertTrue(code.value.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun `초대 코드는 정확히 16바이트 엔트로피를 요구한다`() {
        assertThrows<IllegalArgumentException> { InviteCode.fromEntropy(ByteArray(15)) }
        assertThrows<IllegalArgumentException> { InviteCode.fromEntropy(ByteArray(17)) }
    }

    @Test
    fun `저장된 초대 코드는 22자 base64url 형식만 복원한다`() {
        assertEquals("abcdefghijklmnopqrstuv", InviteCode.of("abcdefghijklmnopqrstuv").value)
        assertThrows<IllegalArgumentException> { InviteCode.of("abcdefghijklmnopqrstu") }
        assertThrows<IllegalArgumentException> { InviteCode.of("abcdefghijklmnopqrstu+") }
        assertThrows<IllegalArgumentException> { InviteCode.of("abcdefghijklmnopqrstu=") }
    }
}
