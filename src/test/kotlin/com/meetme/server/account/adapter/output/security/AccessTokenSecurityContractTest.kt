package com.meetme.server.account.adapter.output.security

import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// ADR-029/030: expectations derive from the service JWT public contract, independently of implementation.
class AccessTokenSecurityContractTest {
    @Test
    fun `RS256 token has kid exact fifteen minute lifetime and independently verifiable signature`() {
        val token = adapter(currentKey, "current").issue(PRINCIPAL)
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val header = mapper.readTree(decode(parts[0]))
        val claims = mapper.readTree(decode(parts[1]))
        assertEquals("RS256", header.path("alg").stringValue())
        assertEquals("current", header.path("kid").stringValue())
        assertEquals(PRINCIPAL.accountId.toString(), claims.path("sub").stringValue())
        assertEquals("https://api.synthetic.example", claims.path("iss").stringValue())
        assertEquals("meetme-web", claims.path("aud").stringValue())
        assertEquals(NOW.epochSecond, claims.path("iat").longValue())
        assertEquals(NOW.plusSeconds(900).epochSecond, claims.path("exp").longValue())
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(currentKey.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(UTF_8))
        assertEquals(true, verifier.verify(decode(parts[2])))
        assertEquals(PRINCIPAL.accountId, adapter(currentKey, "current").verify(token).accountId)
    }

    @Test
    fun `rotation accepts previous public key but signs with current kid`() {
        val old = adapter(oldKey, "previous").issue(PRINCIPAL)
        val rotated = adapter(currentKey, "current")
        assertEquals(PRINCIPAL.accountId, rotated.verify(old).accountId)
        assertNotEquals(old, rotated.issue(PRINCIPAL))
        val removed = adapter(currentKey, "current", keys = mapOf("current" to currentKey.public))
        assertFailsWith<AuthFailure> { removed.verify(old) }
    }

    @Test
    fun `expiration boundary rejects signed token at exp and accepts the preceding second`() {
        val token = adapter(currentKey, "current").issue(PRINCIPAL)
        assertEquals(PRINCIPAL.accountId, adapter(currentKey, "current", NOW.plusSeconds(899)).verify(token).accountId)
        assertFailsWith<AuthFailure> { adapter(currentKey, "current", NOW.plusSeconds(900)).verify(token) }
    }

    @Test
    fun `signature algorithm issuer audience kid and malformed tokens fail closed`() {
        val validClaims =
            "\"sub\":\"${PRINCIPAL.accountId}\",\"iss\":\"https://api.synthetic.example\"," +
                "\"aud\":\"meetme-web\",\"iat\":${NOW.epochSecond}," +
                "\"exp\":${NOW.plusSeconds(900).epochSecond},\"name\":\"Synthetic\""
        val invalid =
            listOf(
                signed("RS256", "current", validClaims, oldKey),
                signed("none", "current", validClaims),
                signed("HS256", "current", validClaims),
                signed("RS256", "unknown", validClaims),
                signed("RS256", "current", validClaims.replace("https://api.synthetic.example", "https://attacker.example")),
                signed("RS256", "current", validClaims.replace("meetme-web", "other-app")),
                signed(
                    "RS256",
                    "current",
                    validClaims.replace("\"exp\":${NOW.plusSeconds(900).epochSecond}", "\"exp\":${NOW.epochSecond}"),
                ),
                signed(
                    "RS256",
                    "current",
                    validClaims.replace("\"exp\":${NOW.plusSeconds(900).epochSecond}", "\"exp\":${NOW.plusSeconds(901).epochSecond}"),
                ),
                signed("RS256", "current", validClaims.replace("\"iat\":${NOW.epochSecond}", "\"iat\":${NOW.plusSeconds(60).epochSecond}")),
                signed("RS256", "current", "$validClaims,\"nbf\":${NOW.plusSeconds(60).epochSecond}"),
                "not-a-jwt",
                "e30.e30.",
            )
        for (token in invalid) assertFailsWith<AuthFailure> { adapter(currentKey, "current").verify(token) }
    }

    private fun adapter(
        key: KeyPair,
        kid: String,
        instant: Instant = NOW,
        keys: Map<String, java.security.PublicKey> = mapOf("current" to currentKey.public, "previous" to oldKey.public),
    ) = Rs256AccessTokenAdapter(key.private, keys, kid, Clock.fixed(instant, ZoneOffset.UTC), "https://api.synthetic.example", "meetme-web")

    private fun signed(
        algorithm: String,
        kid: String,
        claims: String,
        key: KeyPair = currentKey,
    ): String {
        val input = "${encode("{\"alg\":\"$algorithm\",\"kid\":\"$kid\"}")}.${encode("{$claims}")}"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key.private)
        signer.update(input.toByteArray(UTF_8))
        return "$input.${Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())}"
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))

    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private val PRINCIPAL = AccountPrincipal(UUID.fromString("00000000-0000-0000-0000-000000000109"), "Synthetic")
        private val currentKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val oldKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val mapper = ObjectMapper()
    }
}
