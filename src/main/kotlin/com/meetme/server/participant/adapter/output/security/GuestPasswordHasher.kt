package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Component
class GuestPasswordHasher : GuestPasswordPort {
    private val random = SecureRandom()

    override fun hash(rawPassword: String): String {
        require(validPassword(rawPassword)) { "Invalid guest password" }
        val salt = ByteArray(16).also(random::nextBytes)
        val derived = derive(rawPassword, salt)
        return try {
            "pbkdf2-sha256\$600000\$${encode(salt)}\$${encode(derived)}"
        } finally {
            derived.fill(0)
        }
    }

    override fun verify(
        rawPassword: String,
        passwordHash: String,
    ): Boolean {
        if (!validPassword(rawPassword)) return false
        val match = HASH_FORMAT.matchEntire(passwordHash) ?: return false
        val salt = decode(match.groupValues[1]) ?: return false
        val expected = decode(match.groupValues[2]) ?: return false
        if (salt.size != 16 || expected.size != 32) return false
        val actual = derive(rawPassword, salt)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
        }
    }

    private fun validPassword(value: String): Boolean =
        value.codePointCount(0, value.length) in 1..128 &&
            value.codePoints().noneMatch { it in 0xD800..0xDFFF }

    private fun derive(
        password: String,
        salt: ByteArray,
    ): ByteArray {
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, salt, 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            characters.fill('\u0000')
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrNull()
            ?.takeIf { encode(it) == value }

    private companion object {
        val HASH_FORMAT = Regex("^pbkdf2-sha256\\\$600000\\\$([A-Za-z0-9_-]{22})\\\$([A-Za-z0-9_-]{43})$")
    }
}
