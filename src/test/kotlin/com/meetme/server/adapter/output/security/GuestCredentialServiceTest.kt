package com.meetme.server.adapter.output.security

import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GuestCredentialServiceTest {
    @Test
    fun `발급 자격 증명은 32바이트 난수를 base64url padding 없이 인코딩한다`() {
        val entropy = ByteArray(32) { it.toByte() }
        val service = GuestCredentialService(FixedSecureRandom(entropy))

        val issued = service.issue(CREATED_AT)

        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy), issued.rawCredential)
        assertEquals(43, issued.rawCredential.length)
    }

    @Test
    fun `digest는 SHA-256 결과를 base64url padding 없이 인코딩한다`() {
        val service = GuestCredentialService(FixedSecureRandom(ByteArray(32)))

        val digest = service.digest("credential")

        assertEquals("4mW29WRgGh_o3EJ4XNGKhovYAT61iZVg55JIdnpoPms", digest)
        assertEquals(43, digest.length)
        assertNotEquals("credential", digest)
    }

    @Test
    fun `발급 결과의 digest는 발급 원문에서 계산한다`() {
        val service = GuestCredentialService(FixedSecureRandom(ByteArray(32) { 7 }))

        val issued = service.issue(CREATED_AT)

        assertEquals(service.digest(issued.rawCredential), issued.credentialDigest)
    }

    @Test
    fun `게스트 자격 증명은 발급 시각부터 고정 30일 후 만료한다`() {
        val service = GuestCredentialService(FixedSecureRandom(ByteArray(32)))

        val issued = service.issue(CREATED_AT)

        assertEquals(CREATED_AT.plus(Duration.ofDays(30)), issued.expiresAt)
    }

    private class FixedSecureRandom(
        private val entropy: ByteArray,
    ) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            require(bytes.size == entropy.size)
            entropy.copyInto(bytes)
        }
    }

    companion object {
        private val CREATED_AT = Instant.parse("2026-09-19T00:00:00Z")
    }
}
