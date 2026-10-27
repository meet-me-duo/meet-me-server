package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Independent contract tests derived from Issue #108 decisions, not implementation internals. */
class GuestPasswordHasherTest {
    private val passwords: GuestPasswordPort = GuestPasswordHasher()

    @Test
    fun `P01 hash는 확정한 PBKDF2 SHA256 cost와 salt 및 key 길이를 기록한다`() {
        val hash = passwords.hash("format-contract-108")
        val parts = hash.split('$')

        assertEquals(4, parts.size)
        assertEquals("pbkdf2-sha256", parts[0])
        assertEquals("600000", parts[1])
        assertEquals(16, Base64.getUrlDecoder().decode(parts[2]).size)
        assertEquals(32, Base64.getUrlDecoder().decode(parts[3]).size)
        assertFalse(parts[2].contains('='))
        assertFalse(parts[3].contains('='))
    }

    @Test
    fun `P01 독립 Python 표준 라이브러리에서 생성한 PBKDF2 fixture를 검증한다`() {
        // hashlib.pbkdf2_hmac('sha256', b'guest-fixture-108', bytes(range(16)), 600000, 32)
        val hash =
            "pbkdf2-sha256\$600000\$AAECAwQFBgcICQoLDA0ODw\$agZUcgqCkft8JUIPoKwZO64L0Vg22R7P1PkrI1R22MY"

        assertTrue(passwords.verify("guest-fixture-108", hash))
        assertFalse(passwords.verify("guest-fixture-109", hash))
    }

    @Test
    fun `P04 변경된 cost와 salt 및 key 길이는 검증 전에 거부한다`() {
        val salt = "AAECAwQFBgcICQoLDA0ODw"
        val key = "agZUcgqCkft8JUIPoKwZO64L0Vg22R7P1PkrI1R22MY"
        val invalidHashes =
            listOf(
                "pbkdf2-sha256\$1\$$salt\$$key",
                "pbkdf2-sha256\$600001\$$salt\$$key",
                "pbkdf2-sha256\$2147483647\$$salt\$$key",
                "pbkdf2-sha256\$600000\$AA\$$key",
                "pbkdf2-sha256\$600000\$$salt\$AA",
            )

        invalidHashes.forEach { invalidHash ->
            assertFalse(passwords.verify("guest-fixture-108", invalidHash))
        }
    }

    @Test
    fun `P01 같은 암호도 무작위 salt로 서로 다른 해시를 만들고 각각 검증한다`() {
        val rawPassword = "동일한 암호 108"

        val first = passwords.hash(rawPassword)
        val second = passwords.hash(rawPassword)

        assertNotEquals(rawPassword, first)
        assertNotEquals(rawPassword, second)
        assertNotEquals(first, second)
        assertFalse(first.contains(rawPassword))
        assertFalse(second.contains(rawPassword))
        assertTrue(passwords.verify(rawPassword, first))
        assertTrue(passwords.verify(rawPassword, second))
    }

    @Test
    fun `P02 한 글자 다른 암호와 빈 암호는 증명으로 인정하지 않는다`() {
        val hash = passwords.hash("Only-correct-108")

        assertTrue(passwords.verify("Only-correct-108", hash))
        assertFalse(passwords.verify("Only-correct-109", hash))
        assertFalse(passwords.verify("", hash))
    }

    @Test
    fun `P03 암호 앞뒤 공백을 보존하고 trim한 입력을 거부한다`() {
        val rawPassword = "  Guest secret\t "
        val hash = passwords.hash(rawPassword)

        assertTrue(passwords.verify(rawPassword, hash))
        assertFalse(passwords.verify(rawPassword.trim(), hash))
        assertFalse(passwords.verify(rawPassword.trimStart(), hash))
        assertFalse(passwords.verify(rawPassword.trimEnd(), hash))
    }

    @Test
    fun `P03 대소문자와 정규화 형태가 다른 Unicode는 다른 암호다`() {
        val hash = passwords.hash("Guest-\u00e9-108")

        assertTrue(passwords.verify("Guest-\u00e9-108", hash))
        assertFalse(passwords.verify("guest-\u00e9-108", hash))
        assertFalse(passwords.verify("Guest-e\u0301-108", hash))
    }

    @Test
    fun `P04 평문과 빈 값과 잘못된 해시 형식은 인증에 성공하지 않는다`() {
        val rawPassword = "correct-password"
        val invalidHashes = listOf("", rawPassword, "invalid", "pbkdf2", "sha256:deadbeef")

        invalidHashes.forEach { invalidHash ->
            assertFalse(passwords.verify(rawPassword, invalidHash), "Malformed hash must deny access")
        }
    }

    @Test
    fun `P04 정상 해시가 절단되거나 변조되면 거부한다`() {
        val rawPassword = "protected-participant"
        val hash = passwords.hash(rawPassword)
        val mutatedIndex = hash.length - 8
        val replacement = if (hash[mutatedIndex] == 'A') 'B' else 'A'
        val mutated = hash.substring(0, mutatedIndex) + replacement + hash.substring(mutatedIndex + 1)

        assertFalse(passwords.verify(rawPassword, hash.take(hash.length / 2)))
        assertFalse(passwords.verify(rawPassword, mutated))
        assertFalse(passwords.verify(rawPassword, "unsupported-version:" + hash))
    }

    @Test
    fun `P07 선택 암호 없음은 null로 전달하며 빈 문자열 hash는 거부한다`() {
        assertThrows<IllegalArgumentException> { passwords.hash("") }
    }

    @Test
    fun `P07 공백 하나도 길이 1인 암호로 보존한다`() {
        val hash = passwords.hash(" ")

        assertTrue(passwords.verify(" ", hash))
        assertFalse(passwords.verify("", hash))
        assertFalse(passwords.verify("  ", hash))
    }

    @Test
    fun `P07 Unicode 코드 포인트 128개까지 허용하고 긴 입력은 조용히 절단하지 않는다`() {
        val maximum = "😀".repeat(128)
        val hash = passwords.hash(maximum)

        assertTrue(passwords.verify(maximum, hash))
        assertFalse(passwords.verify(maximum + "x", hash))
        assertThrows<IllegalArgumentException> { passwords.hash(maximum + "x") }
    }
}
