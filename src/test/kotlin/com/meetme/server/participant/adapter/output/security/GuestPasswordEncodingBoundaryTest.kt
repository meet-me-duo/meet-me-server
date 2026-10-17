package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent encoding contracts: malformed UTF-16 and noncanonical hashes cannot prove ownership. */
class GuestPasswordEncodingBoundaryTest {
    private val passwords: GuestPasswordPort = GuestPasswordHasher()

    @Test
    fun `P08 hash는 단독 high 및 low surrogate와 잘못된 surrogate 배열을 거부한다`() {
        malformedPasswords.forEach { rawPassword ->
            assertThrows<IllegalArgumentException> { passwords.hash(rawPassword) }
        }
    }

    @Test
    fun `P08 verify는 단독 surrogate를 UTF8 대체 문자 암호로 취급하지 않는다`() {
        val questionMarkHash = passwords.hash("?")
        val replacementCharacterHash = passwords.hash("\uFFFD")

        assertTrue(passwords.verify("?", questionMarkHash))
        assertTrue(passwords.verify("\uFFFD", replacementCharacterHash))
        malformedPasswords.forEach { rawPassword ->
            assertFalse(passwords.verify(rawPassword, questionMarkHash))
            assertFalse(passwords.verify(rawPassword, replacementCharacterHash))
            assertFalse(passwords.verify(rawPassword, fixtureHash()))
        }
    }

    @Test
    fun `P09 독립 Python fixture의 정규 Base64url 무패딩 해시는 허용한다`() {
        // hashlib.pbkdf2_hmac('sha256', b'encoding-boundary-108', bytes([251, 255]) * 8, 600000, 32)
        assertTrue(passwords.verify(FIXTURE_PASSWORD, fixtureHash()))
    }

    @Test
    fun `P09 salt와 key에 패딩이 추가된 해시는 같은 바이트여도 거부한다`() {
        assertContentEquals(Base64.getUrlDecoder().decode(SALT), Base64.getUrlDecoder().decode("$SALT=="))
        assertContentEquals(Base64.getUrlDecoder().decode(KEY), Base64.getUrlDecoder().decode("$KEY="))

        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(salt = "$SALT==")))
        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(key = "$KEY=")))
    }

    @Test
    fun `P09 표준 Base64 alphabet 별칭과 잘못된 문자는 거부한다`() {
        val standardAlphabetSalt = SALT.replace('-', '+').replace('_', '/')
        assertContentEquals(Base64.getUrlDecoder().decode(SALT), Base64.getDecoder().decode(standardAlphabetSalt))

        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(salt = standardAlphabetSalt)))
        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(salt = SALT.dropLast(1) + "!")))
        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(key = KEY.dropLast(1) + "!")))
        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(salt = " $SALT")))
        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(key = "$KEY\n")))
    }

    @Test
    fun `P09 salt의 사용하지 않는 trailing bits가 다르면 같은 decoded bytes여도 거부한다`() {
        val noncanonicalSalt = SALT.dropLast(1) + "x"
        assertContentEquals(Base64.getUrlDecoder().decode(SALT), Base64.getUrlDecoder().decode(noncanonicalSalt))

        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(salt = noncanonicalSalt)))
    }

    @Test
    fun `P09 key의 사용하지 않는 trailing bits가 다르면 같은 decoded bytes여도 거부한다`() {
        val noncanonicalKey = KEY.dropLast(1) + "l"
        assertContentEquals(Base64.getUrlDecoder().decode(KEY), Base64.getUrlDecoder().decode(noncanonicalKey))

        assertFalse(passwords.verify(FIXTURE_PASSWORD, fixtureHash(key = noncanonicalKey)))
    }

    private fun fixtureHash(
        salt: String = SALT,
        key: String = KEY,
    ): String = listOf("pbkdf2-sha256", "600000", salt, key).joinToString("$")

    companion object {
        private const val FIXTURE_PASSWORD = "encoding-boundary-108"
        private const val SALT = "-__7__v_-__7__v_-__7_w"
        private const val KEY = "kMAbuaD5Il1LA9jAMvX5hZHklwWsQiyByXLXd2Hu28k"
        private val malformedPasswords =
            listOf(
                "\uD800",
                "\uDC00",
                "\uD800\uD800",
                "\uDC00\uD800",
                "\uDC00\uDC00",
                "prefix\uD800",
                "\uDC00suffix",
                "a\uD800b",
                "a\uDC00b",
                "\uD83D\uDE00\uD800",
            )
    }
}
